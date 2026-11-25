package com.gary.ysplive.core.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Channel} 状态机 + {@code build_playlist} 的字节级测试。
 * <p>基准串是按 Python 参考实现 {@code build_playlist}（2836-2863）逐行手写的，
 * 断言 {@code equals} 而不是"包含"，这样 holdback、MEDIA-SEQUENCE、TARGETDURATION、
 * PROGRAM-DATE-TIME、{@code %.3f} 格式任何一处偏差都会被抓到。
 */
public class PlaylistTest {

    private static Channel newChannel() {
        ChannelSpec spec = ChannelTable.lookup("cctv1");
        assertNotNull(spec);
        return new Channel(spec);
    }

    private static Channel.RawSeg seg(double dur, String pdt, String url) {
        return new Channel.RawSeg(dur, pdt, url);
    }

    // ------------------------------------------------------------------ 黄金串

    @Test
    public void goldenPlaylistWithHoldback() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        segs.add(seg(6.0, "2024-01-01T12:00:00+08:00", "http://example.test/a1.ts"));
        segs.add(seg(4.0, "2024-01-01T12:00:06+08:00", "http://example.test/a2.ts"));
        segs.add(seg(10.1, "2024-01-01T12:00:10+08:00", "http://example.test/a3.ts"));
        segs.add(seg(3.9, "2024-01-01T12:00:20+08:00", "http://example.test/a4.ts"));
        segs.add(seg(12.0, "2024-01-01T12:00:24+08:00", "http://example.test/a5.ts"));
        ch.applyJceSegments(segs);

        String golden =
                "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:1\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T12:00:00+08:00\n"
                + "#EXTINF:6.000,\n"
                + "http://example.test/a1.ts\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T12:00:06+08:00\n"
                + "#EXTINF:4.000,\n"
                + "http://example.test/a2.ts\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T12:00:10+08:00\n"
                + "#EXTINF:10.100,\n"
                + "http://example.test/a3.ts\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T12:00:20+08:00\n"
                + "#EXTINF:3.900,\n"
                + "http://example.test/a4.ts\n";

        assertEquals(golden, ch.buildPlaylist(true));
    }

    @Test
    public void goldenPlaylistWithoutHoldback() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        segs.add(seg(6.0, "2024-01-01T12:00:00+08:00", "http://example.test/a1.ts"));
        segs.add(seg(4.0, "2024-01-01T12:00:06+08:00", "http://example.test/a2.ts"));
        segs.add(seg(10.1, "2024-01-01T12:00:10+08:00", "http://example.test/a3.ts"));
        segs.add(seg(3.9, "2024-01-01T12:00:20+08:00", "http://example.test/a4.ts"));
        segs.add(seg(12.0, "2024-01-01T12:00:24+08:00", "http://example.test/a5.ts"));
        ch.applyJceSegments(segs);

        String pl = ch.buildPlaylist(false);
        assertNotNull(pl);
        // 不扣留时最后一片也在
        assertTrue(pl.contains("http://example.test/a5.ts"));
        // 5 片的 TARGETDURATION = round(12.0+0.5)=12
        assertTrue(pl.contains("#EXT-X-TARGETDURATION:12\n"));
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:12\n"
                + "#EXT-X-MEDIA-SEQUENCE:1\n", pl.substring(0, pl.indexOf(
                        "#EXT-X-PROGRAM-DATE-TIME:2024-01-01T12:00:00+08:00")));
    }

    @Test
    public void holdbackNeedsAtLeastFourSegments() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        segs.add(seg(6.0, "", "http://example.test/only1.ts"));
        segs.add(seg(6.0, "", "http://example.test/only2.ts"));
        segs.add(seg(6.0, "", "http://example.test/only3.ts"));
        ch.applyJceSegments(segs);

        String golden =
                "#EXTM3U\n"
                + "#EXT-X-VERSION:3\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:1\n"
                + "#EXTINF:6.000,\n"
                + "http://example.test/only1.ts\n"
                + "#EXTINF:6.000,\n"
                + "http://example.test/only2.ts\n"
                + "#EXTINF:6.000,\n"
                + "http://example.test/only3.ts\n";
        // 3 片时不扣留
        assertEquals(golden, ch.buildPlaylist(true));
    }

    @Test
    public void noPdtMeansNoProgramDateTimeLines() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            segs.add(seg(6.0, "", "http://example.test/s" + i + ".ts"));
        }
        ch.applyJceSegments(segs);
        String pl = ch.buildPlaylist(true);
        assertTrue(!pl.contains("#EXT-X-PROGRAM-DATE-TIME"));
        assertTrue(pl.contains("#EXTINF:6.000,\nhttp://example.test/s1.ts\n"));
    }

    @Test
    public void emptyChannelReturnsNull() {
        Channel ch = newChannel();
        assertNull(ch.buildPlaylist(true));
    }

    // ------------------------------------------------------------------ 队列行为

    @Test
    public void firstMergeUsesInitTailOf25() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            segs.add(seg(6.0, "", "http://example.test/i" + i + ".ts"));
        }
        Channel.MergeResult r = ch.applyJceSegments(segs);
        assertTrue(r.isInit);
        assertEquals(25, r.added);
        assertEquals(25, ch.segmentCount());
        // 保留的是最后 25 片：i16..i40
        List<Channel.Segment> tail = ch.allSegments();
        assertEquals("http://example.test/i16.ts", tail.get(0).url);
        assertEquals("http://example.test/i40.ts", tail.get(24).url);
    }

    @Test
    public void incrementalMergeDedupesAndAppends() {
        Channel ch = newChannel();
        List<Channel.RawSeg> init = new ArrayList<>();
        init.add(seg(6.0, "2024-01-01T12:00:00+08:00", "http://example.test/a.ts"));
        init.add(seg(6.0, "2024-01-01T12:00:06+08:00", "http://example.test/b.ts"));
        ch.applyJceSegments(init);

        List<Channel.RawSeg> more = new ArrayList<>();
        // 重复的一片 + 新的一片
        more.add(seg(6.0, "2024-01-01T12:00:00+08:00", "http://example.test/a.ts"));
        more.add(seg(6.0, "2024-01-01T12:00:12+08:00", "http://example.test/c.ts"));
        Channel.MergeResult r = ch.applyJceSegments(more);

        assertTrue(!r.isInit);
        assertEquals(1, r.added);
        assertEquals(3, ch.segmentCount());
    }

    @Test
    public void olderPdtIsSkippedOnIncremental() {
        Channel ch = newChannel();
        List<Channel.RawSeg> init = new ArrayList<>();
        init.add(seg(6.0, "2024-01-01T12:00:00+08:00", "http://example.test/a.ts"));
        ch.applyJceSegments(init);

        List<Channel.RawSeg> stale = new ArrayList<>();
        stale.add(seg(6.0, "2024-01-01T11:00:00+08:00", "http://example.test/z.ts"));
        Channel.MergeResult r = ch.applyJceSegments(stale);
        assertEquals(0, r.added);
        assertEquals(1, ch.segmentCount());
    }

    @Test
    public void queueIsCappedAtMaxSegs() {
        Channel ch = newChannel();
        List<Channel.RawSeg> init = new ArrayList<>();
        init.add(seg(6.0, "", "http://example.test/seed.ts"));
        ch.applyJceSegments(init);

        for (int i = 0; i < Channel.MAX_SEGS + 40; i++) {
            List<Channel.RawSeg> one = new ArrayList<>();
            one.add(seg(6.0, "", "http://example.test/n" + i + ".ts"));
            ch.applyJceSegments(one);
        }
        assertTrue("队列应被裁剪到 " + Channel.MAX_SEGS, ch.segmentCount() <= Channel.MAX_SEGS);
        assertEquals(Channel.MAX_SEGS, ch.segmentCount());
    }

    @Test
    public void playlistTakesOnlyLast15() {
        Channel ch = newChannel();
        List<Channel.RawSeg> init = new ArrayList<>();
        init.add(seg(6.0, "", "http://example.test/seed.ts"));
        ch.applyJceSegments(init);
        for (int i = 0; i < 30; i++) {
            List<Channel.RawSeg> one = new ArrayList<>();
            one.add(seg(6.0, "", "http://example.test/x" + i + ".ts"));
            ch.applyJceSegments(one);
        }
        String pl = ch.buildPlaylist(false);
        int extinf = pl.split("#EXTINF:", -1).length - 1;
        assertEquals(15, extinf);
        // MEDIA-SEQUENCE 是最后 15 片里最旧那片的 seq
        assertEquals(31 - 15 + 1, 17);
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:17\n"));
    }

    // ------------------------------------------------------------------ 模式 / 来源

    @Test
    public void bkModeReturnsBkPlaylist() {
        Channel ch = newChannel();
        ch.setMode(Channel.MODE_BK);
        assertNull(ch.buildPlaylist(true));
        String bk = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nhttp://bk.test/1.ts\n";
        ch.setBkPlaylist(bk);
        assertEquals(bk, ch.buildPlaylist(true));
    }

    @Test
    public void forceBkChannelsStartInBkMode() {
        // 裸 Channel 默认 jce；FORCE_BK 的切换由 ChannelEngine 建表时完成
        Channel bare = new Channel(ChannelTable.lookup("cctv11"));
        assertEquals(Channel.MODE_JCE, bare.mode());

        ChannelEngine engine = new ChannelEngine(null);
        assertTrue(ChannelTable.isForceBk("cctv11"));
        assertEquals(Channel.MODE_BK, engine.channelFor("cctv11").mode());
        assertEquals(Channel.MODE_JCE, engine.channelFor("cctv1").mode());
        // 别名也应落到同一个实例
        assertSame(engine.channelFor("cctv11"), engine.channelFor("cctv11"));
        engine.shutdown();
    }

    @Test
    public void lastBuiltIsCachedForSwr() {
        Channel ch = newChannel();
        List<Channel.RawSeg> segs = new ArrayList<>();
        segs.add(seg(6.0, "", "http://example.test/a.ts"));
        ch.applyJceSegments(segs);
        String built = ch.buildPlaylist(true);
        assertEquals(built, ch.lastBuiltPlaylist());
        assertTrue(ch.lastBuiltTime() > 0);
        ch.invalidateBuilt();
        assertEquals("", ch.lastBuiltPlaylist());
        assertEquals(0.0, ch.lastBuiltTime(), 0.0001);
    }

    @Test
    public void sourceSwitchIsTracked() {
        Channel ch = newChannel();
        assertEquals("", ch.lastM3u8Source());
        ch.setM3u8Source(Channel.SOURCE_DEVICE);
        assertEquals(Channel.SOURCE_DEVICE, ch.lastM3u8Source());
        ch.setM3u8Source(Channel.SOURCE_BK);
        assertEquals(Channel.SOURCE_BK, ch.lastM3u8Source());
    }

    // ------------------------------------------------------------------ segKey

    @Test
    public void segKeyPrefersPdt() {
        assertEquals("pdt:2024-01-01T12:00:00+08:00",
                Channel.segKey("http://x.test/a.ts", "2024-01-01T12:00:00+08:00"));
    }

    @Test
    public void segKeyStripsQueryWhenNoPdt() {
        assertEquals("http://x.test/path/a.ts",
                Channel.segKey("http://x.test/path/a.ts?token=abc&t=1", ""));
        assertEquals("https://x.test:8443/a.ts",
                Channel.segKey("https://x.test:8443/a.ts?x=1", null));
    }

    // ------------------------------------------------------------------ 访问时间

    @Test
    public void touchUpdatesLastAccess() {
        Channel ch = newChannel();
        assertEquals(0.0, ch.lastAccess(), 0.0001);
        ch.touch();
        assertTrue(ch.lastAccess() > 0);
    }
}
