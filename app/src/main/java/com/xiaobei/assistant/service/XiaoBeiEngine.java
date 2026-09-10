package com.xiaobei.assistant.service;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.xiaobei.assistant.ai.QwenClient;
import com.xiaobei.assistant.tts.TtsHelper;
import com.xiaobei.assistant.util.AlarmHelper;
import com.xiaobei.assistant.util.LocalCmd;
import com.xiaobei.assistant.util.ModelManager;
import com.xiaobei.assistant.util.Prefs;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 小北引擎：唤醒 → 聆听 → 语音识别 → (本地快捷指令 / 闹钟提醒 / 千问AI) → 语音播报
 * 唤醒词必须成对说出"小北 小北"（文本层归一化检测，兼容所有vosk版本）。
 *
 * v2 增强：
 *   1. 本地快捷指令（时间/日期/计算/问候/退出）—— 断网也能秒回
 *   2. 真实闹钟提醒（"十分钟后提醒我喝水" → AlarmManager 到点响铃）
 *   3. 多轮对话记忆：最近若干轮上下文自动带给千问，唤醒后可续聊上一个话题
 */
public class XiaoBeiEngine {
    private static final String TAG = "XiaoBeiEngine";
    public static final int ST_WAKE = 0, ST_CMD = 1, ST_AI = 2, ST_ERR = -1;
    private static final int SR = 16000;
    /** 归一化后的标准双呼唤醒词 */
    private static final String WAKE_BI = "小北小北";
    /** 记忆保持时长：超过 30 分钟没聊则自动清空上下文 */
    private static final long MEMORY_TTL = 30 * 60 * 1000L;

    public interface Cb {
        void st(String s, int c);
        void ln(String s);
        void md(String s);
    }

    private final Context ctx;
    private final Cb cb;
    private TtsHelper tts;
    private Thread th;
    private volatile boolean run, busy, listen, voiceOn;
    private long listenStart, lastVoice;
    private Model model;
    private AudioRecord mic;
    private final List<byte[]> buf = new ArrayList<>();

    // ---------- 多轮对话记忆 ----------
    private final List<QwenClient.Msg> history = new ArrayList<>();
    private long lastTalk = 0;

    public XiaoBeiEngine(Context c, Cb h) {
        ctx = c.getApplicationContext();
        cb = h;
    }

    public void start() {
        if (run) return;
        run = true;
        th = new Thread(this::loop, "XB");
        th.start();
    }

    public void stop() {
        run = false;
        busy = false;
        listen = false;
        if (th != null) { th.interrupt(); th = null; }
        closeMic();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    private synchronized TtsHelper tts() {
        if (tts == null) tts = new TtsHelper(ctx);
        return tts;
    }

    // ------------------------------------------------------------------ 主循环
    private void loop() {
        try {
            if (!ModelManager.isModelReady(ctx)) {
                cb.st("语音模型未安装", ST_ERR);
                cb.ln("[系统] 请在小北界面点击①下载语音模型");
                return;
            }
            cb.st("正在加载语音引擎…", ST_CMD);
            cb.md("加载中文模型…");
            model = new Model(new File(ModelManager.getModelPath(ctx)).getAbsolutePath());
            cb.md("模型加载完成");
            if (!openMic()) {
                cb.md("麦克风开启失败");
                cb.st("麦克风失败", ST_ERR);
                return;
            }
            idle();

            Recognizer wake = wakeRec();
            byte[] rb = new byte[3200];
            while (run && wake != null) {
                if (busy) { sleep(80); continue; }
                if (listen) { collect(); continue; }

                int n = mic.read(rb, 0, rb.length);
                if (n <= 0) continue;
                byte[] d = Arrays.copyOf(rb, n);

                boolean hit;
                if (wake.acceptWaveForm(d, d.length)) {
                    hit = hitWake(wake.getResult());
                    wake = wakeRec();            // 一段落结束，重建识别器
                } else {
                    hit = hitWake(wake.getPartialResult());
                }
                if (hit) {
                    wake = wakeRec();
                    wakeUp();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loop", e);
            cb.md("引擎异常: " + e.getMessage());
        } finally {
            closeMic();
            run = false;
        }
    }

    private void idle() {
        cb.st("说「小北 小北」唤醒我", ST_WAKE);
        cb.ln("“小北 小北”随时唤我");
    }

    /** 将 vosk JSON 文本归一化后检测是否为双呼唤醒 */
    private boolean hitWake(String voskJson) {
        if (voskJson == null) return false;
        String raw = voskJson;
        try {
            raw = new JSONObject(voskJson).optString("text", "");
        } catch (Exception ignored) {}
        String norm = raw.replace(" ", "")
                .replace("　", "")
                .replace("，", "")
                .replace(",", "");
        return norm.contains(WAKE_BI);
    }

    private void wakeUp() {
        // 距上次对话超过记忆时长则清空上下文
        if (now() - lastTalk > MEMORY_TTL) {
            history.clear();
        }
        cb.ln("【唤醒成功】");
        cb.st("在呢，请说…", ST_CMD);
        cb.md("聆听中…");
        tts().speak("在呢", null);
        // 等“在呢”播完再开麦，避免助手自身声音被误收为指令
        long t0 = now();
        while (tts().isSpeaking() && now() - t0 < 5000) sleep(80);
        sleep(300);
        if (!run) return;
        listen = true;
        voiceOn = false;
        buf.clear();
        listenStart = now();
    }

    // -------------------------------------------------------------- 收集语音
    private void collect() {
        byte[] rb = new byte[3200];
        int n = mic.read(rb, 0, rb.length);
        if (n <= 0) return;
        byte[] d = Arrays.copyOf(rb, n);
        long t = now();

        if (t - listenStart > 15000) {
            listen = false;
            cb.ln("(超时回待命)");
            idle();
            return;
        }
        buf.add(d);
        while (buf.size() > 320) buf.remove(0);

        boolean loud = isLoud(d);
        if (!voiceOn) {
            if (loud) {
                voiceOn = true;
                lastVoice = t;
                cb.st("在听…", ST_CMD);
            }
        } else {
            if (loud) lastVoice = t;
            else if (t - lastVoice > 1300 || buf.size() > 280) toAI();
        }
    }

    private void toAI() {
        listen = false;
        busy = true;
        byte[] pcm = concat(buf);
        buf.clear();
        cb.st("听清了，正在理解…", ST_AI);
        new Thread(() -> {
            try {
                String txt = recPcm(pcm);
                if (txt.isEmpty()) {
                    tts().speak("我没听清，请再说一次", this::done);
                    cb.ln("(没听清)");
                    return;
                }
                cb.ln("你：" + txt);
                lastTalk = now();

                // 1. 本地快捷指令（断网也能秒回）
                LocalCmd.Result lc = LocalCmd.tryHandle(txt);
                if (lc.handled) {
                    cb.ln("小北：" + lc.speak);
                    cb.st("小北回答中…", ST_AI);
                    if (lc.backIdle) {
                        history.clear();      // “退出/再见”后清空上下文
                    }
                    tts().speak(lc.speak, this::done);
                    return;
                }

                // 2. 真实闹钟/提醒（"十分钟后提醒我喝水"）
                AlarmHelper.Parsed al = AlarmHelper.parse(txt);
                if (al != null) {
                    AlarmHelper.schedule(ctx, al);
                    String conf = AlarmHelper.confirmText(al);
                    cb.ln("小北：" + conf);
                    cb.st("提醒已设置", ST_AI);
                    tts().speak(conf, this::done);
                    return;
                }

                // 3. 千问 AI（携带最近多轮上下文）
                askAI(txt);
            } catch (Exception e) {
                Log.e(TAG, "rec", e);
                done();
            }
        }, "Rec").start();
    }

    // ---------------------------------------------------------------- 千问AI
    private void askAI(String q) {
        cb.st("小北思考中…", ST_AI);
        new Thread(() -> {
            try {
                if (!run) return;
                String key = Prefs.getApiKey(ctx);
                if (key.isEmpty()) {
                    tts().speak("请先在小北界面设置API密钥", this::done);
                    cb.ln("[系统] 未设置千问Key");
                    return;
                }
                // 传最近上下文给千问
                String a = QwenClient.chat(key, history, q);
                if (!run) return;
                cb.ln("小北：" + a);
                cb.st("小北回答中…", ST_AI);
                remember(q, a);
                tts().speak(a, this::done);
            } catch (Exception e) {
                Log.e(TAG, "AI", e);
                if (run) tts().speak("网络连接失败，请稍后再试", this::done);
            }
        }, "AI").start();
    }

    /** 记录当前问答到最近记忆（上限 8 条，防止上下文过长） */
    private void remember(String q, String a) {
        synchronized (history) {
            history.add(QwenClient.Msg.user(q));
            history.add(QwenClient.Msg.assistant(a));
            while (history.size() > 8) {
                history.remove(0);
            }
        }
    }

    private void done() {
        busy = false;
        if (run) idle();
    }

    // ---------------------------------------------------------------- 硬件
    private boolean openMic() {
        closeMic();
        int m = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (m <= 0) m = SR * 2;
        try {
            mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(m, SR * 2));
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic = null;
                return false;
            }
            mic.startRecording();
            return mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        } catch (Exception e) {
            closeMic();
            return false;
        }
    }

    private void closeMic() {
        if (mic != null) {
            try { mic.stop(); } catch (Exception ignored) {}
            try { mic.release(); } catch (Exception ignored) {}
            mic = null;
        }
    }

    /** 一律使用双参构造，兼容各 vosk-android 版本 */
    private Recognizer wakeRec() {
        if (model == null) return null;
        try {
            return new Recognizer(model, SR);
        } catch (Exception e) {
            return null;
        }
    }

    /** 将整段pcm送入识别器返回一句话 */
    private String recPcm(byte[] pcm) {
        if (pcm.length < 1600) return "";
        try (Recognizer r = new Recognizer(model, SR)) {
            StringBuilder s = new StringBuilder();
            int off = 0;
            while (off < pcm.length) {
                int n = Math.min(8000, pcm.length - off);
                byte[] chunk = Arrays.copyOfRange(pcm, off, off + n);
                if (r.acceptWaveForm(chunk, chunk.length)) {
                    s.append(textOf(r.getResult())).append(' ');
                }
                off += n;
            }
            s.append(textOf(r.getFinalResult()));
            return s.toString().trim().replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "";
        }
    }

    private String textOf(String json) {
        try {
            return new JSONObject(json).optString("text", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isLoud(byte[] d) {
        long sum = 0;
        int c = 0;
        for (int i = 0; i + 1 < d.length; i += 2) {
            sum += Math.abs((short) ((d[i + 1] << 8) | (d[i] & 0xFF)));
            c++;
        }
        return c > 0 && sum / c > 800;
    }

    private byte[] concat(List<byte[]> l) {
        int total = 0;
        for (byte[] b : l) total += b.length;
        byte[] o = new byte[total];
        int p = 0;
        for (byte[] b : l) {
            System.arraycopy(b, 0, o, p, b.length);
            p += b.length;
        }
        return o;
    }

    private long now() { return System.currentTimeMillis(); }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }
}
