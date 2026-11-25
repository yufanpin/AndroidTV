package com.gary.ysplive.core.util;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM，与 Python 参考实现 {@code aes_gcm_encrypt} / {@code aes_gcm_decrypt}
 * （line 158-178）**逐字节等价**。
 *
 * <p>Python 侧是手写 GHASH/GF(2^128) 的教科书式实现：12 字节 nonce、J0 = nonce||0x00000001、
 * 16 字节 tag 追加在密文后、支持 AAD。这正是 NIST SP 800-38D 的标准 GCM，所以这里直接用
 * JDK/Android 自带的 {@code AES/GCM/NoPadding}，**不**移植手写 GHASH —— 更快、更不容易出错，
 * 且两平台（Android API 21+ 与桌面 JVM）都提供。
 *
 * <p>线格式（与 Python 完全一致）：
 * <pre>
 *   encrypt(key, nonce, pt)          -> ciphertext || tag(16)
 *   aes_gcm_encrypt_b64(key, pt)     -> base64(nonce(12) || ciphertext || tag(16))
 *   decrypt(key, nonce, ct||tag)     -> plaintext（tag 校验失败抛异常）
 * </pre>
 *
 * <p>默认 AAD 为空串；需要 AAD 时用带 {@code aad} 参数的重载。
 */
public final class AesGcm {

    /** nonce 长度（字节），Python 固定 12。 */
    public static final int NONCE_LEN = 12;
    /** tag 长度（字节），Python 固定 16。 */
    public static final int TAG_LEN = 16;
    /** tag 位数，给 JCE 用。 */
    private static final int TAG_BITS = TAG_LEN * 8;

    private AesGcm() {
    }

    /** 无 AAD 加密，返回 {@code ciphertext || tag(16)}。 */
    public static byte[] encrypt(byte[] key, byte[] nonce12, byte[] plaintext) {
        return encrypt(key, nonce12, plaintext, null);
    }

    /**
     * 加密，返回 {@code ciphertext || tag(16)}。
     *
     * @param key 必须 32 字节（AES-256）
     * @param nonce12 必须 12 字节
     * @param plaintext 任意长度（可为空）
     * @param aad 附加认证数据，{@code null} 或空数组等价于无 AAD
     */
    public static byte[] encrypt(byte[] key, byte[] nonce12, byte[] plaintext, byte[] aad) {
        checkKey(key);
        checkNonce(nonce12);
        byte[] pt = plaintext == null ? new byte[0] : plaintext;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce12));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            // JDK/Android 的 GCM 把 tag 追加在密文后，正好是 Python 的返回格式
            return c.doFinal(pt);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** 无 AAD 解密；输入 {@code ciphertext || tag(16)}。 */
    public static byte[] decrypt(byte[] key, byte[] nonce12, byte[] ctAndTag) {
        return decrypt(key, nonce12, ctAndTag, null);
    }

    /**
     * 解密；输入 {@code ciphertext || tag(16)}。
     *
     * @throws IllegalStateException tag 校验失败或输入过短（对应 Python 的 {@code ValueError}）
     */
    public static byte[] decrypt(byte[] key, byte[] nonce12, byte[] ctAndTag, byte[] aad) {
        checkKey(key);
        checkNonce(nonce12);
        if (ctAndTag == null || ctAndTag.length < TAG_LEN) {
            throw new IllegalStateException("AES-GCM payload too short");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce12));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            return c.doFinal(ctAndTag);
        } catch (GeneralSecurityException e) {
            // Python 侧抛 ValueError('AES-GCM decrypt failed')；这里用 IllegalStateException 表达同样的语义
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    /**
     * 等价 Python {@code normalize_aes_key(value)}：把字符串按 UTF-8 取字节，
     * 右侧补 {@code 0x00} 到 32 字节，超长截断到 32 字节。
     */
    public static byte[] normalizeKey(String value) {
        byte[] raw = value == null ? new byte[0] : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Arrays.copyOf(raw, 32);
    }

    /**
     * 等价 Python {@code aes_gcm_encrypt_b64(value, key)}：
     * 随机 12 字节 nonce，返回 {@code base64(nonce || ciphertext || tag)}（标准带 padding）。
     */
    public static String encryptB64(String value, String key) {
        byte[] nonce = Crypto.randomBytes(NONCE_LEN);
        byte[] enc = encrypt(normalizeKey(key), nonce,
                (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] out = new byte[NONCE_LEN + enc.length];
        System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
        System.arraycopy(enc, 0, out, NONCE_LEN, enc.length);
        return Base64X.encode(out);
    }

    /**
     * 等价 Python {@code aes_gcm_decrypt_b64(value, key)}：解 {@code base64(nonce||ct||tag)}，
     * 用前 12 字节做 nonce。
     *
     * @throws IllegalStateException base64 非法 / 载荷过短 / tag 校验失败 / 明文非 UTF-8
     */
    public static String decryptB64(String value, String key) {
        byte[] raw;
        try {
            raw = Base64X.decode(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("base64 decode failed: " + e.getMessage(), e);
        }
        if (raw.length <= NONCE_LEN) {
            throw new IllegalStateException("AES-GCM payload too short");
        }
        byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(raw, NONCE_LEN, raw.length);
        byte[] plain = decrypt(normalizeKey(key), nonce, ct);
        try {
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException("AES-GCM plaintext is not UTF-8: " + e.getMessage(), e);
        }
    }

    private static void checkKey(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-GCM key must be 32 bytes, got "
                    + (key == null ? "null" : String.valueOf(key.length)));
        }
    }

    private static void checkNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("AES-GCM nonce must be 12 bytes, got "
                    + (nonce == null ? "null" : String.valueOf(nonce.length)));
        }
    }
}