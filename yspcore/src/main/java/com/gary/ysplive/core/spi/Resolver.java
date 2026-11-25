package com.gary.ysplive.core.spi;

/**
 * 高码率（4K / 1080p+）设备协议解析器。
 * <p>对应 Python 参考实现里的 {@code Resolver}。实现负责：
 * <ul>
 *   <li>建立并维护"央视频电视投屏助手"设备身份（注册 + 心跳 + 会话续期）</li>
 *   <li>按需把某个频道解析成带签名头的 {@link ChannelEntry}</li>
 *   <li>换台防抖、链接配额、HTTP 400 自愈与设备轮换</li>
 * </ul>
 * 所有方法都必须可被任意线程并发调用。
 */
public interface Resolver {

    /** 解析器当前可用状态。 */
    final class Status {
        public final boolean ready;
        public final boolean sessionValid;
        public final int activeDeviceSlots;
        public final int heartbeatCount;
        public final String cloudGuid;
        public final String lastError;

        public Status(boolean ready, boolean sessionValid, int activeDeviceSlots,
                      int heartbeatCount, String cloudGuid, String lastError) {
            this.ready = ready;
            this.sessionValid = sessionValid;
            this.activeDeviceSlots = activeDeviceSlots;
            this.heartbeatCount = heartbeatCount;
            this.cloudGuid = cloudGuid == null ? "" : cloudGuid;
            this.lastError = lastError == null ? "" : lastError;
        }
    }

    /** 启动后台线程（心跳、刷新 worker）。可重复调用。 */
    void start();

    /** 停止后台线程，释放资源。 */
    void stop();

    /** 当前状态快照，永不为 null。 */
    Status status();

    /**
     * 解析一个频道，拿回可直接给播放器用的 m3u8 地址与签名头。
     * <p>可能有网络 IO，必须在后台线程调用。
     *
     * @param slug 频道 slug（已归一化，例如 {@code cctv1}）
     * @throws ResolutionException 解析失败；调用方应回退到 1080p 备用通道
     */
    ChannelEntry resolve(String slug) throws ResolutionException;

    /**
     * 把上游 m3u8 里的分片地址改写成走本地代理的形式，并为每个分片绑定取流所需的签名头。
     *
     * @param body       上游 m3u8 文本
     * @param finalUrl   该 m3u8 的最终地址（用于解析相对路径）
     * @param proxyOrigin 例如 {@code http://127.0.0.1:8767}
     */
    String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug);

    /**
     * 为一个即将被代理取流的分片地址找出该用的签名头。
     * <p>对应 Python 的 {@code playback_headers_for_ts_url}。找不到时回调退到默认头。
     *
     * @throws ResolutionException 该 host 需要签名头但缓存里没有
     */
    java.util.Map<String, String> playbackHeadersForTsUrl(String realUrl) throws ResolutionException;

    /** 该频道是否正处于失败冷却中。 */
    boolean isChannelInCooldown(String slug);

    /** 清除某频道的失败冷却（成功以后调用）。 */
    void clearChannelCooldown(String slug);
}
