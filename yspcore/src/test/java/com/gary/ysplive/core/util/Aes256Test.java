package com.gary.ysplive.core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.gary.ysplive.core.testutil.Vectors;

/**
 * AES-256 单块 ECB 与 CTR 测试。
 *
 * <p>ECB 单块是 {@code core.jce} 拼 CTR 的原语，所以必须与 NIST AES-256 已知答案一致；
 * 这里用 FIPS-197 附录 C.3 的标准向量把底子钉死，再用 Python 侧 {@code aes256_encrypt_block}
 * 交叉验证（如果 {@code core.jce} 的 CTR 结果和 Python {@code _gctr} 不同，多半是这里或
 * 计数器块拼接出错）。
 */
public class Aes256Test {

    /** FIPS-197 C.3: AES-256(key=000102...1f, plaintext=00112233445566778899aabbccddeeff)。 */
    @Test
    public void fips197Aes256KnownAnswer() {
        byte[] key = Hex.decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] pt = Hex.decode("00112233445566778899aabbccddeeff");
        byte[] want = Hex.decode("8ea2b7ca516745bfeafc49904b496089");
        assertArrayEquals(want, Aes256.encryptEcbBlock(key, pt));
    }

    /** AES-128 的已知答案不能直接复用（本类只支持 256），但要确认 128 位密钥被拒绝。 */
    @Test
    public void rejectsWrongKeyLength() {
        try {
            Aes256.encryptEcbBlock(new byte[16], new byte[16]);
            fail("16-byte key must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("32"));
        }
    }

    @Test
    public void rejectsWrongBlockLength() {
        try {
            Aes256.encryptEcbBlock(new byte[32], new byte[15]);
            fail("15-byte block must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("16"));
        }
    }

    /** inc32 只加最后 4 字节，大端，回绕到 0。 */
    @Test
    public void inc32BigEndianWrap() {
        byte[] b = Hex.decode("00112233445566778899aabbfffffffe");
        assertArrayEquals(Hex.decode("00112233445566778899aabbffffffff"), Aes256.inc32(b));
        assertArrayEquals(Hex.decode("00112233445566778899aabb00000000"), Aes256.inc32(Aes256.inc32(b)));
    }

    /**
     * CTR 加解密等价性 + 与 Python {@code _gctr} 的一致性。
     * <p>Python 侧 {@code _gctr(key, icb, data)} 用 icb=nonce||0x00000002（GCM 的 counter=2）。
     * 这里直接用它做交叉验证：GCM 已单独验过整链路，这里验原语。
     */
    @Test
    public void gctrIsSymmetric() {
        byte[] key = Hex.decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] icb = Hex.decode("00000000000000000000000000000002");
        byte[] data = "The quick brown fox jumps over the lazy dog. 中文".getBytes(StandardCharsets.UTF_8);
        byte[] enc = Aes256.gctr(key, icb, data);
        assertArrayEquals(data, Aes256.gctr(key, icb, enc));
        assertEquals(data.length, enc.length);
    }

    /** CTR 的密钥流与 AES-GCM 的密钥流必须一致（同一密钥/nonce 下 GCM 的 ct 等于纯 CTR）。 */
    @Test
    public void gctrMatchesGcmKeyStream() {
        byte[] key = Crypto.randomBytes(32);
        byte[] nonce = Crypto.randomBytes(12);
        byte[] data = Crypto.randomBytes(77);
        byte[] gcm = AesGcm.encrypt(key, nonce, data);
        // GCM 的 J0 = nonce||00000001，密文用 inc32(J0) = nonce||00000002 起
        byte[] icb = Aes256.inc32(concat(nonce, Hex.decode("00000001")));
        byte[] ctr = Aes256.gctr(key, icb, data);
        byte[] gcmCt = new byte[data.length];
        System.arraycopy(gcm, 0, gcmCt, 0, data.length);
        assertArrayEquals(gcmCt, ctr);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 用 AES-GCM 向量反推：GCM 密文（去 tag）经 gctr 解出明文，再 gctr 加密回原密文。 */
    @Test
    public void gctrMatchesPythonVectorsViaGcm() {
        List<Map<String, Object>> cases = Vectors.cases("vectors-aesgcm.json");
        int checked = 0;
        for (Map<String, Object> c : cases) {
            byte[] key = Hex.decode(Json.str(c, "key", ""));
            byte[] nonce = Hex.decode(Json.str(c, "nonce", ""));
            byte[] pt = Hex.decode(Json.str(c, "plaintext", ""));
            byte[] ctAll = Hex.decode(Json.str(c, "ciphertextAndTag", ""));
            if (ctAll.length <= 16) {
                continue; // 空明文没有密文部分可比
            }
            byte[] wantCt = new byte[ctAll.length - 16];
            System.arraycopy(ctAll, 0, wantCt, 0, wantCt.length);
            byte[] icb = Aes256.inc32(concat(nonce, Hex.decode("00000001")));
            // Python 的 GCM 密文 == gctr(key, inc32(J0), plaintext)，直接正向比对
            assertArrayEquals("gctr vs python gcm ct (ptLen=" + pt.length + ")",
                    wantCt, Aes256.gctr(key, icb, pt));
            checked++;
        }
        assertTrue("should have checked at least one vector", checked > 0);
    }

    /** 空数据返回空数组，不炸。 */
    @Test
    public void gctrEmptyData() {
        byte[] key = new byte[32];
        byte[] icb = new byte[16];
        assertArrayEquals(new byte[0], Aes256.gctr(key, icb, new byte[0]));
        assertArrayEquals(new byte[0], Aes256.gctr(key, icb, null));
    }
}
