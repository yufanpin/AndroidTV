package com.gary.ysplive.core.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@link TsSegmentCache} 的 LRU / 过期淘汰与命中率测试。 */
public class TsSegmentCacheTest {

    private static byte[] blob(int size, int fill) {
        byte[] b = new byte[size];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    @Test
    public void hitAndMissCounters() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("a", blob(100, 1), null);

        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("a"));
        assertNull(cache.get("missing"));

        assertEquals(2L, cache.hits());
        assertEquals(1L, cache.misses());
        TsSegmentCache.Stats st = cache.stats();
        // 2/3 = 66.666.. → 保留一位小数
        assertEquals(66.7, st.hitRatePercent, 0.001);
        assertEquals("66.7%", st.hitRateText());
        assertEquals(1, st.count);
    }

    @Test
    public void hitRateZeroWhenNoAccess() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        TsSegmentCache.Stats st = cache.stats();
        assertEquals(0.0, st.hitRatePercent, 0.0001);
        assertEquals("0.0%", st.hitRateText());
        assertEquals(0L, st.hits);
        assertEquals(0L, st.misses);
    }

    @Test
    public void lruEvictsOldestByBytes() {
        // 1MB 上限，塞 4 个 400KB → 只能留 2 个
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("a", blob(400 * 1024, 1), null);
        cache.set("b", blob(400 * 1024, 2), null);
        assertEquals(2, cache.count());

        cache.set("c", blob(400 * 1024, 3), null);
        // 800KB + 400KB > 1MB，只淘汰最旧的 a 就够放 c 了（b、c 留下）
        assertNull(cache.get("a"));
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("c"));
        assertEquals(2, cache.count());
        assertTrue(cache.currentBytes() <= 1024L * 1024L);
        // 再塞一个 d：这次淘汰 b
        cache.set("d", blob(400 * 1024, 4), null);
        assertNull(cache.get("b"));
        assertNotNull(cache.get("c"));
        assertNotNull(cache.get("d"));
    }

    @Test
    public void lruKeepsMostRecentWhenSpaceAvailable() {
        TsSegmentCache cache = new TsSegmentCache(2, 180);
        cache.set("a", blob(600 * 1024, 1), null);
        cache.set("b", blob(600 * 1024, 2), null);
        cache.set("c", blob(600 * 1024, 3), null);
        // 2MB 上限可容纳 3 * 600KB = 1800KB
        assertEquals(3, cache.count());
        assertNotNull(cache.get("a"));
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("c"));
    }

    @Test
    public void oversizedEntryIsRejected() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("huge", blob(2 * 1024 * 1024, 9), null);
        assertEquals(0, cache.count());
        assertEquals(0L, cache.currentBytes());
    }

    @Test
    public void expiredEntryIsMissedAndEvicted() {
        // 100ms 存活期
        TsSegmentCache cache = new TsSegmentCache(1024L * 1024L, 100L, true);
        cache.set("a", blob(10, 1), null);
        assertNotNull(cache.get("a"));
        sleep(180);
        assertNull(cache.get("a"));
        assertEquals(0, cache.count());
        assertEquals(0L, cache.currentBytes());
        // 命中 1 次，未中 1 次（过期按 miss 计）
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
    }

    @Test
    public void rewriteSameKeyReplacesBytes() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("a", blob(500 * 1024, 1), null);
        long after1 = cache.currentBytes();
        cache.set("a", blob(100 * 1024, 2), null);
        assertEquals(100 * 1024L, cache.currentBytes());
        assertTrue(cache.currentBytes() < after1);
        assertEquals(1, cache.count());
    }

    @Test
    public void headersAreCopiedNotReferenced() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "video/mp2t");
        cache.set("a", blob(10, 1), headers);
        headers.put("Content-Type", "tampered");

        TsSegmentCache.Entry e = cache.get("a");
        assertNotNull(e);
        assertEquals("video/mp2t", e.headers.get("Content-Type"));
    }

    @Test
    public void removeAndClear() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("a", blob(10, 1), null);
        cache.set("b", blob(10, 2), null);
        assertTrue(cache.remove("a"));
        assertTrue(!cache.remove("a"));
        assertEquals(1, cache.count());
        cache.clear();
        assertEquals(0, cache.count());
        assertEquals(0L, cache.currentBytes());
    }

    @Test
    public void nullKeyIsMiss() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        assertNull(cache.get(null));
        assertEquals(1L, cache.misses());
    }

    @Test
    public void statsMapHasPythonFieldNames() {
        TsSegmentCache cache = new TsSegmentCache(1, 180);
        cache.set("a", blob(1024 * 1024, 1), null);
        Map<String, Object> m = cache.stats().toMap();
        assertEquals(1, m.get("count"));
        assertEquals(1.0, (Double) m.get("size_mb"), 0.001);
        assertEquals(0L, m.get("hits"));
        assertEquals(0L, m.get("misses"));
        assertEquals("0.0%", m.get("hit_rate"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
