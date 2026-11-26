package com.gary.ysplive.core.engine;

import com.gary.ysplive.core.channel.ChannelEngine;
import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.server.LocalServer;
import com.gary.ysplive.core.server.ServerConfig;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.Resolver;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认 {@link Gateway} 实现：把设备协议解析器、频道刷新引擎、本地 HTTP 网关装配起来。
 *
 * <p>App 层通过反射构造本类（见 app 模块的 {@code GatewayProvider}），因此
 * <b>构造函数签名不可更改</b>：{@link #YspGateway(Config)}。
 *
 * <p>生命周期：
 * <pre>
 *   new YspGateway(config) --&gt; start(listener) --&gt; onReady(snapshot) --&gt; ... --&gt; stop()
 * </pre>
 * {@link #start} 立即返回，真正的启动在后台线程完成；启动完成后本地端口即可服务。
 */
public final class YspGateway implements Gateway {

    private static final String TAG = "gateway";

    private final Config config;

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private volatile Resolver resolver;
    private volatile ChannelEngine engine;
    private volatile LocalServer server;
    private volatile boolean running;
    private volatile long startedAtMs;
    private volatile String lastError = "";
    private volatile StartListener listener;

    public YspGateway(Config config) {
        this.config = config == null ? new Config() : config;
    }

    // ------------------------------------------------------------------ 启动

    @Override
    public void start(final StartListener callback) {
        this.listener = callback;
        if (running) {
            notifyReady();
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            // 已经在启动中，回调会在启动完成时统一触发
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                doStart();
            }
        }, "ysp-gateway-start");
        t.setDaemon(true);
        t.start();
    }

    private void doStart() {
        try {
            stage("正在创建解析器…");
            Resolver dev = ResolverFactory.create(config);
            this.resolver = dev;

            stage("正在初始化频道引擎…");
            ChannelEngine eng = new ChannelEngine(dev, config);
            this.engine = eng;

            ServerConfig sc = new ServerConfig();
            sc.port = config.port;
            sc.allowLan = config.allowLan;
            sc.cacheMb = config.cacheMb;
            sc.linksPerDevice = config.linksPerDevice;
            sc.zapDebounceSec = config.zapDebounceSec;
            sc.disableHighBitrate = config.disableHighBitrate;
            sc.only4kHighBitrate = config.only4kHighBitrate;
            sc.dataDir = config.dataDir;
            sc.timeoutSec = config.timeoutSec;
            LocalServer srv = new LocalServer(eng, sc);
            srv.setPortCallback(new LocalServer.PortCallback() {
                @Override
                public void onPort(int port) {
                    Log.i(TAG, "本地网关监听端口: " + port);
                }
            });
            srv.setErrorCallback(new LocalServer.ErrorCallback() {
                @Override
                public void onError(String message, Throwable cause) {
                    lastError = message == null ? "" : message;
                    Log.e(TAG, "网关错误: " + lastError);
                }
            });
            this.server = srv;

            stage("正在启动本地网关…");
            int bound = srv.start();
            if (bound <= 0) {
                fail("本地网关端口启动失败: " + srv.lastError());
                return;
            }

            stage(config.disableHighBitrate
                    ? "1080p 模式（已关闭高码率）"
                    : "正在注册设备身份，准备高码率通道…");
            // 解析器启动是异步的；不阻塞端口服务，让界面立刻可用（先出 1080p）
            if (dev != null) {
                try {
                    dev.start();
                } catch (RuntimeException e) {
                    Log.w(TAG, "高码率解析器启动异常（自动降级 1080p）: " + describe(e));
                }
            }

            this.startedAtMs = System.currentTimeMillis();
            this.running = true;
            Log.i(TAG, "引擎就绪: " + localOrigin() + "/all.m3u  频道数="
                    + ChannelTable.all().size());
            notifyReady();

            // 设备协议是慢启动的，起个轻量线程在就绪后再通知一次界面刷新状态
            Thread watchdog = new Thread(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.currentTimeMillis() + 90000L;
                    boolean announced = false;
                    while (running && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (announced) {
                            continue;
                        }
                        Resolver r = resolver;
                        if (r != null && r.status().ready) {
                            announced = true;
                            Log.i(TAG, "高码率通道已激活（设备身份注册成功）");
                            StartListener cb = listener;
                            if (cb != null) {
                                try {
                                    cb.onStage("高码率通道已就绪");
                                } catch (RuntimeException ignored) {
                                    // 回调异常不影响引擎
                                }
                            }
                        }
                    }
                }
            }, "ysp-gateway-ready-watch");
            watchdog.setDaemon(true);
            watchdog.start();
        } catch (Throwable e) {
            fail(describe(e));
        } finally {
            starting.set(false);
        }
    }

    private void stage(String text) {
        Log.i(TAG, text);
        StartListener cb = listener;
        if (cb != null) {
            try {
                cb.onStage(text);
            } catch (RuntimeException ignored) {
                // 回调异常不影响启动
            }
        }
    }

    private void notifyReady() {
        StartListener cb = listener;
        if (cb != null) {
            try {
                cb.onReady(snapshot());
            } catch (RuntimeException ignored) {
                // 回调异常不影响引擎
            }
        }
    }

    private void fail(String reason) {
        this.running = false;
        this.lastError = reason == null ? "未知错误" : reason;
        Log.e(TAG, "启动失败: " + lastError);
        StartListener cb = listener;
        if (cb != null) {
            try {
                cb.onFailed(lastError);
            } catch (RuntimeException ignored) {
                // 回调异常不影响引擎
            }
        }
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getClass().getSimpleName();
        }
        return msg;
    }

    // ------------------------------------------------------------------ 停止

    @Override
    public void stop() {
        running = false;
        LocalServer srv = server;
        server = null;
        if (srv != null) {
            try {
                srv.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "停止网关异常: " + describe(e));
            }
        }
        ChannelEngine eng = engine;
        engine = null;
        if (eng != null) {
            try {
                eng.shutdown();
            } catch (RuntimeException e) {
                Log.w(TAG, "停止引擎异常: " + describe(e));
            }
        }
        Resolver r = resolver;
        resolver = null;
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "停止解析器异常: " + describe(e));
            }
        }
        Log.i(TAG, "引擎已停止");
    }

    // ------------------------------------------------------------------ 查询

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Snapshot snapshot() {
        ChannelEngine eng = engine;
        Resolver r = resolver;
        LocalServer srv = server;
        boolean ok = running && srv != null && srv.isRunning();
        int port = srv != null ? srv.boundPort() : -1;
        String origin = srv != null ? srv.localOrigin() : "";
        boolean highReady = r != null && r.status().ready;
        int channels = ChannelTable.all().size();
        long uptime = (startedAtMs > 0 && running)
                ? System.currentTimeMillis() - startedAtMs : 0L;
        int cacheEntries = 0;
        double hitRate = 0.0;
        if (eng != null) {
            try {
                com.gary.ysplive.core.channel.TsSegmentCache.Stats st = eng.tsCache().stats();
                if (st != null) {
                    cacheEntries = st.count;
                    hitRate = st.hitRatePercent;
                }
            } catch (RuntimeException ignored) {
                // 统计失败不影响快照
            }
        }
        String guid = r != null ? r.status().cloudGuid : "";
        int hb = r != null ? r.status().heartbeatCount : 0;
        String err = lastError;
        if (r != null && r.status().lastError != null && !r.status().lastError.isEmpty()) {
            err = r.status().lastError;
        }
        return new Snapshot(ok, port, origin, highReady, channels, uptime,
                cacheEntries, hitRate, guid, hb, err);
    }

    @Override
    public String getAllPlaylistUrl() {
        String origin = localOrigin();
        return origin.isEmpty() ? "" : origin + "/all.m3u";
    }

    @Override
    public String getChannelUrl(String slug) {
        String origin = localOrigin();
        if (origin.isEmpty()) {
            return "";
        }
        ChannelSpec spec = ChannelTable.lookup(slug);
        String canonical = spec != null ? spec.slug : ChannelTable.canonical(slug);
        return canonical.isEmpty() ? "" : origin + "/" + canonical + ".m3u8";
    }

    @Override
    public String getTimeshiftUrl(String slug, long startEpochSec, long endEpochSec) {
        String base = getChannelUrl(slug);
        if (base.isEmpty()) {
            return null;
        }
        ChannelSpec spec = ChannelTable.lookup(slug);
        String canonical = spec != null ? spec.slug : ChannelTable.canonical(slug);
        if (!ChannelTable.supportsTimeshift(canonical)) {
            return null;
        }
        long end = endEpochSec > startEpochSec ? endEpochSec : startEpochSec + 3600L;
        return base + "?playseek=" + formatPlayseek(startEpochSec) + "-" + formatPlayseek(end);
    }

    /** 时间戳 → {@code yyyyMMddHHmmss}（东八区，与播放器 catchup-source 模板一致）。 */
    static String formatPlayseek(long epochSec) {
        java.util.Calendar c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        c.setTimeInMillis(epochSec * 1000L);
        StringBuilder sb = new StringBuilder(14);
        sb.append(c.get(java.util.Calendar.YEAR));
        pad(sb, c.get(java.util.Calendar.MONTH) + 1, 2);
        pad(sb, c.get(java.util.Calendar.DAY_OF_MONTH), 2);
        pad(sb, c.get(java.util.Calendar.HOUR_OF_DAY), 2);
        pad(sb, c.get(java.util.Calendar.MINUTE), 2);
        pad(sb, c.get(java.util.Calendar.SECOND), 2);
        return sb.toString();
    }

    private static void pad(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }

    /** 形如 {@code http://127.0.0.1:8767}；未启动返回空串。 */
    public String localOrigin() {
        LocalServer srv = server;
        if (srv == null || !srv.isRunning()) {
            return "";
        }
        String origin = srv.localOrigin();
        return origin == null ? "" : origin;
    }

    /** 直接暴露引擎，供诊断页/测试使用；可能为 null。 */
    public ChannelEngine engine() {
        return engine;
    }

    /** 直接暴露解析器，供诊断页/测试使用；可能为 null。 */
    public Resolver resolver() {
        return resolver;
    }
}
