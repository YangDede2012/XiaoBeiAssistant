package com.xiaobei.assistant.ai;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 阿里云百炼·千问(Qwen) API 客户端
 * 兼容 DashScope OpenAI 兼容模式:
 *   POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * 模型 qwen-turbo (免费额度)
 *
 * v2 增强：支持携带多轮历史消息（最近对话记忆），同时保留单轮 chatOnce。
 */
public class QwenClient {
    private static final String TAG = "QwenClient";
    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-turbo";

    /** 一条历史消息（角色 + 内容） */
    public static final class Msg {
        public final String role;
        public final String content;

        private Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Msg user(String content) {
            return new Msg("user", content);
        }

        public static Msg assistant(String content) {
            return new Msg("assistant", content);
        }
    }

    /**
     * 单轮对话（不带历史，兼容旧调用）
     */
    public static String chatOnce(String apiKey, String question) throws Exception {
        return chat(apiKey, Collections.<Msg>emptyList(), question);
    }

    /**
     * 多轮对话：在 system + 最近历史 基础上追加本次问题。
     *
     * @param apiKey   阿里云百炼 API Key
     * @param history  最近几轮（不包含 system，通常是 user/assistant 交替）
     * @param question 用户本次问题
     */
    public static String chat(String apiKey, List<Msg> history, String question) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        payload.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", systemPrompt()));
        if (history != null) {
            for (Msg m : history) {
                messages.put(new JSONObject()
                        .put("role", m.role)
                        .put("content", m.content));
            }
        }
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", question));
        payload.put("messages", messages);

        JSONObject body = doPost(apiKey, payload.toString());

        // 提取 content
        JSONArray choices = body.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            JSONObject error = body.optJSONObject("error");
            String msg = error != null ? error.optString("message", "未知错误") : "空响应";
            throw new Exception("千问接口返回错误: " + msg);
        }
        JSONObject first = choices.optJSONObject(0);
        if (first == null) throw new Exception("千问接口返回异常");
        String content = first.optJSONObject("message").optString("content", "").trim();
        if (content.isEmpty()) {
            JSONObject error = body.optJSONObject("error");
            throw new Exception("千问无返回内容: " + (error != null ? error.optString("message", "") : ""));
        }
        return content;
    }

    /** 系统提示词，让 AI 以小北身份简洁作答 */
    private static String systemPrompt() {
        return "你是小北，一个安装在智能手表上的语音助手。"
                + "回答问题要简洁、口语化、温暖，一般不超过60个字；"
                + "适合语音播报，不用markdown，不用表情符号。"
                + "如果用户问时间、日期、简单算术或者想设定时的提醒，"
                + "这类问题通常已由手表本地功能处理，你只需继续对话；"
                + "涉及天气、知识问答、建议时再详细回答。"
                + "如果用户在延续上一轮的话题，请结合最近几轮的对话内容作答。";
    }

    private static JSONObject doPost(String apiKey, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(bytes, 0, bytes.length);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream()
                : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();

        String raw = sb.toString();
        if (raw.isEmpty()) {
            throw new Exception("网络连接失败 HTTP " + code);
        }
        Log.d(TAG, "resp(" + code + "): " + raw);
        return new JSONObject(raw);
    }
}
