package com.gary.ysplive.core.device;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化的设备身份状态：设备档案 + 屏幕参数 + 云注册 guid。
 * <p>对应 Python 参考实现的 {@code DeviceState}。落盘成 JSON（默认 {@code device-state.json}），
 * 下次启动直接复用同一台"电视"，避免每次重启都重新注册。
 * <p>可变（注册成功后回填 guid），由持有者加锁。
 */
public final class DeviceState {

    /** 磁盘格式版本。 */
    public int schemaVersion = 1;
    /** 模板来源标识，例如 {@code sony_8k_pool.XR-85Z9K}。 */
    public String profileSource = "";
    public DeviceProfile profile = new DeviceProfile(
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
    /** 电视上报的屏幕参数，例如 {@code 7680-4320-280}。 */
    public String screenParam = "";
    /** 投屏机型名，例如 {@code XR-85Z9K}。 */
    public String castModel = "";
    /** 由档案派生的 X-Uid（sha1 大写）。 */
    public String xUid = "";
    /** 云注册返回的 GUID（形如 {@code XXXX-...-IMLGJDBBQMUM}）。 */
    public String cloudGuid = "";
    /** 首次注册成功的时间（秒）。 */
    public double registeredAt = 0.0;
    /** 最后更新时间（秒）。 */
    public double updatedAt = 0.0;

    public DeviceState() { }

    /** 随机生成一份全新设备身份（对应 Python {@code default_device_state}）。 */
    public static DeviceState createDefault() {
        DevicePool.Template template = DevicePool.randomDeviceTemplate();
        DeviceProfile profile = DeviceProfile.fromTemplate(
                template, Identity.randomHexString(16), Identity.randomMacAddress());
        DeviceState st = new DeviceState();
        st.schemaVersion = 1;
        st.profileSource = template.source;
        st.profile = profile;
        st.screenParam = template.screenParam;
        st.castModel = template.castModel;
        st.xUid = Identity.computeXUid(profile);
        st.cloudGuid = "";
        st.registeredAt = 0.0;
        st.updatedAt = nowSec();
        return st;
    }

    private static double nowSec() {
        return System.currentTimeMillis() / 1000.0;
    }

    /** 转成可序列化的 Map（键名与 Python {@code dataclasses.asdict} 一致）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", schemaVersion);
        m.put("profile_source", profileSource);
        m.put("profile", profile.toMap());
        m.put("screen_param", screenParam);
        m.put("cast_model", castModel);
        m.put("x_uid", xUid);
        m.put("cloud_guid", cloudGuid);
        m.put("registered_at", registeredAt);
        m.put("updated_at", updatedAt);
        return m;
    }

    /** 从 Map 还原。 */
    @SuppressWarnings("unchecked")
    public static DeviceState fromMap(Map<String, Object> m) {
        DeviceState st = new DeviceState();
        if (m == null) {
            return st;
        }
        st.schemaVersion = intOf(m.get("schema_version"), 1);
        st.profileSource = str(m.get("profile_source"));
        Object p = m.get("profile");
        if (p instanceof Map) {
            st.profile = DeviceProfile.fromMap((Map<String, Object>) p);
        }
        st.screenParam = str(m.get("screen_param"));
        st.castModel = str(m.get("cast_model"));
        st.xUid = str(m.get("x_uid"));
        st.cloudGuid = str(m.get("cloud_guid"));
        st.registeredAt = dblOf(m.get("registered_at"));
        st.updatedAt = dblOf(m.get("updated_at"));
        return st;
    }

    /**
     * 清洗并补全从磁盘读出来的状态（对应 Python {@code load_device_state} 的校验段）。
     *
     * @return true 表示这份状态可用；false 表示应当丢弃并重新生成
     */
    public boolean normalizeAfterLoad() {
        if (profile == null || profile.androidId.length() != 16 || profile.mac.isEmpty()) {
            return false;
        }
        xUid = Identity.computeXUid(profile);
        if (screenParam == null || screenParam.isEmpty()) {
            screenParam = "7680-4320-280";
        }
        if (castModel == null || castModel.isEmpty()) {
            castModel = profile.model;
        }
        return true;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double dblOf(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @Override
    public String toString() {
        return "DeviceState{" + profileSource + ", " + profile.brand + " " + profile.model
                + ", guid=" + (cloudGuid.isEmpty() ? "-" : "有") + '}';
    }
}
