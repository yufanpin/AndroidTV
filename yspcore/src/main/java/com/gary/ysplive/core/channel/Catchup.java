package com.gary.ysplive.core.channel;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时移（catchup）查询串解析。
 * <p>严格复刻 Python 参考实现：
 * <ul>
 *   <li>{@code parse_catchup_time_val}（2441-2479）</li>
 *   <li>{@code parse_catchup_query}（2480-2511）</li>
 * </ul>
 *
 * 支持的输入形态（与 Python 一致，顺序也一致）：
 * <ol>
 *   <li>ISO-8601：{@code 2024-01-01T12:00:00+08:00} / {@code ...Z} / {@code 2024-01-01 12:00:00}</li>
 *   <li>14 位紧凑时间：{@code 20240101120000}</li>
 *   <li>13 位毫秒：{@code 1704081600000}</li>
 *   <li>12 位紧凑时间：{@code 202401011200}</li>
 *   <li>10 位秒：{@code 1704081600}</li>
 * </ol>
 * 时区规则：不带时区的值按 {@code Asia/Shanghai}（+08:00）解释，除非以 {@code Z} 结尾。
 * 参考实现的 {@code cst = timezone(timedelta(hours=8))} 是固定偏移而不是 IANA 时区，
 * 所以这里用 {@link ZoneOffset#ofHours(int)} 固定 +8，避免历史夏令时干扰。
 */
public final class Catchup {

    private Catchup() { }

    /** 固定东八区偏移（与 Python {@code timezone(timedelta(hours=8))} 等价）。 */
    public static final ZoneOffset CST = ZoneOffset.ofHours(8);

    /** 时移区间，单位秒。 */
    public static final class Timespan {
        public final long startEpochSec;
        public final long endEpochSec;

        public Timespan(long startEpochSec, long endEpochSec) {
            this.startEpochSec = startEpochSec;
            this.endEpochSec = endEpochSec;
        }

        public long durationSec() {
            return endEpochSec - startEpochSec;
        }

        @Override
        public String toString() {
            return "Timespan{" + startEpochSec + " -> " + endEpochSec + "}";
        }
    }

    // ------------------------------------------------------------------ 单个时间值

    /**
     * 解析单个时间值。对应 Python {@code parse_catchup_time_val}。
     *
     * @return epoch 秒；无法解析返回 {@code null}
     */
    public static Long parseTimeVal(String valStr) {
        if (valStr == null) {
            return null;
        }
        String val = valStr.trim();
        if (val.isEmpty()) {
            return null;
        }
        // 1) ISO-8601：带 'T'，或同时含 '-' 与 ':'
        if (val.indexOf('T') >= 0 || (val.indexOf('-') >= 0 && val.indexOf(':') >= 0)) {
            Long iso = tryIso(val);
            if (iso != null) {
                return iso;
            }
            // 与 Python 一致：ISO 分支解析失败后继续走数字分支
        }
        boolean isUtc = val.toUpperCase(java.util.Locale.ROOT).endsWith("Z");
        String cleanVal = stripFraction(val);
        String digits = onlyDigits(cleanVal);
        if (digits.isEmpty()) {
            return null;
        }
        ZoneOffset tz = isUtc ? ZoneOffset.UTC : CST;
        int len = digits.length();
        if (len == 10) {
            return Long.parseLong(digits);
        }
        if (len == 13) {
            // Python: int(int(digits) / 1000) → 向零截断
            return Long.parseLong(digits) / 1000L;
        }
        if (len >= 14) {
            Long v = tryCompact(digits.substring(0, 14), tz, true);
            if (v != null) {
                return v;
            }
        }
        if (len == 12) {
            Long v = tryCompact(digits, tz, false);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static Long tryIso(String val) {
        try {
            String isoVal = val;
            if (val.toUpperCase(java.util.Locale.ROOT).endsWith("Z")) {
                isoVal = val.substring(0, val.length() - 1) + "+00:00";
            }
            return parseIsoToEpoch(isoVal, CST);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 复刻 {@code datetime.fromisoformat} 的容错程度：接受日期与时间用任一字符分隔、
     * 接受 {@code Z}、接受省略秒、接受小数秒。
     */
    static Long parseIsoToEpoch(String text, ZoneOffset defaultOffset) {
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        ZoneOffset explicitOffset = null;
        int tail = hasOffsetSuffix(s);
        if (tail > 0) {
            String off = s.substring(s.length() - tail);
            s = s.substring(0, s.length() - tail);
            if ("Z".equalsIgnoreCase(off)) {
                explicitOffset = ZoneOffset.UTC;
            } else {
                try {
                    explicitOffset = ZoneOffset.of(off);
                } catch (DateTimeException e) {
                    return null;
                }
            }
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        // 把日期与时间之间的任意单字符分隔符统一成 'T'
        String normalized = s;
        int dateLen = 10;
        if (s.length() > dateLen) {
            char sep = s.charAt(dateLen);
            if (sep == ' ' || sep == 'T' || sep == 't') {
                normalized = s.substring(0, dateLen) + "T" + s.substring(dateLen + 1);
            }
        }
        ZoneOffset offset = explicitOffset == null ? defaultOffset : explicitOffset;
        ZoneId zone = offset == null ? ZoneId.of("Asia/Shanghai") : offset;
        try {
            LocalDateTime ldt = LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(zone).toEpochSecond();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 返回结尾时区后缀长度（{@code Z} / {@code +08:00} / {@code -0500}），没有返回 0。 */
    private static int hasOffsetSuffix(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        char last = s.charAt(s.length() - 1);
        if (last == 'Z' || last == 'z') {
            return 1;
        }
        // ±HH:MM  (6) 或 ±HHMM (5) 或 ±HH (3)
        for (int len = 6; len >= 3; len--) {
            if (s.length() < len) {
                continue;
            }
            int pos = s.length() - len;
            char c = s.charAt(pos);
            if (c != '+' && c != '-') {
                continue;
            }
            String tailStr = s.substring(pos);
            if (len == 6) {
                if (tailStr.charAt(3) == ':' && isDigits(tailStr.substring(1, 3)) && isDigits(tailStr.substring(4))) {
                    return len;
                }
            } else if (len == 5) {
                if (isDigits(tailStr.substring(1))) {
                    return len;
                }
            } else {
                if (isDigits(tailStr.substring(1))) {
                    return len;
                }
            }
        }
        return 0;
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Python {@code re.sub(r'\.\d+', '', val)}：删除小数部分。 */
    private static String stripFraction(String val) {
        StringBuilder sb = new StringBuilder(val.length());
        int i = 0;
        while (i < val.length()) {
            char c = val.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < val.length() && Character.isDigit(val.charAt(j))) {
                    j++;
                }
                if (j > i + 1) {
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String onlyDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Long tryCompact(String digits, ZoneOffset tz, boolean withSeconds) {
        try {
            int year = Integer.parseInt(digits.substring(0, 4));
            int month = Integer.parseInt(digits.substring(4, 6));
            int day = Integer.parseInt(digits.substring(6, 8));
            int hour = Integer.parseInt(digits.substring(8, 10));
            int minute = Integer.parseInt(digits.substring(10, 12));
            int second = withSeconds ? Integer.parseInt(digits.substring(12, 14)) : 0;
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second);
            return ldt.toEpochSecond(tz);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 查询串

    /**
     * 解析整个查询串。对应 Python {@code parse_catchup_query}。
     * <p>优先级：{@code playseek} → {@code start}/{@code end} → {@code utc}/{@code lutc} →
     * {@code starttime}/{@code endtime}。只给起点时，终点按
     * {@code max(start+60, min(start+7200, now))} 补齐。
     *
     * @param queryStr 不含 {@code ?} 的查询串（带 {@code ?} 也能处理）
     * @return 时移区间；没有任何起点返回 {@code null}
     */
    public static Timespan parseQuery(String queryStr) {
        return parseQuery(queryStr, System.currentTimeMillis() / 1000L);
    }

    /** 便于测试注入 {@code now}。 */
    public static Timespan parseQuery(String queryStr, long nowSec) {
        if (queryStr == null || queryStr.isEmpty()) {
            return null;
        }
        Map<String, java.util.List<String>> qs = parseQs(queryStr);
        Long startTs = null;
        Long endTs = null;
        String ps = first(qs, "playseek");
        if (ps != null) {
            int dash = ps.indexOf('-');
            if (dash >= 0) {
                startTs = parseTimeVal(ps.substring(0, dash));
                endTs = parseTimeVal(ps.substring(dash + 1));
            } else {
                startTs = parseTimeVal(ps);
            }
        }
        if (startTs == null) {
            startTs = parseTimeVal(first(qs, "start"));
        }
        if (endTs == null) {
            endTs = parseTimeVal(first(qs, "end"));
        }
        if (startTs == null) {
            startTs = parseTimeVal(first(qs, "utc"));
        }
        if (endTs == null) {
            endTs = parseTimeVal(first(qs, "lutc"));
        }
        if (startTs == null) {
            startTs = parseTimeVal(first(qs, "starttime"));
        }
        if (endTs == null) {
            endTs = parseTimeVal(first(qs, "endtime"));
        }
        if (startTs == null) {
            return null;
        }
        long s = startTs;
        long e;
        if (endTs == null || endTs <= s) {
            e = Math.max(s + 60L, Math.min(s + 7200L, nowSec));
        } else {
            e = endTs;
        }
        return new Timespan(s, e);
    }

    /** 判断查询串里是否带时移参数（用于"回退直播"的判断）。 */
    public static boolean hasCatchupParams(String queryStr) {
        return parseQuery(queryStr) != null;
    }

    /**
     * Python {@code urllib.parse.parse_qs} 的等价实现：
     * 保留空值条目？—— Python 默认 {@code keep_blank_values=False}，即空值会被丢弃。
     * 这里保持一致（值为空串的键不进 map）。
     */
    public static Map<String, java.util.List<String>> parseQs(String queryStr) {
        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        String q = queryStr;
        int qmark = q.indexOf('?');
        if (qmark >= 0) {
            q = q.substring(qmark + 1);
        }
        int hash = q.indexOf('#');
        if (hash >= 0) {
            q = q.substring(0, hash);
        }
        if (q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0) {
                key = pair;
                value = "";
            } else {
                key = pair.substring(0, eq);
                value = pair.substring(eq + 1);
            }
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            String k = urlDecode(key);
            String v = urlDecode(value);
            java.util.List<String> list = out.get(k);
            if (list == null) {
                list = new java.util.ArrayList<>();
                out.put(k, list);
            }
            list.add(v);
        }
        return out;
    }

    /** 取第一个值。 */
    public static String first(Map<String, java.util.List<String>> qs, String key) {
        java.util.List<String> list = qs.get(key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /** {@code application/x-www-form-urlencoded} 解码（'+' 视为空格，%XX 按 UTF-8）。 */
    public static String urlDecode(String s) {
        if (s == null) {
            return null;
        }
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '+') {
                bos.write(' ');
                i++;
            } else if (c == '%' && i + 2 < s.length()
                    && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
                bos.write((Character.digit(s.charAt(i + 1), 16) << 4)
                        + Character.digit(s.charAt(i + 2), 16));
                i += 3;
            } else if (c < 0x80) {
                bos.write(c);
                i++;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bos.write(bytes, 0, bytes.length);
                i++;
            }
        }
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 把 epoch 秒格式化成央视频认识的 {@code yyyyMMddHHmmss}（东八区）。 */
    public static String formatCompact(long epochSec) {
        ZonedDateTime z = Instant.ofEpochSecond(epochSec).atZone(ZoneId.ofOffset("", CST));
        return String.format(java.util.Locale.ROOT, "%04d%02d%02d%02d%02d%02d",
                z.getYear(), z.getMonthValue(), z.getDayOfMonth(),
                z.getHour(), z.getMinute(), z.getSecond());
    }
}
