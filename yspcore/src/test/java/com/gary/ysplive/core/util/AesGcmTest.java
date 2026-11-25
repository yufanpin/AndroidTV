package com.gary.ysplive.core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.gary.ysplive.core.testutil.Vectors;

/**
 * AES-256-GCM 正确性测试。
 *
 * <p><b>本类最重要的用例是 {@link #matchesPythonVectors()}：</b>
 * 向量由 {@code tools/gen_crypto_vectors.py} 直接调用参考实现
 * {@code ysp-live-v9.5.py} 的 {@code aes_gcm_encrypt} 生成。GCM 输出必须逐字节相同
 * （GCM 是确定性的，因为 nonce 由向量给定）。这是整个移植项目最关键的密码学门禁：
 * 一旦这里偏了，登录/取流全部失败且现象是"服务端 500"，极难排查。
 */
public class AesGcmTest {

    private static byte[] hex(String s) {
        return s == null || s.isEmpty() ? new byte[0] : Hex.decode(s);
    }

    /** 与 Python 参考实现逐字节对比（核心门禁）。 */
    @Test
    public void matchesPythonVectors() {
        List<Map<String, Object>> cases = Vectors.cases("vectors-aesgcm.json");
        assertTrue("expected at least 8 vectors", cases.size() >= 8);
        for (Map<String, Object> c : cases) {
            byte[] key = hex(Json.str(c, "key", ""));
            byte[] nonce = hex(Json.str(c, "nonce", ""));
            byte[] pt = hex(Json.str(c, "plaintext", ""));
            byte[] aad = hex(Json.str(c, "aad", ""));
            byte[] want = hex(Json.str(c, "ciphertextAndTag", ""));

            byte[] got = AesGcm.encrypt(key, nonce, pt, aad);
            assertArrayEquals("encrypt mismatch for key=" + Hex.encode(key)
                    + " nonce=" + Hex.encode(nonce) + " ptLen=" + pt.length,
                    want, got);

            // 解密方向也必须一致
            byte[] back = AesGcm.decrypt(key, nonce, want, aad);
            assertArrayEquals("decrypt mismatch for ptLen=" + pt.length, pt, back);
        }
    }

    /** 空 AAD 与传 null 必须等价（Python 默认 aad=b''）。 */
    @Test
    public void nullAadEqualsEmptyAad() {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        byte[] pt = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(AesGcm.encrypt(key, nonce, pt, null), AesGcm.encrypt(key, nonce, pt, new byte[0]));
    }

    /** 往返：多长度。 */
    @Test
    public void roundTripVariousLengths() {
        byte[] key = Crypto.randomBytes(32);
        for (int len : new int[]{0, 1, 15, 16, 17, 31, 32, 33, 100, 1000, 70000}) {
            byte[] nonce = Crypto.randomBytes(12);
            byte[] pt = Crypto.randomBytes(len);
            byte[] ct = AesGcm.encrypt(key, nonce, pt);
            assertEquals("ciphertext length must be pt+16 for len=" + len, len + 16, ct.length);
            assertArrayEquals("round trip failed for len=" + len, pt, AesGcm.decrypt(key, nonce, ct));
        }
    }

    /** tag 被篡改必须失败。 */
    @Test
    public void tamperedTagFails() {
        byte[] key = Crypto.randomBytes(32);
        byte[] nonce = Crypto.randomBytes(12);
        byte[] ct = AesGcm.encrypt(key, nonce, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ct[ct.length - 1] ^= 0x01;
        try {
            AesGcm.decrypt(key, nonce, ct);
            fail("tampered tag must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("decrypt failed"));
        }
    }

    /** 载荷过短必须失败（对应 Python 'AES-GCM payload too short'）。 */
    @Test
    public void tooShortPayloadFails() {
        try {
            AesGcm.decrypt(new byte[32], new byte[12], new byte[15]);
            fail("short payload must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("too short"));
        }
    }

    /** normalizeKey 对齐 Python：UTF-8 + 右侧补零 / 截断到 32。 */
    @Test
    public void normalizeKeyMatchesPython() {
        assertEquals(32, AesGcm.normalizeKey("").length);
        assertEquals(32, AesGcm.normalizeKey("short").length);
        byte[] k = AesGcm.normalizeKey("abc");
        assertEquals('a', k[0]);
        assertEquals('b', k[1]);
        assertEquals('c', k[2]);
        assertEquals(0, k[3]);
        // 中文 3 字节/字 -> "中" 是 3 字节
        byte[] kc = AesGcm.normalizeKey("中");
        assertEquals((byte) 0xE4, kc[0]);
        // 超长截断
        String longKey = "0123456789abcdefghijklmnopqrstuvwxyz";
        byte[] kl = AesGcm.normalizeKey(longKey);
        assertEquals(32, kl.length);
        assertEquals((byte) 'v', kl[31]);
    }

    /** base64 版往返（对应 aes_gcm_encrypt_b64 / aes_gcm_decrypt_b64 的线格式）。 */
    @Test
    public void base64RoundTrip() {
        String key = "abcdef0123456789abcdef0123456789";
        String value = "央视频 session_key 测试 / test";
        String encoded = AesGcm.encryptB64(value, key);
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
        // 线格式: base64( nonce(12) || ct || tag(16) )，所以原始长度 = 12+len+16
        byte[] raw = Base64X.decode(encoded);
        int ptLen = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(12 + ptLen + 16, raw.length);
        assertEquals(value, AesGcm.decryptB64(encoded, key));
    }

    /** 解密 base64 非法字符必须报错而不是静默返回垃圾。 */
    @Test
    public void badBase64Fails() {
        try {
            AesGcm.decryptB64("!!!not base64!!!", "k");
            fail("bad base64 must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("base64"));
        }
    }

    /** 密钥/ nonce 长度校验。 */
    @Test
    public void keyAndNonceLengthValidation() {
        try {
            AesGcm.encrypt(new byte[16], new byte[12], new byte[0]);
            fail("16-byte key must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("32"));
        }
        try {
            AesGcm.encrypt(new byte[32], new byte[16], new byte[0]);
            fail("16-byte nonce must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("12"));
        }
    }
}
