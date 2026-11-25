package com.gary.ysplive.core.spi;

/**
 * 整个直播引擎的门面。
 * <p>App 层只需要跟这个接口打交道：启动引擎 → 拿到本地订阅地址 → 把地址交给播放器。
 * 所有重活（设备协议、频道刷新、TS 代理）都在引擎内部完成。
 */
public interface Gateway {

    /** 引擎运行状态快照。 */
    final class Snapshot {
        public final boolean running;
        public final int port;
        public final String localOrigin;
        /** 高码率设备协议是否就绪（false 时全频道自动走 1080p）。 */
        public final boolean highBitrateReady;
        public final int channelCount;
        public final long uptimeMs;
        public final int cacheEntries;
        public final double cacheHitRate;
        public final String cloudGuid;
        public final int heartbeatCount;
        public final String lastError;

        public Snapshot(boolean running, int port, String localOrigin, boolean highBitrateReady,
                        int channelCount, long uptimeMs, int cacheEntries, double cacheHitRate,
                        String cloudGuid, int heartbeatCount, String lastError) {
            this.running = running;
            this.port = port;
            this.localOrigin = localOrigin == null ? "" : localOrigin;
            this.highBitrateReady = highBitrateReady;
            this.channelCount = channelCount;
            this.uptimeMs = uptimeMs;
            this.cacheEntries = cacheEntries;
            this.cacheHitRate = cacheHitRate;
            this.cloudGuid = cloudGuid == null ? "" : cloudGuid;
            this.heartbeatCount = heartbeatCount;
            this.lastError = lastError == null ? "" : lastError;
        }
    }

    /** 引擎启动进度回调，在任意线程触发。 */
    interface StartListener {
        /** @param stage 人类可读的阶段名，例如“正在注册设备身份” */
        void onStage(String stage);

        void onReady(Snapshot snapshot);

        void onFailed(String reason);
    }

    /**
     * 启动引擎。可重复调用；已启动时立即回调 {@code onReady}。
     * 非阻塞，实际启动在后台线程进行。
     */
    void start(StartListener listener);

    /** 是否已经启动完成（本地端口可服务）。 */
    boolean isRunning();

    /** 停止引擎，释放端口与线程。 */
    void stop();

    /** 当前快照。未启动时 {@code running=false}。 */
    Snapshot snapshot();

    /** 本地订阅地址，例如 {@code http://127.0.0.1:8767/all.m3u}；未启动返回空串。 */
    String getAllPlaylistUrl();

    /** 某频道的本地播放地址，例如 {@code http://127.0.0.1:8767/cctv1.m3u8}；未启动返回空串。 */
    String getChannelUrl(String slug);

    /** 带时移的播放地址；不支持时移的频道返回 {@code null}。 */
    String getTimeshiftUrl(String slug, long startEpochSec, long endEpochSec);

    /** 引擎配置。 */
    final class Config {
        /** 首选端口，占用时自动向后找。 */
        public int port = 8767;
        /** 是否允许局域网访问（true 绑 0.0.0.0，false 绑 127.0.0.1）。 */
        public boolean allowLan = false;
        /** 分片内存缓存 MB。 */
        public int cacheMb = 200;
        /** 单设备高码率并发上限，0 = 关闭主动轮换。 */
        public int linksPerDevice = 6;
        /** 换台防抖秒数，0 = 关闭。 */
        public double zapDebounceSec = 10.0;
        /** true = 完全不启用设备协议，全走 1080p。 */
        public boolean disableHighBitrate = false;
        /** true = 只有 4K/8K 频道走设备协议。 */
        public boolean only4kHighBitrate = false;
        /** 数据持久化目录（设备身份、缓存状态）。 */
        public java.io.File dataDir;
        /** HTTP 超时秒数。 */
        public double timeoutSec = 10.0;
    }
}
