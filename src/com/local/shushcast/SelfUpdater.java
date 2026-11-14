package com.local.shushcast;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Checks GitHub Releases for newer APK versions and installs via PackageInstaller.
 */
public final class SelfUpdater {
    public static final String ACTION_INSTALL_RESULT = "com.local.shushcast.INSTALL_RESULT";
    public static volatile String lastInstallStatus = "";

    private static final String USER_AGENT = "ShushCast-AndroidTV";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onUpdateAvailable(String latestVersion, String releaseName, String apkUrl, long sizeBytes);
        void onNoUpdate(String message);
        void onProgress(int percent, String status);
        void onStatus(String message);
        void onError(String message);
    }

    private SelfUpdater() {}

    public static String getCurrentVersion(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return stripLeadingV(pi.versionName.trim());
            }
        } catch (Exception ignored) {}
        return "1.0.0";
    }

    public static void checkForUpdate(Context context, Callback callback) {
        final Context appCtx = context.getApplicationContext();
        final String repo = appCtx.getString(R.string.github_repo).trim();
        if (repo.isEmpty() || repo.startsWith("OWNER/") || !repo.contains("/")) {
            postNoUpdate(callback, "GitHub repo not configured yet");
            return;
        }

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
                conn = openConnection(apiUrl, "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    postNoUpdate(callback, "No releases published yet");
                    return;
                }
                if (code < 200 || code >= 300) {
                    postError(callback, "Update check failed (HTTP " + code + ")");
                    return;
                }

                String body = readUtf8(conn.getInputStream());
                JSONObject release = new JSONObject(body);
                String tagName = release.optString("tag_name", "").trim();
                String releaseName = release.optString("name", tagName).trim();
                String latestVer = stripLeadingV(tagName);
                String currentVer = getCurrentVersion(appCtx);

                if (latestVer.isEmpty()) {
                    postError(callback, "Latest release has no version tag");
                    return;
                }

                String apkUrl = "";
                long apkSize = -1L;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        String dl = asset.optString("browser_download_url", "");
                        if (name.toLowerCase().endsWith(".apk") && !dl.isEmpty()) {
                            apkUrl = dl;
                            apkSize = asset.optLong("size", -1L);
                            break;
                        }
                    }
                }

                if (!isNewerVersion(latestVer, currentVer)) {
                    postNoUpdate(callback, "Up to date (v" + currentVer + ")");
                    return;
                }
                if (apkUrl.isEmpty()) {
                    postError(callback, "Release v" + latestVer + " has no APK asset");
                    return;
                }

                final String finalUrl = apkUrl;
                final long finalSize = apkSize;
                MAIN.post(() -> {
                    if (callback != null) {
                        callback.onUpdateAvailable(latestVer, releaseName, finalUrl, finalSize);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                postError(callback, "Update check failed" + (msg != null && !msg.isEmpty() ? ": " + msg : ""));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "shushcast-update-check").start();
    }

    public static void downloadAndInstall(Context context, String url, Callback callback) {
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            lastInstallStatus = "Allow 'Install unknown apps', then tap Install again";
            postStatus(callback, lastInstallStatus);
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            if (!(context instanceof Activity)) {
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(settingsIntent);
            return;
        }

        final Context appCtx = context.getApplicationContext();
        lastInstallStatus = "Downloading update...";
        postProgress(callback, 0, "Downloading update... 0%");

        new Thread(() -> {
            HttpURLConnection conn = null;
            PackageInstaller.Session session = null;
            File apkFile = new File(appCtx.getCacheDir(), "update.apk");
            try {
                conn = openFollowingRedirects(url, "application/octet-stream");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    lastInstallStatus = "Download failed (HTTP " + code + ")";
                    postError(callback, lastInstallStatus);
                    return;
                }

                long totalBytes = conn.getContentLengthLong();
                long downloaded = 0L;
                int lastPct = -1;
                byte[] buf = new byte[32768];
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (totalBytes > 0) {
                            int pct = (int) Math.min(100L, (downloaded * 100L) / totalBytes);
                            if (pct != lastPct) {
                                lastPct = pct;
                                postProgress(callback, pct, "Downloading update... " + pct + "%");
                            }
                        } else if (downloaded % (256 * 1024) < buf.length) {
                            postProgress(callback, 0, "Downloading update... " + (downloaded / 1024) + " KB");
                        }
                    }
                    out.flush();
                }

                lastInstallStatus = "Installing update...";
                postStatus(callback, lastInstallStatus);

                PackageInstaller pi = appCtx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = pi.createSession(params);
                session = pi.openSession(sessionId);

                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite("update.apk", 0, apkFile.length())) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                }

                Intent resultIntent = new Intent(appCtx, UpdateReceiver.class);
                resultIntent.setAction(ACTION_INSTALL_RESULT);
                PendingIntent piStatus = PendingIntent.getBroadcast(
                        appCtx,
                        sessionId,
                        resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(piStatus.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                if (session != null) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {}
                }
                String msg = e.getMessage();
                lastInstallStatus = "Update failed" + (msg != null && !msg.isEmpty() ? ": " + msg : "");
                postError(callback, lastInstallStatus);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "shushcast-update-install").start();
    }

    static boolean isNewerVersion(String candidate, String current) {
        int[] candParts = parseVersionParts(candidate);
        int[] currParts = parseVersionParts(current);
        int len = Math.max(candParts.length, currParts.length);
        for (int i = 0; i < len; i++) {
            int a = i < candParts.length ? candParts[i] : 0;
            int b = i < currParts.length ? currParts[i] : 0;
            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    private static String stripLeadingV(String version) {
        if (version == null) return "";
        String s = version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    private static int[] parseVersionParts(String version) {
        String s = stripLeadingV(version);
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        if (s.isEmpty()) return new int[0];

        String[] raw = s.split("\\.");
        int[] parts = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            parts[i] = parseLeadingInt(raw[i]);
        }
        return parts;
    }

    private static int parseLeadingInt(String token) {
        if (token == null) return 0;
        int val = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                val = val * 10 + (c - '0');
            } else {
                break;
            }
        }
        return val;
    }

    private static HttpURLConnection openConnection(String urlStr, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (accept != null) {
            conn.setRequestProperty("Accept", accept);
        }
        return conn;
    }

    private static HttpURLConnection openFollowingRedirects(String urlStr, String accept) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = openConnection(current, accept);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects");
    }

    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void postNoUpdate(Callback callback, String msg) {
        MAIN.post(() -> {
            if (callback != null) callback.onNoUpdate(msg);
        });
    }

    private static void postProgress(Callback callback, int pct, String status) {
        MAIN.post(() -> {
            if (callback != null) callback.onProgress(pct, status);
        });
    }

    private static void postStatus(Callback callback, String msg) {
        MAIN.post(() -> {
            if (callback != null) callback.onStatus(msg);
        });
    }

    private static void postError(Callback callback, String msg) {
        MAIN.post(() -> {
            if (callback != null) callback.onError(msg);
        });
    }
}
