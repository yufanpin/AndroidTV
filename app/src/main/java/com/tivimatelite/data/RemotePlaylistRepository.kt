package com.tivimatelite.data

import android.util.Log
import com.tivimatelite.BuildConfig
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

object RemotePlaylistRepository {
    private const val TAG = "RemotePlaylistRepo"

    suspend fun loadChannels(): List<Channel>? = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.PLAYLIST_URL.trim()
        if (endpoint.isEmpty()) return@withContext null

        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
                requestMethod = "GET"
                useCaches = false
            }

            connection.inputStream.use { input ->
                val channels = ArrayList<Channel>(512)
                M3U8Parser.parse(input).collect { channel ->
                    channels.add(channel)
                }
                channels
            }
        }.onFailure {
            Log.w(TAG, "Remote playlist load failed", it)
        }.getOrNull()
    }
}
