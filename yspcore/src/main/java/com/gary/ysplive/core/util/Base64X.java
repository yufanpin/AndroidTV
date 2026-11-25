package com.gary.ysplive.core.util;

/**
 * 手写 Base64。**刻意不使用 {@code java.util.Base64}**：那是 Android API 26+ 才有的，
 * 本项目 minSdk 是 21。也不使用 {@code android.util.Base64}，因为 core 必须零安卓依赖、
 * 能在桌面 JVM 上直接跑单元测试。
 *
 * <p>与 Python {@code base64.b64encode} / {@code base64.b64decode} 行为对齐：
 * <ul>
 *   <li>{@link #encode(byte[])}：标准字母表，带 {@code =} padding。</li>
 *   <li>{@link #encodeUrlSafeNoPad(byte[])}：{@code -} {@code _} 字母表，去掉 padding
 *       （等价 Python {@code urlsafe_b64encode(...).rstrip(b'=')}）。</li>
 *   <li>{@link #decode(String)}：容忍标准 / urlsafe 两种字母表、padding 可缺、容忍空白与换行。</li>
 * </ul>
 */
public final class Base64X {

    private static final char[] STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] REV = new int[256];

    static {
        java.util.Arrays.fill(REV, -1);
        for (int i = 0; i < STD.length; i++) {
            REV[STD[i]] = i;
        }
        // urlsafe 字母表
        REV['-'] = 62;
        REV['_'] = 63;
    }

    private Base64X() {
    }

    /** 标准 base64，带 padding。 */
    public static String encode(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        return encode(b, STD, true);
    }

    /** urlsafe base64（{@code '-'} / {@code '_'}），不带 padding。 */
    public static String encodeUrlSafeNoPad(byte[] b) {
        if (b == null || b.length == 0) {
            return "";
        }
        return encode(b, STD, false).replace('+', '-').replace('/', '_').replace("=", "");
    }

    private static String encode(byte[] b, char[] table, boolean pad) {
        int n = b.length;
        int outLen = ((n + 2) / 3) * 4;
        char[] out = new char[outLen];
        int o = 0;
        int i = 0;
        while (i + 3 <= n) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF);
            out[o++] = table[(v >>> 18) & 0x3F];
            out[o++] = table[(v >>> 12) & 0x3F];
            out[o++] = table[(v >>> 6) & 0x3F];
            out[o++] = table[v & 0x3F];
            i += 3;
        }
        int rem = n - i;
        if (rem == 1) {
            int v = (b[i] & 0xFF) << 16;
            out[o++] = table[(v >>> 18) & 0x3F];
            out[o++] = table[(v >>> 12) & 0x3F];
            if (pad) {
                out[o++] = '=';
                out[o] = '=';
            }
        } else if (rem == 2) {
            int v = ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8);
            out[o++] = table[(v >>> 18) & 0x3F];
            out[o++] = table[(v >>> 12) & 0x3F];
            out[o++] = table[(v >>> 6) & 0x3F];
            if (pad) {
                out[o] = '=';
            }
        }
        // pad=false 时数组尾部可能残留未写入的 '\0'，这里按实际长度裁剪
        if (!pad) {
            int real = ((n + 2) / 3) * 4 - (rem == 1 ? 2 : rem == 2 ? 1 : 0);
            return new String(out, 0, real);
        }
        return new String(out);
    }

    /** 解码；兼容标准 / urlsafe、padding 可缺、容忍空白。非法输入抛 {@link IllegalArgumentException}。 */
    public static byte[] decode(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        int n = s.length();
        byte[] out = new byte[(n * 3) / 4 + 3];
        int o = 0;
        int acc = 0;
        int bits = 0;
        int count = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c == '=') {
                // padding 之后不允许再有有效字符
                break;
            }
            int d = c < 256 ? REV[c] : -1;
            if (d < 0) {
                throw new IllegalArgumentException("invalid base64 char at " + i + ": " + c);
            }
            acc = (acc << 6) | d;
            bits += 6;
            count++;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((acc >>> bits) & 0xFF);
            }
        }
        if (count % 4 == 1) {
            throw new IllegalArgumentException("invalid base64 length (truncated group)");
        }
        if (o == out.length) {
            return out;
        }
        byte[] r = new byte[o];
        System.arraycopy(out, 0, r, 0, o);
        return r;
    }
}
