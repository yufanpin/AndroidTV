package com.tivimatelite.ysp

import android.content.Context
import com.gary.ysplive.core.spi.Gateway
import java.io.File

/**
 * ysp 分支的引擎配置。默认值面向 S905L3 这类 2GB 低端盒子。
 *
 * 真机实测（192.168.10.240）结论——以下组合换台最快、无 403 重试：
 * - 高码率开启（disableHighBitrate=false）：1080p bk 通道在本机 CDN 上分片 403 全挂，
 *   只有设备协议高码率通道可用；关掉高码率会退化到必挂的 bk，换台卡 15s+。
 * - only4kHighBitrate=false：普通频道也要能用高码率，否则同样掉进 bk 通道。
 * - zapDebounceSec=0：换台防抖会强制先走 1080p bk 等 10s，期间首片 403 触发重试。
 * - cacheMb=128：更大分片缓存让换台/回看命中更快。
 */
data class YspConfig(
    val port: Int = 8767,
    val cacheMb: Int = 128,
    val linksPerDevice: Int = 4,
    val disableHighBitrate: Boolean = false,
    val only4kHighBitrate: Boolean = false,
    val zapDebounceSec: Double = 0.0,
    val allowLan: Boolean = false,
    val logLevel: Int = 1
) {

    fun toGatewayConfig(context: Context): Gateway.Config = Gateway.Config().apply {
        port = this@YspConfig.port.coerceIn(1024, 65535)
        cacheMb = this@YspConfig.cacheMb.coerceIn(16, 128)
        linksPerDevice = this@YspConfig.linksPerDevice.coerceIn(1, 12)
        disableHighBitrate = this@YspConfig.disableHighBitrate
        only4kHighBitrate = this@YspConfig.only4kHighBitrate
        zapDebounceSec = this@YspConfig.zapDebounceSec.coerceIn(0.0, 30.0)
        allowLan = this@YspConfig.allowLan
        timeoutSec = 10.0
        dataDir = dataDir(context)
    }

    /** 需要重启引擎才能生效的字段指纹。 */
    fun restartFingerprint(): String =
        "$port|$cacheMb|$linksPerDevice|$disableHighBitrate|$only4kHighBitrate|$zapDebounceSec|$allowLan"

    companion object {
        private const val PREFS = "ysp_config"
        private const val K_PORT = "port"
        private const val K_CACHE = "cache_mb"
        private const val K_LINKS = "links"
        private const val K_HIGH = "disable_high_bitrate"
        private const val K_ONLY4K = "only4k"
        private const val K_ZAP = "zap_debounce"
        private const val K_LAN = "allow_lan"
        private const val K_LOG = "log_level"
        /** 配置版本：老版本（无此键）加载时把影响流畅度的项升级到实测最优值。 */
        private const val K_VERSION = "config_version"
        private const val CONFIG_VERSION = 2

        fun load(context: Context): YspConfig {
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = YspConfig()
            val version = sp.getInt(K_VERSION, 0)
            // 老配置迁移：只重置影响换台流畅度的三项，保留用户其他偏好（端口/缓存/并发/局域网/日志）。
            // 根因：旧默认 disableHighBitrate=true + only4k=true + zap=10 会让普通频道
            // 走本机 CDN 全挂的 bk 1080p 通道，首片 403 后重试，换台卡 15s+（真机实测）。
            val migrate = version < CONFIG_VERSION
            val cfg = YspConfig(
                port = sp.getInt(K_PORT, defaults.port),
                cacheMb = sp.getInt(K_CACHE, defaults.cacheMb),
                linksPerDevice = sp.getInt(K_LINKS, defaults.linksPerDevice),
                disableHighBitrate = if (migrate) defaults.disableHighBitrate
                    else sp.getBoolean(K_HIGH, defaults.disableHighBitrate),
                only4kHighBitrate = if (migrate) defaults.only4kHighBitrate
                    else sp.getBoolean(K_ONLY4K, defaults.only4kHighBitrate),
                zapDebounceSec = if (migrate) defaults.zapDebounceSec
                    else sp.getString(K_ZAP, null)?.toDoubleOrNull() ?: defaults.zapDebounceSec,
                allowLan = sp.getBoolean(K_LAN, defaults.allowLan),
                logLevel = sp.getInt(K_LOG, defaults.logLevel)
            )
            if (migrate) save(context, cfg)
            return cfg
        }

        fun save(context: Context, config: YspConfig) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(K_PORT, config.port.coerceIn(1024, 65535))
                .putInt(K_CACHE, config.cacheMb.coerceIn(16, 128))
                .putInt(K_LINKS, config.linksPerDevice.coerceIn(1, 12))
                .putBoolean(K_HIGH, config.disableHighBitrate)
                .putBoolean(K_ONLY4K, config.only4kHighBitrate)
                .putString(K_ZAP, config.zapDebounceSec.coerceIn(0.0, 30.0).toString())
                .putBoolean(K_LAN, config.allowLan)
                .putInt(K_LOG, config.logLevel.coerceIn(0, 3))
                .putInt(K_VERSION, CONFIG_VERSION)
                .apply()
        }

        fun dataDir(context: Context): File {
            val external = context.getExternalFilesDir("ysp")
            if (external != null) {
                if (!external.exists()) external.mkdirs()
                return external
            }
            return context.getDir("ysp", Context.MODE_PRIVATE)
        }
    }
}
