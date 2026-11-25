package com.gary.ysplive.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 摘要与随机数工具。对齐 Python 参考实现：
 * <ul>
 *   <li>{@code sha1_upper(value)} = {@code hashlib.sha1(value.encode('utf-8')).hexdigest().upper()}</li>
 *   <li>{@code sha256_hex(value)} / {@code md5_hex(value)} 输出小写 hex</li>
 *   <li>{@code random_hex_string(length)} = 从 {@code "0123456789abcdef"} 里逐字符随机取</li>
 *   <li>{@code random_mac_address()} = 6 字节随机，首字节 {@code (b|2)&254}，冒号连接小写 hex</li>
 * </ul>
 * 所有输入字符串一律按 UTF-8 取字节。
 */
public final class Crypto {

    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    private static final char[] HEX_CHOICE = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    /** SHA-1 十六进制，**大写**（对齐 {@code sha1_upper}）。 */
    public static String sha1Upper(String s) {
        return Hex.encodeUpper(sha1(utf8(s)));
    }

    /** SHA-256 十六进制，小写。 */
    public static String sha256Hex(String s) {
        return Hex.encode(sha256(utf8(s)));
    }

    /** MD5 十六进制，小写。 */
    public static String md5Hex(String s) {
        return Hex.encode(md5(utf8(s)));
    }

    public static byte[] sha1(byte[] b) {
        return digest("SHA-1", b);
    }

    public static byte[] sha256(byte[] b) {
        return digest("SHA-256", b);
    }

    public static byte[] md5(byte[] b) {
        return digest("MD5", b);
    }

    private static byte[] digest(String algo, byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return md.digest(b == null ? new byte[0] : b);
        } catch (NoSuchAlgorithmException e) {
            // Android 与桌面 JVM 都必然提供 SHA-1/SHA-256/MD5，走到这里说明运行环境异常
            throw new IllegalStateException("digest unavailable: " + algo, e);
        }
    }

    /**
     * {@code len} 个字符的小写十六进制随机串（对齐 {@code random_hex_string}）。
     * 注意：参数是**字符数**，不是字节数。
     */
    public static String randomHex(int len) {
        if (len <= 0) {
            return "";
        }
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = HEX_CHOICE[RANDOM.nextInt(16)];
        }
        return new String(out);
    }

    /** 形如 {@code aa:bb:cc:dd:ee:ff}；首字节按 {@code (b|2)&254} 置为本地管理 + 单播。 */
    public static String randomMac() {
        byte[] raw = new byte[6];
        RANDOM.nextBytes(raw);
        raw[0] = (byte) (((raw[0] | 2) & 254) & 0xFF);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int v = raw[i] & 0xFF;
            sb.append(HEX_LOWER[v >>> 4]).append(HEX_LOWER[v & 0x0F]);
        }
        return sb.toString();
    }

    /** 全局共享的 {@link SecureRandom}（已播种，可跨线程用）。 */
    public static SecureRandom random() {
        return RANDOM;
    }

    /** 随机字节数组。 */
    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
