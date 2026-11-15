# Shush Cast

Stream full-level Android TV audio to any phone over local Wi-Fi so the living room stays quiet. No companion phone app required—just scan the on-screen QR code and listen in your mobile browser with independent headphone volume.

- **Homepage:** https://kennyparsons.github.io/shushcast/
- **AFTVnews Downloader Code:** `4756191`
- **Direct Latest APK:** https://github.com/kennyparsons/shushcast/releases/latest/download/shushcast.apk
- **Latest Release Notes:** https://github.com/kennyparsons/shushcast/releases/latest

## Quick Start

### Install on TV (Android TV 10+ / API 29+)

#### Option A: AFTVnews Downloader (`4756191`)

1. Install **Downloader by AFTVnews** from the **Google Play Store** (Google TV / Android TV) or **Amazon Appstore** (Fire TV).
2. Open **Downloader**, type **`4756191`**, and select **Go** (always pulls the latest `shushcast.apk`).
3. If prompted on first sideload, enable **Install unknown apps** for Downloader, then install.
4. After the initial install, future updates can be pulled right inside Shush Cast via **Check for Updates**.

#### Option B: Direct APK / ADB

- Download the latest signed APK directly: https://github.com/kennyparsons/shushcast/releases/latest/download/shushcast.apk
- Or install from a computer over ADB:

```bash
curl -fLO https://github.com/kennyparsons/shushcast/releases/latest/download/shushcast.apk
adb install -r shushcast.apk
```

### Connect & Listen

1. **Start on TV:** Open **Shush Cast** on your Android TV and grant audio capture permission.
2. **Scan QR Code on Phone:** Point your iPhone or Android camera at the QR code on the TV screen to open `http://<tv-ip>:8765` in your browser.
3. **Listen in Headphones with Client Volume:** Mute or turn down the TV, tap **Listen** on your phone, and control headphone volume independently. Multiple phones can connect at once, each with a `50 ms` default lip-sync buffer (switchable between `25 ms`, `50 ms`, and `120 ms`).

To update later, open **Check for Updates** inside the TV app to download and install the latest signed APK from GitHub Releases.

## iPhone Home Screen (PWA) & Silent Mode

- **Works with Silent Mode on:** The web player keeps audio active even when your iPhone's physical Silent/Mute switch is enabled.
- **Add to Home Screen (PWA):** In Mobile Safari, tap **Share -> Add to Home Screen** on the player URL to save Shush Cast as a standalone full-screen web app.

## Remote Button Remapper

Shush Cast exports a translucent `Shush Cast Toggle` activity (`com.local.shushcast.ToggleActivity`) so TV button mappers (e.g., tvQuickActions, Button Mapper) or `adb` can toggle casting without covering the active video app with `MainActivity`:

- **App / Activity picker:** Select **Shush Cast Toggle** directly (exposed via `MAIN` / `LAUNCHER` + `LEANBACK_LAUNCHER`).
- **Intent actions:**
  - `com.local.shushcast.ACTION_TOGGLE` (default): Toggles casting on/off, prompts for `MediaProjection` over the current app when starting, shows a Toast with the live URL or stopped status, and exits immediately.
  - `com.local.shushcast.ACTION_START`: Starts casting (or toasts `"Shush Cast already live: http://<ip>:8765"` if already running).
  - `com.local.shushcast.ACTION_STOP`: Stops casting if running and toasts `"Shush Cast stopped"`.

```bash
adb shell am start -a com.local.shushcast.ACTION_TOGGLE
adb shell am start -a com.local.shushcast.ACTION_START
adb shell am start -a com.local.shushcast.ACTION_STOP
```

## Compatibility & Limitations

- **Supported:** Android TV 10+ (API 29+) and non-DRM apps/streams (YouTube, SmartTube, Plex, Kodi, IPTV players, local media, browser streams). Everything stays 100% on your local LAN.
- **Not supported (OS DRM limit):** DRM-protected streaming apps such as **Netflix** and **Hulu** block Android's `AudioPlaybackCapture` API at the system level, resulting in silent capture during protected playback.

---

## For Developers & Self-Hosting

### Building

Zero AndroidX or Maven dependencies. Requires Android SDK build-tools `34.0.0`, `android-34` platform, and JDK 11+:

```bash
./build.sh
```

The signed APK is written to `build/shushcast.apk` and verified with `apksigner verify`. Optional environment variables:

- `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `ANDROID_BUILD_TOOLS_DIR`, `ANDROID_PLATFORM_JAR`
- `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`
- `VERSION_CODE`, `VERSION_NAME` (overrides manifest version metadata via `aapt2 link`)

### Automated SemVer Releases

Pushes to `main` touching app sources/build files (`src/**`, `res/**`, `AndroidManifest.xml`, `build.sh`) or manual `workflow_dispatch` runs trigger `.github/workflows/release.yml`, which bumps the SemVer tag (`vX.Y.Z`), builds and signs `build/shushcast.apk`, and publishes a GitHub Release:

- `BREAKING CHANGE` or `<type>!:` commit -> **major** bump
- `feat:` / `feat(scope):` commit -> **minor** bump
- All other qualifying commits -> **patch** bump
- `versionCode` is derived from total commit count (`git rev-list --count HEAD`, strictly increasing across releases)

Required GitHub Actions repository secrets for signing releases: `KEYSTORE_BASE64`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`. To point the in-app self-updater at a fork, update `github_repo` in `res/values/strings.xml`.
