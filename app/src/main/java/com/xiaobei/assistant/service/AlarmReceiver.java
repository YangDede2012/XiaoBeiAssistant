package com.xiaobei.assistant.service;

import com.xiaobei.assistant.MainActivity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import com.xiaobei.assistant.R;
import com.xiaobei.assistant.tts.TtsHelper;

/**
 * 闹钟/提醒到点广播：震动 + 短促提示音 + 中文语音播报 + 通知。
 */
public class AlarmReceiver extends BroadcastReceiver {
    public static final String EXTRA_CONTENT = "alarm_content";
    public static final String EXTRA_IS_ALARM = "alarm_is_alarm";
    public static final String CHANNEL_ID = "xiaobei_alarm_ch";
    public static final int NOTIFY_ID = 9090;

    /** 静态持有 TTS 防止广播过程被 GC 中断，播完会释放 */
    private static TtsHelper helper;

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        String content = "时间到了";
        boolean isAlarm = true;
        if (intent != null) {
            String cc = intent.getStringExtra(EXTRA_CONTENT);
            if (cc != null && !cc.trim().isEmpty()) content = cc.trim();
            isAlarm = intent.getBooleanExtra(EXTRA_IS_ALARM, true);
        }

        // 1. 震动提醒
        vibrate(c);

        // 2. 短促提示音（闹钟模式）
        if (isAlarm) {
            // 先震动+响铃，等约 1 秒再开始语音，避免互相遮盖
            ring(c);
            final String __xbcContent = content;
            delayed(1200L, () -> speak(c, "小北提醒你，" + __xbcContent));
        } else {
            speak(c, "小北提醒你，" + content);
        }

        // 3. 高优先级通知（守护进程即使未启动也能看到）
        notifyUser(c, "小北提醒", "⏰ " + content);
    }

    private void vibrate(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 500, 250, 500, 250, 700}, -1));
            } else {
                v.vibrate(new long[]{0, 500, 250, 500, 250, 700}, -1);
            }
        } catch (Exception ignored) {}
    }

    private void ring(Context c) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) return;
            final Ringtone r = RingtoneManager.getRingtone(c, uri);
            if (r == null) return;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
            // 8 秒后停止
            delayed(8000L, () -> {
                try { if (r.isPlaying()) r.stop(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** 中文播报，播完后自动释放 helper */
    private void speak(final Context c, final String text) {
        try {
            if (helper != null) {
                helper.shutdown();
                helper = null;
            }
            helper = new TtsHelper(c.getApplicationContext());
            helper.speak(text, () -> {
                delayed(1000L, () -> {
                    try {
                        if (helper != null) {
                            helper.shutdown();
                            helper = null;
                        }
                    } catch (Exception ignored) {}
                });
            });
        } catch (Exception ignored) {}
    }

    private void delayed(long ms, Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, ms);
    }

    private void notifyUser(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "小北提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("闹钟与提醒到点通知");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(c, MainActivity.class);
            int flags = Build.VERSION.SDK_INT >= 31
                    ? android.app.PendingIntent.FLAG_IMMUTABLE : 0;
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    c, 0, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | flags);
            Notification n = new NotificationCompat.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_notify)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(NOTIFY_ID, n);
        } catch (Exception ignored) {}
    }
}
