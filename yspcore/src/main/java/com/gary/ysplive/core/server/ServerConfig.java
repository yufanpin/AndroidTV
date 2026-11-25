package com.gary.ysplive.core.server;

import java.io.File;

/**
 * 本地 HTTP 网关的运行配置。
 * <p>对应 Python 参考实现里散落的全局量（{@code server_port}、{@code global_ts_cache} 的 MB 数、
 * {@code links_per_device}、换台防抖秒数等）与命令行开关（{@code --no-4k} / {@code --only-4k}）。
 *
 * <p>可变 POJO，方便 App 设置页直接改字段；{@link LocalServer#start()} 时读取一次。
 */
public final class ServerConfig {

    /** 首选端口；占用时自动向后找。 */
    public int port = 8767;
    /** 端口被占用时最多向后尝试多少个。 */
    public int portSearchSpan = 50;
    /** true 绑 {@code 0.0.0.0}（局域网可访问），false 绑 {@code 127.0.0.1}。 */
    public boolean allowLan = false;
    /** 显式绑定地址；为 {@code null} 时由 {@link #allowLan} 推导。 */
    public String bindAddress;
    /** 分片内存缓存上限（MB），Python 默认 200。 */
    public int cacheMb = 200;
    /** 分片缓存最大存活时间（秒），Python 默认 180。 */
    public int cacheMaxAgeSec = 180;
    /** 单设备高码率并发上限；0 = 关闭主动轮换。 */
    public int linksPerDevice = 6;
    /** 换台防抖秒数；0 = 关闭。 */
    public double zapDebounceSec = 0.6;
    /** {@code --no-4k}：完全禁用设备协议，全部走 1080p。 */
    public boolean disableHighBitrate = false;
    /** {@code --only-4k}：只有真 4K/8K 频道走设备协议。 */
    public boolean only4kHighBitrate = false;
    /** 数据持久化目录（设备身份、缓存状态）。 */
    public File dataDir;
    /** HTTP 上游超时（秒）。 */
    public double timeoutSec = 10.0;
    /** 工作线程数；0 = 自动（CPU 核数 * 4，夹在 8..64）。 */
    public int workerThreads = 0;
    /** 单个 keep-alive 连接上最多处理多少个请求。 */
    public int maxRequestsPerConnection = 64;
    /** 单次请求头读取上限（字节），防恶意长头。 */
    public int maxHeaderBytes = 32 * 1024;

    public ServerConfig() { }

    /** 由命令行参数推导（桌面联调用）。 */
    public static ServerConfig fromArgs(String[] args) {
        ServerConfig cfg = new ServerConfig();
        if (args == null) {
            return cfg;
        }
        for (String a : args) {
            if (a == null) {
                continue;
            }
            String arg = a.trim();
            if (arg.equals("--no-4k")) {
                cfg.disableHighBitrate = true;
            } else if (arg.equals("--only-4k")) {
                cfg.only4kHighBitrate = true;
            } else if (arg.equals("--lan")) {
                cfg.allowLan = true;
            } else if (arg.startsWith("--port=")) {
                cfg.port = parseInt(arg.substring("--port=".length()), cfg.port);
            } else if (arg.startsWith("--cache-mb=")) {
                cfg.cacheMb = parseInt(arg.substring("--cache-mb=".length()), cfg.cacheMb);
            } else if (arg.startsWith("--data-dir=")) {
                cfg.dataDir = new File(arg.substring("--data-dir=".length()));
            } else if (arg.startsWith("--bind=")) {
                cfg.bindAddress = arg.substring("--bind=".length());
            }
        }
        return cfg;
    }

    /** 实际绑定地址。 */
    public String resolvedBindAddress() {
        if (bindAddress != null && !bindAddress.trim().isEmpty()) {
            return bindAddress.trim();
        }
        return allowLan ? "0.0.0.0" : "127.0.0.1";
    }

    /** 工作线程数（已解析自动值）。 */
    public int resolvedWorkerThreads() {
        if (workerThreads > 0) {
            return workerThreads;
        }
        int n = Runtime.getRuntime().availableProcessors() * 4;
        return Math.max(8, Math.min(64, n));
    }

    /** 缓存上限字节数。 */
    public long cacheBytes() {
        return Math.max(1, cacheMb) * 1024L * 1024L;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return "ServerConfig{port=" + port + ", bind=" + resolvedBindAddress()
                + ", cacheMb=" + cacheMb + ", linksPerDevice=" + linksPerDevice
                + ", zapDebounceSec=" + zapDebounceSec
                + ", no4k=" + disableHighBitrate + ", only4k=" + only4kHighBitrate + '}';
    }
}
