package com.gary.ysplive.core.device;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 频道 slug → 央视频 <b>Live ID</b> 映射。
 *
 * <p><b>不要和 {@code ChannelSpec.sid} 搞混</b>：
 * <ul>
 *   <li>{@code sid}（如 {@code 2024078201}）是 JCE 时移/bkliveinfo 用的频道编号</li>
 *   <li>Live ID（如 {@code Live1717729995180256}）是 {@code live/v1/01} 接口要的直播间编号</li>
 * </ul>
 * 拿 sid 去请求 {@code live/v1/01} 会直接返回 {@code {"result":"400","data":{}}}，
 * 且不带任何错误说明 —— 这个坑踩过一次。
 *
 * <p>表逐字提取自参考实现 ysp-live-v9.5.py 的 {@code channels()}
 * （机器生成，勿手工编辑；要改请改 tools/gen_live_ids.py）。
 */
public final class LiveIds {

    private LiveIds() { }

    private static final Map<String, String> MAP;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>(26);
        m.put("cctv1", "Live1717729995180256");
        m.put("cctv2", "Live1718261577870260");
        m.put("cctv3", "Live1718261955077261");
        m.put("cctv4", "Live1718276148119264");
        m.put("cctv5", "Live1719474204987287");
        m.put("cctv5p", "Live1719473996025286");
        m.put("cctv7", "Live1718276412224269");
        m.put("cctv8", "Live1718276458899270");
        m.put("cctv9", "Live1718276503187272");
        m.put("cctv10", "Live1718276550002273");
        m.put("cctv11", "Live1718276603690275");
        m.put("cctv12", "Live1718276623932276");
        m.put("cctv13", "Live1718276575708274");
        m.put("cctv14", "Live1718276498748271");
        m.put("cctv15", "Live1718276319614267");
        m.put("cctv16", "Live1718276256572265");
        m.put("cctv17", "Live1718276138318263");
        m.put("cctv4k", "Live1767871224782105");
        m.put("cctv8k", "Live1688400593818102");
        m.put("cctv164k", "Live1704966749996185");
        m.put("cgtn", "Live1719392219423280");
        m.put("cgtnfr", "Live1719392670442283");
        m.put("cgtnru", "Live1719392779653284");
        m.put("cgtnar", "Live1719392885692285");
        m.put("cgtnes", "Live1719392560433282");
        m.put("cgtndoc", "Live1719392360336281");
        MAP = Collections.unmodifiableMap(m);
    }

    /** 全部 slug → Live ID。 */
    public static Map<String, String> all() {
        return MAP;
    }

    /**
     * 取某频道的 Live ID。
     *
     * <p><b>唯一事实源是 {@code ChannelSpec.liveId}</b>：{@code ChannelTable} 已经把 Live ID
     * 放进频道表了，这里优先读它。本类自带的 {@link #MAP} 只作为兜底
     * （例如传入的 slug 不被 ChannelTable 识别时），避免两边表漂移。
     *
     * @param slug 频道 slug（会用 {@code ChannelTable.canonical} 归一）
     * @return Live ID；未知频道返回空串
     */
    public static String forSlug(String slug) {
        if (slug == null) {
            return "";
        }
        // 1) 以 ChannelSpec 为唯一事实源
        com.gary.ysplive.core.data.ChannelSpec spec =
                com.gary.ysplive.core.data.ChannelTable.lookup(slug);
        if (spec != null && spec.hasLiveId()) {
            return spec.liveId;
        }
        // 2) 兜底：本类内置表（防止 ChannelTable 未覆盖的别名）
        String key = com.gary.ysplive.core.data.ChannelTable.canonical(slug);
        String v = MAP.get(key);
        if (v != null) {
            return v;
        }
        v = MAP.get(slug.trim().toLowerCase(java.util.Locale.ROOT));
        return v == null ? "" : v;
    }

    /** 该频道是否有 Live ID（即是否走设备协议）。 */
    public static boolean has(String slug) {
        return !forSlug(slug).isEmpty();
    }
}
