package com.local.shushcast;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 101;
    private static final int REQ_AUDIO_PERM = 102;

    private TextView statusTv;
    private TextView urlTv;
    private TextView clientsTv;
    private TextView levelTv;
    private TextView updateStatusTv;
    private TextView errTv;
    private ProgressBar meterBar;
    private ImageView qrView;
    private Button toggleBtn;
    private Button muteModeBtn;
    private Button updateBtn;

    private String lastQrUrl = "";
    private String pendingUpdateUrl = null;
    private String pendingUpdateVersion = null;
    private String lastSeenInstallStatus = "";
    private boolean isUpdateBusy = false;
    private int muteMode = ShushCastService.MUTE_STREAM_MUTE;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 150);
        }
    };

    private StateListDrawable makeBtnBg(int normalColor, int focusColor) {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(focusColor);
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(3), Color.parseColor("#6EE7B7"));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(10));
        normal.setStroke(dp(1), Color.parseColor("#334155"));

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{android.R.attr.state_pressed}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        muteMode = ShushCastService.activeMuteMode;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#0F1117"));
        root.setPadding(dp(40), dp(20), dp(40), dp(20));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("Shush Cast");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(null, Typeface.BOLD);
        titleRow.addView(title);

        TextView versionBadge = new TextView(this);
        versionBadge.setText("v" + SelfUpdater.getCurrentVersion(this));
        versionBadge.setTextColor(Color.parseColor("#6EE7B7"));
        versionBadge.setTextSize(13);
        versionBadge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        versionBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#1E293B"));
        badgeBg.setCornerRadius(dp(8));
        badgeBg.setStroke(dp(1), Color.parseColor("#334155"));
        versionBadge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(12);
        versionBadge.setLayoutParams(badgeLp);
        titleRow.addView(versionBadge);

        root.addView(titleRow);

        TextView sub = new TextView(this);
        sub.setText("Streams full-level TV audio over WebSocket · Volume is controlled on each phone");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setGravity(Gravity.CENTER_VERTICAL);
        leftCol.setPadding(0, 0, dp(36), 0);

        statusTv = new TextView(this);
        statusTv.setTextSize(18);
        statusTv.setTypeface(null, Typeface.BOLD);
        leftCol.addView(statusTv);

        urlTv = new TextView(this);
        urlTv.setTextColor(Color.parseColor("#6EE7B7"));
        urlTv.setTextSize(24);
        urlTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        urlTv.setPadding(0, dp(10), 0, dp(6));
        leftCol.addView(urlTv);

        clientsTv = new TextView(this);
        clientsTv.setTextColor(Color.parseColor("#CBD5E1"));
        clientsTv.setTextSize(15);
        clientsTv.setPadding(0, 0, 0, dp(16));
        leftCol.addView(clientsTv);

        levelTv = new TextView(this);
        levelTv.setTextColor(Color.parseColor("#94A3B8"));
        levelTv.setTextSize(14);
        leftCol.addView(levelTv);

        meterBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meterBar.setMax(100);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(380), dp(14));
        mp.topMargin = dp(6);
        meterBar.setLayoutParams(mp);
        leftCol.addView(meterBar);

        mainRow.addView(leftCol);

        LinearLayout qrBox = new LinearLayout(this);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER);
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(Color.WHITE);
        qrBg.setCornerRadius(dp(14));
        qrBox.setBackground(qrBg);
        qrBox.setPadding(dp(10), dp(10), dp(10), dp(8));

        qrView = new ImageView(this);
        int qrPx = dp(220);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(qrPx, qrPx));
        qrBox.addView(qrView);

        TextView scanHint = new TextView(this);
        scanHint.setText("Scan with phone camera");
        scanHint.setTextColor(Color.parseColor("#1E293B"));
        scanHint.setTextSize(12);
        scanHint.setTypeface(null, Typeface.BOLD);
        scanHint.setPadding(0, dp(4), 0, 0);
        qrBox.addView(scanHint);

        mainRow.addView(qrBox);
        root.addView(mainRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dp(16), 0, 0);

        final int toggleId = View.generateViewId();
        final int muteId = View.generateViewId();
        final int updateId = View.generateViewId();

        toggleBtn = new Button(this);
        toggleBtn.setId(toggleId);
        toggleBtn.setAllCaps(false);
        toggleBtn.setTextSize(17);
        toggleBtn.setTextColor(Color.WHITE);
        toggleBtn.setBackground(makeBtnBg(Color.parseColor("#1E293B"), Color.parseColor("#0F766E")));
        toggleBtn.setPadding(dp(28), dp(14), dp(28), dp(14));
        toggleBtn.setFocusable(true);
        toggleBtn.setOnClickListener(v -> {
            if (ShushCastService.isRunning) {
                Intent i = new Intent(this, ShushCastService.class);
                i.setAction(ShushCastService.ACTION_STOP);
                startService(i);
            } else {
                startCaptureFlow();
            }
        });
        btnRow.addView(toggleBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(18), 1));
        btnRow.addView(spacer);

        muteModeBtn = new Button(this);
        muteModeBtn.setId(muteId);
        muteModeBtn.setAllCaps(false);
        muteModeBtn.setTextSize(16);
        muteModeBtn.setTextColor(Color.WHITE);
        muteModeBtn.setBackground(makeBtnBg(Color.parseColor("#1E293B"), Color.parseColor("#334155")));
        muteModeBtn.setPadding(dp(24), dp(14), dp(24), dp(14));
        muteModeBtn.setFocusable(true);
        muteModeBtn.setOnClickListener(v -> {
            muteMode = (muteMode + 1) % 3;
            ShushCastService.activeMuteMode = muteMode;
            if (ShushCastService.isRunning) {
                Intent i = new Intent(this, ShushCastService.class);
                i.setAction(ShushCastService.ACTION_SET_MUTE_MODE);
                i.putExtra(ShushCastService.EXTRA_MUTE_MODE, muteMode);
                startService(i);
            }
            refreshUi();
        });
        btnRow.addView(muteModeBtn);

        root.addView(btnRow);

        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setOrientation(LinearLayout.HORIZONTAL);
        updateRow.setGravity(Gravity.CENTER);
        updateRow.setPadding(0, dp(12), 0, 0);

        updateBtn = new Button(this);
        updateBtn.setId(updateId);
        updateBtn.setAllCaps(false);
        updateBtn.setText("Check for Updates");
        updateBtn.setTextSize(15);
        updateBtn.setTextColor(Color.WHITE);
        updateBtn.setBackground(makeBtnBg(Color.parseColor("#1E293B"), Color.parseColor("#0F766E")));
        updateBtn.setPadding(dp(22), dp(11), dp(22), dp(11));
        updateBtn.setFocusable(true);
        updateBtn.setOnClickListener(v -> handleUpdateClick());
        updateRow.addView(updateBtn);

        updateStatusTv = new TextView(this);
        updateStatusTv.setText("");
        updateStatusTv.setTextColor(Color.parseColor("#94A3B8"));
        updateStatusTv.setTextSize(14);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.leftMargin = dp(16);
        updateStatusTv.setLayoutParams(statusLp);
        updateRow.addView(updateStatusTv);

        root.addView(updateRow);

        // Deterministic D-pad navigation across main controls and update row.
        toggleBtn.setNextFocusRightId(muteId);
        toggleBtn.setNextFocusLeftId(muteId);
        toggleBtn.setNextFocusDownId(updateId);
        toggleBtn.setNextFocusUpId(toggleId);

        muteModeBtn.setNextFocusLeftId(toggleId);
        muteModeBtn.setNextFocusRightId(toggleId);
        muteModeBtn.setNextFocusDownId(updateId);
        muteModeBtn.setNextFocusUpId(muteId);

        updateBtn.setNextFocusUpId(toggleId);
        updateBtn.setNextFocusDownId(updateId);
        updateBtn.setNextFocusLeftId(updateId);
        updateBtn.setNextFocusRightId(updateId);

        View.OnFocusChangeListener topFocusTracker = (v, hasFocus) -> {
            if (hasFocus) {
                updateBtn.setNextFocusUpId(v.getId());
            }
        };
        toggleBtn.setOnFocusChangeListener(topFocusTracker);
        muteModeBtn.setOnFocusChangeListener(topFocusTracker);

        errTv = new TextView(this);
        errTv.setTextColor(Color.parseColor("#F87171"));
        errTv.setTextSize(14);
        errTv.setPadding(0, dp(8), 0, 0);
        root.addView(errTv);

        setContentView(root);
        toggleBtn.requestFocus();
    }

    private void handleUpdateClick() {
        if (isUpdateBusy) return;
        isUpdateBusy = true;

        if (pendingUpdateUrl == null) {
            SelfUpdater.lastInstallStatus = "";
            lastSeenInstallStatus = "";
            setUpdateStatus("Checking for updates...", "#94A3B8");
            SelfUpdater.checkForUpdate(this, new SelfUpdater.Callback() {
                @Override
                public void onUpdateAvailable(String latestVersion, String releaseName, String apkUrl, long sizeBytes) {
                    isUpdateBusy = false;
                    pendingUpdateVersion = latestVersion;
                    pendingUpdateUrl = apkUrl;
                    updateBtn.setText("Install v" + latestVersion);
                    String sizeText = sizeBytes > 0 ? " (" + formatSize(sizeBytes) + ")" : "";
                    setUpdateStatus("Update ready: v" + latestVersion + sizeText, "#6EE7B7");
                }

                @Override
                public void onNoUpdate(String message) {
                    isUpdateBusy = false;
                    updateBtn.setText("Check for Updates");
                    setUpdateStatus(message, "#94A3B8");
                }

                @Override
                public void onProgress(int percent, String status) {}

                @Override
                public void onStatus(String message) {}

                @Override
                public void onError(String message) {
                    isUpdateBusy = false;
                    updateBtn.setText("Check for Updates");
                    setUpdateStatus(message, "#F87171");
                }
            });
            return;
        }

        SelfUpdater.downloadAndInstall(this, pendingUpdateUrl, new SelfUpdater.Callback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String releaseName, String apkUrl, long sizeBytes) {}

            @Override
            public void onNoUpdate(String message) {}

            @Override
            public void onProgress(int percent, String status) {
                setUpdateStatus(status, "#6EE7B7");
            }

            @Override
            public void onStatus(String message) {
                setUpdateStatus(message, "#CBD5E1");
                if (!getPackageManager().canRequestPackageInstalls()) {
                    isUpdateBusy = false;
                }
            }

            @Override
            public void onError(String message) {
                isUpdateBusy = false;
                setUpdateStatus(message, "#F87171");
            }
        });
    }

    private void setUpdateStatus(String text, String hexColor) {
        updateStatusTv.setText(text);
        updateStatusTv.setTextColor(Color.parseColor(hexColor));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void startCaptureFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCaptureFlow();
            } else {
                ShushCastService.lastError = "RECORD_AUDIO permission is required for AudioPlaybackCapture";
                refreshUi();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(uiTicker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(uiTicker);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, ShushCastService.class);
            svc.setAction(ShushCastService.ACTION_START);
            svc.putExtra(ShushCastService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ShushCastService.EXTRA_RESULT_DATA, data);
            svc.putExtra(ShushCastService.EXTRA_MUTE_MODE, muteMode);
            startForegroundService(svc);
        }
    }

    private void refreshUi() {
        boolean running = ShushCastService.isRunning;
        String ip = getLocalIp();
        String url = "http://" + ip + ":" + ShushCastService.PORT;
        urlTv.setText(url);

        if (!url.equals(lastQrUrl) && !ip.startsWith("<")) {
            lastQrUrl = url;
            try {
                Bitmap bmp = QrCode.encodeBitmap(url, dp(220));
                qrView.setImageBitmap(bmp);
            } catch (Exception ignored) {}
        }

        if (running) {
            statusTv.setText("Status: LIVE (press Home & open an app)");
            statusTv.setTextColor(Color.parseColor("#34D399"));
            toggleBtn.setText("Stop Shush Cast");
            clientsTv.setText("Connected phones: " + ShushCastService.clientCount);
        } else {
            statusTv.setText("Status: Stopped");
            statusTv.setTextColor(Color.parseColor("#94A3B8"));
            toggleBtn.setText("Start Shush Cast");
            clientsTv.setText("Scan QR or open URL on your phone(s)");
        }

        int peak = ShushCastService.currentPeakPct;
        meterBar.setProgress(peak);
        levelTv.setText("Captured Audio Level: " + peak + "%");

        if (muteMode == ShushCastService.MUTE_STREAM_MUTE) {
            muteModeBtn.setText("TV Speakers: Mute (Stream Mute)");
        } else if (muteMode == ShushCastService.MUTE_ZERO_VOL) {
            muteModeBtn.setText("TV Speakers: Mute (Volume 0)");
        } else {
            muteModeBtn.setText("TV Speakers: Unchanged (Full Vol)");
        }

        String installStatus = SelfUpdater.lastInstallStatus;
        if (installStatus != null && !installStatus.isEmpty() && !installStatus.equals(lastSeenInstallStatus)) {
            lastSeenInstallStatus = installStatus;
            if (installStatus.startsWith("Update installed")) {
                isUpdateBusy = false;
                pendingUpdateUrl = null;
                pendingUpdateVersion = null;
                updateBtn.setText("Check for Updates");
                setUpdateStatus(installStatus, "#34D399");
            } else if (installStatus.startsWith("Install failed") || installStatus.contains("cancelled")) {
                isUpdateBusy = false;
                setUpdateStatus(installStatus, "#F87171");
            } else {
                setUpdateStatus(installStatus, "#CBD5E1");
            }
        }

        errTv.setText(ShushCastService.lastError);
    }

    private String getLocalIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "<TV-IP>";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
