package com.gary.ysplive.core.spi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局日志总线。
 * <p>core 里的所有组件都往这里写；UI 与诊断页从这里读。内存环形缓冲，不落盘（落盘由 app 层决定）。
 * 线程安全，任意线程可调用。
 */
public final class Log {

    /** 一条日志。 */
    public static final class Entry {
        public final long timestampMs;
        public final String tag;
        public final String message;
        /** 0=debug 1=info 2=warn 3=error */
        public final int level;

        public Entry(long timestampMs, String tag, String message, int level) {
            this.timestampMs = timestampMs;
            this.tag = tag == null ? "" : tag;
            this.message = message == null ? "" : message;
            this.level = level;
        }

        /** 形如 {@code [04:12:53] [cctv1] 高清缓冲就绪}。 */
        public String format() {
            return "[" + timeString(timestampMs) + "] "
                    + (tag.isEmpty() ? "" : "[" + tag + "] ")
                    + message;
        }

        @Override
        public String toString() {
            return format();
        }
    }

    /** 日志监听器，会在任意线程被回调；实现方自行切主线程。 */
    public interface Listener {
        void onLog(Entry entry);
    }

    public static final int DEBUG = 0;
    public static final int INFO = 1;
    public static final int WARN = 2;
    public static final int ERROR = 3;

    private static final int CAPACITY = 400;
    private static final Entry[] RING = new Entry[CAPACITY];
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static int writeIndex;
    private static int count;
    private static int minLevel = INFO;
    private static boolean console = true;

    private Log() { }

    /** 最低记录等级；低于此等级的日志直接丢弃。 */
    public static synchronized void setMinLevel(int level) {
        minLevel = level;
    }

    public static synchronized int getMinLevel() {
        return minLevel;
    }

    /** 是否同时打印到 stdout（桌面联调时有用）。 */
    public static void setConsoleEnabled(boolean enabled) {
        console = enabled;
    }

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void d(String tag, String message) {
        append(tag, message, DEBUG);
    }

    public static void i(String tag, String message) {
        append(tag, message, INFO);
    }

    public static void w(String tag, String message) {
        append(tag, message, WARN);
    }

    public static void e(String tag, String message) {
        append(tag, message, ERROR);
    }

    /**
     * 带最小间隔的日志，避免滚动刷新日志刷屏。
     * <p>与 Python 参考实现的 {@code log(msg, min_interval=...)} 语义一致：同一 key 在间隔内只放行一条。
     * 放行后被丢弃的条数会在下一条里以 {@code (省略 N 条)} 形式提示。
     */
    public static void i(String tag, String message, double minIntervalSec) {
        if (minIntervalSec <= 0 || allow(tag + '\u0000' + message, minIntervalSec)) {
            append(tag, message, INFO);
        }
    }

    public static void w(String tag, String message, double minIntervalSec) {
        if (minIntervalSec <= 0 || allow(tag + '\u0000' + message, minIntervalSec)) {
            append(tag, message, WARN);
        }
    }

    private static final class RateHolder {
        long lastAtMs;
        int suppressed;
    }

    private static final java.util.HashMap<String, RateHolder> RATE = new java.util.HashMap<>();

    private static synchronized boolean allow(String key, double intervalSec) {
        long now = System.currentTimeMillis();
        RateHolder holder = RATE.get(key);
        if (holder == null) {
            holder = new RateHolder();
            holder.lastAtMs = now;
            RATE.put(key, holder);
            return true;
        }
        if (now - holder.lastAtMs >= (long) (intervalSec * 1000L)) {
            holder.lastAtMs = now;
            holder.suppressed = 0;
            return true;
        }
        holder.suppressed++;
        return false;
    }

    private static void append(String tag, String message, int level) {
        Entry entry;
        synchronized (Log.class) {
            if (level < minLevel) {
                return;
            }
            entry = new Entry(System.currentTimeMillis(), tag, message, level);
            RING[writeIndex] = entry;
            writeIndex = (writeIndex + 1) % CAPACITY;
            if (count < CAPACITY) {
                count++;
            }
        }
        if (console) {
            System.out.println(entry.format());
        }
        for (Listener l : LISTENERS) {
            try {
                l.onLog(entry);
            } catch (RuntimeException ignored) {
                // 监听器异常不能影响业务
            }
        }
    }

    /** 按时间顺序返回最近的日志（最多 {@code limit} 条，{@code limit<=0} 返回全部缓冲）。 */
    public static synchronized List<Entry> recent(int limit) {
        int total = count;
        int take = (limit <= 0 || limit > total) ? total : limit;
        java.util.ArrayList<Entry> out = new java.util.ArrayList<>(take);
        int start = (writeIndex - take + CAPACITY * 2) % CAPACITY;
        for (int n = 0; n < take; n++) {
            Entry e = RING[(start + n) % CAPACITY];
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    public static void clear() {
        List<Entry> none = java.util.Collections.emptyList();
        synchronized (Log.class) {
            java.util.Arrays.fill(RING, null);
            writeIndex = 0;
            count = 0;
        }
        if (none.isEmpty()) {
            // 保持方法体非空，方便打断点
            return;
        }
    }

    /** 把毫秒时间戳格式化成 {@code HH:mm:ss}（本地时区）。 */
    public static String timeString(long timestampMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(timestampMs);
        return pad2(c.get(java.util.Calendar.HOUR_OF_DAY)) + ":"
                + pad2(c.get(java.util.Calendar.MINUTE)) + ":"
                + pad2(c.get(java.util.Calendar.SECOND));
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
