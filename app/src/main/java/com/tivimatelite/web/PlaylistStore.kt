package com.tivimatelite.web

import android.content.Context

object PlaylistStore {
    private const val PLAYLIST_FILE_NAME = "custom_playlist.m3u"

    fun saveCustomPlaylist(context: Context, content: String) {
        context.openFileOutput(PLAYLIST_FILE_NAME, Context.MODE_PRIVATE).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        }
        AppLogStore.i("PlaylistStore", "Custom playlist saved")
    }

    fun loadCustomPlaylist(context: Context): String? {
        return runCatching {
            context.openFileInput(PLAYLIST_FILE_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun loadEffectivePlaylist(context: Context): String {
        val custom = loadCustomPlaylist(context)
        if (!custom.isNullOrBlank()) return custom

        return runCatching {
            context.assets.open("channels.m3u").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    fun hasCustomPlaylist(context: Context): Boolean {
        val custom = loadCustomPlaylist(context)
        return !custom.isNullOrBlank()
    }
}
