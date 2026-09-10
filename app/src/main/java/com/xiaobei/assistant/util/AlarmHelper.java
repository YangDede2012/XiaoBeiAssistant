package com.xiaobei.assistant.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.xiaobei.assistant.service.AlarmReceiver;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小北闹钟/提醒管理器：解析语音中"几分钟后/几点几分/下午三点"等说法，
 * 通过系统 AlarmManager 真正定时，到点由 AlarmReceiver 响铃+语音+震动。
 *
 * 支持说法（阿拉伯数字与中文数字均可）：
 *   - "十分钟后提醒我喝水"         → 相对提醒
 *   - "5分钟后叫我"               → 相对提醒
 *   - "下午三点提醒我开会"          → 绝对时间
 *   - "明早七点叫我起床"            → 次日绝对时间
 */
public class AlarmHelper {
    private static final String TAG = "AlarmHelper";

    /** 解析结果 */
    public static final class Parsed {
        public final long triggerAt;      // 触发时间戳
        public final String content;      // 提醒内容（无则默认）
        public final boolean isAlarm;     // true=闹钟响铃, false=仅通知/播报

        private Parsed(long triggerAt, String content, boolean isAlarm) {
            this.triggerAt = triggerAt;
            this.content = content;
            this.isAlarm = isAlarm;
        }
    }

    /** 相对时间："X后" */
    private static final Pattern REL = Pattern.compile(
            "(?:过|等|再|在)?(\\d{1,3})\\s*(秒|分钟|小时|个?钟头)后");
    /** 绝对时间："X点Y分" */
    private static final Pattern ABS = Pattern.compile(
            "(?:凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|明早|明晚|明天|今晚|现在)?\\s*"
                    + "(\\d{1,2})\\s*点\\s*(?:(\\d{1,2})\\s*分?)?");
    /** 纯"X分钟"（不带"后"也可） */
    private static final Pattern MIN = Pattern.compile(
            "(\\d{1,2})分(?:钟)?(?:后|以后)?");
    /** 纯"X小时" */
    private static final Pattern HOUR = Pattern.compile(
            "(\\d{1,2})个?(?:小时|钟头)(?:后|以后)?");

    // 提醒关键词
    private static final Pattern REMIND_WORD = Pattern.compile(
            "(提醒|叫我|叫我起床|喊我|闹钟|起床|报到|告诉我)");

    // 中文数字（把"下午三点""十分钟后"等里的汉字数转阿拉伯）
    private static final Pattern CN_NUM_BEFORE_UNIT = Pattern.compile(
            "([零一二两三四五六七八九十]{1,3})(?=[点分时十小月])");
    private static final char[] CN_GE = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    /**
     * 尝试解析设置提醒的语音并返回是否成功。
     *
     * @param text 已识别文本
     * @return 解析结果；null 表示不匹配
     */
    public static Parsed parse(String text) {
        if (text == null) return null;
        String t = text.trim()
                .replace(" ", "")
                .replace("，", "").replace(",", "")
                .replace("您", "你").replace("一下", "")
                .replace("个", "");          // "三个小时后" -> "三小时后"
        // 汉字数字转阿拉伯（只转换"X点/X分/X小时"前的数字，避免误伤普通中文）
        t = cnDigitsToNum(t);

        // 必须含"提醒/闹钟/叫我/叫我起床/喊我"等
        boolean hasCmd = REMIND_WORD.matcher(t).find()
                || t.contains("起床") || t.contains("叫我") || t.contains("喊我");
        if (!hasCmd) return null;

        // ---- 相对时间优先（例：十分钟后 / 5分钟后 / 1小时后）
        Matcher rel = REL.matcher(t);
        if (rel.find()) {
            int n = parseIntSafe(rel.group(1));
            String unit = rel.group(2);
            long ms;
            if (unit.startsWith("秒")) ms = n * 1000L;
            else if (unit.startsWith("分钟") || unit.contains("分钟"))
                ms = n * 60_000L;
            else if (unit.contains("小时") || unit.contains("钟头"))
                ms = n * 3600_000L;
            else ms = 0;
            if (ms <= 0) return null;
            return new Parsed(System.currentTimeMillis() + ms, extractContent(t), false);
        }

        // 相对分钟/小时（不带"后"的词组）
        Matcher min = MIN.matcher(t);
        if (min.find()) {
            // 排除"12点30分"这类绝对时间
            if (!t.matches(".*\\d+\\s*点.*")) {
                int n = parseIntSafe(min.group(1));
                if (n > 0 && n <= 60) {
                    return new Parsed(System.currentTimeMillis() + n * 60_000L,
                            extractContent(t), false);
                }
            }
        }
        Matcher hour = HOUR.matcher(t);
        if (hour.find()) {
            int n = parseIntSafe(hour.group(1));
            if (n > 0 && n <= 24) {
                return new Parsed(System.currentTimeMillis() + n * 3600_000L,
                        extractContent(t), false);
            }
        }

        // ---- 绝对时间（例：下午三点提醒我 / 明早七点叫我 / 晚上8点30分提醒）
        Matcher abs = ABS.matcher(t);
        if (abs.find()) {
            int hh = parseIntSafe(abs.group(1));
            if (hh < 0) return null;
            int mm = 0;
            String mmStr = abs.group(2);
            if (mmStr != null) mm = parseIntSafe(mmStr);
            if (mm < 0 || hh > 23 || mm > 59) return null;

            boolean afterNoon = t.contains("下午") || t.contains("傍晚") || t.contains("晚上")
                    || t.contains("明晚") || t.contains("今晚");
            boolean morning = t.contains("早上") || t.contains("早晨") || t.contains("上午")
                    || t.contains("凌晨") || t.contains("明早");
            if (afterNoon && hh < 12) hh += 12;
            if (!morning && !afterNoon && t.contains("中午") && hh < 11) hh += 12;

            // 是否"明天/明早/明晚"
            boolean tomorrow = t.contains("明天") || t.contains("明早") || t.contains("明晚");
            long target = targetMillis(hh, mm, tomorrow);
            if (target <= 0) return null;
            return new Parsed(target, extractContent(t), true);
        }

        return null;
    }

    /**
     * 把"下午三点""十分钟后""十二点"里的汉字数字转成阿拉伯数字。
     * 仅转换后跟 点/分/时/十/小/月 的文字数字段，避免误伤普通中文。
     */
    private static String cnDigitsToNum(String s) {
        Matcher m = CN_NUM_BEFORE_UNIT.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String cn = m.group(1);
            int v = cnVal(cn);
            if (v >= 0) {
                m.appendReplacement(sb, String.valueOf(v));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 中文数字转数值（支持零~九十九，"两"按2处理） */
    private static int cnVal(String cn) {
        if (cn == null || cn.isEmpty()) return -1;
        if (cn.equals("两")) return 2;

        int tensPos = cn.indexOf('十');
        if (tensPos < 0) {
            if (cn.length() != 1) return -1;
            return cnDigit(cn.charAt(0));
        }
        // 含"十"：X十Y / 十Y / 十 三种形式
        int tens;
        if (tensPos == 0) {
            tens = 1;
        } else {
            tens = cnDigit(cn.charAt(tensPos - 1));
            if (tens <= 0) return -1;
        }
        int ones = 0;
        if (tensPos + 1 < cn.length()) {
            ones = cnDigit(cn.charAt(tensPos + 1));
            if (ones < 0) return -1;
        }
        return tens * 10 + ones;
    }

    /** 单个中文数字转阿拉伯（返回 -1 表示非数字） */
    private static int cnDigit(char c) {
        for (int i = 0; i < CN_GE.length; i++) {
            if (CN_GE[i] == c) return i;
        }
        return -1;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long targetMillis(int hh, int mm, boolean tomorrow) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, hh);
        c.set(java.util.Calendar.MINUTE, mm);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (tomorrow) c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long at = c.getTimeInMillis();
        if (at <= System.currentTimeMillis() && !tomorrow) {
            // 今天已过该时刻则顺延到明天同一时刻
            at += 24L * 3600_000L;
        }
        return at;
    }

    /** 从整句中去掉命令词后提取要提醒的内容 */
    private static String extractContent(String t) {
        String s = t;
        // 尝试按命令词切分：保留命令词之后的内容作为提醒语
        String[] keys = {"提醒我", "提醒", "叫我起床", "叫我", "喊我", "起床", "闹钟"};
        int best = -1;
        for (String k : keys) {
            int idx = s.indexOf(k);
            if (idx >= 0) {
                int end = idx + k.length();
                best = Math.max(best, end);
            }
        }
        if (best >= 0 && best < s.length()) {
            String after = s.substring(best).trim();
            after = after.replaceAll("^[，。！!？?、\\s]+", "");
            // 去掉语气词
            after = after.replace("一下", "").replace("吧", "").replace("啊", "")
                    .replace("哦", "").replace("哈", "").replace("好的", "");
            if (!after.isEmpty()) return after;
        }
        // 没有内容词则默认
        if (s.contains("起床")) return "该起床啦";
        if (s.contains("闹钟")) return "闹钟时间到";
        return "时间到了";
    }

    /**
     * 真正设置系统闹钟/提醒。
     */
    public static void schedule(Context ctx, Parsed p) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.putExtra(AlarmReceiver.EXTRA_CONTENT, p.content);
        i.putExtra(AlarmReceiver.EXTRA_IS_ALARM, p.isAlarm);
        int req = (int) (p.triggerAt % 100000L);  // 大致唯一请求码
        PendingIntent pi = PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (android.os.Build.VERSION.SDK_INT >= 31
                        ? PendingIntent.FLAG_IMMUTABLE : 0));
        // 精确提醒需要 SCHEDULE_EXACT_ALARM 权限；失败退回非精确
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.triggerAt, pi);
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, p.triggerAt, pi);
            } catch (Exception ignored) {}
        }
    }

    /** 把"5分钟后提醒我喝水"变成友好确认语 */
    public static String confirmText(Parsed p) {
        SimpleDateFormat f = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
        String at = f.format(new Date(p.triggerAt));
        String what = p.content.isEmpty() ? "的提醒" : "，提醒你" + p.content;
        return "好的，已设置" + at + what;
    }
}
</｜｜DSML｜｜>
