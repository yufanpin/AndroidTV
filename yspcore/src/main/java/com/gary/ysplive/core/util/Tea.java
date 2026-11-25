package com.gary.ysplive.core.util;

/**
 * 腾讯 TEA（16 轮，delta=0x9E3779B9），与 Python 参考实现 {@code _tea_blk} / {@code _tea_pkt} /
 * {@code _cksum}（line 2220-2244）逐字节等价。
 *
 * <p>注意：这里的"TEA"是**参考实现自己的实现**，与教科书 TEA 一致（大端 uint32、16 轮、固定 delta），
 * 但{@code pack()} 的 CBC 似链接与随机填充是本项目特有的，**必须照抄**才能和 {@code core.bk}
 * 的上游服务端对上。
 */
public final class Tea {

    /** 16 轮固定增量：2654435769 == 0x9E3779B9。 */
    private static final int DELTA = 0x9E3779B9;

    private Tea() {
    }

    /**
     * 等价 Python {@code _tea_blk(blk, key)}：对单个 8 字节块做 16 轮 TEA 加密。
     *
     * @param key16 16 字节密钥（按大端拆成 4 个 uint32）
     * @param block8 8 字节明文块（按大端拆成 y、z）
     * @return 8 字节密文块
     */
    public static byte[] encryptBlock(byte[] key16, byte[] block8) {
        if (key16 == null || key16.length != 16) {
            throw new IllegalArgumentException("TEA key must be 16 bytes, got "
                    + (key16 == null ? "null" : String.valueOf(key16.length)));
        }
        if (block8 == null || block8.length != 8) {
            throw new IllegalArgumentException("TEA block must be 8 bytes, got "
                    + (block8 == null ? "null" : String.valueOf(block8.length)));
        }
        int k0 = readU32(key16, 0);
        int k1 = readU32(key16, 4);
        int k2 = readU32(key16, 8);
        int k3 = readU32(key16, 12);
        int y = readU32(block8, 0);
        int z = readU32(block8, 4);
        int s = 0;
        for (int i = 0; i < 16; i++) {
            s = s + DELTA; // Java int 溢出即 uint32 回绕，与 Python & 0xFFFFFFFF 等价
            y = y + (((z << 4) + k0) ^ (z + s) ^ ((z >>> 5) + k1));
            z = z + (((y << 4) + k2) ^ (y + s) ^ ((y >>> 5) + k3));
        }
        byte[] out = new byte[8];
        writeU32(out, 0, y);
        writeU32(out, 4, z);
        return out;
    }

    /**
     * 等价 Python {@code _cksum(buf)}：**不是**标准 CRC32，而是
     * {@code v = 131*v + b & 0x7FFFFFFF} 的滚动校验（结果恒为非负 int）。
     *
     * @return 0 .. 0x7FFFFFFF 的值
     */
    public static int cksum(byte[] buf) {
        int v = 0;
        if (buf != null) {
            for (byte b : buf) {
                // Python: v = 131*v + b & 0x7FFFFFFF（131*v+b 先用无限精度算，再取低 31 位）
                v = (131 * v + (b & 0xFF)) & 0x7FFFFFFF;
            }
        }
        return v;
    }

    /** {@link #cksum(byte[])} 的别名，契约里叫 {@code crc32Like}。 */
    public static int crc32Like(byte[] buf) {
        return cksum(buf);
    }

    /**
     * 等价 Python {@code _tea_pkt(data, key)}：TEA 加密包。
     *
     * <p>格式（照抄参考实现，注意与标准 TEA CBC 不同）：
     * <pre>
     *   pad   = (8 - (len(data)+10) % 8) % 8
     *   plain = [random&amp;0xF8 | pad] || random(pad) || random(2) || data || 0x00*7
     *   pp = pc = 0^8
     *   for each 8-byte block b of plain:
     *       mixed  = b XOR pc
     *       enc    = TEA(mixed)
     *       cipher = enc XOR pp
     *       pp, pc = mixed, cipher
     * </pre>
     *
     * <p><b>随机性</b>：Python 用 {@code os.urandom}，这里用 {@link Crypto#randomBytes}。
     * 随机选择不影响"能否被服务端解出"，所以两边各自加密的密文天然不同 —— 跨语言对比时请比
     * **解密往返**而不是比密文（参见 {@code TeaTest}）。
     */
    public static byte[] pack(byte[] data, byte[] key16) {
        if (key16 == null || key16.length != 16) {
            throw new IllegalArgumentException("TEA key must be 16 bytes");
        }
        byte[] body = data == null ? new byte[0] : data;
        int pad = (8 - (body.length + 10) % 8) % 8;
        int plainLen = 1 + pad + 2 + body.length + 7;
        byte[] plain = new byte[plainLen];
        byte[] rnd = Crypto.randomBytes(1 + pad + 2);
        int p = 0;
        // 首字节：高 5 位随机、低 3 位存 padding 长度（Python: os.urandom(1)[0] & 248 | pad）
        plain[p++] = (byte) ((rnd[0] & 0xF8) | pad);
        for (int i = 0; i < pad; i++) {
            plain[p++] = rnd[1 + i];
        }
        plain[p++] = rnd[1 + pad];
        plain[p++] = rnd[1 + pad + 1];
        System.arraycopy(body, 0, plain, p, body.length);
        p += body.length;
        // 末尾 7 个 0x00 由 new byte[] 的零初始化保证
        if (p + 7 != plainLen) {
            throw new IllegalStateException("internal padding error");
        }

        byte[] out = new byte[plainLen];
        byte[] pp = new byte[8];
        byte[] pc = new byte[8];
        byte[] mixed = new byte[8];
        for (int off = 0; off < plainLen; off += 8) {
            for (int i = 0; i < 8; i++) {
                mixed[i] = (byte) (plain[off + i] ^ pc[i]);
            }
            byte[] enc = encryptBlock(key16, mixed);
            for (int i = 0; i < 8; i++) {
                out[off + i] = (byte) (enc[i] ^ pp[i]);
            }
            System.arraycopy(mixed, 0, pp, 0, 8);
            System.arraycopy(out, off, pc, 0, 8);
        }
        return out;
    }

    /**
     * {@link #pack} 的逆运算，**仅用于测试/自检**（参考实现里没有解密方向，服务端才有）。
     * 若填充不合法抛 {@link IllegalStateException}。
     */
    public static byte[] unpack(byte[] packed, byte[] key16) {
        if (packed == null || packed.length == 0 || packed.length % 8 != 0) {
            throw new IllegalArgumentException("TEA packet length must be a positive multiple of 8");
        }
        if (key16 == null || key16.length != 16) {
            throw new IllegalArgumentException("TEA key must be 16 bytes");
        }
        byte[] plain = new byte[packed.length];
        byte[] pp = new byte[8];
        byte[] pc = new byte[8];
        for (int off = 0; off < packed.length; off += 8) {
            byte[] cipher = new byte[8];
            System.arraycopy(packed, off, cipher, 0, 8);
            byte[] enc = new byte[8];
            for (int i = 0; i < 8; i++) {
                enc[i] = (byte) (cipher[i] ^ pp[i]);
            }
            byte[] mixed = decryptBlock(key16, enc);
            for (int i = 0; i < 8; i++) {
                plain[off + i] = (byte) (mixed[i] ^ pc[i]);
            }
            // 链式状态：pp = 本块 mixed（明文 XOR 上一块密文），pc = 本块密文
            System.arraycopy(mixed, 0, pp, 0, 8);
            System.arraycopy(cipher, 0, pc, 0, 8);
        }
        int pad = plain[0] & 0x07;
        int dataLen = plain.length - 10 - pad;
        if (dataLen < 0) {
            throw new IllegalStateException("TEA packet padding is invalid");
        }
        byte[] out = new byte[dataLen];
        System.arraycopy(plain, 1 + pad + 2, out, 0, dataLen);
        return out;
    }

    /** 单块 TEA 解密（16 轮逆序），仅供 {@link #unpack} 与自检使用。 */
    public static byte[] decryptBlock(byte[] key16, byte[] block8) {
        if (key16 == null || key16.length != 16) {
            throw new IllegalArgumentException("TEA key must be 16 bytes");
        }
        if (block8 == null || block8.length != 8) {
            throw new IllegalArgumentException("TEA block must be 8 bytes");
        }
        int k0 = readU32(key16, 0);
        int k1 = readU32(key16, 4);
        int k2 = readU32(key16, 8);
        int k3 = readU32(key16, 12);
        int y = readU32(block8, 0);
        int z = readU32(block8, 4);
        int s = DELTA * 16; // 16 轮累加后的 s
        for (int i = 0; i < 16; i++) {
            z = z - (((y << 4) + k2) ^ (y + s) ^ ((y >>> 5) + k3));
            y = y - (((z << 4) + k0) ^ (z + s) ^ ((z >>> 5) + k1));
            s = s - DELTA;
        }
        byte[] out = new byte[8];
        writeU32(out, 0, y);
        writeU32(out, 4, z);
        return out;
    }

    // ---------------------------------------------------------------- 大端 uint32 读写

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static void writeU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** 大端 uint32 转 BigInteger 风格的无符号 long，供调试打印用。 */
    public static long u32(byte[] b, int off) {
        return readU32(b, off) & 0xFFFFFFFFL;
    }
}
