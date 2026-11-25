package com.gary.ysplive.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压力/浸泡测试驱动（桌面 JVM，需显式运行，不在 :core:test 里跑）。
 *
 * <pre>
 * java -cp &lt;main:test&gt; com.gary.ysplive.core.LoadProbe http://127.0.0.1:8768 \
 *      8 20 3 10000
 *   参数：baseUrl 并发客户端数 每客户端分片数 轮换频道数 总时长ms
 * </pre>
 *
 * <p>模拟真实"换台 + 追播"场景：N 个客户端各自反复
 * {@code GET /<channel>.m3u8} → 顺序拉取分片 {@code /proxy.ts}，
 * 同时后台每 ~10s 在 M 个频道间轮换，统计：
 * <ul>
 *   <li>成功/失败/各状态码计数</li>
 *   <li>分片缓存命中率（服务端 /health 的 ts_cache）</li>
 *   <li>JVM 堆 / RSS 变化</li>
 *   <li>连接泄漏：压测前后 {@code /health} 是否仍快速响应、是否有异常断连</li>
 * </ul>
 */
public final class LoadProbe {

    private LoadProbe() { }

    /** 单个客户端的统计。 */
    private static final class ClientStats {
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger fail = new AtomicInteger();
        final AtomicLong bytes = new AtomicLong();
        final Map<String, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<String>());

        void record(int status, int len) {
            statusCounts.computeIfAbsent(String.valueOf(status), k -> new AtomicInteger()).incrementAndGet();
            if (status >= 200 && status < 300) {
                ok.incrementAndGet();
                bytes.addAndGet(len);
            } else {
                fail.incrementAndGet();
            }
        }

        void recordError(String err) {
            fail.incrementAndGet();
            if (errors.size() < 5) {
                errors.add(err);
            }
        }
    }

    /** HTTP 响应。 */
    private static final class Resp {
        int status;
        byte[] body = new byte[0];
        final Map<String, String> headers = new java.util.LinkedHashMap<>();

        String header(String n) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) {
                    return e.getValue();
                }
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8768";
        int clients = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int segsPerClient = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int zapChannels = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        long durationMs = args.length > 4 ? Long.parseLong(args[4]) : 12_000L;

        String[] pool = {"cctv1", "cctv2", "cctv3", "cctv5", "cctv13", "bjws", "hnws"};
        List<String> chans = new ArrayList<>();
        for (int i = 0; i < Math.min(zapChannels, pool.length); i++) {
            chans.add(pool[i]);
        }

        System.out.println("=== LoadProbe ===");
        System.out.println("base=" + base + " clients=" + clients + " segs/client=" + segsPerClient
                + " channels=" + chans + " duration=" + durationMs + "ms");

        // 预热：先把每个频道的 m3u8 拉一次，让分片队列有数据
        System.out.println("--- 预热 " + chans.size() + " 个频道 ---");
        for (String ch : chans) {
            Resp r = get(base + "/" + ch + ".m3u8", null);
            System.out.println("  " + ch + " -> " + r.status + " (" + r.body.length + " bytes)");
        }

        Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(300);
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        long rssBefore = rssKb();
        CachedStats cacheBefore = fetchCacheStats(base);

        final AtomicInteger zapCounter = new AtomicInteger();
        final CountDownLatch startGate = new CountDownLatch(1);
        final long deadline = System.currentTimeMillis() + durationMs;

        // 换台线程：每 ~10s 轮换当前频道
        Thread zapper = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(10_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    int n = zapCounter.incrementAndGet();
                    System.out.println("[zap] 第 " + n + " 次换台 -> 全体客户端轮换频道");
                }
            }
        }, "zapper");
        zapper.setDaemon(true);
        zapper.start();

        // 客户端
        List<ClientStats> allStats = new ArrayList<>();
        Thread[] threads = new Thread[clients];
        for (int i = 0; i < clients; i++) {
            final int clientId = i;
            final ClientStats stats = new ClientStats();
            allStats.add(stats);
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        runClient(base, chans, segsPerClient, deadline, clientId, stats, zapCounter);
                    } catch (Exception e) {
                        stats.recordError("fatal: " + e);
                    }
                }
            }, "client-" + i);
            threads[i].start();
        }

        long wallStart = System.currentTimeMillis();
        startGate.countDown();
        for (Thread t : threads) {
            t.join(durationMs + 60_000L);
        }
        long wallMs = System.currentTimeMillis() - wallStart;
        zapper.interrupt();

        // 汇总
        int totalOk = 0;
        int totalFail = 0;
        long totalBytes = 0;
        Map<String, Integer> aggStatus = new java.util.TreeMap<>();
        List<String> sampleErrors = new ArrayList<>();
        for (int i = 0; i < allStats.size(); i++) {
            ClientStats s = allStats.get(i);
            totalOk += s.ok.get();
            totalFail += s.fail.get();
            totalBytes += s.bytes.get();
            for (Map.Entry<String, AtomicInteger> e : s.statusCounts.entrySet()) {
                aggStatus.merge(e.getKey(), e.getValue().get(), Integer::sum);
            }
            for (String err : s.errors) {
                if (sampleErrors.size() < 8) {
                    sampleErrors.add("c" + i + ": " + err);
                }
            }
        }

        System.gc();
        Thread.sleep(500);
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long rssAfter = rssKb();
        CachedStats cacheAfter = fetchCacheStats(base);

        // 压测后健康检查（验证没有连接/线程泄漏导致服务僵死）
        long healthStart = System.currentTimeMillis();
        Resp health = get(base + "/health", null);
        long healthMs = System.currentTimeMillis() - healthStart;
        long m3u8Start = System.currentTimeMillis();
        Resp m3u8 = get(base + "/cctv1.m3u8", null);
        long m3u8Ms = System.currentTimeMillis() - m3u8Start;

        System.out.println();
        System.out.println("================ 结果 ================");
        System.out.println("挂钟时间:            " + wallMs + " ms");
        System.out.println("请求总数:            " + (totalOk + totalFail));
        System.out.println("成功(2xx):           " + totalOk);
        System.out.println("失败:                " + totalFail);
        System.out.println("吞吐:                "
                + String.format(java.util.Locale.ROOT, "%.1f", (totalOk + totalFail) * 1000.0 / Math.max(1, wallMs))
                + " req/s");
        System.out.println("传输字节:            " + totalBytes + " ("
                + String.format(java.util.Locale.ROOT, "%.1f", totalBytes / (1024.0 * 1024.0)) + " MB, "
                + String.format(java.util.Locale.ROOT, "%.1f", totalBytes * 1000.0
                        / Math.max(1, wallMs) / (1024.0 * 1024.0)) + " MB/s)");
        System.out.println("换台次数:            " + zapCounter.get());
        System.out.println("状态码分布:          " + aggStatus);
        if (!sampleErrors.isEmpty()) {
            System.out.println("错误样本:");
            for (String e : sampleErrors) {
                System.out.println("  " + e);
            }
        }
        System.out.println();
        System.out.println("--- 分片缓存 ---");
        System.out.println("压测前:  " + cacheBefore);
        System.out.println("压测后:  " + cacheAfter);
        System.out.println();
        System.out.println("--- 内存 ---");
        System.out.println("堆:  " + mb(heapBefore) + " -> " + mb(heapAfter)
                + "  (Δ " + mb(heapAfter - heapBefore) + ")");
        System.out.println("RSS: " + (rssBefore < 0 ? "n/a" : (rssBefore / 1024 + " MB")) + " -> "
                + (rssAfter < 0 ? "n/a" : (rssAfter / 1024 + " MB")));
        System.out.println();
        System.out.println("--- 压测后存活检查（连接/线程泄漏）---");
        System.out.println("/health     -> " + health.status + " in " + healthMs + " ms");
        System.out.println("/cctv1.m3u8 -> " + m3u8.status + " in " + m3u8Ms + " ms ("
                + m3u8.body.length + " bytes)");
        Thread.sleep(1500);
        System.out.println("服务端 JVM 线程数:   " + Thread.activeCount() + " (本进程)");
        System.out.println("======================================");

        boolean healthy = health.status == 200 && healthMs < 3000
                && m3u8.status == 200 && m3u8Ms < 30_000
                && totalOk > 0;
        System.out.println(healthy ? "VERDICT: PASS" : "VERDICT: FAIL");
        if (!healthy) {
            System.exit(1);
        }
    }

    /** 单个客户端：反复 拉 m3u8 → 顺序取分片，直到超时。 */
    private static void runClient(String base, List<String> channels, int segsPerClient,
                                  long deadline, int clientId, ClientStats stats,
                                  AtomicInteger zapCounter) {
        int cursor = clientId % channels.size();
        int mismatchedZap = -1;
        while (System.currentTimeMillis() < deadline) {
            // 换台了就换频道
            int zap = zapCounter.get();
            if (zap != mismatchedZap) {
                mismatchedZap = zap;
                cursor = (cursor + 1) % channels.size();
            }
            String slug = channels.get(cursor);
            Resp pl = get(base + "/" + slug + ".m3u8", null);
            stats.record(pl.status, pl.body.length);
            if (pl.status != 200) {
                stats.recordError(slug + ".m3u8 status=" + pl.status);
                sleep(500);
                continue;
            }
            List<String> segUrls = new ArrayList<>();
            for (String line : new String(pl.body, StandardCharsets.UTF_8).split("\n")) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    segUrls.add(s);
                }
            }
            if (segUrls.isEmpty()) {
                stats.recordError(slug + " 无分片");
                sleep(500);
                continue;
            }
            // 从随机位置开始取，模拟播放器追播
            int start = clientId % segUrls.size();
            for (int k = 0; k < segsPerClient && System.currentTimeMillis() < deadline; k++) {
                String segUrl = segUrls.get((start + k) % segUrls.size());
                String token = base64UrlNoPad(segUrl);
                Resp seg = get(base + "/proxy.ts?=" + token, null);
                stats.record(seg.status, seg.body.length);
                if (seg.status != 200 && seg.status != 206) {
                    stats.recordError("seg status=" + seg.status);
                }
                if (k % 3 == 0) {
                    sleep(120);   // 模拟播放节奏
                }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private static Resp get(String url, Map<String, String> extraHeaders) {
        String scheme = url.startsWith("https://") ? "https" : "http";
        String rest = url.substring(scheme.length() + 3);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        int colon = authority.lastIndexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        int port = colon < 0 ? 80 : Integer.parseInt(authority.substring(colon + 1));

        Resp resp = new Resp();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(60_000);
            s.setTcpNoDelay(true);
            StringBuilder sb = new StringBuilder();
            sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(authority).append("\r\n");
            sb.append("Connection: close\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            sb.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream raw = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                raw.write(buf, 0, n);
            }
            byte[] all = raw.toByteArray();
            int headEnd = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    headEnd = i;
                    break;
                }
            }
            if (headEnd < 0) {
                resp.status = -1;
                return resp;
            }
            String head = new String(all, 0, headEnd, StandardCharsets.UTF_8);
            for (String line : head.split("\r\n")) {
                if (line.startsWith("HTTP/")) {
                    String[] p = line.split(" ", 3);
                    resp.status = Integer.parseInt(p[1].trim());
                } else {
                    int c = line.indexOf(':');
                    if (c > 0) {
                        resp.headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
                    }
                }
            }
            resp.body = new byte[all.length - headEnd - 4];
            System.arraycopy(all, headEnd + 4, resp.body, 0, resp.body.length);
        } catch (IOException e) {
            resp.status = -2;
        }
        return resp;
    }

    /** 读取服务端 /health 里的 ts_cache 段。 */
    private static CachedStats fetchCacheStats(String base) {
        Resp r = get(base + "/health", null);
        if (r.status != 200) {
            return new CachedStats(-1, -1, -1, -1, "n/a");
        }
        String text = new String(r.body, StandardCharsets.UTF_8);
        int i = text.indexOf("\"ts_cache\"");
        if (i < 0) {
            return new CachedStats(-1, -1, -1, -1, "n/a");
        }
        String sub = text.substring(i, Math.min(text.length(), i + 400));
        return new CachedStats(
                extractInt(sub, "count"),
                extractInt(sub, "hits"),
                extractInt(sub, "misses"),
                extractInt(sub, "size_mb_milli"),
                extractStr(sub, "hit_rate"));
    }

    private static final class CachedStats {
        final int count;
        final int hits;
        final int misses;
        final int sizeMbMilli;
        final String hitRate;

        CachedStats(int count, int hits, int misses, int sizeMbMilli, String hitRate) {
            this.count = count;
            this.hits = hits;
            this.misses = misses;
            this.sizeMbMilli = sizeMbMilli;
            this.hitRate = hitRate;
        }

        @Override
        public String toString() {
            return "count=" + count + ", hits=" + hits + ", misses=" + misses
                    + ", hit_rate=" + hitRate;
        }
    }

    private static int extractInt(String text, String key) {
        String needle = "\"" + key + "\":";
        int i = text.indexOf(needle);
        if (i < 0) {
            return -1;
        }
        int p = i + needle.length();
        while (p < text.length() && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
            p++;
        }
        int start = p;
        while (p < text.length() && (Character.isDigit(text.charAt(p)) || text.charAt(p) == '.'
                || text.charAt(p) == '-')) {
            p++;
        }
        try {
            return (int) Double.parseDouble(text.substring(start, p));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String extractStr(String text, String key) {
        String needle = "\"" + key + "\":";
        int i = text.indexOf(needle);
        if (i < 0) {
            return "?";
        }
        int q1 = text.indexOf('"', i + needle.length());
        if (q1 < 0) {
            return "?";
        }
        int q2 = text.indexOf('"', q1 + 1);
        return q2 < 0 ? "?" : text.substring(q1 + 1, q2);
    }

    private static String base64UrlNoPad(String s) {
        String std = java.util.Base64.getEncoder().encodeToString(
                s.getBytes(StandardCharsets.UTF_8));
        return std.replace('+', '-').replace('/', '_').replace("=", "");
    }

    /**
     * 采样进程占用的实际内存（KB）。
     * <p>用反射而不是直接 import {@code java.lang.management} / {@code com.sun.management}：
     * 这两个包不在 {@code android.jar} 的 bootclasspath 上，直接引用会让整个
     * {@code :core:test} 编译失败。反射在桌面 JVM 上运行期照常可用，在 Android 上
     * 拿不到就返回 -1，不影响任何逻辑。
     */
    private static long rssKb() {
        try {
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Object os = factory.getMethod("getOperatingSystemMXBean").invoke(null);
            if (os == null) {
                return -1;
            }
            Class<?> sunIface;
            try {
                sunIface = Class.forName("com.sun.management.OperatingSystemMXBean");
            } catch (ClassNotFoundException e) {
                return -1;
            }
            if (!sunIface.isInstance(os)) {
                return -1;
            }
            Object committed = sunIface.getMethod("getCommittedVirtualMemorySize").invoke(os);
            if (committed instanceof Long && ((Long) committed) >= 0L) {
                return ((Long) committed) / 1024L;
            }
        } catch (Throwable ignored) {
            // 非 HotSpot 环境，或 Android 运行期没有这些类
        }
        return -1;
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
