package com.xiaobei.assistant.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.xiaobei.assistant.MainActivity;
import com.xiaobei.assistant.R;

/** 小北语音助手前台服务：托管 XiaoBeiEngine */
public class WakeWordService extends Service {
    public static final String CHANNEL_ID = "xiaobei_ch";
    public static final int NOTIFY_ID = 1;
    public static final int ST_WAKE = 0, ST_CMD = 1, ST_AI = 2, ST_ERR = -1;

    /** 服务是否在运行（可由 MainActivity 查询显示按钮） */
    public static volatile boolean running = false;

    public interface UiSink {
        void state(String t, int c);
        void line(String t);
        void model(String t);
    }

    private static volatile UiSink sink;
    public static void bindUi(UiSink s) { sink = s; }

    private XiaoBeiEngine engine;
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "小北助手",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台监听唤醒词");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        startForeground(NOTIFY_ID, notif("小北待命中"));
        if (it != null && it.getBooleanExtra("want_stop", false)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postState("缺少录音权限", ST_ERR);
            stopSelf();
            running = false;
            return START_NOT_STICKY;
        }
        if (engine == null) {
            engine = new XiaoBeiEngine(this, new XiaoBeiEngine.Cb() {
                @Override
                public void st(String s, int c) {
                    postState(s, c);
                }
                @Override
                public void ln(String s) {
                    UI(() -> { if (sink != null) sink.line(s); });
                }
                @Override
                public void md(String s) {
                    UI(() -> { if (sink != null) sink.model(s); });
                }
            });
            running = true;
            engine.start();
        } else {
            running = true;
        }
        return START_STICKY;
    }

    private void shrink() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
    }

    private void shutdown() {
        shrink();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        shrink();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent it) { return null; }

    private void UI(Runnable r) { h.post(r); }

    private void postState(String t, int c) {
        UI(() -> { if (sink != null) sink.state(t, c); });
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFY_ID, notif(t));
        } catch (Exception ignored) {}
    }

    private Notification notif(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent si = new Intent(this, WakeWordService.class);
        si.putExtra("want_stop", true);
        PendingIntent sp = PendingIntent.getService(this, 1, si,
                Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("小北助手")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .addAction(0, "停止", sp)
                .build();
    }
}
