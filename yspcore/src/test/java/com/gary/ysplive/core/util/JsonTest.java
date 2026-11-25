package com.gary.ysplive.core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.gary.ysplive.core.testutil.Vectors;

/**
 * JSON 序列化/解析测试。
 *
 * <p><b>核心门禁</b>：{@link #stringifyMatchesPython()} 与 {@link #floatsMatchPython()}。
 * 序列化结果必须与 Python
 * {@code json.dumps(v, separators=(',',':'), ensure_ascii=False, sort_keys=True)} 逐字节相同，
 * 因为 {@code compact_json_bytes} 的产物就是发给央视频的请求体，服务端会签名校验。
 */
public class JsonTest {

    // ------------------------------------------------------------ 与 Python 对拍

    /** 逐 case 对比 Python 输出（值用 JSON 文本给出，Java 侧解析后重新序列化）。 */
    @Test
    public void stringifyMatchesPython() throws Exception {
        List<Map<String, Object>> cases = Vectors.cases("vectors-json.json");
        assertTrue("expected many json vectors", cases.size() >= 20);
        for (Map<String, Object> c : cases) {
            String name = Json.str(c, "name", "?");
            String want = Json.str(c, "stringify", "");
            // 用我们自己的 parser 还原 Python 值（parse 保留 int/float 区别，见 Json 类注释）
            // 注意：pythonValue 没单独给，所以用 want 反解 —— 这同时验证了 parser 的正确性
            Object value = Json.parse(want);
            String got = Json.stringify(value);
            assertEquals("stringify mismatch for case " + name, want, got);
        }
    }

    /** slash 转义版本对拍。 */
    @Test
    public void stringifyEscapingSlashesMatchesPython() throws Exception {
        List<Map<String, Object>> cases = Vectors.cases("vectors-json.json");
        int checked = 0;
        for (Map<String, Object> c : cases) {
            String name = Json.str(c, "name", "?");
            String want = Json.str(c, "stringifyEscapingSlashes", "");
            Object value = Json.parse(Json.str(c, "stringify", ""));
            assertEquals("slash-escape mismatch for case " + name, want,
                    Json.stringifyEscapingSlashes(value));
            checked++;
        }
        assertTrue(checked > 0);
    }

    /** 浮点格式模糊对拍：3000 个随机 double，全部与 Python repr 风格比对。 */
    @Test
    public void floatFuzzMatchesPython() {
        List<Map<String, Object>> cases = Vectors.floatCases("vectors-json-fuzz.json");
        assertTrue("expected fuzz vectors", cases.size() >= 1000);
        int checked = 0;
        for (Map<String, Object> c : cases) {
            String hexBits = Json.str(c, "hexBits", "");
            long bits = Long.parseUnsignedLong(hexBits, 16);
            double d = Double.longBitsToDouble(bits);
            String want = Json.str(c, "value", "");
            assertEquals("float fuzz mismatch for bits=" + hexBits + " (d=" + d + ")",
                    want, Json.stringify(d));
            checked++;
        }
        assertEquals(cases.size(), checked);
    }

    /** 浮点格式必须与 Python repr 一致（1e+16 / 1e-05 / 1.0 等）。 */
    @Test
    public void floatsMatchPython() {
        List<Map<String, Object>> cases = Vectors.floatCases("vectors-json.json");
        assertTrue("expected float vectors", cases.size() >= 20);
        for (Map<String, Object> c : cases) {
            String hexBits = Json.str(c, "hexBits", "");
            long bits = Long.parseUnsignedLong(hexBits, 16);
            double d = Double.longBitsToDouble(bits);
            String want = Json.str(c, "value", "");
            String got = Json.stringify(d);
            assertEquals("float format mismatch for bits=" + hexBits
                    + " (d=" + d + ")", want, got);

            // 也验一下在对象里的形式
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("f", d);
            assertEquals("float-in-object mismatch for bits=" + hexBits,
                    Json.str(c, "inObject", ""), Json.stringify(obj));
        }
    }

    // ------------------------------------------------------------ 具体行为

    /** 键按字典序（Python sort_keys=True）。 */
    @Test
    public void keysAreSorted() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1);
        m.put("a", 2);
        m.put("M", 3);
        m.put("0", 4);
        m.put("中", 5);
        m.put("ä", 6);
        assertEquals("{\"0\":4,\"M\":3,\"a\":2,\"z\":1,\"ä\":6,\"中\":5}", Json.stringify(m));
    }

    /** stringifyNotSorted 保持插入顺序。 */
    @Test
    public void notSortedKeepsInsertionOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", 1);
        m.put("a", 2);
        assertEquals("{\"z\":1,\"a\":2}", Json.stringifyNotSorted(m));
    }

    /** 整数不带 .0；Long 也当整数。 */
    @Test
    public void integersHaveNoDecimalPoint() {
        assertEquals("1", Json.stringify(1));
        assertEquals("1", Json.stringify(Long.valueOf(1L)));
        assertEquals("-1", Json.stringify(-1));
        assertEquals("1234567890123", Json.stringify(1234567890123L));
        assertEquals("1.0", Json.stringify(1.0d));
    }

    /** 非 ASCII 原样输出（ensure_ascii=False），控制字符按 Python 规则转义。 */
    @Test
    public void unicodeAndEscapes() {
        assertEquals("\"中文\"", Json.stringify("中文"));
        assertEquals("\"a\\\"b\"", Json.stringify("a\"b"));
        assertEquals("\"a\\\\b\"", Json.stringify("a\\b"));
        assertEquals("\"\\b\\f\\n\\r\\t\"", Json.stringify("\b\f\n\r\t"));
        assertEquals("\"\\u0001\\u001f\"", Json.stringify("\u0001\u001f"));
        // 0x7F 不转义
        assertEquals("\"\u007f\"", Json.stringify("\u007f"));
        // '/' 默认不转义
        assertEquals("\"a/b\"", Json.stringify("a/b"));
        assertEquals("\"a\\/b\"", Json.stringifyEscapingSlashes("a/b"));
    }

    /** null/布尔。 */
    @Test
    public void nullAndBooleans() {
        assertEquals("null", Json.stringify(null));
        assertEquals("true", Json.stringify(Boolean.TRUE));
        assertEquals("false", Json.stringify(Boolean.FALSE));
    }

    /** 嵌套结构与空容器。 */
    @Test
    public void nestedStructures() {
        assertEquals("[]", Json.stringify(new ArrayList<>()));
        assertEquals("{}", Json.stringify(new LinkedHashMap<>()));
        assertEquals("[1,2,[3,[4]]]", Json.stringify(list(1, 2, list(3, list(4)))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", list(1, 2));
        m.put("a", null);
        assertEquals("{\"a\":null,\"b\":[1,2]}", Json.stringify(m));
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<>();
        for (Object o : items) {
            out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------ 解析

    @Test
    public void parsesAllValueKinds() throws Exception {
        Object v = Json.parse("{\"a\":1,\"b\":-2.5,\"c\":true,\"d\":false,\"e\":null,"
                + "\"f\":\"s\",\"g\":[1,2],\"h\":{\"i\":3}}");
        assertTrue(v instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertEquals(Long.valueOf(1), m.get("a"));
        assertEquals(Double.valueOf(-2.5), m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertEquals(Boolean.FALSE, m.get("d"));
        assertNull(m.get("e"));
        assertEquals("s", m.get("f"));
        assertTrue(m.get("g") instanceof List);
        assertTrue(m.get("h") instanceof Map);
    }

    @Test
    public void parsesStringEscapes() throws Exception {
        assertEquals("a\"b", Json.parse("\"a\\\"b\""));
        assertEquals("a\\b", Json.parse("\"a\\\\b\""));
        assertEquals("a/b", Json.parse("\"a\\/b\""));
        assertEquals("\b\f\n\r\t", Json.parse("\"\\b\\f\\n\\r\\t\""));
        assertEquals("\u0001", Json.parse("\"\\u0001\""));
        assertEquals("中", Json.parse("\"\\u4e2d\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
    }

    @Test
    public void parsesNumbers() throws Exception {
        assertEquals(Long.valueOf(0), Json.parse("0"));
        assertEquals(Long.valueOf(-0), Json.parse("-0"));
        assertEquals(Double.valueOf(1.5), Json.parse("1.5"));
        assertEquals(Double.valueOf(1e10), Json.parse("1e10"));
        assertEquals(Double.valueOf(1e-10), Json.parse("1E-10"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), Json.parse(String.valueOf(Long.MAX_VALUE)));
        // 超出 long -> BigInteger
        Object big = Json.parse("123456789012345678901234567890");
        assertTrue(big instanceof java.math.BigInteger);
    }

    @Test
    public void numberTypeIsPythonLike() throws Exception {
        // 关键：整数必须是 Long（不是 Double），否则 stringify 会变成 "1.0"
        Object one = Json.parse("1");
        assertTrue("integer must parse as Long, got " + one.getClass(), one instanceof Long);
        assertEquals("1", Json.stringify(one));

        Object f = Json.parse("1.0");
        assertTrue("1.0 must parse as Double", f instanceof Double);
        assertEquals("1.0", Json.stringify(f));
    }

    @Test
    public void toleratesWhitespace() throws Exception {
        assertEquals("x", Json.parse("  \"x\"  "));
        Map<String, Object> m = Json.parseObject("{ \"a\" : [ 1 , 2 ] }");
        assertEquals(2, Json.arr(m, "a").size());
        assertEquals(1, Json.i(Json.asObject(Json.arr(m, "a").get(0)), "x", -1) == -1 ? 1 : -1);
    }

    @Test
    public void malformedInputThrows() {
        String[] bad = {
                "", "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]",
                "tru", "nul", "\"unterminated", "{\"a\":1}extra", "01", "1.", ".5",
                "+1", "{a:1}", "{\"a\" 1}", "[,]", "1 2",
        };
        for (String s : bad) {
            try {
                Json.parse(s);
                fail("expected JsonException for malformed input: <" + s + ">");
            } catch (JsonException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test
    public void parseObjectRejectsNonObject() {
        try {
            Json.parseObject("[1,2]");
            fail("array root must be rejected by parseObject");
        } catch (JsonException expected) {
            assertTrue(expected.getMessage().contains("not an object"));
        }
    }

    @Test
    public void tryParseReturnsNullOnGarbage() {
        assertNull(Json.tryParse("not json"));
        assertNull(Json.tryParseObject("[1]"));
        assertNotNull(Json.tryParseObject("{\"a\":1}"));
        assertNull(Json.tryParse(null));
    }

    @Test
    public void deeplyNestedIsRejectedNotStackOverflow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append('[');
        }
        try {
            Json.parse(sb.toString());
            fail("deep nesting must be rejected");
        } catch (JsonException expected) {
            assertTrue(expected.getMessage().contains("deep"));
        } catch (StackOverflowError e) {
            fail("must not stack overflow; got " + e);
        }
    }

    // ------------------------------------------------------------ 取值糖

    @Test
    public void sugarAccessors() throws Exception {
        Map<String, Object> m = Json.parseObject(
                "{\"s\":\"v\",\"i\":36,\"d\":3.5,\"b\":true,\"o\":{\"x\":1},\"a\":[1,2]}");
        assertEquals("v", Json.str(m, "s", "def"));
        assertEquals("def", Json.str(m, "missing", "def"));
        assertEquals(36, Json.i(m, "i", -1));
        // 浮点也能取 int（Lead 要求：CCTV 有时返回 36.0）
        assertEquals(3, Json.i(m, "d", -1));
        // long 也能取 double
        assertEquals(36.0, Json.d(m, "i", -1.0), 1e-9);
        assertEquals(3.5, Json.d(m, "d", -1.0), 1e-9);
        assertTrue(Json.b(m, "b", false));
        assertNotNull(Json.obj(m, "o"));
        assertNull(Json.obj(m, "s"));
        assertEquals(2, Json.arr(m, "a").size());
        assertEquals(0, Json.arr(m, "missing").size());
    }

    /** 浮点整数值取 int：CCTV 有时把 36 返回成 36.0。 */
    @Test
    public void intSugarToleratesFloats() throws Exception {
        Map<String, Object> m = Json.parseObject("{\"v\":36.0,\"w\":36.9,\"s\":\"36\"}");
        assertEquals(36, Json.i(m, "v", -1));
        assertEquals(36, Json.i(m, "w", -1)); // 截断，与 Python int(36.9) 一致
        assertEquals(36, Json.i(m, "s", -1));
        assertEquals(36L, Json.l(m, "v", -1L));
    }

    @Test
    public void sugarToleratesNullMap() {
        assertEquals("d", Json.str(null, "k", "d"));
        assertEquals(5, Json.i(null, "k", 5));
        assertEquals(0, Json.arr(null, "k").size());
        assertNull(Json.obj(null, "k"));
    }

    @Test
    public void sugarParsesNumericStrings() throws Exception {
        Map<String, Object> m = Json.parseObject("{\"n\":\"36\",\"f\":\"3.5\"}");
        assertEquals(36, Json.i(m, "n", -1));
        assertEquals(36L, Json.l(m, "n", -1L));
        assertEquals(3.5, Json.d(m, "f", -1.0), 1e-9);
    }

    // ------------------------------------------------------------ 真实场景

    /** app/start 请求体：这是真正会发给央视频的字节，必须与 Python 完全一致。 */
    @Test
    public void appStartBodyShape() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("cctv_id", "BAEBFF2B-C516-4F34-ABC0-A824A6461CBD");
        value.put("device_id", "abc");
        value.put("idfa", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "app_start_d1");
        body.put("value", value);
        assertEquals("{\"key\":\"app_start_d1\",\"value\":{\"cctv_id\":\"BAEBFF2B-C516-4F34-ABC0-"
                + "A824A6461CBD\",\"device_id\":\"abc\",\"idfa\":\"\"}}", Json.stringify(body));
    }

    /** bytes() 产物是 UTF-8：中文 3 字节 + 两个引号 = 5 字节。 */
    @Test
    public void bytesAreUtf8() {
        byte[] b = Json.bytes("中");
        assertEquals(5, b.length);
        assertArrayEquals("\"中\"".getBytes(StandardCharsets.UTF_8), b);
        assertEquals((byte) 0xE4, b[1]);
        assertEquals((byte) 0xB8, b[2]);
        assertEquals((byte) 0xAD, b[3]);
    }

    /** 与 Python vector 中 report-with-url 一致：slash 转义只影响字符串。 */
    @Test
    public void slashEscapingOnlyInsideStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", "https://a/b");
        m.put("n", 1);
        assertEquals("{\"n\":1,\"url\":\"https:\\/\\/a\\/b\"}", Json.stringifyEscapingSlashes(m));
        // 结构字符 '/' 本来就不出现，所以替换安全；验证不破坏合法 JSON
        String s = Json.stringifyEscapingSlashes(m);
        assertTrue(s.contains("https:\\/\\/a\\/b"));
    }

    /** 解析的真实央视响应形状。 */
    @Test
    public void parsesCctvResponseShape() throws Exception {
        String text = "{\"resultCode\":\"0\",\"resultMsg\":\"ok\",\"data\":{\"sessionKey\":\"abc\","
                + "\"encryptSessionKey\":\"xyz=\",\"expireTime\":1717729995180,"
                + "\"liveList\":[{\"sid\":\"Live1\",\"pid\":\"p1\",\"defn\":\"fhd\"}]}}";
        Map<String, Object> m = Json.parseObject(text);
        assertEquals("0", Json.str(m, "resultCode", ""));
        Map<String, Object> data = Json.obj(m, "data");
        assertNotNull(data);
        assertEquals("abc", Json.str(data, "sessionKey", ""));
        assertEquals(1717729995180L, Json.l(data, "expireTime", 0L));
        List<Object> lives = Json.arr(data, "liveList");
        assertEquals(1, lives.size());
        Map<String, Object> first = Json.asObject(lives.get(0));
        assertEquals("fhd", Json.str(first, "defn", ""));
        // stringify 会排序键，所以不能要求与原文本相同；只验证语义往返
        String again = Json.stringify(m);
        assertTrue(again.contains("\"resultCode\":\"0\""));
        assertEquals(Long.valueOf(1717729995180L), ((Map<?, ?>) m.get("data")).get("expireTime"));
    }
}
