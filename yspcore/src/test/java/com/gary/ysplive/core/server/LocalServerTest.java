package com.gary.ysplive.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.gary.ysplive.core.channel.ChannelEngine;
import com.gary.ysplive.core.channel.Catchup;
import com.gary.ysplive.core.channel.Upstream;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link LocalServer} 的端到端测试：真起 {@link ServerSocket}（端口 0 由系统分配），
 * 用裸 socket 发请求，解析响应。
 * <p>{@link Resolver} 用假的（恒不就绪），所以不需要网络；{@code /proxy.ts} 的上游
 * 用本地 {@link ServerSocket} 假 CDN。
 */
public class LocalServerTest {

    private ChannelEngine engine;
    private LocalServer server;
    private int port;
    private FakeCdn cdn;

    /** 恒不就绪的假 Resolver：所有频道走 1080p / bk 路径。 */
    private static final class FakeResolver implements Resolver {
        final AtomicInteger resolveCalls = new AtomicInteger();

        @Override
        public void start() { }

        @Override
        public void stop() { }

        @Override
        public Status status() {
            return new Status(false, false, 0, 0, "", "");
        }

        @Override
        public ChannelEntry resolve(String slug) throws ResolutionException {
            resolveCalls.incrementAndGet();
            throw new ResolutionException("fake resolver: not ready");
        }

        @Override
        public String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug) {
            return body;
        }

        @Override
        public Map<String, String> playbackHeadersForTsUrl(String realUrl) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("User-Agent", "fake-agent/1.0");
            h.put("APPID", "app");
            h.put("APPSIGN", "sign");
            h.put("APPRANDOMSTR", "random");
            return h;
        }

        @Override
        public boolean isChannelInCooldown(String slug) {
            return true;
        }

        @Override
        public void clearChannelCooldown(String slug) { }
    }

    /** 本地假 CDN，返回一小段 TS 与 Range 响应。 */
    private static final class FakeCdn implements Runnable {
        private final ServerSocket socket;
        private final byte[] payload;
        private volatile boolean running = true;
        private Thread thread;
        volatile int lastRangeHeaderSeen = -1;

        FakeCdn(byte[] payload) throws IOException {
            this.payload = payload;
            this.socket = new ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        }

        int port() {
            return socket.getLocalPort();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        void start() {
            thread = new Thread(this, "fake-cdn");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }

        @Override
        public void run() {
            while (running) {
                try (Socket c = socket.accept()) {
                    InputStream in = c.getInputStream();
                    String head = readHead(in);
                    if (head == null) {
                        continue;
                    }
                    String range = null;
                    for (String line : head.split("\r\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0 && line.substring(0, colon).trim()
                                .equalsIgnoreCase("Range")) {
                            range = line.substring(colon + 1).trim();
                        }
                    }
                    OutputStream out = c.getOutputStream();
                    if (range != null) {
                        lastRangeHeaderSeen = 1;
                        int bytes = payload.length / 2;
                        StringBuilder sb = new StringBuilder();
                        sb.append("HTTP/1.1 206 Partial Content\r\n");
                        sb.append("Content-Type: video/mp2t\r\n");
                        sb.append("Content-Range: bytes 0-").append(bytes - 1)
                                .append('/').append(payload.length).append("\r\n");
                        sb.append("Accept-Ranges: bytes\r\n");
                        sb.append("Content-Length: ").append(bytes).append("\r\n");
                        sb.append("Connection: close\r\n\r\n");
                        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        out.write(payload, 0, bytes);
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("HTTP/1.1 200 OK\r\n");
                        sb.append("Content-Type: video/mp2t\r\n");
                        sb.append("Accept-Ranges: bytes\r\n");
                        sb.append("Content-Length: ").append(payload.length).append("\r\n");
                        sb.append("Connection: close\r\n\r\n");
                        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        out.write(payload);
                    }
                    out.flush();
                } catch (IOException e) {
                    if (running) {
                        // 测试环境里忽略
                        return;
                    }
                }
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        // 逐字节读到 "\r\n\r\n"；用 4 字节滑动窗口判断，命中后不吞掉后续 body
        int[] window = new int[4];
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return buf.size() == 0 ? null : buf.toString("UTF-8");
            }
            buf.write(b);
            window[0] = window[1];
            window[1] = window[2];
            window[2] = window[3];
            window[3] = b;
            if (window[0] == '\r' && window[1] == '\n'
                    && window[2] == '\r' && window[3] == '\n') {
                return buf.toString("UTF-8");
            }
            if (buf.size() > 65536) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ 生命周期

    @Before
    public void setUp() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        cdn = new FakeCdn(payload);
        cdn.start();

        Gateway.Config cfg = new Gateway.Config();
        cfg.port = 0;
        engine = new ChannelEngine(new FakeResolver(), cfg);
        // 测试必须完全离线：关闭真实网络路径（JCE / bkliveinfo / 上游引擎）
        engine.setNetworkEnabled(false);
        ServerConfig scfg = new ServerConfig();
        scfg.port = 0;
        scfg.workerThreads = 4;
        server = new LocalServer(engine, scfg);
        port = server.start();
        assertTrue("服务器未能绑定端口", port > 0);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (engine != null) {
            engine.shutdown();
        }
        if (cdn != null) {
            cdn.stop();
        }
    }

    // ------------------------------------------------------------------ HTTP 客户端工具

    /** 一个原始响应。 */
    private static final class Resp {
        int status;
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    private Resp get(int port, String rawTarget) throws IOException {
        return request(port, "GET", rawTarget, null);
    }

    private Resp request(int port, String method, String rawTarget,
                         Map<String, String> extraHeaders) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(20000);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(rawTarget).append(" HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(port).append("\r\n");
            req.append("Connection: close\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                raw.write(buf, 0, n);
            }
            return parseResponse(raw.toByteArray());
        }
    }

    private static Resp parseResponse(byte[] raw) {
        int headEnd = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                headEnd = i;
                break;
            }
        }
        Resp resp = new Resp();
        if (headEnd < 0) {
            resp.body = raw;
            return resp;
        }
        String head = new String(raw, 0, headEnd, StandardCharsets.UTF_8);
        for (String line : head.split("\r\n")) {
            if (line.startsWith("HTTP/")) {
                String[] parts = line.split(" ", 3);
                resp.status = Integer.parseInt(parts[1].trim());
            } else {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    resp.headers.put(line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim());
                }
            }
        }
        int bodyStart = headEnd + 4;
        resp.body = new byte[raw.length - bodyStart];
        System.arraycopy(raw, bodyStart, resp.body, 0, resp.body.length);
        return resp;
    }

    // ------------------------------------------------------------------ 用例

    @Test
    public void rootBanner() throws IOException {
        Resp r = get(port, "/");
        assertEquals(200, r.status);
        assertTrue(r.text().contains("ysp-live IPTV 直播网关运行中"));
        assertTrue(r.text().contains("端口: " + port));
        assertTrue(r.text().contains(AUTHOR_COMMUNITY_CONST()));
        assertTrue(r.text().contains("/all.m3u"));
        assertEquals("*", r.header("Access-Control-Allow-Origin"));
    }

    private static String AUTHOR_COMMUNITY_CONST() {
        return LocalServer.AUTHOR_COMMUNITY;
    }

    @Test
    public void faviconIsSvg() throws IOException {
        Resp r = get(port, "/favicon.ico");
        assertEquals(200, r.status);
        assertTrue(r.header("Content-Type").contains("image/svg+xml"));
        assertTrue(r.text().contains("<svg"));
    }

    @Test
    public void healthJsonShape() throws IOException {
        Resp r = get(port, "/health");
        assertEquals(200, r.status);
        assertTrue(r.header("Content-Type").contains("application/json"));
        String body = r.text();
        assertTrue(body.contains("\"ok\": true"));
        assertTrue(body.contains("\"version\": \"9.5.0\""));
        assertTrue(body.contains("\"author_community\": \"https://t.me/garysclub\""));
        assertTrue(body.contains("\"uptime_sec\":"));
        assertTrue(body.contains("\"engine\": {"));
        assertTrue(body.contains("\"ready\": false"));
        assertTrue(body.contains("\"session_valid\": false"));
        assertTrue(body.contains("\"heartbeat_count\": 0"));
        assertTrue(body.contains("\"guid\": \"\""));
        assertTrue(body.contains("\"channels\": 64"));
        assertTrue(body.contains("\"ts_cache\": {"));
        assertTrue(body.contains("\"hit_rate\": \"0.0%\""));
        assertTrue(body.contains("\"/<channel>.m3u8\""));
        assertTrue(body.contains("\"/proxy.ts\""));
        // 中文不转义（ensure_ascii=False）
        assertTrue(body.contains("其他渠道均非作者发布！"));
    }

    @Test
    public void allM3uHasSixtyFourChannels() throws IOException {
        Resp r = get(port, "/all.m3u");
        assertEquals(200, r.status);
        assertTrue(r.header("Content-Type").contains("application/vnd.apple.mpegurl"));
        String body = r.text();
        assertTrue(body.startsWith("#EXTM3U url-tvg="));
        assertTrue(body.contains("#EXT-X-APTV-PREVIEW: FALSE"));
        int extinf = body.split("#EXTINF:-1", -1).length - 1;
        assertEquals(64, extinf);
        assertTrue(body.contains("tvg-id=\"CCTV1\" tvg-name=\"CCTV-1 综合\""));
        assertTrue(body.contains("group-title=\"央视\""));
        assertTrue(body.contains("group-title=\"卫视\""));
        assertTrue(body.contains(
                "catchup=\"append\" catchup-days=\"7\" "
                + "catchup-source=\"?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}\""));
        assertTrue(body.contains("http://127.0.0.1:" + port + "/cctv1.m3u8"));
        assertTrue(body.endsWith("\n"));
    }

    @Test
    public void allM3uGroupFilter() throws IOException {
        Resp r = get(port, "/all.m3u?group=%E5%A4%AE%E8%A7%86");  // 央视
        assertEquals(200, r.status);
        int extinf = r.text().split("#EXTINF:-1", -1).length - 1;
        int expect = ChannelTable.group("央视").size();
        assertEquals(30, expect);
        assertEquals(expect, extinf);
        assertTrue(!r.text().contains("北京卫视"));
    }

    @Test
    public void allM3uOnly4kFilter() throws IOException {
        Resp r = get(port, "/all.m3u?k4=1");
        assertEquals(200, r.status);
        int extinf = r.text().split("#EXTINF:-1", -1).length - 1;
        assertEquals(3, extinf);
        assertTrue(r.text().contains("cctv4k"));
        assertTrue(r.text().contains("cctv8k"));
        assertTrue(r.text().contains("cctv164k"));
    }

    @Test
    public void diagReport() throws IOException {
        Resp r = get(port, "/diag");
        assertEquals(200, r.status);
        String body = r.text();
        assertTrue(body.contains("ysp-live v9.5 诊断报告 (运行时间:"));
        assertTrue(body.contains("社区：Gary's Club"));
        assertTrue(body.contains("高码率设备状态 (26 路共用): 未就绪"));
        assertTrue(body.contains("分片内存缓存:"));
        assertTrue(body.contains("--- 频道状态列表 ---"));
        assertTrue(body.contains("cctv1 (CCTV-1 综合)"));
        assertTrue(body.endsWith("\n"));
    }

    @Test
    public void unknownChannelIs404() throws IOException {
        Resp r = get(port, "/nosuchchannel.m3u8");
        assertEquals(404, r.status);
        assertEquals("未知频道\n", r.text());
    }

    @Test
    public void unknownPathIs404() throws IOException {
        Resp r = get(port, "/whatever");
        assertEquals(404, r.status);
        assertEquals("not found\n", r.text());
    }

    @Test
    public void proxyTsWithoutTokenIs400() throws IOException {
        Resp r = get(port, "/proxy.ts");
        assertEquals(400, r.status);
        assertEquals("missing proxy ts token\n", r.text());
    }

    @Test
    public void proxyTsWithBadTokenIs400() throws IOException {
        Resp r = get(port, "/proxy.ts?=bm90LWEtdXJs");
        assertEquals(400, r.status);
        assertTrue(r.text().contains("requires http or https URL"));
    }

    @Test
    public void proxyTsWithNonTsUrlIs400() throws IOException {
        String token = Upstream.encodeProxyTsUrl("http://example.test/a.m3u8");
        Resp r = get(port, "/proxy.ts?=" + token);
        assertEquals(400, r.status);
        assertTrue(r.text().contains("not a .ts segment"));
    }

    @Test
    public void proxyTsStreamsAndCaches() throws IOException {
        String realUrl = cdn.baseUrl() + "/seg/1.ts?token=abc";
        String token = Upstream.encodeProxyTsUrl(realUrl);

        Resp first = get(port, "/proxy.ts?=" + token);
        assertEquals(200, first.status);
        assertEquals(4096, first.body.length);
        assertEquals("video/mp2t", first.header("Content-Type"));
        assertEquals("MISS-UPSTREAM", first.header("X-Cache"));
        assertEquals("*", first.header("Access-Control-Allow-Origin"));
        assertEquals("public, max-age=300", first.header("Cache-Control"));

        Resp second = get(port, "/proxy.ts?=" + token);
        assertEquals(200, second.status);
        assertEquals("HIT-RAM", second.header("X-Cache"));
        assertEquals(4096, second.body.length);
        for (int i = 0; i < 64; i++) {
            assertEquals(first.body[i], second.body[i]);
        }
    }

    @Test
    public void proxyTsForwardsRange() throws IOException {
        String realUrl = cdn.baseUrl() + "/seg/2.ts";
        String token = Upstream.encodeProxyTsUrl(realUrl);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Range", "bytes=0-2047");
        Resp r = request(port, "GET", "/proxy.ts?=" + token, extra);
        assertEquals(206, r.status);
        assertEquals(2048, r.body.length);
        assertEquals("MISS-UPSTREAM", r.header("X-Cache"));
        assertNotNull(r.header("Content-Range"));
        assertEquals(1, cdn.lastRangeHeaderSeen);
    }

    @Test
    public void headRequestsHaveNoBody() throws IOException {
        Resp r = request(port, "HEAD", "/all.m3u", null);
        assertEquals(200, r.status);
        assertEquals(0, r.body.length);
        assertTrue(Integer.parseInt(r.header("Content-Length")) > 10000);
    }

    @Test
    public void concurrentRequestsAllSucceed() throws Exception {
        final int threads = 12;
        final int[] ok = new int[threads];
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Resp r = get(port, "/health");
                        if (r.status == 200) {
                            ok[idx] = 1;
                        }
                    } catch (IOException e) {
                        ok[idx] = 0;
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join(20000);
        }
        for (int i = 0; i < threads; i++) {
            assertEquals("第 " + i + " 个并发请求失败", 1, ok[i]);
        }
    }

    @Test
    public void channelRouteFallsBackTo503WhenNoData() throws IOException {
        // 假 resolver 恒不就绪、无分片、无 bk → 503（而不是崩溃或 404）
        Resp r = get(port, "/cctv1.m3u8");
        assertEquals(503, r.status);
        assertTrue(r.text().contains("频道 CCTV-1 综合 暂无数据"));
        assertTrue(r.text().contains("请稍后重试"));
    }

    @Test
    public void channelRouteAcceptsAlias() throws IOException {
        // 别名要能落到同一个频道（这里都应当是 503，因为无数据）
        Resp r = get(port, "/beijingws.m3u8");
        assertEquals(503, r.status);
        assertTrue(r.text().contains("北京卫视"));
    }

    @Test
    public void timeshiftQueryIsParsedOnChannelRoute() throws IOException {
        // 时移参数存在且频道支持时移，但 JCE 不可用 → 回退直播 → 503
        Catchup.Timespan ts = Catchup.parseQuery("playseek=20240101120000-20240101130000");
        assertNotNull(ts);
        Resp r = get(port, "/cctv1.m3u8?playseek=20240101120000-20240101130000");
        assertEquals(503, r.status);
    }

    @Test
    public void localOriginLooksLikeUrl() {
        String origin = server.localOrigin();
        assertTrue(origin.startsWith("http://127.0.0.1:"));
        assertTrue(origin.endsWith(":" + port));
    }

    @Test
    public void allM3uMatchesChannelCount() {
        assertEquals(64, ChannelTable.all().size());
    }
}
