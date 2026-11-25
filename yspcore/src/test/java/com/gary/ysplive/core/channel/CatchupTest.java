package com.gary.ysplive.core.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link Catchup} 的表驱动测试。
 * <p>所有期望的 epoch 秒都按东八区（+08:00）手算，覆盖
 * {@code playseek} / {@code start,end} / {@code utc,lutc} / {@code starttime,endtime} /
 * 10 位秒 / 13 位毫秒 / 12 位分钟 / 14 位秒 / ISO-8601 / {@code Z} 结尾。
 */
public class CatchupTest {

    /** 2024-01-01 12:00:00 +08:00。 */
    private static final long T_2024_01_01_1200 = 1704081600L;
    /** 2024-01-01 13:00:00 +08:00。 */
    private static final long T_2024_01_01_1300 = 1704085200L;

    // ------------------------------------------------------------------ 单值解析

    @Test
    public void fourteenDigitCompactIsCst() {
        assertEquals(Long.valueOf(T_2024_01_01_1200), Catchup.parseTimeVal("20240101120000"));
    }

    @Test
    public void twelveDigitCompactIsMinutePrecision() {
        assertEquals(Long.valueOf(T_2024_01_01_1200), Catchup.parseTimeVal("202401011200"));
    }

    @Test
    public void epochSecondsTenDigits() {
        assertEquals(Long.valueOf(T_2024_01_01_1200), Catchup.parseTimeVal("1704081600"));
    }

    @Test
    public void epochMillisThirteenDigitsTruncates() {
        assertEquals(Long.valueOf(T_2024_01_01_1200), Catchup.parseTimeVal("1704081600000"));
        // 带毫秒余数也应向零截断
        assertEquals(Long.valueOf(T_2024_01_01_1200), Catchup.parseTimeVal("1704081600999"));
    }

    @Test
    public void iso8601WithOffset() {
        assertEquals(Long.valueOf(T_2024_01_01_1200),
                Catchup.parseTimeVal("2024-01-01T12:00:00+08:00"));
    }

    @Test
    public void iso8601WithSpaceSeparator() {
        assertEquals(Long.valueOf(T_2024_01_01_1200),
                Catchup.parseTimeVal("2024-01-01 12:00:00"));
    }

    @Test
    public void iso8601WithZIsUtc() {
        // 2024-01-01T04:00:00Z == 2024-01-01 12:00:00 +08:00
        assertEquals(Long.valueOf(T_2024_01_01_1200),
                Catchup.parseTimeVal("2024-01-01T04:00:00Z"));
    }

    @Test
    public void iso8601WithoutOffsetAssumesCst() {
        assertEquals(Long.valueOf(T_2024_01_01_1200),
                Catchup.parseTimeVal("2024-01-01T12:00:00"));
    }

    @Test
    public void iso8601WithMillis() {
        assertEquals(Long.valueOf(T_2024_01_01_1200),
                Catchup.parseTimeVal("2024-01-01T12:00:00.123+08:00"));
    }

    @Test
    public void trailingZOnCompactMeansUtc() {
        // 20240101120000Z 按 UTC 解释 → 东八区 20:00
        long expect = T_2024_01_01_1200 + 8 * 3600L;
        assertEquals(Long.valueOf(expect), Catchup.parseTimeVal("20240101120000Z"));
    }

    @Test
    public void nullAndBlankAndGarbage() {
        assertNull(Catchup.parseTimeVal(null));
        assertNull(Catchup.parseTimeVal(""));
        assertNull(Catchup.parseTimeVal("   "));
        assertNull(Catchup.parseTimeVal("abc"));
        assertNull(Catchup.parseTimeVal("2024-01-01T99:99:99+08:00"));
        // 与 Python 一致：纯日期（无 'T'、无 ':'）走数字分支，digits=20240101 → 8 位 → None
        assertNull(Catchup.parseTimeVal("2024-01-01"));
    }

    // ------------------------------------------------------------------ 区间解析

    @Test
    public void playseekFullRange() {
        Catchup.Timespan ts = Catchup.parseQuery("playseek=20240101120000-20240101130000");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void playseekSingleValueFillsEnd() {
        long now = T_2024_01_01_1200 + 600;   // 起点后 10 分钟
        Catchup.Timespan ts = Catchup.parseQuery("playseek=20240101120000", now);
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        // end = max(start+60, min(start+7200, now)) = now（因为 now 在窗口内）
        assertEquals(now, ts.endEpochSec);
    }

    @Test
    public void startEndPair() {
        Catchup.Timespan ts = Catchup.parseQuery("start=20240101120000&end=20240101130000");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void utcLutcPair() {
        Catchup.Timespan ts = Catchup.parseQuery("utc=1704081600&lutc=1704085200");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void starttimeEndtimePair() {
        Catchup.Timespan ts = Catchup.parseQuery("starttime=1704081600&endtime=1704085200");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void playseekWinsOverStart() {
        Catchup.Timespan ts = Catchup.parseQuery(
                "playseek=20240101120000-20240101130000&start=20240101000000&end=20240101010000");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void playseekStartOnlyFallsBackToEndParam() {
        Catchup.Timespan ts = Catchup.parseQuery(
                "playseek=20240101120000&end=20240101130000", T_2024_01_01_1200);
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        assertEquals(T_2024_01_01_1300, ts.endEpochSec);
    }

    @Test
    public void endBeforeStartIsRecomputed() {
        long now = T_2024_01_01_1200 + 30;
        Catchup.Timespan ts = Catchup.parseQuery(
                "start=20240101120000&end=20240101100000", now);
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
        // end <= start → max(start+60, min(start+7200, now)) = start+60
        assertEquals(T_2024_01_01_1200 + 60, ts.endEpochSec);
    }

    @Test
    public void endClampedToNowWhenNowEarlier() {
        long now = T_2024_01_01_1200 + 30;
        Catchup.Timespan ts = Catchup.parseQuery("start=20240101120000", now);
        assertEquals(T_2024_01_01_1200 + 60, ts.endEpochSec);
    }

    @Test
    public void noStartReturnsNull() {
        assertNull(Catchup.parseQuery(""));
        assertNull(Catchup.parseQuery(null));
        assertNull(Catchup.parseQuery("end=20240101130000"));
        assertNull(Catchup.parseQuery("foo=bar"));
    }

    @Test
    public void urlEncodingIsDecoded() {
        // Python 侧实测：'playseek=2024-01-01T12%3A00%3A00%2B08%3A00' -> None
        // 原因：parse_qs 解码出 "2024-01-01T12:00:00+08:00"，里面含 '-'，
        // 被当成区间分隔符切成两半，各自解析失败 → 整个 playseek 无效。
        // 这里断言与 Python 一致（不返回区间），而不是"解码成功"。
        assertNull(Catchup.parseQuery("playseek=2024-01-01T12%3A00%3A00%2B08%3A00"));
        // 不含 '-' 的编码值仍然可解
        Catchup.Timespan ts = Catchup.parseQuery("playseek=20240101120000");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
    }

    @Test
    public void leadingQuestionMarkIsTolerated() {
        Catchup.Timespan ts = Catchup.parseQuery("?playseek=20240101120000-20240101130000");
        assertNotNull(ts);
        assertEquals(T_2024_01_01_1200, ts.startEpochSec);
    }

    @Test
    public void durationHelper() {
        Catchup.Timespan ts = new Catchup.Timespan(T_2024_01_01_1200, T_2024_01_01_1300);
        assertEquals(3600L, ts.durationSec());
    }

    @Test
    public void hasCatchupParams() {
        assertTrue(Catchup.hasCatchupParams("playseek=20240101120000-20240101130000"));
        assertTrue(!Catchup.hasCatchupParams("foo=1"));
    }

    @Test
    public void formatCompactIsCst() {
        assertEquals("20240101120000", Catchup.formatCompact(T_2024_01_01_1200));
        assertEquals("20240101130000", Catchup.formatCompact(T_2024_01_01_1300));
    }

    @Test
    public void allSupportedQueryFormatsConsistent() {
        // 同一个时刻的多种写法必须解析成同一秒
        String[] forms = {
            "playseek=20240101120000-20240101130000",
            "start=1704081600&end=1704085200",
            "start=1704081600000&end=1704085200000",
            "starttime=202401011200&endtime=202401011300",
            "start=2024-01-01T12:00:00%2B08:00&end=2024-01-01T13:00:00%2B08:00",
        };
        for (String form : forms) {
            Catchup.Timespan ts = Catchup.parseQuery(form);
            assertNotNull("解析失败: " + form, ts);
            assertEquals("起点不一致: " + form, T_2024_01_01_1200, ts.startEpochSec);
            assertEquals("终点不一致: " + form, T_2024_01_01_1300, ts.endEpochSec);
        }
    }

    /**
     * 与 Python 一致：playseek 里放 ISO 区间会被第一个 '-' 切坏，整段失效。
     * <p>实测 Python {@code parse_catchup_query("playseek=2024-01-01T12:00:00+08:00-...")} → None。
     * 这是参考实现的既有行为，移植时不"修好"它，避免行为漂移。
     */
    @Test
    public void isoRangeInsidePlayseekIsRejectedLikePython() {
        assertNull(Catchup.parseQuery(
                "playseek=2024-01-01T12:00:00+08:00-2024-01-01T13:00:00+08:00"));
    }
}
