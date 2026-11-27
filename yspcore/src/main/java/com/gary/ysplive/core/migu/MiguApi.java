package com.gary.ysplive.core.migu;

import com.gary.ysplive.core.spi.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 咪咕 playurl 协议实现（develop202 算法），逐行移植自 migu_lnss.php。
 *
 * <p>签名链：时间戳 + contId + 版本前缀双 md5，叠加固定盐与随机盐前 4 位；
 * 返回的 puData 经 ddCalcu 逐字符交叉拼接（按日期/频道插字）得到 ddCalcu 参数；
 * 最后最多 6 跳跟踪 302 到最终 CDN 清单地址。
 */
final class MiguApi {

    private static final String TAG = "migu";
    private static final String APP_VERSION = "2600034600";
    private static final String SECRET = "2cac4f2c6c3346a5b34e085725ef7e33migu";
    private static final String PLAYURL = "https://play.miguvideo.com/playurl/v1/play/playurl";
    private static final Random RANDOM = new Random();

    private MiguApi() { }

    /** HTTP 响应。 */
    static final class Resp {
        final int code;
        final String body;
        final String location;
        final String type;

        Resp(int code, String body, String location, String type) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.location = location;
            this.type = type == null ? "" : type;
        }
    }

    /** 与 PHP fetchUrl 对齐：默认跟随重定向，可提取响应头。 */
    static Resp fetch(String url, Map<String, String> headers, boolean follow, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(Math.min(timeoutMs, 8000));
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(follow);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            String type = conn.getContentType();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            return new Resp(code, body, loc, type);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static Resp fetch(String url) throws IOException {
        return fetch(url, null, true, 15000);
    }

    /** md5 小写十六进制。 */
    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** 构造签名请求 URL 与请求头（对应 PHP miguApiUrl）。 */
    private static String[] apiUrl(String pid) {
        String t = String.valueOf(System.currentTimeMillis());
        String v = APP_VERSION;
        String cid = md5(String.valueOf(System.currentTimeMillis()));
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("AppVersion", v);
        h.put("TerminalId", "android");
        h.put("X-UP-CLIENT-CHANNEL-ID", v + "-99000-201600010010028");
        h.put("ClientId", cid);
        // CCTV-5 / CCTV-5+ 不加 appCode（与 PHP 完全一致）
        if (!"641886683".equals(pid) && !"641886773".equals(pid)) {
            h.put("appCode", "miguvideo_default_android");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        int rand = RANDOM.nextInt(1000000);
        String salt = String.format(Locale.ROOT, "%06d", rand) + "25";
        String sign = md5(md5(t + pid + v.substring(0, 8)) + SECRET + salt.substring(0, 4));
        String url = PLAYURL
                + "?sign=" + sign
                + "&rateType=3&contId=" + pid
                + "&timestamp=" + t
                + "&salt=" + salt
                + "&flvEnable=true&super4k=true&h265N=true&4kvivid=true&2Kvivid=true&vivid=2";
        return new String[]{url, sb.toString()};
    }

    /**
     * puData → ddCalcu（对应 PHP ddCalcu）：
     * 逐字符「尾 + 头」交叉拼接，第 2/3/4/5 轮分别插 'v'、日期键、频道键、'a'。
     */
    static String ddCalcu(String puData, String pid) {
        String keys = "cdabyzwxkl";
        StringBuilder dd = new StringBuilder();
        int n = puData.length();
        String ymd = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        // PHP substr(date('Ymd'), 2, 1) → ymd.charAt(2) 作为数字索引
        int dateKey = Character.digit(ymd.charAt(2), 10);
        int pidKey = Character.digit(pid.charAt(6), 10);
        for (int i = 0; i < n / 2; i++) {
            dd.append(puData.charAt(n - i - 1)).append(puData.charAt(i));
            if (i == 1) dd.append('v');
            if (i == 2 && dateKey >= 0 && dateKey < keys.length()) dd.append(keys.charAt(dateKey));
            if (i == 3 && pidKey >= 0 && pidKey < keys.length()) dd.append(keys.charAt(pidKey));
            if (i == 4) dd.append('a');
        }
        return dd.toString();
    }

    /** 解析频道的最终 m3u8 地址；失败返回 null。 */
    static String resolve(String pid) {
        try {
            String[] ua = apiUrl(pid);
            Resp res = fetch(ua[0], parseHeaders(ua[1]), true, 15000);
            String url = extractPlayUrl(res.body);
            if (url == null) {
                Log.w(TAG, "[" + pid + "] playurl 无 url: code=" + res.code + " body=" + abbrev(res.body, 160));
                return null;
            }
            int idx = url.indexOf("&puData=");
            if (idx < 0) {
                Log.w(TAG, "[" + pid + "] url 无 puData");
                return null;
            }
            int end = url.indexOf('&', idx + 8);
            String puData = end < 0 ? url.substring(idx + 8) : url.substring(idx + 8, end);
            url = url + "&ddCalcu=" + ddCalcu(puData, pid) + "&sv=10004&ct=android";

            Map<String, String> miguHeaders = new LinkedHashMap<String, String>();
            miguHeaders.put("User-Agent", "MiguVideo/6.2.30");
            for (int i = 0; i < 6; i++) {
                Resp r = fetch(url, miguHeaders, false, 15000);
                if (r.code != 302) return url;
                if (r.location == null || r.location.isEmpty() || r.location.startsWith("http://bofang")) {
                    return url;
                }
                url = r.location;
            }
            return url;
        } catch (Exception e) {
            Log.w(TAG, "[" + pid + "] resolve 异常: " + e);
            return null;
        }
    }

    /** 从 playurl JSON 提取 body.urlInfo.url（轻量解析，避免引入 JSON 库）。 */
    private static String extractPlayUrl(String json) {
        int ui = json.indexOf("\"urlInfo\"");
        if (ui < 0) return null;
        int u = json.indexOf("\"url\"", ui);
        if (u < 0) return null;
        int colon = json.indexOf(':', u + 5);
        if (colon < 0) return null;
        int q = json.indexOf('"', colon + 1);
        if (q < 0) return null;
        int q2 = json.indexOf('"', q + 1);
        if (q2 < 0) return null;
        return json.substring(q + 1, q2).replace("\\/", "/").replace("\\u0026", "&");
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String line : raw.split("\n")) {
            int c = line.indexOf(':');
            if (c > 0) m.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        return m;
    }

    /** 校验响应体是 m3u8（去 BOM 后以 #EXTM3U 开头）。 */
    static String m3u8Body(String body) {
        if (body == null || body.isEmpty()) return null;
        String t = body;
        while (!t.isEmpty() && (t.charAt(0) == '\uFEFF' || t.charAt(0) == ' ' || t.charAt(0) == '\t'
                || t.charAt(0) == '\r' || t.charAt(0) == '\n')) {
            t = t.substring(1);
        }
        return t.regionMatches(true, 0, "#EXTM3U", 0, 7) ? t : null;
    }

    /** 相对 URL 转绝对（对应 PHP resolveUrl）。 */
    static String resolveUrl(String base, String rel) {
        if (rel == null) return base;
        if (rel.regionMatches(true, 0, "http://", 0, 7) || rel.regionMatches(true, 0, "https://", 0, 8)) {
            return rel;
        }
        try {
            URL b = new URL(base);
            String root = b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "");
            if (rel.startsWith("/")) return root + rel;
            String path = b.getPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return root + dir + rel;
        } catch (Exception e) {
            return rel;
        }
    }

    /** 清单改写：分片与 KEY/MAP 的 URI 换绝对地址（对应 PHP rewriteAbsolute）。 */
    static String rewriteAbsolute(String body, String baseUrl) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (l.charAt(0) == '#') {
                int k = l.indexOf("URI=\"");
                if (k >= 0) {
                    int e = l.indexOf('"', k + 5);
                    if (e > k + 5) {
                        String uri = l.substring(k + 5, e);
                        l = l.substring(0, k + 5) + resolveUrl(baseUrl, uri) + l.substring(e);
                    }
                }
                out.append(l).append('\n');
            } else {
                out.append(resolveUrl(baseUrl, l)).append('\n');
            }
        }
        return out.toString();
    }

    /** master 清单挑最高 BANDWIDTH 变体。 */
    static String pickBestVariant(String master, String baseUrl) {
        String bestUrl = "";
        long bestBw = 0;
        long curBw = 0;
        for (String line : master.split("\n")) {
            String l = line.trim();
            int b = l.indexOf("BANDWIDTH=");
            if (b >= 0) {
                int end = b + 10;
                while (end < l.length() && Character.isDigit(l.charAt(end))) end++;
                try { curBw = Long.parseLong(l.substring(b + 10, end)); } catch (Exception ignored) { curBw = 0; }
            } else if (!l.isEmpty() && l.charAt(0) != '#') {
                if (curBw >= bestBw) {
                    bestBw = curBw;
                    bestUrl = resolveUrl(baseUrl, l);
                }
                curBw = 0;
            }
        }
        return bestUrl;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String abbrev(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
