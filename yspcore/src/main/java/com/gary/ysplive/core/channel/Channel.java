package com.gary.ysplive.core.channel;

import com.gary.ysplive.core.data.ChannelSpec;
import com.gary.ysplive.core.data.ChannelTable;
import com.gary.ysplive.core.spi.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 单频道的分片状态机。
 * <p>对应 Python 参考实现里的 {@code Channel}（2640-2871）+ {@code seg_key}/{@code jce_refresh}
 * 的合并逻辑 + {@code build_playlist}。
 *
 * <p>职责：维护有序分片队列（{@code order} / {@code segments}）、去重集合 {@code seenKeys}、
 * 单调递增的 {@code seq}、最近访问时间 {@code lastAccess}、后台刷新线程引用、备用通道模式
 * （{@code jce}/{@code bk}）、bkliveinfo 地址缓存及其 TTL、以及"最近一次构建的 playlist"（供
 * 15 秒 SWR 缓存使用）。
 *
 * <p>{@code lastM3u8Source} 记录最近一次对外提供的 m3u8 来自设备协议（{@code device}）还是
 * 备用通道（{@code bk}），调用方据此在切换源时注入 {@code #EXT-X-DISCONTINUITY}。
 *
 * <p>线程安全：所有可变状态都在 {@code lock} 上同步。外部若需要"读-改-写"原子性，
 * 请用 {@code synchronized (ch.lock()) { ... }} 包住（可重入）。
 */
public final class Channel {

    /** 主通道模式：JCE 时移回看拉流。 */
    public static final String MODE_JCE = "jce";
    /** 备用通道模式：bkliveinfo 取 stream。 */
    public static final String MODE_BK = "bk";

    /** 对外 m3u8 来源：设备协议解析。 */
    public static final String SOURCE_DEVICE = "device";
    /** 对外 m3u8 来源：备用通道。 */
    public static final String SOURCE_BK = "bk";

    /** 分片队列上限（Python {@code MAX_SEGS}）。 */
    public static final int MAX_SEGS = 120;
    /** {@code seenKeys} 上限（Python {@code deque(maxlen=MAX_SEGS*2)}）。 */
    public static final int MAX_SEEN_KEYS = MAX_SEGS * 2;
    /** 首次冷启动时只保留最后 N 个分片。 */
    private static final int INIT_TAIL = 25;
    /** 构建 playlist 时最多取最近 N 个分片。 */
    private static final int PLAYLIST_TAIL = 15;

    /** JCE 拉回来的一条原始分片（尚未入库）。 */
    public static final class RawSeg {
        public final double dur;
        public final String pdt;
        public final String url;

        public RawSeg(double dur, String pdt, String url) {
            this.dur = dur;
            this.pdt = pdt == null ? "" : pdt;
            this.url = url == null ? "" : url;
        }
    }

    /** 入库结果：是否为首次冷启动、新增了多少条。 */
    public static final class MergeResult {
        public final boolean isInit;
        public final int added;

        MergeResult(boolean isInit, int added) {
            this.isInit = isInit;
            this.added = added;
        }
    }

    /** 队列里的一个分片。 */
    public static final class Segment {
        public final long seq;
        public final double dur;
        public final String pdt;
        public final String url;

        Segment(long seq, double dur, String pdt, String url) {
            this.seq = seq;
            this.dur = dur;
            this.pdt = pdt == null ? "" : pdt;
            this.url = url == null ? "" : url;
        }
    }

    private final ChannelSpec spec;
    private final Object lock = new Object();

    private final HashMap<String, Segment> segments = new HashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final ArrayDeque<String> seenKeys = new ArrayDeque<>();

    private long seq;
    private double lastAccess;
    private Thread thread;
    private String lastError = "";
    private double lastOk;
    private String mode = MODE_JCE;
    private String lastPdt = "";

    private List<String> bkUrls = new ArrayList<>();
    private double bkUrlsTime;
    /** {@link #bkUrls} 是用哪个 defn 拉回来的；阶梯推进后需要按新 defn 重拉。 */
    private String bkUrlsDefn = "";
    private String bkPlaylist = "";
    /** 当前使用的 bk 候选 CDN 下标（0 起）；分片 403/404 时轮换到下一个。 */
    private int bkCandidateIndex;
    /** 上次因分片失败而轮换的时间（秒），用于限流，避免死频道疯狂重试。 */
    private double bkLastRotateAt;
    /**
     * bk 通道实际使用的清晰度标记，默认 {@code null}（表示等于 {@link #defn()}）。
     * <p>真 4K 频道在 {@code fhd}/{@code shd} 全线 403 时可以沿
     * {@link #BK_DEFN_LADDER} 逐级降级（实测 {@code uhd} 反而能出片），
     * 由 {@link #advanceBkLadder()} 推进。
     */
    private String effDefn;
    /** 上次推进清晰度阶梯的时间（秒），用于 10s 节流。 */
    private double bkLadderAt;
    /** 上一次"整条阶梯走完仍失败"的时间（秒），用于 60s 冷却。 */
    private double bkLadderExhaustedAt;
    /** 连续 bk 刷新成功计数；达到阈值就把阶梯复位回默认清晰度。 */
    private int bkSuccessStreak;

    private boolean starting;
    private String lastBuiltPlaylist = "";
    private double lastBuiltTime;
    private String lastM3u8Source = "";

    public Channel(ChannelSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec == null");
        }
        this.spec = spec;
    }

    // ------------------------------------------------------------------ 基本属性

    public ChannelSpec spec() {
        return spec;
    }

    public String slug() {
        return spec.slug;
    }

    public String name() {
        return spec.name;
    }

    public String sid() {
        return spec.sid;
    }

    public String pid() {
        return spec.pid;
    }

    public String defn() {
        return spec.defn;
    }

    public String defnLabel() {
        return spec.defnLabel();
    }

    /** 内部互斥锁；外部需要复合操作时用它包一层。 */
    public Object lock() {
        return lock;
    }

    // ------------------------------------------------------------------ 访问时间 / 状态

    public void touch() {
        synchronized (lock) {
            lastAccess = nowSec();
        }
    }

    public void setLastAccess(double seconds) {
        synchronized (lock) {
            lastAccess = seconds;
        }
    }

    public double lastAccess() {
        synchronized (lock) {
            return lastAccess;
        }
    }

    public double lastOk() {
        synchronized (lock) {
            return lastOk;
        }
    }

    public void setLastOk(double seconds) {
        synchronized (lock) {
            lastOk = seconds;
        }
    }

    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    public void setLastError(String error) {
        synchronized (lock) {
            lastError = error == null ? "" : error;
        }
    }

    public String mode() {
        synchronized (lock) {
            return mode;
        }
    }

    public void setMode(String value) {
        synchronized (lock) {
            mode = value == null ? MODE_JCE : value;
        }
    }

    public boolean isStarting() {
        synchronized (lock) {
            return starting;
        }
    }

    public void setStarting(boolean value) {
        synchronized (lock) {
            starting = value;
        }
    }

    public Thread thread() {
        synchronized (lock) {
            return thread;
        }
    }

    public void setThread(Thread value) {
        synchronized (lock) {
            thread = value;
        }
    }

    public boolean threadDead() {
        synchronized (lock) {
            return thread == null || !thread.isAlive();
        }
    }

    // ------------------------------------------------------------------ 备用通道（bk）

    public String bkPlaylist() {
        synchronized (lock) {
            return bkPlaylist;
        }
    }

    public void setBkPlaylist(String value) {
        synchronized (lock) {
            bkPlaylist = value == null ? "" : value;
        }
    }

    public List<String> bkUrls() {
        synchronized (lock) {
            return new ArrayList<>(bkUrls);
        }
    }

    public void setBkUrls(List<String> urls) {
        synchronized (lock) {
            bkUrls = urls == null ? new ArrayList<String>() : new ArrayList<>(urls);
            // 地址列表换了一批：候选下标归零，避免残留的旧下标越界/指错
            bkCandidateIndex = 0;
        }
    }

    public void clearBkUrls() {
        synchronized (lock) {
            bkUrls = new ArrayList<>();
            bkUrlsTime = 0.0;
            bkUrlsDefn = "";
            bkCandidateIndex = 0;
        }
    }

    /** 当前使用的 bk 候选 CDN 下标。 */
    public int bkCandidateIndex() {
        synchronized (lock) {
            return bkCandidateIndex;
        }
    }

    public void setBkCandidateIndex(int index) {
        synchronized (lock) {
            bkCandidateIndex = Math.max(0, index);
        }
    }

    /** 按当前地址数量取模后的有效下标（地址为空时返回 0）。 */
    public int bkCandidateIndexMod() {
        synchronized (lock) {
            if (bkUrls.isEmpty()) {
                return 0;
            }
            return Math.floorMod(bkCandidateIndex, bkUrls.size());
        }
    }

    /** 上次因分片失败而轮换的时间（秒）。 */
    public double bkLastRotateAt() {
        synchronized (lock) {
            return bkLastRotateAt;
        }
    }

    public void setBkLastRotateAt(double seconds) {
        synchronized (lock) {
            bkLastRotateAt = seconds;
        }
    }

    /**
     * 判断"分片失败 → 轮换 bk 候选 CDN"现在是否允许放行（限流）。
     *
     * @param minIntervalSec 同一频道两次轮换的最小间隔
     */
    public boolean canRotateBkCandidate(double minIntervalSec) {
        synchronized (lock) {
            if (bkUrls.size() < 2) {
                return false;
            }
            return (nowSec() - bkLastRotateAt) >= minIntervalSec;
        }
    }

    // ------------------------------------------------------------------ bk 清晰度阶梯

    /**
     * bk 通道的清晰度回退阶梯。
     * <p>顺序按"2026-11 上游事故期间的实测可用率"排，而不是单纯的清晰度高低：
     * 真 4K 频道当时在 {@code fhd} 上全线 403，反而 {@code uhd} 能出片，所以 uhd 排在
     * 低清晰度之前——降级的目标是"能播"，不是"画质高"。
     */
    public static final String[] BK_DEFN_LADDER = {"fhd", "uhd", "shd", "hd", "sd"};

    /** 一次阶梯推进允许的最小间隔（秒）：一次刷新（约 10s）最多推进一级，慢慢收敛。 */
    public static final double BK_LADDER_MIN_INTERVAL_SEC = 10.0;
    /** 底部 rung 走完仍失败后的冷却（秒），避免死频道高频重试。 */
    public static final double BK_LADDER_EXHAUSTED_INTERVAL_SEC = 60.0;
    /** 连续成功多少次刷新后把阶梯复位回默认清晰度。 */
    public static final int BK_RESET_AFTER_SUCCESSES = 2;

    /** bk 通道当前使用的清晰度标记（默认等于 {@link #defn()}）。 */
    public String effDefn() {
        synchronized (lock) {
            return effDefn != null ? effDefn : spec.defn;
        }
    }

    public void setEffDefn(String value) {
        synchronized (lock) {
            effDefn = value;
        }
    }

    /** 当前处在阶梯的第几级（不在阶梯里返回 -1）。 */
    public int bkLadderIndex() {
        synchronized (lock) {
            String cur = effDefn != null ? effDefn : spec.defn;
            for (int i = 0; i < BK_DEFN_LADDER.length; i++) {
                if (BK_DEFN_LADDER[i].equals(cur)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * 推进一级阶梯（无节流；由调用方控制节奏）。
     *
     * @return 新的 defn；已在最后一级返回 {@code null}（表示无路可退）
     */
    public String advanceBkLadder() {
        synchronized (lock) {
            String cur = effDefn != null ? effDefn : spec.defn;
            int idx = -1;
            for (int i = 0; i < BK_DEFN_LADDER.length; i++) {
                if (BK_DEFN_LADDER[i].equals(cur)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                // 频道表的 defn 不在阶梯里（例如已被改成别的值）：从第一级重新开始
                effDefn = BK_DEFN_LADDER[0];
            } else if (idx + 1 >= BK_DEFN_LADDER.length) {
                return null;
            } else {
                effDefn = BK_DEFN_LADDER[idx + 1];
            }
            bkCandidateIndex = 0;
            bkPlaylist = "";
            lastBuiltPlaylist = "";
            lastBuiltTime = 0.0;
            return effDefn;
        }
    }

    /**
     * 现在是否允许再推进一级阶梯。
     * <p>节流到 {@link #BK_LADDER_MIN_INTERVAL_SEC}（约一次刷新周期）：一次刷新只降一级，
     * 让阶梯在多次刷新之间慢慢收敛，而不是被一次不走运的采样一口气打到底。
     */
    public boolean canStartBkLadderWalk() {
        synchronized (lock) {
            return (nowSec() - bkLadderAt) >= BK_LADDER_MIN_INTERVAL_SEC;
        }
    }

    /** 是否处于"整条阶梯走完仍失败"的冷却期。 */
    public boolean isBkLadderExhausted() {
        synchronized (lock) {
            return (nowSec() - bkLadderExhaustedAt) < BK_LADDER_EXHAUSTED_INTERVAL_SEC;
        }
    }

    /** 上次推进阶梯的时间（秒）。 */
    public double bkLadderExhaustedAt() {
        synchronized (lock) {
            return bkLadderExhaustedAt;
        }
    }

    /** 标记"整条阶梯已走完仍失败"，进入 60s 冷却。 */
    public void markBkLadderExhausted() {
        synchronized (lock) {
            bkLadderExhaustedAt = nowSec();
            bkLadderAt = nowSec();
        }
    }

    /** 记录一次 bk 刷新成功；连续 {@link #BK_RESET_AFTER_SUCCESSES} 次后把阶梯复位。 */
    public void noteBkSuccess() {
        synchronized (lock) {
            bkSuccessStreak++;
            bkLadderExhaustedAt = 0.0;
            if (bkSuccessStreak >= BK_RESET_AFTER_SUCCESSES && effDefn != null) {
                String prev = effDefn;
                effDefn = null;
                bkCandidateIndex = 0;
                bkLadderAt = 0.0;
                bkSuccessStreak = 0;
                Log.i("channel", "[" + spec.slug + "] bk " + prev
                        + " 已连续成功，清晰度复位到 " + spec.defn, 10.0);
            }
        }
    }

    /** 记录一次 bk 刷新失败，清零成功计数。 */
    public void noteBkFailure() {
        synchronized (lock) {
            bkSuccessStreak = 0;
        }
    }

    /** 当前是否处在降级状态（effDefn != 频道表默认）。 */
    public boolean isBkDegraded() {
        synchronized (lock) {
            return effDefn != null && !effDefn.equals(spec.defn);
        }
    }

    /** 复位阶梯到频道表默认值（例如长时间失败后重新来过）。 */
    public void resetBkLadder() {
        synchronized (lock) {
            effDefn = null;
            bkLadderExhaustedAt = 0.0;
            bkCandidateIndex = 0;
        }
    }

    public double bkUrlsTime() {
        synchronized (lock) {
            return bkUrlsTime;
        }
    }

    public void setBkUrlsTime(double seconds) {
        synchronized (lock) {
            bkUrlsTime = seconds;
        }
    }

    /** 当前 {@code bkUrls} 是用哪个 defn 拉回来的（用于判断阶梯推进后需要重拉）。 */
    public String bkUrlsDefn() {
        synchronized (lock) {
            return bkUrlsDefn;
        }
    }

    public void setBkUrlsDefn(String value) {
        synchronized (lock) {
            bkUrlsDefn = value == null ? "" : value;
        }
    }

    /** bkliveinfo 地址是否已过期（或从未拉取）。 */
    public boolean bkUrlsStale(double ttlSec) {
        synchronized (lock) {
            return bkUrls.isEmpty() || (nowSec() - bkUrlsTime > ttlSec);
        }
    }

    // ------------------------------------------------------------------ 构建缓存 / 来源

    public String lastBuiltPlaylist() {
        synchronized (lock) {
            return lastBuiltPlaylist;
        }
    }

    public double lastBuiltTime() {
        synchronized (lock) {
            return lastBuiltTime;
        }
    }

    /** 记录一次对外构建（device 路径需要清空 SWR 缓存）。 */
    public void markBuilt(String playlist, double atSec) {
        synchronized (lock) {
            lastBuiltPlaylist = playlist == null ? "" : playlist;
            lastBuiltTime = atSec;
        }
    }

    public void invalidateBuilt() {
        synchronized (lock) {
            lastBuiltPlaylist = "";
            lastBuiltTime = 0.0;
        }
    }

    public String lastM3u8Source() {
        synchronized (lock) {
            return lastM3u8Source;
        }
    }

    public void setM3u8Source(String source) {
        synchronized (lock) {
            lastM3u8Source = source == null ? "" : source;
        }
    }

    public String lastPdt() {
        synchronized (lock) {
            return lastPdt;
        }
    }

    // ------------------------------------------------------------------ 分片队列

    public boolean hasSegments() {
        synchronized (lock) {
            return !order.isEmpty();
        }
    }

    public int segmentCount() {
        synchronized (lock) {
            return segments.size();
        }
    }

    /** 清空所有分片状态（对应 Python {@code ensure_channel} 里的 cache_stale 分支）。 */
    public void clearBuffer() {
        synchronized (lock) {
            segments.clear();
            order.clear();
            seenKeys.clear();
            lastPdt = "";
            bkPlaylist = "";
        }
    }

    /** 取最近 {@code n} 个分片（顺序从旧到新）。 */
    public List<Segment> tail(int n) {
        synchronized (lock) {
            List<Segment> out = new ArrayList<>();
            if (n <= 0) {
                return out;
            }
            int skip = Math.max(0, order.size() - n);
            int i = 0;
            for (String key : order) {
                if (i++ < skip) {
                    continue;
                }
                Segment s = segments.get(key);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    /** 全部分片快照（诊断用）。 */
    public List<Segment> allSegments() {
        return tail(Integer.MAX_VALUE);
    }

    /**
     * 合并一次 JCE 拉取结果。等价于 Python {@code jce_refresh} 里 {@code with ch.lock} 中的全部逻辑。
     *
     * @param fetched 本次拉到的分片（按上游顺序）
     * @return 是否首次冷启动、新增条数
     */
    public MergeResult applyJceSegments(List<RawSeg> fetched) {
        synchronized (lock) {
            int added = 0;
            boolean isInit = order.isEmpty();
            if (isInit) {
                for (RawSeg seg : fetched) {
                    String k = segKey(seg.url, seg.pdt);
                    if (!seenKeys.contains(k)) {
                        pushSeen(k);
                    }
                }
                List<RawSeg> initSegs = fetched.size() > INIT_TAIL
                        ? fetched.subList(fetched.size() - INIT_TAIL, fetched.size())
                        : fetched;
                for (RawSeg seg : initSegs) {
                    String key = segKey(seg.url, seg.pdt);
                    seq++;
                    segments.put(key, new Segment(seq, seg.dur, seg.pdt, seg.url));
                    order.addLast(key);
                    added++;
                    if (!seg.pdt.isEmpty()) {
                        lastPdt = seg.pdt;
                    }
                }
            } else {
                for (RawSeg seg : fetched) {
                    if (!seg.pdt.isEmpty() && !lastPdt.isEmpty()
                            && seg.pdt.compareTo(lastPdt) <= 0) {
                        continue;
                    }
                    String key = segKey(seg.url, seg.pdt);
                    Segment existing = segments.get(key);
                    if (existing != null) {
                        segments.put(key, new Segment(existing.seq, existing.dur, existing.pdt, seg.url));
                        continue;
                    }
                    if (seenKeys.contains(key)) {
                        continue;
                    }
                    pushSeen(key);
                    seq++;
                    segments.put(key, new Segment(seq, seg.dur, seg.pdt, seg.url));
                    order.addLast(key);
                    if (!seg.pdt.isEmpty()) {
                        lastPdt = seg.pdt;
                    }
                    added++;
                }
            }
            while (order.size() > MAX_SEGS) {
                String oldest = order.pollFirst();
                segments.remove(oldest);
            }
            lastError = "";
            return new MergeResult(isInit, added);
        }
    }

    private void pushSeen(String key) {
        seenKeys.addLast(key);
        while (seenKeys.size() > MAX_SEEN_KEYS) {
            seenKeys.pollFirst();
        }
    }

    /** 分片去重键：有 PROGRAM-DATE-TIME 用 {@code pdt:}，否则用 {@code scheme://host/path}。 */
    public static String segKey(String url, String pdt) {
        if (pdt != null && !pdt.isEmpty()) {
            return "pdt:" + pdt;
        }
        String u = url == null ? "" : url;
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) {
            return u;
        }
        String scheme = u.substring(0, schemeEnd + 3);
        String rest = u.substring(schemeEnd + 3);
        int pathStart = indexOfAny(rest, '/', '?', '#');
        String authority;
        String path;
        if (pathStart < 0) {
            authority = rest;
            path = "";
        } else {
            authority = rest.substring(0, pathStart);
            path = rest.substring(pathStart);
        }
        int queryStart = indexOfAny(path, '?', '#');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        return scheme + authority + path;
    }

    private static int indexOfAny(String s, char... chars) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            for (char target : chars) {
                if (c == target) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 构建 playlist

    /**
     * 构建对外 m3u8 文本。
     *
     * @param holdback true 时扣住最新一片（给播放器留缓冲，避免追到写入中的分片）
     * @return m3u8 文本；无数据返回 {@code null}
     */
    public String buildPlaylist(boolean holdback) {
        List<Segment> segs;
        synchronized (lock) {
            if (MODE_BK.equals(mode)) {
                String res = bkPlaylist.isEmpty() ? null : bkPlaylist;
                if (res != null) {
                    lastBuiltPlaylist = res;
                    lastBuiltTime = nowSec();
                }
                return res;
            }
            segs = tail(PLAYLIST_TAIL);
        }
        if (segs.isEmpty()) {
            return null;
        }
        List<Segment> display;
        if (holdback && segs.size() >= 4) {
            display = new ArrayList<>(segs.subList(0, segs.size() - 1));
        } else {
            display = segs;
        }
        int target = 6;
        for (Segment s : display) {
            int rounded = (int) (s.dur + 0.5);
            if (rounded > target) {
                target = rounded;
            }
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("#EXTM3U\n");
        sb.append("#EXT-X-VERSION:3\n");
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(display.get(0).seq).append('\n');
        for (Segment s : display) {
            if (!s.pdt.isEmpty()) {
                sb.append("#EXT-X-PROGRAM-DATE-TIME:").append(s.pdt).append('\n');
            }
            sb.append("#EXTINF:").append(formatDur(s.dur)).append(",\n");
            sb.append(s.url).append('\n');
        }
        String res = sb.toString();
        synchronized (lock) {
            lastBuiltPlaylist = res;
            lastBuiltTime = nowSec();
        }
        return res;
    }

    /** 与 Python {@code '%.3f' % dur} 完全一致的三位小数格式（不受本地 Locale 影响）。 */
    static String formatDur(double dur) {
        return String.format(java.util.Locale.ROOT, "%.3f", dur);
    }

    // ------------------------------------------------------------------ 诊断

    /** 供 /diag 使用：只读快照。 */
    public String describeForDiag() {
        synchronized (lock) {
            return "segments=" + segments.size() + ", mode=" + mode
                    + ", lastError=" + (lastError.isEmpty() ? "无" : lastError);
        }
    }

    static double nowSec() {
        return System.currentTimeMillis() / 1000.0;
    }
}
