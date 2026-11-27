package com.tivimatelite.ysp

import com.tivimatelite.data.RemotePlaylistRepository
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import com.tivimatelite.util.HttpFetcher
import com.tivimatelite.web.AppLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** ysp 分支唯一频道来源：本机引擎的 /all.m3u。 */
object EnginePlaylistSource {

    private const val TAG = "EnginePlaylist"

    suspend fun load(): RemotePlaylistRepository.RemotePlaylistResult? =
        withContext(Dispatchers.IO) {
            val url = YspEngine.playlistUrl()
            if (url.isEmpty()) {
                AppLogStore.w(TAG, "引擎尚未就绪，暂无播放列表")
                return@withContext null
            }
            runCatching {
                val connection = HttpFetcher.openConnection(url)
                connection.inputStream.use { input ->
                    val channels = ArrayList<Channel>(128)
                    M3U8Parser.parse(input).collect { channels.add(it) }
                    if (channels.isEmpty()) {
                        null
                    } else {
                        RemotePlaylistRepository.RemotePlaylistResult(
                            sourceUrl = url,
                            activeSourceLabel = "咪咕本机引擎",
                            channels = channels
                        )
                    }
                }
            }.onFailure {
                AppLogStore.w(TAG, "拉取引擎播放列表失败: $url", it)
            }.getOrNull()
        }
}
