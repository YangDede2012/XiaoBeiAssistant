package com.xiaobei.assistant.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小北本地快捷指令：时间/日期/简单四则运算/退出/帮助。
 * 不依赖千问 AI，断网也能秒回，适合手表高频问答。
 */
public class LocalCmd {

    /** 处理结果：handled=true 表示已本地处理 */
    public static final class Result {
        public final boolean handled;
        public final String speak;
        /** 是否需要结束当前对话，返回待命 */
        public final boolean backIdle;

        private Result(boolean handled, String speak, boolean backIdle) {
            this.handled = handled;
            this.speak = speak;
            this.backIdle = backIdle;
        }

        public static Result none() {
            return new Result(false, "", false);
        }

        public static Result say(String s) {
            return new Result(true, s, false);
        }

        public static Result moveOn(String s) {
            return new Result(true, s, true);
        }
    }

    private static final Pattern TIME_ASK = Pattern.compile(
            "(几点|什么时间|现在时间|几点了|报时)");
    private static final Pattern DATE_ASK = Pattern.compile(
            "(几号|什么日期|今天日期|星期几|今天是周几)");
    private static final Pattern HELLO_ASK = Pattern.compile(
            "(你好|您好|嗨|哈喽|在呢|在吗)");
    private static final Pattern WHO_ASK = Pattern.compile(
            "(你是谁|你叫什么|你叫啥|介绍一下你)");
    private static final Pattern ABLE_ASK = Pattern.compile(
            "(你会什么|你能做什么|你能干什么|有什么功能|看看你|怎么用你)");
    private static final Pattern QUIT_ASK = Pattern.compile(
            "(退出|没事了|再见|拜拜|不问了|就这样|没有别的事了|结束对话|退下)");
    private static final Pattern THANKS = Pattern.compile(
            "(谢谢|多谢|感谢|辛苦你)");

    /**
     * 尝试本地处理用户语料。
     *
     * @param text 语音识别结果
     * @return 处理结果；不能处理时返回 Result.none()
     */
    public static Result tryHandle(String text) {
        if (text == null) return Result.none();
        String t = text.trim();

        // 退出/结束对话 —— 直接回到待命，不请求 AI
        if (t.length() <= 12 && QUIT_ASK.matcher(t).find()) {
            return Result.moveOn("好的，我在待命");
        }

        // 你是谁
        if (t.length() <= 12 && WHO_ASK.matcher(t).find()) {
            return Result.say("我是小北，你的手表语音助手");
        }

        // 礼貌问候
        if (t.length() <= 10 && HELLO_ASK.matcher(t).find()) {
            return Result.say("你好呀，我是小北。你可以问我现在几点、今天是几号，也可以让我算算数");
        }

        // 帮助
        if (t.length() <= 20 && ABLE_ASK.matcher(t).find()) {
            return Result.say("你可以对我说：现在几点、今天几号、帮我算一算，或者其他想问的问题");
        }

        // 感谢
        if (t.length() <= 10 && THANKS.matcher(t).find()) {
            return Result.say("不客气，随时叫我");
        }

        // 时间
        if (t.length() <= 14 && TIME_ASK.matcher(t).find()) {
            return Result.say(currentTime());
        }

        // 日期
        if (t.length() <= 16 && DATE_ASK.matcher(t).find()) {
            return Result.say(currentDate());
        }

        // 计算
        String calc = tryCalc(t);
        if (calc != null) {
            return Result.say(calc);
        }

        return Result.none();
    }

    /** 当前时间文本：口语化时段 */
    private static String currentTime() {
        Date d = new Date();
        int hour = Integer.parseInt(new SimpleDateFormat("H", Locale.CHINA).format(d));
        int minute = Integer.parseInt(new SimpleDateFormat("mm", Locale.CHINA).format(d));
        String period = hour < 6 ? "凌晨" : hour < 9 ? "早上" : hour < 12 ? "上午"
                : hour < 14 ? "中午" : hour < 18 ? "下午" : "晚上";
        int h12 = hour % 12;
        if (h12 == 0) h12 = 12;
        String minuteWord;
        if (minute == 0) {
            minuteWord = "整";
        } else if (minute < 10) {
            minuteWord = "零" + toCn(String.valueOf(minute)) + "分";
        } else {
            minuteWord = toCn(String.valueOf(minute)) + "分";
        }
        return "现在是" + period + toCn(String.valueOf(h12)) + "点" + minuteWord;
    }

    /** 当前日期文本 */
    private static String currentDate() {
        Date d = new Date();
        String md = new SimpleDateFormat("M月d日", Locale.CHINA).format(d);
        String week = new SimpleDateFormat("EEEE", Locale.CHINA).format(d);
        week = week.replace("礼拜", "星期").replace("周", "星期");
        if (week.length() > 3 && week.startsWith("星期")) {
            week = "星期" + week.substring(2, 3);
        }
        return "今天是" + md + "，" + week;
    }

    /** 数字转中文（0~99） */
    private static String toCn(String num) {
        if (num == null || num.isEmpty()) return "";
        if (!num.matches("\\d+")) return num;
        int v;
        try {
            v = Integer.parseInt(num);
        } catch (Exception e) {
            return num;
        }
        if (v == 0) return "零";
        if (v < 10) return new String[]{"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"}[v];
        String[] ge = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (v < 20) return "十" + ge[v % 10];
        int shi = v / 10;
        int g = v % 10;
        return ge[shi] + "十" + (g == 0 ? "" : ge[g]);
    }

    /** 尝试提取并计算简单四则运算 */
    private static String tryCalc(String raw) {
        String t = raw;
        // 中文运算词转符号
        t = t.replace("加上", "+").replace("加", "+")
                .replace("减去", "-").replace("减", "-")
                .replace("乘以", "*").replace("乘", "*")
                .replace("×", "*").replace("x", "*").replace("X", "*")
                .replace("除以", "/").replace("除", "/").replace("÷", "/");

        // 中文括号转英文
        t = t.replace("（", "(").replace("）", ")");

        // 去掉问句尾巴与无关字
        t = t.replaceAll("[等于多少几？?了呀吧帮算下给是]", "");

        // 只要还残留明显汉字，就不当作纯算式
        if (t.matches(".*[\\u4e00-\\u9fa5].*")) return null;

        // 必须包含运算符和至少两个数字
        if (!t.matches(".*[+\\-*/%].*")) return null;
        Matcher dm = Pattern.compile("\\d").matcher(t);
        int digitCount = 0;
        while (dm.find()) digitCount++;
        if (digitCount < 2) return null;

        try {
            double r = evaluate(t);
            if (Double.isNaN(r) || Double.isInfinite(r)) return null;
            long lr = Math.round(r);
            String out;
            if (Math.abs(r - lr) < 1e-9) {
                out = String.valueOf(lr);
            } else {
                out = String.format(Locale.CHINA, "%.2f", r)
                        .replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return "等于" + out;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------ 简单递归下降表达式求值
    private static int cpos;
    private static String csrc;

    private static double evaluate(String s) {
        csrc = s;
        cpos = 0;
        double v = cexp();
        cskip();
        if (cpos < csrc.length()) throw new IllegalArgumentException("表达式不完整");
        return v;
    }

    private static double cexp() {
        double v = cterm();
        for (;;) {
            cskip();
            if (cpos < csrc.length()) {
                char c = csrc.charAt(cpos);
                if (c == '+') { cpos++; v += cterm(); continue; }
                if (c == '-') { cpos++; v -= cterm(); continue; }
            }
            return v;
        }
    }

    private static double cterm() {
        double v = cunary();
        for (;;) {
            cskip();
            if (cpos < csrc.length()) {
                char c = csrc.charAt(cpos);
                if (c == '*') { cpos++; v *= cunary(); continue; }
                if (c == '/') { cpos++; double d = cunary(); v /= d; continue; }
                if (c == '%') { cpos++; double d = cunary(); v %= d; continue; }
            }
            return v;
        }
    }

    private static double cunary() {
        cskip();
        if (cpos < csrc.length() && (csrc.charAt(cpos) == '-' || csrc.charAt(cpos) == '+')) {
            char op = csrc.charAt(cpos++);
            double v = cunary();
            return op == '-' ? -v : v;
        }
        return cnum();
    }

    private static double cnum() {
        cskip();
        if (cpos >= csrc.length()) throw new IllegalArgumentException("缺少数字");
        if (csrc.charAt(cpos) == '(') {
            cpos++;
            double v = cexp();
            cskip();
            if (cpos >= csrc.length() || csrc.charAt(cpos) != ')')
                throw new IllegalArgumentException("括号不匹配");
            cpos++;
            return v;
        }
        int start = cpos;
        while (cpos < csrc.length()
                && (Character.isDigit(csrc.charAt(cpos)) || csrc.charAt(cpos) == '.')) cpos++;
        if (start == cpos) throw new IllegalArgumentException("非法字符");
        return Double.parseDouble(csrc.substring(start, cpos));
    }

    private static void cskip() {
        while (cpos < csrc.length() && Character.isWhitespace(csrc.charAt(cpos))) cpos++;
    }
}
