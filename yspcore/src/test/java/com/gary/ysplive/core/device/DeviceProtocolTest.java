package com.gary.ysplive.core.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.ChannelEntry;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备层离线测试：身份派生、设备池、Live ID 表、上报体、playlist 改写、失败冷却。
 * <p>全部不碰网络，可在普通 CI 里跑。
 */
public class DeviceProtocolTest {

    // ==================================================================
    // DevicePool：53 条模板逐字复制
    // ==================================================================

    @Test
    public void devicePoolMatchesReferenceCount() {
        assertEquals("参考实现的 DEVICE_PROFILE_POOL 有 53 条", 53, DevicePool.POOL.size());
        assertEquals("其中 28 条是 8K 机型", 28, DevicePool.POOL_8K.size());
    }

    @Test
    public void devicePoolEntriesAreComplete() {
        Set<String> sources = new HashSet<>();
        for (DevicePool.Template t : DevicePool.POOL) {
            assertNotNull(t.source);
            assertFalse("source 不能为空", t.source.isEmpty());
            assertTrue("source 必须唯一: " + t.source, sources.add(t.source));
            for (String f : new String[] {t.brand, t.manufacturer, t.model, t.reportModel,
                    t.hardware, t.board, t.versionId, t.screenParam, t.castModel}) {
                assertNotNull(f);
                assertFalse("字段不能为空 (source=" + t.source + ")", f.isEmpty());
            }
            // screen_param 形如 7680-4320-280
            assertTrue("screen_param 格式: " + t.screenParam,
                    t.screenParam.matches("\\d+-\\d+-\\d+"));
        }
    }

    @Test
    public void devicePoolSpotChecksKnownEntries() {
        DevicePool.Template first = DevicePool.POOL.get(0);
        assertEquals("sony_8k_pool.XR-85Z9K", first.source);
        assertEquals("Sony", first.brand);
        assertEquals("XR-85Z9K", first.model);
        assertEquals("7680-4320-280", first.screenParam);
        assertEquals("XR85Z9K", first.reportModel);
        assertTrue(first.is8k());

        // 索引 20 是 LG OLED88Z2PCA（调试时用过，钉住防止漂移）
        DevicePool.Template lg = DevicePool.POOL.get(20);
        assertEquals("lg_8k_pool.OLED88Z2PCA", lg.source);
        assertEquals("LGE", lg.manufacturer);
        assertEquals("alpha9gen5", lg.hardware);
        assertEquals("lg8k", lg.board);
    }

    @Test
    public void randomDeviceTemplatePrefers8k() {
        for (int i = 0; i < 200; i++) {
            DevicePool.Template t = DevicePool.randomDeviceTemplate();
            assertTrue("随机模板应当优先取 8K 池，实际 " + t.source, t.is8k());
        }
    }

    @Test
    public void bySourceLookup() {
        assertNotNull(DevicePool.bySource("sony_8k_pool.XR-85Z9K"));
        assertNull(DevicePool.bySource("nope"));
        assertNull(DevicePool.bySource(null));
    }

    @Test
    public void sanitizeProfileId() {
        assertEquals("sony", DevicePool.sanitizeProfileId("Sony"));
        assertEquals("lg", DevicePool.sanitizeProfileId("LG"));
        assertEquals("85x925_pro", DevicePool.sanitizeProfileId("85X925 PRO"));
        assertEquals("95s4", DevicePool.sanitizeProfileId("95S4"));
        assertEquals("", DevicePool.sanitizeProfileId(""));
        assertEquals("", DevicePool.sanitizeProfileId(null));
        // 连续非字母数字折叠成一个下划线，首尾去掉
        assertEquals("a_b", DevicePool.sanitizeProfileId("__a---b__"));
        // 全非字母数字 → 空
        assertEquals("", DevicePool.sanitizeProfileId("!!!"));
    }

    @Test
    public void resolutionFromScreenParam() {
        assertEquals("7680*4320", DevicePool.resolutionFromScreenParam("7680-4320-280"));
        assertEquals("3840*2160", DevicePool.resolutionFromScreenParam("3840-2160-260"));
        assertEquals("3840*2160", DevicePool.resolutionFromScreenParam(""));
        assertEquals("3840*2160", DevicePool.resolutionFromScreenParam(null));
        assertEquals("3840*2160", DevicePool.resolutionFromScreenParam("garbage"));
    }

    // ==================================================================
    // DeviceProfile
    // ==================================================================

    private static DeviceProfile fixedProfile() {
        DevicePool.Template t = DevicePool.bySource("lg_8k_pool.OLED88Z2PCA");
        return DeviceProfile.fromTemplate(t, "0123456789abcdef", "aa:bb:cc:dd:ee:ff");
    }

    @Test
    public void profileFromTemplateMatchesReference() {
        DeviceProfile p = fixedProfile();
        assertEquals("lg_oled88z2pca", p.device);
        assertEquals("lg_oled88z2pca", p.product);
        assertEquals("7680*4320", p.resolution);
        assertEquals("lg-tv-build", p.host);
        assertEquals("release-keys", p.tags);
        assertEquals("user", p.buildType);
        assertEquals("build", p.user);
        assertEquals("alpha9gen5", p.hardware);
        assertEquals("lg8k", p.board);
        // 关键：manufacturer 后面只有一个斜杠
        assertEquals("LGE/lg_oled88z2pca/lg_oled88z2pca:13/LGTV.2022.OLED88Z2/2024:user/release-keys",
                p.fingerprint);
        assertEquals("OLED88Z2PCA-user 13 LGTV.2022.OLED88Z2 2024 release-keys", p.display);
    }

    @Test
    public void profileInferOsAndSdk() {
        DeviceProfile p = fixedProfile();
        assertEquals("13", p.inferOsVersion());
        assertEquals("33", p.inferSdkInt());
    }

    @Test
    public void profileReportModel() {
        assertEquals("OLED88Z2PCA", fixedProfile().reportModelOrDerived());
    }

    @Test
    public void profileMapRoundTrip() {
        DeviceProfile p = fixedProfile();
        DeviceProfile back = DeviceProfile.fromMap(p.toMap());
        assertEquals(p.androidId, back.androidId);
        assertEquals(p.mac, back.mac);
        assertEquals(p.fingerprint, back.fingerprint);
        assertEquals(p.reportModel, back.reportModel);
        assertEquals(p.resolution, back.resolution);
    }

    // ==================================================================
    // Identity：X-Uid / X-Fingerprint
    // ==================================================================

    @Test
    public void xUidMatchesReferenceVector() {
        // 参考实现算出来的值（tools/debug_xuid.py 输出的）
        DeviceProfile p = fixedProfile();
        String xuid = Identity.computeXUid(p);
        assertEquals("A3DF773CB7AC08702E2CFB7B9EC9B2A093C8979C", xuid);
    }

    @Test
    public void javaStringHashCodeMatchesJavaSemantics() {
        assertEquals(0, Identity.javaStringHashCode(""));
        assertEquals(97, Identity.javaStringHashCode("a"));
        assertEquals(99162322, Identity.javaStringHashCode("hello"));
        // Java 的 "hello".hashCode() 就是 99162322，直接对照 JDK 行为
        assertEquals("hello".hashCode(), Identity.javaStringHashCode("hello"));
        assertEquals("中文".hashCode(), Identity.javaStringHashCode("中文"));
        assertEquals("lg_oled88z2pca".hashCode(), Identity.javaStringHashCode("lg_oled88z2pca"));
    }

    @Test
    public void javaUuidSignExtendsNegativeHashes() {
        // Python: f'{h & 0xFFFFFFFFFFFFFFFF:016x}' —— 负数会被符号扩展成 ffffffff...
        // 每个 hash 都占满 16 个 hex 位，合计 32 位
        assertEquals("000000005684a2ca0000000000000000", Identity.javaUuidFromHashes(0x5684A2CA, 0));
        assertEquals("000000005684a2cafffffffffd9b20da", Identity.javaUuidFromHashes(1451533002, -40165158));
        assertEquals(32, Identity.javaUuidFromHashes(1, 1).length());
        // 两个都是负数时高 32 位也要是 ffffffff
        assertTrue(Identity.javaUuidFromHashes(-1, -1).startsWith("ffffffff"));
    }

    @Test
    public void nativeDay0IsBeijingMidnight() {
        // 固定期望值直接取自参考实现：native_day0_ms(1700000000) == 1699977600000
        long day0 = Identity.nativeDay0Ms(1700000000L);
        assertEquals(1699977600000L, day0);
        // day0 必须是"北京时间 0 点"：加 8 小时后能被 86400000 整除
        assertEquals(0, Math.floorMod(day0 + 28800000L, 86400000L));
    }

    @Test
    public void fingerprintIsDoubleSha256AndDeterministic() {
        String xuid = "A3DF773CB7AC08702E2CFB7B9EC9B2A093C8979C";
        Identity.Fingerprint a = Identity.computeFingerprint(xuid, 1700000000000L);
        Identity.Fingerprint b = Identity.computeFingerprint(xuid, 1700000000000L);
        assertEquals(a.value, b.value);
        assertEquals(64, a.value.length());
        assertTrue(a.value.matches("[0-9a-f]{64}"));
        assertEquals(1699977600000L, a.day0Ms);
        // 时间戳不同 → 指纹不同
        assertFalse(a.value.equals(Identity.computeFingerprint(xuid, 1700000000001L).value));
    }

    @Test
    public void randomHexAndMacFormat() {
        String hex = Identity.randomHexString(16);
        assertEquals(16, hex.length());
        assertTrue(hex.matches("[0-9a-f]{16}"));
        String mac = Identity.randomMacAddress();
        assertTrue("MAC 格式: " + mac, mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}"));
        // 首字节必须是本地管理位（&= 254, |= 2），所以第二个 hex 位只能是 2/6/a/e
        String b0 = mac.substring(0, 2);
        int v = Integer.parseInt(b0, 16);
        assertEquals(0, v & 1);           // 组播位必须清 0
        assertEquals(2, v & 2);           // 本地管理位必须置 1
    }

    @Test
    public void freshHeadersRefreshTimestampAndNonce() {
        Identity.IdentityData id = Identity.buildIdentity(fixedProfile(), Protocol.APP_CHANNEL,
                Protocol.APP_VERSION);
        Map<String, String> h = Identity.freshHeaders(id.headers, "application/json", null, 1234L);
        assertEquals("1234", h.get("X-Timestamp"));
        assertNotNull(h.get("X-Nonce"));
        assertEquals(id.xUid, h.get("X-Uid"));
        assertEquals(id.xFingerprint, h.get("X-Fingerprint"));
        assertEquals("application/json", h.get("Content-Type"));
        assertFalse(h.containsKey("Accept"));   // accept 传 null → 不写
    }

    @Test
    public void rootHeadersAreRoot() {
        Map<String, String> h = Identity.rootHeaders("1.4.1");
        assertEquals("ROOT", h.get("X-Uid"));
        assertEquals("ROOT", h.get("X-Fingerprint"));
        assertEquals("ROOT", h.get("UID"));
        assertEquals("ROOT", h.get("appChannel"));
    }

    @Test
    public void collectHeadersUseDalvikUa() {
        Map<String, String> h = Identity.buildCollectHeaders(fixedProfile());
        assertTrue("UA 应当含机型和 Build: " + h.get("User-Agent"),
                h.get("User-Agent").startsWith("Dalvik/2.1.0 (Linux; U; Android 13;")
                        && h.get("User-Agent").contains("Build/LGTV.2022.OLED88Z2"));
        assertEquals("application/x-www-form-urlencoded", h.get("Content-type"));
    }

    // ==================================================================
    // LiveIds / ChannelSpec.liveId
    // ==================================================================

    @Test
    public void liveIdsCoverAllBackendChannels() {
        for (ChannelSpec spec : ChannelTable.all()) {
            if (!ChannelTable.isBackendChannel(spec.slug)) {
                continue;
            }
            String liveId = LiveIds.forSlug(spec.slug);
            assertFalse("后端频道 " + spec.slug + " 必须有 Live ID", liveId.isEmpty());
            assertTrue("Live ID 格式: " + liveId, liveId.startsWith("Live"));
        }
    }

    @Test
    public void liveIdAgreesWithChannelSpec() {
        // ChannelSpec 是唯一事实源；LiveIds 必须与它一致
        for (ChannelSpec spec : ChannelTable.all()) {
            if (!spec.hasLiveId()) {
                continue;
            }
            assertEquals("ChannelSpec 与 LiveIds 不一致: " + spec.slug,
                    spec.liveId, LiveIds.forSlug(spec.slug));
        }
    }

    @Test
    public void liveIdIsNotSid() {
        // 这是踩过的坑：sid 与 Live ID 是两套编号，不能混用
        ChannelSpec cctv1 = ChannelTable.lookup("cctv1");
        assertNotNull(cctv1);
        assertEquals("2024078201", cctv1.sid);
        assertEquals("Live1717729995180256", LiveIds.forSlug("cctv1"));
        assertFalse("sid 绝不能等于 Live ID", cctv1.sid.equals(LiveIds.forSlug("cctv1")));
    }

    @Test
    public void liveIdViaAlias() {
        // cctv16-4k 归一成 cctv164k 后应当有 Live ID
        assertEquals("Live1704966749996185", LiveIds.forSlug("cctv16-4k"));
        assertEquals("Live1688400593818102", LiveIds.forSlug("cctv8k.m3u8"));
        assertTrue(LiveIds.forSlug("nonexistent-channel").isEmpty());
        assertTrue(LiveIds.forSlug(null).isEmpty());
    }

    // ==================================================================
    // Protocol：上报体 / 结果码 / playlist
    // ==================================================================

    @Test
    public void reportCommonValueHasAllReferenceFields() {
        DeviceProfile p = fixedProfile();
        Map<String, Object> v = Protocol.buildReportCommonValue(p, "XUID", "dangbei", "1.4.1",
                "", 1700000000000L);
        String[] required = {"cctv_id", "device_id", "idfa", "idfv", "user_id", "app_key", "imei",
            "android_id", "mac", "device_builder_type", "device_hardware", "device_board",
            "device_brand", "device_params", "device_display", "device_version_id", "device_host",
            "device_product", "device_tags", "device_user", "device_fingerprint",
            "device_manufacturer", "device_model", "device_resolution", "system_type",
            "device_type", "app_language", "app_version", "sdk_version", "os_version",
            "app_channel", "data_time"};
        for (String k : required) {
            assertTrue("缺少字段 " + k, v.containsKey(k));
        }
        assertEquals("1700000000000", v.get("data_time"));
        assertEquals("Android", v.get("system_type"));
        assertEquals("TV", v.get("device_type"));
        assertEquals("CHINESE", v.get("app_language"));
        assertEquals("13", v.get("os_version"));
        assertEquals("7680*4320", v.get("device_resolution"));
        assertEquals("OLED88Z2PCA", v.get("device_model"));
    }

    @Test
    public void appStartBodyShape() {
        Map<String, Object> b = Protocol.buildAppStartBody(fixedProfile(), "XUID", "dangbei",
                "1.4.1", 1700000000000L);
        assertEquals("app_start_d1", b.get("key"));
        assertNotNull(b.get("value"));
    }

    @Test
    public void vdnAppcommonIsSortedCompactJson() {
        String s = Protocol.buildVdnAppcommon("1.4.1");
        assertEquals("{\"adid\":\"\",\"an\":\"央视频电视投屏助手\",\"ap\":\"cctv_app_tv\",\"av\":\"1.4.1\"}", s);
    }

    @Test
    public void heartbeatBodyShape() {
        Map<String, Object> b = Protocol.buildHeartbeatBody(fixedProfile(), "XUID", "dangbei",
                "1.4.1", "GUID-1", 1700000000000L);
        assertEquals("app_heartbeat", b.get("key"));
        Map<String, Object> v = com.gary.ysplive.core.util.Json.asObject(b.get("value"));
        assertEquals("GUID-1", v.get("guid"));
        assertEquals("WiFi", v.get("network_type"));
        assertEquals("", v.get("other"));
    }

    @Test
    public void live01BodyShape() {
        Map<String, Object> b = Protocol.buildLive01Body("7680-4320-320", "OLED88Z2PCA",
                "Live1717729995180256");
        assertEquals("Live1717729995180256", b.get("id"));
        assertEquals("7680-4320-320", b.get("screenParam"));
        assertEquals("OLED88Z2PCA", b.get("model"));
        assertEquals("ios", b.get("systemType"));
        assertEquals("cctvVideo", b.get("clientSign"));
        assertEquals("", b.get("rate"));
        Map<String, Object> dev = com.gary.ysplive.core.util.Json.asObject(b.get("deviceId"));
        assertEquals("", dev.get("serial"));
        assertEquals("", dev.get("imei"));
        assertEquals("", dev.get("android_id"));
    }

    @Test
    public void parseResultCodeVariants() {
        assertEquals(Integer.valueOf(0), Protocol.parseResultCode(
                com.gary.ysplive.core.util.Json.tryParse("{\"result\":0}")));
        assertEquals(Integer.valueOf(601), Protocol.parseResultCode(
                com.gary.ysplive.core.util.Json.tryParse("{\"result\":\"601\"}")));
        assertEquals(Integer.valueOf(694), Protocol.parseResultCode(
                com.gary.ysplive.core.util.Json.tryParse("{\"data\":{\"code\":694}}")));
        assertNull(Protocol.parseResultCode(
                com.gary.ysplive.core.util.Json.tryParse("{\"foo\":1}")));
        assertNull(Protocol.parseResultCode(null));
    }

    @Test
    public void extractGuidVariants() {
        assertEquals("ABC-123", Protocol.extractGuid(com.gary.ysplive.core.util.Json.tryParse(
                "{\"data\":{\"guid\":\"ABC-123\"}}")));
        assertEquals("", Protocol.extractGuid(com.gary.ysplive.core.util.Json.tryParse(
                "{\"data\":{}}")));
        assertEquals("", Protocol.extractGuid(null));
    }

    @Test
    public void vdnCodeSignIsMd5OfAkSecretRandom() {
        String rand = "00000000-0000-1234-0000-000000005678";
        Protocol.VdnCode c = Protocol.computeVdnCode("SECRET", rand);
        assertEquals(rand, c.randomStr);
        assertEquals(32, c.sign.length());
        assertTrue(c.sign.matches("[0-9a-f]{32}"));
        // 手工核对一次
        assertEquals(com.gary.ysplive.core.util.Crypto.md5Hex(Identity.AK + "SECRET" + rand), c.sign);
    }

    @Test
    public void appRandomStrFormat() {
        String r = Protocol.generateAppRandomStr();
        assertTrue("APPRANDOMSTR 格式: " + r,
                r.matches("[0-9a-f]{8}-0000-[0-9a-f]{4}-0000-00000000[0-9a-f]{4}"));
    }

    @Test
    public void defaultPlaybackHeaders() {
        Map<String, String> h = Protocol.defaultCctvPlaybackHeaders("0123456789abcdef");
        assertEquals("0123456789abcdef", h.get("UID"));
        assertEquals(Identity.AK, h.get("APPID"));
        assertEquals("api.cctv.cn", h.get("Referer"));
        assertEquals("cctv_app_tv", h.get("User-Agent"));
    }

    @Test
    public void signedHeaderRequirementDetection() {
        assertTrue(Protocol.livePlaybackHostNeedsSignedHeaders("liveali-tpgq.cctv.cn"));
        assertTrue(Protocol.livePlaybackHostNeedsSignedHeaders("LIVETEN-x.cctv.cn"));
        assertFalse(Protocol.livePlaybackHostNeedsSignedHeaders("bklive-xxx.cctv.cn"));
        assertFalse(Protocol.livePlaybackHostNeedsSignedHeaders(""));
        assertFalse(Protocol.livePlaybackHostNeedsSignedHeaders(null));

        Map<String, String> h = new HashMap<>();
        h.put("APPID", Identity.AK);
        ChannelEntry missing = new ChannelEntry("cctv4k", "Live1", "http://liveali-x.cctv.cn/a.m3u8",
                h, "0123456789abcdef", "XUID", "36p", "4K", "liveali-x.cctv.cn", 0, 0, 0);
        assertTrue("liveali 缺 APPSIGN/APPRANDOMSTR 应当判为缺签名头",
                Protocol.entryMissingSignedPlaybackHeaders(missing));

        Map<String, String> full = new HashMap<>(h);
        full.put("APPSIGN", "sign");
        full.put("APPRANDOMSTR", "rand");
        ChannelEntry ok = new ChannelEntry("cctv4k", "Live1", "http://liveali-x.cctv.cn/a.m3u8",
                full, "0123456789abcdef", "XUID", "36p", "4K", "liveali-x.cctv.cn", 0, 0, 0);
        assertFalse(Protocol.entryMissingSignedPlaybackHeaders(ok));

        // 非 liveali/liveten 的 host 不强制要求签名头
        ChannelEntry plain = new ChannelEntry("cctv1", "Live2", "http://bklive-x.cctv.cn/a.m3u8",
                h, "0123456789abcdef", "XUID", "10p", "高码率", "bklive-x.cctv.cn", 0, 0, 0);
        assertFalse(Protocol.entryMissingSignedPlaybackHeaders(plain));
    }

    @Test
    public void tsPathMatchingPrefix() {
        String finalUrl = "http://liveali-x.cctv.cn/live/abcdef.m3u8?wsSecret=1";
        assertTrue(Protocol.tsPathMatchesFinalUrl("/live/abcdef_123.ts", finalUrl));
        assertFalse(Protocol.tsPathMatchesFinalUrl("/other/abcdef_123.ts", finalUrl));
        // 注意：Python 只剥掉 ".m3u8" 后缀再 startswith 比较，所以
        // ".../playlist.m3u8" 的前缀是 ".../playlist"，分片必须也以它开头才算匹配。
        String sub = "http://liveali-tp4k.cctv.cn/live/fJx12C/playlist.m3u8";
        assertTrue(Protocol.tsPathMatchesFinalUrl("/live/fJx12C/playlist_x.ts", sub));
        assertFalse("分片不以 /live/fJx12C/playlist 开头，应当不匹配（与 Python 一致）",
                Protocol.tsPathMatchesFinalUrl("/live/fJx12C/seg1.ts", sub));
    }

    // ==================================================================
    // playlist 改写
    // ==================================================================

    @Test
    public void rewritePlaylistUrlsMakesAbsolute() {
        String body = "#EXTM3U\n#EXT-X-VERSION:3\nseg1.ts\nsub/seg2.ts\n"
                + "http://abs.example.com/seg3.ts\n\n#EXT-X-ENDLIST\n";
        String out = Protocol.rewritePlaylistUrls(body,
                "http://liveali-x.cctv.cn/live/abcdef.m3u8?wsSecret=1");
        assertTrue(out.contains("http://liveali-x.cctv.cn/live/seg1.ts"));
        assertTrue(out.contains("http://liveali-x.cctv.cn/live/sub/seg2.ts"));
        assertTrue(out.contains("http://abs.example.com/seg3.ts"));   // 绝对值原样保留
        assertTrue(out.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n"));
        assertTrue(out.endsWith("\n"));
        // 相对路径不能残留
        assertFalse(out.contains("\nseg1.ts\n"));
    }

    @Test
    public void rsLinesHandlesCrlfAndTrailingNewline() {
        List<String> a = Protocol.rsLines("a\r\nb\r\nc");
        assertEquals(3, a.size());
        assertEquals("a", a.get(0));
        assertEquals("b", a.get(1));
        assertEquals("c", a.get(2));

        List<String> b = Protocol.rsLines("a\nb\n");
        assertEquals("末尾换行不应产生空行", 2, b.size());
        assertEquals(0, Protocol.rsLines("").size());
    }

    @Test
    public void joinUrlVariants() {
        String base = "http://h.com/a/b/c.m3u8?q=1";
        // 期望值全部与 Python urllib.parse.urljoin 实测对齐
        assertEquals("http://h.com/a/b/d.ts", Protocol.joinUrl(base, "d.ts"));
        assertEquals("http://h.com/x.ts", Protocol.joinUrl(base, "/x.ts"));
        assertEquals("http://other.com/y.ts", Protocol.joinUrl(base, "http://other.com/y.ts"));
        assertEquals("http://h.com/z.ts", Protocol.joinUrl(base, "//h.com/z.ts"));
        assertEquals("http://h.com/a/c.ts", Protocol.joinUrl(base, "../c.ts"));
        // base 没有 scheme 时原样返回 ref
        assertEquals("d.ts", Protocol.joinUrl("not-a-url", "d.ts"));
    }

    @Test
    public void isTsUrlDetection() {
        assertTrue(Protocol.isTsUrl("http://h.com/a/b.ts"));
        assertTrue(Protocol.isTsUrl("http://h.com/a/b.TS?x=1"));
        assertFalse(Protocol.isTsUrl("http://h.com/a/b.m3u8"));
        assertFalse(Protocol.isTsUrl(""));
    }

    @Test
    public void urlHostAndPath() {
        assertEquals("liveali-x.cctv.cn", Protocol.urlHost("http://liveali-x.cctv.cn:8080/a/b.ts"));
        assertEquals("/a/b.ts", Protocol.urlPath("http://liveali-x.cctv.cn/a/b.ts?x=1"));
        assertEquals("", Protocol.urlHost("not a url"));
    }

    // ==================================================================
    // DeviceState 持久化
    // ==================================================================

    @Test
    public void deviceStateMapRoundTrip() {
        DeviceState st = DeviceState.createDefault();
        st.cloudGuid = "GUID-TEST";
        st.registeredAt = 1700000000.5;
        DeviceState back = DeviceState.fromMap(st.toMap());
        assertEquals(st.profile.androidId, back.profile.androidId);
        assertEquals(st.profile.fingerprint, back.profile.fingerprint);
        assertEquals(st.screenParam, back.screenParam);
        assertEquals(st.castModel, back.castModel);
        assertEquals(st.xUid, back.xUid);
        assertEquals("GUID-TEST", back.cloudGuid);
        assertEquals(1700000000.5, back.registeredAt, 0.0001);
    }

    @Test
    public void deviceStateRejectsBadProfile() {
        DeviceState st = new DeviceState();
        assertFalse("空 profile 必须被拒", st.normalizeAfterLoad());

        // android_id 长度不是 16 也要被拒
        DeviceState st2 = DeviceState.createDefault();
        Map<String, Object> m = st2.toMap();
        Map<String, Object> prof = com.gary.ysplive.core.util.Json.asObject(m.get("profile"));
        prof.put("android_id", "short");
        DeviceState back = DeviceState.fromMap(m);
        assertFalse(back.normalizeAfterLoad());
    }

    @Test
    public void deviceStateFillsDefaultsOnLoad() {
        DeviceState st = DeviceState.createDefault();
        Map<String, Object> m = st.toMap();
        m.put("screen_param", "");
        m.put("cast_model", "");
        DeviceState back = DeviceState.fromMap(m);
        assertTrue(back.normalizeAfterLoad());
        assertEquals("7680-4320-280", back.screenParam);
        assertEquals(back.profile.model, back.castModel);
    }

    @Test
    public void defaultStateHasXUidAndNoGuid() {
        DeviceState st = DeviceState.createDefault();
        assertEquals(16, st.profile.androidId.length());
        assertFalse(st.profile.mac.isEmpty());
        // X-Uid 是 sha1_upper 的结果：40 个 hex 字符（不是 sha256 的 64）
        assertEquals(40, st.xUid.length());
        assertTrue("X-Uid 必须是大写 hex", st.xUid.matches("[0-9A-F]{40}"));
        assertTrue(st.cloudGuid.isEmpty());
        assertEquals(0.0, st.registeredAt, 0.0);
        assertFalse("新建状态必须带 profileSource", st.profileSource.isEmpty());
        assertFalse(st.screenParam.isEmpty());
    }
}
