package com.gary.ysplive.core.util;

/**
 * 十六进制编解码。纯 Java，不依赖任何第三方库。
 * <p>与 Python 参考实现里的 {@code bytes.hex()} / {@code bytes.fromhex()} 对齐：
 * 编码一律小写，解码容忍大小写与空白。
 */
public final class Hex {

    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    /** 编码为小写十六进制；{@code null} 返回空串。 */
    public static String encode(byte[] b) {
        return encode(b, LOWER);
    }

    /** 编码为大写十六进制；{@code null} 返回空串。 */
    public static String encodeUpper(byte[] b) {
        return encode(b, UPPER);
    }

    private static String encode(byte[] b, char[] table) {
        if (b == null || b.length == 0) {
            return "";
        }
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[i * 2] = table[v >>> 4];
            out[i * 2 + 1] = table[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 解码十六进制字符串。容忍大小写、空白（空格 / 制表 / 换行 / 回车）与 {@code 0x} 前缀；
     * 奇数长度或非法字符抛 {@link IllegalArgumentException}。
     */
    public static byte[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        int n = s.length();
        byte[] tmp = new byte[(n + 1) / 2];
        int out = 0;
        int hi = -1;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            int d = digit(c);
            if (d < 0) {
                throw new IllegalArgumentException("invalid hex char at " + i + ": " + c);
            }
            if (hi < 0) {
                hi = d;
            } else {
                tmp[out++] = (byte) ((hi << 4) | d);
                hi = -1;
            }
        }
        if (hi >= 0) {
            throw new IllegalArgumentException("hex string has odd length");
        }
        if (out == tmp.length) {
            return tmp;
        }
        byte[] r = new byte[out];
        System.arraycopy(tmp, 0, r, 0, out);
        return r;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
