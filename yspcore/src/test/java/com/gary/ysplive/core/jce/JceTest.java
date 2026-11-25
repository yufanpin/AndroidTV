package com.gary.ysplive.core.jce;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gary.ysplive.core.util.Json;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JCE 编解码测试。
 *
 * <p>两层验证：
 * <ol>
 *   <li><b>回环测试</b>：每种类型写进去再读出来，保证 {@link JceWriter}/{@link JceReader} 自洽</li>
 *   <li><b>金标向量</b>：与参考实现 ysp-live-v9.5.py 的 {@code _wrap} 输出<b>逐字节</b>比对。
 *       向量由 {@code tools/gen_jce_vectors.py} 生成（真的把参考脚本 import 进来跑），
 *       存在 {@code tools/vectors-jce.json}。JCE 是二进制 TLV，差一个字节服务端就解不开，
 *       所以这一层是必须的。</li>
 * </ol>
 */
public class JceTest {

    // ==================================================================
    // 1. 类型回环
    // ==================================================================

    @Test
    public void writerReaderRoundTripAllTypes() {
        JceWriter w = new JceWriter();
        w.writeByte(0, 0);
        w.writeByte(1, 1);
        w.writeByte(-1, 2);
        w.writeByte(127, 3);
        w.writeByte(-128, 4);
        w.writeShort(-128, 5);        // 退化成 byte
        w.writeShort(128, 6);         // short
        w.writeShort(32767, 7);
        w.writeShort(-32768, 8);
        w.writeInt(-32768, 9);        // 退化成 short
        w.writeInt(32768, 10);        // int
        w.writeInt(Integer.MAX_VALUE, 11);
        w.writeInt(Integer.MIN_VALUE, 12);
        w.writeLong(-2147483649L, 13); // long
        w.writeLong(2147483648L, 14);
        w.writeLong(Integer.MAX_VALUE, 15);  // 退化成 int
        w.writeFloat(1.5f, 16);
        w.writeFloat(-0.25f, 17);
        w.writeDouble(-2.25d, 18);
        w.writeDouble(0.1d, 19);
        w.writeString("", 20);
        w.writeString("hello", 21);
        w.writeString("中文测试", 22);
        w.writeBytes(new byte[] {0, 1, (byte) 0xFF}, 23);

        Map<Integer, Object> m = new JceReader(w.out()).struct();

        assertEquals(0, m.get(0));
        assertEquals(1, m.get(1));
        assertEquals(-1, m.get(2));
        assertEquals(127, m.get(3));
        assertEquals(-128, m.get(4));
        assertEquals(-128, m.get(5));
        assertEquals(128, m.get(6));
        assertEquals(32767, m.get(7));
        assertEquals(-32768, m.get(8));
        assertEquals(-32768, m.get(9));
        assertEquals(32768, m.get(10));
        assertEquals(Integer.MAX_VALUE, m.get(11));
        assertEquals(Integer.MIN_VALUE, m.get(12));
        assertEquals(-2147483649L, m.get(13));
        assertEquals(2147483648L, m.get(14));
        assertEquals(Integer.MAX_VALUE, m.get(15));
        assertEquals(1.5f, (Float) m.get(16), 0.0f);
        assertEquals(-0.25f, (Float) m.get(17), 0.0f);
        assertEquals(-2.25d, (Double) m.get(18), 0.0d);
        assertEquals(0.1d, (Double) m.get(19), 0.0d);
        assertEquals("", m.get(20));
        assertEquals("hello", m.get(21));
        assertEquals("中文测试", m.get(22));
        assertArrayEquals(new byte[] {0, 1, (byte) 0xFF}, (byte[]) m.get(23));
    }

    @Test
    public void structAndListRoundTrip() {
        JceWriter w = new JceWriter();
        w.writeStruct(ww -> {
            ww.writeInt(7, 0);
            ww.writeString("s", 1);
            ww.writeStruct(www -> www.writeByte(1, 0), 2);
        }, 0);
        w.writeList(Arrays.asList(1, 2, 3), 1, (ww, v) -> ww.writeInt(v, 0));
        w.writeList(new ArrayList<String>(), 2, (ww, v) -> ww.writeString(v, 0));

        Map<Integer, Object> m = new JceReader(w.out()).struct();
        Map<Integer, Object> st = castIntKeyed(m.get(0));
        assertNotNull(st);
        assertEquals(7, st.get(0));
        assertEquals("s", st.get(1));
        assertEquals(1, castIntKeyed(st.get(2)).get(0));

        List<Object> list = Json.asArray(m.get(1));
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));

        assertEquals(0, Json.asArray(m.get(2)).size());
    }

    @Test
    public void longStringUsesType7() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        JceWriter w = new JceWriter();
        w.writeString(sb.toString(), 0);
        Map<Integer, Object> m = new JceReader(w.out()).struct();
        assertEquals(sb.toString(), m.get(0));
        // 255 字节以内走 type 6（1 字节头 + 1 字节长度 + 3 字节负载 = 5 字节）
        JceWriter w2 = new JceWriter();
        w2.writeString("abc", 0);
        assertEquals(5, w2.out().length);
        assertEquals("0603616263", hex(w2.out()));
    }

    @Test
    public void nullStringIsSkipped() {
        JceWriter w = new JceWriter();
        w.writeString(null, 0);
        w.writeString("x", 1);
        Map<Integer, Object> m = new JceReader(w.out()).struct();
        assertFalse(m.containsKey(0));
        assertEquals("x", m.get(1));
    }

    @Test
    public void readerRejectsTruncatedData() {
        JceWriter w = new JceWriter();
        w.writeString("hello", 0);
        byte[] full = w.out();
        byte[] cut = Arrays.copyOf(full, full.length - 2);
        try {
            new JceReader(cut).struct();
            org.junit.Assert.fail("截断的数据应当抛 JceFormatException");
        } catch (JceFormatException expected) {
            // 符合预期
        }
    }

    @Test
    public void unwrapRejectsGarbage() {
        assertNull(JceRequest.unwrap(null));
        assertNull(JceRequest.unwrap(new byte[] {0x13, 0, 0}));
        byte[] junk = new byte[100];
        assertNull(JceRequest.unwrap(junk));       // 首字节不是 0x13
        byte[] junk2 = new byte[100];
        junk2[0] = 0x13;
        assertNull(JceRequest.unwrap(junk2));      // 没有合法 '&'...'(' 包壳
    }

    // ==================================================================
    // 2. 金标向量（逐字节）
    // ==================================================================

    /** 定位 tools/vectors-jce.json（Gradle 的工作目录与 IDE 不同，都试一遍）。 */
    private static File locateVectors() {
        String[] candidates = {
            "tools/vectors-jce.json",
            "../tools/vectors-jce.json",
            "../../tools/vectors-jce.json",
            "C:/1/ysp-android/tools/vectors-jce.json",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    @Test
    public void goldenVectorsMatchReferenceImplementation() throws Exception {
        File vec = locateVectors();
        org.junit.Assume.assumeTrue(
                "缺少 tools/vectors-jce.json（先跑 tools/gen_jce_vectors.py）", vec != null);
        String json = new String(Files.readAllBytes(vec.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> v = Json.parseObject(json);
        String guid = Json.str(v, "guid", "");

        // ---- 常量必须与参考实现一致 ----
        assertEquals(Json.str(v, "verName", ""), JceRequest.VER_NAME);
        assertEquals(Json.str(v, "verCode", ""), JceRequest.VER_CODE);
        assertEquals(Json.str(v, "appId", ""), JceRequest.APP_ID);
        assertEquals(Json.i(v, "qmfAppId", -1), JceRequest.QMF_APP_ID);
        assertEquals(Json.i(v, "qmfPlatform", -1), JceRequest.QMF_PLATFORM);
        assertEquals(Json.i(v, "bizId", -1), JceRequest.BIZ_ID);
        assertEquals(Json.str(v, "chanId", ""), JceRequest.CHAN_ID);

        // ---- 基本类型全覆盖的字节流 ----
        JceWriter w = new JceWriter();
        w.writeByte(0, 0);
        w.writeByte(1, 1);
        w.writeByte(-1, 2);
        w.writeByte(127, 3);
        w.writeShort(-128, 4);
        w.writeShort(128, 5);
        w.writeShort(32767, 6);
        w.writeInt(-32768, 7);
        w.writeInt(32768, 8);
        w.writeInt(Integer.MAX_VALUE, 9);
        w.writeLong(-2147483649L, 10);
        w.writeLong(2147483648L, 11);
        w.writeFloat(1.5f, 12);
        w.writeDouble(-2.25d, 13);
        w.writeString("", 14);
        w.writeString("hello", 15);
        w.writeString("中文", 16);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            big.append('x');
        }
        w.writeString(big.toString(), 17);
        w.writeBytes(new byte[] {0, 1, (byte) 0xFF}, 18);
        w.writeStruct(ww -> {
            ww.writeInt(7, 0);
            ww.writeString("s", 1);
        }, 19);
        w.writeList(Arrays.asList(1, 2, 3), 20, (ww, item) -> ww.writeInt(item, 0));

        assertEquals("primitives 字节流必须与参考实现一致",
                Json.str(v, "primitivesHex", ""), hex(w.out()));

        // ---- _head（含 _qua） ----
        JceWriter hw = new JceWriter();
        hw.writeStruct(ww -> JceRequest.writeHead(ww, 25312, 42, guid), 0);
        assertEquals("_head 字节流必须与参考实现一致",
                Json.str(v, "headHex", ""), hex(hw.out()));

        // ---- 每个时移包：body / inner / 整包 三层都对 ----
        List<Object> cases = Json.arr(v, "cases");
        assertNotNull(cases);
        assertFalse("向量里没有用例", cases.isEmpty());
        for (Object o : cases) {
            Map<String, Object> c = Json.asObject(o);
            String label = Json.str(c, "label", "?");
            int cmd = Json.i(c, "cmd", -1);
            int reqid = Json.i(c, "reqid", 0);
            boolean emptyBody = "wrap_empty_body".equals(label);

            byte[] body = emptyBody
                    ? new byte[0]
                    : JceRequest.buildTimeshiftBody(Json.str(c, "pid", ""),
                            Json.str(c, "sid", ""), Json.l(c, "start", 0),
                            Json.l(c, "end", 0), Json.str(c, "stream", ""));

            assertEquals(label + ": body", Json.str(c, "bodyHex", ""), hex(body));

            String innerHex = Json.str(c, "innerHex", "");
            assertEquals(label + ": inner(gzip 前)",
                    innerHex, hex(JceRequest.buildInner(cmd, body, reqid, guid)));

            // inner = 1B(0x26) + 4B(len) + 1B(0x01) + 10B(0) + reqcmd + 1B(0x28)
            assertEquals(label + ": reqcmd",
                    innerHex.substring(32, innerHex.length() - 2),
                    hex(JceRequest.buildReqCmd(cmd, body, reqid, guid)));

            String packetHex = Json.str(c, "packetHex", "");
            byte[] packet = JceRequest.wrap(cmd, body, reqid, guid);
            assertEquals(label + ": 整包（含 gzip 层）", packetHex, hex(packet));

            // 包头自检：总长回填正确
            int declared = ((packet[1] & 0xFF) << 24) | ((packet[2] & 0xFF) << 16)
                    | ((packet[3] & 0xFF) << 8) | (packet[4] & 0xFF);
            assertEquals(label + ": 包头里的总长", packet.length, declared);
            assertEquals(label + ": 首字节是 0x13", 0x13, packet[0] & 0xFF);
            assertEquals(label + ": 尾字节是 0x03", 0x03, packet[packet.length - 1] & 0xFF);
        }
    }

    /**
     * 自解自的包：{@code _unwrap} 应该能从我们自己打的包里取出 body。
     * <p>这条保证"发出去的包自己能按同样格式读回来"，不依赖服务端。
     */
    @Test
    public void wrapThenUnwrapRoundTrip() throws Exception {
        String guid = "00112233445566778899aabbccddeeff";
        byte[] body = JceRequest.buildTimeshiftBody("600001859", "2024078201",
                1700000000L, 1700000300L, "fhd");
        byte[] packet = JceRequest.wrap(JceRequest.CMD_TIMESHIFT, body, 12345, guid);

        // 按 _unwrap 的结构手工取出 reqcmd，验证能读回 body
        byte[] inner = JceRequest.gunzipForTest(
                Arrays.copyOfRange(packet, 89, packet.length - 1));
        assertNotNull(inner);
        assertEquals(38, inner[0] & 0xFF);
        int lenField = ((inner[1] & 0xFF) << 24) | ((inner[2] & 0xFF) << 16)
                | ((inner[3] & 0xFF) << 8) | (inner[4] & 0xFF);
        // Python 里 len 字段写的是 len(reqcmd)+17，而 inner 总长恰好也是 reqcmd+17，
        // 所以 len 字段 == inner 总长（实测 300B 的 inner 里该字段就是 300）。
        assertEquals(inner.length, lenField);

        byte[] reqcmd = Arrays.copyOfRange(inner, 16, inner.length - 1);
        assertEquals(lenField, inner.length);   // 与上一行同义，明确一下
        assertTrue(reqcmd.length > 0);
        Map<Integer, Object> rc = new JceReader(reqcmd).struct();
        Object headObj = rc.get(0);
        Object bodyObj = rc.get(1);
        assertNotNull(headObj);
        assertNotNull(bodyObj);
        assertArrayEquals(body, (byte[]) bodyObj);

        // 头部里的 reqid / cmd 必须能读回来
        Map<Integer, Object> head = castIntKeyed(headObj);
        assertEquals(Integer.valueOf(12345), head.get(0));
        assertEquals(Integer.valueOf(25312), head.get(1));

        // body 自身字段
        Map<Integer, Object> bodyStruct = new JceReader(body).struct();
        assertEquals("600001859", bodyStruct.get(0));
        assertEquals("2024078201", bodyStruct.get(1));
        assertNum("1700000000", 1700000000L, bodyStruct.get(2));
        assertNum("1700000300", 1700000300L, bodyStruct.get(3));
        assertEquals("fhd", bodyStruct.get(4));
    }

    /**
     * 时间戳的 JCE 宽度必须与 Python {@code W.long} 的降级规则一致。
     *
     * <p>关键事实（用参考实现实测确认）：Python 的 {@code w.long()} 是"能塞小的就用小的"，
     * {@code long(1900000000)} 实际写成 <b>type 2（int32，4 字节）</b>，
     * 只有超出 int32 才升到 type 3（int64，8 字节）。
     * 所以 Java 侧读到 {@code Integer} 是<b>正确</b>的，不是 bug。
     */
    @Test
    public void timeshiftTimestampWidthMatchesPythonLongDegradation() {
        // 1900000000 在 int32 范围内 → Python 写 type 2
        byte[] body = JceRequest.buildTimeshiftBody("p", "s", 1900000000L, 1900086400L, "fhd");
        Map<Integer, Object> m = new JceReader(body).struct();
        assertNum("1900000000", 1900000000L, m.get(2));
        assertNum("1900086400", 1900086400L, m.get(3));

        // 与 Python 逐字节对拍：w.long(1900000000,2) -> 22 71 3f b3 00 (type 2)
        JceWriter justStart = new JceWriter();
        justStart.writeLong(1900000000L, 2);
        assertEquals("22713fb300", hex(justStart.out()));

        // 超出 int32 → Python 写 type 3：23 00000000b2d05e00
        JceWriter justBig = new JceWriter();
        justBig.writeLong(3000000000L, 2);
        assertEquals("2300000000b2d05e00", hex(justBig.out()));

        // 负数大值同理
        Map<Integer, Object> neg = new JceReader(
                JceRequest.buildTimeshiftBody("p", "s", -1L, -86400L, "fhd")).struct();
        assertNum("-1", -1L, neg.get(2));
        assertNum("-86400", -86400L, neg.get(3));
    }

    @Test
    public void gzipOutputMatchesPythonHeader() {
        byte[] gz = JceRequest.gzipForTest("abc".getBytes(StandardCharsets.UTF_8));
        // Python gzip.compress 的固定头：1f 8b 08 00 00 00 00 00 02 ff
        byte[] expect = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, (byte) 0xff};
        assertArrayEquals(expect, Arrays.copyOf(gz, 10));
        // 尾部 8 字节是 CRC32 + ISIZE（小端）
        assertEquals(3, ((gz[gz.length - 4] & 0xFF)) | ((gz[gz.length - 3] & 0xFF) << 8)
                | ((gz[gz.length - 2] & 0xFF) << 16) | ((gz[gz.length - 1] & 0xFF) << 24));
    }

    @Test
    public void guidIs32LowercaseHex() {
        String guid = JceRequest.guid();
        assertEquals(32, guid.length());
        assertTrue("GUID 必须全是小写 hex: " + guid, guid.matches("[0-9a-f]{32}"));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16))
              .append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 数值比较：JCE 会按 {@code W.long} 的降级规则选最小宽度，
     * 所以同一个逻辑值读出来可能是 {@link Integer} 也可能是 {@link Long}。
     * 这里统一按 long 值比较，不纠结包装类型。
     */
    private static void assertNum(String what, long expected, Object actual) {
        assertNotNull("字段 " + what + " 不该是 null", actual);
        assertTrue("字段 " + what + " 不是数字: " + actual.getClass(), actual instanceof Number);
        assertEquals("字段 " + what, expected, ((Number) actual).longValue());
    }

    /**
     * JCE 结构体的键是 {@code Integer} tag（不是 JSON 的 String 键），
     * 所以不能直接用 {@code Json.asObject}。这里做一次带检查的转型。
     */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Object> castIntKeyed(Object v) {
        if (!(v instanceof Map)) {
            return null;
        }
        return (Map<Integer, Object>) v;
    }
}
