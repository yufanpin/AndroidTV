package com.gary.ysplive.core.device;

import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.ChannelEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备协议各接口的请求体/响应解析、上报体构造。
 * <p>逐行复刻 Python 参考实现的这些函数：
 * {@code build_report_common_value} / {@code build_app_start_body} / {@code build_vdn_appcommon} /
 * {@code parse_result_code} / {@code extract_guid} / {@code generate_app_random_str} /
 * {@code compute_vdn_code} / {@code default_cctv_playback_headers} /
 * {@code cctv_playback_headers_for_entry} / {@code entry_missing_signed_playback_headers} /
 * {@code live_playback_host_needs_signed_headers} / {@code ts_path_matches_final_url}。
 * <p>纯函数，无状态，任意线程可调用。
 */
public final class Protocol {

    /** 央视频各接口地址（与 Python 常量表一致）。 */
    public static final String CLOUD_GET_URL = "https://ytpcloudws.cctv.cn/cloudps/wssapi/device/v2/get";
    public static final String CLOUD_REGISTER_URL = "https://ytpcloudws.cctv.cn/cloudps/wssapi/device/v2/register";
    public static final String APP_START_URL = "https://ytpaddr.cctv.cn/gsnw/api/app/start/v1/01";
    public static final String DRM_CONFIG_URL = "https://ytpaddr.cctv.cn/gsnw/drm/config/obtain/v1";
    public static final String VERSION_CONFIG_URL = "https://ytpaddr.cctv.cn/gsnw/version/config/obtain/v1";
    public static final String DICTIONARY_URL = "https://ytpaddr.cctv.cn/gsnw/player/dictionary/obtain/v1";
    public static final String INDEX_URL = "https://ytpaddr.cctv.cn/gsnw/api/index/v1/01";
    public static final String REPORT_SINGLE_URL = "https://ytpdata.cctv.cn/das/app/data/message/single";
    public static final String COLLECT_REPORT_URL = "https://collect.cctv.cn/cctvmobileinf/rest/cctv/receive/new/app";
    public static final String LIVE_V1_01_URL = "https://ytpaddr.cctv.cn/gsnw/api/live/v1/01";
    public static final String LIVE_V1_02_URL = "https://ytpaddr.cctv.cn/gsnw/api/live/v1/02";
    public static final String VDN_GETSTREAM_URL = "https://ytpvdn.cctv.cn/cctvmobileinf/rest/cctv/videoliveUrl/getstream";

    /** 云注册结果码。 */
    public static final int RESULT_OK = 0;
    public static final int RESULT_NEEDS_REGISTER = 601;
    public static final int RESULT_GET_MISSING_OR_INVALID = 2;
    public static final int RESULT_REGISTERED_ELSEWHERE = 694;
    public static final int RESULT_REGISTER_RETRY_LATER = 695;

    /** 设备协议使用的渠道名与版本（央视频给"电视投屏助手"的固定值）。 */
    public static final String APP_CHANNEL = "dangbei";
    public static final String APP_VERSION = "1.4.1";

    private Protocol() { }

    // ==================================================================
    // 请求体
    // ==================================================================

    /** 截断到 {@code limit} 个字符（对应 Python {@code app_start_field}）。 */
    static String field(Object value, int limit) {
        String s = value == null ? "" : String.valueOf(value);
        return limit < 0 || s.length() <= limit ? s : s.substring(0, limit);
    }

    /**
     * 构造埋点/心跳/注册共用的设备信息体（对应 Python {@code build_report_common_value}）。
     * <p>字段顺序与 Python 字典字面量一致；之后的 {@code Json.stringify} 会按键排序，
     * 所以顺序最终不影响字节，但仍照抄便于比对。
     */
    public static Map<String, Object> buildReportCommonValue(DeviceProfile profile, String xUid,
                                                             String appChannel, String version,
                                                             String sdkVersion, long dataTimeMs) {
        String model = profile.reportModelOrDerived();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cctv_id", field(xUid, 64));
        m.put("device_id", field(profile.androidId, 64));
        m.put("idfa", "");
        m.put("idfv", "");
        m.put("user_id", "");
        m.put("app_key", field(Identity.REPORT_APP_KEY, 64));
        m.put("imei", "");
        m.put("android_id", field(profile.androidId, 64));
        m.put("mac", field(profile.mac, 64));
        m.put("device_builder_type", field(profile.buildType, 64));
        m.put("device_hardware", field(profile.hardware, 64));
        m.put("device_board", field(profile.board, 64));
        m.put("device_brand", field(profile.brand, 64));
        m.put("device_params", field(profile.device, 64));
        m.put("device_display", field(profile.display, 64));
        m.put("device_version_id", field(profile.versionId, 64));
        m.put("device_host", field(profile.host, 128));
        m.put("device_product", field(profile.product, 64));
        m.put("device_tags", field(profile.tags, 64));
        m.put("device_user", field(profile.user, 30));
        m.put("device_fingerprint", field(profile.fingerprint, 128));
        m.put("device_manufacturer", field(profile.manufacturer, 64));
        m.put("device_model", field(model, 50));
        m.put("device_resolution", field(profile.resolution, 20));
        m.put("system_type", "Android");
        m.put("device_type", "TV");
        m.put("app_language", "CHINESE");
        m.put("app_version", field(version, 30));
        m.put("sdk_version", field(sdkVersion, 30));
        m.put("os_version", field(profile.inferOsVersion(), 20));
        m.put("app_channel", field(appChannel, 50));
        m.put("data_time", field(dataTimeMs, 13));
        return m;
    }

    /** app/start 请求体（对应 Python {@code build_app_start_body}）。 */
    public static Map<String, Object> buildAppStartBody(DeviceProfile profile, String xUid,
                                                       String appChannel, String version,
                                                       long dataTimeMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "app_start_d1");
        body.put("value", buildReportCommonValue(profile, xUid, appChannel, version, "", dataTimeMs));
        return body;
    }

    /**
     * VDN appcommon 串（对应 Python {@code build_vdn_appcommon}）。
     * <p>紧凑 JSON，键排序，不转义斜杠。
     */
    public static String buildVdnAppcommon(String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adid", "");
        m.put("av", version);
        m.put("an", Identity.VDN_APP_NAME);
        m.put("ap", "cctv_app_tv");
        return com.gary.ysplive.core.util.Json.stringify(m);
    }

    /** 心跳请求体（对应 Python {@code heartbeat_flow} 里的 body）。 */
    public static Map<String, Object> buildHeartbeatBody(DeviceProfile profile, String xUid,
                                                         String appChannel, String version,
                                                         String cloudGuid, long dataTimeMs) {
        Map<String, Object> value = buildReportCommonValue(profile, xUid, appChannel, version, "",
                dataTimeMs);
        value.put("network_type", "WiFi");
        value.put("guid", cloudGuid);
        value.put("other", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "app_heartbeat");
        body.put("value", value);
        return body;
    }

    /** 设备信息上报体（对应 Python {@code device_info_report_flow}）。 */
    public static Map<String, Object> buildDeviceInfoBody(DeviceProfile profile, String xUid,
                                                          String appChannel, String version,
                                                          String cloudGuid, long dataTimeMs) {
        Map<String, Object> value = buildReportCommonValue(profile, xUid, appChannel, version, "",
                dataTimeMs);
        String sdkInt = profile.inferSdkInt();
        String systemInfo = profile.inferOsVersion();
        if (!sdkInt.isEmpty()) {
            systemInfo = systemInfo.isEmpty() ? sdkInt : systemInfo + "/" + sdkInt;
        }
        value.put("version", version);
        value.put("network_status", "WiFi");
        value.put("device_info", profile.brand + "-" + profile.model);
        value.put("manufacturer", profile.manufacturer);
        value.put("cpu_info", "");
        value.put("chip_info", profile.hardware);
        value.put("ram_info", "");
        value.put("memory_info", "");
        value.put("system_info", systemInfo);
        value.put("guid", cloudGuid);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", "app_device_info");
        body.put("value", value);
        return body;
    }

    /** live/v1/01 请求体（对应 Python {@code live_v1_01_flow} 里的 body）。 */
    public static Map<String, Object> buildLive01Body(String screenParam, String castModel,
                                                      String liveId) {
        Map<String, Object> deviceId = new LinkedHashMap<>();
        deviceId.put("serial", "");
        deviceId.put("imei", "");
        deviceId.put("android_id", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("screenParam", screenParam);
        body.put("rate", "");
        body.put("systemType", "ios");
        body.put("model", castModel);
        body.put("id", liveId);
        body.put("userId", Identity.DEFAULT_LIVE_USER_ID);
        body.put("clientSign", "cctvVideo");
        body.put("deviceId", deviceId);
        return body;
    }

    // ==================================================================
    // 响应解析
    // ==================================================================

    /**
     * 从响应 JSON 里挖结果码（对应 Python {@code parse_result_code}）。
     * <p>先看 {@code result/code/errCode/errcode/ret}，再递归进 {@code data/error/response}。
     *
     * @return 结果码；挖不到返回 {@code null}
     */
    public static Integer parseResultCode(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        for (String key : new String[] {"result", "code", "errCode", "errcode", "ret"}) {
            if (map.containsKey(key)) {
                Object raw = map.get(key);
                if (raw instanceof Boolean) {
                    continue;
                }
                if (raw instanceof Number) {
                    return ((Number) raw).intValue();
                }
                if (raw instanceof String) {
                    try {
                        return Integer.parseInt(((String) raw).trim());
                    } catch (NumberFormatException ignored) {
                        // 继续看下一个键
                    }
                }
            }
        }
        for (String key : new String[] {"data", "error", "response"}) {
            if (map.containsKey(key)) {
                Integer found = parseResultCode(map.get(key));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 从响应 JSON 里取云注册 guid（对应 Python {@code extract_guid}）。 */
    public static String extractGuid(Object value) {
        if (!(value instanceof Map)) {
            return "";
        }
        Object data = ((Map<?, ?>) value).get("data");
        if (!(data instanceof Map)) {
            return "";
        }
        Object guid = ((Map<?, ?>) data).get("guid");
        return guid instanceof String ? (String) guid : "";
    }

    // ==================================================================
    // VDN 签名
    // ==================================================================

    /** 生成 {@code APPRANDOMSTR}（对应 Python {@code generate_app_random_str}）。 */
    public static String generateAppRandomStr() {
        java.security.SecureRandom r = com.gary.ysplive.core.util.Crypto.random();
        return String.format(java.util.Locale.ROOT, "%08x-0000-%04x-0000-00000000%04x",
                r.nextInt(), r.nextInt() & 0xFFFF, r.nextInt() & 0xFFFF);
    }

    /** VDN 签名结果。 */
    public static final class VdnCode {
        public final String sign;
        public final String randomStr;

        VdnCode(String sign, String randomStr) {
            this.sign = sign;
            this.randomStr = randomStr;
        }
    }

    /**
     * 算 {@code APPSIGN}（对应 Python {@code compute_vdn_code}）：
     * {@code md5(AK + appSecret + randomStr)} 小写 hex。
     */
    public static VdnCode computeVdnCode(String appSecret) {
        return computeVdnCode(appSecret, null);
    }

    /** 同上，可指定随机串（测试用）。 */
    public static VdnCode computeVdnCode(String appSecret, String randomStr) {
        String r = randomStr != null ? randomStr : generateAppRandomStr();
        String sign = com.gary.ysplive.core.util.Crypto.md5Hex(Identity.AK + appSecret + r);
        return new VdnCode(sign, r);
    }

    // ==================================================================
    // 播放头
    // ==================================================================

    /** 兜底播放头（对应 Python {@code default_cctv_playback_headers}）。 */
    public static Map<String, String> defaultCctvPlaybackHeaders(String uid) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("UID", uid == null ? "" : uid);
        h.put("APPID", Identity.AK);
        h.put("Referer", "api.cctv.cn");
        h.put("User-Agent", "cctv_app_tv");
        return h;
    }

    /** 用 entry 里的签名头覆盖兜底头（对应 Python {@code cctv_playback_headers_for_entry}）。 */
    public static Map<String, String> cctvPlaybackHeadersForEntry(ChannelEntry entry) {
        Map<String, String> headers = defaultCctvPlaybackHeaders(entry.androidId);
        for (String key : new String[] {"UID", "APPID", "APPRANDOMSTR", "Referer", "User-Agent", "APPSIGN"}) {
            String v = entry.playbackHeaders.get(key);
            if (v != null && !v.isEmpty()) {
                headers.put(key, v);
            }
        }
        return headers;
    }

    /** 该 host 是否必须带签名头（{@code liveali} / {@code liveten}）。 */
    public static boolean livePlaybackHostNeedsSignedHeaders(String host) {
        String lower = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("liveali") || lower.contains("liveten");
    }

    /**
     * entry 是否缺少必须的签名头（对应 Python {@code entry_missing_signed_playback_headers}）。
     * <p>注意：只有 {@code liveali}/{@code liveten} 的地址才强制要求 APPID/APPSIGN/APPRANDOMSTR；
     * 其它 CDN 不缺也算可用。
     */
    public static boolean entryMissingSignedPlaybackHeaders(ChannelEntry entry) {
        String host = entry.finalHost != null && !entry.finalHost.isEmpty()
                ? entry.finalHost : urlHost(entry.finalUrl);
        if (!livePlaybackHostNeedsSignedHeaders(host)) {
            return false;
        }
        for (String key : new String[] {"APPID", "APPSIGN", "APPRANDOMSTR"}) {
            String v = entry.playbackHeaders.get(key);
            if (v == null || v.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分片路径是否属于该 m3u8（对应 Python {@code ts_path_matches_final_url}）。
     * <p>把 finalUrl 的 path 去掉 {@code .m3u8} 尾巴当作前缀比对。
     */
    public static boolean tsPathMatchesFinalUrl(String tsPath, String finalUrl) {
        if (tsPath == null) {
            return false;
        }
        String path = urlPath(finalUrl);
        if (path == null) {
            return false;
        }
        if (path.endsWith(".m3u8")) {
            path = path.substring(0, path.length() - ".m3u8".length());
        }
        return !path.isEmpty() && tsPath.startsWith(path);
    }

    // ==================================================================
    // URL 小工具
    // ==================================================================

    /** 取 URL 的 host（小写）；解析失败返回空串。 */
    public static String urlHost(String url) {
        try {
            java.net.URI u = new java.net.URI(url == null ? "" : url);
            String h = u.getHost();
            return h == null ? "" : h;
        } catch (java.net.URISyntaxException e) {
            return "";
        }
    }

    /** 取 URL 的 path；解析失败返回 {@code null}。 */
    public static String urlPath(String url) {
        try {
            String p = new java.net.URI(url == null ? "" : url).getPath();
            return p == null ? "" : p;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** 是否是一个 {@code .ts} 分片地址（看 path 后缀）。 */
    public static boolean isTsUrl(String value) {
        String path = urlPath(value);
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".ts");
    }

    // ==================================================================
    // playlist 改写
    // ==================================================================

    /**
     * 把 playlist 里的相对地址补成绝对地址（对应 Python {@code rewrite_playlist_urls}）。
     * <p>注释行与空行原样保留；结尾统一补 {@code \n}。
     */
    public static String rewritePlaylistUrls(String text, String baseUrl) {
        String out = replaceNonCommentLines(text, line -> {
            String stripped = line.trim();
            if (stripped.isEmpty()) {
                return line;
            }
            return joinUrl(baseUrl, stripped);
        });
        return out;
    }

    /** 行处理回调。 */
    public interface LineFn {
        String apply(String line);
    }

    /** 对每行（非空且非注释）套一个函数，行尾统一补 {@code \n}。 */
    static String replaceNonCommentLines(String text, LineFn fn) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (String line : rsLines(text)) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                out.append(line).append('\n');
            } else {
                out.append(fn.apply(line)).append('\n');
            }
        }
        return out.toString();
    }

    /** 按 {@code \n} 切行（容忍 {@code \r\n}，末尾换行不产生空行、行内 {@code \r} 去掉）。 */
    public static List<String> rsLines(String text) {
        String norm = (text == null ? "" : text).replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < norm.length(); i++) {
            if (norm.charAt(i) == '\n') {
                lines.add(norm.substring(start, i));
                start = i + 1;
            }
        }
        if (start < norm.length()) {
            lines.add(norm.substring(start));
        }
        return lines;
    }

    /**
     * URL 拼接（等价于 {@code urllib.parse.urljoin} 的常用分支）。
     * <p>base 无 scheme/host 时直接返回 ref（与 Python 的 {@code has_base} 判定一致）。
     */
    public static String joinUrl(String base, String ref) {
        if (ref == null || ref.isEmpty()) {
            return base == null ? "" : base;
        }
        String lower = ref.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return ref;
        }
        String[] bp = splitUrl(base);
        if (bp == null) {
            return ref.trim();
        }
        String scheme = bp[0];
        String authority = bp[1];
        String basePath = bp[2];
        String origin = scheme + "://" + authority;
        if (ref.startsWith("//")) {
            return scheme + ":" + ref;
        }
        if (ref.startsWith("?")) {
            return origin + basePath + ref;
        }
        if (ref.startsWith("#")) {
            return origin + basePath + ref;
        }
        if (ref.startsWith("/")) {
            return origin + normalizePath(ref);
        }
        int slash = basePath.lastIndexOf('/');
        String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "/";
        return origin + normalizePath(dir + ref);
    }

    /** 解析 URL 成 {@code {scheme, authority, path}}；不合法返回 {@code null}。 */
    static String[] splitUrl(String url) {
        if (url == null) {
            return null;
        }
        int idx = url.indexOf("://");
        if (idx <= 0) {
            return null;
        }
        String scheme = url.substring(0, idx);
        String rest = url.substring(idx + 3);
        int start = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                start = i;
                break;
            }
        }
        if (start == 0) {
            return null;
        }
        String authority = rest.substring(0, start);
        String path = start < rest.length() ? rest.substring(start) : "/";
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        if (path.isEmpty()) {
            path = "/";
        }
        return new String[] {scheme, authority, path};
    }

    /** 折叠 {@code .} / {@code ..} 段。 */
    static String normalizePath(String path) {
        String[] segs = path.split("/", -1);
        List<String> out = new ArrayList<>(segs.length);
        for (String seg : segs) {
            if (".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(out.get(i));
        }
        String r = sb.toString();
        return r.isEmpty() ? "/" : r;
    }

    /** 频道 slug 对应的央视频 live_id（表里没有则空串）。 */
    public static String liveIdFor(String slug) {
        com.gary.ysplive.core.data.ChannelSpec spec = ChannelTable.lookup(slug);
        return spec == null ? "" : spec.sid;
    }
}
