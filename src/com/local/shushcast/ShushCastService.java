package com.local.shushcast;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class ShushCastService extends Service {
    public static final String ACTION_START = "com.local.shushcast.START";
    public static final String ACTION_STOP = "com.local.shushcast.STOP";
    public static final String ACTION_SET_MUTE_MODE = "com.local.shushcast.MUTE_MODE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_MUTE_MODE = "muteMode";

    public static final int MUTE_NONE = 0;        // Leave TV volume untouched
    public static final int MUTE_STREAM_MUTE = 1; // AudioManager ADJUST_MUTE
    public static final int MUTE_ZERO_VOL = 2;    // setStreamVolume(0)

    public static final int PORT = 8765;
    private static final String CHANNEL_ID = "shush_cast_channel";
    private static final int NOTIF_ID = 1001;

    public static volatile boolean isRunning = false;
    public static volatile int clientCount = 0;
    public static volatile int currentPeakPct = 0;
    public static volatile int activeMuteMode = MUTE_STREAM_MUTE;
    public static volatile String lastError = "";

    public static String getLocalIp() {
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

    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private WsHttpServer server;
    private Thread captureThread;
    private volatile boolean capturing = false;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private AudioManager audioManager;
    private int savedVolume = -1;
    private boolean savedMuted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_MUTE_MODE.equals(action)) {
            int mode = intent.getIntExtra(EXTRA_MUTE_MODE, MUTE_STREAM_MUTE);
            activeMuteMode = mode;
            if (isRunning) applyMuteMode(mode);
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            activeMuteMode = intent.getIntExtra(EXTRA_MUTE_MODE, MUTE_STREAM_MUTE);
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                lastError = "MediaProjection permission denied";
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIF_ID, buildNotification("Starting Shush Cast..."));
            startStreaming(resultCode, resultData);
        }
        return START_STICKY;
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shushcast:audio");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}

        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "shushcast:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {}
    }

    private void startStreaming(int resultCode, Intent resultData) {
        stopStreamingInternal();
        lastError = "";
        try {
            acquireLocks();

            if (server == null) {
                server = new WsHttpServer(this, PORT, count -> {
                    clientCount = count;
                    updateNotification();
                });
                server.start();
            }

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, null);

            saveTvAudioState();
            applyMuteMode(activeMuteMode);

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            int sampleRate = 48000;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, 3840 * 4);

            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord failed to initialize");
            }

            audioRecord.startRecording();
            capturing = true;
            isRunning = true;
            server.setCapturing(true);
            updateNotification();

            captureThread = new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                } catch (Throwable ignored) {}
                byte[] chunk = new byte[1920]; // 20ms @ 48kHz stereo 16-bit
                while (capturing) {
                    int n = audioRecord.read(chunk, 0, chunk.length);
                    if (n > 0) {
                        int maxAbs = 0;
                        for (int i = 0; i + 1 < n; i += 2) {
                            short s = (short) ((chunk[i] & 0xFF) | (chunk[i + 1] << 8));
                            int a = s < 0 ? -s : s;
                            if (a > maxAbs) maxAbs = a;
                        }
                        currentPeakPct = Math.min(100, (maxAbs * 100) / 32767);
                        server.broadcastAudio(chunk, n);
                    }
                }
            }, "shush-capture");
            captureThread.start();
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            stopSelf();
        }
    }

    private void saveTvAudioState() {
        if (audioManager == null) return;
        if (savedVolume == -1) {
            savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            savedMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        }
    }

    private void applyMuteMode(int mode) {
        if (audioManager == null) return;
        try {
            if (mode == MUTE_NONE) {
                if (savedMuted) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                } else {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    if (savedVolume >= 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
                    }
                }
            } else if (mode == MUTE_STREAM_MUTE) {
                if (savedVolume > 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
                }
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            } else if (mode == MUTE_ZERO_VOL) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void restoreTvAudioState() {
        if (audioManager == null || savedVolume == -1) return;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    savedMuted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
        } catch (Throwable ignored) {}
        savedVolume = -1;
    }

    private void stopStreamingInternal() {
        capturing = false;
        isRunning = false;
        currentPeakPct = 0;
        if (captureThread != null) {
            try { captureThread.join(400); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Throwable ignored) {}
            try { audioRecord.release(); } catch (Throwable ignored) {}
            audioRecord = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
            mediaProjection = null;
        }
        if (server != null) {
            server.setCapturing(false);
        }
        restoreTvAudioState();
        releaseLocks();
    }

    @Override
    public void onDestroy() {
        stopStreamingInternal();
        if (server != null) {
            server.stop();
            server = null;
        }
        clientCount = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Shush Cast", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification("Port " + PORT + " · Listeners: " + clientCount));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Shush Cast Active")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
