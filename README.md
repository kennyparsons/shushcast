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
