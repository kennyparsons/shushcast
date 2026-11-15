package com.local.shushcast;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Translucent, history-less trampoline Activity for remote-button / intent toggling
 * without pulling MainActivity over the currently playing TV app.
 */
public final class ToggleActivity extends Activity {
    public static final String ACTION_TOGGLE = "com.local.shushcast.ACTION_TOGGLE";
    public static final String ACTION_START = "com.local.shushcast.ACTION_START";
    public static final String ACTION_STOP = "com.local.shushcast.ACTION_STOP";

    private static final int REQ_CAPTURE = 201;
    private static final int REQ_AUDIO_PERM = 202;

    private boolean pendingCapture = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            if (ShushCastService.isRunning) {
                stopCastService();
                showToast("Shush Cast stopped");
            }
            finish();
            return;
        }

        if (ACTION_START.equals(action)) {
            if (ShushCastService.isRunning) {
                showToast("Shush Cast already live: " + getLiveUrl());
                finish();
                return;
            }
            startCaptureFlow();
            return;
        }

        // Default / ACTION_TOGGLE / Intent.ACTION_MAIN
        if (ShushCastService.isRunning) {
            stopCastService();
            showToast("Shush Cast stopped");
            finish();
        } else {
            startCaptureFlow();
        }
    }

    private void stopCastService() {
        Intent svc = new Intent(this, ShushCastService.class);
        svc.setAction(ShushCastService.ACTION_STOP);
        startService(svc);
    }

    private void startCaptureFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
            return;
        }
        if (pendingCapture) {
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            showToast("MediaProjection unavailable");
            finish();
            return;
        }
        pendingCapture = true;
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable t) {
            pendingCapture = false;
            showToast("Unable to start screen capture");
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCaptureFlow();
            } else {
                ShushCastService.lastError = "RECORD_AUDIO permission is required for AudioPlaybackCapture";
                showToast("RECORD_AUDIO permission required for Shush Cast");
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            pendingCapture = false;
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ShushCastService.class);
                svc.setAction(ShushCastService.ACTION_START);
                svc.putExtra(ShushCastService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(ShushCastService.EXTRA_RESULT_DATA, data);
                svc.putExtra(ShushCastService.EXTRA_MUTE_MODE, ShushCastService.activeMuteMode);
                startForegroundService(svc);
                showToast("Shush Cast live: " + getLiveUrl());
            }
            finish();
        }
    }

    private String getLiveUrl() {
        return "http://" + ShushCastService.getLocalIp() + ":" + ShushCastService.PORT;
    }

    private void showToast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
