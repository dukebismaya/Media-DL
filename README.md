<div align="center">

# MM-DL

**A minimal, Android app to download videos and audio from the web.**

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-1.0-7C5CFC?style=flat-square)
![Min SDK](https://img.shields.io/badge/minSdk-24-22D3EE?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-10B981?style=flat-square)

</div>

---

## What it does

Paste or share any video/audio URL — Media-DL fetches the available formats and lets you download exactly what you want, saved directly to your device.

- Paste a URL **or share directly from any app** (YouTube, Instagram, SoundCloud, etc.) via the system share sheet
- Fetches available formats automatically — choose video quality or audio-only
- Downloads saved to `Downloads/MediaDL/` on your device
- Tracks your **download history** with thumbnails, file sizes, and timestamps
- Manage downloads: view, delete, filter by type or date

---

## Features

| | |
|---|---|
| Share sheet integration | Share a link from any browser or app — Media-DL handles the rest |
| Format picker | Choose from all available video/audio formats before downloading |
| Download manager | Track progress, view completed downloads, delete files |
| History tab | Full download history, searchable and filterable |
| Dark-first UI | Aurora-themed dark interface built with Jetpack Compose |
| Adaptive icon | Supports themed icons on Android 13+ |

---

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **yt-dlp** via [youtubedl-android](https://github.com/yausername/youtubedl-android)
- **MediaStore API** for scoped storage
- Single-Activity architecture · AndroidViewModel · Coil for image loading

---

## Requirements

- Android 7.0+ (API 24)
- Internet connection 🫠

---

## Getting Started

1. Clone the repo
   ```bash
   git clone https://github.com/dukebismaya/Media-DL.git
   ```
2. Open in **Android Studio Meerkat** or later
3. Build & run on a device or emulator (`./gradlew installDebug`)

> `local.properties` is not included — Android Studio generates it automatically.

---

## Legal

Media-DL is a personal tool. Users are responsible for ensuring they have the right to download any content. The developer does not host, cache, or distribute any third-party media.

---

<div align="center">

## Developer

<br>

**Bismaya Jyoti Dalei**

[dev.bismaya@gmail.com](mailto:dev.bismaya@gmail.com) · [mediadl.bismaya.xyz](https://mediadl.bismaya.xyz)

<br>

*Built with care — designed to stay out of your way.*

</div>

---

<div align="center">
<sub>© 2026 Bismaya Jyoti Dalei — MIT License</sub>
</div>
