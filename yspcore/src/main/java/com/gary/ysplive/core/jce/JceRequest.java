package com.gary.ysplive.core.jce;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * JCE 请求封装：{@code _qua} / {@code _head} / {@code _wrap} / {@code _unwrap}，以及时移取流请求。
 * <p>全部字节布局与 Python 参考实现的 {@code _wrap} 完全一致（含 gzip 压缩级别与 mtime=0 的写法），
 * 因此可以用金标向量做逐字节断言。
 *
 * <h3>包络结构（共 89 字节头）</h3>
 * <pre>
 *  0      1        0x13
 *  1..4   4        total length (大端, 回填)
 *  5..6   2        0x0002
 *  7..8   2        0xFF01
 *  9..10  2        cmd
 *  11..12 2        0
 *  13..20 8        reqid
 *  21..24 4        标志位 (531 = 0x213, bit1 表示 body 已 gzip)
 *  25..28 4        QMF_APP_ID
 *  29..36 8        BIZ_ID
 *  37..68 32       GUID (ASCII, 不足补 0)
 *  69     1        QMF_PLATFORM
 *  70..73 4        VER_CODE
 *  74..79 6        0
 *  80     1        0
 *  81..82 2        0
 *  83..84 2        0
 *  85..88 4        inner 未压缩长度
 *  89..    n        gzip(inner)
 *  -1     1        0x03
 * </pre>
 */
public final class JceRequest {

    /** 时移取流命令号。 */
    public static final int CMD_TIMESHIFT = 25312;

    public static final String VER_NAME = "3.2.7.26212";
    public static final String VER_CODE = "302070";
    public static final String APP_ID = "1200013";
    public static final int QMF_APP_ID = 10012;
    public static final int QMF_PLATFORM = 1;
    public static final int BIZ_ID = 0;
    public static final String CHAN_ID = "10070";

    /** 固定 531：标志位，bit1 置位表示负载已 gzip 压缩。 */
    private static final int FLAGS = 531;

    private static final String TIMESHIFT_ENDPOINT = "https://jacc.ysp.cctv.cn";
    private static final String DEAD_CDN_HOST = "liverecord.video.cloud.cctv.com";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 进程级 GUID：32 位小写 hex，与 Python 参考实现一样在启动时随机生成一次。 */
    private static final String GUID = randomHex32();

    private JceRequest() { }

    private static String randomHex32() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    /** 当前进程使用的 GUID（32 位小写 hex）。 */
    public static String guid() {
        return GUID;
    }

    /** 时移流地址已出现在已知死掉的 CDN 主机上，调用方应回退到 bk 通道。 */
    public static final class DeadHostException extends Exception {
        private static final long serialVersionUID = 1L;

        public DeadHostException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // _qua
    // ------------------------------------------------------------------

    /** 写 {@code _qua}（终端/能力信息）。 */
    public static void writeQua(JceWriter w) {
        writeQua(w, GUID);
    }

    /** 写 {@code _qua}，可指定 GUID（供金标测试注入固定值）。 */
    public static void writeQua(JceWriter w, String guid) {
        String g = guid == null ? "" : guid;
        w.writeString(VER_NAME, 0);
        w.writeString(VER_CODE, 1);
        w.writeInt(1080, 2);
        w.writeInt(2400, 3);
        w.writeInt(3, 4);
        w.writeString("12", 5);
        w.writeInt(1, 6);
        w.writeInt(1, 7);
        w.writeInt(420, 8);
        w.writeString(CHAN_ID, 9);
        for (int i = 10; i < 15; i++) {
            w.writeString("", i);
        }
        w.writeStruct(ww -> {
            ww.writeInt(0, 0);
            ww.writeByte(0, 1);
            ww.writeString("", 2);
        }, 15);
        w.writeString("", 16);
        w.writeString("", 17);
        w.writeString("", 18);
        w.writeStruct(ww -> {
            ww.writeInt(0, 0);
            ww.writeFloat(0f, 1);
            ww.writeFloat(0f, 2);
            ww.writeDouble(0d, 3);
        }, 19);
        w.writeString(g.length() > 16 ? g.substring(0, 16) : g, 20);
        w.writeString("Pixel 6", 21);
        w.writeInt(1, 22);
        for (int i = 23; i < 27; i++) {
            w.writeInt(0, i);
        }
        w.writeString("", 27);
        w.writeString("", 28);
        w.writeString(g, 29);
    }

    // ------------------------------------------------------------------
    // _head
    // ------------------------------------------------------------------

    /** 写 {@code _head}（请求头结构）。 */
    public static void writeHead(JceWriter w, int cmd, int reqid) {
        writeHead(w, cmd, reqid, GUID);
    }

    /** 写 {@code _head}，可指定 GUID（供金标测试注入固定值）。 */
    public static void writeHead(JceWriter w, int cmd, int reqid, String guid) {
        w.writeInt(reqid, 0);
        w.writeInt(cmd, 1);
        w.writeStruct(ww -> writeQua(ww, guid), 2);
        w.writeString(APP_ID, 3);
        w.writeString(guid == null ? "" : guid, 4);
        w.writeList(null, 5, null);
        w.writeStruct(ww -> { }, 6);
        w.writeList(null, 7, null);
        w.writeInt(0, 8);
        w.writeInt(0, 9);
        w.writeInt(0, 10);
    }

    // ------------------------------------------------------------------
    // _wrap
    // ------------------------------------------------------------------

    /** 打包一个请求包。 */
    public static byte[] wrap(int cmd, byte[] body, int reqid) {
        return wrap(cmd, body, reqid, GUID);
    }

    /** 打包一个请求包，可指定 GUID（供金标测试注入固定值）。 */
    public static byte[] wrap(int cmd, byte[] body, int reqid, String guid) {
        byte[] inner = buildInner(cmd, body, reqid, guid);
        byte[] comp = gzip(inner);

        ByteArrayOutputStream out = new ByteArrayOutputStream(128 + comp.length);
        out.write(19);
        writeInt(out, 0);              // 回填总长
        writeShort(out, 2);
        writeShort(out, 65281);
        writeShort(out, cmd);
        writeShort(out, 0);
        writeLong(out, reqid & 0xFFFFFFFFL);
        writeInt(out, FLAGS);
        writeInt(out, QMF_APP_ID);
        writeLong(out, BIZ_ID & 0xFFFFFFFFL);
        byte[] g = (guid == null ? "" : guid).getBytes(StandardCharsets.US_ASCII);
        int glen = Math.min(32, g.length);
        out.write(g, 0, glen);
        for (int i = glen; i < 32; i++) {
            out.write(0);
        }
        out.write(QMF_PLATFORM & 0xFF);
        writeInt(out, Integer.parseInt(VER_CODE));
        for (int i = 0; i < 6; i++) {
            out.write(0);
        }
        out.write(0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, inner.length);
        out.write(comp, 0, comp.length);
        out.write(3);

        byte[] packet = out.toByteArray();
        putInt(packet, 1, packet.length);
        return packet;
    }

    /** 只构造 {@code _wrap} 里的 {@code reqcmd}（结构体 0 + bytes 1）。 */
    public static byte[] buildReqCmd(int cmd, byte[] body, int reqid, String guid) {
        JceWriter w = new JceWriter();
        w.writeStruct(ww -> writeHead(ww, cmd, reqid, guid), 0);
        w.writeBytes(body == null ? new byte[0] : body, 1);
        return w.out();
    }

    /**
     * 构造 gzip 之前的 {@code inner} 明文：
     * {@code 0x26 | len(reqcmd)+17 (4B) | 0x01 | 10×0x00 | reqcmd | 0x28}。
     */
    public static byte[] buildInner(int cmd, byte[] body, int reqid, String guid) {
        byte[] reqcmd = buildReqCmd(cmd, body, reqid, guid);
        byte[] inner = new byte[1 + 4 + 1 + 10 + reqcmd.length + 1];
        int p = 0;
        inner[p++] = 38;
        p = putInt(inner, p, reqcmd.length + 17);
        inner[p++] = 1;
        p += 10;
        System.arraycopy(reqcmd, 0, inner, p, reqcmd.length);
        p += reqcmd.length;
        inner[p] = 40;
        return inner;
    }

    // ------------------------------------------------------------------
    // _unwrap
    // ------------------------------------------------------------------

    /**
     * 解开服务端返回的包络，取出 JCE 响应负载。
     *
     * @return 响应负载字节；不是合法包络时返回 {@code null}（与 Python 一致）
     */
    public static byte[] unwrap(byte[] data) {
        if (data == null || data.length < 90 || (data[0] & 0xFF) != 0x13) {
            return null;
        }
        int flags = getInt(data, 21);
        byte[] payload = new byte[data.length - 89 - 1];
        System.arraycopy(data, 89, payload, 0, payload.length);
        if ((flags & 2) != 0) {
            try {
                payload = gunzip(payload);
            } catch (DataFormatException e) {
                return null;
            }
        }
        if (payload.length < 17 || payload[0] != '&' || payload[payload.length - 1] != '(') {
            return null;
        }
        byte[] rcBytes = new byte[payload.length - 16 - 1];
        System.arraycopy(payload, 16, rcBytes, 0, rcBytes.length);
        Map<Integer, Object> rc;
        try {
            rc = new JceReader(rcBytes).struct();
        } catch (JceFormatException e) {
            return null;
        }
        Object body = rc.get(1);
        return body instanceof byte[] ? (byte[]) body : new byte[0];
    }

    // ------------------------------------------------------------------
    // 时移
    // ------------------------------------------------------------------

    /** 构造时移请求体（{@code pid, sid, start, end, stream}，tag 0..4）。 */
    public static byte[] buildTimeshiftBody(String pid, String sid, long startEpochSec,
                                            long endEpochSec, String stream) {
        JceWriter w = new JceWriter();
        w.writeString(pid, 0);
        w.writeString(sid, 1);
        w.writeLong(startEpochSec, 2);
        w.writeLong(endEpochSec, 3);
        w.writeString(stream == null || stream.isEmpty() ? "fhd" : stream, 4);
        return w.out();
    }

    /** 构造时移请求的完整包络。 */
    public static byte[] buildTimeshiftPacket(String pid, String sid, long startEpochSec,
                                              long endEpochSec, String stream, int reqid) {
        return wrap(CMD_TIMESHIFT, buildTimeshiftBody(pid, sid, startEpochSec, endEpochSec, stream), reqid);
    }

    /** 解析时移响应负载，返回 m3u8 地址。 */
    public static String parseTimeshiftResponse(byte[] respBody) throws DeadHostException {
        if (respBody == null || respBody.length == 0) {
            throw new IllegalArgumentException("bad response");
        }
        Map<Integer, Object> m = new JceReader(respBody).struct();
        int err = JceReader.optInt(m, 0, 0);
        if (err != 0) {
            Object msg = m.get(1);
            throw new IllegalArgumentException(msg instanceof String ? (String) msg : "errCode=" + err);
        }
        Object urlVal = m.get(2);
        String url = urlVal instanceof String ? (String) urlVal : "";
        if (url.isEmpty()) {
            throw new IllegalArgumentException("empty m3u8");
        }
        if (url.contains(DEAD_CDN_HOST)) {
            throw new DeadHostException("dead cdn host");
        }
        return url;
    }

    /**
     * 走央视频设备协议拿时移 m3u8 地址（对应 Python 的 {@code jce_timeshift_url}）。
     *
     * @param pid   央视频 pid（{@code ChannelSpec.pid}）
     * @param sid   央视频 sid（{@code ChannelSpec.sid}）
     * @param startEpochSec 起始时间（秒）
     * @param endEpochSec   结束时间（秒）
     * @param defn  清晰度，{@code fhd}/{@code shd}
     * @return 绝对 m3u8 地址
     * @throws DeadHostException 返回的地址落在已知失效的 CDN 上，调用方应回退 bk 通道
     * @throws Exception 其它任何失败
     */
    public static String jceTimeshiftUrl(String pid, String sid, long startEpochSec,
                                         long endEpochSec, String defn) throws Exception {
        int reqid = (int) ((System.currentTimeMillis()) & 0x7FFFFFFFL);
        byte[] packet = buildTimeshiftPacket(pid, sid, startEpochSec, endEpochSec, defn, reqid);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/octet-stream");
        byte[] raw;
        com.gary.ysplive.core.net.Http http = null;
        try {
            http = new com.gary.ysplive.core.net.Http(15000);
            com.gary.ysplive.core.net.HttpResponse resp =
                    http.postBytes(TIMESHIFT_ENDPOINT, headers, packet);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IllegalStateException("JCE 时移接口 HTTP " + resp.status + " " + resp.reason);
            }
            raw = resp.body;
        } finally {
            if (http != null) {
                try {
                    http.close();
                } catch (RuntimeException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
        byte[] respBody = unwrap(raw);
        if (respBody == null) {
            throw new IllegalStateException("JCE 响应包络无法解析");
        }
        return parseTimeshiftResponse(respBody);
    }

    // ------------------------------------------------------------------
    // gzip / 字节工具
    // ------------------------------------------------------------------

    /**
     * 供测试使用：暴露 gzip 实现，验证与 Python {@code gzip.compress} 字节一致。
     * <p>生产代码请用 {@link #wrap}。
     */
    public static byte[] gzipForTest(byte[] data) {
        return gzip(data);
    }

    /**
     * 供测试使用：暴露 gunzip 实现。
     * <p>生产代码请用 {@link #unwrap}。
     */
    public static byte[] gunzipForTest(byte[] data) {
        try {
            return gunzip(data);
        } catch (DataFormatException e) {
            return null;
        }
    }

    /**
     * gzip 压缩，输出与 Python {@code gzip.compress(data)}（默认 level 9、mtime=0）逐字节一致：
     * 头 {@code 1f 8b 08 00 00 00 00 00 02 ff}、raw deflate、CRC32 LE、ISIZE LE。
     */
    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 32);
        out.write(0x1F);
        out.write(0x8B);
        out.write(0x08);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x00);
        out.write(0x02);
        out.write(0xFF);
        Deflater deflater = new Deflater(9, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                if (n > 0) {
                    out.write(chunk, 0, n);
                } else if (deflater.needsInput()) {
                    break;
                }
            }
        } finally {
            deflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(data);
        long c = crc.getValue();
        out.write((int) (c & 0xFF));
        out.write((int) ((c >>> 8) & 0xFF));
        out.write((int) ((c >>> 16) & 0xFF));
        out.write((int) ((c >>> 24) & 0xFF));
        int n = data.length;
        out.write(n & 0xFF);
        out.write((n >>> 8) & 0xFF);
        out.write((n >>> 16) & 0xFF);
        out.write((n >>> 24) & 0xFF);
        return out.toByteArray();
    }

    /** gzip 解压（raw inflate + 校验头尾）。 */
    static byte[] gunzip(byte[] data) throws DataFormatException {
        if (data == null || data.length < 18) {
            throw new DataFormatException("gzip 数据过短");
        }
        int off = 10;
        if ((data[3] & 0x08) != 0) {           // FNAME
            while (off < data.length && data[off] != 0) {
                off++;
            }
            off++;
        }
        int len = data.length - off - 8;
        if (len < 0) {
            throw new DataFormatException("gzip 负载长度非法");
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, len * 3));
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n > 0) {
                    out.write(chunk, 0, n);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break;
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        writeInt(out, (int) (v >>> 32));
        writeInt(out, (int) v);
    }

    private static int putInt(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
        return off + 4;
    }

    private static int getInt(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
    }
}
