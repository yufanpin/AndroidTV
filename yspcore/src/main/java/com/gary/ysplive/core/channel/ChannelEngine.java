package com.gary.ysplive.core.channel;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 频道刷新引擎：拥有全部 {@link Channel} 实例、控制面限速器、分片缓存，
 * 以及 Python 参考实现里 {@code ensure_channel} / {@code refresh_once} / {@code refresh_loop} /
 * {@code trigger_background_refresh} / {@code coalesce_refresh} / {@code get_channel_instance}
 * 与 {@code do_GET} 里 {@code /<slug>.m3u8} 分支的全部逻辑。
 *
 * <p>对外提供三种消费方式：
 * <ul>
 *   <li>{@link #playlistFor(String, String, String)} —— 给 {@code core.server} 的 m3u8 路由用</li>
 *   <li>{@link #playlistResult(String, String, String)} —— 同上但带响应头/状态码</li>
 *   <li>{@link #fetchSegmentEx(String, String)} —— 给 {@code /proxy.ts} 取分片用</li>
 * </ul>
 * 所有方法线程安全。
 */
public final class ChannelEngine {

    private static final String TAG = "engine";

    /** 刷新间隔（秒），Python {@code REFRESH_INTERVAL}。 */
    public static final int REFRESH_INTERVAL = 10;
    /** 空闲多少秒后停止后台拉流，Python {@code IDLE_TIMEOUT}。 */
    public static final int IDLE_TIMEOUT = 120;
    /** 设备链接空闲回收窗口（秒），Python {@code LINK_IDLE_TIMEOUT}。 */
    public static final double LINK_IDLE_TIMEOUT = 15.0;
    /** JCE 时移窗口（秒），Python {@code WINDOW}。 */
    public static final int WINDOW = 300;
    /** 分片队列上限，Python {@code MAX_SEGS}。 */
    public static final int MAX_SEGS = 120;
    /** bkliveinfo 地址缓存 TTL（秒），Python {@code BK_URL_TTL}。 */
    public static final int BK_URL_TTL = 300;
    /** SWR 缓存窗口（秒）：Python 里是硬编码的 15.0。 */
    public static final double SWR_TTL_SEC = 15.0;
    /** 超过这个年龄就顺手触发后台刷新（Python 是 6.0）。 */
    public static final double SWR_REFRESH_AFTER_SEC = 6.0;
    /**
     * 同一频道两次"bk 候选 CDN 轮换"的最小间隔（秒）。
     * <p>限流目的：真死的频道不能被打成无限重试；而正常的坏 CDN 一次轮换后就能恢复。
     */
    public static final double BK_ROTATE_MIN_INTERVAL_SEC = 20.0;
    /** bk 候选探活时检查多少个分片（分片可用性按片变化，只探首片会误杀）。 */
    private static final int PROBE_SEG_COUNT = 3;

    /** 一次 m3u8 响应的完整信息（供 HTTP 层直接写 socket）。 */
    public static final class PlaylistResult {
        public final int status;
        public final String body;
        public final String contentType;
        public final Map<String, String> headers;

        PlaylistResult(int status, String body, String contentType, Map<String, String> headers) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.contentType = contentType == null
                    ? "application/vnd.apple.mpegurl" : contentType;
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
        }

        public boolean isOk() {
            return status == 200;
        }
    }

    /** 一次分片响应的完整信息。 */
    public static final class SegmentResult {
        public final byte[] data;
        /** 需要透传的响应头（原样大小写）。 */
        public final Map<String, String> headers;
        /** {@code HIT-RAM} 或 {@code MISS-UPSTREAM}。 */
        public final String cacheState;
        public final int status;

        SegmentResult(byte[] data, Map<String, String> headers, String cacheState, int status) {
            this.data = data == null ? new byte[0] : data;
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
            this.cacheState = cacheState == null ? "MISS-UPSTREAM" : cacheState;
            this.status = status;
        }
    }

    /**
     * 上游取流注入点（测试用）。
     * <p>返回 {@code null} 表示"照常用真实网络"，所以生产路径完全不受影响；
     * 单元测试可以装一个假的，避免连外网。
     */
    public interface UpstreamHook {
        /** 返回 JCE 时移播放列表文本；{@code null} = 走真实网络。 */
        String jcePlaylistText(Channel ch) throws Exception;

        /** 返回备用通道播放列表文本；{@code null} = 走真实网络。 */
        String bkPlaylistText(Channel ch) throws Exception;

        /** 返回时移播放列表文本；{@code null} = 走真实网络。 */
        String timeshiftPlaylistText(Channel ch, Catchup.Timespan ts) throws Exception;
    }

    private final Resolver resolver;
    private final Gateway.Config config;
    private final ApiFlowLimiter flowLimiter;
    private final TsSegmentCache tsCache;

    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Object channelsLock = new Object();

    private final Map<String, CountDownLatch> inflight = new HashMap<>();
    private final Object inflightLock = new Object();

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final long startedAtMs = System.currentTimeMillis();
    private volatile Boolean resolverReadyOverride;
    private volatile UpstreamHook upstreamHook;

    public ChannelEngine(Resolver resolver, Gateway.Config config) {
        this.resolver = resolver;
        this.config = config == null ? new Gateway.Config() : config;
        this.flowLimiter = new ApiFlowLimiter();
        int cacheMb = this.config.cacheMb > 0 ? this.config.cacheMb : 200;
        this.tsCache = new TsSegmentCache(cacheMb, 180);
        buildChannelMap();
        Log.i(TAG, "频道引擎初始化完成，共 " + channels.size() + " 路");
    }

    public ChannelEngine(Resolver resolver) {
        this(resolver, new Gateway.Config());
    }

    private void buildChannelMap() {
        for (ChannelSpec spec : ChannelTable.all()) {
            Channel ch = new Channel(spec);
            if (ChannelTable.isForceBk(spec.slug)) {
                ch.setMode(Channel.MODE_BK);
            }
            channels.put(spec.slug, ch);
        }
    }

    // ------------------------------------------------------------------ 访问器

    public Resolver resolver() {
        return resolver;
    }

    public Gateway.Config config() {
        return config;
    }

    public ApiFlowLimiter flowLimiter() {
        return flowLimiter;
    }

    public TsSegmentCache tsCache() {
        return tsCache;
    }

    public long uptimeSec() {
        return Math.max(0L, (System.currentTimeMillis() - startedAtMs) / 1000L);
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    /** 全部频道（含从未拉取过的），顺序与 {@link ChannelTable#all()} 一致。 */
    public List<Channel> allChannels() {
        synchronized (channelsLock) {
            return new ArrayList<>(channels.values());
        }
    }

    /**
     * 按名字取频道实例，等价于 Python {@code get_channel_instance}：
     * 归一化 + 别名解析后查表，未命中返回 {@code null}。
     */
    public Channel channelFor(String slug) {
        String canonical = ChannelTable.canonical(slug);
        if (canonical == null || canonical.isEmpty()) {
            return null;
        }
        synchronized (channelsLock) {
            Channel ch = channels.get(canonical);
            if (ch != null) {
                return ch;
            }
            ChannelSpec spec = ChannelTable.lookup(canonical);
            if (spec == null) {
                return null;
            }
            ch = new Channel(spec);
            if (ChannelTable.isForceBk(spec.slug)) {
                ch.setMode(Channel.MODE_BK);
            }
            channels.put(canonical, ch);
            return ch;
        }
    }

    public int channelCount() {
        synchronized (channelsLock) {
            return channels.size();
        }
    }

    // ------------------------------------------------------------------ resolver 状态

    /** 高码率设备协议是否就绪。 */
    public boolean resolverReady() {
        Boolean override = resolverReadyOverride;
        if (override != null) {
            return override;
        }
        if (resolver == null) {
            return false;
        }
        try {
            Resolver.Status st = resolver.status();
            return st != null && st.ready;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 强制设置就绪标记（桌面联调/测试用；{@code null} 表示回到 resolver 自报状态）。 */
    public void setResolverReady(Boolean ready) {
        this.resolverReadyOverride = ready;
    }

    /** 装一个上游取流注入点（测试用；{@code null} = 真实网络）。 */
    public void setUpstreamHook(UpstreamHook hook) {
        this.upstreamHook = hook;
    }

    public UpstreamHook upstreamHook() {
        return upstreamHook;
    }

    /** 是否允许访问真实网络（测试里置 false 后，所有网络路径都返回"无数据"）。 */
    private volatile boolean networkEnabled = true;

    public void setNetworkEnabled(boolean enabled) {
        this.networkEnabled = enabled;
    }

    public boolean isNetworkEnabled() {
        return networkEnabled;
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    // ------------------------------------------------------------------ 限速

    /** 控制面限速：JCE / bkliveinfo 之间至少 700ms ± 250ms。 */
    public void waitApiSlot() {
        flowLimiter.waitSlot();
    }

    // ------------------------------------------------------------------ coalesce（合并并发刷新）

    /**
     * 同一个 slug 的刷新合并成一次执行。等价于 Python {@code coalesce_refresh}：
     * 首个调用者负责真正执行，其余等待同一个完成信号。
     *
     * @param timeoutSec 等待上限；{@code null} 表示"执行完才返回"
     */
    public void coalesceRefresh(String slug, Runnable refreshFn, Double timeoutSec) {
        CountDownLatch gate;
        boolean waiter;
        synchronized (inflightLock) {
            CountDownLatch existing = inflight.get(slug);
            if (existing != null) {
                gate = existing;
                waiter = true;
            } else {
                gate = new CountDownLatch(1);
                inflight.put(slug, gate);
                waiter = false;
            }
        }
        if (waiter) {
            double t = timeoutSec == null ? 6.0 : timeoutSec;
            awaitQuietly(gate, (long) (t * 1000L));
            return;
        }
        final CountDownLatch done = gate;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshFn.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "刷新任务异常 " + slug + ": " + e, 10.0);
                } finally {
                    synchronized (inflightLock) {
                        inflight.remove(slug);
                    }
                    done.countDown();
                }
            }
        }, "ysp-refresh-" + slug);
        t.setDaemon(true);
        t.start();
        if (timeoutSec != null) {
            awaitQuietly(gate, (long) (timeoutSec * 1000L));
        } else {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long ms) {
        try {
            latch.await(Math.max(1L, ms), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 该 slug 当前是否有刷新在飞。 */
    public boolean isRefreshing(String slug) {
        synchronized (inflightLock) {
            return inflight.containsKey(slug);
        }
    }

    // ------------------------------------------------------------------ 单次刷新

    /**
     * 刷新一次。等价于 Python {@code refresh_once}：
     * bk 模式走 {@link #bkRefresh}，jce 模式遇到死域名自动切 bk。
     */
    public boolean refreshOnce(Channel ch) {
        if (ch == null) {
            return false;
        }
        if (shutdown.get()) {
            return false;
        }
        try {
            boolean ok;
            if (Channel.MODE_BK.equals(ch.mode())) {
                ok = bkRefresh(ch);
            } else {
                try {
                    ok = jceRefresh(ch);
                } catch (Upstream.DeadHostException dead) {
                    ch.setMode(Channel.MODE_BK);
                    Log.i(TAG, "[" + ch.slug() + "] JCE 域名受限，自动切换 bkliveinfo 备用通道", 10.0);
                    ok = bkRefresh(ch);
                }
            }
            if (ok) {
                ch.setLastOk(Channel.nowSec());
            }
            return ok;
        } catch (Exception e) {
            String msg = shorten(e.getClass().getSimpleName() + ": " + e.getMessage(), 120);
            ch.setLastError(msg);
            Log.i(TAG, "[" + ch.slug() + "] " + ch.defnLabel() + " 刷新异常: " + msg, 15.0);
            return false;
        }
    }

    /**
     * 拉 JCE 时移播放列表并入库。等价于 Python {@code jce_refresh}。
     *
     * @throws Upstream.DeadHostException JCE 返回死 CDN 地址
     */
    public boolean jceRefresh(Channel ch) throws Exception {
        List<Channel.RawSeg> segs = jceFetch(ch);
        Channel.MergeResult merged = ch.applyJceSegments(segs);
        if (merged.isInit && merged.added > 0) {
            Log.i(TAG, "[" + ch.slug() + "] " + ch.defnLabel()
                    + " 备用流缓冲就绪 (" + ch.segmentCount() + " 片)");
        }
        return true;
    }

    /** 拉一次 JCE 时移播放列表，解析成有序分片。等价于 Python {@code jce_fetch}。 */
    public List<Channel.RawSeg> jceFetch(Channel ch) throws Exception {
        String text;
        UpstreamHook hook = upstreamHook;
        if (hook != null) {
            text = hook.jcePlaylistText(ch);
            if (text == null) {
                text = jceFetchReal(ch);
            }
        } else if (!networkEnabled) {
            throw new IOException("network disabled");
        } else {
            text = jceFetchReal(ch);
        }
        return parseJceText(text, ch);
    }

    /** 真实网络路径：限速 → 换时移地址 → 拉播放列表。 */
    private String jceFetchReal(Channel ch) throws Exception {
        flowLimiter.waitSlot();
        long now = System.currentTimeMillis() / 1000L;
        String m3u8Url = Upstream.jceTimeshiftUrl(
                ch.pid(), ch.sid(), now - WINDOW, now, ch.defn());
        return Upstream.fetchJcePlaylist(m3u8Url);
    }

    /** 把 JCE 播放列表文本解析成分片序列（相对地址已在上游被 join 成绝对地址）。 */
    List<Channel.RawSeg> parseJceText(String text, Channel ch) throws IOException {
        String base = "http://placeholder.invalid/";
        List<Channel.RawSeg> segs = new ArrayList<>();
        double dur = 6.0;
        String pdt = "";
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.startsWith("#EXTINF:")) {
                String val = line.substring("#EXTINF:".length());
                int comma = val.indexOf(',');
                if (comma >= 0) {
                    val = val.substring(0, comma);
                }
                try {
                    dur = Double.parseDouble(val.trim());
                } catch (NumberFormatException e) {
                    dur = 6.0;
                }
            } else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                pdt = line.substring("#EXT-X-PROGRAM-DATE-TIME:".length());
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segs.add(new Channel.RawSeg(dur, pdt, UrlX.urlJoin(base, line)));
                pdt = "";
            }
        }
        if (segs.isEmpty()) {
            throw new IOException("empty playlist");
        }
        return segs;
    }

    /**
     * 拉取 bkliveinfo 备用通道播放列表。等价于 Python {@code bk_refresh}：
     * 地址按 {@link #BK_URL_TTL} 缓存；两次尝试（第一次失败后重新取地址）。
     */
    public boolean bkRefresh(Channel ch) throws Exception {
        UpstreamHook hook = upstreamHook;
        if (hook != null) {
            String pl = hook.bkPlaylistText(ch);
            if (pl != null) {
                if (!pl.contains("#EXTM3U")) {
                    throw new IOException("bk playlist missing #EXTM3U");
                }
                ch.setBkPlaylist(pl);
                ch.setLastError("");
                return true;
            }
        }
        if (!networkEnabled) {
            throw new IOException("network disabled");
        }
        flowLimiter.waitSlot();
        // 清晰度阶梯：一次 bkRefresh 最多推进一级，靠后续刷新（约 10s）慢慢收敛。
        // 之所以不在一次调用里连降 5 级：这台上游的健康度是**按请求**随机波动的，
        // 一口气烧完整条阶梯等于拿一次不走运的采样当结论，之后还得吃 60s 冷却。
        Candidate best = new Candidate();
        // 先在当前 rung 上试一轮候选
        if (ch.bkUrlsStale(BK_URL_TTL) || !ch.effDefn().equals(ch.bkUrlsDefn())) {
            List<String> urls = Upstream.bkPlayurls(ch.sid(), ch.pid(), ch.effDefn());
            ch.setBkUrls(urls);
            ch.setBkUrlsDefn(ch.effDefn());
            ch.setBkUrlsTime(Channel.nowSec());
            Log.i(TAG, "[" + ch.slug() + "] bkliveinfo 备用通道就绪 ("
                    + urls.size() + " 地址, defn=" + ch.effDefn() + ")", 60.0);
        }
        tryBkCandidates(ch, best);

        // 当前 rung 没有可播分片 → 最多降一级，然后在本调用内重试一次。
        // 一次刷新只降一级是刻意的：这台 CDN 的健康度是**按请求**随机波动的，
        // 一口气把 5 级全烧掉等于拿一次不走运的采样当结论，之后还要吃 60s 冷却，更糟。
        if (best.alive == 0 && !ch.isBkLadderExhausted() && ch.canStartBkLadderWalk()) {
            String prev = ch.effDefn();
            String next = ch.advanceBkLadder();
            if (next == null) {
                Log.i(TAG, "[" + ch.slug() + "] bk 阶梯已到底（" + prev + "）", 10.0);
                ch.markBkLadderExhausted();
            } else {
                Log.i(TAG, "[" + ch.slug() + "] bk " + prev + " 源全挂，降级 " + next + " 重试", 10.0);
                try {
                    List<String> urls = Upstream.bkPlayurls(ch.sid(), ch.pid(), next);
                    ch.setBkUrls(urls);
                    ch.setBkUrlsDefn(next);
                    ch.setBkUrlsTime(Channel.nowSec());
                } catch (Exception e) {
                    Log.w(TAG, "[" + ch.slug() + "] 降级后取地址失败: " + e.getMessage(), 10.0);
                }
                best = new Candidate();
                tryBkCandidates(ch, best);
            }
        }

        if (best.pl != null && best.alive > 0) {
            ch.setBkCandidateIndex(Math.max(0, best.idx));
            ch.setBkPlaylist(best.pl);
            ch.setLastError("");
            ch.noteBkSuccess();
            Log.i(TAG, "[" + ch.slug() + "] bk " + best.defn + " 已出片 (活片 "
                    + best.alive + "/" + PROBE_SEG_COUNT + ", 候选 " + best.idx + ")", 10.0);
            return true;
        }
        // 一个能播的片都没有：仍然把列表交出去（播放器可能碰到能播的片），
        // 但如实记录错误，并靠分片阶段的轮换兜着。
        if (best.pl != null) {
            ch.setBkCandidateIndex(Math.max(0, best.idx));
            ch.setBkPlaylist(best.pl);
            ch.setLastError("bk 分片全部不可播 (defn=" + best.defn + ")");
            ch.noteBkFailure();
            Log.i(TAG, "[" + ch.slug() + "] bk " + best.defn
                    + " 探活 0/" + PROBE_SEG_COUNT + " 可播，先交出列表待分片阶段重试", 10.0);
            return true;
        }
        ch.noteBkFailure();
        throw new IOException("bk playlist failed (defn=" + ch.effDefn() + ")");
    }

    /**
     * 在<b>当前</b> defn 下枚举所有候选 CDN，返回最佳播放列表（或 {@code null}）。
     * <p>含健康优先排序（{@link #candidateOrder}）+ 分片探活打分。
     * 入参 {@code best} 是跨 defn 累积的"目前最好的一条"，本方法会把更优的结果写进去。
     */
    private void tryBkCandidates(Channel ch, Candidate best) {
        List<String> urls = ch.bkUrls();
        if (urls.isEmpty()) {
            return;
        }
        int count = urls.size();
        int start = Math.floorMod(ch.bkCandidateIndex(), count);
        String fallbackPl = null;
        int fallbackIdx = -1;
        for (int step = 0; step < count; step++) {
            // 实测规律：`bk` 前缀域名（bklive-/bkhlsliveali-/bkmobilelive-）的数据面整片 403，
            // 而不带该前缀的同源域名（live-/hlsliveali-/mobilelive-）正常。
            // Python 的 bk_playurls 反而把 'bklive-' 排在最前，等于优先选死域名，这里反过来。
            int idx = candidateOrder(start, step, count, urls);
            String u = urls.get(idx);
            String pl;
            try {
                pl = Upstream.fetchAbsPlaylist(u);
            } catch (Upstream.HttpStatusException e) {
                Log.i(TAG, "[" + ch.slug() + "] bk 候选 " + idx + " HTTP " + e.status, 30.0);
                continue;
            } catch (Exception e) {
                Log.i(TAG, "[" + ch.slug() + "] bk 候选 " + idx + " 异常: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage(), 30.0);
                continue;
            }
            if (!pl.contains("#EXTM3U")) {
                continue;
            }
            if (fallbackPl == null) {
                fallbackPl = pl;
                fallbackIdx = idx;
            }
            // 探活打分：事故期间分片可用性是按片变化的，用"活片数"而不是布尔值来挑。
            int alive = probePlayableSegments(pl);
            if (alive > best.alive || (alive == best.alive && alive > 0 && best.pl == null)) {
                best.alive = alive;
                best.pl = pl;
                best.idx = idx;
                best.defn = ch.effDefn();
            }
            if (alive >= PROBE_SEG_COUNT) {
                return;   // 全片可用，不用再看别的候选
            }
        }
        // 一个都没探活过：仍然保留一条能拉的列表，好过 503
        if (best.pl == null && fallbackPl != null) {
            best.pl = fallbackPl;
            best.idx = fallbackIdx;
            best.defn = ch.effDefn();
            Log.i(TAG, "[" + ch.slug() + "] bk 候选探活均不可用，暂用候选 "
                    + fallbackIdx + "/" + count + "（待分片阶段再轮换）", 30.0);
        }
    }

    /** 跨 defn 累积的"最佳候选"评分。 */
    private static final class Candidate {
        String pl;
        String defn;
        int idx = -1;
        int alive;
    }

    // ------------------------------------------------------------------ 后台循环

    /**
     * 后台刷新循环。等价于 Python {@code refresh_loop}：
     * 最近 {@link #IDLE_TIMEOUT} 秒内有访问就持续刷新，每 {@link #REFRESH_INTERVAL} 秒一次，
     * 连续失败 3 次后退避到 30 秒。
     */
    public void refreshLoop(Channel ch) {
        Log.i(TAG, "[" + ch.slug() + "] " + ch.defnLabel()
                + " 后台拉取启动 [" + ch.mode() + "]", 10.0);
        int fails = 0;
        if (Channel.nowSec() - ch.lastOk() < REFRESH_INTERVAL) {
            ch.buildPlaylist(true);
            sleepQuietly(REFRESH_INTERVAL * 1000L);
        }
        while (!shutdown.get() && (Channel.nowSec() - ch.lastAccess() < IDLE_TIMEOUT)) {
            boolean ok = refreshOnce(ch);
            if (ok) {
                ch.buildPlaylist(true);
            }
            fails = ok ? 0 : fails + 1;
            sleepQuietly((fails < 3 ? REFRESH_INTERVAL : 30) * 1000L);
        }
        Log.i(TAG, "[" + ch.slug() + "] 空闲超时，已停止后台拉取", 10.0);
    }

    /**
     * 确保频道有后台拉流线程与可用的分片。等价于 Python {@code ensure_channel}。
     */
    public void ensureChannel(Channel ch) {
        if (ch == null) {
            return;
        }
        double now = Channel.nowSec();
        ch.setLastAccess(now);
        boolean waitOnly;
        boolean needFetch;
        boolean threadDead;
        synchronized (ch.lock()) {
            if (ch.isStarting()) {
                waitOnly = true;
                needFetch = false;
                threadDead = false;
            } else {
                waitOnly = false;
                threadDead = ch.threadDead();
                boolean cacheStale = (now - ch.lastOk() > IDLE_TIMEOUT)
                        || (!ch.hasSegments() && ch.bkPlaylist().isEmpty());
                if (cacheStale) {
                    ch.clearBuffer();
                    needFetch = true;
                } else {
                    needFetch = false;
                }
                if (needFetch || threadDead) {
                    ch.setStarting(true);
                } else {
                    return;
                }
            }
        }
        if (waitOnly) {
            final Channel target = ch;
            coalesceRefresh(ch.slug(), new Runnable() {
                @Override
                public void run() {
                    refreshOnce(target);
                }
            }, 5.0);
            return;
        }
        final Channel target = ch;
        try {
            if (needFetch) {
                coalesceRefresh(ch.slug(), new Runnable() {
                    @Override
                    public void run() {
                        refreshOnce(target);
                    }
                }, 5.0);
            }
            if (threadDead) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        refreshLoop(target);
                    }
                }, "ysp-loop-" + ch.slug());
                t.setDaemon(true);
                ch.setThread(t);
                t.start();
            }
        } finally {
            ch.setStarting(false);
        }
    }

    /**
     * 后台触发一次刷新 + 重建 playlist。等价于 Python {@code trigger_background_refresh}。
     */
    public void triggerBackgroundRefresh(final Channel ch) {
        if (ch == null) {
            return;
        }
        synchronized (inflightLock) {
            if (inflight.containsKey(ch.slug())) {
                return;
            }
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                coalesceRefresh(ch.slug(), new Runnable() {
                    @Override
                    public void run() {
                        if (refreshOnce(ch)) {
                            ch.buildPlaylist(true);
                        }
                    }
                }, null);
            }
        }, "ysp-bg-" + ch.slug());
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ m3u8 路由

    /**
     * 频道 m3u8（do_GET 的 {@code /<slug>.m3u8} 逻辑）。
     *
     * @param slug       任意写法（自动归一化 + 别名）
     * @param hostHeader 请求的 Host 头（用于改写代理地址）
     * @param proto      {@code http} / {@code https}
     * @return m3u8 文本；频道不存在返回 {@code null}
     */
    public String playlistFor(String slug, String hostHeader, String proto) {
        PlaylistResult r = playlistResult(slug, hostHeader, proto);
        return r == null ? null : r.body;
    }

    /**
     * 同 {@link #playlistFor}，但带状态码与响应头（{@code X-SWR} 等）。
     * <p>顺序严格照抄 Python：时移 → 设备协议 → SWR 缓存 → 1080p 构建 → 上游引擎 → 503。
     *
     * @return 频道不存在返回 {@code null}（由 HTTP 层回 404）
     */
    public PlaylistResult playlistResult(String slug, String hostHeader, String proto) {
        return playlistResult(slug, hostHeader, proto, null);
    }

    public PlaylistResult playlistResult(String slug, String hostHeader, String proto,
                                         Catchup.Timespan catchup) {
        String rawSlug = ChannelTable.normalize(slug);
        String canonical = ChannelTable.canonical(rawSlug);
        Channel ch = channelFor(canonical);
        if (ch == null) {
            return null;
        }
        String host = (hostHeader == null || hostHeader.trim().isEmpty())
                ? "localhost:" + config.port : hostHeader.trim();
        String scheme = normalizeProto(proto);

        // 1) 时移分支
        if (catchup != null && ChannelTable.supportsTimeshift(canonical)) {
            String pl = timeshiftPlaylist(ch, catchup);
            if (pl != null) {
                return new PlaylistResult(200, pl, "application/vnd.apple.mpegurl", null);
            }
        }

        // 2) 设备协议（高码率）
        if (ChannelTable.isBackendChannel(canonical) && resolverReady() && resolver != null) {
            if (!resolver.isChannelInCooldown(canonical)) {
                try {
                    String pl = devicePlaylist(ch, host, scheme);
                    if (pl != null && pl.contains("#EXTM3U")) {
                        String prevSrc;
                        synchronized (ch.lock()) {
                            prevSrc = ch.lastM3u8Source();
                            ch.setM3u8Source(Channel.SOURCE_DEVICE);
                            ch.invalidateBuilt();
                        }
                        if (Channel.SOURCE_BK.equals(prevSrc)) {
                            pl = Upstream.injectHlsDiscontinuity(pl);
                        }
                        // 设备协议成功 = 真的拿到了高码率，界面可以如实标 4K
                        com.gary.ysplive.core.data.QualityState.reportResolution(canonical, true);
                        return new PlaylistResult(200, pl, "application/vnd.apple.mpegurl", null);
                    }
                } catch (Exception e) {
                    String clean = shorten(String.valueOf(e.getMessage()).trim(), 160);
                    Log.i(TAG, "[" + canonical + "] 高码率播放暂时受限 (" + clean
                            + ")，平滑回退 1080p", 5.0);
                }
            }
        }

        // 3) SWR 缓存
        double nowTs = Channel.nowSec();
        ch.setLastAccess(nowTs);
        String cachedPl;
        double cachedAge;
        synchronized (ch.lock()) {
            cachedPl = ch.lastBuiltPlaylist();
            cachedAge = nowTs - ch.lastBuiltTime();
        }
        if (!cachedPl.isEmpty() && cachedAge < SWR_TTL_SEC && cachedPl.contains("#EXTM3U")) {
            String prevSrc;
            synchronized (ch.lock()) {
                prevSrc = ch.lastM3u8Source();
                ch.setM3u8Source(Channel.SOURCE_BK);
            }
            if (Channel.SOURCE_DEVICE.equals(prevSrc)) {
                cachedPl = Upstream.injectHlsDiscontinuity(cachedPl);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-SWR", "HIT");
            if (cachedAge > SWR_REFRESH_AFTER_SEC) {
                triggerBackgroundRefresh(ch);
            }
            return new PlaylistResult(200, cachedPl, "application/vnd.apple.mpegurl", headers);
        }

        // 4) 1080p 备用通道
        ensureChannel(ch);
        String pl = ch.buildPlaylist(true);
        if (pl != null && pl.contains("#EXTM3U")) {
            String prevSrc;
            synchronized (ch.lock()) {
                prevSrc = ch.lastM3u8Source();
                ch.setM3u8Source(Channel.SOURCE_BK);
            }
            // 走到这里说明走的是 bk 1080p 通道：
            // 标称 4K/8K 的频道此时是降级播放，界面应如实显示而不是继续标 4K。
            com.gary.ysplive.core.data.QualityState.reportResolution(canonical, false);
            if (Channel.SOURCE_DEVICE.equals(prevSrc)) {
                pl = Upstream.injectHlsDiscontinuity(pl);
            }
            return new PlaylistResult(200, pl, "application/vnd.apple.mpegurl", null);
        }

        // 5) 上游引擎（兼容另一个本地 ysp-live 的 8787 引擎）
        if (networkEnabled) {
            String upstreamPl = Upstream.fetchUpstreamEnginePlaylist(canonical, host, scheme);
            if (upstreamPl != null && upstreamPl.contains("#EXTM3U")) {
                return new PlaylistResult(200, upstreamPl, "application/vnd.apple.mpegurl", null);
            }
        }

        // 6) 503
        String err = ch.lastError();
        String body = "频道 " + ch.name() + " 暂无数据 ("
                + (err.isEmpty() ? "拉取中" : err) + "), 请稍后重试\n";
        return new PlaylistResult(503, body, "text/plain; charset=utf-8", null);
    }

    /**
     * 时移播放列表。对应 Python do_GET 里 {@code catchup_times && canonical in TIMESHIFT_SUPPORTED}
     * 的分支：调 JCE 拿时移地址，再拉播放列表原样返回。
     *
     * @return 失败返回 {@code null}（调用方回退直播）
     */
    public String timeshiftPlaylist(Channel ch, Catchup.Timespan ts) {
        if (ch == null || ts == null) {
            return null;
        }
        UpstreamHook hook = upstreamHook;
        if (hook != null) {
            try {
                String pl = hook.timeshiftPlaylistText(ch, ts);
                if (pl != null) {
                    return pl.contains("#EXTM3U") ? pl : null;
                }
            } catch (Exception e) {
                Log.i(TAG, "[" + ch.slug() + "] 时移获取失败: " + e.getMessage() + "，回退直播", 10.0);
                return null;
            }
        }
        if (!networkEnabled) {
            return null;
        }
        try {
            flowLimiter.waitSlot();
            String m3u8Url = Upstream.jceTimeshiftUrl(
                    ch.pid(), ch.sid(), ts.startEpochSec, ts.endEpochSec, ch.defn());
            String pl = Upstream.fetchAbsPlaylist(m3u8Url);
            if (pl != null && pl.contains("#EXTM3U")) {
                return pl;
            }
            Log.i(TAG, "[" + ch.slug() + "] 时移获取失败: 播放列表为空，回退直播", 10.0);
            return null;
        } catch (Exception e) {
            Log.i(TAG, "[" + ch.slug() + "] 时移获取失败: " + e.getMessage() + "，回退直播", 10.0);
            return null;
        }
    }

    /**
     * 走设备协议解析并改写分片为本地代理地址。
     * <p>等价于 Python 的 {@code resolver.fetch_channel_playlist(canonical, host, proto)}。
     */
    private String devicePlaylist(Channel ch, String host, String proto) throws Exception {
        ChannelEntry entry;
        try {
            entry = resolver.resolve(ch.slug());
        } catch (ResolutionException e) {
            throw new IOException(e.getMessage() == null ? "resolution failed" : e.getMessage(), e);
        }
        if (entry == null || entry.finalUrl.isEmpty()) {
            throw new IOException("resolver returned empty finalUrl");
        }
        String body;
        try {
            Upstream.Resp resp = Upstream.httpGet(
                    entry.finalUrl, entry.playbackHeaders, Upstream.PLAYLIST_TIMEOUT_MS);
            if (!resp.ok()) {
                throw new IOException("HTTP " + resp.status);
            }
            body = resp.text();
        } catch (IOException e) {
            throw new IOException("拉取设备 m3u8 失败: " + e.getMessage(), e);
        }
        String proxyOrigin = proto + "://" + host;
        String pl = resolver.proxyPlaylist(body, entry.finalUrl, proxyOrigin, ch.slug());
        if (pl == null || pl.isEmpty()) {
            throw new IOException("proxyPlaylist returned empty");
        }
        if (!pl.contains("#EXTM3U")) {
            throw new IOException("device playlist missing #EXTM3U");
        }
        resolver.clearChannelCooldown(ch.slug());
        return pl;
    }

    /** 记录一次设备协议失败（供 resolver 实现自行统计；接口未暴露失败钩子时只打日志）。 */
    public void noteDeviceFailure(String slug, String reason) {
        Log.i(TAG, "[" + slug + "] 设备协议失败: " + shorten(reason, 120), 5.0);
    }

    // ------------------------------------------------------------------ 分片代理

    /**
     * 取一个 TS 分片（{@code /proxy.ts} 的实现核心）。
     * <p>无 Range 时先查 {@link TsSegmentCache}，未命中再请求上游并在 200 时回填缓存。
     *
     * @param realUrl     真实分片地址
     * @param rangeHeader 播放器传的 {@code Range} 头（可为 {@code null}）
     */
    public byte[] fetchSegment(String realUrl, String rangeHeader) throws IOException {
        return fetchSegmentEx(realUrl, rangeHeader).data;
    }

    /** 同 {@link #fetchSegment}，但返回响应头与缓存状态。 */
    public SegmentResult fetchSegmentEx(String realUrl, String rangeHeader) throws IOException {
        if (realUrl == null || realUrl.isEmpty()) {
            throw new IllegalArgumentException("empty ts url");
        }
        String scheme = UrlX.schemeOf(realUrl);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("invalid ts scheme: " + scheme);
        }
        boolean hasRange = rangeHeader != null && !rangeHeader.trim().isEmpty();

        // 1) 缓存命中
        if (!hasRange) {
            TsSegmentCache.Entry hit = tsCache.get(realUrl);
            if (hit != null) {
                return new SegmentResult(hit.data, hit.headers, "HIT-RAM", 200);
            }
        }

        // 2) 取签名头
        Map<String, String> playbackHeaders = new LinkedHashMap<>();
        if (resolver != null) {
            try {
                Map<String, String> h = resolver.playbackHeadersForTsUrl(realUrl);
                if (h != null) {
                    playbackHeaders.putAll(h);
                }
            } catch (ResolutionException e) {
                throw new IOException("取分片签名头失败: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new IOException("取分片签名头失败: " + e, e);
            }
        }
        Map<String, String> extra = new LinkedHashMap<>();
        if (hasRange) {
            extra.put("Range", Upstream.headerValueClean(rangeHeader));
        }
        Map<String, String> headers = Upstream.playbackRequestHeaders(playbackHeaders, extra);

        // 3) 请求上游
        Upstream.Resp resp = Upstream.httpGet(realUrl, headers, 30_000);

        // 3b) 分片被拒（403/404）→ 若是 bk 通道，轮换到下一个候选 CDN 并就地重试一次。
        //     背景：某些 CDN 的播放列表能拉通，但数据面整片 403（实测 cctv4k 候选 0 全 403、
        //     候选 1 正常）。只看播放列表会永久锁死在坏 CDN 上，必须由分片失败驱动轮换。
        //     注意：只针对 bk 通道；设备协议的 403 走 resolver 自己的设备轮换，这里绝不碰设备身份。
        if ((resp.status == 403 || resp.status == 404) && !hasRange) {
            SegmentResult rotated = tryRotateBkCandidate(realUrl, rangeHeader);
            if (rotated != null) {
                return rotated;
            }
        }

        Map<String, String> pass = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            String k = e.getKey();
            String lower = k == null ? "" : k.toLowerCase(Locale.ROOT);
            if (lower.equals("content-type") || lower.equals("content-range")
                    || lower.equals("accept-ranges") || lower.equals("content-length")
                    || lower.equals("location")) {
                pass.put(k, e.getValue());
            }
        }
        if (pass.isEmpty()) {
            pass.put("Content-Type", "video/mp2t");
        }

        // 4) 回填缓存（仅 200 且无 Range、且长度自洽、且不超 35MB）
        if (resp.status == 200 && !hasRange && resp.body.length > 0
                && resp.body.length <= Upstream.MAX_SEGMENT_BYTES) {
            String cl = Upstream.headerOrNull(resp.headers, "content-length");
            boolean consistent = true;
            if (cl != null) {
                try {
                    consistent = Long.parseLong(cl.trim()) == resp.body.length;
                } catch (NumberFormatException ignored) {
                    consistent = true;
                }
            }
            if (consistent) {
                tsCache.set(realUrl, resp.body, pass);
            }
        }
        return new SegmentResult(resp.body, pass, "MISS-UPSTREAM", resp.status);
    }

    // ------------------------------------------------------------------ bk 候选探活

    /**
     * 按"健康优先"顺序枚举 bk 候选下标。
     * <p>实测（2026-11 上游事故期间）：{@code bk} 前缀域名
     * （{@code bklive-…} / {@code bkhlsliveali-…} / {@code bkmobilelive-…}）
     * 播放列表可拉但**分片整片 403**，而不带该前缀的同源域名正常。
     * Python 的 {@code bk_playurls} 偏偏把 {@code bklive-} 排在最前，等于优先选死域名。
     * <p>所以枚举顺序改为：先给出<b>不含</b> {@code bk} 前缀的候选，再给含前缀的；
     * 各自仍从 {@code start}（= 上次轮换到的下标）开始环形遍历，保留轮换语义。
     */
    static int candidateOrder(int start, int step, int count, java.util.List<String> urls) {
        int healthySeen = 0;
        int deadSeen = 0;
        int healthyTotal = 0;
        for (int i = 0; i < count; i++) {
            if (!isBkPrefixedHost(urls.get(i))) {
                healthyTotal++;
            }
        }
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % count;
            boolean dead = isBkPrefixedHost(urls.get(idx));
            int rank = dead ? healthyTotal + (deadSeen++) : (healthySeen++);
            if (rank == step) {
                return idx;
            }
        }
        return (start + step) % count;
    }

    /** 该 URL 的 host 是否以 {@code bk} 开头（这类域名数据面当前不可用）。 */
    static boolean isBkPrefixedHost(String url) {
        String host = UrlX.hostOf(url);
        return host != null && host.startsWith("bk");
    }

    /** 首片探活结果。 */
    private enum ProbeResult {
        /** 首片能拿到（2xx），该候选可用。 */
        ALIVE,
        /** 首片明确 403/404，该候选数据面已死。 */
        DEAD,
        /** 网络抖动/超时等不确定情况：当作可用，不做额外轮换（避免误杀）。 */
        UNKNOWN
    }

    /**
     * 对播放列表的前 {@link #PROBE_SEG_COUNT} 个分片做 HEAD 探活，返回**可播片数**。
     * <p>用 HTTP HEAD 只取状态码（不下载 body），成本可忽略。
     * <p>返回计数而不是布尔值：事故期间分片可用性是**按片**变化的
     * （实测同一 playlist 里 403/200/403 混着来），只判"通/不通"会把能用的候选误杀掉。
     */
    private int probePlayableSegments(String playlist) {
        List<String> segs = new ArrayList<>();
        for (String rawLine : playlist.split("\n")) {
            String line = rawLine.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                segs.add(line);
                if (segs.size() >= PROBE_SEG_COUNT) {
                    break;
                }
            }
        }
        if (segs.isEmpty()) {
            return 0;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", Upstream.BK_UA);
        headers.put("Referer", Upstream.BK_REFERER);
        headers.put("Accept", "*/*");
        // 上游此刻是**按请求**随机拒绝的（实测同一 playlist 连探 5 次得 1/3/3/3/3），
        // 所以"探到 0"不能直接判死——再给一次机会，避免把能用的候选误杀。
        int alive = probeOnce(segs, headers);
        if (alive == 0) {
            sleepQuietly(250);
            alive = probeOnce(segs, headers);
        }
        return alive;
    }

    /** 对给定的分片列表做一轮 HEAD 探活，返回 2xx 的数量。 */
    private int probeOnce(List<String> segs, Map<String, String> headers) {
        int alive = 0;
        for (String segUrl : segs) {
            try {
                int status = Upstream.headStatus(segUrl, headers, 6000);
                if (status >= 200 && status < 300) {
                    alive++;
                }
            } catch (IOException ignored) {
                // 探活失败当作不可播；分片阶段还会再来
            }
        }
        return alive;
    }

    /** 首片探活（保留给诊断用）。 */
    private ProbeResult probeFirstSegment(String playlist) {
        int alive = probePlayableSegments(playlist);
        if (alive > 0) {
            return ProbeResult.ALIVE;
        }
        return ProbeResult.DEAD;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 分片被上游拒绝（403/404）时，尝试把该频道轮换到下一个 bk 候选 CDN。
     * <p>只在 {@code bk} 模式的频道上生效，且有 {@link #BK_ROTATE_MIN_INTERVAL_SEC} 限流，
     * 避免一个真死的频道被打成无限重试。
     *
     * @return 轮换后重新请求到的分片结果（成功时）；不适用/未轮换返回 {@code null}
     */
    private SegmentResult tryRotateBkCandidate(String realUrl, String rangeHeader) {
        // 找出这个分片地址可能属于哪个频道：按 host 匹配当前 bk 播放列表里的分片域名。
        // 分片 URL 在 bk 播放列表里是绝对地址，可以直接和 bkUrls 的 host 比对。
        Channel target = channelForBkSegment(realUrl);
        if (target == null) {
            return null;
        }
        if (!Channel.MODE_BK.equals(target.mode())) {
            return null;
        }
        if (!target.canRotateBkCandidate(BK_ROTATE_MIN_INTERVAL_SEC)) {
            return null;
        }
        List<String> urls = target.bkUrls();
        if (urls.size() < 2) {
            return null;
        }
        int prev = target.bkCandidateIndexMod();
        int next = (prev + 1) % urls.size();
        target.setBkCandidateIndex(next);
        target.setBkLastRotateAt(Channel.nowSec());
        target.setBkPlaylist("");
        target.invalidateBuilt();
        Log.i(TAG, "[" + target.slug() + "] bk 分片 403/404，切换备用 CDN (候选 "
                + next + "/" + urls.size() + ")", 10.0);
        try {
            if (!bkRefresh(target)) {
                return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "[" + target.slug() + "] bk 候选切换后仍失败: " + e.getMessage(), 10.0);
            return null;
        }
        // 轮换后播放列表已换；这里不再猜新分片地址（旧地址未必存在于新 CDN），
        // 让播放器按新的 /<slug>.m3u8 重新取分片即可。
        return null;
    }

    /**
     * 找出"当前 bk 播放列表里确实包含这个分片地址"的频道。
     * <p>先做**精确 URL 子串匹配**（分片地址是直接从该播放列表里取出来的，必然原样存在），
     * 只有在精确匹配不到时才退化到 host 匹配。多个频道会共用同一个 CDN 域名
     * （例如 {@code hlsliveali-cdn.ysp.cctv.cn}），只按 host 会认错频道。
     */
    private Channel channelForBkSegment(String realUrl) {
        if (realUrl == null || realUrl.isEmpty()) {
            return null;
        }
        String segHost = UrlX.hostOf(realUrl);
        Channel hostMatch = null;
        int hostMatches = 0;
        synchronized (channelsLock) {
            for (Channel ch : channels.values()) {
                if (!Channel.MODE_BK.equals(ch.mode())) {
                    continue;
                }
                String pl = ch.bkPlaylist();
                if (pl.isEmpty()) {
                    continue;
                }
                if (pl.contains(realUrl)) {
                    return ch;   // 精确命中，立刻返回
                }
                if (segHost != null && !segHost.isEmpty() && pl.contains(segHost)) {
                    hostMatch = ch;
                    hostMatches++;
                }
            }
        }
        // host 匹配只在唯一时才可信
        return hostMatches == 1 ? hostMatch : null;
    }    /** 停止所有后台线程（尽力而为：置位后循环会自行退出）。 */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "频道引擎正在停止…");
        synchronized (channelsLock) {
            for (Channel ch : channels.values()) {
                Thread t = ch.thread();
                if (t != null) {
                    t.interrupt();
                }
            }
        }
        tsCache.clear();
        Log.i(TAG, "频道引擎已停止");
    }

    private static String normalizeProto(String proto) {
        if (proto == null) {
            return "http";
        }
        String p = proto.trim().toLowerCase(Locale.ROOT);
        return ("https".equals(p)) ? "https" : "http";
    }

    static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
