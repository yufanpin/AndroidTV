package com.gary.ysplive.core.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.gary.ysplive.core.util.Json;

/**
 * 极简 HTTP 客户端，语义对齐 Python 参考实现的 {@code HttpClient}（line 750-799）。
 *
 * <h2>必须遵守的几件事（不是随便写的）</h2>
 * <ul>
 *   <li><b>永远 {@link Proxy#NO_PROXY}</b>：Python 侧是 {@code ProxyHandler({})}。央视频域名
 *       走系统代理会直接 403，这是实测过的。</li>
 *   <li><b>默认发 {@code Accept-Encoding: identity}</b>：不让 JVM 做透明 gzip，否则
 *       {@code Content-Length} 与实际 body 长度对不上（本项目多处读 Content-Length）。
 *       若调用方显式传了 {@code Accept-Encoding}，尊重调用方；但只要响应带
 *       {@code Content-Encoding: gzip}，仍然会解压 —— 与 Python {@code _send} 完全一致。</li>
 *   <li><b>非 2xx 不抛异常</b>：返回带错误 body 的 {@link HttpResponse}（Python 同理）。
 *       只有传输层失败才抛 {@link IOException}，message 是 Python
 *       {@code _normalize_network_error} 的中文可用版本（connection refused / reset /
 *       timeout / failed to lookup address）。</li>
 *   <li><b>不带 Cookie</b>：Python 里虽然有 CookieJar，但本项目所有协议都是无状态头部签名，
 *       实测不依赖 Cookie；这里不实现 Cookie 以保持无状态（避免跨设备会话串味）。</li>
 * </ul>
 *
 * <h2>关于 {@code Host} 头（写在这里防止别人踩坑）</h2>
 * {@code HttpURLConnection} 把 {@code Host} 视为 restricted header，{@code setRequestProperty}
 * 会被**静默丢弃**，实际发出的是 URL 里的 host:port；即便设置
 * {@code sun.net.http.allowRestrictedHeaders=true} 也无效（桌面 JDK 实测）。因此本类**忽略**
 * 调用方传入的 {@code Host}；调用方若需要不同的 Host，请直接改 URL。
 *
 * <p>本类线程安全性：实例本身无 mutable 状态（只有不可变配置），可被多线程共享。
 */
public class Http implements Closeable {

    /** 单块读取缓冲大小（{@link #stream} 用）。 */
    private static final int CHUNK = 64 * 1024;

    private final int timeoutMs;
    private final boolean insecureTls;
    private final SSLSocketFactory insecureFactory;

    /**
     * 当前在飞的连接。{@link #close()} 会把它们全部 {@code disconnect}，
     * 从而让阻塞在 socket 读上的线程立刻抛异常返回。
     * <p>没有这个集合时 {@code close()} 只能是空操作——{@code HttpURLConnection} 的阻塞读
     * <b>不会</b>被 {@code Thread.interrupt()} 打断，会一直卡到读超时才返回，
     * 导致"停止引擎"要干等十几秒。这是实测结论，不是理论推演。
     */
    private final java.util.Set<HttpURLConnection> inFlight =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());
    private volatile boolean closed;

    private final HostnameVerifier acceptAllHostnames = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    public Http(int timeoutMs) {
        this(timeoutMs, false);
    }

    public Http(int timeoutMs, boolean insecureTls) {
        // Python 侧是 max(timeout, 0.1) 秒，这里用毫秒且至少给 500ms，避免 0 被解释成"无限"
        this.timeoutMs = Math.max(timeoutMs, 500);
        this.insecureTls = insecureTls;
        this.insecureFactory = insecureTls ? buildTrustAllFactory() : null;
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    /** GET，默认跟随重定向。 */
    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, headers, null, true);
    }

    /** POST 原始字节。 */
    public HttpResponse postBytes(String url, Map<String, String> headers, byte[] body) throws IOException {
        return request("POST", url, headers, body == null ? new byte[0] : body, true);
    }

    /**
     * POST JSON：序列化规则**必须**是 Python {@code json.dumps(separators=(',',':'),
     * ensure_ascii=False, sort_keys=True)}（见 {@link Json#stringify}），因为服务端会对 body 签名。
     */
    public HttpResponse postJson(String url, Map<String, String> headers, Object body) throws IOException {
        return postBytes(url, headers, Json.bytes(body));
    }

    /** POST 表单；编码规则见 {@link #formEncode(List)}（逐字节对齐 Python {@code form_encode}）。 */
    public HttpResponse postForm(String url, Map<String, String> headers,
                                 List<String[]> pairs) throws IOException {
        Map<String, String> h = headers == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(headers);
        if (!hasHeader(h, "Content-Type")) {
            // urllib 对带 data 且未显式指定 Content-Type 的请求会自动补这一条，这里对齐
            h.put("Content-Type", "application/x-www-form-urlencoded");
        }
        return postBytes(url, h, formEncode(pairs));
    }

    /**
     * 不指定是否跟随重定向的通用请求；{@code followRedirects=false} 时把 3xx 原样返回，
     * 供本地 /proxy.ts 透传 3xx 用。
     */
    public HttpResponse request(String method, String url, Map<String, String> headers,
                                byte[] body, boolean followRedirects) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(method, url, headers, body != null, followRedirects);
            if (body != null) {
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(body);
                    os.flush();
                } finally {
                    closeQuietly(os);
                }
            }
            int status = conn.getResponseCode();
            String reason = conn.getResponseMessage();
            Map<String, String> respHeaders = readHeaders(conn);
            byte[] respBody = readBody(conn, status, respHeaders);
            return new HttpResponse(status, reason == null ? "" : reason, respHeaders, respBody);
        } catch (IOException e) {
            throw new IOException(normalizeNetworkError(e), e);
        } finally {
            release(conn);
        }
    }

    /**
     * 流式 GET：不在内存里整体缓冲，按 64KB 块交给 {@code sink}。
     * {@code range} 非 null 时发 {@code Range: bytes=start-end}（{@code end<0} 表示到末尾）。
     * <p>状态码与响应头先交给 {@link StreamSink#onHeaders}，调用方据此决定是否继续读 body。
     * <p>默认跟随重定向；需要透传 3xx 时用
     * {@link #stream(String, Map, Range, boolean, StreamSink)}。
     */
    public void stream(String url, Map<String, String> headers, Range range, StreamSink sink)
            throws IOException {
        stream(url, headers, range, true, sink);
    }

    /**
     * 流式 GET 的完整版本：{@code followRedirects=false} 时不跟随，让调用方看到原始 3xx。
     * {@code range} 与调用方自带的 {@code Range} 头等价（自带优先）。
     */
    public void stream(String url, Map<String, String> headers, Range range,
                       boolean followRedirects, StreamSink sink) throws IOException {
        HttpURLConnection conn = null;
        try {
            Map<String, String> h = headers == null
                    ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(headers);
            if (range != null && !hasHeader(h, "Range")) {
                h.put("Range", range.headerValue());
            }
            conn = open("GET", url, h, false, followRedirects);
            int status = conn.getResponseCode();
            String reason = conn.getResponseMessage();
            Map<String, String> respHeaders = readHeaders(conn);
            if (reason == null) {
                reason = "";
            }
            sink.onHeaders(status, respHeaders);
            InputStream in = inputStreamFor(conn, status, respHeaders);
            if (in == null) {
                return;
            }
            try {
                byte[] buf = new byte[CHUNK];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.onBytes(buf, 0, n);
                }
            } finally {
                closeQuietly(in);
            }
        } catch (IOException e) {
            throw new IOException(normalizeNetworkError(e), e);
        } finally {
            release(conn);
        }
    }

    /**
     * 释放资源：把所有在飞的连接直接 {@code disconnect}。
     * <p>这是"停止引擎"能立刻返回的关键——阻塞中的 socket 读会被打断并抛
     * {@code IOException}，而不是干等到读超时。调用后本实例仍可继续使用（{@code closed}
     * 只影响当前这批连接，不锁死对象），这样 {@code stop()} 之后的重连不会失效。
     */
    @Override
    public void close() {
        closed = true;
        java.util.ArrayList<HttpURLConnection> snapshot =
                new java.util.ArrayList<HttpURLConnection>(inFlight);
        inFlight.clear();
        for (HttpURLConnection c : snapshot) {
            try {
                c.disconnect();
            } catch (Throwable ignored) {
                // disconnect 本身失败无所谓，目标只是解除阻塞
            }
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private HttpURLConnection open(String method, String url, Map<String, String> headers,
                                   boolean hasBody, boolean followRedirects) throws IOException {
        URL u;
        try {
            u = new URL(url);
        } catch (IOException e) {
            throw new IOException("network error: invalid URL " + url, e);
        }
        String scheme = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("network error: unsupported scheme " + u.getProtocol());
        }
        HttpURLConnection conn = (HttpURLConnection) u.openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod(method == null ? "GET" : method.toUpperCase(Locale.ROOT));
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setDoInput(true);
        if (hasBody) {
            conn.setDoOutput(true);
        }
        if (conn instanceof HttpsURLConnection && insecureTls) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            https.setSSLSocketFactory(insecureFactory);
            https.setHostnameVerifier(acceptAllHostnames);
        }

        boolean callerAcceptEncoding = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || k.isEmpty() || v == null) {
                    continue;
                }
                String lower = k.toLowerCase(Locale.ROOT);
                // Host：JVM restricted header，设了也会被静默丢弃，直接跳过（见类注释）
                if ("host".equals(lower)) {
                    continue;
                }
                // Content-Length：由 JVM 按我们写入的字节数计算，调用方给的值不可信
                if ("content-length".equals(lower)) {
                    continue;
                }
                // Connection：本类统一用 close（与 Python urllib 行为一致）
                if ("connection".equals(lower)) {
                    continue;
                }
                if ("accept-encoding".equals(lower)) {
                    callerAcceptEncoding = true;
                }
                conn.setRequestProperty(k, cleanHeaderValue(v));
            }
        }
        if (!callerAcceptEncoding) {
            // 禁掉 JVM 的透明 gzip：否则 Content-Length 与实际读到的字节数对不上
            conn.setRequestProperty("Accept-Encoding", "identity");
        }
        // Python urllib 默认就发 Connection: close，这里对齐（调用方传入的连接头被忽略）
        conn.setRequestProperty("Connection", "close");
        // 登记到在飞集合，close() 时才能把阻塞读打断
        inFlight.add(conn);
        return conn;
    }

    /** 请求结束（无论成败）时从在飞集合移除。 */
    private void release(HttpURLConnection conn) {
        if (conn == null) {
            return;
        }
        inFlight.remove(conn);
        try {
            conn.disconnect();
        } catch (Throwable ignored) {
            // 已经断开，忽略
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        if (headers == null) {
            return false;
        }
        for (String k : headers.keySet()) {
            if (k != null && k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉头值里的 CR/LF（对齐 Python {@code header_value_clean}）。 */
    private static String cleanHeaderValue(String v) {
        if (v.indexOf('\r') < 0 && v.indexOf('\n') < 0) {
            return v;
        }
        return v.replace("\r", "").replace("\n", "");
    }

    private static Map<String, String> readHeaders(HttpURLConnection conn) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, List<String>> raw;
        try {
            raw = conn.getHeaderFields();
        } catch (RuntimeException e) {
            return out;
        }
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            String k = e.getKey();
            if (k == null) {
                continue; // 状态行
            }
            List<String> vs = e.getValue();
            if (vs == null || vs.isEmpty()) {
                continue;
            }
            String kl = k.toLowerCase(Locale.ROOT);
            if (vs.size() == 1) {
                out.put(kl, vs.get(0));
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < vs.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(vs.get(i));
                }
                out.put(kl, sb.toString());
            }
        }
        return out;
    }

    private static byte[] readBody(HttpURLConnection conn, int status, Map<String, String> respHeaders)
            throws IOException {
        InputStream in;
        if (status >= 200 && status < 300) {
            in = conn.getInputStream();
        } else {
            in = conn.getErrorStream();
        }
        if (in == null) {
            return new byte[0];
        }
        byte[] raw;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            raw = bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
        // 对齐 Python：先整体读完，再看 content-encoding 是否含 gzip；解压失败就按原文返回
        String enc = respHeaders == null ? null : respHeaders.get("content-encoding");
        if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
            try {
                return gunzip(raw);
            } catch (IOException e) {
                return raw;
            }
        }
        return raw;
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        if (raw.length < 18) {
            // gzip 头 10 字节 + 至少 8 字节 trailer，短于此必然不是合法 gzip
            throw new IOException("not gzip");
        }
        GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw), CHUNK);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, raw.length * 3));
            byte[] buf = new byte[CHUNK];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            closeQuietly(gz);
        }
    }

    /** 流式路径拿响应流；{@code Content-Encoding: gzip} 时直接套 {@link GZIPInputStream}。 */
    private static InputStream inputStreamFor(HttpURLConnection conn, int status,
                                              Map<String, String> respHeaders) throws IOException {
        InputStream in;
        if (status >= 200 && status < 300) {
            in = conn.getInputStream();
        } else {
            in = conn.getErrorStream();
        }
        if (in == null) {
            return null;
        }
        String enc = respHeaders == null ? null : respHeaders.get("content-encoding");
        if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
            return new GZIPInputStream(in, CHUNK);
        }
        return in;
    }

    /** 把底层异常翻译成 Python {@code _normalize_network_error} 同款措辞。 */
    static String normalizeNetworkError(Throwable e) {
        Throwable t = e;
        // 常见包装：ConnectException 在 UnknownHostException 之外
        for (int depth = 0; t != null && depth < 8; depth++) {
            String name = t.getClass().getName();
            String msg = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof java.net.ConnectException
                    && (msg.contains("refused") || name.endsWith("ConnectException"))) {
                return "connection refused: " + msg;
            }
            if (t instanceof java.net.SocketException
                    && (msg.contains("reset") || msg.contains("Broken pipe")
                    || msg.contains("Connection reset"))) {
                return "connection reset by peer: " + msg;
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return "operation timed out: " + msg;
            }
            if (t instanceof java.net.UnknownHostException) {
                return "failed to lookup address: " + msg;
            }
            if (t instanceof java.net.NoRouteToHostException) {
                return "network is unreachable: " + msg;
            }
            t = t.getCause();
        }
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        return "network error: " + msg;
    }

    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 故意信任一切：insecureTls 仅用于自签/CDN 证书异常的排障场景
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 同上
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("failed to build insecure TLS context", e);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
                // 忽略：关闭失败不影响结果
            }
        }
    }

    // ---------------------------------------------------------------- 表单编码

    /**
     * 表单编码，**逐字节**对齐 Python {@code form_urlencode_value}（line 714-724）：
     * <ul>
     *   <li>ASCII 字母数字与 {@code * - . _} 原样输出；</li>
     *   <li>空格 → {@code +}；</li>
     *   <li>其余按 UTF-8 每个字节输出 {@code %XX}，**大写**十六进制。</li>
     * </ul>
     * 注意与 {@code URLEncoder} 的差别：{@code URLEncoder} 会把 {@code ~} 编掉、
     * 把 {@code *} 原样保留（这点一致），但 {@code URLEncoder} 对 {@code ! ' ( )} 的处理与本
     * 参考实现不同，所以不能直接用。
     */
    public static String formEncodeValue(String s) {
        if (s == null) {
            s = "";
        }
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(raw.length * 3);
        for (byte b : raw) {
            int v = b & 0xFF;
            char c = (char) v;
            if (isAsciiAlnum(v) || c == '*' || c == '-' || c == '.' || c == '_') {
                sb.append(c);
            } else if (v == 32) {
                sb.append('+');
            } else {
                sb.append('%');
                sb.append(HEX_UPPER[v >>> 4]);
                sb.append(HEX_UPPER[v & 0x0F]);
            }
        }
        return sb.toString();
    }

    private static boolean isAsciiAlnum(int v) {
        return (v >= '0' && v <= '9') || (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z');
    }

    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    /** 把 {@code [key, value]} 对编码成 {@code k1=v1&k2=v2} 的 UTF-8 字节。 */
    public static byte[] formEncode(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder(64);
        if (pairs != null) {
            boolean first = true;
            for (String[] p : pairs) {
                if (p == null || p.length < 2) {
                    continue;
                }
                if (!first) {
                    sb.append('&');
                }
                first = false;
                sb.append(formEncodeValue(p[0])).append('=').append(formEncodeValue(p[1]));
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 嵌套类型

    /** 字节区间；{@code end < 0} 表示"到资源末尾"。 */
    public static final class Range {
        public final long start;
        public final long end;

        public Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        /** 构造 {@code bytes=start-end}（end<0 时只发 {@code bytes=start-}）。 */
        public String headerValue() {
            StringBuilder sb = new StringBuilder("bytes=");
            if (start > 0) {
                sb.append(start).append('-');
            } else {
                sb.append("0-");
            }
            if (end >= 0) {
                sb.append(end);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return headerValue();
        }
    }

    /** 流式回调。 */
    public interface StreamSink {
        void onHeaders(int status, Map<String, String> headers) throws IOException;

        void onBytes(byte[] buf, int off, int len) throws IOException;
    }

    /**
     * 便捷方法：把响应体整体读成字符串（UTF-8）。供"响应一定是文本"的场景用，避免各处重复
     * {@code new String(resp.body, StandardCharsets.UTF_8)}。
     */
    public static String textOf(HttpResponse resp) {
        return resp == null ? "" : resp.text();
    }

    /** 空的只读 header map，便于调用方省掉 null 判断。 */
    public static Map<String, String> noHeaders() {
        return Collections.emptyMap();
    }

    /** 构造单条 header 的 map。 */
    public static Map<String, String> headers(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>(2);
        m.put(k, v);
        return m;
    }

    /** 便捷：把响应解析成 JSON 对象，失败返回 null（等价 Python {@code response_json} 的第三项）。 */
    public static Map<String, Object> responseJson(HttpResponse resp) {
        if (resp == null) {
            return null;
        }
        return Json.tryParseObject(resp.text());
    }

    /** 把便于阅读的 header list 转成 map（测试用）。 */
    static Map<String, String> toLowerMap(List<String[]> pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        if (pairs != null) {
            for (String[] p : pairs) {
                if (p != null && p.length >= 2 && p[0] != null) {
                    m.put(p[0].toLowerCase(Locale.ROOT), p[1]);
                }
            }
        }
        return m;
    }

    /** 供测试/诊断：非空 header 数。 */
    static List<String> headerNames(Map<String, String> headers) {
        return headers == null ? new ArrayList<String>() : new ArrayList<>(headers.keySet());
    }
}
