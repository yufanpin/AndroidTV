package com.tivimatelite.player

import android.content.Context

object PlaybackHistoryStore {
    private const val PREFS_NAME = "playback_history"
    private const val KEY_LAST_URL = "last_url"
    private const val KEY_LAST_CHANNEL_NAME = "last_channel_name"
    private const val KEY_LAST_SOURCE_INDEX = "last_source_index"

    fun saveLastPlayedUrl(context: Context, url: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_URL, null) == url) return
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }

    fun saveLastPlayedChannel(
        context: Context,
        channelName: String,
        sourceIndex: Int,
        url: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CHANNEL_NAME, channelName)
            .putInt(KEY_LAST_SOURCE_INDEX, sourceIndex)
            .putString(KEY_LAST_URL, url)
            .apply()
    }

    fun getLastPlayedUrl(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URL, null)
    }

    fun getLastPlayedState(context: Context): LastPlayedState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LastPlayedState(
            channelName = prefs.getString(KEY_LAST_CHANNEL_NAME, null),
            sourceIndex = prefs.getInt(KEY_LAST_SOURCE_INDEX, 0),
            url = prefs.getString(KEY_LAST_URL, null)
        )
    }

    data class LastPlayedState(
        val channelName: String?,
        val sourceIndex: Int,
        val url: String?
    )
}
