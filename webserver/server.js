const express = require('express');
const cors = require('cors');
const { spawn, execFile } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');
const net = require('net');
const dns = require('dns');
const http = require('http');
const https = require('https');

const app = express();
const PORT = process.env.PORT || 3000;
// The server listens on every network, but other devices are turned away (403) until you press
// "Start sharing" in Settings. Start with HOST=127.0.0.1 to make it strictly this-computer-only.
const HOST = process.env.HOST || '0.0.0.0';

let sharingEnabled = false;   // always off when the server starts

function isLoopback(addr) {
  const a = String(addr || '');
  return a === '::1' || a.startsWith('127.') || a.startsWith('::ffff:127.');
}

// First thing every request meets: is it from this computer, or from another device?
app.use((req, res, next) => {
  req.isRemote = !isLoopback(req.socket.remoteAddress);
  if (req.isRemote && !sharingEnabled) {
    return res.status(403).type('text/plain')
      .send('Sharing is off. Turn it on in Settings on the computer that runs DownloaderHub.');
  }
  next();
});

// ---------------------------------------------------------------------------
// yt-dlp command. Override with env vars when packaging (e.g. on Android):
//   YT_DLP_CMD="yt-dlp"            or  YT_DLP_CMD="python3 -m yt_dlp"
//   YT_DLP_JS_RUNTIME=none         (skip --js-runtimes) or a runtime name, default "node"
//   MAX_JOBS=3                     max yt-dlp processes running at once
// ---------------------------------------------------------------------------
const YT_DLP_PARTS = (process.env.YT_DLP_CMD || 'python -m yt_dlp').trim().split(/\s+/);
const JS_RUNTIME = process.env.YT_DLP_JS_RUNTIME === undefined ? 'node' : process.env.YT_DLP_JS_RUNTIME.trim();
const YT_DLP = [
  ...YT_DLP_PARTS,
  ...(JS_RUNTIME && JS_RUNTIME.toLowerCase() !== 'none' ? ['--js-runtimes', JS_RUNTIME] : [])
];
const MAX_JOBS = Math.max(1, parseInt(process.env.MAX_JOBS, 10) || 3);

// ---------------------------------------------------------------------------
// CORS: only this device's own pages (browser on localhost, Android WebView).
// Add more origins with CORS_ORIGINS="https://a.example,https://b.example".
// ---------------------------------------------------------------------------
const EXTRA_ORIGINS = (process.env.CORS_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
const LOCAL_ORIGIN = /^(https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(:\d+)?|https:\/\/appassets\.androidplatform\.net|capacitor:\/\/localhost|null)$/;
app.use(cors({
  origin: (origin, cb) => cb(null, !origin || LOCAL_ORIGIN.test(origin) || EXTRA_ORIGINS.includes(origin))
}));
app.use(express.json({ limit: '100kb' }));
app.use(express.urlencoded({ extended: true, limit: '100kb' }));

// Only the app's own files are public. (Serving __dirname would expose server.js and more.)
const STATIC_FILES = {
  '/': 'index.html',
  '/index.html': 'index.html',
  '/Style.css': 'Style.css'
};
app.get(Object.keys(STATIC_FILES), (req, res) => {
  res.sendFile(path.join(__dirname, STATIC_FILES[req.path]));
});

// ---- Share on the local network (controlled by the button in Settings) ----
function lanUrls() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const n of nets[name] || []) {
      if ((n.family === 'IPv4' || n.family === 4) && !n.internal) out.push(`http://${n.address}:${PORT}`);
    }
  }
  return out;
}

function shareState(req) {
  const strict = HOST === '127.0.0.1' || HOST === 'localhost' || HOST === '::1';
  return {
    enabled: sharingEnabled,
    canToggle: !req.isRemote,     // only the computer running the server can switch it
    canShare: !strict,            // false when the server was started with HOST=127.0.0.1
    port: Number(PORT),
    urls: sharingEnabled ? lanUrls() : []
  };
}

app.get('/api/share', (req, res) => res.json(shareState(req)));

app.post('/api/share', (req, res) => {
  if (req.isRemote) return res.status(403).json({ error: 'Only the computer running the server can change sharing.' });
  sharingEnabled = !!(req.body && req.body.enabled);
  console.log(`[share] sharing is now ${sharingEnabled ? 'ON' : 'OFF'}`);
  res.json(shareState(req));
});

// ===========================================================================
// Helpers
// ===========================================================================

function sanitizeFilename(name) {
  // Removes characters that are illegal in Windows/Linux file paths, and strips newlines
  return String(name).replace(/[<>:"/\\|?*\n\r]/g, '_').substring(0, 200);
}

// Accepts only real http(s) links. Anything else (like "--exec ...") is rejected,
// so it can never be read by yt-dlp as an option.
function cleanUrl(u) {
  if (typeof u !== 'string') return null;
  const s = u.trim();
  if (!s || s.length > 2000) return null;
  try {
    const p = new URL(s);
    return (p.protocol === 'http:' || p.protocol === 'https:') ? p.href : null;
  } catch (_) { return null; }
}

// For requests from other devices only: refuse links that point at this computer or its private network
async function remoteUrlBlocked(req, url) {
  if (!req.isRemote) return false;
  try {
    const host = new URL(url).hostname.replace(/^\[|\]$/g, '');
    if (net.isIP(host)) return isPrivateAddress(host);
    const addrs = await dns.promises.lookup(host, { all: true });
    return addrs.some(a => isPrivateAddress(a.address));
  } catch (_) { return true; }   // can't tell: refuse
}
const REMOTE_BLOCKED_MSG = { error: 'That address is not allowed from another device.' };

function lastLine(s) {
  return String(s || '').split('\n').filter(l => l.trim()).pop() || '';
}

function classifyYtError(msg) {
  const m = String(msg || '').toLowerCase();
  if (/private video|this video is private|private account|account is private/.test(m)) return 'private';
  if (/not available in your country|blocked it in your country|geo.?restrict|not made this video available in your country/.test(m)) return 'region';
  if (/video unavailable|has been removed|removed by the uploader|no longer available|isn't available anymore|has been terminated|does not exist|been deleted|post unavailable|content isn't available|page isn't available|http error 404/.test(m)) return 'removed';
  if (/sign in to confirm your age|age.?restricted|confirm your age/.test(m)) return 'restricted';
  return 'unknown';
}

// ---- Limit how many yt-dlp processes run at once (a phone can't take a pile of them) ----
let activeJobs = 0;
const jobQueue = [];

function acquireSlot() {
  return new Promise((resolve) => {
    const grant = () => {
      activeJobs++;
      let released = false;
      resolve(() => {
        if (released) return;
        released = true;
        activeJobs--;
        const next = jobQueue.shift();
        if (next) next();
      });
    };
    if (activeJobs < MAX_JOBS) grant(); else jobQueue.push(grant);
  });
}

// Runs yt-dlp and resolves with stdout. Rejects with an Error whose message is the last stderr line.
async function ytExec(args, { timeout = 45000 } = {}) {
  const release = await acquireSlot();
  return new Promise((resolve, reject) => {
    execFile(YT_DLP[0], [...YT_DLP.slice(1), ...args],
      { timeout, maxBuffer: 20 * 1024 * 1024 },
      (err, stdout, stderr) => {
        release();
        if (err) return reject(new Error(lastLine(stderr) || err.message));
        resolve(stdout);
      });
  });
}

// ===========================================================================
// Stream URL cache
// ===========================================================================

const STREAM_URL_TTL_MS = 30 * 60 * 1000;
const STREAM_URL_MAX = 300;
const STREAM_URL_CACHE = new Map(); // key -> { url, headers, expires }
const STREAM_URL_INFLIGHT = new Map(); // key -> Promise (so parallel requests share one yt-dlp run)

function cacheSet(key, entry) {
  STREAM_URL_CACHE.delete(key);
  STREAM_URL_CACHE.set(key, entry);
  while (STREAM_URL_CACHE.size > STREAM_URL_MAX) {
    STREAM_URL_CACHE.delete(STREAM_URL_CACHE.keys().next().value); // oldest first
  }
}

setInterval(() => {
  const now = Date.now();
  for (const [k, v] of STREAM_URL_CACHE) if (v.expires <= now) STREAM_URL_CACHE.delete(k);
}, 5 * 60 * 1000).unref();

async function resolveStreamUrl(cacheKey, pageUrl, formatSelector) {
  const cached = STREAM_URL_CACHE.get(cacheKey);
  if (cached && Date.now() < cached.expires) return cached;
  if (STREAM_URL_INFLIGHT.has(cacheKey)) return STREAM_URL_INFLIGHT.get(cacheKey);

  const job = (async () => {
    const stdout = await ytExec(
      ['-j', '--no-playlist', '--no-warnings', '-f', formatSelector, '--', pageUrl],
      { timeout: 45000 }
    );
    let data;
    try { data = JSON.parse(stdout); }
    catch { throw new Error('Could not parse stream info'); }
    if (!data.url) throw new Error('No stream URL returned');

    const entry = {
      url: data.url,
      headers: data.http_headers || {},
      expires: Date.now() + STREAM_URL_TTL_MS
    };
    cacheSet(cacheKey, entry);
    return entry;
  })();

  STREAM_URL_INFLIGHT.set(cacheKey, job);
  try { return await job; }
  finally { STREAM_URL_INFLIGHT.delete(cacheKey); }
}

function proxyStream(targetUrl, reqHeaders, res, contentTypeHint, depth = 0) {
  if (depth > 3) { if (!res.headersSent) res.status(502).json({ error: 'Too many redirects' }); return; }

  let proxyReq;
  try {
    const client = targetUrl.startsWith('https') ? https : http;
    proxyReq = client.get(targetUrl, { headers: reqHeaders }, (upstream) => {
      // Follow redirects
      if ([301, 302, 303, 307, 308].includes(upstream.statusCode) && upstream.headers.location) {
        upstream.resume();
        let next;
        try { next = new URL(upstream.headers.location, targetUrl).href; }
        catch (_) { if (!res.headersSent) res.status(502).json({ error: 'Bad redirect' }); return; }
        return proxyStream(next, reqHeaders, res, contentTypeHint, depth + 1);
      }

      res.status(upstream.statusCode || 200);
      for (const k of ['content-type', 'content-length', 'content-range',
                       'accept-ranges', 'cache-control', 'last-modified', 'etag']) {
        if (upstream.headers[k]) res.setHeader(k, upstream.headers[k]);
      }
      if (!res.getHeader('content-type') && contentTypeHint) {
        res.setHeader('Content-Type', contentTypeHint);
      }

      upstream.pipe(res);
      upstream.on('error', () => { try { res.destroy(); } catch (_) {} });
    });
  } catch (err) {
    console.error('[proxy] error:', err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Failed to proxy stream' });
    return;
  }

  // Stop the upstream request as soon as the player goes away
  res.on('close', () => { try { proxyReq.destroy(); } catch (_) {} });

  proxyReq.on('error', (err) => {
    console.error('[proxy] error:', err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Failed to proxy stream' });
    else res.destroy();
  });
}

// ---- Cache-warming helpers: pick a format matching what /api/preview or
//      /api/stream would pick, so the next request is a cache hit. ----

function pickPreviewFormat(formats) {
  const combined = (formats || []).filter(f =>
    f.vcodec !== 'none' && f.acodec !== 'none' && f.ext === 'mp4'
    && f.height && f.height <= 480 && f.url);
  if (combined.length) return combined.sort((a, b) => b.height - a.height)[0];

  const anyCombined = (formats || []).filter(f =>
    f.vcodec !== 'none' && f.acodec !== 'none' && f.height && f.height <= 480 && f.url);
  if (anyCombined.length) return anyCombined.sort((a, b) => b.height - a.height)[0];
  return null;
}

function pickAudioFormat(formats) {
  const audioOnly = (formats || []).filter(f =>
    f.vcodec === 'none' && f.acodec && f.acodec !== 'none' && f.url);
  const m4a = audioOnly.find(f => f.ext === 'm4a');
  if (m4a) return m4a;
  if (audioOnly.length) {
    return audioOnly.sort((a, b) => (b.abr || b.tbr || 0) - (a.abr || a.tbr || 0))[0];
  }
  return (formats || []).find(f => f.acodec !== 'none' && f.url) || null;
}

// ===========================================================================
// Music search
// ===========================================================================

// Searches YouTube Music (not general YouTube) so results are official tracks/albums
// rather than random covers, reaction videos, or 10-hour loop uploads.
app.post('/api/music/search', async (req, res) => {
  const query = String((req.body && req.body.query) || '').trim().slice(0, 200);
  if (!query) return res.status(400).json({ error: 'Search query is required' });

  const searchUrl = `https://music.youtube.com/search?q=${encodeURIComponent(query)}`;

  try {
    const stdout = await ytExec(
      ['--dump-single-json', '--flat-playlist', '--no-warnings', '--', searchUrl],
      { timeout: 30000 }
    );
    let d;
    try { d = JSON.parse(stdout); }
    catch (e) {
      console.error('[music search parse error]', e.message, '\nRaw stdout was:', stdout.slice(0, 500));
      return res.status(500).json({ error: 'Failed to parse search results.' });
    }
    const results = (d.entries || [])
      .filter(e => e.ie_key === 'Youtube' && e.id)
      .slice(0, 12)
      .map(e => ({
        id: e.id,
        title: e.title || 'Untitled',
        artist: e.artist || e.uploader || e.channel || 'YouTube Music',
        duration: e.duration || null,
        thumbnail: `https://i.ytimg.com/vi/${e.id}/hqdefault.jpg`
      }));
    res.json({ results });
  } catch (err) {
    console.error('[music search failed]', err.message);
    res.status(400).json({ error: err.message });
  }
});

// ===========================================================================
// Info (with stream-cache warming)
// ===========================================================================

app.post('/api/info', async (req, res) => {
  const raw = req.body && req.body.url;
  if (!raw) return res.status(400).json({ error: 'URL is required' });
  const url = cleanUrl(raw);
  if (!url) return res.status(400).json({ error: 'That is not a valid http(s) link' });
  if (await remoteUrlBlocked(req, url)) return res.status(400).json(REMOTE_BLOCKED_MSG);

  let stdout;
  try {
    stdout = await ytExec(['--dump-single-json', '--no-download', '--flat-playlist', '--', url], { timeout: 45000 });
  } catch (err) {
    return res.status(400).json({ error: err.message, reason: classifyYtError(err.message) });
  }

  try {
    const d = JSON.parse(stdout);

    if (d._type === 'playlist' || d.entries) {
      const entries = (d.entries || [])
        .filter(e => e.url || e.original_url || e.webpage_url || e.id)
        .map(e => ({
          id: e.id,
          title: e.title || 'Untitled',
          url: e.url || e.original_url || e.webpage_url || `https://www.youtube.com/watch?v=${e.id}`,
          duration: e.duration || null
        }));

      return res.json({
        isPlaylist: true,
        title: d.title || 'Playlist',
        thumbnail: d.thumbnails && d.thumbnails.length ? d.thumbnails[0].url : '',
        entries: entries
      });
    }

    // Warm the stream URL cache so the next /api/stream or /api/preview
    // for this exact video is a cache hit (no second yt-dlp run).
    try {
      const isMusic = /music\.youtube\.com/.test(url);
      if (isMusic) {
        const f = pickAudioFormat(d.formats);
        if (f && f.url) {
          cacheSet('a:' + d.id, {
            url: f.url,
            headers: f.http_headers || {},
            expires: Date.now() + STREAM_URL_TTL_MS
          });
        }
      } else {
        const f = pickPreviewFormat(d.formats);
        if (f && f.url) {
          cacheSet('v:' + url, {
            url: f.url,
            headers: f.http_headers || {},
            expires: Date.now() + STREAM_URL_TTL_MS
          });
        }
      }
    } catch (_) { /* warming is best-effort */ }

    res.json({
      isPlaylist: false,
      title: d.title || 'Untitled',
      artist: d.channel || d.uploader || null,
      thumbnail: d.thumbnail || '',
      duration: d.duration || null,
      resolution: d.resolution || (d.height ? `${d.height}p` : null),
      ext: d.ext || 'mp4',
      // Signed stream URLs stay on the server; the app never needs them.
      formats: (d.formats || []).map(f => ({
        format_id: f.format_id,
        ext: f.ext,
        resolution: f.height ? `${f.height}p` : 'audio',
        filesize: f.filesize || f.filesize_approx || 0,
        vcodec: f.vcodec,
        acodec: f.acodec
      }))
    });
  } catch (e) {
    res.status(500).json({ error: 'Failed to parse media details.' });
  }
});

// ===========================================================================
// Quick "is this link still alive?" check used by Collections
// ===========================================================================

app.post('/api/check', async (req, res) => {
  const raw = req.body && req.body.url;
  if (!raw) return res.status(400).json({ error: 'URL is required' });
  const url = cleanUrl(raw);
  if (!url) return res.status(400).json({ error: 'That is not a valid http(s) link' });
  if (await remoteUrlBlocked(req, url)) return res.status(400).json(REMOTE_BLOCKED_MSG);

  try {
    const stdout = await ytExec(
      ['--dump-single-json', '--no-download', '--flat-playlist', '--no-warnings', '--', url],
      { timeout: 30000 }
    );
    try {
      const d = JSON.parse(stdout);
      res.json({ status: 'ok', title: d.title || null, thumbnail: d.thumbnail || null });
    } catch (_) {
      res.json({ status: 'unknown' });
    }
  } catch (err) {
    res.json({ status: classifyYtError(err.message) });
  }
});

// ===========================================================================
// Thumbnail proxy: lets the app keep a small copy of a thumbnail inside a
// Collection item, so it still shows after the video is deleted.
// Only fetches real images from the public internet (private addresses blocked).
// ===========================================================================

const MAX_THUMB_BYTES = 2 * 1024 * 1024;

function isPrivateAddress(addr) {
  let a = String(addr || '');
  const mapped = a.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/i);
  if (mapped) a = mapped[1];
  if (net.isIPv4(a)) {
    const [p, q] = a.split('.').map(Number);
    return p === 0 || p === 10 || p === 127 || p >= 224
      || (p === 169 && q === 254)
      || (p === 172 && q >= 16 && q <= 31)
      || (p === 192 && q === 168)
      || (p === 100 && q >= 64 && q <= 127);
  }
  if (net.isIPv6(a)) {
    const l = a.toLowerCase();
    return l === '::' || l === '::1' || l.startsWith('fc') || l.startsWith('fd') || /^fe[89ab]/.test(l);
  }
  return true; // not a valid IP: refuse
}

// Checks the real address at connect time, which also covers DNS tricks.
function safeLookup(hostname, options, cb) {
  dns.lookup(hostname, options, (err, address, family) => {
    if (err) return cb(err);
    const list = Array.isArray(address) ? address : [{ address, family }];
    if (list.some(x => isPrivateAddress(x.address))) return cb(new Error('Blocked address'));
    cb(null, address, family);
  });
}

function fetchImage(targetUrl, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 3) return reject(new Error('Too many redirects'));
    let u;
    try { u = new URL(targetUrl); } catch (_) { return reject(new Error('Bad URL')); }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return reject(new Error('Bad URL'));
    const host = u.hostname.replace(/^\[|\]$/g, '');
    if (net.isIP(host) && isPrivateAddress(host)) return reject(new Error('Blocked address'));

    const client = u.protocol === 'https:' ? https : http;
    const req = client.get(u, { lookup: safeLookup, timeout: 10000, headers: { 'User-Agent': 'Mozilla/5.0' } }, (up) => {
      if ([301, 302, 303, 307, 308].includes(up.statusCode) && up.headers.location) {
        up.resume();
        let next;
        try { next = new URL(up.headers.location, u).href; } catch (_) { return reject(new Error('Bad redirect')); }
        return resolve(fetchImage(next, depth + 1));
      }
      const type = String(up.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
      if (up.statusCode !== 200 || !/^image\/(jpeg|png|webp|gif|avif)$/.test(type)) {
        up.resume();
        return reject(new Error('Not an image'));
      }
      const chunks = [];
      let size = 0;
      up.on('data', (c) => {
        size += c.length;
        if (size > MAX_THUMB_BYTES) { req.destroy(); reject(new Error('Image too large')); }
        else chunks.push(c);
      });
      up.on('end', () => resolve({ buf: Buffer.concat(chunks), type }));
      up.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('Timeout')));
    req.on('error', reject);
  });
}

app.get('/api/thumb', async (req, res) => {
  const url = cleanUrl(req.query.url);
  if (!url) return res.status(400).json({ error: 'Invalid url' });
  try {
    const { buf, type } = await fetchImage(url);
    res.setHeader('Content-Type', type);
    res.setHeader('Cache-Control', 'private, max-age=86400');
    res.end(buf);
  } catch (err) {
    res.status(502).json({ error: 'Could not load thumbnail' });
  }
});

// ===========================================================================
// Download. yt-dlp (and ffmpeg) work inside a private temp folder that is
// always removed when the request ends, even if the app cancels halfway.
// ===========================================================================

app.post('/api/download', async (req, res) => {
  const body = req.body || {};
  if (!body.url) return res.status(400).json({ error: 'URL is required' });
  const url = cleanUrl(body.url);
  if (!url) return res.status(400).json({ error: 'That is not a valid http(s) link' });
  if (await remoteUrlBlocked(req, url)) return res.status(400).json(REMOTE_BLOCKED_MSG);

  const type = body.type === 'audio' ? 'audio' : 'video';
  const fid = body.format_id ? String(body.format_id) : '';
  if (fid && !/^[\w.+-]{1,40}$/.test(fid)) return res.status(400).json({ error: 'Invalid format' });

  const baseName = body.filename ? sanitizeFilename(body.filename) : 'download';
  const ext = type === 'audio' ? 'mp3' : 'mp4';
  const heightMatch = /^(\d+)p$/.exec(fid);

  let tmpDir = null;
  let proc = null;
  let clientGone = false;
  let responded = false;
  let release = () => {};

  const cleanup = () => {
    if (tmpDir) fs.rm(tmpDir, { recursive: true, force: true }, () => {});
  };
  const fail = (status, payload) => {
    if (responded || res.headersSent) return;
    responded = true;
    res.status(status).json(payload);
  };

  // Runs when the response finishes OR the app disconnects: stop yt-dlp, delete the temp folder.
  res.on('close', () => {
    clientGone = true;
    if (proc && proc.exitCode === null) { try { proc.kill('SIGTERM'); } catch (_) {} }
    release();
    cleanup();
  });

  release = await acquireSlot();
  if (clientGone) { release(); return; }

  try { tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dlhub-')); }
  catch (_) { release(); return fail(500, { error: 'Could not create a temporary folder.' }); }
  const tmpFile = path.join(tmpDir, `out.${ext}`);

  let args;
  if (type === 'audio') {
    args = ['-x', '--audio-format', 'mp3', '--audio-quality', '0', '--embed-metadata', '--embed-thumbnail', '-o', tmpFile];
  } else if (heightMatch) {
    const height = parseInt(heightMatch[1], 10);
    args = [
      '-f', `bv*[height<=${height}]+ba/b[height<=${height}]`,
      '-S', `res:${height},ext:mp4:m4a`,
      '--merge-output-format', 'mp4',
      '-o', tmpFile
    ];
  } else if (fid) {
    args = ['-f', `${fid}+ba/b`, '--merge-output-format', 'mp4', '-o', tmpFile];
  } else {
    args = ['-f', 'bv*+ba/b', '--merge-output-format', 'mp4', '-o', tmpFile];
  }
  args.push('--', url);

  console.log(`[download] type=${type} format_id=${fid || '(none)'} url=${url}`);

  proc = spawn(YT_DLP[0], [...YT_DLP.slice(1), ...args], {
    stdio: ['ignore', 'pipe', 'pipe'],
    timeout: 900000
  });

  let stderrBuf = '';
  proc.stderr.on('data', (chunk) => { stderrBuf += chunk.toString(); });

  proc.on('error', () => {
    release();
    fail(500, { error: 'Failed to start download process on server.' });
    cleanup();
  });

  proc.on('close', (code) => {
    release();
    if (responded || clientGone) return;

    if (code !== 0) {
      const msg = lastLine(stderrBuf) || 'Internal Download failure';
      fail(400, { error: msg, reason: classifyYtError(msg) });
      cleanup();
      return;
    }

    // Normally out.<ext>; fall back to any other finished output file
    let outFile = null;
    try {
      const files = fs.readdirSync(tmpDir).filter(f => f.startsWith('out.') && !/\.(part|ytdl|temp)$/.test(f));
      outFile = files.includes(`out.${ext}`) ? `out.${ext}` : files[0];
    } catch (_) {}
    if (!outFile) { fail(500, { error: 'The download finished but no file was produced.' }); cleanup(); return; }
    const outPath = path.join(tmpDir, outFile);

    const sendFile = (actualHeight) => {
      if (responded || clientGone) return;
      responded = true;

      let size = null;
      try { size = fs.statSync(outPath).size; } catch (_) {}

      const encodedFilename = encodeURIComponent(`${baseName}.${ext}`);
      res.setHeader('Content-Disposition', `attachment; filename="media.${ext}"; filename*=UTF-8''${encodedFilename}`);
      res.setHeader('Content-Type', type === 'audio' ? 'audio/mpeg' : 'video/mp4');
      if (size != null) res.setHeader('Content-Length', String(size));

      if (type === 'video') {
        const requestedHeight = heightMatch ? parseInt(heightMatch[1], 10) : null;
        res.setHeader('X-Actual-Height', actualHeight != null ? String(actualHeight) : 'unknown');
        if (requestedHeight != null) res.setHeader('X-Requested-Height', String(requestedHeight));
        res.setHeader('Access-Control-Expose-Headers', 'X-Actual-Height, X-Requested-Height');
      }

      const stream = fs.createReadStream(outPath);
      stream.on('error', () => { try { res.destroy(); } catch (_) {} });
      stream.pipe(res);
      // The temp folder is removed by res.on('close') above.
    };

    if (type === 'video') {
      execFile('ffprobe', [
        '-v', 'error',
        '-select_streams', 'v:0',
        '-show_entries', 'stream=height',
        '-of', 'csv=p=0',
        outPath
      ], (probeErr, stdout) => {
        const actualHeight = !probeErr ? parseInt(String(stdout).trim(), 10) : null;
        console.log(`[download] actual output height: ${actualHeight || 'unknown (ffprobe failed/missing)'}`);
        sendFile(Number.isFinite(actualHeight) ? actualHeight : null);
      });
    } else {
      sendFile(null);
    }
  });
});

// ===========================================================================
// Preview (video) — direct proxy, no pre-download, Range-aware
// ===========================================================================

app.get('/api/preview', async (req, res) => {
  const url = cleanUrl(req.query.url);
  if (!url) return res.status(400).json({ error: 'Missing or invalid url' });
  if (await remoteUrlBlocked(req, url)) return res.status(400).json(REMOTE_BLOCKED_MSG);

  try {
    const stream = await resolveStreamUrl(
      'v:' + url,
      url,
      'b[height<=480][ext=mp4]/b[height<=480]/b'
    );
    const headers = { ...stream.headers };
    if (req.headers.range) headers['Range'] = req.headers.range;
    proxyStream(stream.url, headers, res, 'video/mp4');
  } catch (err) {
    console.error('[preview] failed:', err.message);
    if (!res.headersSent) res.status(400).json({ error: err.message });
  }
});

// ===========================================================================
// Audio stream — direct proxy, no pre-download, Range-aware
// ===========================================================================

app.get('/api/stream', async (req, res) => {
  const id = String(req.query.id || '');
  if (!/^[\w-]{6,20}$/.test(id)) return res.status(400).json({ error: 'Invalid track id' });
  const pageUrl = `https://music.youtube.com/watch?v=${id}`;

  try {
    const stream = await resolveStreamUrl('a:' + id, pageUrl, 'ba[ext=m4a]/ba/b');
    const headers = { ...stream.headers };
    if (req.headers.range) headers['Range'] = req.headers.range;
    proxyStream(stream.url, headers, res, 'audio/mp4');
  } catch (err) {
    console.error('[stream] failed:', err.message);
    if (!res.headersSent) res.status(500).json({ error: err.message });
  }
});

app.listen(PORT, HOST, () => {
  console.log(`Server running at http://localhost:${PORT}`);
  console.log('Other devices are blocked until you press "Start sharing" in Settings > System.');
});
