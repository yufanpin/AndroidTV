package com.tivimatelite.ysp

import android.content.Context
import com.gary.ysplive.core.spi.Gateway
import java.io.File

/**
 * migu 分支的引擎配置。咪咕内核只关心监听端口与是否允许局域网访问，
 * 其余 ysp 设备协议相关字段（高码率 / 4K / 缓存 / 并发 / 换台防抖）已随内核一起移除。
 */
data class YspConfig(
    val port: Int = 8767,
    val allowLan: Boolean = false,
    val logLevel: Int = 1
) {

    fun toGatewayConfig(context: Context): Gateway.Config = Gateway.Config().apply {
        port = this@YspConfig.port.coerceIn(1024, 65535)
        allowLan = this@YspConfig.allowLan
        timeoutSec = 10.0
        dataDir = dataDir(context)
    }

    /** 需要重启引擎才能生效的字段指纹。 */
    fun restartFingerprint(): String = "$port|$allowLan"

    companion object {
        private const val PREFS = "ysp_config"
        private const val K_PORT = "port"
        private const val K_LAN = "allow_lan"
        private const val K_LOG = "log_level"

        fun load(context: Context): YspConfig {
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = YspConfig()
            return YspConfig(
                port = sp.getInt(K_PORT, defaults.port),
                allowLan = sp.getBoolean(K_LAN, defaults.allowLan),
                logLevel = sp.getInt(K_LOG, defaults.logLevel)
            )
        }

        fun save(context: Context, config: YspConfig) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(K_PORT, config.port.coerceIn(1024, 65535))
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
