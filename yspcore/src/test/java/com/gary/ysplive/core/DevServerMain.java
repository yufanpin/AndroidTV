package com.gary.ysplive.core;

import com.gary.ysplive.core.channel.Channel;
import com.gary.ysplive.core.channel.ChannelEngine;
import com.gary.ysplive.core.channel.Catchup;
import com.gary.ysplive.core.channel.Upstream;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.server.LocalServer;
import com.gary.ysplive.core.server.ServerConfig;
import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桌面 JVM 联调入口（不在常规 {@code :core:test} 里跑，需显式指定）。
 *
 * <pre>
 * # 只跑这个 harness：
 * gradle --project-dir C:\1\ysp-android :core:test --no-daemon --tests '*DevServerMain*'
 *
 * # 或者直接起服务（推荐，方便 curl）：
 * java -cp &lt;classes&gt; com.gary.ysplive.core.DevServerMain 8768
 * </pre>
 *
 * <p>它做的事：
 * <ol>
 *   <li>构造 {@link ChannelEngine}（设备协议解析器按 {@code --no-4k} 决定是否挂真实现）</li>
 *   <li>起 {@link LocalServer}（默认 8768，避免和 127.0.0.1:8767 的 Python 参考实现抢端口）</li>
 *   <li>打印 {@code /all.m3u} 与 {@code /cctv1.m3u8} 的真实响应，便于人工比对</li>
 * </ol>
 */
public final class DevServerMain {

    /** 默认端口：8767 留给 Python 参考实现。 */
    public static final int DEFAULT_PORT = 8768;

    private DevServerMain() { }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        boolean no4k = false;
        boolean lan = false;
        for (String a : args) {
            if (a == null) {
                continue;
            }
            String arg = a.trim();
            if (arg.equals("--no-4k")) {
                no4k = true;
            } else if (arg.equals("--lan")) {
                lan = true;
            } else if (arg.matches("\\d+")) {
                port = Integer.parseInt(arg);
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }
        Log.setMinLevel(Log.INFO);

        Gateway.Config config = new Gateway.Config();
        config.port = port;
        config.allowLan = lan;
        config.cacheMb = 200;
        config.disableHighBitrate = no4k;
        config.dataDir = new File(System.getProperty("java.io.tmpdir"), "ysp-live-dev");

        Resolver resolver = no4k ? new OffResolver() : new ReflectResolver();

        ChannelEngine engine = new ChannelEngine(resolver, config);
        ServerConfig scfg = new ServerConfig();
        scfg.port = port;
        scfg.allowLan = lan;
        scfg.cacheMb = 200;
        scfg.disableHighBitrate = no4k;

        LocalServer server = new LocalServer(engine, scfg);
        server.setPortCallback(new LocalServer.PortCallback() {
            @Override
            public void onPort(int actualPort) {
                System.out.println("[dev] 监听端口: " + actualPort);
            }
        });
        int actualPort = server.start();
        if (actualPort <= 0) {
            System.err.println("[dev] 服务启动失败: " + server.lastError());
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[dev] 正在停止…");
            }
        }));

        System.out.println("=========================================================");
        System.out.println("[dev] ysp-live Java 网关已启动");
        System.out.println("[dev] 全频道订阅: http://127.0.0.1:" + actualPort + "/all.m3u");
        System.out.println("[dev] 单频道:     http://127.0.0.1:" + actualPort + "/cctv1.m3u8");
        System.out.println("[dev] 健康度:     http://127.0.0.1:" + actualPort + "/health");
        System.out.println("[dev] 诊断:       http://127.0.0.1:" + actualPort + "/diag");
        System.out.println("=========================================================");

        dumpAllM3u(engine, actualPort);
        dumpChannel(engine, "cctv1", actualPort);

        // 常驻，直到进程被结束
        while (true) {
            Thread.sleep(60_000L);
        }
    }

    /** 打印 /all.m3u 的实际内容（与 LocalServer 走同一条代码路径）。 */
    public static String dumpAllM3u(ChannelEngine engine, int port) {
        LocalServer scratch = new LocalServer(engine, portConfig(port));
        String host = "127.0.0.1:" + port;
        String body = scratch.allM3u(host, "http", null, false);
        System.out.println("---------------- /all.m3u (前 6 行) ----------------");
        String[] lines = body.split("\n");
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            System.out.println(lines[i]);
        }
        System.out.println("... 共 " + (body.split("#EXTINF:-1", -1).length - 1) + " 路频道, "
                + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " 字节");
        return body;
    }

    /** 实拉一次 {@code /<slug>.m3u8}，打印真实播放列表。 */
    public static String dumpChannel(ChannelEngine engine, String slug, int port) {
        System.out.println("---------------- /" + slug + ".m3u8 ----------------");
        try {
            ChannelEngine.PlaylistResult r =
                    engine.playlistResult(slug, "127.0.0.1:" + port, "http", null);
            if (r == null) {
                System.out.println("(404 未知频道)");
                return null;
            }
            System.out.println("状态: " + r.status + "  X-SWR: " + r.headers.get("X-SWR"));
            String[] lines = r.body.split("\n");
            for (int i = 0; i < Math.min(14, lines.length); i++) {
                System.out.println(lines[i]);
            }
            if (lines.length > 14) {
                System.out.println("... 共 " + lines.length + " 行");
            }
            return r.body;
        } catch (RuntimeException e) {
            System.out.println("(异常: " + e + ")");
            return null;
        }
    }

    /** 打印时移解析结果，便于手工核对。 */
    public static void dumpCatchup(String query) {
        Catchup.Timespan ts = Catchup.parseQuery(query);
        System.out.println("[dev] catchup " + query + " -> " + ts);
    }

    private static ServerConfig portConfig(int port) {
        ServerConfig c = new ServerConfig();
        c.port = port;
        return c;
    }

    // ------------------------------------------------------------------ 假 Resolver

    /** 完全关闭高码率：全部频道走 1080p 备用通道。 */
    static final class OffResolver implements Resolver {
        private final AtomicInteger heartbeats = new AtomicInteger();

        @Override
        public void start() {
            heartbeats.set(1);
        }

        @Override
        public void stop() { }

        @Override
        public Status status() {
            return new Status(false, false, 0, heartbeats.get(), "", "");
        }

        @Override
        public ChannelEntry resolve(String slug) throws ResolutionException {
            throw new ResolutionException("device protocol disabled (--no-4k)");
        }

        @Override
        public String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug) {
            return body;
        }

        @Override
        public Map<String, String> playbackHeadersForTsUrl(String realUrl) {
            return new LinkedHashMap<>();
        }

        @Override
        public boolean isChannelInCooldown(String slug) {
            return true;
        }        @Override
        public void clearChannelCooldown(String slug) { }
    }

    /**
     * 反射式加载 device-dev 的真实 {@code DeviceResolver}。
     * <p>用反射是因为它在并行开发中可能还没落地；缺失时优雅退化为"仅 1080p"，
     * 而不是让整个 harness 起不来。
     */
    static final class ReflectResolver implements Resolver {
        private final Resolver delegate;

        ReflectResolver() {
            Resolver found = null;
            String[] candidates = {
                "com.gary.ysplive.core.device.DeviceResolver",
                "com.gary.ysplive.core.device.ResolverImpl",
            };
            for (String fqcn : candidates) {
                try {
                    Class<?> cls = Class.forName(fqcn);
                    found = (Resolver) cls.getDeclaredConstructor().newInstance();
                    System.out.println("[dev] 已加载设备协议解析器: " + fqcn);
                    break;
                } catch (Throwable ignored) {
                    // 继续试下一个
                }
            }
            if (found == null) {
                System.out.println("[dev] 未找到设备协议解析器实现，仅启用 1080p 备用通道");
            }
            this.delegate = found;
        }

        boolean present() {
            return delegate != null;
        }

        @Override
        public void start() {
            if (delegate != null) {
                delegate.start();
            }
        }

        @Override
        public void stop() {
            if (delegate != null) {
                delegate.stop();
            }
        }

        @Override
        public Status status() {
            if (delegate == null) {
                return new Status(false, false, 0, 0, "", "resolver not present");
            }
            return delegate.status();
        }

        @Override
        public ChannelEntry resolve(String slug) throws ResolutionException {
            if (delegate == null) {
                throw new ResolutionException("resolver not present");
            }
            return delegate.resolve(slug);
        }

        @Override
        public String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug) {
            if (delegate == null) {
                return body;
            }
            return delegate.proxyPlaylist(body, finalUrl, proxyOrigin, slug);
        }

        @Override
        public Map<String, String> playbackHeadersForTsUrl(String realUrl) throws ResolutionException {
            if (delegate == null) {
                return new LinkedHashMap<>();
            }
            return delegate.playbackHeadersForTsUrl(realUrl);
        }

        @Override
        public boolean isChannelInCooldown(String slug) {
            return delegate != null && delegate.isChannelInCooldown(slug);
        }

        @Override
        public void clearChannelCooldown(String slug) {
            if (delegate != null) {
                delegate.clearChannelCooldown(slug);
            }
        }
    }

    // ------------------------------------------------------------------ 自检（不联网）

    /** 冒烟自检：验证 M3U 与频道表一致，不依赖网络。 */
    public static boolean selfCheck() {
        Gateway.Config config = new Gateway.Config();
        config.port = DEFAULT_PORT;
        ChannelEngine engine = new ChannelEngine(new OffResolver(), config);
        LocalServer server = new LocalServer(engine, portConfig(DEFAULT_PORT));
        String m3u = server.allM3u("127.0.0.1:" + DEFAULT_PORT, "http", null, false);
        int count = m3u.split("#EXTINF:-1", -1).length - 1;
        if (count != ChannelTable.all().size()) {
            System.err.println("[dev] 自检失败: 频道数 " + count + " != " + ChannelTable.all().size());
            return false;
        }
        if (!m3u.endsWith("\n")) {
            System.err.println("[dev] 自检失败: M3U 未以换行结束");
            return false;
        }
        for (String slug : new String[] {"cctv1", "cctv5p", "hnws", "guoxue"}) {
            if (!m3u.contains("/" + slug + ".m3u8")) {
                System.err.println("[dev] 自检失败: 缺少频道 " + slug);
                return false;
            }
        }
        List<Channel> all = engine.allChannels();
        if (all.size() != 64) {
            System.err.println("[dev] 自检失败: 引擎频道数 " + all.size());
            return false;
        }
        engine.shutdown();
        System.out.println("[dev] 自检通过: " + count + " 路频道, " + m3u.length() + " 字符");
        return true;
    }

    /** {@code /proxy.ts} token 往返自检。 */
    public static boolean selfCheckProxyToken() {
        String url = "http://example.test/path/a.ts?x=1&y=2";
        String token = Upstream.encodeProxyTsUrl(url);
        String back = Upstream.decodeProxyTsUrl(token);
        if (!url.equals(back)) {
            System.err.println("[dev] token 往返失败: " + url + " -> " + back);
            return false;
        }
        System.out.println("[dev] token 往返通过: " + token);
        return true;
    }
}
