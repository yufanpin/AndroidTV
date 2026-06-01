package com.tivimatelite.loader

import android.content.Context
import com.tivimatelite.data.RemotePlaylistRepository
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import com.tivimatelite.player.PlaybackHistoryStore
import kotlinx.coroutines.flow.collect
import java.io.FileNotFoundException

data class ChannelGroup(
    val name: String,
    val sources: List<String>
)

class ChannelLoader(
    private val loadRemoteChannels: suspend () -> RemotePlaylistRepository.RemotePlaylistResult? = {
        RemotePlaylistRepository.loadChannels()
    },
    private val loadLocalChannels: suspend () -> List<Channel>,
    private val getLastPlayedState: () -> PlaybackHistoryStore.LastPlayedState
) {
    constructor(context: Context) : this(
        loadLocalChannels = {
            try {
                val channels = ArrayList<Channel>(512)
                context.assets.open("channels.m3u").use { input ->
                    M3U8Parser.parse(input).collect { channel -> channels.add(channel) }
                }
                channels
            } catch (_: FileNotFoundException) {
                emptyList()
            }
        },
        getLastPlayedState = { PlaybackHistoryStore.getLastPlayedState(context) }
    )

    suspend fun loadInitial(): LoadResult? {
        val loaded = loadRows() ?: return null
        val channelGroups = groupChannels(loaded.channels)
        if (channelGroups.isEmpty()) return null

        return LoadResult(
            channelGroups = channelGroups,
            selection = restoreSelection(channelGroups, getLastPlayedState()),
            activePlaylistSource = loaded.activePlaylistSource,
            fallbackChannelName = null
        )
    }

    suspend fun reloadKeepingCurrent(
        currentGroups: List<ChannelGroup>,
        currentChannelIndex: Int,
        currentSourceIndex: Int
    ): LoadResult? {
        val loaded = loadRows() ?: return null
        val channelGroups = groupChannels(loaded.channels)
        if (channelGroups.isEmpty()) return null

        val previousGroup = currentGroups.getOrNull(currentChannelIndex)
        val previousChannelName = previousGroup?.name
        val previousSourceUrl = previousGroup?.sources?.getOrNull(currentSourceIndex)

        val byUrlIndex = previousSourceUrl?.let { url ->
            channelGroups.indexOfFirst { group -> url in group.sources }
        } ?: -1
        if (byUrlIndex >= 0) {
            return LoadResult(
                channelGroups = channelGroups,
                selection = Selection(
                    channelIndex = byUrlIndex,
                    sourceIndex = channelGroups[byUrlIndex].sources.indexOf(previousSourceUrl).coerceAtLeast(0)
                ),
                activePlaylistSource = loaded.activePlaylistSource,
                fallbackChannelName = null
            )
        }

        val byNameIndex = previousChannelName?.let { name ->
            channelGroups.indexOfFirst { group -> group.name == name }
        } ?: -1
        if (byNameIndex >= 0) {
            return LoadResult(
                channelGroups = channelGroups,
                selection = Selection(channelIndex = byNameIndex, sourceIndex = 0),
                activePlaylistSource = loaded.activePlaylistSource,
                fallbackChannelName = null
            )
        }

        return LoadResult(
            channelGroups = channelGroups,
            selection = Selection(channelIndex = 0, sourceIndex = 0),
            activePlaylistSource = loaded.activePlaylistSource,
            fallbackChannelName = channelGroups.first().name
        )
    }

    private suspend fun loadRows(): LoadedRows? {
        val remoteResult = loadRemoteChannels()
        if (remoteResult != null) {
            return LoadedRows(
                channels = remoteResult.channels,
                activePlaylistSource = remoteResult.activeSourceLabel
            )
        }

        return LoadedRows(
            channels = loadLocalChannels(),
            activePlaylistSource = "local assets/channels.m3u"
        )
    }

    private fun groupChannels(rows: List<Channel>): List<ChannelGroup> {
        val grouped = LinkedHashMap<String, LinkedHashSet<String>>(rows.size)
        for (row in rows) {
            val name = row.name.trim()
            val url = row.streamUrl.trim()
            if (name.isEmpty() || url.isEmpty()) continue
            grouped.getOrPut(name) { LinkedHashSet(4) }.add(url)
        }
        return grouped.entries.map { ChannelGroup(it.key, it.value.toList()) }
    }

    private fun restoreSelection(
        channelGroups: List<ChannelGroup>,
        lastState: PlaybackHistoryStore.LastPlayedState
    ): Selection {
        val byUrlIndex = lastState.url?.let { savedUrl ->
            channelGroups.indexOfFirst { group -> savedUrl in group.sources }
        } ?: -1
        if (byUrlIndex >= 0) {
            return Selection(
                channelIndex = byUrlIndex,
                sourceIndex = channelGroups[byUrlIndex].sources.indexOf(lastState.url).coerceAtLeast(0)
            )
        }

        val byNameIndex = lastState.channelName?.let { savedName ->
            channelGroups.indexOfFirst { it.name == savedName }
        } ?: -1
        if (byNameIndex >= 0) {
            return Selection(
                channelIndex = byNameIndex,
                sourceIndex = lastState.sourceIndex.coerceIn(0, channelGroups[byNameIndex].sources.lastIndex)
            )
        }

        return Selection(channelIndex = 0, sourceIndex = 0)
    }

    data class Selection(
        val channelIndex: Int,
        val sourceIndex: Int
    )

    data class LoadResult(
        val channelGroups: List<ChannelGroup>,
        val selection: Selection,
        val activePlaylistSource: String,
        val fallbackChannelName: String?
    )

    private data class LoadedRows(
        val channels: List<Channel>,
        val activePlaylistSource: String
    )
}
