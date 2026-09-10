package com.xiaobei.assistant.tts;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;

/**
 * 中文语音播报封装：小北说话。
 * 自动等待系统 TTS 引擎就绪后开始朗读。
 * 一条播报完成后回调 onDone()。
 */
public class TtsHelper {
    private static final String TAG = "TtsHelper";
    private final Context context;
    private TextToSpeech tts;
    private boolean ready = false;
    private Runnable pendingText = null;
    private Runnable pendingDone = null;
    private boolean speaking = false;

    public TtsHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        init();
    }

    private void init() {
        tts = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS engine init failed status=" + status);
                return;
            }
            // 优先中文
            int zh = tts.setLanguage(Locale.CHINESE);
            if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Chinese not supported, fallback English");
                tts.setLanguage(Locale.US);
            } else {
                // 尝试中文变体
                try {
                    tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                } catch (Exception ignore) {}
            }
            ready = true;
            Log.d(TAG, "TTS ready");
            // 如果有等着的播报就开始
            if (pendingText != null) {
                Runnable r = pendingText;
                pendingText = null;
                r.run();
            }

            // 设置监听：当一次播报完成时回调
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    speaking = true;
                }

                @Override
                public void onDone(String utteranceId) {
                    speaking = false;
                    mainPost(() -> {
                        if (pendingDone != null) {
                            Runnable done = pendingDone;
                            pendingDone = null;
                            done.run();
                        }
                    });
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String utteranceId) {
                    speaking = false;
                    mainPost(() -> {
                        if (pendingDone != null) {
                            Runnable done = pendingDone;
                            pendingDone = null;
                            done.run();
                        }
                    });
                }
            });
        });
    }

    private void mainPost(Runnable r) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(r);
    }

    /**
     * 朗读一句话，完成后（可选）触发 onDone 回调。
     */
    public void speak(String text, Runnable onDone) {
        if (pendingDone != null) return; // 仍在处理上一句
        this.pendingDone = onDone;

        if (!ready || tts == null) {
            // 还没就绪，先缓存文本
            pendingText = () -> doSpeak(text);
            return;
        }
        doSpeak(text);
    }

    private void doSpeak(String text) {
        if (tts == null || text == null || text.isEmpty()) {
            if (pendingDone != null) {
                Runnable done = pendingDone;
                pendingDone = null;
                done.run();
            }
            return;
        }
        // 中文 TTS 播报
        String utteranceId = "xiaobei" + System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= 21) {
            Bundle b = new Bundle();
            b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, utteranceId);
            if (res == TextToSpeech.ERROR) {
                fail();
            }
        } else {
            @SuppressWarnings("deprecation")
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
            if (res == TextToSpeech.ERROR) {
                fail();
            }
        }
    }

    private void fail() {
        speaking = false;
        mainPost(() -> {
            if (pendingDone != null) {
                Runnable done = pendingDone;
                pendingDone = null;
                done.run();
            }
        });
    }

    /** 停止所有播报并释放引擎 */
    public void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignore) {}
            try {
                tts.shutdown();
            } catch (Exception ignore) {}
            tts = null;
        }
        ready = false;
        pendingText = null;
        pendingDone = null;
    }

    /** 是否正在说话 */
    public boolean isSpeaking() {
        return speaking;
    }
}
