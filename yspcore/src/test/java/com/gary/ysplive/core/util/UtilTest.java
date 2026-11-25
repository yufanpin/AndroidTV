package com.gary.ysplive.core.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/** {@link Hex}、{@link Base64X}、{@link Crypto} 的单元测试（含与 Python 行为的对照点）。 */
public class UtilTest {

    // ------------------------------------------------------------ Hex

    @Test
    public void hexEncodeDecode() {
        assertEquals("", Hex.encode(new byte[0]));
        assertEquals("00ff7f", Hex.encode(new byte[]{0x00, (byte) 0xFF, 0x7F}));
        assertEquals("00FF7F", Hex.encodeUpper(new byte[]{0x00, (byte) 0xFF, 0x7F}));
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF, 0x7F}, Hex.decode("00ff7f"));
        // 容忍大写
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF, 0x7F}, Hex.decode("00FF7F"));
        // 容忍空白与换行
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, Hex.decode("01 02\n03"));
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, Hex.decode("\t010203\r\n"));
        assertArrayEquals(new byte[0], Hex.decode(""));
        assertArrayEquals(new byte[0], Hex.decode(null));
        assertEquals("", Hex.encode(null));
    }

    @Test
    public void hexRejectsInvalid() {
        try {
            Hex.decode("0g");
            fail("invalid hex char must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid hex"));
        }
        try {
            Hex.decode("abc");
            fail("odd length must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("odd"));
        }
    }

    /** 与 Python bytes.hex() 一致：小写、无分隔。 */
    @Test
    public void hexMatchesPythonBytesHex() {
        byte[] b = "央视频".getBytes(StandardCharsets.UTF_8);
        assertEquals("e5a4aee8a786e9a291", Hex.encode(b));
        assertArrayEquals(b, Hex.decode("e5a4aee8a786e9a291"));
    }

    // ------------------------------------------------------------ Base64X

    /** RFC 4648 标准向量。 */
    @Test
    public void base64Rfc4648() {
        assertEquals("", Base64X.encode(new byte[0]));
        assertEquals("Zg==", Base64X.encode("f".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Zm8=", Base64X.encode("fo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Zm9v", Base64X.encode("foo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Zm9vYg==", Base64X.encode("foob".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Zm9vYmE=", Base64X.encode("fooba".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Zm9vYmFy", Base64X.encode("foobar".getBytes(StandardCharsets.UTF_8)));
        assertEquals("foobar", new String(Base64X.decode("Zm9vYmFy"), StandardCharsets.UTF_8));
        assertEquals("foobar", new String(Base64X.decode("Zm9vYmFy"), StandardCharsets.UTF_8));
    }

    /** 与 java.util.Base64 对拍所有长度（仅测试中用，生产代码不用）。 */
    @Test
    public void base64MatchesJdk() {
        for (int len = 0; len < 200; len++) {
            byte[] b = Crypto.randomBytes(len);
            String mine = Base64X.encode(b);
            String jdk = java.util.Base64.getEncoder().encodeToString(b);
            assertEquals("encode mismatch len=" + len, jdk, mine);
            assertArrayEquals("decode mismatch len=" + len, b, Base64X.decode(mine));
            // urlsafe no-pad 也要对上
            String mineU = Base64X.encodeUrlSafeNoPad(b);
            String jdkU = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            assertEquals("urlsafe encode mismatch len=" + len, jdkU, mineU);
            assertArrayEquals("urlsafe decode mismatch len=" + len, b, Base64X.decode(mineU));
        }
    }

    /** padding 可缺、urlsafe 与标准混用都能解。 */
    @Test
    public void base64TolerantDecode() {
        byte[] b = "foobar".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(b, Base64X.decode("Zm9vYmFy"));
        assertArrayEquals(b, Base64X.decode("Zm9vYmFy==")); // 多余 padding 也容忍
        // urlsafe 特有字符
        byte[] tricky = new byte[]{(byte) 0xFB, (byte) 0xFF, 0x00, (byte) 0x3E};
        String std = Base64X.encode(tricky);
        String url = Base64X.encodeUrlSafeNoPad(tricky);
        assertArrayEquals(tricky, Base64X.decode(std));
        assertArrayEquals(tricky, Base64X.decode(url));
        assertTrue("urlsafe must not contain + or /", url.indexOf('+') < 0 && url.indexOf('/') < 0);
        assertTrue("urlsafe must not contain =", url.indexOf('=') < 0);
    }

    @Test
    public void base64RejectsInvalid() {
        try {
            Base64X.decode("!!!!");
            fail("invalid base64 must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid base64"));
        }
        try {
            Base64X.decode("Z");
            fail("truncated group must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("length"));
        }
    }

    /** 与 Python base64 对拍（标准 + urlsafe rstrip）。 */
    @Test
    public void base64MatchesPython() {
        // python: base64.b64encode(b'hello world') == b'aGVsbG8gd29ybGQ='
        assertEquals("aGVsbG8gd29ybGQ=", Base64X.encode("hello world".getBytes(StandardCharsets.UTF_8)));
        // python: base64.urlsafe_b64encode(b'\xfb\xff\x00>').rstrip(b'=') == b'-_8APg'
        byte[] tricky = new byte[]{(byte) 0xFB, (byte) 0xFF, 0x00, 0x3E};
        assertEquals("-_8APg", Base64X.encodeUrlSafeNoPad(tricky));
    }

    // ------------------------------------------------------------ Crypto

    @Test
    public void digestsMatchPythonHexdigest() {
        // python: hashlib.sha256(b'').hexdigest()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Crypto.sha256Hex(""));
        // python: hashlib.sha1(b'').hexdigest().upper()
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", Crypto.sha1Upper(""));
        // python: hashlib.md5(b'').hexdigest()
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Crypto.md5Hex(""));
        // python: hashlib.sha1(b'abc').hexdigest().upper()
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", Crypto.sha1Upper("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Crypto.sha256Hex("abc"));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Crypto.md5Hex("abc"));
    }

    /** UTF-8 编码：非 ASCII 按 UTF-8 而不是平台默认字符集。 */
    @Test
    public void digestsUseUtf8() {
        byte[] utf8 = "中文".getBytes(StandardCharsets.UTF_8);
        assertEquals(Hex.encode(Crypto.sha256(utf8)), Crypto.sha256Hex("中文"));
        assertEquals(Hex.encodeUpper(Crypto.sha1(utf8)), Crypto.sha1Upper("中文"));
        assertEquals(Hex.encode(Crypto.md5(utf8)), Crypto.md5Hex("中文"));
    }

    @Test
    public void digestByteApis() {
        assertEquals(20, Crypto.sha1(new byte[0]).length);
        assertEquals(32, Crypto.sha256(new byte[0]).length);
        assertEquals(16, Crypto.md5(new byte[0]).length);
    }

    /** randomHex(n) 返回 n **个字符**（不是 n 字节），全为小写 hex。 */
    @Test
    public void randomHexLengthAndCharset() {
        for (int len : new int[]{0, 1, 5, 16, 32, 64}) {
            String s = Crypto.randomHex(len);
            assertEquals("length wrong for len=" + len, len, s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                assertTrue("non-hex char: " + c, (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
            }
        }
        // 两次不同（概率上）
        assertFalse(Crypto.randomHex(32).equals(Crypto.randomHex(32)));
        assertEquals("", Crypto.randomHex(-1));
    }

    /** randomMac 格式 aa:bb:cc:dd:ee:ff，首字节本地管理 + 单播。 */
    @Test
    public void randomMacFormat() {
        for (int i = 0; i < 100; i++) {
            String mac = Crypto.randomMac();
            assertEquals("wrong length: " + mac, 17, mac.length());
            String[] parts = mac.split(":");
            assertEquals(6, parts.length);
            for (String p : parts) {
                assertEquals(2, p.length());
                assertTrue(p, p.matches("[0-9a-f]{2}"));
            }
            int first = Integer.parseInt(parts[0], 16);
            assertEquals("bit1 must be set (locally administered)", 1, (first >> 1) & 1);
            assertEquals("bit0 must be clear (unicast)", 0, first & 1);
        }
    }

    @Test
    public void randomIsSharedInstance() {
        assertTrue(Crypto.random() != null);
        assertTrue(Crypto.random() == Crypto.random());
        assertEquals(8, Crypto.randomBytes(8).length);
    }
}
