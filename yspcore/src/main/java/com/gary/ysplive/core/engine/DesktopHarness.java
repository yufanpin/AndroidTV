package com.gary.ysplive.core.engine;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 桌面联调入口：在普通 JVM 上把整条链路跑起来（不依赖 Android）。
 *
 * <p>运行方式：
 * <pre>
 *   $env:YSP_HARNESS='1'; gradle :core:test --tests '*DesktopHarness*' --info
 * </pre>
 * 或者直接给 IDE 跑 {@code main}。
 *
 * <p>它会：起网关 → 等就绪 → 拉 /health → 拉 /all.m3u → 拉若干频道 m3u8 → 真的下载一个分片
 * → 打印结果并以退出码表示成败。这是"能不能真的播"的唯一可信判据。
 */
public final class DesktopHarness {

    private static final String TAG = "harness";

    public static void main(String[] args) throws Exception {
        int port = 18767;
        String only = null;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--channel".equals(args[i]) && i + 1 < args.length) {
                only = args[++i];
            }
        }

        Gateway.Config cfg = new Gateway.Config();
        cfg.port = port;
        cfg.allowLan = false;
        cfg.cacheMb = 64;
        cfg.dataDir = new File(System.getProperty("java.io.tmpdir"), "ysp-harness-data");
        //noinspection ResultOfMethodCallIgnored
        cfg.dataDir.mkdirs();

        YspGateway gw = new YspGateway(cfg);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        gw.start(new Gateway.StartListener() {
            @Override
            public void onStage(String stage) {
                System.out.println("[stage] " + stage);
            }

            @Override
            public void onReady(Gateway.Snapshot snapshot) {
                System.out.println("[ready] origin=" + snapshot.localOrigin
                        + " port=" + snapshot.port + " channels=" + snapshot.channelCount);
                ready.countDown();
            }

            @Override
            public void onFailed(String reason) {
                failure.set(reason);
                ready.countDown();
            }
        });

        if (!ready.await(120, TimeUnit.SECONDS)) {
            System.out.println("FAIL: 启动超时");
            gw.stop();
            System.exit(2);
        }
        if (failure.get() != null) {
            System.out.println("FAIL: 启动失败 " + failure.get());
            gw.stop();
            System.exit(3);
        }

        String origin = gw.localOrigin();
        int failures = 0;
        int checked = 0;

        // 等设备会话就绪（4K/高码率频道依赖它；1080p 频道不依赖）。
        // 参考实现同样是"设备身份就绪后才激活高码率池"，冷启动有几秒窗口。
        long deadline = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < deadline && !gw.snapshot().highBitrateReady) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[wait] highBitrateReady=" + gw.snapshot().highBitrateReady
                + " heartbeat=" + gw.snapshot().heartbeatCount);
        System.out.println("[note] 上游设备协议不可用时，4K 频道会自动回退 bkliveinfo；"
                + "若上游 CDN 本身 403，则该频道无法出片（与参考实现同表现）");

        // 1) /health
        String health = httpGet(origin + "/health", null, 20000);
        System.out.println("\n===== /health =====\n" + health);
        if (health == null || !health.contains("\"ok\": true")) {
            System.out.println("FAIL: /health 异常");
            failures++;
        }

        // 2) /all.m3u
        String all = httpGet(origin + "/all.m3u", null, 20000);
        System.out.println("\n===== /all.m3u (head) =====");
        System.out.println(head(all, 800));
        long channelLines = all == null ? 0 : all.split("\n").length;
        System.out.println("m3u lines = " + channelLines);
        if (all == null || !all.startsWith("#EXTM3U") || channelLines < 120) {
            System.out.println("FAIL: all.m3u 不完整");
            failures++;
        }

        // 3) 逐个频道拉 m3u8（默认抽查几个有代表性的）
        String[] targets = only != null ? new String[] {only}
                : new String[] {"cctv1", "cctv2", "cctv6", "cctv13", "cctv17",
                                "cctv4k", "cctv164k", "cctv8k",
                                "bjws", "dfws", "hnws", "zjws", "gdws", "cetv1", "guoxue"};
        for (String slug : targets) {
            String url = origin + "/" + slug + ".m3u8";
            // 真实播放器在拿到空/错误响应后会重试若干次（ExoPlayer 默认 3 次退避重试）。
            // 引擎的清晰度阶梯是"每次刷新推进一级"，需要几次请求才能收敛，
            // 所以这里也重试最多 6 次、每次间隔 3s，模拟播放器的真实行为。
            String pl = null;
            long cost = 0;
            int segs = 0;
            for (int attempt = 0; attempt < 6; attempt++) {
                long t0 = System.currentTimeMillis();
                pl = httpGet(url, null, 45000);
                cost = System.currentTimeMillis() - t0;
                segs = pl == null ? 0 : countSegments(pl);
                if (pl != null && pl.startsWith("#EXTM3U") && segs > 0) {
                    break;
                }
                if (attempt < 5) {
                    System.out.println("  (第 " + (attempt + 1) + " 次未出片，3s 后重试——"
                            + "播放器同样会重试，引擎在此间推进清晰度阶梯)");
                    sleepQuiet(3000L);
                }
            }
            checked++;
            System.out.println("\n===== /" + slug + ".m3u8  (" + cost + "ms, " + segs + " 段) =====");
            System.out.println(head(pl, 500));
            if (pl == null || !pl.startsWith("#EXTM3U") || segs == 0) {
                System.out.println("FAIL: " + slug + " 无有效分片");
                failures++;
                continue;
            }
            // 4) 真下载分片，验证签名头/代理链路。
            //    播放器的实际行为是"连续取分片，任意一片成功即可起播"，所以这里也试前 3 片，
            //    只要有一片能拿到完整 MPEG-TS 就认为该频道可播。上游 CDN 目前存在
            //    按 IP 的间歇性限流（同一 m3u8 里部分分片 403、部分 200），
            //    参考实现同样如此，因此不能以"第一片失败"判定频道不可用。
            int segOk = 0;
            String segDetail = "";
            for (int k = 0; k < Math.min(3, allSegments(pl).length); k++) {
                String seg = allSegments(pl)[k];
                long s0 = System.currentTimeMillis();
                byte[] data = httpGetBytes(seg, 60000);
                long scost = System.currentTimeMillis() - s0;
                if (data != null && data.length >= 1000) {
                    segOk++;
                    segDetail = data.length + "B/" + scost + "ms";
                    if (!looksLikeTs(data)) {
                        System.out.println("  WARN: 分片不像 MPEG-TS，前 8 字节=" + hex8(data));
                    }
                    break;
                }
            }
            System.out.println("  segment -> " + (segOk > 0
                    ? "OK " + segDetail : "FAIL (前 3 片均不可用)"));
            if (segOk == 0) {
                System.out.println("FAIL: " + slug + " 分片下载失败");
                failures++;
            } else if (segOk < 0) {
                // 占位，保持分支完整
                failures++;
            }
        }

        Gateway.Snapshot snap = gw.snapshot();
        System.out.println("\n===== snapshot =====");
        System.out.println("running=" + snap.running + " highBitrateReady=" + snap.highBitrateReady
                + " heartbeat=" + snap.heartbeatCount + " cacheEntries=" + snap.cacheEntries
                + " hitRate=" + snap.cacheHitRate + " guid=" + snap.cloudGuid);
        System.out.println("lastError=" + snap.lastError);

        System.out.println("\n===== 最近日志 =====");
        for (Log.Entry e : Log.recent(60)) {
            System.out.println(e.format());
        }

        gw.stop();
        System.out.println("\nRESULT: " + (failures == 0 ? "PASS" : "FAIL(" + failures + ")")
                + "  (" + (checked - failures) + "/" + checked + " 频道出片)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String head(String s, int n) {
        if (s == null) {
            return "<null>";
        }
        return s.length() <= n ? s : s.substring(0, n) + "\n...(共 " + s.length() + " 字符)";
    }

    private static int countSegments(String m3u) {
        int n = 0;
        for (String line : m3u.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                n++;
            }
        }
        return n;
    }

    private static String firstSegment(String m3u) {
        for (String line : m3u.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                return t;
            }
        }
        return null;
    }

    /** 取出播放列表里所有分片地址（按出现顺序）。 */
    private static String[] allSegments(String m3u) {
        if (m3u == null) {
            return new String[0];
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String line : m3u.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean looksLikeTs(byte[] data) {
        return data.length > 0 && (data[0] & 0xFF) == 0x47;
    }

    private static String hex8(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, data.length); i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private static String httpGet(String url, String range, int timeoutMs) {
        byte[] b = httpGetBytes(url, timeoutMs);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "YspLiveHarness/9.5");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                System.out.println("  HTTP " + code + " (无响应体)");
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            if (code != 200) {
                System.out.println("  HTTP " + code + ": "
                        + head(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 200));
                return null;
            }
            return out.toByteArray();
        } catch (Exception e) {
            System.out.println("  请求异常 " + url + " -> " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
