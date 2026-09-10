package com.xiaobei.assistant.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 小北助手的 SharedPreferences 封装 */
public class Prefs {
    private static final String NAME = "xiaobei_prefs";
    private static final String KEY_API = "api_key";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getApiKey(Context c) {
        return sp(c).getString(KEY_API, "");
    }

    public static void setApiKey(Context c, String key) {
        sp(c).edit().putString(KEY_API, key == null ? "" : key.trim()).apply();
    }
}
