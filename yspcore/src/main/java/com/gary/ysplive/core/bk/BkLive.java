package com.gary.ysplive.core.bk;

import com.gary.ysplive.core.net.Http;
import com.gary.ysplive.core.net.HttpResponse;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.util.Base64X;
import com.gary.ysplive.core.util.Json;
import com.gary.ysplive.core.util.Tea;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * bkliveinfo 取流通道（1080p 备用通道）。
 * <p>逐行复刻 Python 参考实现的 {@code _ck_guard} / {@code _ckey} / {@code bk_playurls} /
 * {@code fetch_abs_playlist}。这是一条"腾讯 TEA 加密 body + 查询串"的老接口，主要给
 * 走不通设备协议的频道兜底，也用于时移刷新。
 *
 * <h3>协议要点</h3>
 * <ul>
 *   <li>{@code _tea_pkt}：自研 CBC 链（每块先用前一块密文 XOR 再 TEA 加密，再与前一块明文 XOR），
 *       padding 长度藏在首字节低 3 位</li>
 *   <li>{@code _cksum}：{@code v = (131*v + b) & 0x7FFFFFFF}</li>
 *   <li>cKey = {@code --01} + base64(XOR(TEA(pkt)))，pkt 前 2 字节是大端长度，偏移 18..22 处回填校验和</li>
 * </ul>
 * 全部方法是阻塞 IO，必须在后台线程调用。
 */
public final class BkLive {

    private static final String TAG = "bk";

    // ---- 常量（与 Python 一字不差） ----
    private static final long CK_PLATFORM = 4330403L;
    private static final String CK_APPVER = "V8.22.1035.3031";

    private static final byte[] CK_TEA = hex("59b2f7cf725ef43c34fdd7c123411ed3");
    private static final byte[] CK_GTEA = hex("110DBEC10C23E7D2E56A1CAD6914EF1B");
    private static final byte[] CK_XOR = {
        (byte) 132, 46, (byte) 237, 8, (byte) 240, 102, (byte) 230, (byte) 234,
        72, (byte) 180, (byte) 202, (byte) 169, (byte) 145, (byte) 237, 111, (byte) 243
    };
    private static final byte[] CK_GXOR = {
        (byte) 179, (byte) 201, 83, (byte) 160, 105, 19, (byte) 173, 77
    };

    /** {@code spvcode} 常量：base64("H(30:1080,60:1080|30:1080,60:1080)")。 */
    private static final String BK_H264 = Base64X.encode(
            "H(30:1080,60:1080|30:1080,60:1080)".getBytes(StandardCharsets.UTF_8));

    private static final String BK_INFO_URL = "https://bkliveinfo.ysp.cctv.cn/?";
    private static final String BK_UA = "qqlive";
    private static final String BK_REFERER = "https://live.cctv.cn/";

    private static final SecureRandom RANDOM = new SecureRandom();

    private BkLive() { }

    // ==================================================================
    // 公开 API
    // ==================================================================

    /**
     * 取 bkliveinfo 播放地址列表（对应 Python {@code bk_playurls}）。
     *
     * @param sid  央视频 sid（Python 里的 {@code channel_id} / {@code cnlid}）
     * @param pid  央视频 pid（Python 里的 {@code live_pid} / {@code livepid}）
     * @param defn 清晰度，{@code fhd}/{@code shd}
     * @return 去重并按 {@code bklive-} 优先排序的 URL 列表，绝不为 null
     * @throws Exception iretcode 非 0、无 playurl、网络失败
     */
    public static List<String> bkPlayurls(String sid, String pid, String defn) throws Exception {
        String d = (defn == null || defn.trim().isEmpty()) ? "fhd" : defn.trim();
        Map<String, Object> key = ckey(sid);

        String query = buildQuery(sid, pid, d, key);
        String url = BK_INFO_URL + query;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", BK_UA);
        headers.put("Accept", "application/json");

        String text = httpGet(url, headers, 5000);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            throw new IOException("bkliveinfo 返回非 JSON: " + abbrev(text));
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("bkliveinfo 返回格式异常: " + abbrev(text));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) parsed;

        int retcode = intOf(p.get("iretcode"), -1);
        if (retcode != 0) {
            Object errinfo = p.get("errinfo");
            throw new IOException("iretcode=" + retcode + " " + (errinfo == null ? "" : errinfo));
        }

        // 收集：playurl + backurl_list/backurlList/backurl
        List<String> raw = new ArrayList<>();
        Object playurl = p.get("playurl");
        if (playurl instanceof String && !((String) playurl).isEmpty()) {
            raw.add((String) playurl);
        }
        Object bu = p.get("backurl_list");
        if (bu == null) {
            bu = p.get("backurlList");
        }
        if (bu == null) {
            bu = p.get("backurl");
        }
        if (bu instanceof List) {
            for (Object it : (List<?>) bu) {
                if (it instanceof String) {
                    raw.add((String) it);
                } else if (it instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> im = (Map<String, Object>) it;
                    Object u = im.get("url");
                    if (!(u instanceof String)) {
                        u = im.get("playurl");
                    }
                    raw.add(u instanceof String ? (String) u : "");
                }
            }
        } else if (bu instanceof String) {
            for (String x : ((String) bu).split("[;,]")) {
                if (!x.trim().isEmpty()) {
                    raw.add(x);
                }
            }
        }

        // 去重（保序）+ 只留 .cctv. 域名
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String u : raw) {
            if (u != null && !u.isEmpty() && u.contains(".cctv.")) {
                uniq.add(u);
            }
        }
        if (uniq.isEmpty()) {
            throw new IOException("no playurl");
        }
        List<String> urls = new ArrayList<>(uniq);
        // 稳定排序：含 bklive- 的排前面，其余按字典序
        urls.sort((a, b) -> {
            int ka = a.contains("bklive-") ? 0 : 1;
            int kb = b.contains("bklive-") ? 0 : 1;
            if (ka != kb) {
                return Integer.compare(ka, kb);
            }
            return a.compareTo(b);
        });
        Log.d(TAG, "bkliveinfo 取到 " + urls.size() + " 个地址 (sid=" + sid + ", defn=" + d + ")");
        return urls;
    }

    /**
     * 取主 playlist 并展开成绝对地址（对应 Python {@code fetch_abs_playlist}）。
     * <p>最多递归 2 层 {@code #EXT-X-STREAM-INF}，每行非注释地址都转成绝对 URL。
     *
     * @return 展开后的 m3u8 文本（LF 换行）
     */
    public static String fetchAbsPlaylist(String url) throws Exception {
        return fetchAbsPlaylist(url, 0);
    }

    private static String fetchAbsPlaylist(String url, int depth) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", BK_UA);
        headers.put("Referer", BK_REFERER);
        headers.put("Accept", "application/vnd.apple.mpegurl,application/json,*/*");

        String text;
        String finalUrl;
        Http http = new Http(10000);
        try {
            HttpResponse resp = http.get(url, headers);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("playlist HTTP " + resp.status + " " + resp.reason);
            }
            text = resp.text();
            finalUrl = url;
        } finally {
            closeQuietly(http);
        }

        if (depth < 2) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String s = lines[j].trim();
                        if (!s.isEmpty() && !s.startsWith("#")) {
                            return fetchAbsPlaylist(Url.join(finalUrl, s), depth + 1);
                        }
                    }
                    break;
                }
            }
        }

        StringBuilder out = new StringBuilder(text.length() + 64);
        boolean first = true;
        for (String ln : text.split("\n", -1)) {
            String s = ln.trim();
            if (!first) {
                out.append('\n');
            }
            first = false;
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.append(Url.join(finalUrl, s));
            } else {
                out.append(ln);
            }
        }
        return out.toString();
    }

    // ==================================================================
    // cKey 链
    // ==================================================================

    /**
     * 构造 cKey 三件套（对应 Python {@code _ckey}）。
     *
     * @return {@code cKey} / {@code guid} / {@code ts} / {@code flowId}
     */
    static Map<String, Object> ckey(String channelId) {
        long ts = System.currentTimeMillis() / 1000L;
        String guid = randomHex(16);
        String guard = ckGuard(ts, guid);
        String uid = randomHex(4).toUpperCase(java.util.Locale.ROOT);

        ByteArrayOutputStream body = new ByteArrayOutputStream(512);
        write(body, hex("0000004200000004000004d2"));
        write(body, u32be(CK_PLATFORM));
        write(body, u32be(0));
        write(body, u32be(ts));
        writeLp(body, "dcgh");
        writeLp(body, "_zj1A5Gh6QYcxWjIUGos2w==");
        writeLp(body, CK_APPVER);
        writeLp(body, String.valueOf(channelId));
        writeLp(body, guid);
        write(body, u32be(1));
        write(body, u32be(1));
        writeLp(body, uid);
        writeLp(body, "nil");
        writeLp(body, "57eab0c4-2c58-44c6-8ae9-dd2757525dc5");
        writeLp(body, "nil");
        writeLp(body, "v0.1.000");
        writeLp(body, "com.cctv.yangshipin.app.iphone");
        writeLp(body, String.valueOf(CK_PLATFORM));
        writeLp(body, "ex_json_bus");
        writeLp(body, "ex_json_vs");
        writeLp(body, guard);

        byte[] bodyBytes = body.toByteArray();

        // pkt = 2 字节长度 + body；偏移 18..22 回填 cksum(pkt)
        byte[] pkt = new byte[2 + bodyBytes.length];
        pkt[0] = (byte) ((bodyBytes.length >>> 8) & 0xFF);
        pkt[1] = (byte) (bodyBytes.length & 0xFF);
        System.arraycopy(bodyBytes, 0, pkt, 2, bodyBytes.length);
        putU32be(pkt, 18, cksum(pkt));

        byte[] enc = teaPkt(pkt, CK_TEA);
        byte[] full = new byte[enc.length + 4];
        System.arraycopy(enc, 0, full, 0, enc.length);
        putU32be(full, enc.length, cksum(pkt));
        for (int i = 0; i < full.length; i++) {
            full[i] ^= CK_XOR[i & 15];
        }

        String b64 = Base64X.encode(full).replace('+', '_').replace('/', '-');
        int end = b64.length();
        while (end > 0 && b64.charAt(end - 1) == '=') {
            end--;
        }
        b64 = b64.substring(0, end);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cKey", "--01" + b64);
        out.put("guid", guid);
        out.put("ts", ts);
        out.put("flowId", randomHex(32).toUpperCase(java.util.Locale.ROOT) + "_" + CK_PLATFORM);
        return out;
    }

    /** 对应 Python {@code _ck_guard}：TEA(pkt) + cksum，再整体 XOR，最后大写 hex。 */
    static String ckGuard(long ts, String guid) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(64);
        write(body, u32be(ts));
        writeLp(body, tail5(guid));
        writeLp(body, tail5("null"));
        writeLp(body, tail5("null"));
        writeLp(body, "-1");
        byte[] plain = lp(body.toByteArray());

        byte[] enc = concat(teaPkt(plain, CK_GTEA), u32be(cksum(plain)));
        for (int i = 0; i < enc.length; i++) {
            enc[i] ^= CK_GXOR[i & 7];
        }
        return hexUpper(enc);
    }

    /**
     * 对应 Python {@code _tea_pkt}：CBC 风格的 TEA 链式加密。
     * <pre>
     *   pad = (8 - (len+10) % 8) % 8
     *   plain = [rand&amp;0xF8|pad] + rand(pad) + rand(2) + data + 7×0x00
     *   每块: mixed = block XOR prevCipher; enc = TEA(mixed); out = enc XOR prevMixed
     * </pre>
     */
    static byte[] teaPkt(byte[] data, byte[] key) {
        int pad = (8 - (data.length + 10) % 8) % 8;
        byte[] r = new byte[1 + pad + 2];
        RANDOM.nextBytes(r);
        byte[] plain = new byte[1 + pad + 2 + data.length + 7];
        plain[0] = (byte) ((r[0] & 0xF8) | pad);
        System.arraycopy(r, 1, plain, 1, pad + 2);
        System.arraycopy(data, 0, plain, 1 + pad + 2, data.length);

        byte[] out = new byte[plain.length];
        byte[] pp = new byte[8];
        byte[] pc = new byte[8];
        for (int off = 0; off < plain.length; off += 8) {
            byte[] mixed = new byte[8];
            for (int i = 0; i < 8; i++) {
                mixed[i] = (byte) (plain[off + i] ^ pc[i]);
            }
            byte[] enc = Tea.encryptBlock(key, mixed);
            // 关键：Python 是 `cipher = enc ^ pp; out += cipher; pp, pc = mixed, cipher`
            // 也就是说 pc 链的是**最终输出的密文块**（enc^pp），不是 TEA 输出的 enc。
            // 这里曾经错误地让 pc = enc，导致第二块起全部错位，服务端只回 iretcode=32。
            byte[] cipher = new byte[8];
            for (int i = 0; i < 8; i++) {
                cipher[i] = (byte) (enc[i] ^ pp[i]);
                out[off + i] = cipher[i];
            }
            pp = mixed;
            pc = cipher;
        }
        return out;
    }

    /** 对应 Python {@code _cksum}。 */
    static int cksum(byte[] buf) {
        int v = 0;
        for (byte b : buf) {
            v = (131 * v + (b & 0xFF)) & 0x7FFFFFFF;
        }
        return v;
    }

    // ==================================================================
    // 查询串
    // ==================================================================

    private static String buildQuery(String sid, String pid, String defn, Map<String, Object> key) {
        // 严格按 Python 字典字面量的插入顺序，urllib.parse.urlencode 保序
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"atime", "120"});
        pairs.add(new String[] {"livepid", str(pid)});
        pairs.add(new String[] {"cnlid", str(sid)});
        pairs.add(new String[] {"appVer", CK_APPVER});
        pairs.add(new String[] {"app_version", "300090"});
        pairs.add(new String[] {"caplv", "1"});
        pairs.add(new String[] {"cmd", "2"});
        pairs.add(new String[] {"defn", defn});
        pairs.add(new String[] {"device", "iPhone"});
        pairs.add(new String[] {"encryptVer", "4.2"});
        pairs.add(new String[] {"getpreviewinfo", "0"});
        pairs.add(new String[] {"hevclv", "0"});
        pairs.add(new String[] {"lang", "zh-Hans_CN"});
        pairs.add(new String[] {"livequeue", "0"});
        pairs.add(new String[] {"logintype", "1"});
        pairs.add(new String[] {"nettype", "1"});
        pairs.add(new String[] {"newnettype", "1"});
        pairs.add(new String[] {"newplatform", String.valueOf(CK_PLATFORM)});
        pairs.add(new String[] {"platform", String.valueOf(CK_PLATFORM)});
        pairs.add(new String[] {"sdtfrom", "v3021"});
        pairs.add(new String[] {"spacode", "23"});
        pairs.add(new String[] {"spaudio", "1"});
        pairs.add(new String[] {"spdemuxer", "6"});
        pairs.add(new String[] {"spdrm", "2"});
        pairs.add(new String[] {"spdynamicrange", "1"});
        pairs.add(new String[] {"spflv", "1"});
        pairs.add(new String[] {"spflvaudio", "1"});
        pairs.add(new String[] {"sphdrfps", "60"});
        pairs.add(new String[] {"sphttps", "1"});
        pairs.add(new String[] {"spvcode", BK_H264});
        pairs.add(new String[] {"spvideo", "4"});
        pairs.add(new String[] {"stream", "1"});
        pairs.add(new String[] {"system", "1"});
        pairs.add(new String[] {"sysver", "ios18.2.1"});
        pairs.add(new String[] {"uhd_flag", "0"});
        pairs.add(new String[] {"cKey", str(key.get("cKey"))});
        pairs.add(new String[] {"guid", str(key.get("guid"))});
        pairs.add(new String[] {"fntick", str(key.get("ts"))});
        pairs.add(new String[] {"flowid", str(key.get("flowId"))});
        pairs.add(new String[] {"playbacktime", "0"});

        StringBuilder sb = new StringBuilder(1024);
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append(formEncode(pairs.get(i)[0])).append('=').append(formEncode(pairs.get(i)[1]));
        }
        return sb.toString();
    }

    /**
     * 与 Python {@code urllib.parse.urlencode} 等价（quote_plus 语义）：
     * 字母数字与 {@code *-._} 不转义，空格变 {@code +}，其余 {@code %XX}（大写）。
     */
    static String formEncode(String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(raw.length + 8);
        for (byte b : raw) {
            int c = b & 0xFF;
            char ch = (char) c;
            boolean safe = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || ch == '*' || ch == '-' || ch == '.' || ch == '_';
            if (safe) {
                sb.append(ch);
            } else if (c == ' ') {
                sb.append('+');
            } else {
                sb.append('%');
                sb.append(HEX_UPPER.charAt((c >>> 4) & 0xF));
                sb.append(HEX_UPPER.charAt(c & 0xF));
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 字节工具
    // ==================================================================

    private static final String HEX_UPPER = "0123456789ABCDEF";
    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();

    /** Python {@code _lp}：2 字节大端长度 + 数据。 */
    private static byte[] lp(byte[] d) {
        byte[] out = new byte[2 + d.length];
        out[0] = (byte) ((d.length >>> 8) & 0xFF);
        out[1] = (byte) (d.length & 0xFF);
        System.arraycopy(d, 0, out, 2, d.length);
        return out;
    }

    private static void writeLp(ByteArrayOutputStream out, String s) {
        byte[] d = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        out.write((d.length >>> 8) & 0xFF);
        out.write(d.length & 0xFF);
        out.write(d, 0, d.length);
    }

    private static void write(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static byte[] u32be(long v) {
        return new byte[] {
            (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF),
            (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)
        };
    }

    private static void putU32be(byte[] buf, int off, long v) {
        buf[off] = (byte) ((v >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Python 的 {@code str(v)[-5:]}，长度不足 5 返回空串。 */
    private static String tail5(Object v) {
        String t = String.valueOf(v);
        return t.length() >= 5 ? t.substring(t.length() - 5) : "";
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_LOWER[RANDOM.nextInt(16)]);
        }
        return sb.toString();
    }

    private static String hexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(HEX_UPPER.charAt((x >> 4) & 0xF)).append(HEX_UPPER.charAt(x & 0xF));
        }
        return sb.toString();
    }

    static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return r;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String httpGet(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        Http http = new Http(timeoutMs);
        try {
            HttpResponse resp = http.get(url, headers);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("HTTP " + resp.status + " " + resp.reason);
            }
            return resp.text();
        } finally {
            closeQuietly(http);
        }
    }

    private static void closeQuietly(Http http) {
        if (http != null) {
            try {
                http.close();
            } catch (RuntimeException ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ==================================================================
    // URL 工具
    // ==================================================================

    /** 最小 URL 拼接（等价于 {@code urllib.parse.urljoin}，只覆盖我们需要的场景）。 */
    static final class Url {

        private Url() { }

        static String join(String base, String ref) {
            if (ref == null || ref.isEmpty()) {
                return base == null ? "" : base;
            }
            String lower = ref.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return ref;
            }
            String[] bp = split(base);
            if (bp == null) {
                return ref;
            }
            String scheme = bp[0];
            String authority = bp[1];
            String basePath = bp[2];

            if (ref.startsWith("//")) {
                return scheme + ":" + ref;
            }
            if (ref.startsWith("?")) {
                return scheme + "://" + authority + basePath + ref;
            }
            if (ref.startsWith("/")) {
                return scheme + "://" + authority + ref;
            }
            if (ref.startsWith("#")) {
                return scheme + "://" + authority + basePath + ref;
            }
            int slash = basePath.lastIndexOf('/');
            String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "/";
            return scheme + "://" + authority + normalize(dir + ref);
        }

        /** 解析成 {scheme, authority, path}。 */
        private static String[] split(String url) {
            int idx = url.indexOf("://");
            if (idx <= 0) {
                return null;
            }
            String scheme = url.substring(0, idx);
            String rest = url.substring(idx + 3);
            int start = 0;
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    start = i;
                    break;
                }
                start = rest.length();
            }
            String authority = rest.substring(0, start);
            String path = start < rest.length() ? rest.substring(start) : "/";
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int h = path.indexOf('#');
            if (h >= 0) {
                path = path.substring(0, h);
            }
            if (path.isEmpty()) {
                path = "/";
            }
            return new String[] {scheme, authority, path};
        }

        /** 折叠 {@code .} 与 {@code ..}。 */
        private static String normalize(String path) {
            String[] segs = path.split("/", -1);
            List<String> out = new ArrayList<>(segs.length);
            for (String seg : segs) {
                if (".".equals(seg)) {
                    continue;
                }
                if ("..".equals(seg)) {
                    if (!out.isEmpty()) {
                        out.remove(out.size() - 1);
                    }
                    continue;
                }
                out.add(seg);
            }
            StringBuilder sb = new StringBuilder(path.length());
            for (int i = 0; i < out.size(); i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(out.get(i));
            }
            String r = sb.toString();
            return r.isEmpty() ? "/" : r;
        }
    }
}
