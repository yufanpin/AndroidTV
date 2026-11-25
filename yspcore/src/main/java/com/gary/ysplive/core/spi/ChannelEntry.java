package com.gary.ysplive.core.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次高码率解析的结果。
 * <p>对应 Python 参考实现里的 {@code ChannelEntry}：一个可播放的 m3u8 地址，外加取分片时必须带的签名头。
 * 不可变；过期后由 {@link Resolver} 重新解析。
 */
public final class ChannelEntry {

    /** 频道 slug。 */
    public final String channel;
    /** 央视频 live_id。 */
    public final String liveId;
    /** 上游 m3u8 地址。 */
    public final String finalUrl;
    /** 取 m3u8 和 ts 都要带的请求头（UID/APPID/APPSIGN/APPRANDOMSTR/Referer/User-Agent）。 */
    public final Map<String, String> playbackHeaders;
    /** 解析时使用的设备 android_id。 */
    public final String androidId;
    /** 解析时使用的 X-Uid。 */
    public final String xUid;
    /** 码率标记，例如 {@code 36p}。 */
    public final String rate;
    /** 友好码率名，例如 {@code 4K超高清}。 */
    public final String rateName;
    public final String finalHost;
    public final long refreshedAtMs;
    public final long expiresAtMs;
    public final long sessionGeneration;

    public ChannelEntry(String channel, String liveId, String finalUrl,
                        Map<String, String> playbackHeaders, String androidId, String xUid,
                        String rate, String rateName, String finalHost,
                        long refreshedAtMs, long expiresAtMs, long sessionGeneration) {
        this.channel = channel == null ? "" : channel;
        this.liveId = liveId == null ? "" : liveId;
        this.finalUrl = finalUrl == null ? "" : finalUrl;
        this.playbackHeaders = playbackHeaders == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<>(playbackHeaders);
        this.androidId = androidId == null ? "" : androidId;
        this.xUid = xUid == null ? "" : xUid;
        this.rate = rate == null ? "" : rate;
        this.rateName = rateName == null ? "" : rateName;
        this.finalHost = finalHost == null ? "" : finalHost;
        this.refreshedAtMs = refreshedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.sessionGeneration = sessionGeneration;
    }

    public boolean fresh(long nowMs) {
        return !finalUrl.isEmpty() && nowMs < expiresAtMs;
    }

    /** 是否缺少签名头（这类 entry 不能直接拿去取分片）。 */
    public boolean missingSignedHeaders() {
        for (String key : new String[] {"APPID", "APPSIGN", "APPRANDOMSTR"}) {
            String v = playbackHeaders.get(key);
            if (v == null || v.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ChannelEntry{" + channel + ", " + rateName + ", fresh=" + (System.currentTimeMillis() < expiresAtMs)
                + ", host=" + finalHost + '}';
    }
}
