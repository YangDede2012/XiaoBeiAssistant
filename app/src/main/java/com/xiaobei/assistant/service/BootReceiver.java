package com.xiaobei.assistant.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后自动启动小北服务（如果用户已勾选“开机自启”）。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String PREFS = "xiaobei_prefs";
    private static final String KEY_AUTO = "auto_start";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        boolean boot = action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
                || action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED);

        if (!boot) return;

        boolean auto = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO, false);
        if (auto) {
            try {
                Intent svc = new Intent(context, WakeWordService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                Log.i(TAG, "auto start xiaobei service");
            } catch (Exception e) {
                Log.e(TAG, "start fail", e);
            }
        }
    }
}
