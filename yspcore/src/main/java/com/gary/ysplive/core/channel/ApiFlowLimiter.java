package com.gary.ysplive.core.channel;

import java.util.Random;

/**
 * 控制面接口限速器。
 * <p>对应 Python 参考实现的 {@code ApiFlowLimiter}：两次"控制面"请求（JCE 时移、bkliveinfo）
 * 之间至少间隔 {@code minIntervalMs}，并叠加 {@code [0, jitterMs)} 的随机抖动，
 * 避免固定节奏被上游风控识别。
 * <p>线程安全：所有等待串行化，任意线程可并发调用 {@link #waitSlot()}。
 */
public final class ApiFlowLimiter {

    /** 默认最小间隔（毫秒）。 */
    public static final long DEFAULT_MIN_INTERVAL_MS = 700L;
    /** 默认抖动上界（毫秒）。 */
    public static final long DEFAULT_JITTER_MS = 250L;

    private final long minIntervalMs;
    private final long jitterMs;
    private final Random random;
    private final Object lock = new Object();
    private long lastCallMs;
    private long waitCount;

    public ApiFlowLimiter() {
        this(DEFAULT_MIN_INTERVAL_MS, DEFAULT_JITTER_MS);
    }

    public ApiFlowLimiter(long minIntervalMs, long jitterMs) {
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.jitterMs = Math.max(0L, jitterMs);
        this.random = new Random();
    }

    /**
     * 占用一个调用时隙；必要时阻塞到距上次调用足够久为止。
     * <p>与 Python 语义一致：抖动每次重新抽取，且计时以"离开时"为准。
     */
    public void waitSlot() {
        synchronized (lock) {
            long target;
            if (jitterMs > 0L) {
                target = minIntervalMs + (long) (random.nextDouble() * jitterMs);
            } else {
                target = minIntervalMs;
            }
            long now = System.currentTimeMillis();
            long diff = now - lastCallMs;
            if (lastCallMs != 0L && diff < target) {
                sleepQuietly(target - diff);
            }
            lastCallMs = System.currentTimeMillis();
            waitCount++;
        }
    }

    /** 已等待并放行的次数（诊断用）。 */
    public long waitCount() {
        synchronized (lock) {
            return waitCount;
        }
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    public long jitterMs() {
        return jitterMs;
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0L) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
