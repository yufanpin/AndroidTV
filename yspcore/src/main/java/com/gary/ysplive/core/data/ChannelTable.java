package com.gary.ysplive.core.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 频道清单与静态常量。
 * <p>由 ysp-live v9.5 的 {@code DEFAULT_CHANNELS} / {@code ALIAS_MAP} / {@code TVG_IDS} 等
 * 常量表机器生成，请勿手工修改条目顺序（顺序即界面默认排序）。
 */
public final class ChannelTable {

    private ChannelTable() { }

    public static final String LOGO_BASE = "https://garysclub.sharewithyou.dpdns.org/logos/ysp-live-logos";

    private static final String[][] RAW = {
        {"cctv1", "CCTV-1 综合", "2024078201", "600001859", "fhd", "央视", "CCTV1", "Live1717729995180256"},
        {"cctv2", "CCTV-2 财经", "2024075401", "600001800", "fhd", "央视", "CCTV2", "Live1718261577870260"},
        {"cctv3", "CCTV-3 综艺", "2024068501", "600001801", "fhd", "央视", "CCTV3", "Live1718261955077261"},
        {"cctv4", "CCTV-4 中文国际", "2029797101", "600001814", "fhd", "央视", "CCTV4", "Live1718276148119264"},
        {"cctv5", "CCTV-5 体育", "2024078401", "600001818", "fhd", "央视", "CCTV5", "Live1719474204987287"},
        {"cctv5p", "CCTV-5+ 体育赛事", "2024078001", "600001817", "fhd", "央视", "CCTV5+", "Live1719473996025286"},
        {"cctv6", "CCTV-6 电影", "2013693901", "600108442", "fhd", "央视", "CCTV6", ""},
        {"cctv7", "CCTV-7 国防军事", "2024072001", "600004092", "fhd", "央视", "CCTV7", "Live1718276412224269"},
        {"cctv8", "CCTV-8 电视剧", "2029793001", "600001803", "fhd", "央视", "CCTV8", "Live1718276458899270"},
        {"cctv9", "CCTV-9 纪录", "2024078601", "600004078", "fhd", "央视", "CCTV9", "Live1718276503187272"},
        {"cctv10", "CCTV-10 科教", "2024078701", "600001805", "fhd", "央视", "CCTV10", "Live1718276550002273"},
        {"cctv11", "CCTV-11 戏曲", "2027248701", "600001806", "fhd", "央视", "CCTV11", "Live1718276603690275"},
        {"cctv12", "CCTV-12 社会与法", "2027248801", "600001807", "fhd", "央视", "CCTV12", "Live1718276623932276"},
        {"cctv13", "CCTV-13 新闻", "2029797201", "600001811", "fhd", "央视", "CCTV13", "Live1718276575708274"},
        {"cctv14", "CCTV-14 少儿", "2027248901", "600001809", "fhd", "央视", "CCTV14", "Live1718276498748271"},
        {"cctv15", "CCTV-15 音乐", "2027249001", "600001815", "fhd", "央视", "CCTV15", "Live1718276319614267"},
        {"cctv16", "CCTV-16 奥林匹克", "2027249101", "600098637", "fhd", "央视", "CCTV16", "Live1718276256572265"},
        {"cctv164k", "CCTV-16 4K", "2027249301", "600099502", "fhd", "央视", "CCTV16", "Live1704966749996185"},
        {"cctv17", "CCTV-17 农业农村", "2027249401", "600001810", "fhd", "央视", "CCTV17", "Live1718276138318263"},
        {"cctv4k", "CCTV-4K 超高清", "2029810301", "600002264", "fhd", "央视", "CCTV4K", "Live1767871224782105"},
        {"cctv8k", "CCTV-8K 超高清", "2026774101", "600156816", "fhd", "央视", "CCTV-8K", "Live1688400593818102"},
        {"cgtn", "CGTN", "2024181701", "600014550", "fhd", "央视", "CGTN英语", "Live1719392219423280"},
        {"cgtnfr", "CGTN 法语", "2024181801", "600084704", "fhd", "央视", "CGTN法语", "Live1719392670442283"},
        {"cgtnru", "CGTN 俄语", "2024181901", "600084758", "fhd", "央视", "CGTN俄语", "Live1719392779653284"},
        {"cgtnar", "CGTN 阿拉伯语", "2024182001", "600084782", "fhd", "央视", "CGTN阿语", "Live1719392885692285"},
        {"cgtnes", "CGTN 西班牙语", "2024182101", "600084744", "fhd", "央视", "CGTN西语", "Live1719392560433282"},
        {"cgtndoc", "CGTN 纪录", "2024182301", "600084781", "fhd", "央视", "CGTN纪录", "Live1719392360336281"},
        {"cctvfyjc", "CCTV 风云剧场", "2025637102", "600099658", "shd", "央视", "CCTV风云剧场", ""},
        {"cctvdyjc", "CCTV 第一剧场", "2026874202", "600099655", "shd", "央视", "CCTV第一剧场", ""},
        {"cctvhjjc", "CCTV 怀旧剧场", "2026874302", "600099620", "shd", "央视", "CCTV怀旧剧场", ""},
        {"bjws", "北京卫视", "2024052703", "600002309", "fhd", "卫视", "北京卫视", ""},
        {"jsws", "江苏卫视", "2024171103", "600002521", "fhd", "卫视", "江苏卫视", ""},
        {"dfws", "东方卫视", "2024054503", "600002483", "fhd", "卫视", "东方卫视", ""},
        {"zjws", "浙江卫视", "2024054703", "600002520", "fhd", "卫视", "浙江卫视", ""},
        {"hnws", "湖南卫视", "2024054803", "600002475", "fhd", "卫视", "湖南卫视", ""},
        {"hbws", "湖北卫视", "2024171203", "600002508", "fhd", "卫视", "湖北卫视", ""},
        {"gdws", "广东卫视", "2024060903", "600002485", "fhd", "卫视", "广东卫视", ""},
        {"gxws", "广西卫视", "2024060703", "600002509", "fhd", "卫视", "广西卫视", ""},
        {"hljws", "黑龙江卫视", "2029797003", "600002498", "fhd", "卫视", "黑龙江卫视", ""},
        {"hainanws", "海南卫视", "2024055603", "600002506", "fhd", "卫视", "海南卫视", ""},
        {"cqws", "重庆卫视", "2024061103", "600002531", "fhd", "卫视", "重庆卫视", ""},
        {"szws", "深圳卫视", "2024061303", "600002481", "fhd", "卫视", "深圳卫视", ""},
        {"scws", "四川卫视", "2024061403", "600002516", "fhd", "卫视", "四川卫视", ""},
        {"henanws", "河南卫视", "2029797303", "600002525", "fhd", "卫视", "河南卫视", ""},
        {"dnws", "东南卫视", "2024061503", "600002484", "fhd", "卫视", "东南卫视", ""},
        {"gzws", "贵州卫视", "2024061603", "600002490", "fhd", "卫视", "贵州卫视", ""},
        {"jxws", "江西卫视", "2024061703", "600002503", "fhd", "卫视", "江西卫视", ""},
        {"lnws", "辽宁卫视", "2024171303", "600002505", "fhd", "卫视", "辽宁卫视", ""},
        {"ahws", "安徽卫视", "2024171403", "600002532", "fhd", "卫视", "安徽卫视", ""},
        {"hebws", "河北卫视", "2024171503", "600002493", "fhd", "卫视", "河北卫视", ""},
        {"sdws", "山东卫视", "2029787903", "600002513", "fhd", "卫视", "山东卫视", ""},
        {"tjws", "天津卫视", "2019927003", "600152137", "fhd", "卫视", "天津卫视", ""},
        {"jlws", "吉林卫视", "2025561503", "600190405", "fhd", "卫视", "吉林卫视", ""},
        {"saxws", "陕西卫视", "2029795103", "600190400", "fhd", "卫视", "陕西卫视", ""},
        {"nxws", "宁夏卫视", "2025608503", "600190737", "fhd", "卫视", "宁夏卫视", ""},
        {"nmgws", "内蒙古卫视", "2025561203", "600190401", "fhd", "卫视", "内蒙古卫视", ""},
        {"ynws", "云南卫视", "2025561303", "600190402", "fhd", "卫视", "云南卫视", ""},
        {"shanxiws", "山西卫视", "2025560803", "600190407", "fhd", "卫视", "山西卫视", ""},
        {"gsws", "甘肃卫视", "2025561703", "600190408", "fhd", "卫视", "甘肃卫视", ""},
        {"qhws", "青海卫视", "2025559103", "600190406", "fhd", "卫视", "青海卫视", ""},
        {"xizangws", "西藏卫视", "2025558003", "600190403", "fhd", "卫视", "西藏卫视", ""},
        {"xjws", "新疆卫视", "2019927403", "600152138", "fhd", "卫视", "新疆卫视", ""},
        {"cetv1", "CETV-1", "2022823801", "600171827", "fhd", "卫视", "CETV1", ""},
        {"guoxue", "国学频道", "2029360403", "600213139", "fhd", "卫视", "国学", ""},
    };

    private static final Map<String, String> ALIAS_RAW = new LinkedHashMap<>();
    static {
        ALIAS_RAW.put("anhuiws", "ahws");
        ALIAS_RAW.put("beijingws", "bjws");
        ALIAS_RAW.put("cctv16hd", "cctv16");
        ALIAS_RAW.put("cctv5plus", "cctv5p");
        ALIAS_RAW.put("cctvdiyijuchang", "cctvdyjc");
        ALIAS_RAW.put("cctvfengyunjuchang", "cctvfyjc");
        ALIAS_RAW.put("cctvhuaijiujuchang", "cctvhjjc");
        ALIAS_RAW.put("cgtnalaboyu", "cgtnar");
        ALIAS_RAW.put("cgtneyu", "cgtnru");
        ALIAS_RAW.put("cgtnfayu", "cgtnfr");
        ALIAS_RAW.put("cgtnwaiyujilu", "cgtndoc");
        ALIAS_RAW.put("cgtnxibanyayu", "cgtnes");
        ALIAS_RAW.put("chongqingws", "cqws");
        ALIAS_RAW.put("dongfangws", "dfws");
        ALIAS_RAW.put("fujiandongnanws", "dnws");
        ALIAS_RAW.put("guangdongws", "gdws");
        ALIAS_RAW.put("guangxiws", "gxws");
        ALIAS_RAW.put("guizhouws", "gzws");
        ALIAS_RAW.put("hebeiws", "hebws");
        ALIAS_RAW.put("heilongjiangws", "hljws");
        ALIAS_RAW.put("hubeiws", "hbws");
        ALIAS_RAW.put("hunanws", "hnws");
        ALIAS_RAW.put("jiangsuws", "jsws");
        ALIAS_RAW.put("jiangxiws", "jxws");
        ALIAS_RAW.put("jilinws", "jlws");
        ALIAS_RAW.put("liaoningws", "lnws");
        ALIAS_RAW.put("neimengguws", "nmgws");
        ALIAS_RAW.put("ningxiaws", "nxws");
        ALIAS_RAW.put("qinghaiws", "qhws");
        ALIAS_RAW.put("shandongws", "sdws");
        ALIAS_RAW.put("shannxiws", "saxws");
        ALIAS_RAW.put("shenzhenws", "szws");
        ALIAS_RAW.put("sichuanws", "scws");
        ALIAS_RAW.put("tianjinws", "tjws");
        ALIAS_RAW.put("xinjiangws", "xjws");
        ALIAS_RAW.put("yunnanws", "ynws");
        ALIAS_RAW.put("zhejiangws", "zjws");
    }

    private static final java.util.Set<String> FORCE_BK = new java.util.HashSet<>();
    private static final java.util.Set<String> TIMESHIFT = new java.util.HashSet<>();
    private static final java.util.Set<String> BACKEND = new java.util.HashSet<>();
    private static final java.util.Set<String> TRUE_4K = new java.util.HashSet<>();
    static {
        FORCE_BK.add("cctv11");
        FORCE_BK.add("cctv12");
        FORCE_BK.add("cctv14");
        FORCE_BK.add("cctv15");
        FORCE_BK.add("cctv16");
        FORCE_BK.add("cctv17");
        FORCE_BK.add("cctvdyjc");
        FORCE_BK.add("cctvfyjc");
        FORCE_BK.add("cctvhjjc");
        TIMESHIFT.add("ahws");
        TIMESHIFT.add("bjws");
        TIMESHIFT.add("cctv1");
        TIMESHIFT.add("cctv10");
        TIMESHIFT.add("cctv13");
        TIMESHIFT.add("cctv2");
        TIMESHIFT.add("cctv3");
        TIMESHIFT.add("cctv4");
        TIMESHIFT.add("cctv5");
        TIMESHIFT.add("cctv5p");
        TIMESHIFT.add("cctv6");
        TIMESHIFT.add("cctv7");
        TIMESHIFT.add("cctv8");
        TIMESHIFT.add("cctv8k");
        TIMESHIFT.add("cctv9");
        TIMESHIFT.add("cetv1");
        TIMESHIFT.add("cgtn");
        TIMESHIFT.add("cgtnar");
        TIMESHIFT.add("cgtndoc");
        TIMESHIFT.add("cgtnes");
        TIMESHIFT.add("cgtnfr");
        TIMESHIFT.add("cgtnru");
        TIMESHIFT.add("cqws");
        TIMESHIFT.add("dfws");
        TIMESHIFT.add("dnws");
        TIMESHIFT.add("gdws");
        TIMESHIFT.add("gsws");
        TIMESHIFT.add("guoxue");
        TIMESHIFT.add("gxws");
        TIMESHIFT.add("gzws");
        TIMESHIFT.add("hainanws");
        TIMESHIFT.add("hbws");
        TIMESHIFT.add("hebws");
        TIMESHIFT.add("henanws");
        TIMESHIFT.add("hljws");
        TIMESHIFT.add("hnws");
        TIMESHIFT.add("jlws");
        TIMESHIFT.add("jsws");
        TIMESHIFT.add("jxws");
        TIMESHIFT.add("lnws");
        TIMESHIFT.add("nmgws");
        TIMESHIFT.add("nxws");
        TIMESHIFT.add("qhws");
        TIMESHIFT.add("saxws");
        TIMESHIFT.add("scws");
        TIMESHIFT.add("sdws");
        TIMESHIFT.add("shanxiws");
        TIMESHIFT.add("szws");
        TIMESHIFT.add("tjws");
        TIMESHIFT.add("xizangws");
        TIMESHIFT.add("xjws");
        TIMESHIFT.add("ynws");
        TIMESHIFT.add("zjws");
        BACKEND.add("cctv1");
        BACKEND.add("cctv10");
        BACKEND.add("cctv11");
        BACKEND.add("cctv12");
        BACKEND.add("cctv13");
        BACKEND.add("cctv14");
        BACKEND.add("cctv15");
        BACKEND.add("cctv16");
        BACKEND.add("cctv164k");
        BACKEND.add("cctv17");
        BACKEND.add("cctv2");
        BACKEND.add("cctv3");
        BACKEND.add("cctv4");
        BACKEND.add("cctv4k");
        BACKEND.add("cctv5");
        BACKEND.add("cctv5p");
        BACKEND.add("cctv7");
        BACKEND.add("cctv8");
        BACKEND.add("cctv8k");
        BACKEND.add("cctv9");
        BACKEND.add("cgtn");
        BACKEND.add("cgtnar");
        BACKEND.add("cgtndoc");
        BACKEND.add("cgtnes");
        BACKEND.add("cgtnfr");
        BACKEND.add("cgtnru");
        TRUE_4K.add("cctv164k");
        TRUE_4K.add("cctv4k");
        TRUE_4K.add("cctv8k");
    }

    private static final List<ChannelSpec> ALL;
    private static final Map<String, ChannelSpec> BY_SLUG;
    static {
        List<ChannelSpec> all = new ArrayList<>(RAW.length);
        Map<String, ChannelSpec> bySlug = new LinkedHashMap<>(RAW.length * 2);
        for (String[] r : RAW) {
            ChannelSpec spec = new ChannelSpec(r[0], r[1], r[2], r[3], r[4], r[5], r[6],
                    LOGO_BASE + "/" + r[0] + ".png", r[7]);
            all.add(spec);
            bySlug.put(spec.slug, spec);
        }
        ALL = Collections.unmodifiableList(all);
        BY_SLUG = Collections.unmodifiableMap(bySlug);
    }

    /** 全部频道，顺序即默认展示顺序。 */
    public static List<ChannelSpec> all() {
        return ALL;
    }

    /** 按 slug 精确查找（不处理别名），找不到返回 null。 */
    public static ChannelSpec bySlug(String slug) {
        return slug == null ? null : BY_SLUG.get(slug);
    }

    /** 归一化频道名：去掉首尾空白与斜杠、去 .m3u8 后缀、转小写，并把 cctv16-4k 等写法归一。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        key = key.toLowerCase(java.util.Locale.ROOT);
        if (key.endsWith(".m3u8")) {
            key = key.substring(0, key.length() - 5);
        }
        if (key.equals("cctv16-4k") || key.equals("cctv16_4k")
                || key.equals("cctv16/4k") || key.equals("cctv16(4k)")) {
            return "cctv164k";
        }
        return key;
    }

    /** 归一化并解析别名，得到规范 slug。 */
    public static String canonical(String raw) {
        String key = normalize(raw);
        String alias = ALIAS_RAW.get(key);
        return alias != null ? alias : key;
    }

    /** 归一化 + 别名 + 查表；找不到返回 null。 */
    public static ChannelSpec lookup(String raw) {
        ChannelSpec spec = bySlug(canonical(raw));
        if (spec != null) {
            return spec;
        }
        return bySlug(normalize(raw));
    }

    public static boolean isForceBk(String slug) {
        return FORCE_BK.contains(slug);
    }

    public static boolean supportsTimeshift(String slug) {
        return TIMESHIFT.contains(slug);
    }

    public static boolean isBackendChannel(String slug) {
        return BACKEND.contains(slug);
    }

    public static boolean isTrue4k(String slug) {
        return TRUE_4K.contains(slug);
    }

    public static List<ChannelSpec> group(String group) {
        List<ChannelSpec> out = new ArrayList<>();
        for (ChannelSpec s : ALL) {
            if (s.group.equals(group)) {
                out.add(s);
            }
        }
        return out;
    }
}
