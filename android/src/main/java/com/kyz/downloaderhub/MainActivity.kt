package com.kyz.downloaderhub

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

// Reads a text field; returns null when it is missing or JSON null
private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun errJson(message: String, reason: String? = null): JSONObject {
    val o = JSONObject().put("error", message)
    if (reason != null) o.put("reason", reason)
    return o
}

private fun lastLine(s: String?): String =
    (s ?: "").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""

private fun sanitizeFilename(name: String): String =
    name.replace(Regex("[<>:\"/\\\\|?*\\r\\n]"), "_").trim().take(200).ifEmpty { "download" }

// Only real http(s) links are accepted. Because it must start with "http",
// a link can never be read by yt-dlp as an option such as "--exec".
private fun cleanUrl(raw: String?): String? {
    val s = raw?.trim() ?: return null
    if (s.isEmpty() || s.length > 2000) return null
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) return null
    return try {
        val u = java.net.URL(s)
        if (u.host.isNullOrEmpty()) null else s
    } catch (e: Exception) {
        null
    }
}

private fun classifyYtError(msg: String?): String {
    val m = (msg ?: "").lowercase()
    return when {
        Regex("private video|this video is private|private account|account is private").containsMatchIn(m) -> "private"
        Regex("not available in your country|blocked it in your country|geo.?restrict|not made this video available in your country").containsMatchIn(m) -> "region"
        Regex("video unavailable|has been removed|removed by the uploader|no longer available|isn't available anymore|has been terminated|does not exist|been deleted|post unavailable|content isn't available|page isn't available|http error 404").containsMatchIn(m) -> "removed"
        Regex("sign in to confirm your age|age.?restricted|confirm your age").containsMatchIn(m) -> "restricted"
        else -> "unknown"
    }
}

// ---------------------------------------------------------------------------
// Thumbnail download helpers (used by /api/thumb)
// ---------------------------------------------------------------------------

private const val MAX_THUMB_BYTES = 2 * 1024 * 1024   // 2 MB

// True for addresses that belong to the phone itself or the home network.
// Thumbnails must only come from the public internet.
private fun isPrivateAddress(a: InetAddress): Boolean {
    if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
        a.isSiteLocalAddress || a.isMulticastAddress) return true
    val b = a.address
    if (b.size == 4) {
        val p = b[0].toInt() and 0xFF
        val q = b[1].toInt() and 0xFF
        return p == 0 || p >= 224 || (p == 100 && q in 64..127)
    }
    if (b.size == 16) return (b[0].toInt() and 0xFE) == 0xFC   // fc00::/7
    return true
}

// Downloads one small image safely. Throws an IOException if anything looks wrong.
private fun fetchImage(target: String, depth: Int = 0): Pair<ByteArray, String> {
    if (depth > 3) throw IOException("Too many redirects")
    val u = java.net.URL(target)
    if (u.protocol != "http" && u.protocol != "https") throw IOException("Bad URL")
    if (u.host.isNullOrEmpty()) throw IOException("Bad URL")
    for (addr in InetAddress.getAllByName(u.host)) {
        if (isPrivateAddress(addr)) throw IOException("Blocked address")
    }

    val conn = u.openConnection() as HttpURLConnection
    try {
        conn.instanceFollowRedirects = false          // we follow redirects ourselves, checking each one
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

        val code = conn.responseCode
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            val loc = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
            return fetchImage(java.net.URL(u, loc).toString(), depth + 1)
        }

        val type = (conn.contentType ?: "").substringBefore(';').trim().lowercase()
        if (code != 200 || !Regex("^image/(jpeg|png|webp|gif|avif)$").matches(type)) {
            throw IOException("Not an image")
        }

        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        conn.inputStream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_THUMB_BYTES) throw IOException("Image too large")
            }
        }
        return out.toByteArray() to type
    } finally {
        conn.disconnect()
    }
}

// ---------------------------------------------------------------------------
// Media helpers (video preview + music playback without a server)
// ---------------------------------------------------------------------------

private const val MEDIA_HOST = "dlhub.local"                 // virtual address the page uses for media
private const val STREAM_URL_TTL_MS = 30L * 60 * 1000       // direct stream links are reused for 30 minutes

private class StreamInfo(val url: String, val headers: Map<String, String>, val expires: Long)

private val streamCache = ConcurrentHashMap<String, StreamInfo>()

private fun cacheStream(key: String, url: String, headers: JSONObject?) {
    if (streamCache.size > 300) {
        val now = System.currentTimeMillis()
        streamCache.entries.removeAll { it.value.expires <= now }
        if (streamCache.size > 300) streamCache.clear()
    }
    val map = HashMap<String, String>()
    if (headers != null) {
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = headers.optString(k, "")
            if (v.isNotEmpty()) map[k] = v
        }
    }
    streamCache[key] = StreamInfo(url, map, System.currentTimeMillis() + STREAM_URL_TTL_MS)
}

private fun cacheStreamFromFormat(key: String, f: JSONObject) {
    val url = f.str("url") ?: return
    cacheStream(key, url, f.optJSONObject("http_headers"))
}

private fun formatList(formats: JSONArray): List<JSONObject> =
    (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }

// A small video that has picture AND sound (up to 480p), preferably mp4
private fun pickPreviewFormat(formats: JSONArray): JSONObject? {
    val combined = formatList(formats).filter {
        it.str("vcodec") != "none" && it.str("acodec") != "none" &&
                it.optInt("height", 0) in 1..480 && it.str("url") != null
    }
    return combined.filter { it.str("ext") == "mp4" }.maxByOrNull { it.optInt("height", 0) }
        ?: combined.maxByOrNull { it.optInt("height", 0) }
}

// Audio only, preferably m4a
private fun pickAudioFormat(formats: JSONArray): JSONObject? {
    val all = formatList(formats)
    val audioOnly = all.filter {
        it.str("vcodec") == "none" && it.str("acodec") != null &&
                it.str("acodec") != "none" && it.str("url") != null
    }
    audioOnly.firstOrNull { it.str("ext") == "m4a" }?.let { return it }
    if (audioOnly.isNotEmpty()) {
        return audioOnly.maxByOrNull {
            val abr = it.optDouble("abr", 0.0)
            if (abr > 0) abr else it.optDouble("tbr", 0.0)
        }
    }
    return all.firstOrNull { it.str("acodec") != "none" && it.str("url") != null }
}

// Saves the direct stream link found during /api/info, so playing needs no second yt-dlp run
private fun warmStreamCache(pageUrl: String, d: JSONObject) {
    try {
        val formats = d.optJSONArray("formats") ?: return
        if (pageUrl.contains("music.youtube.com")) {
            val id = d.str("id") ?: return
            val f = pickAudioFormat(formats) ?: return
            cacheStreamFromFormat("a:$id", f)
        } else {
            val f = pickPreviewFormat(formats) ?: return
            cacheStreamFromFormat("v:$pageUrl", f)
        }
    } catch (e: Exception) {
        // warming is best-effort
    }
}

private fun mediaError(code: Int, msg: String): WebResourceResponse =
    WebResourceResponse(
        "text/plain", "utf-8", code, "Error",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(msg.toByteArray())
    )

private fun openUpstream(s: StreamInfo, range: String?): HttpURLConnection {
    val u = java.net.URL(s.url)
    if (u.protocol != "http" && u.protocol != "https") throw IOException("Bad stream URL")
    if (u.host.isNullOrEmpty()) throw IOException("Bad stream URL")
    for (addr in InetAddress.getAllByName(u.host)) {
        if (isPrivateAddress(addr)) throw IOException("Blocked address")
    }
    val conn = u.openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 15000
    conn.readTimeout = 30000
    for ((k, v) in s.headers) {
        if (k.equals("Range", true) || k.equals("Host", true) || k.equals("Accept-Encoding", true)) continue
        conn.setRequestProperty(k, v)
    }
    conn.setRequestProperty("Accept-Encoding", "identity")   // keep Range and sizes exact
    if (range != null) conn.setRequestProperty("Range", range)
    return conn
}

// Throws if the link does not point to the public internet
private fun checkPublicHost(urlStr: String) {
    val u = java.net.URL(urlStr)
    if (u.protocol != "http" && u.protocol != "https") throw IOException("Bad stream URL")
    if (u.host.isNullOrEmpty()) throw IOException("Bad stream URL")
    for (addr in InetAddress.getAllByName(u.host)) {
        if (isPrivateAddress(addr)) throw IOException("Blocked address")
    }
}

// ---------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    private val prefs by lazy { getSharedPreferences("dh", MODE_PRIVATE) }

    // ---- Save location: any folder the person picks (works on phones where the default Downloads route fails) ----
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("save_tree", uri.toString()).apply()
            } catch (e: Exception) {
                Log.e("DLHUB", "folder permission failed", e)
            }
        }
        saveFolderEvent()
    }

    private fun saveFolderLabel(): String {
        val s = prefs.getString("save_tree", null) ?: return ""
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(s))
            id.substringAfter(':', id).ifEmpty { "Internal storage" }
        } catch (e: Exception) {
            ""
        }
    }

    private fun saveFolderEvent() {
        runOnUiThread {
            web.evaluateJavascript(
                "window.__saveFolderChanged && window.__saveFolderChanged(${JSONObject.quote(saveFolderLabel())})", null
            )
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private fun ask(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread { permissionLauncher.launch(permission) }
        }
    }

    private fun say(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---- yt-dlp start-up: runs once, and updates yt-dlp at most once a day ----
    private val initLock = Any()
    private var initialized = false

    // ---- Storage safety (low-storage phones such as Android Go clean cache folders and can leave odd files behind) ----

    // Folder for unfinished downloads. It is NOT the cache folder, so Android cannot clean it while we work.
    private fun workRoot(): File {
        val d = File(noBackupFilesDir, "dl-work")
        if (d.exists() && !d.isDirectory) d.delete()      // a stray FILE with this name would cause "Not a directory"
        d.mkdirs()
        return d
    }

    // Folder for yt-dlp's own cache (kept so repeat runs stay fast)
    private fun ytCacheDir(): File {
        val d = File(noBackupFilesDir, "yt-cache")
        if (d.exists() && !d.isDirectory) d.delete()
        d.mkdirs()
        return d
    }

    // Leftovers of crashed or cancelled downloads
    private fun sweepOldWork() {
        try {
            val cutoff = System.currentTimeMillis() - 60L * 60 * 1000
            workRoot().listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
        } catch (e: Exception) {
            Log.e("DLHUB", "sweep failed", e)
        }
    }

    // Free space the app can really use (counts clearable cache too); the larger of the two readings wins,
    // so a phone that reports one of them wrongly is not treated as full.
    private fun freeBytes(dir: File): Long {
        var best = dir.usableSpace
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                best = maxOf(best, sm.getAllocatableBytes(sm.getUuidForPath(dir)))
            } catch (e: Exception) {
                Log.e("DLHUB", "allocatable bytes failed", e)
            }
        }
        return best
    }

    private fun looksLikeStorageError(msg: String?): Boolean {
        val m = (msg ?: "").lowercase()
        return "not a directory" in m || "enotdir" in m || "errno 20" in m ||
                "no space left" in m || "enospc" in m || "errno 28" in m ||
                "no such file or directory" in m || "enoent" in m || "errno 2]" in m
    }

    // Runs yt-dlp. If it fails because of a storage problem, rebuilds the folders and tries once more.
    private fun ytRun(
        request: YoutubeDLRequest,
        processId: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): YoutubeDLResponse {
        request.addOption("--cache-dir", ytCacheDir().absolutePath)
        fun once(): YoutubeDLResponse =
            if (processId != null) {
                YoutubeDL.getInstance().execute(request, processId) { p, _, _ -> onProgress?.invoke(p) }
            } else {
                YoutubeDL.getInstance().execute(request)
            }
        return try {
            once()
        } catch (e: Exception) {
            if (processId != null && DownloadTracker.wasCancelled(processId)) throw e
            if (updating) {                                     // the update was swapping files under us: wait, then try once more
                var waited = 0
                while (updating && waited < 30000) { Thread.sleep(500); waited += 500 }
                return once()
            }
            if (!looksLikeStorageError(e.message)) throw e
            Log.e("DLHUB", "storage problem, rebuilding folders and retrying once", e)
            workRoot()
            ytCacheDir()
            once()
        }
    }

    @Volatile private var updating = false

    private fun ensureReady() {
        synchronized(initLock) {
            if (initialized) return
            YoutubeDL.getInstance().init(application)
            FFmpeg.getInstance().init(application)
            initialized = true
        }
        updateInBackground()           // never makes a request wait
    }

    // Updates yt-dlp at most once a day, on its own thread, so the first link or song is not held up for it
    private fun updateInBackground() {
        val prefs = getSharedPreferences("dh", MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_updated", 0L)
        if (System.currentTimeMillis() - last <= 24L * 60 * 60 * 1000) return
        thread(name = "ytdlp-update") {
            updating = true
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.STABLE)
                Log.d("DLHUB", "yt-dlp update: $status")
                prefs.edit().putLong("ytdlp_updated", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.e("DLHUB", "yt-dlp update failed (continuing)", e)
            } finally {
                updating = false
            }
        }
    }

    // ---- Sends a finished answer back to the page ----
    private fun reply(id: Int, status: Int, json: String) {
        web.post {
            web.evaluateJavascript("window.__nativeReply($id, $status, ${JSONObject.quote(json)})", null)
        }
    }

    // ---- Routes (the old Express server's job) ----
    private fun handleApi(path: String, bodyText: String): Pair<Int, JSONObject> {
        val route = path.substringBefore('?')
        val body = try { JSONObject(bodyText) } catch (e: Exception) { JSONObject() }
        return when (route) {
            "/api/info" -> apiInfo(body)
            "/api/download" -> apiDownload(body)
            "/api/check" -> apiCheck(body)
            "/api/thumb" -> apiThumb(path)
            "/api/music/search" -> apiMusicSearch(body)
            else -> 501 to errJson("This feature is not in the app yet.")
        }
    }

    private fun apiInfo(body: JSONObject): Pair<Int, JSONObject> {
        val raw = body.str("url") ?: return 400 to errJson("URL is required")
        val url = cleanUrl(raw) ?: return 400 to errJson("That is not a valid http(s) link")

        ensureReady()

        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-download")
        request.addOption("--flat-playlist")
        request.addOption("--no-warnings")
        if (isYouTube(url)) request.addOption("--extractor-args", "youtube:skip=hls,dash")

        val stdout = try {
            ytRun(request).out
        } catch (e: Exception) {
            val msg = lastLine(e.message).ifEmpty { "Could not read this link." }
            return 400 to errJson(msg, classifyYtError(e.message))
        }

        val d = try {
            JSONObject(stdout.trim())
        } catch (e: Exception) {
            return 500 to errJson("Failed to parse media details.")
        }

        // ---- Playlist ----
        val entriesArr = d.optJSONArray("entries")
        if (d.str("_type") == "playlist" || entriesArr != null) {
            val entries = JSONArray()
            if (entriesArr != null) {
                for (i in 0 until entriesArr.length()) {
                    val e = entriesArr.optJSONObject(i) ?: continue
                    val id = e.str("id")
                    val link = e.str("url") ?: e.str("original_url") ?: e.str("webpage_url")
                    ?: (if (id != null) "https://www.youtube.com/watch?v=$id" else null)
                    ?: continue
                    entries.put(
                        JSONObject()
                            .put("id", id ?: JSONObject.NULL)
                            .put("title", e.str("title") ?: "Untitled")
                            .put("url", link)
                            .put("duration", e.opt("duration") ?: JSONObject.NULL)
                    )
                }
            }
            val thumb = d.optJSONArray("thumbnails")?.optJSONObject(0)?.str("url") ?: ""
            return 200 to JSONObject()
                .put("isPlaylist", true)
                .put("title", d.str("title") ?: "Playlist")
                .put("thumbnail", thumb)
                .put("entries", entries)
        }

        // ---- Single video ----
        val formats = JSONArray()
        val fa = d.optJSONArray("formats")
        if (fa != null) {
            for (i in 0 until fa.length()) {
                val f = fa.optJSONObject(i) ?: continue
                val fh = f.optInt("height", 0)
                var size = f.optLong("filesize", 0L)
                if (size == 0L) size = f.optLong("filesize_approx", 0L)
                formats.put(
                    JSONObject()
                        .put("format_id", f.str("format_id") ?: JSONObject.NULL)
                        .put("ext", f.str("ext") ?: JSONObject.NULL)
                        .put("resolution", if (fh > 0) "${fh}p" else "audio")
                        .put("filesize", size)
                        .put("vcodec", f.str("vcodec") ?: JSONObject.NULL)
                        .put("acodec", f.str("acodec") ?: JSONObject.NULL)
                )
            }
        }
        warmStreamCache(url, d)
        val h = d.optInt("height", 0)
        return 200 to JSONObject()
            .put("isPlaylist", false)
            .put("title", d.str("title") ?: "Untitled")
            .put("artist", d.str("channel") ?: d.str("uploader") ?: JSONObject.NULL)
            .put("thumbnail", d.str("thumbnail") ?: "")
            .put("duration", d.opt("duration") ?: JSONObject.NULL)
            .put("resolution", d.str("resolution") ?: (if (h > 0) "${h}p" else JSONObject.NULL))
            .put("ext", d.str("ext") ?: "mp4")
            .put("formats", formats)
    }

    // ---- Link check (Collections): is this link still alive? Always answers 200 with a status ----
    private fun apiCheck(body: JSONObject): Pair<Int, JSONObject> {
        val raw = body.str("url") ?: return 400 to errJson("URL is required")
        val url = cleanUrl(raw) ?: return 400 to errJson("That is not a valid http(s) link")

        ensureReady()

        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-download")
        request.addOption("--flat-playlist")
        request.addOption("--no-warnings")
        if (isYouTube(url)) request.addOption("--extractor-args", "youtube:skip=hls,dash")

        val stdout = try {
            ytRun(request).out
        } catch (e: Exception) {
            val status = classifyYtError(e.message)
            Log.d("DLHUB", "check: $status | ${lastLine(e.message)}")
            return 200 to JSONObject().put("status", status)
        }

        return try {
            val d = JSONObject(stdout.trim())
            200 to JSONObject()
                .put("status", "ok")
                .put("title", d.str("title") ?: JSONObject.NULL)
                .put("thumbnail", d.str("thumbnail") ?: JSONObject.NULL)
        } catch (e: Exception) {
            200 to JSONObject().put("status", "unknown")
        }
    }

    // ---- Thumbnail copy (Collections): answers with the image as base64 text inside JSON ----
    private fun apiThumb(path: String): Pair<Int, JSONObject> {
        val query = path.substringAfter('?', "")
        val raw = Uri.parse("http://x/?$query").getQueryParameter("url")
        val url = cleanUrl(raw) ?: return 400 to errJson("Invalid url")
        return try {
            val (bytes, type) = fetchImage(url)
            200 to JSONObject()
                .put("type", type)
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.d("DLHUB", "thumb failed: ${e.message}")
            502 to errJson("Could not load thumbnail")
        }
    }

    private val searchCache = ConcurrentHashMap<String, Pair<Long, String>>()   // query -> (time, results)

    // ---- Music search: searches YouTube Music (official tracks), max 12 results ----
    private fun apiMusicSearch(body: JSONObject): Pair<Int, JSONObject> {
        val query = (body.str("query") ?: "").trim().take(200)
        if (query.isEmpty()) return 400 to errJson("Search query is required")

        ensureReady()

        val cached = searchCache[query.lowercase()]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60 * 1000) {
            return 200 to JSONObject().put("results", JSONArray(cached.second))
        }

        val searchUrl = "https://music.youtube.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val request = YoutubeDLRequest(searchUrl)
        request.addOption("--dump-single-json")
        request.addOption("--flat-playlist")
        request.addOption("--no-warnings")

        val stdout = try {
            ytRun(request).out
        } catch (e: Exception) {
            Log.e("DLHUB", "music search failed", e)
            return 400 to errJson(lastLine(e.message).ifEmpty { "Search failed." })
        }

        val d = try {
            JSONObject(stdout.trim())
        } catch (e: Exception) {
            Log.e("DLHUB", "music search parse error: ${stdout.take(300)}")
            return 500 to errJson("Failed to parse search results.")
        }

        val results = JSONArray()
        val entries = d.optJSONArray("entries")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                if (results.length() >= 12) break
                val e = entries.optJSONObject(i) ?: continue
                val id = e.str("id") ?: continue
                if (e.str("ie_key") != "Youtube") continue
                results.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", e.str("title") ?: "Untitled")
                        .put("artist", e.str("artist") ?: e.str("uploader") ?: e.str("channel") ?: "YouTube Music")
                        .put("duration", e.opt("duration") ?: JSONObject.NULL)
                        .put("thumbnail", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                )
            }
        }
        Log.d("DLHUB", "music search: ${results.length()} results")
        if (results.length() > 0) {
            if (searchCache.size > 30) searchCache.clear()
            searchCache[query.lowercase()] = System.currentTimeMillis() to results.toString()
        }
        return 200 to JSONObject().put("results", results)
    }

    // ---- Download: yt-dlp works in a private cache folder, then the file is copied to Downloads ----
    private val downloadSlots = Semaphore(2)   // at most 2 downloads at the same time

    // Copies into the folder chosen in Settings. Returns null when none is chosen or it no longer works.
    private fun saveToChosenFolder(file: File, displayName: String, mime: String): Pair<String, String?>? {
        val s = prefs.getString("save_tree", null) ?: return null
        return try {
            val tree = Uri.parse(s)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(contentResolver, parent, mime, displayName)
                ?: throw IOException("Could not create the file in the chosen folder")
            val out = contentResolver.openOutputStream(doc)
                ?: throw IOException("Could not write to the chosen folder")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            displayName to doc.toString()
        } catch (e: Exception) {
            Log.e("DLHUB", "chosen folder failed, using the default one", e)
            if (e is SecurityException) prefs.edit().remove("save_tree").apply()
            null
        }
    }

    // One attempt to save through MediaStore into the given Downloads sub-folder (Android 10+)
    private fun saveViaMediaStore(file: File, displayName: String, mime: String, folder: String): Pair<String, String?> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in $folder")
        try {
            val out = contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the file in $folder")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        return displayName to uri.toString()
    }

    // Returns the saved name and, when possible, the file's address (used by Open / Share in the app)
    private fun saveToDownloads(file: File, displayName: String, mime: String): Pair<String, String?> {
        saveToChosenFolder(file, displayName, mime)?.let { return it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                saveViaMediaStore(file, displayName, mime, Environment.DIRECTORY_DOWNLOADS + "/DownloaderHub")
            } catch (first: Exception) {
                Log.e("DLHUB", "Downloads/DownloaderHub failed, trying Downloads itself", first)
                try {
                    saveViaMediaStore(file, displayName, mime, Environment.DIRECTORY_DOWNLOADS)
                } catch (second: Exception) {
                    Log.e("DLHUB", "Downloads failed too", second)
                    throw IOException(first.message ?: "Could not save to Downloads", second)
                }
            }
        }

        // Android 9 and older: needs the storage permission
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DownloaderHub")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create the Downloads/DownloaderHub folder")
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val suffix = if (dot > 0) displayName.substring(dot) else ""
        var target = File(dir, displayName)
        var n = 1
        while (target.exists()) {
            target = File(dir, "$stem ($n)$suffix")
            n++
        }
        file.copyTo(target)
        return target.name to null
    }

    private fun apiDownload(body: JSONObject): Pair<Int, JSONObject> {
        val raw = body.str("url") ?: return 400 to errJson("URL is required")
        val url = cleanUrl(raw) ?: return 400 to errJson("That is not a valid http(s) link")

        val isAudio = body.str("type") == "audio"
        val fid = body.str("format_id") ?: ""
        if (fid.isNotEmpty() && !Regex("^[\\w.+-]{1,40}$").matches(fid)) {
            return 400 to errJson("Invalid format")
        }
        val height = Regex("^(\\d+)p$").find(fid)?.groupValues?.get(1)?.toIntOrNull()
        val baseName = sanitizeFilename(body.str("filename") ?: "download")
        val ext = if (isAudio) "mp3" else "mp4"

        ensureReady()

        val processId = "dl-${System.nanoTime()}"
        val callId = callIdLocal.get() ?: -1
        val root = workRoot()
        val freeMb = freeBytes(root) / (1024 * 1024)
        if (freeMb < 60) {
            return 507 to errJson("Only about $freeMb MB of space is free on the phone. Free some space and try again.")
        }
        sweepOldWork()
        downloadSlots.acquire()
        val workDir = File(root, "dl-${System.nanoTime()}")
        try {
            DownloadTracker.begin(applicationContext, processId)
            if (callId >= 0) callToProcess[callId] = processId
            if (!workDir.mkdirs() && !workDir.isDirectory) throw IOException("Could not create the working folder")
            val outFile = File(workDir, "out.$ext")

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            if (isAudio) {
                // Settings > Downloads > Audio Quality: best, standard (~128 kbps) or small (~96 kbps)
                val audioQuality = when (body.str("audioQuality")) { "standard" -> "5"; "small" -> "7"; else -> "0" }
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", audioQuality)
                request.addOption("--embed-metadata")
            } else if (height != null) {
                request.addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]")
                request.addOption("-S", "res:$height,ext:mp4:m4a")
                request.addOption("--merge-output-format", "mp4")
            } else if (fid.isNotEmpty()) {
                request.addOption("-f", "$fid+ba/b")
                request.addOption("--merge-output-format", "mp4")
            } else {
                request.addOption("-f", "bv*+ba/b")
                request.addOption("--merge-output-format", "mp4")
            }
            request.addOption("-o", outFile.absolutePath)

            Log.d("DLHUB", "download start: type=${if (isAudio) "audio" else "video"} format=${fid.ifEmpty { "(none)" }}")
            var lastSent = -1f
            var lastAt = 0L
            ytRun(request, processId) { progress ->
                DownloadTracker.progress(applicationContext, processId, progress)
                val now = System.currentTimeMillis()
                if (callId >= 0 && progress != lastSent && now - lastAt > 350) {
                    lastAt = now
                    lastSent = progress
                    downloadEvent(callId, progress)
                }
            }

            // Normally out.<ext>; fall back to any other finished output file
            val produced = workDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("out.") && !Regex("\\.(part|ytdl|temp)$").containsMatchIn(it.name) }
                ?: emptyList()
            val result = produced.firstOrNull { it.name == "out.$ext" } ?: produced.firstOrNull()
            ?: return 500 to errJson("The download finished but no file was produced.")

            val (savedName, savedUri) = saveToDownloads(
                result, "$baseName.$ext", if (isAudio) "audio/mpeg" else "video/mp4"
            )
            Log.d("DLHUB", "download saved: $savedName")
            return 200 to JSONObject()
                .put("saved", true)
                .put("name", savedName)
                .put("uri", savedUri ?: JSONObject.NULL)
                .put("folder", "Download/DownloaderHub")
        } catch (e: Exception) {
            if (DownloadTracker.wasCancelled(processId)) {
                Log.d("DLHUB", "download cancelled")
                return 499 to errJson("Download cancelled")
            }
            Log.e("DLHUB", "download failed", e)
            val low = (e.message ?: "").lowercase()
            if ("no space left" in low || "enospc" in low || "errno 28" in low) {
                return 507 to errJson("The phone ran out of space while downloading (about ${freeBytes(root) / (1024 * 1024)} MB free). Free some space and try again.")
            }
            if (e is IOException) {
                return 507 to errJson("Could not save the file: ${(e.message ?: "unknown reason").take(100)}. In Settings > Downloads you can choose a different save folder.")
            }
            if (looksLikeStorageError(e.message)) {
                return 507 to errJson("Storage problem while downloading: ${lastLine(e.message).take(120)}")
            }
            val msg = lastLine(e.message).ifEmpty { "Download failed." }
            return 400 to errJson(msg, classifyYtError(e.message))
        } finally {
            workDir.deleteRecursively()
            DownloadTracker.end(applicationContext, processId)
            if (callId >= 0) callToProcess.remove(callId)
            downloadSlots.release()
        }
    }

    // ---- Video preview + music: the page asks https://dlhub.local/..., we answer from here ----
    private val mediaLock = Any()

    private fun isYouTube(url: String): Boolean =
        Regex("^https?://([\\w-]+\\.)?(youtube\\.com|youtu\\.be)/", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun streamRequest(pageUrl: String, selector: String, fast: Boolean): YoutubeDLRequest {
        val request = YoutubeDLRequest(pageUrl)
        request.addOption("-j")
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("-f", selector)
        if (isYouTube(pageUrl)) {
            if (fast) {
                // Ask YouTube's app API directly (android_vr needs no PO token and no player script) and skip the
                // web page and the manifests: fewer requests and no slow script work on the phone.
                request.addOption("--extractor-args", "youtube:player_client=android_vr;player_skip=webpage,configs,js;skip=dash,hls")
            } else {
                request.addOption("--extractor-args", "youtube:skip=dash,hls")
            }
        }
        return request
    }

    // The newest link-finding job that nobody is waiting for yet (so a tap on that same song does not cancel it)
    @Volatile private var prefetchingKey: String? = null

    private fun resolveStream(key: String, pageUrl: String, selector: String, processId: String? = null): StreamInfo {
        streamCache[key]?.let { if (System.currentTimeMillis() < it.expires) return it }
        synchronized(mediaLock) {
            // another request may have just filled it while we waited
            streamCache[key]?.let { if (System.currentTimeMillis() < it.expires) return it }
            ensureReady()
            val started = System.currentTimeMillis()
            var d: JSONObject? = null
            var usedFast = false
            if (isYouTube(pageUrl)) {
                try {
                    val fast = JSONObject(ytRun(streamRequest(pageUrl, selector, true), processId).out.trim())
                    if (fast.str("url") != null) { d = fast; usedFast = true }
                } catch (e: Exception) {
                    Log.d("DLHUB", "fast way failed (${lastLine(e.message).take(80)}), using the normal way")
                }
            }
            if (d == null) {
                d = JSONObject(ytRun(streamRequest(pageUrl, selector, false), processId).out.trim())
            }
            val url = d.str("url") ?: throw IOException("No stream URL returned")
            cacheStream(key, url, d.optJSONObject("http_headers"))
            Log.d("DLHUB", "stream ready in ${System.currentTimeMillis() - started} ms (fast=$usedFast)")
            return streamCache[key]!!
        }
    }

    private fun serveMedia(request: WebResourceRequest): WebResourceResponse {
        val u = request.url
        val key: String
        val pageUrl: String
        val selector: String
        val mimeHint: String
        when (u.path) {
            "/preview" -> {
                val url = cleanUrl(u.getQueryParameter("url")) ?: return mediaError(400, "Bad url")
                key = "v:$url"
                pageUrl = url
                selector = "b[height<=480][ext=mp4]/b[height<=480]/b"
                mimeHint = "video/mp4"
            }
            "/stream" -> {
                val id = u.getQueryParameter("id") ?: ""
                if (!Regex("^[\\w-]{6,20}$").matches(id)) return mediaError(400, "Bad track id")
                key = "a:$id"
                pageUrl = "https://music.youtube.com/watch?v=$id"
                selector = "ba[ext=m4a]/ba/b"
                mimeHint = "audio/mp4"
            }
            else -> return mediaError(404, "Not found")
        }

        val range = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Range", true) }?.value

        for (attempt in 0..1) {
            val stream = resolveStream(key, pageUrl, selector)
            val conn = openUpstream(stream, range)
            val code = conn.responseCode
            // An old link may have expired: forget it and try once with a fresh one
            if ((code == 403 || code == 404 || code == 410) && attempt == 0) {
                conn.disconnect()
                streamCache.remove(key)
                continue
            }
            if (code != 200 && code != 206) {
                conn.disconnect()
                return mediaError(if (code in 400..599) code else 502, "Upstream answered $code")
            }

            val mime = (conn.contentType ?: mimeHint).substringBefore(';').trim().ifEmpty { mimeHint }
            val headers = HashMap<String, String>()
            conn.getHeaderField("Content-Length")?.let { headers["Content-Length"] = it }
            conn.getHeaderField("Content-Range")?.let { headers["Content-Range"] = it }
            headers["Accept-Ranges"] = "bytes"
            headers["Access-Control-Allow-Origin"] = "*"

            val reason = conn.responseMessage?.takeIf { it.isNotBlank() }
                ?: (if (code == 206) "Partial Content" else "OK")
            val body = object : FilterInputStream(conn.inputStream) {
                override fun close() {
                    try { super.close() } finally { conn.disconnect() }
                }
            }
            Log.d("DLHUB", "media ${u.path}: $code range=${range ?: "-"}")
            return WebResourceResponse(mime, null, code, reason, headers, body)
        }
        return mediaError(502, "Could not load media")
    }

    // ---- Native music player: Media3 plays inside PlaybackService, so music keeps going in the background ----
    private val uiHandler = Handler(Looper.getMainLooper())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    @Volatile private var musicLoading = false
    @Volatile private var loadSeq = 0          // which song was chosen last; older answers are thrown away
    @Volatile private var wantPlay = true      // play as soon as the chosen song is ready (Pause while loading turns it off)
    private var tickerRunning = false

    // Tells the page what the native player is doing (the page's audio object listens)
    private fun musicEvent(type: String, a: Double = 0.0, b: Double = 0.0) {
        runOnUiThread {
            web.evaluateJavascript("window.__musicEvent && window.__musicEvent('$type', $a, $b)", null)
        }
    }

    private fun sendMusicTime(c: MediaController) {
        val dur = c.duration
        musicEvent("time", c.currentPosition / 1000.0, if (dur > 0) dur / 1000.0 else 0.0)
    }

    private val musicTicker = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && c.isPlaying) {
                sendMusicTime(c)
                uiHandler.postDelayed(this, 500)
            } else {
                tickerRunning = false
            }
        }
    }

    private fun startMusicTicker() {
        if (tickerRunning) return
        tickerRunning = true
        uiHandler.post(musicTicker)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (musicLoading && !playWhenReady) return      // we paused the old song ourselves; the page already knows
            musicEvent(if (playWhenReady) "play" else "pause")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startMusicTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) sendMusicTime(c)
            if (playbackState == Player.STATE_ENDED) {
                musicEvent("ended")
                c.pause()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("DLHUB", "music player error: ${error.errorCodeName}", error)
            musicEvent("error")
        }
    }

    // Connects to PlaybackService (once), then runs block. Call on the main thread.
    private fun withController(block: (MediaController) -> Unit) {
        controller?.let {
            if (it.isConnected) {
                block(it)
                return
            }
        }
        val executor = ContextCompat.getMainExecutor(this)
        val fut = controllerFuture ?: run {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val created = MediaController.Builder(this, token).buildAsync()
            controllerFuture = created
            created.addListener({
                try {
                    val c = created.get()
                    c.addListener(playerListener)
                    controller = c
                } catch (e: Exception) {
                    Log.e("DLHUB", "music player connect failed", e)
                    controllerFuture = null
                }
            }, executor)
            created
        }
        fut.addListener({
            try {
                block(fut.get())
            } catch (e: Exception) {
                Log.e("DLHUB", "music player not ready", e)
                musicLoading = false
                musicEvent("error")
            }
        }, executor)
    }

    // ---- Fullscreen video: the preview's fullscreen button needs this, a plain WebView ignores it ----
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    // Back closes fullscreen first (only active while fullscreen is open)
    private val fullscreenBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFullscreen()
        }
    }

    private fun setFullscreenBars(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        view.setBackgroundColor(Color.BLACK)
        // The window's top layer ignores our padding, so the video really fills the screen
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setFullscreenBars(true)
        fullscreenBack.isEnabled = true
    }

    private fun exitFullscreen() {
        val v = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(v)
        fullscreenView = null
        fullscreenBack.isEnabled = false
        setFullscreenBars(false)
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    // ---- Back button: the page decides what Back should close first, the app only exits from Home ----
    private val appBack = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            web.evaluateJavascript("window.__handleBack ? window.__handleBack() : false") { handled ->
                if (handled != "true") {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    // ---- In-app download progress: remembers which page request is which download ----
    private val callIdLocal = ThreadLocal<Int>()
    private val callToProcess = ConcurrentHashMap<Int, String>()

    private fun downloadEvent(callId: Int, percent: Float) {
        runOnUiThread {
            web.evaluateJavascript("window.__dlProgress && window.__dlProgress($callId, $percent)", null)
        }
    }

    // ---- What the page can call through window.AndroidBridge ----
    @Suppress("unused")
    inner class Bridge {
        @JavascriptInterface
        fun apiCall(id: Int, path: String, body: String) {
            thread {
                callIdLocal.set(id)
                val result = try {
                    handleApi(path, body)
                } catch (e: Exception) {
                    Log.e("DLHUB", "api failed: $path", e)
                    500 to errJson(lastLine(e.message).ifEmpty { "Something went wrong in the app." })
                }
                reply(id, result.first, result.second.toString())
            }
        }

        @JavascriptInterface
        fun requestNotifications() {
            when {
                Build.VERSION.SDK_INT < 33 ->
                    say("Android ${Build.VERSION.RELEASE}: notifications need no permission")
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED ->
                    say("Notifications are already allowed")
                else -> {
                    say("Asking for notification permission...")
                    ask(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        @JavascriptInterface
        fun requestStorage() {
            if (Build.VERSION.SDK_INT <= 28) ask(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else say("Android ${Build.VERSION.RELEASE}: storage needs no permission")
        }

        // ---- Open / share a file that was downloaded earlier. Answers "ok", "missing" or "noapp" ----
        @JavascriptInterface
        fun openFile(uri: String, mime: String): String {
            return try {
                val u = Uri.parse(uri)
                contentResolver.openFileDescriptor(u, "r")?.close()          // still there?
                val i = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(u, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                "ok"
            } catch (e: ActivityNotFoundException) {
                "noapp"
            } catch (e: Exception) {
                Log.e("DLHUB", "open file failed", e)
                "missing"
            }
        }

        @JavascriptInterface
        fun shareFile(uri: String, mime: String): String {
            return try {
                val u = Uri.parse(uri)
                contentResolver.openFileDescriptor(u, "r")?.close()
                val send = Intent(Intent.ACTION_SEND)
                    .setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri("", u)
                startActivity(
                    Intent.createChooser(send, "Share")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "ok"
            } catch (e: Exception) {
                Log.e("DLHUB", "share file failed", e)
                "missing"
            }
        }

        // ---- Copy / share a link (the browser clipboard is blocked in the WebView) ----
        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", text.take(4000)))
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(4000))
                startActivity(Intent.createChooser(send, "Share link"))
            }
        }

        // ---- Save location ----
        @JavascriptInterface
        fun pickSaveFolder() {
            runOnUiThread { pickFolder.launch(null) }
        }

        @JavascriptInterface
        fun clearSaveFolder() {
            prefs.edit().remove("save_tree").apply()
            saveFolderEvent()
        }

        @JavascriptInterface
        fun getSaveFolder(): String = saveFolderLabel()

        // ---- Cancel one download from the page (its progress card has a cancel button) ----
        @JavascriptInterface
        fun cancelDownloadCall(callId: Int) {
            callToProcess[callId]?.let { DownloadTracker.cancelOne(it) }
        }

        // ---- Haptic feedback (the page only calls this when the setting is on) ----
        @JavascriptInterface
        fun haptic(kind: String) {
            runOnUiThread {
                val c = when (kind) {
                    "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                    "reject" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                    else -> HapticFeedbackConstants.VIRTUAL_KEY
                }
                web.performHapticFeedback(c)
            }
        }

        // ---- Clipboard: the browser clipboard is blocked in the WebView, so read it here ----
        @JavascriptInterface
        fun readClipboard(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@MainActivity).toString().take(2000)
                } else ""
            } catch (e: Exception) {
                Log.e("DLHUB", "clipboard read failed", e)
                ""
            }
        }

        // ---- Text size (Settings > Appearance) ----
        @JavascriptInterface
        fun setTextZoom(percent: Int) {
            runOnUiThread { web.settings.textZoom = percent.coerceIn(70, 160) }
        }

        // ---- Music (native player) ----
        @JavascriptInterface
        fun musicLoad(id: String, title: String, artist: String, thumb: String) {
            if (!Regex("^[\\w-]{6,20}$").matches(id)) return
            val mine = ++loadSeq
            musicLoading = true
            wantPlay = true
            runOnUiThread { controller?.pause() }          // the old song stops at once, not when the new one is found
            thread {
                // a background look-up for some other song must not hold this one up
                if (prefetchingKey != null && prefetchingKey != "a:$id") {
                    try { YoutubeDL.getInstance().destroyProcessById("prefetch") } catch (e: Exception) { /* nothing to stop */ }
                }
                try {
                    val stream = resolveStream("a:$id", "https://music.youtube.com/watch?v=$id", "ba[ext=m4a]/ba/b")
                    if (mine != loadSeq) return@thread           // another song was chosen in the meantime
                    checkPublicHost(stream.url)

                    PlaybackBridge.userAgent = stream.headers.entries
                        .firstOrNull { it.key.equals("User-Agent", true) }?.value ?: "Mozilla/5.0"
                    PlaybackBridge.headers = stream.headers.filterKeys { k ->
                        !k.equals("User-Agent", true) && !k.equals("Accept-Encoding", true) &&
                                !k.equals("Range", true) && !k.equals("Host", true)
                    }

                    val meta = MediaMetadata.Builder().setTitle(title).setArtist(artist)
                    cleanUrl(thumb)?.let { meta.setArtworkUri(Uri.parse(it)) }
                    val item = MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(stream.url)
                        .setMediaMetadata(meta.build())
                        .build()

                    runOnUiThread {
                        if (mine != loadSeq) return@runOnUiThread
                        withController { c ->
                            if (mine != loadSeq) return@withController
                            c.setMediaItem(item)
                            c.prepare()
                            if (wantPlay) c.play()
                            musicLoading = false
                            musicEvent(if (wantPlay) "play" else "pause")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DLHUB", "music load failed", e)
                    if (mine == loadSeq) {
                        musicLoading = false
                        musicEvent("error")
                    }
                }
            }
        }

        // Looks the next song's link up while the current one plays, so changing song is instant
        @JavascriptInterface
        fun musicPrefetch(id: String) {
            if (!Regex("^[\\w-]{6,20}$").matches(id)) return
            val key = "a:$id"
            val cached = streamCache[key]
            if (cached != null && System.currentTimeMillis() < cached.expires) return
            thread {
                prefetchingKey = key
                try {
                    resolveStream(key, "https://music.youtube.com/watch?v=$id", "ba[ext=m4a]/ba/b", "prefetch")
                } catch (e: Exception) {
                    Log.d("DLHUB", "prefetch skipped: ${lastLine(e.message).take(80)}")
                } finally {
                    if (prefetchingKey == key) prefetchingKey = null
                }
            }
        }

        @JavascriptInterface
        fun musicPlay() {
            runOnUiThread {
                if (musicLoading) { wantPlay = true; return@runOnUiThread }     // starts by itself once the song is ready
                val c = controller ?: return@runOnUiThread
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
        }

        // The page asks "what is the player doing?" when you come back to the app
        @JavascriptInterface
        fun musicSync() {
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                musicEvent(if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) "play" else "pause")
                sendMusicTime(c)
            }
        }

        @JavascriptInterface
        fun musicPause() {
            runOnUiThread {
                if (musicLoading) wantPlay = false                               // stay paused once the song is ready
                controller?.pause()
            }
        }

        @JavascriptInterface
        fun musicSeek(ms: Double) {
            runOnUiThread { controller?.seekTo(ms.toLong()) }
        }

        @JavascriptInterface
        fun musicVolume(v: Double) {
            runOnUiThread { controller?.volume = v.toFloat().coerceIn(0f, 1f) }
        }
    }

    override fun onDestroy() {
        PlaybackBridge.onSkip = null
        exitFullscreen()
        uiHandler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0c0e12"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.textZoom = 100
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != MEDIA_HOST) return super.shouldInterceptRequest(view, request)
                return try {
                    serveMedia(request)
                } catch (e: Exception) {
                    Log.e("DLHUB", "media failed: ${request.url.path}", e)
                    mediaError(502, "Could not load media")
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
        onBackPressedDispatcher.addCallback(this, appBack)
        onBackPressedDispatcher.addCallback(this, fullscreenBack)   // added last, so it gets Back first
        // Next/previous buttons in the notification ask the page to change song
        PlaybackBridge.onSkip = { dir -> musicEvent("skip", dir.toDouble()) }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.loadUrl("file:///android_asset/index.html")

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0c0e12"))
        root.addView(web)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false

        // Warm up yt-dlp in the background so the first fetch is quicker
        thread {
            try {
                ensureReady()
            } catch (e: Exception) {
                Log.e("DLHUB", "yt-dlp start-up failed", e)
            }
        }
    }
}