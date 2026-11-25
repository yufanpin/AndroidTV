package com.gary.ysplive.core.device;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份"电视设备档案"：一台随机生成的安卓电视身份。
 * <p>对应 Python 参考实现的 {@code DeviceProfile} 数据类。字段全部参与
 * {@code X-Uid} 的哈希计算与上报体，所以必须整体保持一致。
 * <p>不可变（构造后只读），可安全跨线程共享。
 */
public final class DeviceProfile {

    /** 16 位小写 hex，等同安卓 {@code Settings.Secure.ANDROID_ID}。 */
    public final String androidId;
    /** MAC 地址 {@code aa:bb:cc:dd:ee:ff}。 */
    public final String mac;
    public final String hardware;
    public final String board;
    public final String brand;
    public final String device;
    public final String manufacturer;
    public final String model;
    public final String product;
    public final String tags;
    public final String buildType;
    public final String user;
    /** 形如 {@code 7680*4320}。 */
    public final String resolution;
    public final String display;
    public final String versionId;
    public final String host;
    public final String fingerprint;
    /** 上报用的机型名（去掉品牌前缀与空格）。 */
    public final String reportModel;

    public DeviceProfile(String androidId, String mac, String hardware, String board, String brand,
                         String device, String manufacturer, String model, String product, String tags,
                         String buildType, String user, String resolution, String display,
                         String versionId, String host, String fingerprint, String reportModel) {
        this.androidId = nn(androidId);
        this.mac = nn(mac);
        this.hardware = nn(hardware);
        this.board = nn(board);
        this.brand = nn(brand);
        this.device = nn(device);
        this.manufacturer = nn(manufacturer);
        this.model = nn(model);
        this.product = nn(product);
        this.tags = nn(tags);
        this.buildType = nn(buildType);
        this.user = nn(user);
        this.resolution = nn(resolution);
        this.display = nn(display);
        this.versionId = nn(versionId);
        this.host = nn(host);
        this.fingerprint = nn(fingerprint);
        this.reportModel = nn(reportModel);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    /**
     * 由模板生成一份完整档案（对应 Python {@code device_profile_from_template}）。
     *
     * @param template  设备模板
     * @param androidId 16 位 hex
     * @param mac       MAC 地址
     */
    public static DeviceProfile fromTemplate(DevicePool.Template template, String androidId, String mac) {
        String brandId = DevicePool.sanitizeProfileId(template.brand);
        String modelId = DevicePool.sanitizeProfileId(template.model);
        String device = brandId + "_" + modelId;
        String product = brandId + "_" + modelId;
        String resolution = DevicePool.resolutionFromScreenParam(template.screenParam);
        String display = template.model + "-user 13 " + template.versionId + " 2024 release-keys";
        String host = brandId + "-tv-build";
        // 注意：Python 是 f"{manufacturer}/{product}/{device}:13/..."，只有一个斜杠。
        // 早期误抄成 "//" 会导致 device_fingerprint 上报值不对。
        String fingerprint = template.manufacturer + "/" + product + "/" + device
                + ":13/" + template.versionId + "/2024:user/release-keys";
        return new DeviceProfile(
                androidId, mac,
                template.hardware, template.board, template.brand,
                device, template.manufacturer, template.model, product,
                "release-keys", "user", "build",
                resolution, display, template.versionId, host, fingerprint, template.reportModel);
    }

    /**
     * 从 Android {@code Build} 字符串推断系统版本，例如 {@code 13}。
     * <p>对应 Python {@code infer_os_version}：取 fingerprint 第一个 {@code :} 之后、
     * 第一个 {@code /} 之前的那段。
     */
    public String inferOsVersion() {
        int colon = fingerprint.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String tail = fingerprint.substring(colon + 1);
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return "";
        }
        return tail.substring(0, slash);
    }

    /** 由 {@link #inferOsVersion()} 推断 SDK_INT；未知返回空串。对应 Python {@code infer_sdk_int}。 */
    public String inferSdkInt() {
        String osv = inferOsVersion();
        if (osv.isEmpty()) {
            return "";
        }
        String major = osv.split("\\.", -1)[0];
        switch (major) {
            case "13": return "33";
            case "12": return "31";
            case "11": return "30";
            case "10": return "29";
            case "9":  return "28";
            case "8":  return "26";
            case "7":  return "24";
            case "6":  return "23";
            default:   return "";
        }
    }

    /** 上报体里的机型名：优先用模板给的 reportModel，否则从 model 里剥掉厂商名与空格。 */
    public String reportModelOrDerived() {
        if (!reportModel.isEmpty()) {
            return reportModel;
        }
        return model.replace(manufacturer, "").replace(" ", "");
    }

    /** 转成可序列化的 Map（键名与 Python {@code dataclasses.asdict} 的蛇形命名一致）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("android_id", androidId);
        m.put("mac", mac);
        m.put("hardware", hardware);
        m.put("board", board);
        m.put("brand", brand);
        m.put("device", device);
        m.put("manufacturer", manufacturer);
        m.put("model", model);
        m.put("product", product);
        m.put("tags", tags);
        m.put("build_type", buildType);
        m.put("user", user);
        m.put("resolution", resolution);
        m.put("display", display);
        m.put("version_id", versionId);
        m.put("host", host);
        m.put("fingerprint", fingerprint);
        m.put("report_model", reportModel);
        return m;
    }

    /** 从 Map 还原；缺失字段按空串处理（对应 Python {@code DeviceProfile.from_dict}）。 */
    public static DeviceProfile fromMap(Map<String, Object> m) {
        Map<String, Object> d = m == null ? new LinkedHashMap<String, Object>() : m;
        return new DeviceProfile(
                str(d.get("android_id")), str(d.get("mac")), str(d.get("hardware")),
                str(d.get("board")), str(d.get("brand")), str(d.get("device")),
                str(d.get("manufacturer")), str(d.get("model")), str(d.get("product")),
                str(d.get("tags")), str(d.get("build_type")), str(d.get("user")),
                str(d.get("resolution")), str(d.get("display")), str(d.get("version_id")),
                str(d.get("host")), str(d.get("fingerprint")), str(d.get("report_model")));
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public String toString() {
        return "DeviceProfile{" + brand + " " + model + ", android_id=" + androidId + '}';
    }
}
