package com.gary.ysplive.core.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.gary.ysplive.core.channel.ChannelEngine;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /all.m3u} 的**字节级**对齐测试：把 Python 参考实现（127.0.0.1:8767）
 * 实际产出的 {@code all.m3u} 存成测试资源 {@code reference-all.m3u}
 * （仅把端口 8767 换成 8768），断言我的输出与之逐字节相同。
 *
 * <p>这是本移植里"外观零回归"的硬证据：频道顺序、tvg-id/tvg-name/tvg-logo/group-title、
 * 时移属性、行尾换行任何一处偏差都会失败。
 */
public class AllM3uGoldenTest {

    private static final String GOLDEN_RESOURCE = "/reference-all.m3u";
    private static final int PORT = 8768;

    private static final class NotReadyResolver implements Resolver {
        @Override
        public void start() { }

        @Override
        public void stop() { }

        @Override
        public Status status() {
            return new Status(false, false, 0, 0, "", "");
        }

        @Override
        public ChannelEntry resolve(String slug) throws ResolutionException {
            throw new ResolutionException("not ready");
        }

        @Override
        public String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug) {
            return body;
        }

        @Override
        public Map<String, String> playbackHeadersForTsUrl(String realUrl) {
            return new LinkedHashMap<>();
        }

        @Override
        public boolean isChannelInCooldown(String slug) {
            return true;
        }

        @Override
        public void clearChannelCooldown(String slug) { }
    }

    private static String loadGolden() throws IOException {
        InputStream in = AllM3uGoldenTest.class.getResourceAsStream(GOLDEN_RESOURCE);
        assertNotNull("测试资源缺失: " + GOLDEN_RESOURCE, in);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(20000);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static LocalServer newServer() {
        Gateway.Config config = new Gateway.Config();
        config.port = PORT;
        ChannelEngine engine = new ChannelEngine(new NotReadyResolver(), config);
        ServerConfig scfg = new ServerConfig();
        scfg.port = PORT;
        return new LocalServer(engine, scfg);
    }

    @Test
    public void allM3uIsByteIdenticalToPythonReference() throws IOException {
        String golden = loadGolden();
        String mine = newServer().allM3u("127.0.0.1:" + PORT, "http", null, false);

        byte[] g = golden.getBytes(StandardCharsets.UTF_8);
        byte[] m = mine.getBytes(StandardCharsets.UTF_8);
        if (!golden.equals(mine)) {
            // 给出首个差异位置，便于定位
            int limit = Math.min(g.length, m.length);
            int diff = -1;
            for (int i = 0; i < limit; i++) {
                if (g[i] != m[i]) {
                    diff = i;
                    break;
                }
            }
            String ctxG = diff < 0 ? "(长度不同)"
                    : golden.substring(Math.max(0, diff - 60), Math.min(golden.length(), diff + 60));
            String ctxM = diff < 0 ? "(长度不同)"
                    : mine.substring(Math.max(0, diff - 60), Math.min(mine.length(), diff + 60));
            throw new AssertionError("all.m3u 与参考实现不一致"
                    + "\n黄金长度=" + g.length + " 我的长度=" + m.length
                    + "\n首个差异字节偏移=" + diff
                    + "\n参考: " + ctxG.replace("\n", "\\n")
                    + "\n我的: " + ctxM.replace("\n", "\\n"));
        }
        assertEquals(g.length, m.length);
        // 4 行文件头 + 64 组（EXTINF + URL）= 132 行，加结尾换行分割出的空串共 133 项
        assertEquals(4 + 64 * 2 + 1, mine.split("\n", -1).length);
    }

    @Test
    public void goldenResourceIsComplete() throws IOException {
        String golden = loadGolden();
        // 黄金文件是 Python 参考实现的原始 UTF-8 字节（8767 换成 8768 后长度不变）
        assertEquals(18983, golden.getBytes(StandardCharsets.UTF_8).length);
        int count = golden.split("#EXTINF:-1", -1).length - 1;
        assertEquals(64, count);
        assertTrue(golden.endsWith("\n"));
        assertTrue(golden.startsWith("#EXTM3U url-tvg="));
    }

    @Test
    public void timeshiftChannelsCarryCatchupAttrs() {
        String m3u = newServer().allM3u("127.0.0.1:" + PORT, "http", null, false);
        String[] lines = m3u.split("\n");
        int withCatchup = 0;
        int withoutCatchup = 0;
        int idx = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("#EXTINF:-1")) {
                continue;
            }
            idx++;
            // 紧跟的下一行是 URL：http://host/<slug>.m3u8
            String url = lines[i + 1];
            String slug = url.substring(url.lastIndexOf('/') + 1, url.length() - ".m3u8".length());
            boolean timeshift = ChannelTable.supportsTimeshift(slug);
            boolean hasAttr = line.contains("catchup=\"append\"");
            if (hasAttr) {
                withCatchup++;
            } else {
                withoutCatchup++;
            }
            assertEquals("频道 " + slug + " 的时移属性与 TIMESHIFT_SUPPORTED 不一致",
                    timeshift, hasAttr);
        }
        assertEquals(64, idx);
        assertEquals(ChannelTable.all().size(), withCatchup + withoutCatchup);
        assertTrue("应当有频道支持时移", withCatchup > 0);
        assertTrue("应当有频道不支持时移", withoutCatchup > 0);
    }

    @Test
    public void tvgIdsMatchTable() {
        String m3u = newServer().allM3u("127.0.0.1:" + PORT, "http", null, false);
        for (com.gary.ysplive.core.data.ChannelSpec spec : ChannelTable.all()) {
            String expect = "tvg-id=\"" + spec.tvgId + "\" tvg-name=\"" + spec.name
                    + "\" tvg-logo=\"" + spec.logo + "\" group-title=\"" + spec.group + "\"";
            assertTrue("频道 " + spec.slug + " 的属性行缺失或顺序不对: 期望包含 " + expect,
                    m3u.contains(expect));
        }
    }

    private static String extractSlug(String m3u, String extinfLine) {
        int idx = m3u.indexOf(extinfLine);
        int nextExtinf = m3u.indexOf("#EXTINF:-1", idx + 1);
        String block = nextExtinf < 0 ? m3u.substring(idx) : m3u.substring(idx, nextExtinf);
        int slash = block.indexOf("/", block.indexOf("\n"));
        int dot = block.indexOf(".m3u8");
        if (slash < 0 || dot < 0) {
            return "";
        }
        return block.substring(slash + 1, dot);
    }
}
