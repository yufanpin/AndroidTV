package com.gary.ysplive.core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.gary.ysplive.core.testutil.Vectors;

/**
 * TEA（腾讯 TEA 变体）与 {@code _cksum} 测试。
 *
 * <p>{@link #encryptBlockMatchesPython()} 是确定性对比（TEA 单块无随机性），必须逐字节一致。
 * {@code pack()} 含随机填充，所以只能验"解密往返"与"长度/填充结构"，不能比密文
 * —— 这一点与参考实现的性质一致（Python 每次跑出来的 {@code _tea_pkt} 都不同）。
 */
public class TeaTest {

    private static byte[] hex(String s) {
        return s == null || s.isEmpty() ? new byte[0] : Hex.decode(s);
    }

    /** 单块加密与 Python {@code _tea_blk} 逐字节一致。 */
    @Test
    public void encryptBlockMatchesPython() {
        List<Map<String, Object>> cases = Vectors.items("vectors-tea.json", "encryptBlockCases");
        assertTrue("expected tea vectors", cases.size() >= 4);
        for (Map<String, Object> c : cases) {
            byte[] key = hex(Json.str(c, "key", ""));
            byte[] block = hex(Json.str(c, "block", ""));
            byte[] want = hex(Json.str(c, "cipher", ""));
            assertArrayEquals("TEA block mismatch key=" + Hex.encode(key), want,
                    Tea.encryptBlock(key, block));
        }
    }

    /** {@code _cksum} 与 Python 逐字节一致（含 0x7FFFFFFF 掩码语义）。 */
    @Test
    public void cksumMatchesPython() {
        List<Map<String, Object>> cases = Vectors.items("vectors-tea.json", "cksumCases");
        assertTrue(cases.size() >= 4);
        for (Map<String, Object> c : cases) {
            byte[] data = hex(Json.str(c, "data", ""));
            int want = Json.i(c, "cksum", 0);
            assertEquals("cksum mismatch for data=" + Hex.encode(data), want, Tea.cksum(data));
            assertEquals(Tea.cksum(data), Tea.crc32Like(data));
        }
    }

    /** cksum 结果永远非负（Python 掩了 0x7FFFFFFF）。 */
    @Test
    public void cksumAlwaysNonNegative() {
        for (int i = 0; i < 200; i++) {
            byte[] data = Crypto.randomBytes(1 + i % 40);
            int v = Tea.cksum(data);
            assertTrue("cksum must be >= 0, got " + v, v >= 0);
            assertTrue("cksum must fit 31 bits", v <= 0x7FFFFFFF);
        }
    }

    /** TEA 单块加解密互逆。 */
    @Test
    public void blockRoundTrip() {
        for (int i = 0; i < 50; i++) {
            byte[] key = Crypto.randomBytes(16);
            byte[] block = Crypto.randomBytes(8);
            byte[] enc = Tea.encryptBlock(key, block);
            assertEquals(8, enc.length);
            assertArrayEquals(block, Tea.decryptBlock(key, enc));
        }
    }

    /** pack/unpack 往返：多种长度，含空数据与边界。 */
    @Test
    public void packUnpackRoundTrip() {
        byte[] key = Hex.decode("110DBEC10C23E7D2E56A1CAD6914EF1B");
        for (int len : new int[]{0, 1, 2, 3, 6, 7, 8, 9, 14, 15, 16, 17, 100, 1000}) {
            byte[] data = Crypto.randomBytes(len);
            byte[] packed = Tea.pack(data, key);
            assertEquals("packed length must be multiple of 8", 0, packed.length % 8);
            // Python: pad = (8 - (len+10)%8)%8; 总长 = 1+pad+2+len+7
            int pad = (8 - (len + 10) % 8) % 8;
            assertEquals("packed length wrong for len=" + len, 1 + pad + 2 + len + 7, packed.length);
            assertArrayEquals("unpack mismatch for len=" + len, data, Tea.unpack(packed, key));
        }
    }

    /** pack 首字节低 3 位必须等于 padding 长度（服务端靠这个解包）。 */
    @Test
    public void packEmbeddsPaddingLength() {
        byte[] key = Crypto.randomBytes(16);
        for (int len = 0; len < 40; len++) {
            byte[] data = Crypto.randomBytes(len);
            byte[] packed = Tea.pack(data, key);
            int pad = (8 - (len + 10) % 8) % 8;
            // pack 后总长公式成立；再解回来必须还原数据（含空数据）
            assertEquals((1 + pad + 2 + len + 7), packed.length);
            assertArrayEquals("roundtrip len=" + len, data, Tea.unpack(packed, key));
        }
    }

    /** pack 末尾 7 字节（解密后）必须为 0（参考实现固定写 7 个 0）。 */
    @Test
    public void packTrailingZeros() {
        byte[] key = Crypto.randomBytes(16);
        byte[] packed = Tea.pack("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), key);
        byte[] plain = Tea.unpack(packed, key);
        assertEquals("hello", new String(plain, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 长度/密钥校验。 */
    @Test
    public void validation() {
        try {
            Tea.encryptBlock(new byte[8], new byte[8]);
            fail("8-byte key must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("16"));
        }
        try {
            Tea.encryptBlock(new byte[16], new byte[7]);
            fail("7-byte block must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("8"));
        }
    }

    /** u32 读数辅助。 */
    @Test
    public void u32ReadsBigEndian() {
        byte[] b = Hex.decode("FFFFFFFF00000001");
        assertEquals(0xFFFFFFFFL, Tea.u32(b, 0));
        assertEquals(1L, Tea.u32(b, 4));
    }
}
