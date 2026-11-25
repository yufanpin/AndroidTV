package com.gary.ysplive.core.device;

import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.util.Crypto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 央视频设备身份：X-Uid 派生、X-Fingerprint 计算、以及各套请求头的构造。
 * <p>逐行复刻 Python 参考实现的 {@code java_string_hashcode} / {@code java_uuid_from_hashes} /
 * {@code native_day0_ms} / {@code compute_fingerprint} / {@code compute_x_uid} /
 * {@code build_identity} / {@code fresh_headers} / {@code root_headers} / {@code build_collect_headers}。
 *
 * <h3>为什么有 Java 的 hashCode 和 UUID</h3>
 * 央视频的 {@code X-Uid} 算法是从安卓客户端抄来的：它先算两个 Java {@code String.hashCode()}，
 * 拼成一个 128 位"UUID"，再跟 android_id 一起 sha1。所以这里必须用 Java 的 32 位溢出语义，
 * 而不能用 Python 的任意精度整数。
 */
public final class Identity {

    private static final String TAG = "identity";

    /** 全局签名盐，出现在 X-Fingerprint 与 VDN 签名里。 */
    public static final String AK = "9f5c54c4ed0e50109b800f7e28fec205";

    /** 默认 Accept-Language。 */
    public static final String DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.8";
    /** 上报/注册用的设备名。 */
    public static final String DEFAULT_DEVICE_NAME = "央视频电视投屏助手";
    /** VDN 接口的 app name。 */
    public static final String VDN_APP_NAME = "央视频电视投屏助手";
    /** 默认 live userId。 */
    public static final String DEFAULT_LIVE_USER_ID = "BAEBFF2B-C516-4F34-ABC0-A824A6461CBD";
    /** 埋点上报 appKey。 */
    public static final String REPORT_APP_KEY = "1178c84d-4818-44ff-b415-02106e87e144";
    /** 埋点 SDK 版本。 */
    public static final String COLLECT_SDK_VERSION = "1.0.0";

    private Identity() { }

    // ==================================================================
    // 哈希与派生
    // ==================================================================

    /**
     * 等价于 Java {@code String.hashCode()}：{@code h = h*31 + cp}，32 位溢出，返回有符号 int。
     * <p>按 Unicode 码点遍历（与 Python 的 {@code for ch in value} 一致），因此非 BMP 字符也对。
     */
    public static int javaStringHashCode(String value) {
        if (value == null) {
            return 0;
        }
        int h = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            h = h * 31 + cp;
            i += Character.charCount(cp);
        }
        return h;
    }

    /**
     * 把两个 32 位哈希拼成 32 位小写 hex（等价于 Python 的
     * {@code f'{msb << 64 | lsb:032x}'}）。
     * <p><b>关键</b>：Python 侧是 {@code h & 0xFFFFFFFFFFFFFFFF}，即**符号扩展到 64 位**
     * （负数会变成 {@code fffffffffd9b20da} 这种）。所以这里必须写成 {@code (long) hash}
     * 做符号扩展，而不是 {@code hash & 0xFFFFFFFFL}（那样高 32 位会被清零，X-Uid 就全错了）。
     */
    public static String javaUuidFromHashes(int msbHash, int lsbHash) {
        return String.format(java.util.Locale.ROOT, "%016x%016x", (long) msbHash, (long) lsbHash);
    }

    /**
     * 北京时间当天 0 点对应的时间戳（毫秒）。
     * <p>{@code 86400000 * ((now_s + 28800) // 86400) - 28800000}，即按 UTC+8 切日。
     */
    public static long nativeDay0Ms(long nowSec) {
        return 86400000L * Math.floorDiv(nowSec + 28800L, 86400L) - 28800000L;
    }

    /** X-Fingerprint 计算的三元组。 */
    public static final class Fingerprint {
        /** 最终下发的指纹（两层 sha256）。 */
        public final String value;
        /** 参与哈希的毫秒时间戳。 */
        public final long timestampMs;
        /** 当天 0 点毫秒。 */
        public final long day0Ms;

        Fingerprint(String value, long timestampMs, long day0Ms) {
            this.value = value;
            this.timestampMs = timestampMs;
            this.day0Ms = day0Ms;
        }
    }

    /**
     * 计算 X-Fingerprint（对应 Python {@code compute_fingerprint}）。
     * <p>{@code sha256(sha256(AK + xUid + nowMs + day0Ms))}。
     */
    public static Fingerprint computeFingerprint(String xUid, long nowMs) {
        long day0 = nativeDay0Ms(nowMs / 1000L);
        String first = Crypto.sha256Hex(AK + xUid + nowMs + day0);
        return new Fingerprint(Crypto.sha256Hex(first), nowMs, day0);
    }

    /**
     * 由设备档案派生 X-Uid（对应 Python {@code compute_x_uid}）。
     * <p>把一堆 Build 字段拼成串 → 两个 Java hashCode → 128 位伪 UUID →
     * 与 android_id 拼接后 sha1 大写。
     */
    public static String computeXUid(DeviceProfile profile) {
        String buildIdentityStr = "1698"
                + profile.hardware + profile.board + profile.brand + profile.device
                + profile.manufacturer + profile.model + profile.product + profile.tags
                + profile.buildType + profile.user + profile.resolution + profile.mac;
        String uuidPart = javaUuidFromHashes(
                javaStringHashCode(buildIdentityStr), javaStringHashCode(profile.model));
        return Crypto.sha1Upper(profile.androidId + "|" + uuidPart);
    }

    /** 16 位小写 hex 随机串（相当于安卓 ANDROID_ID）。 */
    public static String randomHexString(int length) {
        StringBuilder sb = new StringBuilder(length);
        java.security.SecureRandom r = Crypto.random();
        for (int i = 0; i < length; i++) {
            sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    /** 随机 MAC（首字节置本地管理位、清组播位）。 */
    public static String randomMacAddress() {
        byte[] raw = new byte[6];
        Crypto.random().nextBytes(raw);
        raw[0] = (byte) ((raw[0] | 2) & 254);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            int b = raw[i] & 0xFF;
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 当前毫秒时间戳。 */
    public static long currentTimeMs() {
        return System.currentTimeMillis();
    }

    // ==================================================================
    // Identity
    // ==================================================================

    /** 一次会话的身份：X-Uid + X-Fingerprint + 基础请求头模板。 */
    public static final class IdentityData {
        public String xUid = "";
        public String xFingerprint = "";
        public long fingerprintTimestampMs;
        /** 请求头模板（大小写敏感，key 用央视频的写法）。 */
        public final Map<String, String> headers = new LinkedHashMap<>();

        @Override
        public String toString() {
            return "Identity{xUid=" + xUid + '}';
        }
    }

    /**
     * 构造身份与请求头模板（对应 Python {@code build_identity}）。
     *
     * @param appChannel 渠道名，设备协议固定 {@code dangbei}
     * @param version    客户端版本，设备协议固定 {@code 1.4.1}
     */
    public static IdentityData buildIdentity(DeviceProfile profile, String appChannel, String version) {
        IdentityData id = new IdentityData();
        id.xUid = computeXUid(profile);
        Fingerprint fp = computeFingerprint(id.xUid, currentTimeMs());
        id.xFingerprint = fp.value;
        id.fingerprintTimestampMs = fp.timestampMs;

        id.headers.put("Accept", "application/json");
        id.headers.put("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        id.headers.put("Referer", "api.cctv.cn");
        id.headers.put("User-Agent", "cctv_app_tv");
        id.headers.put("UID", profile.androidId);
        id.headers.put("appChannel", appChannel);
        id.headers.put("X-Uid", id.xUid);
        id.headers.put("X-Fingerprint", id.xFingerprint);
        id.headers.put("X-Version", version);
        id.headers.put("Content-Type", "application/json; charset=utf-8");
        id.headers.put("Connection", "Keep-Alive");
        id.headers.put("Accept-Encoding", "gzip");
        id.headers.put("Cache-Control", "no-cache");
        return id;
    }

    /**
     * 基于模板刷出一套本次请求头：刷新 X-Timestamp / X-Nonce，其余字段照抄
     * （对应 Python {@code fresh_headers}）。
     *
     * @param contentType 非空时覆盖 Content-Type；传 {@code null} 表示沿用模板
     * @param accept      非空时覆盖 Accept
     * @param forceTs     非 null 时用这个时间戳当 X-Timestamp（app/start 要与指纹一致）
     */
    public static Map<String, String> freshHeaders(Map<String, String> template, String contentType,
                                                   String accept, Long forceTs) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> tpl = template == null ? new LinkedHashMap<String, String>() : template;
        if (accept != null) {
            headers.put("Accept", accept);
        }
        long reqTs = forceTs != null ? forceTs : currentTimeMs();
        headers.put("X-Timestamp", String.valueOf(reqTs));
        headers.put("X-Nonce", java.util.UUID.randomUUID().toString());
        for (String key : new String[] {"Accept-Language", "Referer", "User-Agent", "UID",
                "appChannel", "X-Uid", "X-Fingerprint", "X-Version"}) {
            String v = tpl.get(key);
            if (v != null) {
                headers.put(key, v);
            }
        }
        if (contentType != null && !contentType.isEmpty()) {
            headers.put("Content-Type", contentType);
        }
        for (String key : new String[] {"Connection", "Accept-Encoding", "Cache-Control"}) {
            String v = tpl.get(key);
            if (v != null) {
                headers.put(key, v);
            }
        }
        return headers;
    }

    /** 造一个匿名"ROOT"头（预热/字典等不需要真实身份的接口用）。 */
    public static Map<String, String> rootHeaders(String version) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Uid", "ROOT");
        h.put("X-Fingerprint", "ROOT");
        h.put("X-Nonce", java.util.UUID.randomUUID().toString());
        h.put("X-Timestamp", String.valueOf(currentTimeMs()));
        h.put("X-Version", version);
        h.put("UID", "ROOT");
        h.put("Referer", "api.cctv.cn");
        h.put("User-Agent", "cctv_app_tv");
        h.put("appChannel", "ROOT");
        h.put("Connection", "Keep-Alive");
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    /** 埋点上报用的头（对应 Python {@code build_collect_headers}）。 */
    public static Map<String, String> buildCollectHeaders(DeviceProfile profile) {
        String release = profile.inferOsVersion();
        String ua = "Dalvik/2.1.0 (Linux; U; Android "
                + (release.isEmpty() ? "Android" : release)
                + "; " + profile.model + " Build/" + profile.versionId + ")";
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-type", "application/x-www-form-urlencoded");
        h.put("Charset", "UTF-8");
        h.put("User-Agent", ua);
        h.put("Connection", "Keep-Alive");
        h.put("Accept-Encoding", "gzip");
        return h;
    }

    /** 去掉 header 值里的 CR/LF，避免头注入（对应 Python {@code header_value_clean}）。 */
    public static String headerValueClean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "").replace("\n", "");
    }

    /** 供 Resolver 复用的日志出口（统一 tag 前缀）。 */
    static void logInfo(String channel, String message) {
        Log.i(TAG, channel == null || channel.isEmpty() ? message : "[" + channel + "] " + message);
    }
}
