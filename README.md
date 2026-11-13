# VidLab

A modern, Netflix-style Android media center and cloud streaming player built with **Jetpack Compose**, **Media3 (ExoPlayer + FFmpeg)**, and an **embedded Rclone engine**.

Stream directly from encrypted Google Drive vaults and WebDAV servers—complete with automatic poster/backdrop scraping, episode tracking, and Simkl scrobbling.

---

## Key Features

- **Embedded Rclone & WebDAV Engine:** Import or paste your `rclone.conf` directly on launch (including `crypt`, `combine`, and `drive` remotes) or connect to any WebDAV endpoint (`http://127.0.0.1:8080`).
- **Automated Catalog & Metadata Matching:** Scans folders and filenames into organized **Movies**, **TV Shows**, and **Anime** rails with rich artwork, synopses, genres, and manual rematching support.
- **Auto-Advancing Hero Carousel & Resume Bar:** Highlights featured titles at the top and surfaces a full-width **Resume Playback** bar with episode progress whenever you have an in-progress title.
- +*Hardware + FFmpeg Software Playback:** Built on AndroidX Media3 with hardware/software decoder selection, 60s in-advance / 15s instant RAM back-buffering, gesture controls, subtitle/audio track defaults, and custom playback speeds.
- **Simkl Scrobbling & Watch History:** Syncs watch progress automatically via Simkl PIN authentication and keeps local resume states across episodes.
- +*Built-in Batch Renamer:** Clean up messy cloud filenames server-side using `rclone moveto` or WebDAV `MOVE`.

---

## Quick Start

1. Download the latest `VidLab-release.apk` from the [**Releases**](../../releases) tab and install it on Android 7.0+ (`minSdk 24`).
2. On first launch, choose **Import `rclone.conf`** or **Paste Config*y (or open the left navigation drawer -> **Source** to configure a WebDAV URL).
3. Tick the remote(s) you want VidLab to index in the **Select Remotes to Use** prompt and tap **Save & Scan**.

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK (`compileSdk 34`, `targetSdk 34`)
- Gradle 8.7+

### Compile Release & Debug APKs
```bash
./gradlew assembleRelease assembleDebug
```
Outputs are generated at:
- `app/build/outputs/apk/release/app-release.apk` (R8-minified & resource-shrunk)
- app/build/outputs/apk/debug/app-debug.apk`

---

## Privacy & Configuration Security

VidLab stores your `rclone.conf`, catalog cache, and watch history strictly inside app-private storage (`Context.filesDir`). Never commit personal `rclone.conf` tokens or credentials to public repositories.

## License

Released under the [MIT License](LICENSE).
