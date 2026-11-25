package com.gary.ysplive.core.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 设备档案模板池（随机电视身份）。
 * <p>{@link #POOL} 逐字复制自参考实现 ysp-live-v9.5.py 的 {@code DEVICE_PROFILE_POOL}
 * （机器生成，请勿手工增删改条目；要改请改 tools/gen_device_pool.py 后重新生成）。
 * <p>共 53 条，其中 28 条是 8K 电视（{@code source} 里带 {@code 8k}）。
 * {@link #randomDeviceTemplate()} 优先从 8K 池里抽，这样 {@code screenParam} 才够 7680-4320，
 * 央视频才会下发 4K/8K 码率。
 */
public final class DevicePool {

    private DevicePool() { }

    /** 一条设备模板，字段与 Python 字典一一对应。 */
    public static final class Template {
        public final String source;
        public final String brand;
        public final String manufacturer;
        public final String model;
        public final String reportModel;
        public final String hardware;
        public final String board;
        public final String versionId;
        public final String screenParam;
        public final String castModel;

        Template(String source, String brand, String manufacturer, String model, String reportModel,
                 String hardware, String board, String versionId, String screenParam, String castModel) {
            this.source = source;
            this.brand = brand;
            this.manufacturer = manufacturer;
            this.model = model;
            this.reportModel = reportModel;
            this.hardware = hardware;
            this.board = board;
            this.versionId = versionId;
            this.screenParam = screenParam;
            this.castModel = castModel;
        }

        /** 是否 8K 机型（对应 Python 的 {@code '8k' in source}）。 */
        public boolean is8k() {
            return source.toLowerCase(Locale.ROOT).contains("8k");
        }

        @Override
        public String toString() {
            return source + " (" + brand + " " + model + ")";
        }
    }

    /** 设备档案模板池。 */
    public static final List<Template> POOL;

    /** 只含 8K 机型的子池。 */
    public static final List<Template> POOL_8K;

    static {
        List<Template> all = new ArrayList<Template>(53);
        all.add(new Template("sony_8k_pool.XR-85Z9K", "Sony", "Sony", "XR-85Z9K", "XR85Z9K", "mt5895", "mt5895", "SONYTV.2022.XR_85Z9K", "7680-4320-280", "XR-85Z9K"));
        all.add(new Template("sony_8k_pool.XR-75Z9K", "Sony", "Sony", "XR-75Z9K", "XR75Z9K", "mt5895", "mt5895", "SONYTV.2022.XR_75Z9K", "7680-4320-260", "XR-75Z9K"));
        all.add(new Template("sony_8k_pool.XR-85Z9J", "Sony", "Sony", "XR-85Z9J", "XR85Z9J", "mt5895", "mt5895", "SONYTV.2021.XR_85Z9J", "7680-4320-280", "XR-85Z9J"));
        all.add(new Template("sony_8k_pool.XR-75Z9J", "Sony", "Sony", "XR-75Z9J", "XR75Z9J", "mt5895", "mt5895", "SONYTV.2021.XR_75Z9J", "7680-4320-260", "XR-75Z9J"));
        all.add(new Template("sony_8k_pool.KD-98ZG9", "Sony", "Sony", "KD-98ZG9", "KD98ZG9", "mt5893", "mt5893", "SONYTV.2019.KD_98ZG9", "7680-4320-320", "KD-98ZG9"));
        all.add(new Template("sony_8k_pool.KD-85ZG9", "Sony", "Sony", "KD-85ZG9", "KD85ZG9", "mt5893", "mt5893", "SONYTV.2019.KD_85ZG9", "7680-4320-280", "KD-85ZG9"));
        all.add(new Template("sony_8k_pool.KD-85ZH8", "Sony", "Sony", "KD-85ZH8", "KD85ZH8", "mt5893", "mt5893", "SONYTV.2020.KD_85ZH8", "7680-4320-280", "KD-85ZH8"));
        all.add(new Template("sony_8k_pool.KD-75ZH8", "Sony", "Sony", "KD-75ZH8", "KD75ZH8", "mt5893", "mt5893", "SONYTV.2020.KD_75ZH8", "7680-4320-260", "KD-75ZH8"));
        all.add(new Template("samsung_8k_pool.QA85QN900A", "Samsung", "Samsung", "QA85QN900A", "QA85QN900A", "s5e9925", "neo8k", "SAMSUNGTV.2021.QN900A", "7680-4320-280", "QA85QN900A"));
        all.add(new Template("samsung_8k_pool.QA75QN900A", "Samsung", "Samsung", "QA75QN900A", "QA75QN900A", "s5e9925", "neo8k", "SAMSUNGTV.2021.QN900A", "7680-4320-260", "QA75QN900A"));
        all.add(new Template("samsung_8k_pool.QA85QN900B", "Samsung", "Samsung", "QA85QN900B", "QA85QN900B", "s5e9925", "neo8k", "SAMSUNGTV.2022.QN900B", "7680-4320-280", "QA85QN900B"));
        all.add(new Template("samsung_8k_pool.QA75QN900B", "Samsung", "Samsung", "QA75QN900B", "QA75QN900B", "s5e9925", "neo8k", "SAMSUNGTV.2022.QN900B", "7680-4320-260", "QA75QN900B"));
        all.add(new Template("samsung_8k_pool.QA85QN900C", "Samsung", "Samsung", "QA85QN900C", "QA85QN900C", "s5e9935", "neo8k", "SAMSUNGTV.2023.QN900C", "7680-4320-280", "QA85QN900C"));
        all.add(new Template("samsung_8k_pool.QA75QN900C", "Samsung", "Samsung", "QA75QN900C", "QA75QN900C", "s5e9935", "neo8k", "SAMSUNGTV.2023.QN900C", "7680-4320-260", "QA75QN900C"));
        all.add(new Template("samsung_8k_pool.QA85QN900D", "Samsung", "Samsung", "QA85QN900D", "QA85QN900D", "s5e9945", "neo8k", "SAMSUNGTV.2024.QN900D", "7680-4320-280", "QA85QN900D"));
        all.add(new Template("samsung_8k_pool.QA98QN990C", "Samsung", "Samsung", "QA98QN990C", "QA98QN990C", "s5e9935", "neo8k", "SAMSUNGTV.2023.QN990C", "7680-4320-320", "QA98QN990C"));
        all.add(new Template("samsung_8k_pool.QA85QN800C", "Samsung", "Samsung", "QA85QN800C", "QA85QN800C", "s5e9935", "neo8k", "SAMSUNGTV.2023.QN800C", "7680-4320-280", "QA85QN800C"));
        all.add(new Template("samsung_8k_pool.QA75QN800D", "Samsung", "Samsung", "QA75QN800D", "QA75QN800D", "s5e9945", "neo8k", "SAMSUNGTV.2024.QN800D", "7680-4320-260", "QA75QN800D"));
        all.add(new Template("lg_8k_pool.OLED88Z1PCA", "LG", "LGE", "OLED88Z1PCA", "OLED88Z1PCA", "alpha9gen4", "lg8k", "LGTV.2021.OLED88Z1", "7680-4320-320", "OLED88Z1PCA"));
        all.add(new Template("lg_8k_pool.OLED77Z1PCA", "LG", "LGE", "OLED77Z1PCA", "OLED77Z1PCA", "alpha9gen4", "lg8k", "LGTV.2021.OLED77Z1", "7680-4320-260", "OLED77Z1PCA"));
        all.add(new Template("lg_8k_pool.OLED88Z2PCA", "LG", "LGE", "OLED88Z2PCA", "OLED88Z2PCA", "alpha9gen5", "lg8k", "LGTV.2022.OLED88Z2", "7680-4320-320", "OLED88Z2PCA"));
        all.add(new Template("lg_8k_pool.OLED77Z2PCA", "LG", "LGE", "OLED77Z2PCA", "OLED77Z2PCA", "alpha9gen5", "lg8k", "LGTV.2022.OLED77Z2", "7680-4320-260", "OLED77Z2PCA"));
        all.add(new Template("lg_8k_pool.OLED88Z3PCA", "LG", "LGE", "OLED88Z3PCA", "OLED88Z3PCA", "alpha9gen6", "lg8k", "LGTV.2023.OLED88Z3", "7680-4320-320", "OLED88Z3PCA"));
        all.add(new Template("lg_8k_pool.OLED77Z3PCA", "LG", "LGE", "OLED77Z3PCA", "OLED77Z3PCA", "alpha9gen6", "lg8k", "LGTV.2023.OLED77Z3", "7680-4320-260", "OLED77Z3PCA"));
        all.add(new Template("lg_8k_pool.OLED88Z4PCA", "LG", "LGE", "OLED88Z4PCA", "OLED88Z4PCA", "alpha9gen7", "lg8k", "LGTV.2024.OLED88Z4", "7680-4320-320", "OLED88Z4PCA"));
        all.add(new Template("lg_8k_pool.86QNED99", "LG", "LGE", "86QNED99", "86QNED99", "alpha9gen4", "lg8k", "LGTV.2021.86QNED99", "7680-4320-300", "86QNED99"));
        all.add(new Template("sony_4k_pool.XR-85X95K", "Sony", "Sony", "XR-85X95K", "XR85X95K", "mt5895", "mt5895", "SONYTV.2022.XR_85X95K", "3840-2160-300", "XR-85X95K"));
        all.add(new Template("sony_4k_pool.XR-75X95K", "Sony", "Sony", "XR-75X95K", "XR75X95K", "mt5895", "mt5895", "SONYTV.2022.XR_75X95K", "3840-2160-280", "XR-75X95K"));
        all.add(new Template("sony_4k_pool.XR-65X90K", "Sony", "Sony", "XR-65X90K", "XR65X90K", "mt5895", "mt5895", "SONYTV.2022.XR_65X90K", "3840-2160-260", "XR-65X90K"));
        all.add(new Template("sony_4k_pool.XR-55X90K", "Sony", "Sony", "XR-55X90K", "XR55X90K", "mt5895", "mt5895", "SONYTV.2022.XR_55X90K", "3840-2160-240", "XR-55X90K"));
        all.add(new Template("sony_4k_pool.XR-65A95K", "Sony", "Sony", "XR-65A95K", "XR65A95K", "mt5895", "mt5895", "SONYTV.2022.XR_65A95K", "3840-2160-260", "XR-65A95K"));
        all.add(new Template("samsung_4k_pool.QA85QN90C", "Samsung", "Samsung", "QA85QN90C", "QA85QN90C", "s5e9935", "neo4k", "SAMSUNGTV.2023.QN90C", "3840-2160-300", "QA85QN90C"));
        all.add(new Template("samsung_4k_pool.QA75QN90C", "Samsung", "Samsung", "QA75QN90C", "QA75QN90C", "s5e9935", "neo4k", "SAMSUNGTV.2023.QN90C", "3840-2160-280", "QA75QN90C"));
        all.add(new Template("samsung_4k_pool.QA65QN90C", "Samsung", "Samsung", "QA65QN90C", "QA65QN90C", "s5e9935", "neo4k", "SAMSUNGTV.2023.QN90C", "3840-2160-260", "QA65QN90C"));
        all.add(new Template("samsung_4k_pool.QA55QN90C", "Samsung", "Samsung", "QA55QN90C", "QA55QN90C", "s5e9935", "neo4k", "SAMSUNGTV.2023.QN90C", "3840-2160-240", "QA55QN90C"));
        all.add(new Template("samsung_4k_pool.QA65S95C", "Samsung", "Samsung", "QA65S95C", "QA65S95C", "s5e9935", "oled4k", "SAMSUNGTV.2023.S95C", "3840-2160-260", "QA65S95C"));
        all.add(new Template("lg_4k_pool.OLED83C3PCA", "LG", "LGE", "OLED83C3PCA", "OLED83C3PCA", "alpha9gen6", "lg4k", "LGTV.2023.OLED83C3", "3840-2160-300", "OLED83C3PCA"));
        all.add(new Template("lg_4k_pool.OLED77C3PCA", "LG", "LGE", "OLED77C3PCA", "OLED77C3PCA", "alpha9gen6", "lg4k", "LGTV.2023.OLED77C3", "3840-2160-280", "OLED77C3PCA"));
        all.add(new Template("lg_4k_pool.OLED65C3PCA", "LG", "LGE", "OLED65C3PCA", "OLED65C3PCA", "alpha9gen6", "lg4k", "LGTV.2023.OLED65C3", "3840-2160-260", "OLED65C3PCA"));
        all.add(new Template("lg_4k_pool.OLED55C3PCA", "LG", "LGE", "OLED55C3PCA", "OLED55C3PCA", "alpha9gen6", "lg4k", "LGTV.2023.OLED55C3", "3840-2160-240", "OLED55C3PCA"));
        all.add(new Template("lg_4k_pool.86QNED90", "LG", "LGE", "86QNED90", "86QNED90", "alpha7gen5", "lg4k", "LGTV.2022.86QNED90", "3840-2160-300", "86QNED90"));
        all.add(new Template("tcl_8k_pool.85X925PRO", "TCL", "TCL", "85X925 PRO", "85X925PRO", "mt9615", "tcl8k", "TCLTV.2021.X925PRO", "7680-4320-280", "85X925 PRO"));
        all.add(new Template("tcl_8k_pool.75X925PRO", "TCL", "TCL", "75X925 PRO", "75X925PRO", "mt9615", "tcl8k", "TCLTV.2021.X925PRO", "7680-4320-260", "75X925 PRO"));
        all.add(new Template("tcl_4k_pool.85C845", "TCL", "TCL", "85C845", "85C845", "mt9615", "tcl4k", "TCLTV.2023.C845", "3840-2160-300", "85C845"));
        all.add(new Template("tcl_4k_pool.75C845", "TCL", "TCL", "75C845", "75C845", "mt9615", "tcl4k", "TCLTV.2023.C845", "3840-2160-280", "75C845"));
        all.add(new Template("tcl_4k_pool.65C845", "TCL", "TCL", "65C845", "65C845", "mt9615", "tcl4k", "TCLTV.2023.C845", "3840-2160-260", "65C845"));
        all.add(new Template("tcl_4k_pool.75C745", "TCL", "TCL", "75C745", "75C745", "mt9615", "tcl4k", "TCLTV.2023.C745", "3840-2160-280", "75C745"));
        all.add(new Template("tcl_4k_pool.65C745", "TCL", "TCL", "65C745", "65C745", "mt9615", "tcl4k", "TCLTV.2023.C745", "3840-2160-260", "65C745"));
        all.add(new Template("changhong_4k_pool.U65G7", "CHANGHONG", "CHANGHONG", "U65G7", "U65G7", "mt9632", "changhong4k", "CHANGHONGTV.2022.U65G7", "3840-2160-260", "U65G7"));
        all.add(new Template("changhong_4k_pool.U55G7", "CHANGHONG", "CHANGHONG", "U55G7", "U55G7", "mt9632", "changhong4k", "CHANGHONGTV.2022.U55G7", "3840-2160-240", "U55G7"));
        all.add(new Template("changhong_4k_pool.L55QCN1", "CHANGHONG", "CHANGHONG", "L55QCN1", "L55QCN1", "mt9632", "changhong4k", "CHANGHONGTV.2021.L55QCN1", "3840-2160-240", "L55QCN1"));
        all.add(new Template("changhong_4k_pool.U43QCN1", "CHANGHONG", "CHANGHONG", "U43QCN1", "U43QCN1", "mt9632", "changhong4k", "CHANGHONGTV.2021.U43QCN1", "3840-2160-220", "U43QCN1"));
        all.add(new Template("changhong_4k_pool.UD65YC5500UA", "CHANGHONG", "CHANGHONG", "UD65YC5500UA", "UD65YC5500UA", "mt9632", "changhong4k", "CHANGHONGTV.2020.UD65YC5500UA", "3840-2160-260", "UD65YC5500UA"));
        POOL = Collections.unmodifiableList(all);

        List<Template> eightK = new ArrayList<Template>();
        for (Template t : all) {
            if (t.is8k()) {
                eightK.add(t);
            }
        }
        POOL_8K = Collections.unmodifiableList(eightK);
    }

    /**
     * 随机取一条模板；优先 8K 池（与 Python {@code random_device_template} 一致）。
     */
    public static Template randomDeviceTemplate() {
        List<Template> src = POOL_8K.isEmpty() ? POOL : POOL_8K;
        return src.get(ThreadLocalRandom.current().nextInt(src.size()));
    }

    /**
     * 按 {@code source} 精确取模板；找不到返回 {@code null}。
     * <p>用于从磁盘恢复设备身份时保持机型不变。
     */
    public static Template bySource(String source) {
        if (source == null) {
            return null;
        }
        for (Template t : POOL) {
            if (t.source.equals(source)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 把档案 id 归一：只保留 ASCII 字母数字并小写，其余连续段折叠成单个下划线，首尾下划线去掉。
     * <p>对应 Python {@code sanitize_profile_id}。
     */
    public static String sanitizeProfileId(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean asciiAlnum = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
            if (asciiAlnum) {
                out.append(Character.toLowerCase(ch));
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                out.append('_');
                lastUnderscore = true;
            }
        }
        String s = out.toString();
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == '_') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == '_') {
            e--;
        }
        return s.substring(b, e);
    }

    /**
     * 把 {@code screen_param}（如 {@code 7680-4320-280}）转成 {@code 7680*2160} 形式的宽高。
     * <p>对应 Python {@code resolution_from_screen_param}：取前两段，缺省 {@code 3840*2160}。
     */
    public static String resolutionFromScreenParam(String screenParam) {
        if (screenParam != null) {
            String[] parts = screenParam.split("-", -1);
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return parts[0] + "*" + parts[1];
            }
        }
        return "3840*2160";
    }
}
