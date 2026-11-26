package com.gary.ysplive.core.server;

import com.gary.ysplive.core.channel.Catchup;
import com.gary.ysplive.core.channel.Channel;
import com.gary.ysplive.core.channel.ChannelEngine;
import com.gary.ysplive.core.channel.TsSegmentCache;
import com.gary.ysplive.core.channel.Upstream;
import com.gary.ysplive.core.channel.UrlX;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.Resolver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简本地 HTTP/1.1 网关。
 * <p>对应 Python 参考实现的 {@code Handler}（2968-3318）+ {@code ThreadingHTTPServer}：
 * 用裸 {@link ServerSocket} 而不是 {@code com.sun.net.httpserver}（Android 上没有）。
 *
 * <p>路由与 Python 完全一致：
 * <ul>
 *   <li>{@code /}、{@code /index.html} → 纯文本状态横幅</li>
 *   <li>{@code /favicon.ico} → SVG 电视图标</li>
 *   <li>{@code /health} → JSON 健康快照</li>
 *   <li>{@code /all.m3u} → 64 路频道总表（含 7 天时移属性）</li>
 *   <li>{@code /diag} → 诊断文本报告</li>
 *   <li>{@code /<slug>.m3u8} → 单频道播放列表（时移 → 设备协议 → SWR → 1080p → 上游 → 503）</li>
 *   <li>{@code /proxy.ts?=<token>} → TS 分片代理（走内存缓存）</li>
 *   <li>{@code /engine_proxy?url=...} → 另一路引擎分片的中转</li>
 * </ul>
 *
 * <p>支持 {@code HEAD}、keep-alive 与并发请求；默认只绑 {@code 127.0.0.1}。
 * 首选端口被占用时自动向后找一个空闲端口，并通过 {@link PortCallback} 上报实际端口。
 */
public final class LocalServer {

    private static final String TAG = "server";

    /** 与 Python 一致的版本号 / 作者信息。 */
    public static final String VERSION = "9.5.0";
    public static final String AUTHOR_COMMUNITY = "https://t.me/garysclub";
    public static final String AUTHOR_CHANNEL = "https://t.me/garysclubchannel";
    public static final String NOTICE = "其他渠道均非作者发布！";

    /** 单次分片响应上限（Python 是 35MB 硬闸）。 */
    private static final int MAX_PROXY_BYTES = 35 * 1024 * 1024;

    /** 端口确定后的回调（可能因为原端口被占用而与请求值不同）。 */
    public interface PortCallback {
        void onPort(int port);
    }

    /** 启动失败回调。 */
    public interface ErrorCallback {
        void onError(String message, Throwable cause);
    }

    private final ServerConfig config;
    private final ChannelEngine engine;

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workers;
    private volatile Thread acceptThread;
    private volatile boolean running;
    private volatile int boundPort = -1;
    private volatile PortCallback portCallback;
    private volatile ErrorCallback errorCallback;
    private volatile String lastError = "";

    public LocalServer(ChannelEngine engine, ServerConfig config) {
        if (engine == null) {
            throw new IllegalArgumentException("engine == null");
        }
        this.engine = engine;
        this.config = config == null ? new ServerConfig() : config;
    }

    // ------------------------------------------------------------------ 生命周期

    public void setPortCallback(PortCallback callback) {
        this.portCallback = callback;
    }

    public void setErrorCallback(ErrorCallback callback) {
        this.errorCallback = callback;
    }

    /**
     * 启动服务器（阻塞到端口绑定完成，之后在后台线程 accept）。
     *
     * @return 实际监听端口；失败返回 -1
     */
    public synchronized int start() {
        if (running) {
            return boundPort;
        }
        String bind = config.resolvedBindAddress();
        InetAddress address;
        try {
            address = InetAddress.getByName(bind);
        } catch (IOException e) {
            fail("无法解析绑定地址 " + bind, e);
            return -1;
        }
        ServerSocket socket = null;
        int port = -1;
        int span = Math.max(1, config.portSearchSpan);
        for (int i = 0; i < span; i++) {
            int candidate = config.port + i;
            try {
                socket = new ServerSocket(candidate, 128, address);
                // 端口 0 时由系统分配，实际端口必须从 socket 读回来
                port = socket.getLocalPort();
                break;
            } catch (IOException e) {
                if (i == 0) {
                    Log.i(TAG, "端口 " + candidate + " 不可用，尝试向后查找…");
                }
                socket = null;
            }
            if (config.port == 0) {
                // 端口 0 由系统分配，失败说明地址本身有问题
                break;
            }
        }
        if (socket == null) {
            fail("没有可用端口（从 " + config.port + " 起尝试 " + span + " 个）", null);
            return -1;
        }
        try {
            socket.setReuseAddress(true);
            socket.setSoTimeout(0);
        } catch (SocketException ignored) {
            // 部分平台不支持，忽略
        }
        this.serverSocket = socket;
        this.boundPort = port;
        this.running = true;
        this.workers = Executors.newFixedThreadPool(config.resolvedWorkerThreads(),
                new NamedThreadFactory("ysp-http-"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "ysp-accept");
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
        Log.i(TAG, "本地网关已启动: http://" + bind + ":" + port
                + " (缓存 " + config.cacheMb + "MB, 工作线程 " + config.resolvedWorkerThreads() + ")");
        PortCallback cb = portCallback;
        if (cb != null) {
            try {
                cb.onPort(port);
            } catch (RuntimeException e) {
                Log.w(TAG, "端口回调异常: " + e.getMessage());
            }
        }
        return port;
    }

    /** 停止服务器并释放端口。 */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败无所谓
            }
        }
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
        Log.i(TAG, "本地网关已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public int boundPort() {
        return boundPort;
    }

    /** 形如 {@code http://127.0.0.1:8767}。 */
    public String localOrigin() {
        int port = boundPort;
        if (port < 0) {
            return "";
        }
        String host = "0.0.0.0".equals(config.resolvedBindAddress())
                ? "127.0.0.1" : config.resolvedBindAddress();
        return "http://" + host + ":" + port;
    }

    public String lastError() {
        return lastError;
    }

    private void fail(String message, Throwable cause) {
        this.lastError = message;
        Log.e(TAG, message + (cause == null ? "" : (": " + cause.getMessage())));
        ErrorCallback cb = errorCallback;
        if (cb != null) {
            try {
                cb.onError(message, cause);
            } catch (RuntimeException ignored) {
                // 回调异常不影响主流程
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = serverSocket;
            if (socket == null) {
                return;
            }
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                // 单次 accept 失败（例如瞬时 fd 耗尽）不应终止整个服务
                Log.w(TAG, "accept 失败: " + e.getMessage(), 5.0);
                continue;
            }
            ExecutorService pool = workers;
            if (pool == null) {
                closeQuietly(client);
                return;
            }
            try {
                pool.execute(new Connection(client));
            } catch (RejectedExecutionException e) {
                closeQuietly(client);
            }
        }
    }

    // ------------------------------------------------------------------ 连接处理

    /** 一条客户端连接的会话（keep-alive 多请求）。 */
    private final class Connection implements Runnable {

        private final Socket socket;

        Connection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(60_000);
                InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);
                int served = 0;
                while (running && served < config.maxRequestsPerConnection) {
                    Request req = readRequest(in);
                    if (req == null) {
                        return;
                    }
                    served++;
                    boolean keepAlive = req.keepAlive && served < config.maxRequestsPerConnection;
                    handle(req, out, keepAlive);
                    out.flush();
                    if (!keepAlive) {
                        return;
                    }
                }
            } catch (SocketException e) {
                // 客户端提前断开，正常现象
            } catch (IOException e) {
                Log.d(TAG, "连接异常: " + e.getMessage());
            } catch (RuntimeException e) {
                Log.w(TAG, "处理请求异常: " + e);
            } finally {
                closeQuietly(socket);
            }
        }

        private void handle(Request req, OutputStream out, boolean keepAlive) throws IOException {
            String path = req.path;
            String rawPath = req.rawTarget;
            String query = UrlX.queryOf(rawPath);
            Catchup.Timespan catchup = Catchup.parseQuery(query);
            String host = req.header("host");
            if (host == null || host.trim().isEmpty()) {
                host = "localhost:" + boundPort;
            }
            String proto = firstProto(req.header("x-forwarded-proto"));

            // CORS 预检：桌面端 WebView(hls.js) / 浏览器播放器会先发 OPTIONS。
            // 不处理会让所有跨 origin 请求在预检阶段就被浏览器拦掉。
            if ("OPTIONS".equals(req.method)) {
                String reqHeaders = req.header("access-control-request-headers");
                if (reqHeaders == null || reqHeaders.trim().isEmpty()) {
                    reqHeaders = "Range, Content-Type, Accept";
                }
                Map<String, String> cors = new LinkedHashMap<>();
                cors.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
                cors.put("Access-Control-Allow-Headers", reqHeaders);
                cors.put("Access-Control-Max-Age", "86400");
                writeResponse(out, 204, "text/plain", new byte[0], false, keepAlive, cors, false);
                return;
            }

            if (path.equals("/") || path.equals("/index.html")) {
                sendText(out, req, 200, rootBanner(host), "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            if (path.equals("/favicon.ico")) {
                String fav = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
                        + "<text y=\".9em\" font-size=\"90\">\uD83D\uDCFA</text></svg>";
                sendText(out, req, 200, fav, "image/svg+xml", keepAlive, null);
                return;
            }
            if (path.equals("/health")) {
                sendText(out, req, 200, healthJson(), "application/json; charset=utf-8", keepAlive, null);
                return;
            }
            if (path.equals("/all.m3u")) {
                Map<String, List<String>> qs = Catchup.parseQs(query);
                String group = Catchup.first(qs, "group");
                String k4 = Catchup.first(qs, "k4");
                boolean only4k = k4 != null && ("1".equals(k4.toLowerCase(Locale.ROOT))
                        || "true".equals(k4.toLowerCase(Locale.ROOT))
                        || "yes".equals(k4.toLowerCase(Locale.ROOT)));
                sendText(out, req, 200, allM3u(host, proto, group, only4k),
                        "application/vnd.apple.mpegurl", keepAlive, null);
                return;
            }
            if (path.equals("/diag")) {
                sendText(out, req, 200, diagReport(), "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            if (path.equals("/proxy.ts") || path.equals("/proxy")) {
                handleProxyTs(req, out, rawPath, keepAlive);
                return;
            }
            if (path.equals("/engine_proxy")) {
                handleEngineProxy(req, out, query, keepAlive);
                return;
            }
            String slug = channelSlugFromPath(path);
            if (slug != null) {
                handleChannel(req, out, slug, host, proto, catchup, keepAlive);
                return;
            }
            sendText(out, req, 404, "not found\n", "text/plain; charset=utf-8", keepAlive, null);
        }

        // -------------------------------------------------------------- 各路由

        private void handleChannel(Request req, OutputStream out, String slug, String host,
                                   String proto, Catchup.Timespan catchup, boolean keepAlive)
                throws IOException {
            Channel ch = engine.channelFor(slug);
            if (ch == null) {
                sendText(out, req, 404, "未知频道\n", "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            ChannelEngine.PlaylistResult result =
                    engine.playlistResult(ch.slug(), host, proto, catchup);
            if (result == null) {
                sendText(out, req, 404, "未知频道\n", "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            sendText(out, req, result.status, result.body, result.contentType, keepAlive,
                    result.headers);
        }

        private void handleProxyTs(Request req, OutputStream out, String rawPath, boolean keepAlive)
                throws IOException {
            String token = Upstream.proxyTsToken(rawPath);
            if (token == null || token.isEmpty()) {
                sendText(out, req, 400, "missing proxy ts token\n",
                        "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            String realUrl;
            try {
                realUrl = Upstream.decodeProxyTsUrl(token);
            } catch (IllegalArgumentException e) {
                sendText(out, req, 400, e.getMessage() + "\n",
                        "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            String rangeHeader = req.header("range");
            ChannelEngine.SegmentResult seg;
            try {
                seg = engine.fetchSegmentEx(realUrl, rangeHeader);
            } catch (IllegalArgumentException e) {
                sendText(out, req, 400, e.getMessage() + "\n",
                        "text/plain; charset=utf-8", keepAlive, null);
                return;
            } catch (IOException e) {
                sendText(out, req, 502, "Upstream CDN error: " + e.getMessage() + "\n",
                        "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            if (seg.data.length > MAX_PROXY_BYTES) {
                sendText(out, req, 502, "upstream segment too large\n",
                        "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            Map<String, String> headers = new LinkedHashMap<>(seg.headers);
            headers.put("X-Cache", seg.cacheState);
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Cache-Control", "public, max-age=300");
            String ctype = Upstream.headerOrNull(seg.headers, "content-type");
            if (ctype == null || ctype.isEmpty()) {
                ctype = "video/mp2t";
            }
            writeResponse(out, seg.status, ctype, seg.data, req.headOnly, keepAlive, headers, true);
        }

        private void handleEngineProxy(Request req, OutputStream out, String query, boolean keepAlive)
                throws IOException {
            Map<String, List<String>> qs = Catchup.parseQs(query);
            String target = Catchup.first(qs, "url");
            if (target == null || target.isEmpty()) {
                sendText(out, req, 400, "missing url\n", "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            if (!Upstream.isSafeEngineProxyUrl(target)) {
                sendText(out, req, 403, "forbidden url\n", "text/plain; charset=utf-8", keepAlive, null);
                return;
            }
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", Upstream.UA);
                headers.put("Accept", "*/*");
                Upstream.Resp resp = Upstream.httpGet(target, headers, 10_000);
                if (resp.body.length > MAX_PROXY_BYTES) {
                    sendText(out, req, 502, "upstream segment too large\n",
                            "text/plain; charset=utf-8", keepAlive, null);
                    return;
                }
                String ctype = resp.header("content-type");
                if (ctype == null || ctype.isEmpty()) {
                    ctype = "video/mp2t";
                }
                writeResponse(out, resp.status, ctype, resp.body, req.headOnly, keepAlive, null, true);
            } catch (IOException e) {
                sendText(out, req, 502, "engine proxy error: " + e.getMessage() + "\n",
                        "text/plain; charset=utf-8", keepAlive, null);
            }
        }
    }

    // ------------------------------------------------------------------ 路由内容

    private String rootBanner(String host) {
        int port = boundPort;
        StringBuilder sb = new StringBuilder(512);
        sb.append("====================================================\n");
        sb.append("ysp-live IPTV 直播网关运行中 (端口: ").append(port).append(")\n");
        sb.append("\uD83D\uDCE2 官方发布社区: ").append(AUTHOR_COMMUNITY).append('\n');
        sb.append("\uD83D\uDCE2 官方发布频道: ").append(AUTHOR_CHANNEL).append('\n');
        sb.append("\u26A0\uFE0F 严正声明: 其他任何渠道均非作者发布！\n");
        sb.append("====================================================\n\n");
        sb.append("全频道订阅: http://").append(host).append("/all.m3u\n");
        sb.append("系统健康度: http://").append(host).append("/health\n");
        sb.append("实时诊断日志: http://").append(host).append("/diag\n");
        return sb.toString();
    }

    /** {@code /health}：字段与形状对齐 Python {@code json.dumps(..., indent=2)}。 */
    private String healthJson() {
        Resolver resolver = engine.resolver();
        boolean sessionValid = false;
        int heartbeatCount = 0;
        String guid = "";
        int activeSlots = 0;
        if (resolver != null) {
            try {
                Resolver.Status st = resolver.status();
                if (st != null) {
                    sessionValid = st.sessionValid;
                    heartbeatCount = st.heartbeatCount;
                    guid = st.cloudGuid;
                    activeSlots = st.activeDeviceSlots;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "读取 resolver 状态失败: " + e.getMessage(), 30.0);
            }
        }
        Map<String, Object> engineMap = new LinkedHashMap<>();
        engineMap.put("ready", engine.resolverReady());
        engineMap.put("active_device_slots", activeSlots);
        engineMap.put("session_valid", sessionValid);
        engineMap.put("heartbeat_count", heartbeatCount);
        engineMap.put("guid", guid);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ok", Boolean.TRUE);
        root.put("version", VERSION);
        root.put("author_community", AUTHOR_COMMUNITY);
        root.put("author_channel", AUTHOR_CHANNEL);
        root.put("notice", NOTICE);
        root.put("uptime_sec", engine.uptimeSec());
        root.put("engine", engineMap);
        root.put("channels", ChannelTable.all().size());
        root.put("ts_cache", engine.tsCache().stats().toMap());
        List<Object> routes = new ArrayList<>();
        routes.add("/");
        routes.add("/all.m3u");
        routes.add("/diag");
        routes.add("/health");
        routes.add("/<channel>.m3u8");
        routes.add("/proxy.ts");
        root.put("routes", routes);
        return MiniJson.pretty(root);
    }

    /** {@code /all.m3u}：64 路频道总表。 */
    public String allM3u(String host, String proto, String targetGroup, boolean only4k) {
        String scheme = ("https".equals(proto)) ? "https" : "http";
        List<String> lines = new ArrayList<>(ChannelTable.all().size() * 2 + 4);
        lines.add("#EXTM3U url-tvg=\"https://epg.zsdc.eu.org/t.xml.gz,"
                + "https://epg.pw/xmltv/epg_CN.xml.gz\" x-tvg-url=\"https://epg.zsdc.eu.org/t.xml.gz\"");
        lines.add("#EXT-X-APTV-PREVIEW: FALSE");
        lines.add("#EXT-X-APTV-LATENCY: FALSE");
        lines.add("#EXT-X-APTV-LOGO: FALSE");
        for (ChannelSpec spec : ChannelTable.all()) {
            String slug = spec.slug;
            String name = spec.name;
            String grp = spec.group;
            String logo = spec.logo;
            String tid = spec.tvgId;
            if (targetGroup != null && !targetGroup.isEmpty() && !grp.contains(targetGroup)) {
                continue;
            }
            if (only4k && !ChannelTable.isTrue4k(slug)) {
                continue;
            }
            StringBuilder attrs = new StringBuilder(256);
            attrs.append(" tvg-id=\"").append(tid).append('"')
                    .append(" tvg-name=\"").append(name).append('"')
                    .append(" tvg-logo=\"").append(logo).append('"')
                    .append(" group-title=\"").append(grp).append('"');
            if (ChannelTable.supportsTimeshift(slug)) {
                attrs.append(" catchup=\"append\" catchup-days=\"7\"")
                        .append(" catchup-source=\"?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}\"");
            }
            lines.add("#EXTINF:-1" + attrs + "," + name);
            lines.add(scheme + "://" + host + "/" + slug + ".m3u8");
        }
        return String.join("\n", lines) + "\n";
    }

    /** {@code /diag}：诊断文本报告。 */
    public String diagReport() {
        List<String> info = new ArrayList<>(128);
        long nowSec = System.currentTimeMillis() / 1000L;
        info.add("====================================================");
        info.add("ysp-live v9.5 诊断报告 (运行时间: " + engine.uptimeSec() + "s)");
        info.add("\uD83D\uDCE2 首发地址");
        info.add("社区：Gary's Club (" + AUTHOR_COMMUNITY + ")");
        info.add("频道：Gary's Club Channel (" + AUTHOR_CHANNEL + ")");
        info.add("\u26A0\uFE0F 其他任何分发渠道均非作者本人！");
        info.add("====================================================");

        boolean ready = engine.resolverReady();
        info.add("单设备并发池状态: " + (ready ? "就绪" : "未就绪(1080p自动回退)"));

        Resolver resolver = engine.resolver();
        boolean sessionValid = false;
        int heartbeat = 0;
        String guid = "无";
        int activeSlots = 0;
        if (resolver != null) {
            try {
                Resolver.Status st = resolver.status();
                if (st != null) {
                    sessionValid = st.sessionValid;
                    heartbeat = st.heartbeatCount;
                    guid = st.cloudGuid.isEmpty() ? "无" : st.cloudGuid;
                    activeSlots = st.activeDeviceSlots;
                }
            } catch (RuntimeException e) {
                info.add("读取设备状态失败: " + e.getMessage());
            }
        }
        int backendCount = 0;
        for (ChannelSpec spec : ChannelTable.all()) {
            if (ChannelTable.isBackendChannel(spec.slug)) {
                backendCount++;
            }
        }
        info.add("高码率设备状态 (" + backendCount + " 路共用): "
                + (sessionValid ? "有效" : "未就绪")
                + ", 占用=" + activeSlots + "/" + config.linksPerDevice
                + ", 心跳=" + heartbeat + ", GUID=" + guid);

        TsSegmentCache.Stats stats = engine.tsCache().stats();
        info.add("分片内存缓存: 条目=" + stats.count + ", 占用=" + formatMb(stats.sizeMb)
                + "MB, 命中率=" + stats.hitRateText()
                + " (命中:" + stats.hits + "/未中:" + stats.misses + ")");
        info.add("--- 频道状态列表 ---");
        for (ChannelSpec spec : ChannelTable.all()) {
            String slug = spec.slug;
            Channel ch = engine.channelFor(slug);
            String age;
            if (ch != null && ch.lastOk() > 0) {
                age = (int) (nowSec - ch.lastOk()) + "s前";
            } else {
                age = "从未拉取";
            }
            String modeStr;
            if (ChannelTable.isBackendChannel(slug) && ready) {
                modeStr = "4K/高码率";
            } else if (ch != null) {
                modeStr = ch.mode();
            } else {
                modeStr = "idle";
            }
            String cooldownStr = "";
            if (resolver != null) {
                try {
                    if (resolver.isChannelInCooldown(slug)) {
                        cooldownStr = " [高码冷却中]";
                    }
                } catch (RuntimeException ignored) {
                    // 冷却查询失败不影响诊断输出
                }
            }
            String errMsg = ch == null ? "无" : ch.lastError();
            info.add(slug + " (" + spec.name + ") 模式=" + modeStr + cooldownStr
                    + " 上次刷新=" + age + " 错误=" + (errMsg.isEmpty() ? "无" : errMsg));
        }
        List<Log.Entry> recent = Log.recent(30);
        if (!recent.isEmpty()) {
            info.add("--- 最近运行事件 (最新 30 条) ---");
            for (Log.Entry e : recent) {
                info.add(e.format());
            }
        }
        return String.join("\n", info) + "\n";
    }

    private static String formatMb(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v) + ".0";
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ 请求解析

    /** 一个已解析的请求。 */
    private static final class Request {
        String method = "GET";
        String rawTarget = "/";
        String path = "/";
        String version = "HTTP/1.1";
        boolean headOnly;
        boolean keepAlive = true;
        final Map<String, String> headers = new LinkedHashMap<>();

        String header(String name) {
            return Upstream.headerOrNull(headers, name);
        }
    }

    /**
     * 读一个请求；对端正常关闭返回 {@code null}。
     * <p>只支持 {@code Content-Length} 请求体（本项目所有请求都没有 body，
     * 但会读掉以保持连接干净）。
     */
    private Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in, config.maxHeaderBytes);
        if (requestLine == null) {
            return null;
        }
        // 跳过请求前的空行
        int guard = 0;
        while (requestLine.isEmpty() && guard++ < 8) {
            requestLine = readLine(in, config.maxHeaderBytes);
            if (requestLine == null) {
                return null;
            }
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            return null;
        }
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.ROOT);
        req.rawTarget = parts[1];
        req.version = parts.length > 2 ? parts[2] : "HTTP/1.1";
        req.headOnly = "HEAD".equals(req.method);

        int total = 0;
        while (true) {
            String line = readLine(in, config.maxHeaderBytes);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            total += line.length();
            if (total > config.maxHeaderBytes) {
                throw new IOException("request headers too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            req.headers.put(name, value);
        }

        // 目标可能是绝对 URI（代理风格），只取 path+query
        String target = req.rawTarget;
        if (target.startsWith("http://") || target.startsWith("https://")) {
            int schemeEnd = target.indexOf("://") + 3;
            int slash = target.indexOf('/', schemeEnd);
            target = slash < 0 ? "/" : target.substring(slash);
        }
        int q = target.indexOf('?');
        req.path = q < 0 ? target : target.substring(0, q);
        if (req.path.isEmpty()) {
            req.path = "/";
        }

        String connection = req.header("connection");
        if (connection != null) {
            String c = connection.toLowerCase(Locale.ROOT);
            if (c.contains("close")) {
                req.keepAlive = false;
            } else if (c.contains("keep-alive")) {
                req.keepAlive = true;
            }
        } else {
            req.keepAlive = "HTTP/1.1".equalsIgnoreCase(req.version);
        }

        // 读掉请求体（本服务没有需要 body 的路由）
        String cl = req.header("content-length");
        if (cl != null) {
            long len = parseLongOr(cl, 0L);
            long remaining = len;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    break;
                }
                remaining -= skipped;
            }
        }
        return req;
    }

    /** 读一行（LF 或 CRLF 结尾），EOF 返回 {@code null}。 */
    private static String readLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.UTF_8);
            }
            buf.write(b);
            if (buf.size() > maxBytes) {
                throw new IOException("line too long");
            }
        }
        if (buf.size() == 0) {
            return null;
        }
        return buf.toString("UTF-8");
    }

    /** 从 {@code /cctv1.m3u8} 取 slug；不是频道路由返回 {@code null}。 */
    static String channelSlugFromPath(String path) {
        if (path == null || path.length() < 8 || !path.startsWith("/")) {
            return null;
        }
        if (!path.endsWith(".m3u8")) {
            return null;
        }
        String raw = path.substring(1, path.length() - 5);
        if (raw.isEmpty()) {
            return null;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return raw;
    }

    // ------------------------------------------------------------------ 响应写出

    private void sendText(OutputStream out, Request req, int status, String body,
                          String contentType, boolean keepAlive, Map<String, String> extra)
            throws IOException {
        byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        writeResponse(out, status, contentType, data, req.headOnly, keepAlive, extra, false);
    }

    /**
     * 写一个完整响应。
     *
     * @param rawHeaders true 时不做"统一头"处理（{@code /proxy.ts} 需要原样透传上游头）
     */
    private void writeResponse(OutputStream out, int status, String contentType, byte[] data,
                               boolean headOnly, boolean keepAlive,
                               Map<String, String> extra, boolean rawHeaders) throws IOException {
        StringBuilder head = new StringBuilder(512);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        head.append("Server: ysp-live/").append(VERSION).append("\r\n");
        head.append("Date: ").append(httpDate()).append("\r\n");
        if (!rawHeaders) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(data.length).append("\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Cache-Control: no-cache, no-store, max-age=0\r\n");
        } else {
            // rawHeaders 会透传上游响应头，但仍必须补 CORS：
            // 桌面端 WebView(hls.js) 与浏览器播放器是从别的 origin 拉这些资源的，
            // 缺 Access-Control-Allow-Origin 会直接 manifestLoadError / 分片被拦。
            boolean hasCors = false;
            if (extra != null) {
                for (String hk : extra.keySet()) {
                    if (hk != null && hk.equalsIgnoreCase("Access-Control-Allow-Origin")) {
                        hasCors = true;
                        break;
                    }
                }
            }
            if (!hasCors) {
                head.append("Access-Control-Allow-Origin: *\r\n");
            }
        }
        boolean hasContentLength = false;
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String lower = e.getKey().toLowerCase(Locale.ROOT);
                if (rawHeaders && lower.equals("content-type")) {
                    head.append("Content-Type: ").append(e.getValue()).append("\r\n");
                    hasContentLength = hasContentLength || lower.equals("content-length");
                    continue;
                }
                if (lower.equals("content-length")) {
                    hasContentLength = true;
                }
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        if (rawHeaders && !hasContentLength) {
            head.append("Content-Length: ").append(data.length).append("\r\n");
        }
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (!headOnly && data.length > 0) {
            out.write(data);
        }
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 206:
                return "Partial Content";
            case 301:
                return "Moved Permanently";
            case 302:
                return "Found";
            case 304:
                return "Not Modified";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 416:
                return "Requested Range Not Satisfiable";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            default:
                return "Status";
        }
    }

    private static final ThreadLocal<SimpleDateFormat> HTTP_DATE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            return f;
        }
    };

    private static String httpDate() {
        return HTTP_DATE.get().format(new Date());
    }

    private static String firstProto(String value) {
        if (value == null) {
            return "http";
        }
        String first = value.split(",")[0].trim().toLowerCase(Locale.ROOT);
        return first.isEmpty() ? "http" : first;
    }

    private static long parseLongOr(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓
        }
    }

    /** 带名字的线程工厂，方便 /diag 与线上排查。 */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
