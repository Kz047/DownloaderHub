# <img src="https://api.iconify.design/lucide/download.svg?color=%233b82f6" width="28" height="28" align="absmiddle" /> DownloaderHub

[![License: AGPL v3](https://img.shields.io/badge/License-AGPLv3-blue.svg?style=flat-square)](LICENSE)
[![Node.js](https://img.shields.io/badge/Node.js-18%2B-339933?style=flat-square&logo=nodedotjs&logoColor=white)](https://nodejs.org/)
[![Express](https://img.shields.io/badge/Express-4.x-000000?style=flat-square&logo=express&logoColor=white)](https://expressjs.com/)
[![Platform](https://img.shields.io/badge/Platform-Web%20%7C%20Android-6366f1?style=flat-square)](#project-structure)
[![Powered by yt-dlp](https://img.shields.io/badge/Powered%20by-yt--dlp-ff0000?style=flat-square&logo=youtube&logoColor=white)](https://github.com/yt-dlp/yt-dlp)

A self-hosted media downloader, YouTube Music player, and link organizer built with Node.js, Express, and `yt-dlp`. Available as both a self-hosted web server and a standalone Android application.

---

## <img src="https://api.iconify.design/lucide/layers.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> Features

* <img src="https://api.iconify.design/lucide/film.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Multi-Platform Video & Audio Downloads**
  * Supports **YouTube**, **Instagram**, **TikTok**, **X (Twitter)**, and **Facebook**.
  * Choose video resolutions (`360p`, `480p`, `720p`, `1080p`, or `4K`) or extract audio as **MP3** with embedded metadata and cover art.
  * **Batch Playlist Support:** Automatically detects playlists and lets you select individual items or download everything at once.
  * **In-App Preview:** Stream a quick video preview directly before downloading.

* <img src="https://api.iconify.design/lucide/music.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Built-in Music Player**
  * Search tracks directly via **YouTube Music** (`Discover`), save tracks to `Favorites`, and revisit `Recently Played`.
  * Includes a full playback bar, queue autoplay, background playback on Android, a mini-player bar, and one-click MP3 downloading.

* <img src="https://api.iconify.design/lucide/folder-heart.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Collections & Link Health Checker**
  * Organize saved links into custom **Collections** (music playlists, memes, reference clips, etc.).
  * Caches small local copies of thumbnails inside your storage so saved items remain recognizable even if the original video is deleted.
  * Built-in **Check links** tool detects deleted, private, age-restricted, or region-blocked URLs.
  * Export and import your collections as portable `.json` backups.

* <img src="https://api.iconify.design/lucide/palette.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Customizable Interface**
  * Includes 7 built-in themes (`System`, `Black`, `White`, `Dark`, `Ocean`, `Purple`, `Forest`) plus a live **Custom Theme** color-wheel editor and accent color sliders.
  * Adjustable corner styles, card density, text scaling (Android), reduced-motion mode, and **Modern** (bottom bar + swipe gestures) or **Classic** (sidebar) phone layouts.

* <img src="https://api.iconify.design/lucide/shield-check.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Privacy & Security First**
  * No accounts, ads, or external tracking.
  * Temporary files are processed in isolated folders and deleted automatically as soon as a download completes or cancels.
  * External LAN devices are blocked (`403`) by default until you explicitly enable **Share on Local Network** in Settings.

---

## <img src="https://api.iconify.design/lucide/folder-tree.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> Project Structure

```text
DownloaderHub/
├── android/              # Android WebView app with native AndroidBridge & on-device yt-dlp
├── webserver/            # Node.js + Express backend and web frontend
│   ├── index.html        # Single-page application UI & client logic
│   ├── Style.css         # Themes, responsive layouts, and animations
│   ├── server.js         # Express API, yt-dlp runner, and stream proxy
│   ├── package.json      # Node.js dependencies and start script
│   └── package-lock.json # Locked dependency versions
├── LICENSE               # GNU Affero General Public License v3.0
└── README.md             # Project documentation
```

---

## <img src="https://api.iconify.design/lucide/wrench.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> Prerequisites (Web Server)

Before running the web server version on your computer or home server, make sure the following tools are installed:

1. **Node.js** (v18 or newer recommended)
2. **Python 3** with **`yt-dlp`** installed:
   ```bash
   pip install -U yt-dlp
   ```
3. **FFmpeg & FFprobe** (required for merging high-resolution video/audio streams and converting audio to MP3)

---

## <img src="https://api.iconify.design/lucide/terminal.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> How to Run Locally (Web Server)

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Kz047/DownloaderHub.git
   cd DownloaderHub/webserver
   ```

2. **Install Node.js dependencies:**
   ```bash
   npm install
   ```

3. **Start the server:**
   ```bash
   npm start
   ```

4. **Open the app:**
   Open your browser and go to `http://localhost:3000`.

> <img src="https://api.iconify.design/lucide/smartphone.svg?color=%233b82f6" width="15" height="15" align="absmiddle" /> **Android Note:** The Android app runs `yt-dlp` natively on the device via `AndroidBridge` and does not require a running computer server to work.

---

## <img src="https://api.iconify.design/lucide/book-open.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> How to Use

* <img src="https://api.iconify.design/lucide/arrow-down-circle.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Download Video or Audio:**
  Paste a supported media or playlist link into the search bar on the **Home** screen (auto-fetch analyzes the link automatically). Select your preferred resolution (`360p`–`4K`) in the bottom dock, then tap **Video** or **Audio**. Tap **Save** in the dock to bookmark the link into a Collection instead.

* <img src="https://api.iconify.design/lucide/headphones.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Stream & Save Music:**
  Open the **Music** tab and search for a song, artist, or album. Tap any card to start streaming, tap the heart icon to favorite it, or use the download button/menu to save it as an MP3.

* <img src="https://api.iconify.design/lucide/folder-check.svg?color=%233b82f6" width="16" height="16" align="absmiddle" /> **Manage Collections & History:**
  Open **Collections** to create folders, check whether saved links are still online (`⋯ → Check links`), or batch-download an entire collection. Open **Recent** to view your download history. Swipe left on any row (on touch devices) or tap the menu button to re-download, open/share files, or remove items.

---

## <img src="https://api.iconify.design/lucide/server.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> Self-Hosting Guide (Web Server)

### <img src="https://api.iconify.design/lucide/wifi.svg?color=%233b82f6" width="17" height="17" align="absmiddle" /> 1. Share Over Local Wi-Fi (LAN)

By default, `server.js` listens on `0.0.0.0:3000` so devices on your home network can connect, but blocks remote requests until enabled:

1. Open `http://localhost:3000` on the host computer.
2. Go to **Settings → System → Share on Local Network**.
3. Click **Start sharing** to reveal your local IP URLs (e.g., `http://192.168.1.x:3000`) to open in your phone or tablet's browser.
4. Sharing automatically turns back off whenever the server restarts.

### <img src="https://api.iconify.design/lucide/sliders.svg?color=%233b82f6" width="17" height="17" align="absmiddle" /> 2. Environment Variables

Customize how the server runs by setting environment variables:

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` | `3000` | Port the HTTP server listens on. |
| `HOST` | `0.0.0.0` | Network interface to bind to. Set to `127.0.0.1` to strictly disable LAN sharing. |
| `YT_DLP_CMD` | `python -m yt_dlp` | Command used to invoke `yt-dlp` (e.g., `yt-dlp` or `python3 -m yt_dlp`). |
| `YT_DLP_JS_RUNTIME` | `node` | JavaScript runtime passed to `yt-dlp --js-runtimes` (set to `none` to disable). |
| `MAX_JOBS` | `3` | Maximum number of concurrent `yt-dlp` processes allowed at once. |
| `CORS_ORIGINS` | *(empty)* | Comma-separated list of extra allowed origins if hosting behind a reverse proxy. |

**Example (Linux / macOS):**

```bash
PORT=8080 YT_DLP_CMD="yt-dlp" MAX_JOBS=4 npm start
```

### <img src="https://api.iconify.design/lucide/refresh-cw.svg?color=%233b82f6" width="17" height="17" align="absmiddle" /> 3. Running in the Background

For continuous self-hosting on a home server using PM2:

```bash
npm install -g pm2
pm2 start npm --name "downloaderhub" -- start
pm2 save
```

---

## <img src="https://api.iconify.design/lucide/scale.svg?color=%233b82f6" width="20" height="20" align="absmiddle" /> License & Credits

* **License:** Licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. See the [LICENSE](LICENSE) file for full terms.
* **Foundational Code:** Built upon open-source work originally created by [budijoi](https://github.com/budijoi) under the MIT License.
* **Web Server Media Engine:** Powered by [yt-dlp](https://github.com/yt-dlp/yt-dlp) and [FFmpeg](https://ffmpeg.org/) for media extraction, stream merging, and audio conversion.
* **Android Media Engine:** Powered by [youtubedl-android](https://github.com/yausername/youtubedl-android) by [yausername](https://github.com/yausername) (`youtubedl-android` and `ffmpeg` Android libraries) for native on-device downloading and muxing.
