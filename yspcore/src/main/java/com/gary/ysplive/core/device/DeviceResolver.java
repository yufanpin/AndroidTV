package com.gary.ysplive.core.device;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.net.Http;
import com.gary.ysplive.core.net.HttpResponse;
import com.gary.ysplive.core.spi.ChannelEntry;
import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.ResolutionException;
import com.gary.ysplive.core.spi.Resolver;
import com.gary.ysplive.core.util.AesGcm;
import com.gary.ysplive.core.util.Base64X;
import com.gary.ysplive.core.util.Json;
import com.gary.ysplive.core.util.RsaOaep;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 央视频设备协议解析器。
 * <p>这是整个网关最难也最关键的一块：伪装成"央视频电视投屏助手"，走整套
 * {@code app/start → 云注册 → 心跳 → live/v1/01 → live/v1/02 → vdn/getstream}
 * 链路，拿到 4K/8K 码流的带签名地址。
 *
 * <h3>核心链路</h3>
 * <pre>
 *  1. 设备身份：随机挑一台 8K 电视模板 → 派生 X-Uid / X-Fingerprint（见 {@link Identity}）
 *  2. app/start：拿到 AES-GCM 加密的 session_key（用 X-Fingerprint 前 32 位当密钥解）
 *  3. 云注册：RSA-OAEP 加密 device_id 换 cloud guid
 *  4. 心跳：上报在线状态（后台每 30s 一次）
 *  5. live/v1/01：按 live_id 取直播地址列表，优先挑 rate=="36p" 的那路
 *  6. live/v1/02：拿 appSecret
 *  7. vdn/getstream：用 appSecret 算 APPSIGN，换取最终带签名的 m3u8 地址
 * </pre>
 *
 * <h3>容错设计</h3>
 * <ul>
 *   <li>任何单次失败都只会抛 {@link ResolutionException}，调用方回退 1080p，绝不崩进程</li>
 *   <li>HTTP 400 / 身份失效 → 换一台新"电视"重新注册（5 分钟冷却、每日最多 5 次）</li>
 *   <li>失败冷却：第 1 次 15s、第 2 次 60s、第 3 次起 120s</li>
 *   <li>换台防抖：刚切过去的频道先回 1080p，停留 N 秒后后台预取高码率</li>
 *   <li>链接配额：单设备最多 {@code linksPerDevice} 路并发，空闲 15s 释放</li>
 * </ul>
 * 所有公开方法线程安全。
 */
public final class DeviceResolver implements Resolver {

    private static final String TAG = "device";

    // ---- 运行参数（与 Python 参考实现 engine_args 一致） ----
    private static final double SESSION_TTL_SEC = 7200.0;
    private static final double SESSION_REFRESH_MARGIN_SEC = 300.0;
    private static final double CACHE_TTL_SEC = 60.0;
    private static final double HEARTBEAT_INTERVAL_SEC = 30.0;
    private static final double REFRESH_INTERVAL_SEC = 1.0;
    private static final double LINK_IDLE_TIMEOUT_SEC = 15.0;
    private static final double DEVICE_WIPE_COOLDOWN_SEC = 300.0;
    private static final int DEVICE_WIPE_DAILY_LIMIT = 5;
    private static final int THREAD_JOIN_MS = 1500;

    /** 太早失败冷却（第 1/2/3 次）。 */
    private static final double COOLDOWN_1_SEC = 15.0;
    private static final double COOLDOWN_2_SEC = 60.0;
    private static final double COOLDOWN_3_SEC = 120.0;

    /** 云注册用的 RSA 公钥（SPKI DER 的 base64）。 */
    private static final String RSA_PUBLIC_KEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkKeLy4ywWLSnBkwRyqYgF3HMIj05V5uuh5HjyEsZOWnu1NHu3jPQv3sr32wwQNYv5qapsNXmNgLUDHtgHZxqPQAYXltjSRc0qhcD286t62wOIHId8zXS3s1Jy4rgU4qjQWzI9rp/1sE0pMsmwTaJa4zuJ5iz8VwF8Av5oJ1k+HxY+/HLnjNlW1hmWLpuDYmkZYuAoTHa1VGeHQh9FEKI8ZcL3GTQphShUoC+Kg3P1hGUVTtCYapmzPS5lkAdwebuzwvTCfGiTErYZCnPBUSeV7BVlgjtLYIi29KvF0a8FHsJMfe/UdHcyW/RihsIYOtDQcRRpFGXyPXbVrzFJse24QIDAQAB";

    // ---- 配置 ----
    private final Gateway.Config config;
    private volatile int linksPerDevice;
    private volatile double zapDebounceSec;
    private volatile boolean only4kHighBitrate;
    private volatile File deviceStateFile;
    private volatile int timeoutMs = 10000;

    // ---- 状态（全部由 stateLock 保护） ----
    private final Object stateLock = new Object();
    /** 解析成功的频道缓存。 */
    private final Map<String, ChannelEntry> cache = new LinkedHashMap<>();
    /** 频道最近访问时间（用于链接配额驱逐）。 */
    private final Map<String, Double> channelLastAccess = new HashMap<>();
    /** 频道失败记录：slug → {失败时间, 错误信息, 连续失败次数}。 */
    private final Map<String, Failure> channelFailures = new HashMap<>();
    /** 后台预取进行中的频道 → 开始时间。 */
    private final Map<String, Double> backgroundRefreshing = new HashMap<>();

    private AppSession session;
    private long sessionGeneration;
    private double lastBusinessEndAt;
    private String lastError = "";
    private double lastErrorAt;

    // 换台防抖
    private final Map<String, Double> zapTimes = new HashMap<>();
    private final Set<String> zapPendingChannels = new HashSet<>();
    private final Map<String, Integer> zapGenerations = new HashMap<>();
    private volatile String zapChannel = "";

    // 设备轮换
    private double lastDeviceWipeAt;
    private String deviceWipeDay = "";
    private int deviceWipeCount;

    // ---- 线程 ----
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Thread bootstrapThread;
    private volatile Thread heartbeatThread;
    private volatile Thread refreshThread;
    private volatile Thread telemetryThread;
    private final Object refreshSignal = new Object();
    private final List<String> refreshQueue = new ArrayList<>();
    private volatile boolean refreshWorkerBusy;

    // ---- 统计 ----
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private final AtomicLong resolveOkCount = new AtomicLong();
    private final AtomicLong resolveFailCount = new AtomicLong();

    /** 一次失败记录。 */
    private static final class Failure {
        final double failedAt;
        final String message;
        final int count;

        Failure(double failedAt, String message, int count) {
            this.failedAt = failedAt;
            this.message = message == null ? "" : message;
            this.count = count;
        }
    }

    // ==================================================================
    // 构造
    // ==================================================================

    /**
     * @param config 引擎配置；{@code null} 时使用全部默认值（便于单测与探针）
     */
    public DeviceResolver(Gateway.Config config) {
        this.config = config;
        int links = config == null ? 6 : config.linksPerDevice;
        this.linksPerDevice = Math.max(0, links);
        this.zapDebounceSec = config == null ? 10.0 : Math.max(0.0, config.zapDebounceSec);
        this.only4kHighBitrate = config != null && config.only4kHighBitrate;
        double t = config == null ? 10.0 : config.timeoutSec;
        if (t > 0) {
            this.timeoutMs = (int) Math.max(1000, Math.round(t * 1000));
        }
        if (config != null && config.dataDir != null) {
            this.deviceStateFile = new File(config.dataDir, "device-state.json");
        }
    }

    /** 单设备高码率并发上限；0 表示关闭配额限制。 */
    public void setLinksPerDevice(int n) {
        this.linksPerDevice = Math.max(0, n);
    }

    /** 换台防抖秒数；0 表示关闭（立即尝试高码率）。 */
    public void setZapDebounceSeconds(double sec) {
        this.zapDebounceSec = Math.max(0.0, sec);
    }

    /** true 时只有 4K/8K 频道走设备协议。 */
    public void setOnly4kHighBitrate(boolean value) {
        this.only4kHighBitrate = value;
    }

    /**
     * 设备身份落盘位置；{@code null} 表示纯内存（不写盘）。
     * <p>与 {@link #setDeviceStateFile(File)} 完全等价，保留两个名字是因为
     * 上层 {@code ResolverFactory} 用的是 {@code setDeviceState}。
     */
    public void setDeviceStateFile(File file) {
        this.deviceStateFile = file;
    }

    /** {@link #setDeviceStateFile(File)} 的别名，供 {@code ResolverFactory} 调用。 */
    public void setDeviceState(File file) {
        setDeviceStateFile(file);
    }

    /** HTTP 超时（毫秒）。 */
    public void setTimeoutMs(int ms) {
        this.timeoutMs = Math.max(1000, ms);
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        stopped.set(false);
        Log.i(TAG, "设备协议解析器启动");

        // 预先把设备身份准备好（可能读盘/生成），避免首次 resolve 才慢
        Thread boot = new Thread(() -> {
            // 建会话前先看一眼是否已被 stop()，避免启动竞态下白跑一轮网络请求
            if (stopped.get()) {
                return;
            }
            try {
                ensureSessionQuietly();
            } catch (RuntimeException e) {
                if (!stopped.get()) {
                    Log.w(TAG, "首次会话建立失败（将在首次播放时重试）: " + e.getMessage());
                }
            }
        }, "device-bootstrap");
        boot.setDaemon(true);
        bootstrapThread = boot;
        boot.start();

        heartbeatThread = new Thread(this::heartbeatLoop, "device-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        refreshThread = new Thread(this::refreshLoop, "device-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    @Override
    public void stop() {
        if (!started.get() || !stopped.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "设备协议解析器停止");
        Thread boot = bootstrapThread;
        Thread hb = heartbeatThread;
        Thread rf = refreshThread;
        Thread tm = telemetryThread;
        bootstrapThread = null;
        heartbeatThread = null;
        refreshThread = null;
        telemetryThread = null;

        // 1) 先中断线程（让 sleep/wait/阻塞 IO 立刻返回）
        if (boot != null) {
            boot.interrupt();
        }
        if (hb != null) {
            hb.interrupt();
        }
        if (rf != null) {
            rf.interrupt();
            synchronized (refreshSignal) {
                refreshSignal.notifyAll();
            }
        }
        if (tm != null) {
            tm.interrupt();
        }
        // 2) 关掉所有在飞的 HTTP 客户端，让阻塞中的 socket 读立刻抛异常返回。
        //    只 interrupt 不够：HttpURLConnection 的读不会被中断打断，会一直卡到超时。
        closeAllActiveClients();

        try {
            if (boot != null) {
                boot.join(THREAD_JOIN_MS);
            }
            if (hb != null) {
                hb.join(THREAD_JOIN_MS);
            }
            if (rf != null) {
                rf.join(THREAD_JOIN_MS);
            }
            if (tm != null) {
                tm.join(THREAD_JOIN_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (stateLock) {
            session = null;
        }
        started.set(false);
    }

    /** 在飞的 HTTP 客户端集合；stop() 时全部关掉以解除阻塞。 */
    private final Set<Http> activeClients = Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Http, Boolean>());

    private Http newClient() {
        Http http = new Http(timeoutMs);
        activeClients.add(http);
        return http;
    }

    private void releaseClient(Http http) {
        if (http != null) {
            activeClients.remove(http);
            closeQuietly(http);
        }
    }

    private void closeAllActiveClients() {
        List<Http> snapshot = new ArrayList<>(activeClients);
        for (Http h : snapshot) {
            try {
                h.close();
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
        activeClients.clear();
    }

    @Override
    public Status status() {
        AppSession s;
        boolean valid;
        String guid;
        String err;
        int activeSlots;
        synchronized (stateLock) {
            s = session;
            valid = s != null && sessionFresh(s);
            guid = s == null ? "" : s.cloudGuid;
            err = lastError;
            activeSlots = s == null ? 0 : 1;
        }
        return new Status(started.get() && valid, valid, activeSlots,
                heartbeatCount.get(), guid, err);
    }

    // ==================================================================
    // 主入口：resolve
    // ==================================================================

    @Override
    public ChannelEntry resolve(String slug) throws ResolutionException {
        String channel = ChannelTable.canonical(slug);
        if (channel == null || channel.isEmpty()) {
            throw new ResolutionException("频道名称为空", false);
        }
        ChannelSpec spec = ChannelTable.lookup(channel);
        if (spec == null) {
            throw new ResolutionException("未知频道: " + slug, false);
        }
        // 非后端频道（卫视等）本来就没有设备协议码流
        if (!ChannelTable.isBackendChannel(channel)) {
            throw new ResolutionException("频道 " + channel + " 不走高码率设备协议", false);
        }
        if (only4kHighBitrate && !ChannelTable.isTrue4k(channel)) {
            throw new ResolutionException("仅 4K 模式，跳过 " + channel, false);
        }

        double now = nowSec();
        synchronized (stateLock) {
            channelLastAccess.put(channel, now);
        }

        // 1) 命新鲜缓存直接返回
        ChannelEntry cached = freshCached(channel, now);
        if (cached != null) {
            return cached;
        }

        // 2) 冷却中就别再撞了
        double cooldown = cooldownRemaining(channel, now);
        if (cooldown > 0) {
            Failure f = failureOf(channel);
            throw new ResolutionException("频道 " + channel + " 冷却中 (剩余 "
                    + (int) Math.ceil(cooldown) + "s): " + (f == null ? "" : f.message), true);
        }

        // 3) 换台防抖：先让调用方用 1080p，后台并行预取
        if (shouldDebounce(channel, now)) {
            scheduleZapPrefetch(channel);
            throw new ResolutionException("频道 " + channel + " 换台防抖中，先后出 1080p", true);
        }

        // 4) 真正解析
        try {
            ChannelEntry entry = resolveNow(channel, spec, now);
            resolveOkCount.incrementAndGet();
            return entry;
        } catch (ResolutionException e) {
            recordFailure(channel, e.getMessage());
            resolveFailCount.incrementAndGet();
            // HTTP 400 一类身份失效 → 换设备后重试一次
            if (isIdentityError(e.getMessage()) && rotateDeviceOn400(channel + ":" + abbrev(e.getMessage(), 60))) {
                try {
                    ChannelSpec again = ChannelTable.lookup(channel);
                    ChannelEntry entry = resolveNow(channel, again, nowSec());
                    clearChannelCooldown(channel);
                    synchronized (stateLock) {
                        channelFailures.remove(channel);
                    }
                    resolveOkCount.incrementAndGet();
                    return entry;
                } catch (ResolutionException retryErr) {
                    recordFailure(channel, retryErr.getMessage());
                    throw retryErr;
                }
            }
            throw e;
        }
    }

    /** 走完整设备协议链解析一次并写缓存。 */
    private ChannelEntry resolveNow(String channel, ChannelSpec spec, double now) throws ResolutionException {
        AppSession sess = ensureSession();
        // 链接配额
        enforceLinkQuota(sess, channel);
        // 串行化 + 控制面限速
        synchronized (controlLock) {
            waitBusinessGap();
            ChannelEntry entry = resolveChannelOnce(sess, channel, spec, now);
            synchronized (stateLock) {
                cache.put(channel, entry);
                lastError = "";
                lastErrorAt = 0;
                channelFailures.remove(channel);
            }
            String label = rateLabel(channel, entry);
            Log.i(TAG, "[" + channel + "] 直播源获取成功 (清晰度: " + label + ")", 10.0);
            return entry;
        }
    }

    private final Object controlLock = new Object();

    /**
     * 三接口连打：live/v1/01 → live/v1/02 → vdn/getstream。
     * <p>对应 Python {@code resolve_channel_once}。
     * <p><b>注意</b>：{@code live/v1/01} 的 {@code id} 要的是 Live ID（{@link LiveIds}），
     * 不是 {@code ChannelSpec.sid}。
     */
    private ChannelEntry resolveChannelOnce(AppSession sess, String channel, ChannelSpec spec,
                                            double now) throws ResolutionException {
        String liveId = LiveIds.forSlug(channel);
        if (liveId.isEmpty()) {
            throw new ResolutionException("频道 " + channel + " 没有 Live ID, 无法走设备协议", false);
        }
        Live01Result live01 = liveV101Flow(sess, liveId);
        String appSecret = liveV102Flow(sess);
        VdnResult vdn = vdnGetStreamFlow(sess.identity, sess.version, live01.liveUrl, appSecret);

        Map<String, String> playbackHeaders = Protocol.defaultCctvPlaybackHeaders(sess.profile.androidId);
        playbackHeaders.put("APPRANDOMSTR", vdn.appRandomStr);
        playbackHeaders.put("APPSIGN", vdn.appSign);

        sess.linkedChannels.add(channel);

        String rateName = live01.rateName.isEmpty() ? "高码率" : live01.rateName;
        if (ChannelTable.isTrue4k(channel)) {
            if (rateName.contains("8K") || "8K高清".equals(rateName) || "8K超高清".equals(rateName)) {
                rateName = "4K超高清";
            }
        } else if ("8K高清".equals(rateName) || "4K高清".equals(rateName)
                || "8K超高清".equals(rateName) || "4K超高清".equals(rateName)) {
            rateName = "高码率";
        }

        return new ChannelEntry(
                channel,
                liveId,
                vdn.finalUrl,
                playbackHeaders,
                sess.profile.androidId,
                sess.identity.xUid,
                live01.rate,
                rateName,
                Protocol.urlHost(vdn.finalUrl),
                nowToMs(now),
                nowToMs(now + CACHE_TTL_SEC),
                sess.generation);
    }

    // ==================================================================
    // 会话
    // ==================================================================

    /** 一次"电视"会话：设备身份 + 客户端 + 会话密钥 + 云 guid。 */
    private static final class AppSession {
        final DeviceProfile profile;
        final Identity.IdentityData identity;
        final Http client;
        final String sessionKey;
        final String cloudGuid;
        final String version;
        final String screenParam;
        final String castModel;
        final double createdAt;
        final long generation;
        final Set<String> linkedChannels = Collections.synchronizedSet(new LinkedHashSet<String>());
        double lastHeartbeatAt;
        int heartbeatCount = 1;
        String lastHeartbeatResult = "";
        String lastHeartbeatError = "";

        AppSession(DeviceProfile profile, Identity.IdentityData identity, Http client,
                   String sessionKey, String cloudGuid, String version, String screenParam,
                   String castModel, double createdAt, long generation) {
            this.profile = profile;
            this.identity = identity;
            this.client = client;
            this.sessionKey = sessionKey;
            this.cloudGuid = cloudGuid;
            this.version = version;
            this.screenParam = screenParam;
            this.castModel = castModel;
            this.createdAt = createdAt;
            this.generation = generation;
            this.lastHeartbeatAt = createdAt;
        }
    }

    private boolean sessionFresh(AppSession s) {
        return s != null && (nowSec() - s.createdAt) < SESSION_TTL_SEC;
    }

    private double sessionTtlRemaining(AppSession s) {
        return SESSION_TTL_SEC - (nowSec() - s.createdAt);
    }

    /** 拿一个可用会话；需要时重新 bootstrap。 */
    private AppSession ensureSession() throws ResolutionException {
        AppSession s;
        synchronized (stateLock) {
            s = session;
            if (s != null && sessionFresh(s)) {
                double rem = sessionTtlRemaining(s);
                if (rem > SESSION_REFRESH_MARGIN_SEC) {
                    return s;
                }
            }
        }
        synchronized (controlLock) {
            synchronized (stateLock) {
                s = session;
                if (s != null && sessionFresh(s)) {
                    double rem = sessionTtlRemaining(s);
                    if (rem > SESSION_REFRESH_MARGIN_SEC) {
                        return s;
                    }
                }
                sessionGeneration++;
            }
            try {
                s = bootstrapSession(sessionGeneration);
            } catch (Exception e) {
                String msg = "建立设备会话失败: " + e.getMessage();
                synchronized (stateLock) {
                    lastError = msg;
                    lastErrorAt = nowSec();
                }
                throw new ResolutionException(msg, true);
            }
            synchronized (stateLock) {
                session = s;
                lastError = "";
            }
            return s;
        }
    }

    /** 尽力建立会话，不抛异常（供 start() 预热）。 */
    private void ensureSessionQuietly() {
        try {
            ensureSession();
        } catch (ResolutionException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    // ==================================================================
    // bootstrap：app/start → 云注册 → 心跳
    // ==================================================================

    private AppSession bootstrapSession(long generation) throws Exception {
        DeviceState state = loadOrCreateDeviceState();
        DeviceProfile profile = state.profile;
        Identity.IdentityData identity = Identity.buildIdentity(profile, Protocol.APP_CHANNEL,
                Protocol.APP_VERSION);

        Http client = newClient();
        boolean keepClient = false;
        try {
            AppSession sess = bootstrapWithClient(state, profile, identity, client, generation);
            keepClient = true;
            return sess;
        } finally {
            // 会话建失败就别把这个 client 留在 activeClients 里（会泄漏到 stop()）
            if (!keepClient) {
                releaseClient(client);
            }
        }
    }

    /** 用给定 client 走完 bootstrap 的三个接口并组出会话。 */
    private AppSession bootstrapWithClient(DeviceState state, DeviceProfile profile,
                                           Identity.IdentityData identity, Http client,
                                           long generation) throws Exception {
        String sessionKey = appStartFlow(client, profile, identity);
        String cloudGuid = cloudRegistrationFlow(identity);
        if (cloudGuid.isEmpty() && state.cloudGuid != null && !state.cloudGuid.isEmpty()) {
            cloudGuid = state.cloudGuid;
        }
        if (cloudGuid.isEmpty()) {
            throw new IOException("云注册失败：未拿到 guid");
        }

        double now = nowSec();
        state.xUid = identity.xUid;
        state.cloudGuid = cloudGuid;
        state.updatedAt = now;
        if (state.registeredAt <= 0) {
            state.registeredAt = now;
        }
        saveDeviceState(state);

        String hbResult;
        try {
            hbResult = heartbeatFlow(profile, identity, cloudGuid);
        } catch (Exception e) {
            hbResult = "";
            Log.w(TAG, "首次心跳失败（会话仍可用）: " + e.getMessage());
        }
        heartbeatCount.incrementAndGet();
        Log.i(TAG, "设备会话就绪: " + profile.brand + " " + profile.model
                + " (" + state.profileSource + "), guid=" + abbrev(cloudGuid, 12));

        // 后台补埋点/字典/索引，失败无所谓。
        // 这些请求纯属"刷活跃度"，少一个完全不影响拿流，所以每步之前都要看 stopped 标志，
        // 否则 stop() 之后线程还在偷偷发网络请求。
        final Http bgClient = client;
        final Identity.IdentityData bgIdentity = identity;
        final String bgGuid = cloudGuid;
        startTelemetry(profile, bgIdentity, bgClient, bgGuid);

        AppSession sess = new AppSession(profile, identity, client, sessionKey, cloudGuid,
                Protocol.APP_VERSION, state.screenParam, state.castModel, nowSec(), generation);
        sess.lastHeartbeatResult = hbResult;
        return sess;
    }

    private Map<String, Object> appStartReportBody(DeviceProfile profile, String xUid, String guid) {
        return Protocol.buildAppStartBody(profile, xUid, Protocol.APP_CHANNEL,
                Protocol.APP_VERSION, Identity.currentTimeMs());
    }

    /**
     * 起一条后台埋点线程（失败完全无所谓，只是"刷活跃度"）。
     * <p>线程被 {@link #telemetryThread} 跟踪，{@link #stop()} 能中断并 join 它；
     * 每个子步骤之前都检查 {@code stopped}，保证 stop 之后不会再多发一个请求。
     * <p>如果上一条还没退干净就不重复起（400 轮换会反复 bootstrap，避免线程堆积）。
     */
    private void startTelemetry(final DeviceProfile profile, final Identity.IdentityData identity,
                                final Http client, final String cloudGuid) {
        Thread prev = telemetryThread;
        if (prev != null && prev.isAlive()) {
            return;
        }
        if (stopped.get()) {
            return;
        }
        Thread t = new Thread(() -> runTelemetry(profile, identity, client, cloudGuid),
                "device-telemetry");
        t.setDaemon(true);
        telemetryThread = t;
        t.start();
    }

    /** 依次打 5 个"可有可无"的接口；任何一步失败或被 stop 都直接收工。 */
    private void runTelemetry(DeviceProfile profile, Identity.IdentityData identity,
                              Http client, String cloudGuid) {
        try {
            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                collectReport(profile, appStartReportBody(profile, identity.xUid, cloudGuid));
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // 埋点失败不影响业务
            }

            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                dictionaryObtain(identity, Protocol.APP_VERSION);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // 忽略
            }

            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                deviceInfoReportFlow(profile, identity, cloudGuid);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // 忽略
            }

            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                indexFlow(client, identity);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
                // 忽略
            }

            if (stopped.get() || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                warmupFlow(client, identity, Protocol.APP_VERSION);
            } catch (InterruptedException e) {
                // 收工
            } catch (Exception ignored) {
                // 忽略
            }
        } finally {
            if (telemetryThread == Thread.currentThread()) {
                telemetryThread = null;
            }
        }
    }

    /**
     * {@code app/start}：拿 AES-GCM 加密的 session_key。
     * <p>用 X-Fingerprint 的前 32 个字符当 AES-256 密钥解。
     */
    private String appStartFlow(Http client, DeviceProfile profile, Identity.IdentityData identity)
            throws Exception {
        String last = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt > 1) {
                sleepMs(1000L * (attempt - 1));
            }
            long tokenTime = Identity.currentTimeMs();
            Identity.Fingerprint fp = Identity.computeFingerprint(identity.xUid, tokenTime);
            identity.xFingerprint = fp.value;
            identity.fingerprintTimestampMs = fp.timestampMs;
            identity.headers.put("X-Fingerprint", fp.value);

            Map<String, Object> body = Protocol.buildAppStartBody(profile, identity.xUid,
                    Protocol.APP_CHANNEL, Protocol.APP_VERSION, Identity.currentTimeMs());
            Map<String, String> headers = Identity.freshHeaders(identity.headers,
                    "application/json", "application/json", fp.timestampMs);
            headers.put("UID", "");

            HttpResponse resp;
            try {
                resp = client.postJson(Protocol.APP_START_URL, headers, body);
            } catch (IOException e) {
                last = "app/start 网络错误: " + e.getMessage();
                continue;
            }
            String text = resp.text();
            Object value = Json.tryParse(text);
            if (resp.status >= 200 && resp.status < 300) {
                String encrypted = extractEncryptedSessionKey(value);
                if (!encrypted.isEmpty()) {
                    String sessionKey = AesGcm.decryptB64(encrypted, fp.value.substring(0, 32));
                    if (sessionKey == null || sessionKey.isEmpty()) {
                        throw new IOException("app/start 解出的 session_key 为空");
                    }
                    Log.d(TAG, "app/start 成功，session_key 已解密");
                    return sessionKey;
                }
            }
            last = "app/start HTTP " + resp.status + ": " + abbrev(text, 300);
        }
        throw new IOException(last);
    }

    /** 从 app/start 响应里挖出加密的 session_key 串。 */
    private static String extractEncryptedSessionKey(Object value) {
        if (!(value instanceof Map)) {
            return "";
        }
        Object data = ((Map<?, ?>) value).get("data");
        if (data instanceof Map) {
            Object keyVal = ((Map<?, ?>) data).get("key");
            if (keyVal == null) {
                // 没有 key 字段时，data 本身可能就是字符串值
                return "";
            }
            return keyVal instanceof String ? (String) keyVal : "";
        }
        return data instanceof String ? (String) data : "";
    }

    /**
     * 云注册：RSA-OAEP 加密 device_id，先 get 再按需 register。
     * <p>对应 Python {@code cloud_registration_flow}，返回 cloud guid。
     */
    private String cloudRegistrationFlow(Identity.IdentityData identity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_name", Identity.DEFAULT_DEVICE_NAME);
        body.put("device_id", rsaEncryptDeviceId(identity.xUid));

        Http http = newClient();
        try {
            HttpResponse resp = http.postJson(Protocol.CLOUD_GET_URL,
                    Identity.freshHeaders(identity.headers, "application/json", "application/json", null),
                    body);
            Object value = Json.tryParse(resp.text());
            Integer result = Protocol.parseResultCode(value);
            if (result != null && result == Protocol.RESULT_OK) {
                String guid = Protocol.extractGuid(value);
                if (!guid.isEmpty()) {
                    return guid;
                }
            }
            if (result != null && result != Protocol.RESULT_NEEDS_REGISTER
                    && result != Protocol.RESULT_GET_MISSING_OR_INVALID) {
                Log.w(TAG, "云注册 get 返回非预期码: " + result);
                return "";
            }

            Integer lastResult = null;
            String guid = "";
            for (int i = 0; i < 2; i++) {
                HttpResponse regResp = http.postJson(Protocol.CLOUD_REGISTER_URL,
                        Identity.freshHeaders(identity.headers, "application/json",
                                "application/json", null), body);
                Object regValue = Json.tryParse(regResp.text());
                lastResult = Protocol.parseResultCode(regValue);
                guid = Protocol.extractGuid(regValue);
                if (!guid.isEmpty() || lastResult == null
                        || lastResult != Protocol.RESULT_REGISTER_RETRY_LATER) {
                    break;
                }
                sleepMs(500L);
            }
            if (!guid.isEmpty()) {
                return guid;
            }
            if (lastResult != null && (lastResult == Protocol.RESULT_OK
                    || lastResult == Protocol.RESULT_REGISTERED_ELSEWHERE
                    || lastResult == Protocol.RESULT_GET_MISSING_OR_INVALID)) {
                HttpResponse again = http.postJson(Protocol.CLOUD_GET_URL,
                        Identity.freshHeaders(identity.headers, "application/json",
                                "application/json", null), body);
                return Protocol.extractGuid(Json.tryParse(again.text()));
            }
            return "";
        } finally {
            releaseClient(http);
        }
    }

    /**
     * 用 RSA-OAEP-SHA256 分段加密 device_id（对应 Python {@code rsa_encrypt_device_id}）。
     * <p>2048 位密钥单块最多 {@code 256-2*32-2=190} 字节明文。
     */
    static String rsaEncryptDeviceId(String deviceId) throws Exception {
        byte[] der = Base64X.decode(RSA_PUBLIC_KEY_B64);
        int k = 256;                                  // 2048 bit
        int hlen = 32;
        int chunkSize = k - 2 * hlen - 2;             // 190
        byte[] data = deviceId.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += chunkSize) {
            int len = Math.min(chunkSize, data.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(data, off, chunk, 0, len);
            byte[] enc = RsaOaep.encrypt(der, chunk);
            out.write(enc, 0, enc.length);
        }
        return Base64X.encode(out.toByteArray());
    }

    /** 心跳上报（对应 Python {@code heartbeat_flow}）。返回结果码文本。 */
    private String heartbeatFlow(DeviceProfile profile, Identity.IdentityData identity,
                                 String cloudGuid) throws Exception {
        Map<String, Object> body = Protocol.buildHeartbeatBody(profile, identity.xUid,
                Protocol.APP_CHANNEL, Protocol.APP_VERSION, cloudGuid, Identity.currentTimeMs());
        Http http = newClient();
        try {
            HttpResponse resp = http.postJson(Protocol.REPORT_SINGLE_URL,
                    Identity.freshHeaders(identity.headers, "application/json", "application/json", null),
                    body);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("heartbeat HTTP " + resp.status);
            }
            Integer code = Protocol.parseResultCode(Json.tryParse(resp.text()));
            return code == null ? "" : String.valueOf(code);
        } finally {
            releaseClient(http);
        }
    }

    // ---- 预热类接口（失败无所谓） ----

    private void collectReport(DeviceProfile profile, Map<String, Object> body) throws Exception {
        Http http = newClient();
        try {
            List<String[]> pairs = new ArrayList<>();
            pairs.add(new String[] {"info", Json.stringify(body)});
            HttpResponse resp = http.postForm(Protocol.COLLECT_REPORT_URL,
                    Identity.buildCollectHeaders(profile), pairs);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("collect report HTTP " + resp.status);
            }
        } finally {
            releaseClient(http);
        }
    }

    private void dictionaryObtain(Identity.IdentityData identity, String version) throws Exception {
        Http http = newClient();
        try {
            HttpResponse resp = http.postBytes(Protocol.DICTIONARY_URL,
                    Identity.rootHeaders(version), new byte[0]);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("dictionary HTTP " + resp.status);
            }
        } finally {
            releaseClient(http);
        }
    }

    private void deviceInfoReportFlow(DeviceProfile profile, Identity.IdentityData identity,
                                     String cloudGuid) throws Exception {
        Map<String, Object> body = Protocol.buildDeviceInfoBody(profile, identity.xUid,
                Protocol.APP_CHANNEL, Protocol.APP_VERSION, cloudGuid, Identity.currentTimeMs());
        Http http = newClient();
        try {
            HttpResponse resp = http.postJson(Protocol.REPORT_SINGLE_URL,
                    Identity.freshHeaders(identity.headers, "application/json", "application/json", null),
                    body);
            if (resp.status < 200 || resp.status >= 300) {
                throw new IOException("device info HTTP " + resp.status);
            }
        } finally {
            releaseClient(http);
        }
    }

    private void indexFlow(Http client, Identity.IdentityData identity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", Protocol.APP_CHANNEL);
        body.put("source", "application");
        HttpResponse resp = client.postJson(Protocol.INDEX_URL,
                Identity.freshHeaders(identity.headers, "application/json", "application/json", null),
                body);
        if (resp.status < 200 || resp.status >= 300) {
            throw new IOException("index HTTP " + resp.status);
        }
    }

    private void warmupFlow(Http client, Identity.IdentityData identity, String version) throws Exception {
        String appcommon = Protocol.buildVdnAppcommon(version);
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"appcommon", appcommon});
        HttpResponse drm = client.postForm(Protocol.DRM_CONFIG_URL,
                Identity.freshHeaders(identity.headers, "application/x-www-form-urlencoded", null, null),
                pairs);
        if (drm.status < 200 || drm.status >= 300) {
            throw new IOException("drm config HTTP " + drm.status);
        }
        String url = Protocol.VERSION_CONFIG_URL + "?appcommon=" + Http.formEncodeValue(appcommon);
        Map<String, String> headers = Identity.freshHeaders(identity.headers, "", null, null);
        headers.remove("Content-Type");
        HttpResponse ver = client.get(url, headers);
        if (ver.status < 200 || ver.status >= 300) {
            throw new IOException("version config HTTP " + ver.status);
        }
    }

    // ==================================================================
    // live/v1/01
    // ==================================================================

    private static final class Live01Result {
        final String liveUrl;
        final String rate;
        final String rateName;

        Live01Result(String liveUrl, String rate, String rateName) {
            this.liveUrl = liveUrl;
            this.rate = rate;
            this.rateName = rateName;
        }
    }

    /**
     * {@code live/v1/01}：取该 live_id 的码率列表，优先挑 {@code rate == "36p"}，否则第一路。
     * <p>返回的 url 可能是 AES-GCM 密文（用 session_key 解），也可能是明文 http 地址。
     */
    private Live01Result liveV101Flow(AppSession sess, String liveId) throws ResolutionException {
        Map<String, Object> body = Protocol.buildLive01Body(sess.screenParam, sess.castModel, liveId);
        HttpResponse resp;
        try {
            resp = sess.client.postJson(Protocol.LIVE_V1_01_URL,
                    Identity.freshHeaders(sess.identity.headers, "application/json",
                            "application/json", null), body);
        } catch (IOException e) {
            throw new ResolutionException("live/v1/01 网络错误: " + e.getMessage(), true);
        }
        String text = resp.text();
        if (resp.status < 200 || resp.status >= 300) {
            throw new ResolutionException("live/v1/01 HTTP " + resp.status + ": " + abbrev(text, 300), true);
        }
        Object value = Json.tryParse(text);
        List<Object> videos = null;
        if (value instanceof Map) {
            Object data = ((Map<?, ?>) value).get("data");
            if (data instanceof Map) {
                Object v = ((Map<?, ?>) data).get("videoList");
                if (v == null) {
                    v = ((Map<?, ?>) data).get("videos");
                }
                if (v instanceof List) {
                    videos = Json.asArray(v);
                }
            }
        }
        if (videos == null) {
            throw new ResolutionException("live/v1/01 响应缺少 videos", true);
        }

        Map<String, Object> selected = null;
        Map<String, Object> fallback = null;
        for (Object item : videos) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> im = Json.asObject(item);
            Object url = im.get("url");
            if (!(url instanceof String) || ((String) url).isEmpty()) {
                continue;
            }
            if (fallback == null) {
                fallback = im;
            }
            if ("36p".equals(asString(im.get("rate")))) {
                selected = im;
                break;
            }
        }
        Map<String, Object> video = selected != null ? selected : fallback;
        if (video == null) {
            throw new ResolutionException("live/v1/01 没有可用地址", true);
        }
        String rawUrl = asString(video.get("url"));
        String liveUrl;
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            liveUrl = rawUrl;
        } else {
            try {
                liveUrl = AesGcm.decryptB64(rawUrl, sess.sessionKey);
            } catch (RuntimeException e) {
                throw new ResolutionException("live/v1/01 地址解密失败: " + e.getMessage(), true);
            }
            if (liveUrl == null || liveUrl.isEmpty()) {
                throw new ResolutionException("live/v1/01 解出的地址为空", true);
            }
        }
        return new Live01Result(liveUrl, asString(video.get("rate")), asString(video.get("rateName")));
    }

    // ==================================================================
    // live/v1/02
    // ==================================================================

    /** {@code live/v1/02}：拿 AES-GCM 加密的 appSecret，用 session_key 解。 */
    private String liveV102Flow(AppSession sess) throws ResolutionException {
        String encryptedGuid;
        try {
            encryptedGuid = AesGcm.encryptB64("", sess.sessionKey);
        } catch (RuntimeException e) {
            throw new ResolutionException("live/v1/02 加密 guid 失败: " + e.getMessage(), true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("guid", encryptedGuid);

        HttpResponse resp;
        try {
            resp = sess.client.postJson(Protocol.LIVE_V1_02_URL,
                    Identity.freshHeaders(sess.identity.headers, "application/json",
                            "application/json", null), body);
        } catch (IOException e) {
            throw new ResolutionException("live/v1/02 网络错误: " + e.getMessage(), true);
        }
        String text = resp.text();
        if (resp.status < 200 || resp.status >= 300) {
            throw new ResolutionException("live/v1/02 HTTP " + resp.status + ": " + abbrev(text, 300), true);
        }
        Object value = Json.tryParse(text);
        String encrypted = null;
        if (value instanceof Map) {
            Object data = ((Map<?, ?>) value).get("data");
            if (data instanceof Map) {
                for (String key : new String[] {"appSecret", "app_secret"}) {
                    Object v = ((Map<?, ?>) data).get(key);
                    if (v instanceof String) {
                        encrypted = (String) v;
                        break;
                    }
                }
            }
            if (encrypted == null && data instanceof String) {
                encrypted = (String) data;
            }
        }
        if (encrypted == null) {
            throw new ResolutionException("live/v1/02 缺少 appSecret", true);
        }
        try {
            String secret = AesGcm.decryptB64(encrypted, sess.sessionKey);
            if (secret == null || secret.isEmpty()) {
                throw new ResolutionException("live/v1/02 解出的 appSecret 为空", true);
            }
            return secret;
        } catch (RuntimeException e) {
            throw new ResolutionException("live/v1/02 appSecret 解密失败: " + e.getMessage(), true);
        }
    }

    // ==================================================================
    // vdn/getstream
    // ==================================================================

    private static final class VdnResult {
        final String finalUrl;
        final String appSign;
        final String appRandomStr;

        VdnResult(String finalUrl, String appSign, String appRandomStr) {
            this.finalUrl = finalUrl;
            this.appSign = appSign;
            this.appRandomStr = appRandomStr;
        }
    }

    /**
     * {@code vdn/getstream}：用 appSecret 算 APPSIGN，换最终带签名 m3u8 地址。
     * <p>表单提交 {@code appcommon} + {@code url}，头里带 {@code APPID/APPSIGN/APPRANDOMSTR}。
     */
    private VdnResult vdnGetStreamFlow(Identity.IdentityData identity, String version,
                                       String liveUrl, String appSecret) throws ResolutionException {
        Protocol.VdnCode code = Protocol.computeVdnCode(appSecret);
        Map<String, String> headers = Identity.freshHeaders(identity.headers,
                "application/x-www-form-urlencoded", null, null);
        headers.put("APPID", Identity.AK);
        headers.put("APPSIGN", code.sign);
        headers.put("APPRANDOMSTR", code.randomStr);

        String appcommon = Protocol.buildVdnAppcommon(version);
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[] {"appcommon", appcommon});
        pairs.add(new String[] {"url", liveUrl});

        HttpResponse resp;
        Http http = newClient();
        try {
            resp = http.postForm(Protocol.VDN_GETSTREAM_URL, headers, pairs);
        } catch (IOException e) {
            throw new ResolutionException("VDN 网络错误: " + e.getMessage(), true);
        } finally {
            releaseClient(http);
        }
        String text = resp.text();
        if (resp.status < 200 || resp.status >= 300) {
            throw new ResolutionException("VDN HTTP " + resp.status + ": " + abbrev(text, 300), true);
        }
        Object value = Json.tryParse(text);
        if (value instanceof Map) {
            Map<String, Object> m = Json.asObject(value);
            String succeed = succeedText(m.get("succeed"));
            if ("1".equals(succeed)) {
                Object url = m.get("url");
                if (url instanceof String && !((String) url).isEmpty()) {
                    return new VdnResult((String) url, code.sign, code.randomStr);
                }
            }
        }
        throw new ResolutionException("VDN 未返回最终地址: " + abbrev(text, 400), true);
    }

    /** 把 {@code succeed} 字段归一成字符串（可能是 1 / "1" / true）。 */
    private static String succeedText(Object succeed) {
        if (succeed == null) {
            return "";
        }
        if (succeed instanceof Boolean) {
            return ((Boolean) succeed) ? "1" : "0";
        }
        String s = String.valueOf(succeed).trim();
        while (s.length() > 1 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    // ==================================================================
    // 后台线程
    // ==================================================================

    /** 心跳循环：每 30s 上报一次，顺带检查会话是否需要续期。 */
    private void heartbeatLoop() {
        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep((long) (HEARTBEAT_INTERVAL_SEC * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopped.get()) {
                return;
            }
            try {
                sendHeartbeat();
            } catch (RuntimeException e) {
                Log.d(TAG, "心跳异常: " + e.getMessage());
            }
        }
    }

    private void sendHeartbeat() {
        AppSession s;
        synchronized (stateLock) {
            s = session;
        }
        if (s == null) {
            return;
        }
        evictIdleLinks(s);
        if (!sessionFresh(s) || sessionTtlRemaining(s) <= SESSION_REFRESH_MARGIN_SEC) {
            try {
                ensureSession();
            } catch (ResolutionException e) {
                Log.w(TAG, "会话续期失败: " + e.getMessage());
            }
            return;
        }
        synchronized (controlLock) {
            waitBusinessGap();
            try {
                String code = heartbeatFlow(s.profile, s.identity, s.cloudGuid);
                synchronized (stateLock) {
                    lastBusinessEndAt = nowSec();
                    s.lastHeartbeatAt = nowSec();
                    s.heartbeatCount++;
                    s.lastHeartbeatResult = code;
                    s.lastHeartbeatError = "";
                }
                heartbeatCount.incrementAndGet();
            } catch (Exception e) {
                synchronized (stateLock) {
                    s.lastHeartbeatError = String.valueOf(e.getMessage());
                    lastError = "心跳失败: " + e.getMessage();
                    lastErrorAt = nowSec();
                }
                Log.d(TAG, "心跳失败: " + e.getMessage());
            }
        }
    }

    /** 后台预取队列消费循环。 */
    private void refreshLoop() {
        while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
            String channel;
            synchronized (refreshSignal) {
                while (refreshQueue.isEmpty() && !stopped.get()) {
                    try {
                        refreshSignal.wait(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (stopped.get()) {
                    return;
                }
                channel = refreshQueue.remove(0);
            }
            refreshWorkerBusy = true;
            try {
                resolve(channel);
                Log.i(TAG, "[" + channel + "] 高码率后台预取完成, 继续停留将自动升级", 10.0);
            } catch (Exception e) {
                Log.i(TAG, "[" + channel + "] 高码率后台预取失败: " + e.getMessage()
                        + ", 继续 1080p", 10.0);
            } finally {
                refreshWorkerBusy = false;
                synchronized (stateLock) {
                    backgroundRefreshing.remove(channel);
                }
            }
        }
    }

    private void scheduleRefresh(String channel) {
        synchronized (refreshSignal) {
            if (refreshQueue.contains(channel) || refreshQueue.size() >= 4) {
                return;
            }
            refreshQueue.add(channel);
            refreshSignal.notifyAll();
        }
    }

    // ==================================================================
    // 换台防抖
    // ==================================================================

    /** 是否应该"先回 1080p"。对应 Python {@code zap_debounced}。 */
    private boolean shouldDebounce(String channel, double now) {
        if (ChannelTable.isTrue4k(channel)) {
            return false;                      // 4K 频道不做防抖，直接干
        }
        double debounce = zapDebounceSec;
        if (debounce <= 0) {
            return false;
        }
        synchronized (stateLock) {
            ChannelEntry entry = cache.get(channel);
            if (entry != null && entry.fresh(nowToMs(now))
                    && !Protocol.entryMissingSignedPlaybackHeaders(entry)) {
                return false;
            }
            AppSession sess = session;
            if (sess != null && sess.linkedChannels.contains(channel) && entry != null
                    && (now - toSec(entry.expiresAtMs)) < 30.0) {
                return false;
            }
            zapChannel = channel;
            if (zapPendingChannels.contains(channel)) {
                return true;
            }
            Double chZap = zapTimes.get(channel);
            double chZapTime = chZap == null ? 0.0 : chZap;
            if (chZapTime <= 0.0 || (now - chZapTime > Math.max(debounce * 3.0, 30.0))) {
                chZapTime = now;
                zapTimes.put(channel, now);
            }
            return now - chZapTime < debounce;
        }
    }

    /** 安排一次防抖到期后的后台预取。 */
    private void scheduleZapPrefetch(String channel) {
        int gen;
        double delay;
        synchronized (stateLock) {
            if (zapPendingChannels.contains(channel)) {
                return;
            }
            zapPendingChannels.add(channel);
            gen = zapGenerations.containsKey(channel) ? zapGenerations.get(channel) + 1 : 1;
            zapGenerations.put(channel, gen);
            Double t = zapTimes.get(channel);
            double chZapTime = t == null ? nowSec() : t;
            delay = Math.max(0.1, zapDebounceSec - (nowSec() - chZapTime));
        }
        final int finalGen = gen;
        Thread worker = new Thread(() -> zapResolveWorker(channel, finalGen, delay), "device-zap");
        worker.setDaemon(true);
        worker.start();
    }

    private void zapResolveWorker(String channel, int gen, double delay) {
        try {
            Thread.sleep((long) (delay * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (stateLock) {
            Integer cur = zapGenerations.get(channel);
            if (cur == null || cur != gen) {
                zapPendingChannels.remove(channel);
                return;
            }
            Double acc = channelLastAccess.get(channel);
            double lastAcc = acc == null ? 0.0 : acc;
            double now = nowSec();
            if ((now - lastAcc > Math.max(delay + 2.0, 12.0))
                    || (!channel.equals(zapChannel) && now - lastAcc > 5.0)) {
                zapPendingChannels.remove(channel);
                zapTimes.remove(channel);
                return;
            }
        }
        try {
            resolve(channel);
            Log.i(TAG, "[" + channel + "] 高码率后台预取完成, 继续停留将自动升级", 10.0);
        } catch (Exception e) {
            Log.i(TAG, "[" + channel + "] 高码率后台预取失败: " + e.getMessage() + ", 继续 1080p", 10.0);
        } finally {
            synchronized (stateLock) {
                zapPendingChannels.remove(channel);
            }
        }
    }

    // ==================================================================
    // 链接配额
    // ==================================================================

    /** 超出配额时先驱逐空闲链接，还超就报错让调用方回退 1080p。 */
    private void enforceLinkQuota(AppSession sess, String channel) throws ResolutionException {
        int limit = linksPerDevice;
        if (limit <= 0) {
            return;
        }
        if (sess.linkedChannels.contains(channel)) {
            return;
        }
        if (sess.linkedChannels.size() < limit) {
            return;
        }
        evictIdleLinks(sess);
        if (sess.linkedChannels.size() >= limit) {
            throw new ResolutionException("设备链接配额已满 (" + limit + "), 回退 1080p", true);
        }
    }

    /**
     * 释放空闲超过 {@link #LINK_IDLE_TIMEOUT_SEC} 的链接（对应 Python {@code evict_idle_links}）。
     * <p>注意：这里只释放"配额占位"，
     * 不删缓存条目，所以正在看的频道不会因此掉线。
     */
    private void evictIdleLinks(AppSession sess) {
        double now = nowSec();
        Map<String, Double> snapshot;
        synchronized (stateLock) {
            snapshot = new HashMap<>(channelLastAccess);
        }
        List<String> toDrop = new ArrayList<>();
        synchronized (sess.linkedChannels) {
            for (String ch : sess.linkedChannels) {
                Double last = snapshot.get(ch);
                if (now - (last == null ? 0.0 : last) > LINK_IDLE_TIMEOUT_SEC) {
                    toDrop.add(ch);
                }
            }
            for (String ch : toDrop) {
                sess.linkedChannels.remove(ch);
            }
        }
        if (!toDrop.isEmpty()) {
            Log.d(TAG, "释放空闲链接: " + toDrop);
        }
    }

    // ==================================================================
    // 冷却
    // ==================================================================

    @Override
    public boolean isChannelInCooldown(String slug) {
        String channel = ChannelTable.canonical(slug);
        return cooldownRemaining(channel, nowSec()) > 0;
    }

    @Override
    public void clearChannelCooldown(String slug) {
        String channel = ChannelTable.canonical(slug);
        synchronized (stateLock) {
            channelFailures.remove(channel);
        }
    }

    private double cooldownRemaining(String channel, double now) {
        Failure f = failureOf(channel);
        if (f == null) {
            return 0.0;
        }
        double cooldown;
        if (f.count <= 1) {
            cooldown = COOLDOWN_1_SEC;
        } else if (f.count == 2) {
            cooldown = COOLDOWN_2_SEC;
        } else {
            cooldown = COOLDOWN_3_SEC;
        }
        return cooldown - (now - f.failedAt);
    }

    private Failure failureOf(String channel) {
        synchronized (stateLock) {
            return channelFailures.get(channel);
        }
    }

    private void recordFailure(String channel, String message) {
        double now = nowSec();
        synchronized (stateLock) {
            Failure prev = channelFailures.get(channel);
            int count = prev == null ? 1 : prev.count + 1;
            channelFailures.put(channel, new Failure(now, message, count));
            lastError = message == null ? "" : message;
            lastErrorAt = now;
        }
        Log.w(TAG, "[" + channel + "] 解析失败: " + abbrev(message, 200));
    }

    // ==================================================================
    // 设备轮换
    // ==================================================================

    /**
     * 遇到 HTTP 400 / 身份失效时换一台新"电视"（对应 Python {@code rotate_device_on_400}）。
     * <p>保护：5 分钟内只换一次；每天最多 5 次。
     *
     * @return 是否真的换了（换了才值得重试一次）
     */
    private boolean rotateDeviceOn400(String reason) {
        double now = nowSec();
        int wipeNo;
        synchronized (stateLock) {
            if (now - lastDeviceWipeAt < DEVICE_WIPE_COOLDOWN_SEC) {
                return false;
            }
            String day = dayString(now);
            if (!day.equals(deviceWipeDay)) {
                deviceWipeDay = day;
                deviceWipeCount = 0;
            }
            if (deviceWipeCount >= DEVICE_WIPE_DAILY_LIMIT) {
                Log.w(TAG, "24 小时内已自动换新身份 " + DEVICE_WIPE_DAILY_LIMIT
                        + " 次，停止自动更换，请手动检查网络或设备", 60.0);
                return false;
            }
            lastDeviceWipeAt = now;
            deviceWipeCount++;
            wipeNo = deviceWipeCount;
            session = null;
            cache.clear();
            channelFailures.clear();
            channelLastAccess.clear();
        }
        // 生成并落盘一份全新身份
        DeviceState fresh = DeviceState.createDefault();
        saveDeviceState(fresh);
        Log.i(TAG, "[设备] 检测到 400 (" + abbrev(reason, 80) + ")，旧身份已废弃，换新设备身份 "
                + "(5 分钟内仅一次，今日第 " + wipeNo + " 次)");
        return true;
    }

    // ==================================================================
    // 设备状态持久化
    // ==================================================================

    private DeviceState loadOrCreateDeviceState() {
        File f = deviceStateFile;
        if (f != null && f.isFile()) {
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Map<String, Object> map = Json.tryParseObject(text);
                if (map != null) {
                    DeviceState st = DeviceState.fromMap(map);
                    if (st.normalizeAfterLoad() && st.registeredAt > 0) {
                        Log.d(TAG, "复用已保存的设备身份: " + st.profile.brand + " " + st.profile.model);
                        return st;
                    }
                    if (st.normalizeAfterLoad()) {
                        return st;
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.d(TAG, "设备状态文件读取失败，重新生成: " + e.getMessage());
            }
        }
        DeviceState st = DeviceState.createDefault();
        saveDeviceState(st);
        return st;
    }

    /** 原子写设备状态（写临时文件再 rename）。写盘失败不影响业务。 */
    private void saveDeviceState(DeviceState state) {
        File f = deviceStateFile;
        if (f == null) {
            return;
        }
        try {
            File parent = f.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            String text = Json.stringify(state.toMap());
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                Files.deleteIfExists(tmp.toPath());
            }
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "设备状态落盘失败（忽略）: " + e.getMessage());
        }
    }

    // ==================================================================
    // playlist 代理
    // ==================================================================

    /**
     * 把上游 m3u8 改写成"绝对地址 + 分片走本地代理"的形式。
     * <p>对应 Python {@code rewrite_playlist_urls} + {@code proxy_playlist_ts_urls}：
     * <ol>
     *   <li>先把相对路径补成绝对 URL</li>
     *   <li>再把每个 {@code .ts} 地址换成 {@code {proxyOrigin}/proxy.ts?=<urlsafe-b64-nopad>}</li>
     * </ol>
     */
    @Override
    public String proxyPlaylist(String body, String finalUrl, String proxyOrigin, String slug) {
        String absolute = Protocol.rewritePlaylistUrls(body == null ? "" : body, finalUrl);
        String origin = proxyOrigin == null ? "" : rstripSlash(proxyOrigin);
        return Protocol.replaceNonCommentLines(absolute, line -> {
            String stripped = line.trim();
            if (!Protocol.isTsUrl(stripped)) {
                return line;
            }
            String b64 = Base64X.encodeUrlSafeNoPad(stripped.getBytes(StandardCharsets.UTF_8));
            return origin + "/proxy.ts?=" + b64;
        });
    }

    private static String rstripSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * 给一个即将被代理取流的分片地址挑签名头（对应 Python {@code playback_headers_for_ts_url}）。
     * <p>先找 host+path 前缀都匹配的缓存条目；找不到就退回同 host 的条目；
     * 再不行用默认头；如果 host 是 {@code liveali}/{@code liveten} 而完全没有签名头，
     * 抛 {@link ResolutionException}（这类 CDN 不带签名一定 403）。
     */
    @Override
    public Map<String, String> playbackHeadersForTsUrl(String realUrl) throws ResolutionException {
        java.net.URI parsed;
        try {
            parsed = new java.net.URI(realUrl == null ? "" : realUrl);
        } catch (java.net.URISyntaxException e) {
            throw new ResolutionException("代理分片地址非法: " + e.getMessage(), false);
        }
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new ResolutionException("代理分片地址缺少 host", false);
        }
        String tsPath = parsed.getPath() == null ? "" : parsed.getPath();

        Map<String, String> sameHost = null;
        String fallbackUid = "";
        List<ChannelEntry> snapshot;
        synchronized (stateLock) {
            snapshot = new ArrayList<>(cache.values());
            if (session != null) {
                fallbackUid = session.profile.androidId;
            }
        }
        for (ChannelEntry entry : snapshot) {
            if (Protocol.entryMissingSignedPlaybackHeaders(entry)) {
                continue;
            }
            String entryHost = entry.finalHost != null && !entry.finalHost.isEmpty()
                    ? entry.finalHost : Protocol.urlHost(entry.finalUrl);
            if (!entryHost.equalsIgnoreCase(host)) {
                continue;
            }
            Map<String, String> headers = Protocol.cctvPlaybackHeadersForEntry(entry);
            if (Protocol.tsPathMatchesFinalUrl(tsPath, entry.finalUrl)) {
                return headers;
            }
            if (sameHost == null) {
                sameHost = headers;
            }
        }
        if (sameHost != null) {
            return sameHost;
        }
        if (Protocol.livePlaybackHostNeedsSignedHeaders(host)) {
            throw new ResolutionException("缺少 " + host + " 的缓存签名头", true);
        }
        if (fallbackUid.isEmpty()) {
            for (ChannelEntry entry : snapshot) {
                if (!entry.androidId.isEmpty()) {
                    fallbackUid = entry.androidId;
                    break;
                }
            }
        }
        return Protocol.defaultCctvPlaybackHeaders(fallbackUid);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private ChannelEntry freshCached(String channel, double now) {
        synchronized (stateLock) {
            ChannelEntry e = cache.get(channel);
            if (e != null && e.fresh(nowToMs(now)) && !Protocol.entryMissingSignedPlaybackHeaders(e)) {
                return e;
            }
            return null;
        }
    }

    /** 控制面最小间隔（Python 的 {@code refresh_interval}），避免请求过密被风控。 */
    private void waitBusinessGap() {
        double wait;
        synchronized (stateLock) {
            wait = REFRESH_INTERVAL_SEC - (nowSec() - lastBusinessEndAt);
        }
        if (wait > 0 && wait < 10) {
            sleepMs((long) (wait * 1000));
        }
    }

    private void markBusinessEnd() {
        synchronized (stateLock) {
            lastBusinessEndAt = nowSec();
        }
    }

    private String rateLabel(String channel, ChannelEntry entry) {
        String label;
        if (ChannelTable.isTrue4k(channel)) {
            if ("36p".equals(entry.rate)) {
                label = "4K超高清 (36M超高码率)";
            } else if ("10p".equals(entry.rate)) {
                label = "4K (10M码率)";
            } else {
                label = "4K超高清";
            }
        } else if ("10p".equals(entry.rate)) {
            label = "高码率 (10M码率)";
        } else if ("36p".equals(entry.rate)) {
            label = "超高码率 (36M码率)";
        } else {
            label = entry.rateName.isEmpty() ? (entry.rate.isEmpty() ? "高码率" : entry.rate)
                    : entry.rateName;
        }
        if (ChannelTable.isTrue4k(channel) && label.contains("8K")) {
            label = "4K超高清 (36M超高码率)";
        }
        return label;
    }

    private static boolean isIdentityError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("400") || lower.contains("401") || lower.contains("403")
                || lower.contains("app/start") || lower.contains("decrypt")
                || lower.contains("session_key") || lower.contains("内部错误")
                || lower.contains("no usable url") || lower.contains("missing videos")
                || lower.contains("vdn") || lower.contains("live/v1/01")
                || lower.contains("live/v1/02");
    }

    private static String asString(Object v) {
        return v == null ? "" : (v instanceof String ? (String) v : String.valueOf(v));
    }

    private static String abbrev(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String dayString(double epochSec) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.ROOT);
        return fmt.format(new java.util.Date((long) (epochSec * 1000)));
    }

    private static double nowSec() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static long nowToMs(double epochSec) {
        return (long) (epochSec * 1000.0);
    }

    private static double toSec(long epochMs) {
        return epochMs / 1000.0;
    }

    private static void sleepMs(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Http http) {
        if (http != null) {
            try {
                http.close();
            } catch (RuntimeException ignored) {
                // 忽略
            }
        }
    }

    /** 供测/探针读取：当前缓存的频道数。 */
    public int cachedChannelCount() {
        synchronized (stateLock) {
            return cache.size();
        }
    }

    /** 供测/探针读取：当前设备档案。 */
    public DeviceProfile currentProfile() {
        synchronized (stateLock) {
            return session == null ? null : session.profile;
        }
    }

    /** 供测/探针读取：当前 cloud guid。 */
    public String cloudGuid() {
        synchronized (stateLock) {
            return session == null ? "" : session.cloudGuid;
        }
    }

    /** 供测/探针读取：成功/失败计数。 */
    public long[] counters() {
        return new long[] {resolveOkCount.get(), resolveFailCount.get()};
    }
}
