package com.gary.ysplive.core.jce;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JCE 二进制 TLV 写入器。
 * <p>逐字节复刻 Python 参考实现里的 {@code W} 类。JCE 是腾迅内部序列化格式：
 * <ul>
 *   <li>头字节：{@code (tag << 4) | type}；tag &gt;= 15 时写 {@code 0xF0|type} 再补一个 tag 字节</li>
 *   <li>整数按"能塞小的就用小的"逐级降级：byte(0) → short(1) → int(2) → long(3)</li>
 *   <li>0 用 type 12（零值）表示，不写负载</li>
 *   <li>字符串：长度 &lt;= 255 用 type 6（1 字节长度），否则 type 7（4 字节长度）</li>
 *   <li>bytes：type 13 后面跟一个"长度字段"（head(0,0) + int(长度)）+ 裸数据</li>
 *   <li>struct：type 10 + 内容 + type 11 结束标记</li>
 *   <li>list：type 9 + 4 字节长度 + 逐项（每项自带 tag 0）</li>
 * </ul>
 * 全部用大端。本类非线程安全，每次请求新建一个即可。
 */
public final class JceWriter {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

    /** 原始字节（调试/测试用）。 */
    public byte[] toByteArray() {
        return buf.toByteArray();
    }

    public int size() {
        return buf.size();
    }

    private void u8(int v) {
        buf.write(v & 0xFF);
    }

    private void be16(int v) {
        u8(v >>> 8);
        u8(v);
    }

    private void be32(int v) {
        u8(v >>> 24);
        u8(v >>> 16);
        u8(v >>> 8);
        u8(v);
    }

    private void be64(long v) {
        be32((int) (v >>> 32));
        be32((int) v);
    }

    /** 写类型 + tag 头。{@code tag >= 15} 走两字节形式。 */
    public void head(int type, int tag) {
        if (tag < 15) {
            u8(((tag & 0x0F) << 4) | (type & 0x0F));
        } else {
            u8(0xF0 | (type & 0x0F));
            u8(tag);
        }
    }

    /** 写 byte；0 用 type 12 表示。 */
    public void writeByte(int v, int tag) {
        if (v == 0) {
            head(12, tag);
        } else {
            head(0, tag);
            u8(v);
        }
    }

    public void writeShort(int v, int tag) {
        if (v >= -128 && v <= 127) {
            writeByte(v, tag);
        } else {
            head(1, tag);
            be16(v);
        }
    }

    public void writeInt(int v, int tag) {
        if (v >= -32768 && v <= 32767) {
            writeShort(v, tag);
        } else {
            head(2, tag);
            be32(v);
        }
    }

    public void writeLong(long v, int tag) {
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            writeInt((int) v, tag);
        } else {
            head(3, tag);
            be64(v);
        }
    }

    public void writeFloat(float v, int tag) {
        head(4, tag);
        be32(Float.floatToIntBits(v));
    }

    public void writeDouble(double v, int tag) {
        head(5, tag);
        be64(Double.doubleToLongBits(v));
    }

    /** 写字符串；{@code null} 直接跳过（与 Python 的 {@code if s is None: return} 一致）。 */
    public void writeString(String s, int tag) {
        if (s == null) {
            return;
        }
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (data.length > 255) {
            head(7, tag);
            be32(data.length);
            buf.write(data, 0, data.length);
        } else {
            head(6, tag);
            u8(data.length);
            buf.write(data, 0, data.length);
        }
    }

    /** 写二进制；type 13 + 内嵌长度字段 + 裸数据。 */
    public void writeBytes(byte[] data, int tag) {
        head(13, tag);
        head(0, 0);
        writeInt(data.length, 0);
        buf.write(data, 0, data.length);
    }

    /** 结构体写入回调。 */
    public interface StructFn {
        void write(JceWriter w);
    }

    /** 写结构体：type 10 + 回调内容 + type 11 结束。 */
    public void writeStruct(StructFn fn, int tag) {
        head(10, tag);
        if (fn != null) {
            fn.write(this);
        }
        head(11, 0);
    }

    /** 列表元素写入回调。 */
    public interface WriteFn<T> {
        void write(JceWriter w, T item);
    }

    /**
     * 写列表：type 9 + 4 字节长度 + 逐项。
     * <p>{@code writeFn} 为 {@code null} 时只写长度 0（与 Python 传 {@code None} 等价）。
     */
    public <T> void writeList(java.util.List<T> items, int tag, WriteFn<T> writeFn) {
        head(9, tag);
        int n = items == null ? 0 : items.size();
        writeInt(n, 0);
        if (writeFn != null && items != null) {
            for (T item : items) {
                writeFn.write(this, item);
            }
        }
    }

    /** 取出封好的字节。 */
    public byte[] out() {
        return buf.toByteArray();
    }
}
