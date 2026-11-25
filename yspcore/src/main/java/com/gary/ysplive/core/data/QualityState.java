package com.gary.ysplive.core.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 当前各频道的真实播放质量（运行期探测结果）。
 *
 * <h2>为什么需要这个类</h2>
 * 频道表里 3 个标称 4K/8K 的频道（cctv4k / cctv8k / cctv164k）的 {@code defn}
 * 都写着 {@code fhd}。实测（BitrateProbe 扫 TS SPS）确认：
 * <pre>
 *   defn=fhd  -> 1920x1080 (H.264)  3.78 Mbps   ← 实际播放的
 *   defn=uhd  -> 上游不返回播放列表
 *   defn=shd  -> 403
 * </pre>
 * 真正的 4K 码流必须走设备协议，但该协议的上游端点目前
 * {@code app/start} 一律返回 HTTP 400 —— 用原作者自己的 Python 版
 * （ysp-live-v9.5.py）跑同样请求也是 400，说明是上游变更，不是移植 bug。
 *
 * <p>所以这些频道实际只能放 1080p。界面上继续标「4K」是在骗用户。
 * 这个类负责记录「实际拿到了什么」，让界面如实显示，
 * 并在上游恢复后自动重新标回 4K。
 */
public final class QualityState {

    private QualityState() {
    }

    /** slug -> 实际分辨率描述（如 "1080p" / "4K"）。 */
    private static final Map<String, String> ACTUAL =
            Collections.synchronizedMap(new HashMap<String, String>());

    /** 4K/8K 频道当前是否已确认降级到 1080p。 */
    private static final Map<String, Boolean> DEGRADED =
            Collections.synchronizedMap(new HashMap<String, Boolean>());

    /** 记录某频道实际拿到的画质。 */
    public static void setActual(String slug, String quality, boolean degraded) {
        if (slug == null) {
            return;
        }
        if (quality != null) {
            ACTUAL.put(slug, quality);
        }
        DEGRADED.put(slug, degraded);
    }

    /** 某频道实际画质；未知时返回 null。 */
    public static String actual(String slug) {
        return slug == null ? null : ACTUAL.get(slug);
    }

    /** 该频道是否处于降级状态（标称 4K 实际 1080p）。 */
    public static boolean isDegraded(String slug) {
        Boolean b = slug == null ? null : DEGRADED.get(slug);
        return b != null && b;
    }

    /** 清空（配置变更/重启引擎时调用）。 */
    public static void reset() {
        ACTUAL.clear();
        DEGRADED.clear();
    }

    // ---------------- 供上游解析器回报 ----------------

    /**
     * 由解析路径调用：记录某频道这次实际走的是高码率还是 1080p 回退。
     *
     * @param highBitrate true 表示真的拿到了高码率（设备协议成功）
     */
    public static void reportResolution(String slug, boolean highBitrate) {
        if (slug == null) {
            return;
        }
        boolean nominal4k = ChannelTable.isTrue4k(slug);
        if (highBitrate) {
            setActual(slug, nominal4k ? "4K" : "高码率", false);
        } else {
            // 标称 4K 的频道回退到 1080p 时，明确标记为降级
            setActual(slug, "1080p", nominal4k);
        }
    }
}
