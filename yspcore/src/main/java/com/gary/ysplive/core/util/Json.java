package com.gary.ysplive.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研 JSON（不引入 org.json / Gson / Jackson：core 必须零第三方依赖且能在桌面 JVM 跑）。
 *
 * <h2>序列化：逐字节对齐 Python</h2>
 * {@link #stringify(Object)} 的输出与
 * {@code json.dumps(value, separators=(',',':'), ensure_ascii=False, sort_keys=True)}
 * **逐字节相同**。这一点是硬要求：央视频的 {@code app/start} 请求体就是用这个形式生成的，
 * 服务端会对其做哈希/签名校验。
 * <ul>
 *   <li>键按字典序（等价 Python {@code sort_keys=True}）。</li>
 *   <li>无任何空格：{@code {"a":1,"b":2}}。</li>
 *   <li>整数不带 {@code .0}；浮点用 Python {@code repr()} 风格（{@code 1.0}、{@code 1e+16}、
 *       {@code 1e-05}、{@code 1.5e-07}），不是 Java 的 {@code 1.0E16} 风格。</li>
 *   <li>非 ASCII 原样输出（UTF-8），不转 {@code \\uXXXX}。</li>
 *   <li>转义只处理 {@code "} {@code \\} 与 {@code < 0x20} 的控制字符，规则与 Python 一致
 *       （{@code \b \f \n \r \t}，其余 {@code \\u00xx} 小写十六进制）；{@code 0x7F} 不转义。</li>
 * </ul>
 *
 * <h2>反序列化：数字类型与 Python 对齐</h2>
 * 整数解析成 {@link Long}（超出 long 范围则 {@link BigInteger}），带小数点/指数的解析成
 * {@link Double} —— 这与 Python {@code json.loads} 的 int/float 区分一致，从而保证
 * "parse 再 stringify" 不会把 {@code 1} 变成 {@code 1.0}。
 * <p>注意：<b>不要把值强转 {@code Double}</b>，请用 {@link #i} {@link #l} {@link #d} {@link #num}
 * 等取值糖，或 {@code ((Number) v)}。
 */
public final class Json {

    /** 递归深度上限，防止恶意/畸形输入把栈打爆。 */
    private static final int MAX_DEPTH = 512;

    private Json() {
    }

    // ---------------------------------------------------------------- 序列化

    /** 紧凑、键排序（等价 Python {@code sort_keys=True}）。 */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, true);
        return sb.toString();
    }

    /** 紧凑、保持 Map 的迭代顺序（不排序）。 */
    public static String stringifyNotSorted(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, false);
        return sb.toString();
    }

    /**
     * 紧凑、键排序，并把字符串里的 {@code /} 转成 {@code \/}
     * （等价 Python {@code compact_json_bytes(v, escape_forward_slashes=True)}）。
     */
    public static String stringifyEscapingSlashes(Object value) {
        return stringify(value).replace("/", "\\/");
    }

    /** {@link #stringify(Object)} 的 UTF-8 字节，可直接当请求体。 */
    public static byte[] bytes(Object value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeValue(StringBuilder sb, Object v, boolean sortKeys) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Character) {
            writeString(sb, String.valueOf((char) (Character) v));
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(formatDouble(((Number) v).doubleValue()));
        } else if (v instanceof BigDecimal) {
            sb.append(((BigDecimal) v).toPlainString());
        } else if (v instanceof Number) {
            // Integer / Long / Short / Byte / BigInteger：整数形态，不带 .0
            sb.append(v.toString());
        } else if (v instanceof Map) {
            writeMap(sb, (Map<?, ?>) v, sortKeys);
        } else if (v instanceof Iterable) {
            writeIterable(sb, (Iterable<?>) v);
        } else if (v.getClass().isArray()) {
            writeArray(sb, v);
        } else {
            // 与 Python 不同：Python 会 TypeError；这里退化为字符串，避免整个流程因日志字段崩掉
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m, boolean sortKeys) {
        sb.append('{');
        boolean first = true;
        if (sortKeys) {
            String[] keys = new String[m.size()];
            int i = 0;
            for (Object k : m.keySet()) {
                keys[i++] = k == null ? "null" : String.valueOf(k);
            }
            Arrays.sort(keys);
            for (String k : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, k);
                sb.append(':');
                writeValue(sb, m.get(k), true);
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey() == null ? "null" : String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue(), false);
            }
        }
        sb.append('}');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o, true);
        }
        sb.append(']');
    }

    private static void writeArray(StringBuilder sb, Object arr) {
        sb.append('[');
        int n = java.lang.reflect.Array.getLength(arr);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, java.lang.reflect.Array.get(arr, i), true);
        }
        sb.append(']');
    }

    /**
     * 字符串转义，严格对齐 Python {@code json.dumps(ensure_ascii=False)}：
     * 只转义 {@code "} {@code \\} 和 {@code < 0x20}；{@code /} 与 {@code 0x7F} 及非 ASCII 原样保留。
     */
    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append(HEX[c >>> 4]);
                        sb.append(HEX[c & 0x0F]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 把 double 渲染成 Python {@code repr()} 风格：短往返位数 + Python 的定点/指数选择规则
     * （{@code -4 <= decpt <= 16} 用定点，否则指数），指数带符号且至少两位。
     */
    static String formatDouble(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        String js = Double.toString(d);
        boolean neg = js.charAt(0) == '-';
        if (neg) {
            js = js.substring(1);
        }
        int epos = js.indexOf('E');
        if (epos < 0) {
            epos = js.indexOf('e');
        }
        int exp = 0;
        String mant = js;
        if (epos >= 0) {
            exp = Integer.parseInt(js.substring(epos + 1));
            mant = js.substring(0, epos);
        }
        int dot = mant.indexOf('.');
        String digits;
        int decpt;
        if (dot < 0) {
            digits = mant;
            decpt = mant.length() + exp;
        } else {
            digits = mant.substring(0, dot) + mant.substring(dot + 1);
            decpt = dot + exp;
        }
        // 去前导零（同时修正小数点位置）
        int lead = 0;
        while (lead < digits.length() && digits.charAt(lead) == '0') {
            lead++;
        }
        if (lead > 0) {
            digits = digits.substring(lead);
            decpt -= lead;
        }
        // 去尾随零
        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        if (digits.isEmpty()) {
            // ±0.0
            digits = "0";
            decpt = 1;
        }

        StringBuilder sb = new StringBuilder(24);
        if (neg) {
            sb.append('-');
        }
        boolean useExp = decpt <= -4 || decpt > 16;
        if (useExp) {
            sb.append(digits.charAt(0));
            if (digits.length() > 1) {
                sb.append('.').append(digits, 1, digits.length());
            }
            int e = decpt - 1;
            sb.append('e').append(e < 0 ? '-' : '+');
            int ae = Math.abs(e);
            if (ae < 10) {
                sb.append('0');
            }
            sb.append(ae);
        } else if (decpt <= 0) {
            sb.append("0.");
            for (int i = 0; i < -decpt; i++) {
                sb.append('0');
            }
            sb.append(digits);
        } else if (decpt >= digits.length()) {
            sb.append(digits);
            for (int i = digits.length(); i < decpt; i++) {
                sb.append('0');
            }
            sb.append(".0");
        } else {
            sb.append(digits, 0, decpt).append('.').append(digits, decpt, digits.length());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 反序列化

    /** 解析任意 JSON 值；畸形输入抛 {@link JsonException}。 */
    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("json text is null");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue(0);
        p.skipWs();
        if (!p.eof()) {
            throw new JsonException("trailing characters at " + p.pos);
        }
        return v;
    }

    /** 解析 JSON 对象；顶层不是对象抛 {@link JsonException}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("json root is not an object: "
                    + (v == null ? "null" : v.getClass().getSimpleName()));
        }
        return (Map<String, Object>) v;
    }

    /** 解析失败返回 null，不抛异常（供"响应不一定是 JSON"的场景用）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> tryParseObject(String text) {
        try {
            Object v = parse(text);
            return v instanceof Map ? (Map<String, Object>) v : null;
        } catch (JsonException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 解析失败返回 null，不抛异常。 */
    public static Object tryParse(String text) {
        try {
            return parse(text);
        } catch (JsonException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw new JsonException("json nested too deep (> " + MAX_DEPTH + ")");
            }
            if (eof()) {
                throw new JsonException("unexpected end of json");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new JsonException("unexpected char at " + pos + ": " + c);
            }
        }

        private void expectWord(String w) throws JsonException {
            if (s.startsWith(w, pos)) {
                pos += w.length();
            } else {
                throw new JsonException("expected '" + w + "' at " + pos);
            }
        }

        private Map<String, Object> readObject(int depth) throws JsonException {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (!eof() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (eof() || s.charAt(pos) != '"') {
                    throw new JsonException("expected object key string at " + pos);
                }
                String key = readString();
                skipWs();
                if (eof() || s.charAt(pos) != ':') {
                    throw new JsonException("expected ':' at " + pos);
                }
                pos++;
                skipWs();
                m.put(key, readValue(depth + 1));
                skipWs();
                if (eof()) {
                    throw new JsonException("unexpected end of json in object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return m;
                }
                throw new JsonException("expected ',' or '}' at " + pos);
            }
        }

        private List<Object> readArray(int depth) throws JsonException {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (!eof() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue(depth + 1));
                skipWs();
                if (eof()) {
                    throw new JsonException("unexpected end of json in array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new JsonException("expected ',' or ']' at " + pos);
            }
        }

        private String readString() throws JsonException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder(32);
            while (true) {
                if (eof()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new JsonException("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        sb.append(readHex4());
                        break;
                    default:
                        throw new JsonException("invalid escape '\\" + e + "' at " + (pos - 1));
                }
            }
        }

        private char readHex4() throws JsonException {
            if (pos + 4 > s.length()) {
                throw new JsonException("truncated \\u escape at " + pos);
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                char c = s.charAt(pos++);
                int d;
                if (c >= '0' && c <= '9') {
                    d = c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    d = c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    d = c - 'A' + 10;
                } else {
                    throw new JsonException("invalid \\u hex digit '" + c + "' at " + (pos - 1));
                }
                v = (v << 4) | d;
            }
            return (char) v;
        }

        private Object readNumber() throws JsonException {
            int start = pos;
            if (!eof() && s.charAt(pos) == '-') {
                pos++;
            }
            // 对齐 Python json：不允许前导零（"01" 非法），但允许单独的 "0"
            if (!eof() && s.charAt(pos) == '0') {
                pos++;
                if (!eof() && isDigit(s.charAt(pos))) {
                    throw new JsonException("invalid number: leading zero at " + start);
                }
            } else {
                int digitsStart = pos;
                while (!eof() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == digitsStart) {
                    throw new JsonException("invalid number at " + start);
                }
            }
            boolean isFloat = false;
            if (!eof() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (eof() || !isDigit(s.charAt(pos))) {
                    throw new JsonException("invalid number: digits expected after '.' at " + pos);
                }
                while (!eof() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!eof() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (!eof() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (eof() || !isDigit(s.charAt(pos))) {
                    throw new JsonException("invalid number: digits expected in exponent at " + pos);
                }
                while (!eof() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty() || "-".equals(raw)) {
                throw new JsonException("invalid number at " + start);
            }
            if (!isFloat) {
                try {
                    return Long.valueOf(Long.parseLong(raw));
                } catch (NumberFormatException ignore) {
                    // 超出 long：用 BigInteger，保证仍是"整数"语义
                    try {
                        return new BigInteger(raw);
                    } catch (NumberFormatException e) {
                        throw new JsonException("invalid integer: " + raw);
                    }
                }
            }
            try {
                return Double.valueOf(Double.parseDouble(raw));
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number: " + raw);
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }

    // ---------------------------------------------------------------- 取值糖

    /** 取字符串；缺失或 null 返回 {@code def}。非字符串用 {@code String.valueOf}。 */
    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) {
            return def;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    /** 取字符串；缺失返回空串。 */
    public static String str(Map<String, Object> m, String k) {
        return str(m, k, "");
    }

    /** 取 int；缺失/不可解析返回 {@code def}。 */
    public static int i(Map<String, Object> m, String k, int def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 取 long；缺失/不可解析返回 {@code def}。 */
    public static long l(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 取 double；缺失/不可解析返回 {@code def}。 */
    public static double d(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 取 boolean；Python 风格：非空字符串/非零数字都算 true。 */
    public static boolean b(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof String) {
            String t = ((String) v).trim();
            if (t.isEmpty()) {
                return false;
            }
            return !"false".equalsIgnoreCase(t) && !"0".equals(t) && !"null".equalsIgnoreCase(t);
        }
        return def;
    }

    /** 取嵌套对象；缺失或类型不符返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 取数组；缺失或类型不符返回**空 list**（不会返回 null，便于直接 for 循环）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : java.util.Collections.emptyList();
    }

    /** 把任意值当对象；不是对象返回 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 把任意值当数组；不是数组返回空 list。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) {
        return v instanceof List ? (List<Object>) v : java.util.Collections.emptyList();
    }

    /** 把任意值当字符串；null 返回 {@code def}。 */
    public static String asString(Object v, String def) {
        if (v == null) {
            return def;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
