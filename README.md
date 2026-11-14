# Shush Cast

Android TV app that captures system playback audio via `AudioPlaybackCapture` and streams it over local HTTP/WebSocket so phones on the same network can listen with independent volume control.

## Building

Requires Android SDK build-tools `34.0.0`, `android-34` platform, and JDK 11+:

```bash
./build.sh
```

Output APK is written to `build/shushcast.apk` and verified with `apksigner verify`.

## Self-Update & Publishing Releases

1. Set `<string name="github_repo">OWNER/shushcast</string>` in `res/values/strings.xml` to your GitHub `owner/repo`.
2. Bump `android:versionCode` and `android:versionName` in `AndroidManifest.xml`.
3. Build with `./build.sh`.
4. Tag and publish a GitHub Release (e.g., `v1.0.1`) with `build/shushcast.apk` attached as a release asset.
5. On the TV, select **Check for Updates** -> **Install v1.0.1**.

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
