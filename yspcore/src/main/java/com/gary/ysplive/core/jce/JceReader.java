package com.gary.ysplive.core.jce;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCE 二进制 TLV 读取器。
 * <p>逐字节复刻 Python 参考实现里的 {@code R} 类。读取 {@link JceWriter} 写出的数据；
 * 也用于解开时移接口返回的包络（{@code _unwrap} 之后的部分）。
 * <p>值映射规则（与 Python 一致）：
 * <ul>
 *   <li>byte → {@link Integer}，short → Integer，int → Integer，long → {@link Long}</li>
 *   <li>float → {@link Float}，double → {@link Double}</li>
 *   <li>string → {@link String}</li>
 *   <li>bytes → {@code byte[]}</li>
 *   <li>struct → {@code LinkedHashMap<Integer, Object>}（键是 tag）</li>
 *   <li>list → {@code ArrayList<Object>}</li>
 *   <li>type 11（结束）→ {@code null}，type 12（零）→ {@code Integer 0}</li>
 * </ul>
 * 遇到底层数据不足时抛 {@link JceFormatException}（不抛受检 IOException，方便调用）。
 */
public final class JceReader {

    private final byte[] data;
    private int pos;

    public JceReader(byte[] data) {
        this.data = data == null ? new byte[0] : data;
    }

    /** 剩余字节数。 */
    public int rem() {
        return data.length - pos;
    }

    /** 当前读取偏移。 */
    public int position() {
        return pos;
    }

    public byte[] get(int n) {
        if (n < 0 || pos + n > data.length) {
            throw new JceFormatException("JCE 数据不足: 需要 " + n + " 字节, 剩余 " + rem());
        }
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    public int u8() {
        return get(1)[0] & 0xFF;
    }

    /** 读一个头，返回 {@code {type, tag}}。 */
    public int[] head() {
        int b = u8();
        int type = b & 0x0F;
        int tag = (b & 0xF0) >>> 4;
        if (tag == 15) {
            tag = u8();
        }
        return new int[] {type, tag};
    }

    /** 读一个"带 tag 的值"（先读头再读负载），返回 {@code {tag, value}}。 */
    public TaggedValue field() {
        int[] h = head();
        Object v = value(h[0]);
        return new TaggedValue(h[1], v);
    }

    /** 带 tag 的值。 */
    public static final class TaggedValue {
        public final int tag;
        public final Object value;

        TaggedValue(int tag, Object value) {
            this.tag = tag;
            this.value = value;
        }
    }

    /** 按类型读负载。 */
    public Object value(int type) {
        switch (type) {
            case 0:
                return (int) get(1)[0];
            case 1:
                return (int) (short) ((u8() << 8) | u8());
            case 2:
                return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
            case 3: {
                long hi = ((long) (u8() << 24 | u8() << 16 | u8() << 8 | u8())) & 0xFFFFFFFFL;
                long lo = ((long) (u8() << 24 | u8() << 16 | u8() << 8 | u8())) & 0xFFFFFFFFL;
                return (hi << 32) | lo;
            }
            case 4:
                return Float.intBitsToFloat((u8() << 24) | (u8() << 16) | (u8() << 8) | u8());
            case 5: {
                long hi = ((long) (u8() << 24 | u8() << 16 | u8() << 8 | u8())) & 0xFFFFFFFFL;
                long lo = ((long) (u8() << 24 | u8() << 16 | u8() << 8 | u8())) & 0xFFFFFFFFL;
                return Double.longBitsToDouble((hi << 32) | lo);
            }
            case 6: {
                int n = u8();
                return new String(get(n), StandardCharsets.UTF_8);
            }
            case 7: {
                int n = (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
                return new String(get(n), StandardCharsets.UTF_8);
            }
            case 8: {
                int n = intValue();
                Map<Object, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    Object k = fieldValue();
                    Object v = fieldValue();
                    m.put(k, v);
                }
                return m;
            }
            case 9: {
                int n = intValue();
                List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    list.add(fieldValue());
                }
                return list;
            }
            case 10:
                return struct();
            case 11:
                return null;
            case 12:
                return 0;
            case 13: {
                head();
                int n = intValue();
                return get(n);
            }
            default:
                throw new JceFormatException("未知 JCE 类型: " + type);
        }
    }

    /** 读一个有 tag 的值，丢掉 tag。 */
    public Object fieldValue() {
        int[] h = head();
        return value(h[0]);
    }

    /** 读一个"长度/整数字段"：先读头，再取其整数值。 */
    public int intValue() {
        Object v = fieldValue();
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        throw new JceFormatException("期望整数, 实际 " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    /** 读整个结构体，直到遇到 type 11 或数据耗尽。键为 tag。 */
    public Map<Integer, Object> struct() {
        Map<Integer, Object> m = new LinkedHashMap<>();
        while (rem() > 0) {
            int[] h = head();
            if (h[0] == 11) {
                break;
            }
            m.put(h[1], value(h[0]));
        }
        return m;
    }

    /** 取值糖：取 int，缺省给 def。 */
    public static int optInt(Map<Integer, Object> m, int tag, int def) {
        Object v = m == null ? null : m.get(tag);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    /** 取值糖：取字符串，缺省给 def。 */
    public static String optString(Map<Integer, Object> m, int tag, String def) {
        Object v = m == null ? null : m.get(tag);
        return v instanceof String ? (String) v : def;
    }
}
