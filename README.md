# Shush Cast

Android TV app that captures system playback audio via `AudioPlaybackCapture` and streams it over local HTTP/WebSocket so phones on the same network can listen with independent volume control.

## Building

Requires Android SDK build-tools `34.0.0`, `android-34` platform, and JDK 11+:

```bash
./build.sh
```

Output APK is written to `build/shushcast.apk` and verified with `apksigner verify`. Optional environment variables:

- `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `ANDROID_BUILD_TOOLS_DIR`, `ANDROID_PLATFORM_JAR`
- `KEYSTORE_PATH`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`
- `VERSION_CODE`, `VERSION_NAME` (overrides manifest version metadata via `aapt2 link`)

## Self-Update & Automated Releases

Pushes to `main` (or manual `workflow_dispatch` runs) trigger `.github/workflows/release.yml`, which automatically bumps the SemVer tag (`vX.Y.Z`, starting from `v1.0.0`), builds and signs `build/shushcast.apk`, and publishes a GitHub Release:

- `BREAKING CHANGE` or `<type>!:` commit -> **major** bump
- `feat:` / `feat(scope):` commit -> **minor** bump
- All other commits -> **patch** bump
- `versionCode` is derived from total commit count (`git rev-list --count HEAD`, strictly increasing across releases)

Required GitHub Actions repository secrets:

- `KEYSTORE_BASE64`: Base64-encoded PKCS12/JKS signing keystore
- `KEYSTORE_PASS`: Keystore password
- `KEY_ALIAS`: Signing key alias
- `KEY_PASS`: Signing key password

On the TV, select **Check for Updates** -> **Install vX.Y.Z** to pull the latest release APK from `kennyparsons/shushcast` (configured via `github_repo` in `res/values/strings.xml`).

## Remote Button / Headless Toggle

Shush Cast exports a translucent `Shush Cast Toggle` activity (`com.local.shushcast.ToggleActivity`) so TV button mappers (e.g., tvQuickActions, Button Mapper) or `adb` can toggle casting without covering the active video app with `MainActivity`:

- **App / Activity picker**: Select **Shush Cast Toggle** directly (exposed via `MAIN` / `LAUNCHER` + `LEANBACK_LAUNCHER`).
- **Intent actions**:
  - `com.local.shushcast.ACTION_TOGGLE` (default): Toggles casting on/off, prompts for `MediaProjection` over the current app when starting, shows a Toast with the live URL or stopped status, and exits immediately.
  - `com.local.shushcast.ACTION_START`: Starts casting (or toasts `"Shush Cast already live: http://<ip>:8765"` if already running).
  - `com.local.shushcast.ACTION_STOP`: Stops casting if running and toasts `"Shush Cast stopped"`.

Example via `adb`:

```bash
adb shell am start -a com.local.shushcast.ACTION_TOGGLE
adb shell am start -a com.local.shushcast.ACTION_START
adb shell am start -a com.local.shushcast.ACTION_STOP
```
