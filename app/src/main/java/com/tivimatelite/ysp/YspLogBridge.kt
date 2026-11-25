package com.tivimatelite.ysp

import com.gary.ysplive.core.spi.Log
import com.tivimatelite.web.AppLogStore

/** 把引擎日志接入 App 的统一日志与流程事件。只安装一次。 */
object YspLogBridge {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            Log.addListener { entry ->
                if (entry == null) return@addListener
                val tag = "ysp/" + (entry.tag ?: "engine")
                when (entry.level) {
                    Log.ERROR -> AppLogStore.e(tag, entry.message)
                    Log.WARN -> AppLogStore.w(tag, entry.message)
                    else -> AppLogStore.i(tag, entry.message)
                }
                YspEvents.recordCore(entry)
            }
            Log.setConsoleEnabled(false)
        }
    }
}
