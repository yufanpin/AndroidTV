/** 一个频道的静态描述。 */
package com.gary.ysplive.core.data;

import java.util.Objects;

public final class ChannelSpec {

    /** 规范化 slug，例如 {@code cctv1} / {@code hnws}。 */
    public final String slug;
    /** 展示名称，例如 {@code CCTV-1 综合}。 */
    public final String name;
    /** 央视频 sid。 */
    public final String sid;
    /** 央视频 pid。 */
    public final String pid;
    /** 默认清晰度标记：{@code fhd} 或 {@code shd}。 */
    public final String defn;
    /** 分组：{@code 央视} / {@code 卫视}。 */
    public final String group;
    /** EPG tvg-id。 */
    public final String tvgId;
    /** 台标 URL。 */
    public final String logo;
    /**
     * 央视频设备协议用的 {@code live_id}（形如 {@code Live1717729995180256}）。
     * <p>注意：这<b>不是</b> {@link #sid}，两者完全独立。设备协议 {@code live/v1/01} 的
     * {@code id} 字段必须用这个值，否则会返回"响应缺少 videos"。
     * <p>只有 26 个走设备协议的高码率频道有值；1080p 频道为空串。
     */
    public final String liveId;

    public ChannelSpec(String slug, String name, String sid, String pid, String defn,
                       String group, String tvgId, String logo) {
        this(slug, name, sid, pid, defn, group, tvgId, logo, "");
    }

    public ChannelSpec(String slug, String name, String sid, String pid, String defn,
                       String group, String tvgId, String logo, String liveId) {
        this.slug = slug;
        this.name = name;
        this.sid = sid;
        this.pid = pid;
        this.defn = defn;
        this.group = group;
        this.tvgId = tvgId;
        this.logo = logo;
        this.liveId = liveId == null ? "" : liveId;
    }

    /** 是否支持走高码率设备协议（有 live_id 即可）。 */
    public boolean hasLiveId() {
        return !liveId.isEmpty();
    }

    /** 友好看法的清晰度名，例如 {@code fhd -> 1080p}。 */
    public String defnLabel() {
        String d = defn == null ? "" : defn.trim().toLowerCase(java.util.Locale.ROOT);
        if ("fhd".equals(d)) {
            return "1080p";
        }
        if ("shd".equals(d)) {
            return "720p";
        }
        return d.isEmpty() ? "1080p" : defn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChannelSpec)) {
            return false;
        }
        return Objects.equals(slug, ((ChannelSpec) o).slug);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(slug);
    }

    @Override
    public String toString() {
        return "ChannelSpec{" + slug + ", " + name + ", sid=" + sid + ", pid=" + pid + '}';
    }
}
