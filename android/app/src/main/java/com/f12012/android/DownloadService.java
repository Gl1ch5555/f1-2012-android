package com.f12012.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps F1 2012 game downloads and ISO extraction running
 * reliably in the background with WakeLock/WifiLock and live notification progress.
 */
public final class DownloadService extends Service {
    private static final String CHANNEL_ID = "f1_2012_download";
    private static final int NOTIF_ID = 4347;
    private static final int RESULT_NOTIF_ID = 4348;

    static final String ACTION_START = "com.f12012.android.action.START_DOWNLOAD";
    static final String ACTION_CANCEL = "com.f12012.android.action.CANCEL_DOWNLOAD";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TARGET_DIR = "target_dir";

    interface Listener {
        void onProgress(String statusText, int percent);
        void onSuccess();
        void onError(String message);
        void onCancelled();
    }

    private static volatile boolean running;
    private static volatile String lastStatus = "";
    private static volatile int lastPercent;
    private static volatile Listener activeListener;
    private static volatile String pendingOutcome; // "success" | "cancelled" | "error:<msg>"
    private static final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotifMs;

    static void start(Context ctx, String url, File targetDir) {
        Intent i = new Intent(ctx, DownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TARGET_DIR, targetDir.getAbsolutePath());
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    static void cancel(Context ctx) {
        cancelFlag.set(true);
        Intent i = new Intent(ctx, DownloadService.class).setAction(ACTION_CANCEL);
        ctx.startService(i);
    }

    static boolean isRunning() {
        return running;
    }

    static String getLastStatus() {
        return lastStatus;
    }

    static int getLastPercent() {
        return lastPercent;
    }

    static void setListener(Listener listener) {
        activeListener = listener;
        if (listener != null) {
            if (running) {
                listener.onProgress(lastStatus, lastPercent);
            } else if (pendingOutcome != null) {
                String outcome = pendingOutcome;
                pendingOutcome = null;
                if ("success".equals(outcome)) {
                    listener.onSuccess();
                } else if ("cancelled".equals(outcome)) {
                    listener.onCancelled();
                } else if (outcome.startsWith("error:")) {
                    listener.onError(outcome.substring(6));
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelFlag.set(true);
            lastStatus = "Отмена загрузки...";
            updateNotification(lastStatus, lastPercent);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (running) {
                return START_NOT_STICKY;
            }
            String url = intent.getStringExtra(EXTRA_URL);
            String targetPath = intent.getStringExtra(EXTRA_TARGET_DIR);
            if (url == null || targetPath == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            cancelFlag.set(false);
            pendingOutcome = null;
            running = true;
            lastStatus = "Подготовка к скачиванию...";
            lastPercent = 0;

            Notification notif = buildNotification(lastStatus, 0);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notif);
            }
            acquireLocks();

            File targetDir = new File(targetPath);
            executor.execute(() -> runDownload(url, targetDir));
        }
        return START_NOT_STICKY;
    }

    private void runDownload(String url, File targetDir) {
        try {
            GameDownloader.downloadAndInstall(
                    getApplicationContext(),
                    url,
                    targetDir,
                    cancelFlag,
                    new GameDownloader.Listener() {
                        @Override
                        public void onProgress(String statusText, int percent) {
                            lastStatus = statusText;
                            lastPercent = percent;
                            long now = System.currentTimeMillis();
                            if (now - lastNotifMs >= 600) {
                                lastNotifMs = now;
                                updateNotification(statusText, percent);
                            }
                            Listener l = activeListener;
                            if (l != null) {
                                l.onProgress(statusText, percent);
                            }
                        }

                        @Override
                        public void onSuccess() {
                            running = false;
                            Listener l = activeListener;
                            if (l != null) {
                                l.onSuccess();
                            } else {
                                pendingOutcome = "success";
                                showResultNotification("F1 2012 готова к запуску", "Файлы игры скачаны и распакованы.");
                            }
                        }

                        @Override
                        public void onError(String message) {
                            running = false;
                            if (cancelFlag.get()) {
                                Listener l = activeListener;
                                if (l != null) {
                                    l.onCancelled();
                                } else {
                                    pendingOutcome = "cancelled";
                                }
                                return;
                            }
                            Listener l = activeListener;
                            if (l != null) {
                                l.onError(message);
                            } else {
                                pendingOutcome = "error:" + message;
                                showResultNotification("Ошибка загрузки F1 2012", message);
                            }
                        }
                    }
            );
        } finally {
            running = false;
            releaseLocks();
            stopForeground(true);
            stopSelf();
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F12012:DownloadWakeLock");
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "F12012:DownloadWifiLock");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
        wifiLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Загрузка F1 2012", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Прогресс скачивания и распаковки игры в фоне");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification(String text, int percent) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancelIntent, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("F1 2012 — загрузка игры")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .setProgress(100, Math.max(0, Math.min(100, percent)), percent <= 0)
                .addAction(new Notification.Action.Builder(null, "Отмена", cancelPi).build());
        return b.build();
    }

    private void updateNotification(String text, int percent) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text, percent));
        }
    }

    private void showResultNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, openIntent, piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentPi);
        nm.notify(RESULT_NOTIF_ID, b.build());
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
