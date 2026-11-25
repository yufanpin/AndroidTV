package com.gary.ysplive.core.util;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256 单块 ECB 加密。仅提供 ECB 单块（16 字节）原语，供 CTR 模式在
 * {@code core.jce} 里自行拼装计数器块用 —— 参考实现里 Python 是手写 AES + 手写 {@code _gctr}，
 * 这里用 JDK/Android 自带的 AES 实现替代，结果等价且更快、更不容易出错。
 *
 * <p>本类只做**加密**：CTR 与 GCM 的密钥流都只需要正向加密，不需要解密或填充。
 */
public final class Aes256 {

    /** 分组长度（字节）。 */
    public static final int BLOCK = 16;

    private Aes256() {
    }

    /**
     * 用 256 位密钥加密单个 16 字节分组（{@code AES/ECB/NoPadding}）。
     *
     * @param key32 32 字节密钥
     * @param block16 恰好 16 字节明文块
     * @return 16 字节密文块
     */
    public static byte[] encryptEcbBlock(byte[] key32, byte[] block16) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes, got "
                    + (key32 == null ? "null" : String.valueOf(key32.length)));
        }
        if (block16 == null || block16.length != BLOCK) {
            throw new IllegalArgumentException("AES block must be 16 bytes, got "
                    + (block16 == null ? "null" : String.valueOf(block16.length)));
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"));
            return c.doFinal(block16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256 ECB encrypt failed", e);
        }
    }

    /**
     * 计数器块自增：把最后 4 字节按大端 uint32 加一（对齐 Python {@code _inc32}）。
     * 返回新数组，不修改入参。
     */
    public static byte[] inc32(byte[] block16) {
        if (block16 == null || block16.length != BLOCK) {
            throw new IllegalArgumentException("counter block must be 16 bytes");
        }
        byte[] out = block16.clone();
        long ctr = ((long) (out[12] & 0xFF) << 24)
                | ((long) (out[13] & 0xFF) << 16)
                | ((long) (out[14] & 0xFF) << 8)
                | (out[15] & 0xFF);
        ctr = (ctr + 1) & 0xFFFFFFFFL;
        out[12] = (byte) (ctr >>> 24);
        out[13] = (byte) (ctr >>> 16);
        out[14] = (byte) (ctr >>> 8);
        out[15] = (byte) ctr;
        return out;
    }

    /**
     * 通用计数器模式（GCM 风格的 CTR），与 Python {@code _gctr(key, icb, data)} 逐字节等价：
     * 用 {@code icb} 作为第一个计数器块加密，之后每块 {@link #inc32} 自增，密钥流与数据异或，
     * 末尾不足 16 字节的块只用前若干个密钥流字节。加解密同一函数。
     *
     * @param key32 32 字节密钥
     * @param icb 16 字节初始计数器块（注意 GCM 里传的是 {@code _inc32(j0)}，即 counter=2）
     * @param data 任意长度数据
     */
    public static byte[] gctr(byte[] key32, byte[] icb, byte[] data) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        if (icb == null || icb.length != BLOCK) {
            throw new IllegalArgumentException("initial counter block must be 16 bytes");
        }
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"));
            byte[] out = new byte[data.length];
            byte[] cb = icb.clone();
            for (int off = 0; off < data.length; off += BLOCK) {
                byte[] ks = c.doFinal(cb);
                int n = Math.min(BLOCK, data.length - off);
                for (int i = 0; i < n; i++) {
                    out[off + i] = (byte) (data[off + i] ^ ks[i]);
                }
                cb = inc32(cb);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256 CTR failed", e);
        }
    }
}
