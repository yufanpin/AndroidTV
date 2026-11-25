package com.gary.ysplive.core.util;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * RSA-OAEP（SHA-256 / MGF1-SHA256 / 空 label），与 Python 参考实现
 * {@code rsa_oaep_sha256_encrypt}（line 228-234）+ {@code rsa_encrypt_device_id}（line 585-599）
 * 等价。
 *
 * <h2>⚠️ 必须显式指定 OAEPParameterSpec（本项目最容易踩的坑）</h2>
 * {@code Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")} 这个名字里的 "SHA-256"
 * 只约束**主摘要**；MGF1 的摘要**默认值各平台不同**：
 * <ul>
 *   <li>桌面 JVM：MGF1 默认用 SHA-1；</li>
 *   <li>部分 Android 版本：MGF1 默认用 SHA-1 或 SHA-256，因厂商/版本而异。</li>
 * </ul>
 * 而参考实现（{@code _mgf1_sha256}）明确用 **SHA-256** 做 MGF1。若不显式指定，
 * 两平台产出的密文不同，服务端只接受其中一种 → 表现为"某类设备注册失败"。
 * 因此这里**总是**传入
 * {@code OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)}。
 * <p>反过来的风险也处理了：如果某些老 Android 的 provider 直接拒绝这个 spec，
 * 会回退到纯 Java 手写实现（{@link #encryptFallback}），保证功能不缺失。
 *
 * <h2>分块</h2>
 * 参考实现把消息按 {@code k - 2*hLen - 2} 字节切块、逐块 OAEP 加密后拼接
 * （RSA-2048 + SHA-256 → 每块 190 字节）。这里照抄，{@link #encrypt} 自己分块。
 */
public final class RsaOaep {

    private static final int HASH_LEN = 32; // SHA-256 输出长度

    private RsaOaep() {
    }

    /**
     * 用 SPKI（X.509 SubjectPublicKeyInfo）DER 公钥加密任意长度消息，
     * 自动按 {@code k - 2*32 - 2} 分块；返回各块密文直接拼接。
     *
     * @param spkiDer 公钥 DER（就是 {@code RSA_PUBLIC_KEY_B64} base64 解出来的字节）
     * @param message 明文
     */
    public static byte[] encrypt(byte[] spkiDer, byte[] message) {
        if (spkiDer == null || spkiDer.length == 0) {
            throw new IllegalArgumentException("empty RSA public key DER");
        }
        byte[] data = message == null ? new byte[0] : message;

        PublicKey key;
        int k;
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(spkiDer);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            key = kf.generatePublic(spec);
            k = (key instanceof java.security.interfaces.RSAPublicKey)
                    ? (((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() + 7) / 8
                    : (spkiDer.length * 8 / 9);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid RSA public key DER", e);
        }

        int chunkSize = k - 2 * HASH_LEN - 2;
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("RSA key too small for OAEP-SHA256: k=" + k);
        }
        // 对齐 Python: `for i in range(0, len(data), chunk_size)` —— 空消息时循环不执行，
        // 结果是空字节数组（而不是加密一个空块），base64 后是空串。
        if (data.length == 0) {
            return new byte[0];
        }
        List<byte[]> chunks = new ArrayList<>();
        for (int off = 0; off < data.length; off += chunkSize) {
            int n = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            chunks.add(chunk);
        }
        byte[] out = new byte[chunks.size() * k];
        int pos = 0;
        for (byte[] chunk : chunks) {
            byte[] enc = encryptChunk(key, k, chunk);
            System.arraycopy(enc, 0, out, pos, enc.length);
            pos += enc.length;
        }
        return out;
    }

    /** 用 base64 的 SPKI 公钥加密，返回标准 base64（等价 Python {@code rsa_encrypt_device_id}）。 */
    public static String encryptToBase64(String spkiBase64, String message) {
        byte[] der = Base64X.decode(spkiBase64);
        return Base64X.encode(encrypt(der, message == null
                ? new byte[0] : message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** 单块 OAEP 加密；优先走 JCE + 显式 OAEPParameterSpec，失败回退手写实现。 */
    private static byte[] encryptChunk(PublicKey key, int k, byte[] chunk) {
        try {
            return jceEncrypt(key, chunk);
        } catch (Throwable ignore) {
            // 某些老 Android provider 不认 OAEPParameterSpec(MGF1-SHA256) 或 GCM/OAEP 组合；
            // 回退到与参考实现逐字节一致的手写实现（见下方 encryptFallback）。
        }
        byte[] der = key.getEncoded();
        return encryptFallback(der, chunk);
    }

    private static byte[] jceEncrypt(PublicKey key, byte[] chunk) throws Exception {
        OAEPParameterSpec spec = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.ENCRYPT_MODE, key, spec);
        return c.doFinal(chunk);
    }

    /**
     * 手写 OAEP-SHA256 编码 + 裸 RSA（与 Python {@code _oaep_encode_sha256} 逐字节一致）。
     * 只在 JCE 不可用时兜底，保证功能完整。
     * <p>注意 seed 用随机值（Python 也是 {@code secrets.token_bytes(32)}），所以密文每次不同，
     * 无法逐字节比对 —— 只能验证"能解开"。测试里用确定性 seed 的重载做逐字节比对。
     */
    static byte[] encryptFallback(byte[] spkiDer, byte[] message) {
        return encryptFallback(spkiDer, message, Crypto.randomBytes(HASH_LEN));
    }

    /** 确定性 seed 版本，供测试与逐字节比对使用。 */
    static byte[] encryptFallback(byte[] spkiDer, byte[] message, byte[] seed) {
        BigInteger[] ne = parseSpki(spkiDer);
        BigInteger n = ne[0];
        BigInteger e = ne[1];
        int k = (n.bitLength() + 7) / 8;
        byte[] em = oaepEncode(message, k, seed);
        BigInteger m = new BigInteger(1, em);
        BigInteger c = m.modPow(e, n);
        return toFixedLength(c, k);
    }

    /** 解析 SPKI DER，返回 {@code [n, e]}（对齐 Python {@code parse_spki_rsa_pubkey}）。 */
    static BigInteger[] parseSpki(byte[] der) {
        List<byte[]> outer = readTlv(der, 0);
        // outer[0] = AlgorithmIdentifier, outer[1] = BIT STRING
        byte[] bitString = outer.get(1);
        if (bitString.length == 0 || bitString[0] != 0) {
            throw new IllegalArgumentException("invalid SPKI: BIT STRING must have 0 unused bits");
        }
        byte[] innerDer = new byte[bitString.length - 1];
        System.arraycopy(bitString, 1, innerDer, 0, innerDer.length);
        List<byte[]> inner = readTlv(innerDer, 0);
        BigInteger n = new BigInteger(1, inner.get(0));
        BigInteger e = new BigInteger(1, inner.get(1));
        return new BigInteger[]{n, e};
    }

    /** 读一个 SEQUENCE，返回其内部各 TLV 的**内容**字节列表。 */
    private static List<byte[]> readTlv(byte[] der, int pos) {
        if (der[pos] != 0x30) {
            throw new IllegalArgumentException("expected SEQUENCE at " + pos);
        }
        pos++;
        int[] lenPair = readLen(der, pos);
        int len = lenPair[0];
        pos = lenPair[1];
        int end = pos + len;
        List<byte[]> items = new ArrayList<>();
        while (pos < end) {
            pos++; // tag，参考实现不区分具体 tag
            int[] lp = readLen(der, pos);
            int ln = lp[0];
            pos = lp[1];
            byte[] content = new byte[ln];
            System.arraycopy(der, pos, content, 0, ln);
            items.add(content);
            pos += ln;
        }
        return items;
    }

    /** 返回 {@code [length, newPos]}。 */
    private static int[] readLen(byte[] der, int pos) {
        int first = der[pos] & 0xFF;
        pos++;
        if ((first & 0x80) == 0) {
            return new int[]{first, pos};
        }
        int n = first & 0x7F;
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (der[pos + i] & 0xFF);
        }
        return new int[]{len, pos + n};
    }

    /** OAEP 编码（EM = 0x00 || maskedSeed || maskedDB），逐字节对齐 Python。 */
    static byte[] oaepEncode(byte[] message, int k, byte[] seed) {
        if (message.length > k - 2 * HASH_LEN - 2) {
            throw new IllegalArgumentException("OAEP message too long");
        }
        byte[] lhash = Crypto.sha256(new byte[0]);
        int dbLen = k - HASH_LEN - 1;
        byte[] db = new byte[dbLen];
        System.arraycopy(lhash, 0, db, 0, HASH_LEN);
        int psLen = k - message.length - 2 * HASH_LEN - 2;
        db[HASH_LEN + psLen] = 0x01;
        System.arraycopy(message, 0, db, HASH_LEN + psLen + 1, message.length);

        byte[] dbMask = mgf1Sha256(seed, dbLen);
        for (int i = 0; i < dbLen; i++) {
            db[i] ^= dbMask[i];
        }
        byte[] seedMask = mgf1Sha256(db, HASH_LEN);
        byte[] maskedSeed = new byte[HASH_LEN];
        for (int i = 0; i < HASH_LEN; i++) {
            maskedSeed[i] = (byte) (seed[i] ^ seedMask[i]);
        }
        byte[] em = new byte[k];
        em[0] = 0x00;
        System.arraycopy(maskedSeed, 0, em, 1, HASH_LEN);
        System.arraycopy(db, 0, em, 1 + HASH_LEN, dbLen);
        return em;
    }

    private static byte[] mgf1Sha256(byte[] seed, int length) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(length);
        int counter = 0;
        while (out.size() < length) {
            byte[] ctr = new byte[]{
                    (byte) (counter >>> 24), (byte) (counter >>> 16),
                    (byte) (counter >>> 8), (byte) counter};
            byte[] input = new byte[seed.length + 4];
            System.arraycopy(seed, 0, input, 0, seed.length);
            System.arraycopy(ctr, 0, input, seed.length, 4);
            byte[] d = Crypto.sha256(input);
            out.write(d, 0, d.length);
            counter++;
        }
        byte[] raw = out.toByteArray();
        byte[] r = new byte[length];
        System.arraycopy(raw, 0, r, 0, length);
        return r;
    }

    private static byte[] toFixedLength(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        if (raw.length > len) {
            // 去掉 BigInteger 可能多的前导 0
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }

    /** 供测试：用确定性 seed 做与 Python 逐字节可比的加密（单块）。 */
    public static byte[] encryptDeterministic(byte[] spkiDer, byte[] message, byte[] seed) {
        return encryptFallback(spkiDer, message, seed);
    }
}
