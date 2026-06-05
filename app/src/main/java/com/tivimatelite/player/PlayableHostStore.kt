package com.tivimatelite.player

import android.content.Context

/**
 * 智能线路记忆存储（P0）
 *
 * 记录播放成功的域名，下次优先选择。
 * 播放失败时移除该域名。
 */
object PlayableHostStore {
    private const val PREFS_NAME = "playable_hosts"

    fun addHost(context: Context, url: String) {
        val host = extractHost(url) ?: return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hosts = prefs.getStringSet("hosts", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (hosts.add(host)) {
            prefs.edit().putStringSet("hosts", hosts).apply()
        }
    }

    fun removeHost(context: Context, url: String) {
        val host = extractHost(url) ?: return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hosts = prefs.getStringSet("hosts", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (hosts.remove(host)) {
            prefs.edit().putStringSet("hosts", hosts).apply()
        }
    }

    fun getHosts(context: Context): Set<String> {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet("hosts", emptySet()) ?: emptySet()
    }

    /** 从 URL 中提取域名 */
    fun extractHost(url: String): String? {
        return try {
            val withoutProtocol = url.substringAfter("://")
            withoutProtocol.substringBefore("/").substringBefore(":").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
