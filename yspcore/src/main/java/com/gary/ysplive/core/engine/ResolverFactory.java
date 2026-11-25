package com.gary.ysplive.core.engine;

import com.gary.ysplive.core.spi.Gateway;
import com.gary.ysplive.core.spi.Log;
import com.gary.ysplive.core.spi.Resolver;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 高码率解析器的装配点。
 *
 * <p>用反射构造 {@code com.gary.ysplive.core.device.DeviceResolver}，好处是：
 * <ul>
 *   <li>引擎其余部分只依赖 {@link Resolver} 接口，任何一侧重构都不会互相牵连；</li>
 *   <li>解析器缺失或构造失败时，整个网关自动降级为纯 1080p 模式，而不是启动失败——
 *       这正是本项目"多重故障平滑回退不断流"的设计要求。</li>
 * </ul>
 */
final class ResolverFactory {

    private static final String TAG = "gateway";
    private static final String IMPL = "com.gary.ysplive.core.device.DeviceResolver";

    private ResolverFactory() { }

    /**
     * 尝试创建解析器。
     *
     * @return 成功返回实例；失败返回 {@code null}（调用方应降级为 1080p）
     */
    static Resolver create(Gateway.Config config) {
        Class<?> clazz;
        try {
            clazz = Class.forName(IMPL);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "未找到高码率解析器 " + IMPL + "，本次以 1080p 模式运行");
            return null;
        } catch (LinkageError e) {
            Log.w(TAG, "高码率解析器加载失败: " + e + "，本次以 1080p 模式运行");
            return null;
        }
        Object instance;
        try {
            Constructor<?> ctor = clazz.getConstructor(Gateway.Config.class);
            instance = ctor.newInstance(config);
        } catch (Throwable e) {
            Log.w(TAG, "高码率解析器构造失败: " + e + "，本次以 1080p 模式运行");
            return null;
        }
        if (!(instance instanceof Resolver)) {
            Log.w(TAG, IMPL + " 未实现 Resolver 接口，本次以 1080p 模式运行");
            return null;
        }
        Resolver resolver = (Resolver) instance;

        // 可选配置项：存在就调，不存在就忽略（保持向前/向后兼容）
        invokeIfPresent(resolver, "setLinksPerDevice", int.class, Integer.valueOf(config.linksPerDevice));
        invokeIfPresent(resolver, "setZapDebounceSeconds", double.class,
                Double.valueOf(config.zapDebounceSec));
        invokeIfPresent(resolver, "setOnly4kHighBitrate", boolean.class,
                Boolean.valueOf(config.only4kHighBitrate));
        File dataDir = config.dataDir;
        if (dataDir != null) {
            File stateFile = new File(dataDir, "device-state.json");
            if (!invokeIfPresent(resolver, "setDeviceStateFile", File.class, stateFile)) {
                invokeIfPresent(resolver, "setDeviceState", File.class, stateFile);
            }
        }
        invokeIfPresent(resolver, "setTimeoutMs", int.class,
                Integer.valueOf((int) Math.round(config.timeoutSec * 1000.0)));
        return resolver;
    }

    private static boolean invokeIfPresent(Object target, String name, Class<?> type, Object arg) {
        try {
            Method m = target.getClass().getMethod(name, type);
            m.invoke(target, arg);
            return true;
        } catch (NoSuchMethodException e) {
            // 实现方没有这个可选项，忽略
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "调用 " + name + " 失败: " + e);
            return false;
        }
    }
}
