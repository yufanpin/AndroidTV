package com.tivimatelite.ysp

import android.content.Context
import com.gary.ysplive.core.spi.Gateway
import java.io.File

/**
 * ysp 分支的引擎配置。默认值面向 S905L3 这类 2GB 低端盒子：
 * 关闭高码率、缓存压到 32MB，只走 1080p JCE 通道。
 */
data class YspConfig(
    val port: Int = 8767,
    val cacheMb: Int = 32,
    val linksPerDevice: Int = 4,
    val disableHighBitrate: Boolean = true,
    val only4kHighBitrate: Boolean = false,
    val zapDebounceSec: Double = 10.0,
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

        fun load(context: Context): YspConfig {
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = YspConfig()
            return YspConfig(
                port = sp.getInt(K_PORT, defaults.port),
                cacheMb = sp.getInt(K_CACHE, defaults.cacheMb),
                linksPerDevice = sp.getInt(K_LINKS, defaults.linksPerDevice),
                disableHighBitrate = sp.getBoolean(K_HIGH, defaults.disableHighBitrate),
                only4kHighBitrate = sp.getBoolean(K_ONLY4K, defaults.only4kHighBitrate),
                zapDebounceSec = sp.getString(K_ZAP, null)?.toDoubleOrNull()
                    ?: defaults.zapDebounceSec,
                allowLan = sp.getBoolean(K_LAN, defaults.allowLan),
                logLevel = sp.getInt(K_LOG, defaults.logLevel)
            )
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
