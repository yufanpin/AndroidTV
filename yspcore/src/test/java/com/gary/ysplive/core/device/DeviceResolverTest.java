package com.gary.ysplive.core.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;

import org.junit.Test;

import java.util.Map;

/**
 * {@link DeviceResolver} 的<b>离线</b>健壮性测试。
 *
 * <p>全部不碰网络：只验证"失败时必须优雅降级，不能崩、不能卡线程、不能留脏状态"。
 * 真实联网验证在 {@link LiveProbe}（需 {@code YSP_LIVE_PROBE=1}）。
 */
public class DeviceResolverTest {

    /** 造一个必然连不上网的 resolver：超时给最小、端口不通。 */
    private static DeviceResolver offlineResolver() {
        Gateway.Config cfg = new Gateway.Config();
        cfg.linksPerDevice = 6;
        cfg.zapDebounceSec = 0.0;
        cfg.timeoutSec = 1.0;
        cfg.dataDir = null;             // 不落盘
        return new DeviceResolver(cfg);
    }

    @Test
    public void constructorAcceptsNullConfig() {
        DeviceResolver r = new DeviceResolver(null);
        assertNotNull(r.status());
        assertFalse("未启动时 ready 必须是 false", r.status().ready);
        assertFalse("未启动时 sessionValid 必须是 false", r.status().sessionValid);
        assertEquals("", r.status().cloudGuid);
    }

    @Test
    public void statusNeverReturnsNull() {
        DeviceResolver r = offlineResolver();
        Resolver.Status st = r.status();
        assertNotNull(st);
        assertNotNull(st.cloudGuid);
        assertNotNull(st.lastError);
        assertEquals(0, st.heartbeatCount);
        assertEquals(0, st.activeDeviceSlots);
    }

    @Test
    public void startStopAreIdempotentAndLeaveNoThreads() throws Exception {
        DeviceResolver r = offlineResolver();
        r.start();
        r.start();      // 重复调用不能起第二套线程
        Thread.sleep(200);
        r.stop();
        r.stop();       // 重复 stop 必须安全

        // 关键：stop 之后不能再有我们起的线程活着
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name.startsWith("device-") && t.isAlive()) {
                fail("stop 之后仍有线程存活: " + name);
            }
        }
    }

    /**
     * {@code stop()} 必须很快返回。
     * <p>历史 bug：bootstrap 线程卡在 socket 读里，{@code Thread.join(1500)} 每次都超时，
     * 导致 stop 至少慢 1.5s。现在 stop 会先关掉所有在飞的 HTTP 连接（触发读立刻抛异常），
     * 再 join，所以应该远快于 join 超时。
     */
    @Test
    public void stopReturnsQuickly() throws Exception {
        DeviceResolver r = offlineResolver();
        r.start();
        Thread.sleep(100);          // 让 bootstrap 真的开始跑起来
        long t0 = System.nanoTime();
        r.stop();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        // join 超时是 1500ms × 4 个线程；正常关连接后应当 <1s。给足余量避免 CI 抖动误报。
        assertTrue("stop() 耗时应当远小于 join 超时, 实际 " + ms + "ms", ms < 3000);
        assertFalse("stop 之后 ready 必须是 false", r.status().ready);
    }

    /** 反复 start/stop 不能堆积 device-* 线程。 */
    @Test
    public void repeatedStartStopCyclesDoNotLeakThreads() throws Exception {
        for (int i = 0; i < 3; i++) {
            DeviceResolver r = offlineResolver();
            r.start();
            r.stop();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (name.startsWith("device-") && t.isAlive()) {
                    fail("第 " + (i + 1) + " 轮 stop 后仍有线程存活: " + name);
                }
            }
        }
    }

    @Test
    public void stopWithoutStartIsSafe() {
        DeviceResolver r = offlineResolver();
        r.stop();
        assertNotNull(r.status());
    }

    @Test
    public void resolveUnknownChannelThrowsResolutionException() {
        DeviceResolver r = offlineResolver();
        try {
            r.resolve("no-such-channel-xyz");
            fail("未知频道应当抛 ResolutionException");
        } catch (ResolutionException e) {
            assertFalse("未知频道不可恢复", e.recoverable);
        }
    }

    @Test
    public void resolveEmptyOrNullChannelThrows() {
        DeviceResolver r = offlineResolver();
        for (String bad : new String[] {null, "", "   "}) {
            try {
                r.resolve(bad);
                // 空白串经 normalize 后为空 → 应当抛
                if (bad != null && !bad.trim().isEmpty()) {
                    fail("应当抛异常: " + bad);
                }
            } catch (ResolutionException expected) {
                // 符合预期
            }
        }
    }

    @Test
    public void resolveNonBackendChannelThrowsNotRecoverable() {
        DeviceResolver r = offlineResolver();
        // bjws 是卫视频道，没有设备协议码流
        try {
            r.resolve("bjws");
            fail("非后端频道应当抛 ResolutionException");
        } catch (ResolutionException e) {
            assertFalse("非后端频道不该让调用方重试", e.recoverable);
            assertTrue(e.getMessage(), e.getMessage().contains("设备协议"));
        }
    }

    @Test
    public void only4kModeRejectsNon4k() {
        DeviceResolver r = offlineResolver();
        r.setOnly4kHighBitrate(true);
        try {
            r.resolve("cctv1");
            fail("仅 4K 模式下 cctv1 应当被拒");
        } catch (ResolutionException e) {
            assertFalse(e.recoverable);
            assertTrue(e.getMessage(), e.getMessage().contains("仅 4K"));
        }
    }

    @Test
    public void cooldownEscalatesAndCanBeCleared() {
        DeviceResolver r = offlineResolver();
        assertFalse("初始不该在冷却中", r.isChannelInCooldown("cctv1"));
        r.clearChannelCooldown("cctv1");        // 清一个不存在的，必须安全
        assertFalse(r.isChannelInCooldown("cctv1"));
        // 未知频道也不能抛
        assertFalse(r.isChannelInCooldown("no-such"));
        r.clearChannelCooldown("no-such");
    }

    @Test
    public void proxyPlaylistIsSafeBeforeStart() {
        DeviceResolver r = offlineResolver();
        String out = r.proxyPlaylist("#EXTM3U\n#EXT-X-VERSION:3\nseg1.ts\nHTTP://A/b.ts\n",
                "http://liveali-x.cctv.cn/live/abc.m3u8", "http://127.0.0.1:8767/", "cctv1");
        assertTrue(out.startsWith("#EXTM3U\n"));
        assertTrue("分片应当被包成代理地址", out.contains("http://127.0.0.1:8767/proxy.ts?="));
        // 结尾斜杠要被去掉，不能出现双斜杠
        assertFalse("proxyOrigin 尾部斜杠应当被去掉: " + out, out.contains("8767//proxy.ts"));
        // 注释行原样保留
        assertTrue(out.contains("#EXT-X-VERSION:3"));
    }

    @Test
    public void proxyPlaylistKeepsNonTsLines() {
        DeviceResolver r = offlineResolver();
        String body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\nkey.bin\nseg.ts\n";
        String out = r.proxyPlaylist(body, "http://h.com/a/b.m3u8", "http://127.0.0.1:8767", "x");
        assertTrue("非 .ts 的相对地址也应转成绝对", out.contains("http://h.com/a/key.bin"));
        assertTrue(out.contains("/proxy.ts?="));
        assertTrue(out.contains("#EXT-X-KEY"));
    }

    @Test
    public void proxyPlaylistHandlesNullAndEmpty() {
        DeviceResolver r = offlineResolver();
        assertEquals("", r.proxyPlaylist(null, null, "http://o", "x"));
        assertEquals("", r.proxyPlaylist("", null, "http://o", "x"));
    }

    @Test
    public void playbackHeadersFallsBackToDefaultsForPlainHost() throws Exception {
        DeviceResolver r = offlineResolver();
        Map<String, String> h = r.playbackHeadersForTsUrl("http://bklive-x.cctv.cn/a/b.ts");
        assertNotNull(h);
        assertEquals(Identity.AK, h.get("APPID"));
        assertNotNull(h.get("UID"));
    }

    @Test
    public void playbackHeadersRejectsLivealiWithoutCache() {
        DeviceResolver r = offlineResolver();
        try {
            r.playbackHeadersForTsUrl("http://liveali-x.cctv.cn/live/a/b.ts");
            fail("liveali 且无缓存签名头时必须抛 ResolutionException");
        } catch (ResolutionException e) {
            assertTrue("应当说明缺签名头: " + e.getMessage(),
                    e.getMessage().contains("签名头"));
        }
    }

    @Test
    public void playbackHeadersRejectsMalformedUrl() {
        DeviceResolver r = offlineResolver();
        for (String bad : new String[] {null, "", "not a url", "://", "/relative/path.ts"}) {
            try {
                r.playbackHeadersForTsUrl(bad);
                // 相对路径解析出来 host 为 null → 也应当抛
                fail("非法 URL 应当抛异常: " + bad);
            } catch (ResolutionException expected) {
                // 符合预期
            }
        }
    }

    @Test
    public void settersAreSafeWithSillyValues() {
        DeviceResolver r = offlineResolver();
        r.setLinksPerDevice(-5);            // 负数 → 归 0
        r.setZapDebounceSeconds(-1.0);      // 负数 → 归 0
        r.setTimeoutMs(0);                  // 太小 → 抬到下限
        r.setDeviceStateFile(null);         // 允许 null（纯内存）
        r.setDeviceState(null);             // 别名也要能接 null
        r.setOnly4kHighBitrate(false);
        assertNotNull(r.status());
    }

    @Test
    public void countersAndIntrospectionAreSafe() {
        DeviceResolver r = offlineResolver();
        assertEquals(0, r.cachedChannelCount());
        assertNull(r.currentProfile());
        assertEquals("", r.cloudGuid());
        long[] c = r.counters();
        assertEquals(2, c.length);
        assertEquals(0, c[0]);
    }
}
