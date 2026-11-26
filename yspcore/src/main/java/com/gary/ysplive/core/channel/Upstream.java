package com.gary.ysplive.core.channel;

import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.util.Base64X;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 上游访问适配层：把所有跨包调用（JCE / bkliveinfo / HTTP / base64）收敛到一个文件里。
 * <p>这样其它模块改名或还没落地时，只需要改这个文件的一行，不影响
 * {@link ChannelEngine} 与 {@code core.server} 的逻辑。
 *
 * <p>HTTP 走 {@link HttpURLConnection} 并默认 {@link Proxy#NO_PROXY}
 * （等价于 Python 的 {@code ProxyHandler({})}，央视频域名走代理会 403）。
 */
public final class Upstream {

    private static final String TAG = "upstream";

    /** Python {@code UA}。 */
    public static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 备用通道（bkliveinfo 播放列表）拉取用的 UA 与 Referer。 */
    public static final String BK_UA = "qqlive";
    public static final String BK_REFERER = "https://live.cctv.cn/";

    /** 单次上游播放列表拉取超时。 */
    public static final int PLAYLIST_TIMEOUT_MS = 10_000;
    /** JCE 请求超时（Python 是 20s urlopen）。 */
    public static final int JCE_TIMEOUT_MS = 20_000;

    /** 分片大小上限（Python 是 35MB 的硬闸）。 */
    public static final int MAX_SEGMENT_BYTES = 35 * 1024 * 1024;

    private Upstream() { }

    // ------------------------------------------------------------------ 异常

    /** 死域名：JCE 返回的地址落在已失效 CDN，需要自动切到 bk 通道。 */
    public static final class DeadHostException extends Exception {
        private static final long serialVersionUID = 1L;

        public DeadHostException(String message) {
            super(message);
        }
    }

    /** 上游返回的响应（头键全小写）。 */
    public static final class Resp {
        public final int status;
        public final String reason;
        public final Map<String, String> headers;
        public final byte[] body;

        Resp(int status, String reason, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.reason = reason == null ? "" : reason;
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
            this.body = body == null ? new byte[0] : body;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headerOrNull(headers, name);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 上游返回非 2xx 时抛出（封装状态码，便于 bk 刷新重试逻辑区分）。 */
    public static final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        public HttpStatusException(int status, String message) {
            super("HTTP " + status + (message == null || message.isEmpty() ? "" : " " + message));
            this.status = status;
        }
    }

    /**
     * 组装取流请求头，等价于 Python {@code _playback_request_headers}：
     * 只透传白名单里的签名头，再补 {@code Accept}/{@code Accept-Encoding}/{@code Connection}。
     * <p>{@code Host} 不放：Python 里它就是 URL 的 host:port，而 JVM 会自己填正确值
     * （{@code Host} 是受限头，手工设置会被静默丢弃）。
     */
    public static Map<String, String> playbackRequestHeaders(Map<String, String> playbackHeaders,
                                                             Map<String, String> extra) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (playbackHeaders != null) {
            for (String key : new String[] {"UID", "APPID", "APPRANDOMSTR", "Referer",
                    "User-Agent", "APPSIGN"}) {
                String value = playbackHeaders.get(key);
                if (value == null) {
                    // 大小写不敏感再找一次
                    for (Map.Entry<String, String> e : playbackHeaders.entrySet()) {
                        if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                            value = e.getValue();
                            break;
                        }
                    }
                }
                if (value != null && !value.trim().isEmpty()) {
                    headers.put(key, headerValueClean(value));
                }
            }
        }
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        headers.put("Connection", "close");
        if (extra != null) {
            headers.putAll(extra);
        }
        return headers;
    }

    // ------------------------------------------------------------------ JCE

    /**
     * 生成时移 m3u8 地址。对应 Python {@code jce_timeshift_url(pid, sid, start, end, stream)}。
     *
     * @throws DeadHostException 返回的地址落在死 CDN（调用方应切换到 bk 通道）
     */
    public static String jceTimeshiftUrl(String pid, String sid, long startSec, long endSec,
                                         String defn) throws Exception {
        try {
            return com.gary.ysplive.core.jce.JceRequest.jceTimeshiftUrl(pid, sid, startSec, endSec, defn);
        } catch (com.gary.ysplive.core.jce.JceRequest.DeadHostException e) {
            throw new DeadHostException(e.getMessage() == null ? "dead cdn host" : e.getMessage());
        }
    }

    // ------------------------------------------------------------------ bk（bkliveinfo）

    /** 拉取备用通道候选地址。对应 Python {@code bk_playurls(sid, pid, defn)}。 */
    public static List<String> bkPlayurls(String sid, String pid, String defn) throws Exception {
        List<String> urls = com.gary.ysplive.core.bk.BkLive.bkPlayurls(sid, pid, defn);
        return urls == null ? new ArrayList<String>() : new ArrayList<>(urls);
    }

    // ------------------------------------------------------------------ 播放列表

    /**
     * 拉一个上游播放列表并展开 master playlist（最多 2 层）。
     * <p>对应 Python {@code fetch_abs_playlist(url, depth=0)}：返回的分片地址会被
     * urljoin 成绝对地址，无法识别的行原样保留。
     */
    public static String fetchAbsPlaylist(String url) throws IOException {
        return fetchAbsPlaylist(url, 0);
    }

    private static String fetchAbsPlaylist(String url, int depth) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", BK_UA);
        headers.put("Referer", BK_REFERER);
        headers.put("Accept", "application/vnd.apple.mpegurl,application/json,*/*");
        Resp resp = httpGet(url, headers, PLAYLIST_TIMEOUT_MS);
        if (!resp.ok()) {
            throw new HttpStatusException(resp.status, "");
        }
        String text = resp.text();
        String finalUrl = resp.header("x-final-url");
        if (finalUrl == null || finalUrl.isEmpty()) {
            finalUrl = url;
        }
        if (depth < 2) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String s = lines[j].trim();
                        if (!s.isEmpty() && !s.startsWith("#")) {
                            return fetchAbsPlaylist(UrlX.urlJoin(finalUrl, s), depth + 1);
                        }
                    }
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder(text.length() + 128);
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String rawLine : lines) {
            String s = rawLine.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                out.add(UrlX.urlJoin(finalUrl, s));
            } else {
                out.add(stripCr(rawLine));
            }
        }
        sb.append(String.join("\n", out));
        return sb.toString();
    }

    /** 拉 JCE 时移播放列表（带浏览器 UA）。 */
    public static String fetchJcePlaylist(String m3u8Url) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        Resp resp = httpGet(m3u8Url, headers, JCE_TIMEOUT_MS);
        if (!resp.ok()) {
            throw new IOException("HTTP " + resp.status);
        }
        String finalUrl = resp.header("x-final-url");
        String base = (finalUrl == null || finalUrl.isEmpty()) ? m3u8Url : finalUrl;
        // 分片相对地址需要在解析时 join，这里统一改成绝对地址，后续逻辑就不用再拼
        StringBuilder sb = new StringBuilder(resp.body.length + 128);
        for (String line : resp.text().split("\n", -1)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                sb.append(UrlX.urlJoin(base, s));
            } else {
                sb.append(stripCr(line));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    // ------------------------------------------------------------------ HTTP

    /** 普通 GET（跟随重定向），返回完整响应。 */
    public static Resp httpGet(String url, Map<String, String> headers, int timeoutMs)
            throws IOException {
        return httpRequest("GET", url, headers, null, timeoutMs, true);
    }

    /**
     * 原始 HTTP 请求。
     * <p>注意：{@link HttpURLConnection} 不允许自由设置 {@code Host}/{@code Connection} 等
     * 受限头，这里会跳过这些键（由 JVM 自己填正确的值），其余签名头原样透传。
     */
    public static Resp httpRequest(String method, String url, Map<String, String> headers,
                                   byte[] body, int timeoutMs, boolean followRedirects)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            URLConnection raw = u.openConnection(Proxy.NO_PROXY);
            if (!(raw instanceof HttpURLConnection)) {
                throw new IOException("not an http(s) url: " + url);
            }
            conn = (HttpURLConnection) raw;
            conn.setRequestMethod(method);
            conn.setConnectTimeout(Math.max(1000, timeoutMs));
            conn.setReadTimeout(Math.max(1000, timeoutMs));
            conn.setInstanceFollowRedirects(followRedirects);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || v == null || k.isEmpty()) {
                        continue;
                    }
                    String lower = k.toLowerCase(Locale.ROOT);
                    if (lower.equals("host") || lower.equals("connection")
                            || lower.equals("content-length") || lower.equals("accept-encoding")) {
                        continue;
                    }
                    conn.setRequestProperty(k, headerValueClean(v));
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                } finally {
                    os.close();
                }
            }
            int status = conn.getResponseCode();
            Map<String, String> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                respHeaders.put(k.toLowerCase(Locale.ROOT), e.getValue().get(0));
            }
            String finalUrl = conn.getURL() == null ? url : conn.getURL().toString();
            respHeaders.put("x-final-url", finalUrl);
            byte[] data = readAll(conn, status);
            String reason = conn.getResponseMessage();
            return new Resp(status, reason, respHeaders, data);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(HttpURLConnection conn, int status) throws IOException {
        InputStream in = null;
        try {
            in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return new byte[0];
            }
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_SEGMENT_BYTES + 1024L * 1024L) {
                    throw new IOException("response too large (>35MB)");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
    }

    /** 只取响应状态码，不读 body（分片探活用，HTTP HEAD）。 */
    public static int headStatus(String url, Map<String, String> headers, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            URLConnection raw = u.openConnection(Proxy.NO_PROXY);
            if (!(raw instanceof HttpURLConnection)) {
                throw new IOException("not an http(s) url: " + url);
            }
            conn = (HttpURLConnection) raw;
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(Math.max(1000, timeoutMs));
            conn.setReadTimeout(Math.max(1000, timeoutMs));
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    if (k == null || e.getValue() == null) {
                        continue;
                    }
                    String lower = k.toLowerCase(Locale.ROOT);
                    if (lower.equals("host") || lower.equals("connection")
                            || lower.equals("content-length") || lower.equals("accept-encoding")) {
                        continue;
                    }
                    conn.setRequestProperty(k, headerValueClean(e.getValue()));
                }
            }
            return conn.getResponseCode();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 去掉头值里的 CR/LF（防头注入），对应 Python {@code header_value_clean}。 */
    public static String headerValueClean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "");
    }

    /** 大小写不敏感地取头（键可能来自上游原始大小写）。 */
    public static String headerOrNull(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ TS 代理 token

    /**
     * 从请求路径里取 {@code /proxy.ts} 的 token。
     * <p>对应 Python {@code proxy_ts_token}：支持 {@code ?=<token>}、{@code ?ts=}、
     * {@code ?url=}，以及裸查询串。
     */
    public static String proxyTsToken(String rawPath) {
        int q = rawPath == null ? -1 : rawPath.indexOf('?');
        if (q < 0) {
            return null;
        }
        String query = rawPath.substring(q + 1);
        if (query.startsWith("=")) {
            String token = query.substring(1);
            return token.isEmpty() ? null : token;
        }
        String t = queryParam(rawPath, "ts");
        if (t != null && !t.isEmpty()) {
            return t;
        }
        t = queryParam(rawPath, "url");
        if (t != null && !t.isEmpty()) {
            return t;
        }
        if (!query.isEmpty() && query.indexOf('=') < 0) {
            return query;
        }
        return null;
    }

    /** 取原始（未解码）查询参数值。 */
    public static String queryParam(String url, String name) {
        int q = url == null ? -1 : url.indexOf('?');
        if (q < 0) {
            return null;
        }
        String query = url.substring(q + 1);
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }

    /**
     * 解码 {@code /proxy.ts} 的 token。对应 Python {@code decode_proxy_ts_url}。
     *
     * @throws IllegalArgumentException 解码失败/不是 http(s)/不是 .ts
     */
    public static String decodeProxyTsUrl(String token) {
        String padded = token;
        int rem = (-token.length()) % 4;
        if (rem < 0) {
            rem += 4;
        }
        StringBuilder sb = new StringBuilder(token);
        for (int i = 0; i < rem; i++) {
            sb.append('=');
        }
        padded = sb.toString();
        byte[] raw;
        try {
            raw = Base64X.decode(padded);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid proxy ts token: " + e.getMessage(), e);
        }
        String value;
        try {
            value = new String(raw, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("proxy ts token is not UTF-8: " + e.getMessage(), e);
        }
        String scheme = UrlX.schemeOf(value);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "proxy ts currently requires http or https URL, got " + scheme);
        }
        String path = UrlX.pathOf(value);
        if (!path.toLowerCase(Locale.ROOT).endsWith(".ts")) {
            throw new IllegalArgumentException("proxy ts URL is not a .ts segment");
        }
        return value;
    }

    /** 生成 {@code /proxy.ts} token（编码用；与解码互逆）。 */
    public static String encodeProxyTsUrl(String realUrl) {
        return Base64X.encodeUrlSafeNoPad(realUrl.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 引擎上游（8787 兼容）

    /** Python {@code engine_port}：另一个 ysp-live 引擎的端口。 */
    public static volatile int enginePort;

    /** Python {@code ALLOWED_ENGINE_PROXY_SUFFIXES}。 */
    private static final String[] ALLOWED_ENGINE_PROXY_SUFFIXES = {
        ".cctv.cn", ".cctv.com", ".cctvpic.com", ".citvc.com", ".yangshipin.cn",
        ".cntv.cn", ".cntv.com", ".myqcloud.com", ".qqlive.com", ".gtimg.cn",
        ".smtcdns.net", ".smtcdns.com", ".tcdnlive.com", ".alicdn.com", ".alikunlun.com",
        ".wscdns.com", ".chinanetcenter.com", ".bdydns.com", ".ksyungslb.com", ".ks-cdn.com",
    };

    /** 对应 Python {@code _is_safe_engine_proxy_url}。 */
    public static boolean isSafeEngineProxyUrl(String target) {
        return isSafeEngineProxyUrl(target, 0);
    }

    private static boolean isSafeEngineProxyUrl(String target, int depth) {
        if (depth > 2) {
            return false;
        }
        if (target == null) {
            return false;
        }
        String scheme = UrlX.schemeOf(target);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        String host = UrlX.hostOf(target);
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("127.0.0.1") || host.equals("localhost")) {
            int port = UrlX.portOf(target);
            if (port != enginePort) {
                return false;
            }
            if (!UrlX.pathOf(target).startsWith("/segment")) {
                return false;
            }
            String inner = queryParam(target, "url");
            return inner != null && !inner.isEmpty() && isSafeEngineProxyUrl(inner, depth + 1);
        }
        for (String suffix : ALLOWED_ENGINE_PROXY_SUFFIXES) {
            if (host.equals(suffix.substring(1)) || host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从另一个本地 ysp-live 引擎拉 m3u8 并把分片改写成走自己的 {@code /engine_proxy}。
     * <p>对应 Python {@code fetch_upstream_engine_playlist}。
     */
    public static String fetchUpstreamEnginePlaylist(String canonical, String host, String proto) {
        int port = enginePort;
        if (port <= 0) {
            return null;
        }
        String scheme = ("http".equals(proto) || "https".equals(proto)) ? proto : "http";
        String[] candidates = {
            "http://127.0.0.1:" + port + "/live/" + canonical + ".m3u8",
            "http://127.0.0.1:" + port + "/" + canonical + ".m3u8",
        };
        for (String u : candidates) {
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", UA);
                Resp resp = httpGet(u, headers, PLAYLIST_TIMEOUT_MS);
                if (resp.status != 200) {
                    continue;
                }
                String text = resp.text();
                if (!text.contains("#EXTM3U")) {
                    continue;
                }
                String basePrefix = scheme + "://" + host + "/engine_proxy?url=";
                List<String> outLines = new ArrayList<>();
                for (String line : text.split("\n", -1)) {
                    String lineStr = line.trim();
                    if (lineStr.isEmpty()) {
                        continue;
                    }
                    if (lineStr.startsWith("#")) {
                        outLines.add(lineStr);
                    } else {
                        String fullTarget = (lineStr.startsWith("http://") || lineStr.startsWith("https://"))
                                ? lineStr
                                : UrlX.urlJoin(u, lineStr);
                        outLines.add(basePrefix + UrlX.quote(fullTarget));
                    }
                }
                return String.join("\n", outLines) + "\n";
            } catch (Exception e) {
                Log.w(TAG, "上游引擎拉取失败 " + u + ": " + e.getMessage(), 30.0);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ HLS 不连续点

    /**
     * 在第一个分片前插入 {@code #EXT-X-DISCONTINUITY}（切换 m3u8 来源时用）。
     * <p>对应 Python {@code inject_hls_discontinuity}：已有标记则原样返回。
     */
    public static String injectHlsDiscontinuity(String pl) {
        if (pl == null || pl.contains("#EXT-X-DISCONTINUITY")) {
            return pl;
        }
        String[] lines = pl.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length + 2);
        boolean inserted = false;
        for (String line : lines) {
            String s = line.trim();
            if (!inserted && (s.startsWith("#EXT-X-PROGRAM-DATE-TIME:")
                    || s.startsWith("#EXTINF:")
                    || (!s.isEmpty() && !s.startsWith("#")))) {
                out.add("#EXT-X-DISCONTINUITY");
                inserted = true;
            }
            out.add(stripCr(line));
        }
        if (!inserted) {
            return pl;
        }
        String joined = String.join("\n", out);
        return pl.endsWith("\n") ? joined : joined;
    }
}
