package com.gary.ysplive.core.migu;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.io.BufferedReader;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 咪咕直播内核：实现 {@link Gateway} 接口，替换 ysp 设备协议引擎。
 *
 * <p>内嵌轻量 HTTP 网关（裸 ServerSocket），提供：
 * <ul>
 *   <li>{@code /all.m3u} — 84 路频道清单，播放行自指本件 {@code /?id=N}</li>
 *   <li>{@code /?id=N} — 咪咕 playurl 签名解析 → 302 跟踪 → m3u8（分片直连 CDN）</li>
 *   <li>{@code /{slug}.m3u8} — app 侧按 slug 取流（兼容 {@code /cctv1.m3u8} 形式）</li>
 *   <li>{@code /health} {@code /diag} — Web 后台诊断</li>
 * </ul>
 *
 * <p>流地址缓存 900s（PHP 原版同值），命中直接返回，避免每 6s 轮询打上游。
 */
public final class MiguGateway implements Gateway {

    private static final String TAG = "migu";
    private static final long CACHE_TTL_MS = 900_000L;

    private final Config config;
    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong startedAtMs = new AtomicLong();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private volatile StartListener listener;
    private volatile ServerSocket serverSocket;
    private volatile int boundPort;
    private volatile String lastError = "";

    private static final class CacheEntry {
        final String url;
        final long ts;
        CacheEntry(String url, long ts) { this.url = url; this.ts = ts; }
    }

    public MiguGateway(Config config) {
        this.config = config == null ? new Config() : config;
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void start(final StartListener callback) {
        this.listener = callback;
        if (running.get()) { notifyReady(); return; }
        if (!starting.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { doStart(); }
        }, "migu-gateway-start");
        t.setDaemon(true);
        t.start();
    }

    private void doStart() {
        try {
            stage("正在启动咪咕内核…");
            String bind = config.allowLan ? "0.0.0.0" : "127.0.0.1";
            int port = config.port > 0 ? config.port : 8767;
            ServerSocket ss = null;
            for (int attempt = 0; attempt < 20 && ss == null; attempt++) {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(bind, port + attempt));
                } catch (IOException e) {
                    if (ss != null) { try { ss.close(); } catch (IOException ignored) {} ss = null; }
                }
            }
            if (ss == null) {
                fail("本地端口启动失败（" + bind + ":" + port + "）");
                return;
            }
            this.serverSocket = ss;
            this.boundPort = ss.getLocalPort();
            startedAtMs.set(System.currentTimeMillis());
            running.set(true);
            Log.i(TAG, "咪咕内核已启动 " + bind + ":" + boundPort + "，频道 " + MiguChannel.count() + " 路");

            Thread accept = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "migu-http-accept");
            accept.setDaemon(true);
            accept.start();

            stage("就绪");
            notifyReady();
        } catch (Throwable e) {
            fail("内核启动异常: " + e);
        }
    }

    private void acceptLoop() {
        while (running.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                final Socket s = serverSocket.accept();
                Thread worker = new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "migu-http-worker");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept 异常: " + e);
            }
        }
    }

    @Override public boolean isRunning() { return running.get(); }

    @Override
    public void stop() {
        running.set(false);
        starting.set(false);
        Socket sock = null;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
        synchronized (cache) { cache.clear(); }
    }

    @Override
    public Snapshot snapshot() {
        long up = running.get() ? (System.currentTimeMillis() - startedAtMs.get()) / 1000L : 0L;
        double rate = hits.get() + misses.get() > 0
                ? (double) hits.get() / (hits.get() + misses.get()) * 100.0 : 0.0;
        return new Snapshot(
                running.get(), boundPort,
                "http://127.0.0.1:" + (boundPort > 0 ? boundPort : config.port),
                false, MiguChannel.count(), up, cache.size(), rate,
                "MIGU", 0, lastError);
    }

    @Override public String getAllPlaylistUrl() {
        return running.get() ? "http://127.0.0.1:" + boundPort + "/all.m3u" : "";
    }

    @Override public String getChannelUrl(String slug) {
        if (!running.get()) return "";
        String id = normalizeId(slug);
        return id == null ? "" : "http://127.0.0.1:" + boundPort + "/" + id + ".m3u8";
    }

    @Override public String getTimeshiftUrl(String slug, long start, long end) { return null; }

    /** 供 app 的 Web 后台读取频道列表（兼容旧 YspGateway.engine().allChannels() 的语义）。 */
    public java.util.List<MiguChannel> channels() {
        return MiguChannel.all();
    }

    private void stage(String s) {
        StartListener l = listener;
        if (l != null) { try { l.onStage(s); } catch (Throwable ignored) {} }
    }

    private void notifyReady() {
        StartListener l = listener;
        if (l != null) { try { l.onReady(snapshot()); } catch (Throwable ignored) {} }
    }

    private void fail(String reason) {
        lastError = reason;
        running.set(false);
        starting.set(false);
        StartListener l = listener;
        if (l != null) { try { l.onFailed(reason); } catch (Throwable ignored) {} }
    }

    // ------------------------------------------------------------------ HTTP 处理

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = br.readLine();
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            while ((line = br.readLine()) != null && !line.isEmpty()) { /* 跳过请求头 */ }
            int q = path.indexOf('?');
            String route = q >= 0 ? path.substring(0, q) : path;
            String query = q >= 0 ? path.substring(q + 1) : "";

            byte[] body;
            String ctype;
            if ("/all.m3u".equals(route)) {
                body = allPlaylist().getBytes(StandardCharsets.UTF_8);
                ctype = "application/vnd.apple.mpegurl";
            } else if ("/health".equals(route)) {
                body = healthJson().getBytes(StandardCharsets.UTF_8);
                ctype = "application/json; charset=utf-8";
            } else if ("/diag".equals(route)) {
                body = diag().getBytes(StandardCharsets.UTF_8);
                ctype = "text/plain; charset=utf-8";
            } else if ("/".equals(route) || "/play".equals(route)) {
                String id = param(query, "id");
                MiguChannel ch = id == null ? null : MiguChannel.byId(id);
                if (ch == null) {
                    body = "404 - channel not found\n".getBytes(StandardCharsets.UTF_8);
                    ctype = "text/plain; charset=utf-8";
                    send(s, 404, ctype, body); return;
                }
                body = channelPlaylist(ch);
                ctype = body == null ? null : "application/vnd.apple.mpegurl";
                if (body == null) {
                    body = "502 - upstream unreachable\n".getBytes(StandardCharsets.UTF_8);
                    ctype = "text/plain; charset=utf-8";
                    send(s, 502, ctype, body); return;
                }
            } else if (route.endsWith(".m3u8")) {
                String id = normalizeId(route.substring(0, route.length() - 6));
                MiguChannel ch = id == null ? null : MiguChannel.byId(id);
                if (ch == null) {
                    body = "404 - channel not found\n".getBytes(StandardCharsets.UTF_8);
                    ctype = "text/plain; charset=utf-8";
                    send(s, 404, ctype, body); return;
                }
                body = channelPlaylist(ch);
                ctype = body == null ? null : "application/vnd.apple.mpegurl";
                if (body == null) {
                    body = "502 - upstream unreachable\n".getBytes(StandardCharsets.UTF_8);
                    ctype = "text/plain; charset=utf-8";
                    send(s, 502, ctype, body); return;
                }
            } else {
                body = "404 not found\n".getBytes(StandardCharsets.UTF_8);
                ctype = "text/plain; charset=utf-8";
                send(s, 404, ctype, body); return;
            }
            send(s, 200, ctype, body);
        } catch (Exception e) {
            Log.w(TAG, "HTTP 处理异常: " + e);
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void send(Socket s, int code, String ctype, byte[] body) {
        try {
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : "Error").append("\r\n");
            head.append("Content-Type: ").append(ctype == null ? "application/octet-stream" : ctype).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Cache-Control: no-store\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ 业务逻辑

    /** slug（数字 ID 或cctv1 形式）归一化为频道 ID。 */
    private String normalizeId(String slug) {
        if (slug == null) return null;
        if (MiguChannel.byId(slug) != null) return slug;
        // 兼容 app 传入 cctv1 之类：取尾部数字
        StringBuilder digits = new StringBuilder();
        for (int i = slug.length() - 1; i >= 0 && Character.isDigit(slug.charAt(i)); i--) {
            digits.insert(0, slug.charAt(i));
        }
        String d = digits.toString();
        return d.isEmpty() ? null : d;
    }

    private String allPlaylist() {
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        for (MiguChannel ch : MiguChannel.all()) {
            out.append("#EXTINF:-1 tvg-id=\"").append(ch.name).append("\" group-title=\"")
               .append(ch.group).append("\",").append(ch.name).append("\n");
            out.append("http://127.0.0.1:").append(boundPort).append("/?id=").append(ch.id).append("\n");
        }
        return out.toString();
    }

    /** 取频道 m3u8：缓存优先，失效重解析（900s TTL）。 */
    private byte[] channelPlaylist(MiguChannel ch) {
        String cached = cacheGet(ch.pid);
        if (cached != null) {
            try {
                MiguApi.Resp r = MiguApi.fetch(cached);
                String m3u8 = MiguApi.m3u8Body(r.body);
                if (m3u8 != null && m3u8.toLowerCase(Locale.ROOT).indexOf("#ext-x-stream-inf") < 0) {
                    return MiguApi.rewriteAbsolute(m3u8, cached).getBytes(StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) { }
        }

        String finalUrl = MiguApi.resolve(ch.pid);
        if (finalUrl == null) {
            Log.w(TAG, "[" + ch.name + "] 解析失败");
            return null;
        }
        try {
            MiguApi.Resp r = MiguApi.fetch(finalUrl);
            String m3u8 = MiguApi.m3u8Body(r.body);
            if (m3u8 == null) return null;

            String base = finalUrl;
            if (m3u8.toLowerCase(Locale.ROOT).contains("#ext-x-stream-inf")) {
                String best = MiguApi.pickBestVariant(m3u8, finalUrl);
                if (best != null && !best.isEmpty()) {
                    base = best;
                    MiguApi.Resp v = MiguApi.fetch(best);
                    String vm = MiguApi.m3u8Body(v.body);
                    if (vm != null) m3u8 = vm;
                }
            }
            cachePut(ch.pid, base);
            return MiguApi.rewriteAbsolute(m3u8, base).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "[" + ch.name + "] 取流异常: " + e);
            return null;
        }
    }

    private String cacheGet(String pid) {
        synchronized (cache) {
            CacheEntry e = cache.get(pid);
            if (e != null && System.currentTimeMillis() - e.ts < CACHE_TTL_MS) {
                hits.incrementAndGet();
                return e.url;
            }
            if (e != null) cache.remove(pid);
        }
        misses.incrementAndGet();
        return null;
    }

    private void cachePut(String pid, String url) {
        synchronized (cache) { cache.put(pid, new CacheEntry(url, System.currentTimeMillis())); }
    }

    private String healthJson() {
        Snapshot s = snapshot();
        return "{\"ok\":" + s.running + ",\"port\":" + s.port + ",\"channels\":" + s.channelCount
                + ",\"cache\":" + s.cacheEntries + ",\"hitRate\":" + String.format(Locale.ROOT, "%.1f", s.cacheHitRate)
                + ",\"kernel\":\"migu\"}";
    }

    private String diag() {
        Snapshot s = snapshot();
        return "MIGU kernel (develop202)\n"
                + "running=" + s.running + " port=" + s.port + "\n"
                + "channels=" + s.channelCount + " cache=" + s.cacheEntries + "\n"
                + "hits=" + hits.get() + " misses=" + misses.get() + "\n"
                + "uptime=" + s.uptimeMs / 1000 + "s\n"
                + (lastError.isEmpty() ? "" : "lastError=" + lastError + "\n");
    }

    private static String param(String query, String key) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
        }
        return null;
    }
}
