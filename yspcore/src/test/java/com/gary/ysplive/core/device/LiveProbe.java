package com.gary.ysplive.core.device;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.ResolutionException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实联网探针。
 *
 * <p><b>默认不跑</b>：只有设置了环境变量 {@code YSP_LIVE_PROBE} 才会执行，
 * 其余情况整个类 skip（{@link assumeTrue}），所以普通 CI 不会因为它变红。
 *
 * <pre>
 *   $env:YSP_LIVE_PROBE='1'
 *   gradle --project-dir C:\1\ysp-android :core:test --tests '*LiveProbe*' --no-daemon
 * </pre>
 *
 * <p>判定标准（任务里的 definitive test）：
 * {@code resolve()} 拿到的 {@code finalUrl}，用它自带的 {@code playbackHeaders} 去 GET，
 * 必须 HTTP 2xx 且 body 以 {@code #EXTM3U} 开头。
 */
public class LiveProbe {

    private static DeviceResolver resolver;

    private static boolean enabled() {
        String v = System.getenv("YSP_LIVE_PROBE");
        return v != null && !v.trim().isEmpty() && !"0".equals(v.trim());
    }

    @BeforeClass
    public static void setUp() {
        assumeTrue("未设置 YSP_LIVE_PROBE，跳过联网探针", enabled());
        Log.setMinLevel(Log.DEBUG);
        Gateway.Config cfg = new Gateway.Config();
        cfg.linksPerDevice = 0;         // 探针不限制配额
        cfg.zapDebounceSec = 0.0;       // 立即拿高码率，不防抖
        cfg.timeoutSec = 15.0;
        resolver = new DeviceResolver(cfg);
        resolver.start();
    }

    @AfterClass
    public static void tearDown() {
        if (resolver != null) {
            resolver.stop();
            resolver = null;
        }
    }

    /** 探一个频道并断言整条链路可用。 */
    private static void assertChannelPlayable(String slug) throws Exception {
        long t0 = System.currentTimeMillis();
        ChannelEntry entry = resolver.resolve(slug);
        long resolveMs = System.currentTimeMillis() - t0;

        assertNotNull("resolve 返回 null: " + slug, entry);
        assertTrue("finalUrl 不能为空: " + slug, !entry.finalUrl.isEmpty());
        assertTrue("finalUrl 应当是 http(s): " + slug + " -> " + entry.finalUrl,
                entry.finalUrl.startsWith("http://") || entry.finalUrl.startsWith("https://"));
        assertTrue("playbackHeaders 不能为空: " + slug, !entry.playbackHeaders.isEmpty());
        assertTrue("entry 应当 fresh: " + slug, entry.fresh(System.currentTimeMillis()));
        assertNotNull("finalHost 不能为空: " + slug + " -> " + entry.finalUrl,
                entry.finalHost);
        assertTrue("finalHost 不能为空串: " + slug, !entry.finalHost.isEmpty());

        // liveali/liveten 必须带齐签名头
        if (Protocol.livePlaybackHostNeedsSignedHeaders(entry.finalHost)) {
            assertTrue("liveali/liveten 频道必须带签名头: " + slug,
                    !Protocol.entryMissingSignedPlaybackHeaders(entry));
            assertNotNull(entry.playbackHeaders.get("APPSIGN"));
            assertNotNull(entry.playbackHeaders.get("APPRANDOMSTR"));
        }

        // 真取 m3u8
        Map<String, String> headers = new LinkedHashMap<>(entry.playbackHeaders);
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "identity");
        com.gary.ysplive.core.net.Http http = new com.gary.ysplive.core.net.Http(20000);
        try {
            com.gary.ysplive.core.net.HttpResponse resp = http.get(entry.finalUrl, headers);
            assertTrue(slug + " 的 m3u8 应当 HTTP 2xx, 实际 " + resp.status, 
                    resp.status >= 200 && resp.status < 300);
            String text = resp.text();
            assertTrue(slug + " 的 m3u8 应当以 #EXTM3U 开头, 实际: "
                    + abbrev(text, 120), text.startsWith("#EXTM3U"));
            assertTrue(slug + " 的 m3u8 应当有分片", text.contains(".ts") || text.contains("#EXTINF"));
            System.out.println("[LiveProbe] " + slug + " OK  rate=" + entry.rate
                    + " name=" + entry.rateName + " host=" + entry.finalHost
                    + " resolveMs=" + resolveMs + " url=" + abbrev(entry.finalUrl, 140));
        } finally {
            http.close();
        }

        // 代理改写必须可用，且签名头能查回来
        String proxied = resolver.proxyPlaylist("#EXTM3U\nseg1.ts\n", entry.finalUrl,
                "http://127.0.0.1:8767", slug);
        assertTrue("proxyPlaylist 应当产出代理地址: " + proxied,
                proxied.contains("/proxy.ts?="));
        assertTrue("proxyPlaylist 应当保留 #EXTM3U", proxied.startsWith("#EXTM3U"));

        Map<String, String> tsHeaders = resolver.playbackHeadersForTsUrl(entry.finalUrl);
        assertNotNull("playbackHeadersForTsUrl 不能返回 null", tsHeaders);
    }

    // ==================================================================
    // 核心用例
    // ==================================================================

    @Test
    public void cctv1IsPlayable() throws Exception {
        assertChannelPlayable("cctv1");
    }

    @Test
    public void cctv4kIsPlayableWithSignedHeaders() throws Exception {
        assertChannelPlayable("cctv4k");
    }

    @Test
    public void cctv8kIsPlayableWithSignedHeaders() throws Exception {
        assertChannelPlayable("cctv8k");
    }

    @Test
    public void cctv164kIsPlayableWithSignedHeaders() throws Exception {
        assertChannelPlayable("cctv164k");
    }

    @Test
    public void cctv5IsPlayable() throws Exception {
        assertChannelPlayable("cctv5");
    }

    /**
     * 全量扫 26 个后端频道。
     * <p>容忍个别频道当时没有直播（央视频某些频道会下线），但整体必须过半，
     * 且 4K 三兄弟必须成功 —— 那是本项目的目标。
     */
    @Test
    public void allBackendChannelsMostlyWork() throws Exception {
        java.util.List<String> slugs = new java.util.ArrayList<>(LiveIds.all().keySet());
        int ok = 0;
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (String slug : slugs) {
            try {
                ChannelEntry e = resolver.resolve(slug);
                if (e != null && !e.finalUrl.isEmpty()) {
                    ok++;
                } else {
                    failed.add(slug + "(空 URL)");
                }
            } catch (ResolutionException e) {
                failed.add(slug + "(" + abbrev(e.getMessage(), 80) + ")");
            }
        }
        System.out.println("[LiveProbe] 全量 " + ok + "/" + slugs.size() + " 成功; 失败: " + failed);
        assertTrue("至少一半后端频道应当可用, 实际 " + ok + "/" + slugs.size(),
                ok * 2 >= slugs.size());
        for (String must : new String[] {"cctv4k", "cctv8k", "cctv164k"}) {
            try {
                assertChannelPlayable(must);
            } catch (AssertionError e) {
                throw new AssertionError("4K 频道必须可用: " + must + " -> " + e.getMessage(), e);
            }
        }
    }

    /** {@code status()} 在跑过之后应当有 guid 和心跳。 */
    @Test
    public void statusReportsLiveSession() throws Exception {
        assertChannelPlayable("cctv1");
        com.gary.ysplive.core.spi.Resolver.Status st = resolver.status();
        assertNotNull(st);
        assertTrue("sessionValid 应当为 true", st.sessionValid);
        assertTrue("cloudGuid 不能为空", !st.cloudGuid.isEmpty());
        assertTrue("heartbeatCount 应当 > 0", st.heartbeatCount > 0);
        assertTrue("activeDeviceSlots 应当为 1", st.activeDeviceSlots == 1);
        System.out.println("[LiveProbe] status: ready=" + st.ready + " guid=" + st.cloudGuid
                + " heartbeats=" + st.heartbeatCount + " lastError=" + st.lastError);
    }

    private static String abbrev(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
