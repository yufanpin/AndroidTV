package com.tivimatelite.web

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object PlaylistStore {
    private const val PREFS_NAME = "playlist_admin"
    private const val KEY_MODE = "mode"
    private const val KEY_SOURCES_JSON = "sources_json"
    private const val KEY_SELECTED_ID = "selected_id"

    private const val MODE_BUILTIN = "builtin"
    private const val MODE_CUSTOM = "custom"

    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 7000

    data class CustomSource(
        val id: String,
        val name: String,
        val url: String
    )

    data class EffectivePlaylist(
        val content: String,
        val mode: String,
        val activeSourceLabel: String
    )

    fun setModeBuiltin(context: Context) = setMode(context, MODE_BUILTIN)
    fun setModeCustom(context: Context) = setMode(context, MODE_CUSTOM)

    fun isCustomMode(context: Context): Boolean {
        return getPrefs(context).getString(KEY_MODE, MODE_BUILTIN) == MODE_CUSTOM
    }

    fun addCustomSource(context: Context, name: String, url: String) {
        val trimmedName = name.trim().ifEmpty { "自定义源" }
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return

        val sources = getCustomSources(context).toMutableList()
        val source = CustomSource(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            url = trimmedUrl
        )
        sources.add(source)
        saveCustomSources(context, sources)

        val selected = getSelectedSourceId(context)
        if (selected == null) setSelectedSourceId(context, source.id)
        AppLogStore.i("PlaylistStore", "新增自定义源: ${source.name}")
    }

    fun deleteCustomSource(context: Context, id: String) {
        val sources = getCustomSources(context).filterNot { it.id == id }
        saveCustomSources(context, sources)

        val selected = getSelectedSourceId(context)
        if (selected == id) {
            setSelectedSourceId(context, sources.firstOrNull()?.id)
        }
        AppLogStore.i("PlaylistStore", "删除自定义源: $id")
    }

    fun selectCustomSource(context: Context, id: String) {
        val exists = getCustomSources(context).any { it.id == id }
        if (!exists) return
        setSelectedSourceId(context, id)
        AppLogStore.i("PlaylistStore", "启用自定义源: $id")
    }

    fun getCustomSources(context: Context): List<CustomSource> {
        val raw = getPrefs(context).getString(KEY_SOURCES_JSON, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())

        val result = ArrayList<CustomSource>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (id.isEmpty() || url.isEmpty()) continue
            result.add(CustomSource(id, if (name.isEmpty()) "自定义源" else name, url))
        }
        return result
    }

    fun getSelectedSourceId(context: Context): String? {
        return getPrefs(context).getString(KEY_SELECTED_ID, null)
    }

    fun getConfigFingerprint(context: Context): String {
        val prefs = getPrefs(context)
        val mode = prefs.getString(KEY_MODE, MODE_BUILTIN).orEmpty()
        val selectedId = prefs.getString(KEY_SELECTED_ID, "").orEmpty()
        val sourcesJson = prefs.getString(KEY_SOURCES_JSON, "[]").orEmpty()
        return "$mode|$selectedId|$sourcesJson"
    }

    fun loadEffectivePlaylist(context: Context): EffectivePlaylist {
        if (isCustomMode(context)) {
            val sources = getCustomSources(context)
            val selectedId = getSelectedSourceId(context)
            val selected = sources.firstOrNull { it.id == selectedId }
            if (selected != null) {
                val remote = loadFromUrl(selected.url)
                if (!remote.isNullOrBlank()) {
                    return EffectivePlaylist(
                        content = remote,
                        mode = MODE_CUSTOM,
                        activeSourceLabel = "自定义: ${selected.name}"
                    )
                }
                AppLogStore.w("PlaylistStore", "自定义源拉取失败: ${selected.url}")
            }
        }

        val builtin = loadBuiltinPlaylist(context)
        return EffectivePlaylist(
            content = builtin,
            mode = MODE_BUILTIN,
            activeSourceLabel = "内置源"
        )
    }

    private fun loadBuiltinPlaylist(context: Context): String {
        return runCatching {
            context.assets.open("channels.m3u").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private fun loadFromUrl(url: String): String? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun saveCustomSources(context: Context, sources: List<CustomSource>) {
        val array = JSONArray()
        for (source in sources) {
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("url", source.url)
            )
        }
        getPrefs(context).edit().putString(KEY_SOURCES_JSON, array.toString()).apply()
    }

    private fun setMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_MODE, mode).apply()
        AppLogStore.i("PlaylistStore", "切换模式: $mode")
    }

    private fun setSelectedSourceId(context: Context, id: String?) {
        getPrefs(context).edit().putString(KEY_SELECTED_ID, id).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
