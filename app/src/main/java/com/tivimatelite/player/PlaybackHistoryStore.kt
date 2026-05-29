package com.tivimatelite.player

import android.content.Context

object PlaybackHistoryStore {
    private const val PREFS_NAME = "playback_history"
    private const val KEY_LAST_URL = "last_url"

    fun saveLastPlayedUrl(context: Context, url: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_URL, null) == url) return
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }

    fun getLastPlayedUrl(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URL, null)
    }
}
