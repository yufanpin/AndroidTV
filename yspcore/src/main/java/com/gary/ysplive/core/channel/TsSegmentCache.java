package com.gary.ysplive.core.channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片（TS）内存缓存，按总字节数做 LRU 淘汰，并带最大存活时间。
 * <p>对应 Python 参考实现的 {@code TsSegmentCache}：
 * <ul>
 *   <li>超过 {@code maxBytes} 时从队首（最久未写入）开始淘汰</li>
 *   <li>条目超过 {@code maxAgeMs} 视为过期，读取时淘汰并按 miss 计</li>
 *   <li>命中/未命中计数器，命中率按 {@code hits/(hits+misses)} 计算</li>
 * </ul>
 * 线程安全（单锁）。
 */
public final class TsSegmentCache {

    /** 一次命中的返回值：字节 + 需要透传给播放器的响应头（键保持原样）。 */
    public static final class Entry {
        public final byte[] data;
        public final Map<String, String> headers;
        public final long storedAtMs;
        public final long expireAtMs;

        Entry(byte[] data, Map<String, String> headers, long storedAtMs, long expireAtMs) {
            this.data = data;
            this.headers = headers;
            this.storedAtMs = storedAtMs;
            this.expireAtMs = expireAtMs;
        }

        public long size() {
            return data == null ? 0L : data.length;
        }
    }

    /** 统计快照，字段与 Python {@code stats()} 一一对应。 */
    public static final class Stats {
        public final int count;
        public final double sizeMb;
        public final long hits;
        public final long misses;
        public final double hitRatePercent;

        Stats(int count, double sizeMb, long hits, long misses) {
            this.count = count;
            this.sizeMb = sizeMb;
            this.hits = hits;
            this.misses = misses;
            long total = Math.max(1L, hits + misses);
            this.hitRatePercent = round1(hits * 100.0 / total);
        }

        /** 形如 {@code 87.5%}，与 Python 输出一致（保留一位小数）。 */
        public String hitRateText() {
            return hitRatePercent + "%";
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count);
            m.put("size_mb", sizeMb);
            m.put("hits", hits);
            m.put("misses", misses);
            m.put("hit_rate", hitRateText());
            return m;
        }

        private static double round1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }
    }

    private final long maxBytes;
    private final long maxAgeMs;
    private final Object lock = new Object();
    private final HashMap<String, Entry> cache = new HashMap<>();
    /** 写入顺序（LRU 依据）：队首最旧。 */
    private final Deque<String> order = new ArrayDeque<>();
    private long currBytes;
    private long hits;
    private long misses;

    public TsSegmentCache() {
        this(200, 180);
    }

    public TsSegmentCache(int maxMb, int maxAgeSec) {
        this.maxBytes = Math.max(1L, (long) maxMb) * 1024L * 1024L;
        this.maxAgeMs = Math.max(1L, (long) maxAgeSec) * 1000L;
    }

    public TsSegmentCache(long maxBytes, long maxAgeMs, boolean unusedMarker) {
        this.maxBytes = Math.max(1L, maxBytes);
        this.maxAgeMs = Math.max(1L, maxAgeMs);
    }

    /** 读缓存；未命中/已过期返回 {@code null}（并计入 misses）。 */
    public Entry get(String key) {
        if (key == null) {
            synchronized (lock) {
                misses++;
            }
            return null;
        }
        synchronized (lock) {
            Entry e = cache.get(key);
            if (e == null) {
                misses++;
                return null;
            }
            if (System.currentTimeMillis() > e.expireAtMs) {
                cache.remove(key);
                order.remove(key);
                currBytes -= e.size();
                misses++;
                return null;
            }
            hits++;
            return e;
        }
    }

    /**
     * 写缓存。超过单条上限直接丢弃（与 Python 一致的"大分片不缓存"策略）；
     * 写入前按 LRU 淘汰到能放下为止。
     */
    public void set(String key, byte[] data, Map<String, String> headers) {
        if (key == null || data == null) {
            return;
        }
        synchronized (lock) {
            if (data.length > maxBytes) {
                return;
            }
            Entry old = cache.remove(key);
            if (old != null) {
                order.remove(key);
                currBytes -= old.size();
            }
            while (currBytes + data.length > maxBytes && !order.isEmpty()) {
                String oldKey = order.pollFirst();
                Entry oldVal = cache.remove(oldKey);
                if (oldVal != null) {
                    currBytes -= oldVal.size();
                }
            }
            long now = System.currentTimeMillis();
            Map<String, String> copy = headers == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<>(headers);
            cache.put(key, new Entry(data, copy, now, now + maxAgeMs));
            order.addLast(key);
            currBytes += data.length;
        }
    }

    /** 主动移除一条（例如上游返回 404 以后）。 */
    public boolean remove(String key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            Entry old = cache.remove(key);
            if (old == null) {
                return false;
            }
            order.remove(key);
            currBytes -= old.size();
            return true;
        }
    }

    public void clear() {
        synchronized (lock) {
            cache.clear();
            order.clear();
            currBytes = 0L;
        }
    }

    /** 统计快照。 */
    public Stats stats() {
        synchronized (lock) {
            return new Stats(cache.size(), round2(currBytes / (1024.0 * 1024.0)), hits, misses);
        }
    }

    public long currentBytes() {
        synchronized (lock) {
            return currBytes;
        }
    }

    public int count() {
        synchronized (lock) {
            return cache.size();
        }
    }

    public long hits() {
        synchronized (lock) {
            return hits;
        }
    }

    public long misses() {
        synchronized (lock) {
            return misses;
        }
    }

    public long maxBytes() {
        return maxBytes;
    }

    public long maxAgeMs() {
        return maxAgeMs;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
