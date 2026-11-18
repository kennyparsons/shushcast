# AGENTS.md — Shush Cast

Android TV (API 29+, `targetSdk 32`) local-LAN audio streamer (`com.local.shushcast`). Captures system playback via `AudioPlaybackCapture` and streams 48 kHz stereo 16-bit PCM over WebSocket (`:8765`) to a zero-app mobile browser player (`res/raw/player.html`).

## Repository Layout

- `AndroidManifest.xml` — App manifest (`package="com.local.shushcast"`, `minSdkVersion="29"`, `targetSdkVersion="32"`).
- `build.sh` — Standalone APK build/sign/verify script (no Gradle).
- `src/com/local/shushcast/`
  - `MainActivity.java` — Leanback / D-pad TV UI, QR code display, mute-mode selector, update check trigger.
  - `ToggleActivity.java` — Translucent trampoline activity for remote-button remappers / intents (`ACTION_TOGGLE`, `ACTION_START`, `ACTION_STOP`).
  - `ShushCastService.java` — Foreground `mediaProjection` service: PCM capture loop, WakeLock/WifiLock, TV mute/restore, `WsHttpServer` lifecycle.
  - `WsHttpServer.java` — Pure-Java HTTP + WebSocket server on port `8765` (`/`, `/ws`, `/ping`, `/silence.wav`, `/sw.js`, `/manifest.webmanifest`, `/icon.svg`).
  - `QrCode.java` — Zero-dep QR code encoder (Versions 2–4, `Bitmap`).
  - `SelfUpdater.java` / `UpdateReceiver.java` — GitHub Releases self-updater via `PackageInstaller`.
- `res/raw/player.html` — Mobile web player + PWA client served by `WsHttpServer`.
- `res/values/strings.xml` — Defines `github_repo` (`kennyparsons/shushcast`) for the self-updater.
- `docs/` — Static GitHub Pages landing site (`.nojekyll`, `index.html`, `styles.css`, icons/OG image).
- `.github/workflows/release.yml` — Automated SemVer APK release pipeline.

## Build Stack

Zero Gradle, AndroidX, or Maven dependencies. `./build.sh` compiles directly with Android SDK `34.0.0` (`android-34`) and JDK 11+:

1. `aapt2 compile` (`res/` -> `build/res/compiled.zip`)
2. `aapt2 link` (`AndroidManifest.xml` -> `build/unsigned.apk` + `build/gen/.../R.java`; supports `VERSION_CODE` / `VERSION_NAME` overrides via `--replace-version`)
3. `javac --release 11` -> `build/obj/`
4. `d8 --min-api 29` -> `build/dex/classes.dex`
5. `zip` + `zipalign -f 4` -> `build/aligned.apk`
6. `apksigner sign` + `apksigner verify --print-certs` -> `build/shushcast.apk`

Env overrides supported by `build.sh`: `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `ANDROID_BUILD_TOOLS_DIR`, `ANDROID_PLATFORM_JAR`, `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`, `VERSION_CODE`, `VERSION_NAME`.

## Signing & Self-Update Rules

- **Production keystore:** GitHub Actions signs releases using the `CN=TVHelper` keystore stored in repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`).
- **Signature continuity:** In-app updates in `SelfUpdater.java` / `UpdateReceiver.java` query `https://api.github.com/repos/<github_repo>/releases/latest` (configured via `github_repo` in `res/values/strings.xml`) and install `shushcast.apk` via `PackageInstaller`. Android enforces an exact signing-certificate match across upgrades.
- **Do not overwrite a release TV install with a mismatched local debug key:** Verify signer certs (`apksigner verify --print-certs`) before sideloading onto a TV that already has a GitHub Release build installed; mismatched keys fail upgrade/in-app self-update unless the existing app is uninstalled first.

## Release Workflow

`.github/workflows/release.yml` (`Release APK`):
- **Triggers:** Pushes to `main` touching `src/**`, `res/**`, `AndroidManifest.xml`, or `build.sh`, plus `workflow_dispatch`. Docs-only or `AGENTS.md` commits do **not** trigger a release.
- **SemVer bumping** (computed from commits since the latest `vX.Y.Z` tag):
  - `BREAKING CHANGE` or `<type>!:` (e.g., `feat!:`) -> **major** (`(X+1).0.0`)
  - `feat:` / `feat(scope):` -> **minor** (`X.(Y+1).0`)
  - Everything else (e.g., `fix:`) -> **patch** (`X.Y.(Z+1)`)
- **`versionCode`:** `git rev-list --count HEAD` (clamped above previous tag count so it is strictly monotonic).
- **Outputs:** Creates annotated tag `vX.Y.Z` and publishes `build/shushcast.apk` to GitHub Releases.

## Core Architecture & Gotchas

- **`ShushCastService.java` (capture, locks, TV audio):**
  - Captures `AudioPlaybackCapture` (`USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`) at 48 kHz stereo 16-bit PCM in `1920`-byte chunks (`10 ms` per read; note an inline comment still reads `20ms`) and broadcasts frames to all connected WebSocket clients.
  - Holds `PARTIAL_WAKE_LOCK` (`shushcast:audio`) and `WIFI_MODE_FULL_LOW_LATENCY` / `WIFI_MODE_FULL_HIGH_PERF` (`shushcast:wifi`), both `setReferenceCounted(false)`, released on stop.
  - Defaults to `MUTE_STREAM_MUTE` (`AudioManager.ADJUST_MUTE`), snapshots TV volume/mute state before muting, and always restores it in `restoreTvAudioState()` when casting stops.
- **`ToggleActivity.java` & `AndroidManifest.xml`:**
  - Must keep `android:noHistory` **disabled** (omitted) so `startActivityForResult` (`MediaProjection` prompt) and `requestPermissions` (`RECORD_AUDIO`) can deliver results back to `ToggleActivity` instead of finishing when the system dialog opens.
  - Must keep `android:taskAffinity=""` (with `excludeFromRecents="true"` and `launchMode="singleTop"`) so triggering a toggle over a playing video app does not bring `MainActivity` to the foreground.
- **`QrCode.java`:**
  - Timing patterns use indices `8..size-9` (`for (int i = 8; i < size - 8; i++)`) so they never overwrite finder patterns or format-info cells.
- **`res/raw/player.html`:**
  - Schedules Web Audio chunks via `AudioBufferSourceNode` (`ctx.createBufferSource()`) instead of `AudioWorklet` because `http://<tv-ip>:8765` LAN URLs are non-secure contexts where `AudioWorklet` is unavailable on iOS Safari.
  - Plays a hidden looping `/silence.wav` `<audio>` element alongside `navigator.audioSession.type = 'playback'` so Web Audio remains audible when the iPhone hardware Silent/Mute switch is enabled.
  - Defaults lip-sync buffer delay to `50 ms` (`Low`; switchable to `25 ms` or `120 ms`).
- **`docs/`:** Serves the GitHub Pages site via GitHub's built-in `pages-build-deployment` workflow.

## Permanent URLs

- **Homepage (GitHub Pages):** `https://kennyparsons.github.io/shushcast/`
- **Latest APK Download:** `https://github.com/kennyparsons/shushcast/releases/latest/download/shushcast.apk`
- **AFTVnews Downloader Code:** `4756191`
