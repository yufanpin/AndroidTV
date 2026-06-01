package com.tivimatelite.loader

import com.tivimatelite.data.RemotePlaylistRepository
import com.tivimatelite.model.Channel
import com.tivimatelite.player.PlaybackHistoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLoaderTest {

    @Test
    fun `loadInitial groups remote channels and restores saved selection`() = runTest {
        val loader = ChannelLoader(
            loadRemoteChannels = {
                RemotePlaylistRepository.RemotePlaylistResult(
                    sourceUrl = "http://remote",
                    activeSourceLabel = "remote backend",
                    channels = listOf(
                        Channel("CCTV 1", null, null, "url-1a"),
                        Channel("CCTV 1", null, null, "url-1b"),
                        Channel("CCTV 1", null, null, "url-1a"),
                        Channel("CCTV 2", null, null, "url-2")
                    )
                )
            },
            loadLocalChannels = { error("local should not be used") },
            getLastPlayedState = {
                PlaybackHistoryStore.LastPlayedState(
                    channelName = "CCTV 1",
                    sourceIndex = 0,
                    url = "url-1b"
                )
            }
        )

        val result = loader.loadInitial()

        assertNotNull(result)
        result!!
        assertEquals("remote backend", result.activePlaylistSource)
        assertEquals(2, result.channelGroups.size)
        assertEquals("CCTV 1", result.channelGroups[0].name)
        assertEquals(listOf("url-1a", "url-1b"), result.channelGroups[0].sources)
        assertEquals(0, result.selection.channelIndex)
        assertEquals(1, result.selection.sourceIndex)
    }

    @Test
    fun `loadInitial falls back to local asset label when remote is unavailable`() = runTest {
        val loader = ChannelLoader(
            loadRemoteChannels = { null },
            loadLocalChannels = {
                listOf(
                    Channel("Local 1", null, null, "local-url")
                )
            },
            getLastPlayedState = { PlaybackHistoryStore.LastPlayedState(null, 0, null) }
        )

        val result = loader.loadInitial()

        assertNotNull(result)
        assertEquals("local assets/channels.m3u", result!!.activePlaylistSource)
        assertEquals(0, result.selection.channelIndex)
        assertEquals(0, result.selection.sourceIndex)
    }

    @Test
    fun `reloadKeepingCurrent prefers previous source url then channel name then first group`() = runTest {
        val currentGroups = listOf(
            ChannelGroup("CCTV 1", listOf("url-1a", "url-1b")),
            ChannelGroup("CCTV 2", listOf("url-2"))
        )

        val byUrlLoader = ChannelLoader(
            loadRemoteChannels = { null },
            loadLocalChannels = {
                listOf(
                    Channel("CCTV 1", null, null, "url-1c"),
                    Channel("CCTV 1", null, null, "url-1b"),
                    Channel("CCTV 3", null, null, "url-3")
                )
            },
            getLastPlayedState = { PlaybackHistoryStore.LastPlayedState(null, 0, null) }
        )
        val byUrl = byUrlLoader.reloadKeepingCurrent(currentGroups, currentChannelIndex = 0, currentSourceIndex = 1)
        assertEquals(0, byUrl!!.selection.channelIndex)
        assertEquals(1, byUrl.selection.sourceIndex)
        assertNull(byUrl.fallbackChannelName)

        val byNameLoader = ChannelLoader(
            loadRemoteChannels = { null },
            loadLocalChannels = {
                listOf(
                    Channel("CCTV 1", null, null, "url-1c"),
                    Channel("CCTV 3", null, null, "url-3")
                )
            },
            getLastPlayedState = { PlaybackHistoryStore.LastPlayedState(null, 0, null) }
        )
        val byName = byNameLoader.reloadKeepingCurrent(currentGroups, currentChannelIndex = 0, currentSourceIndex = 1)
        assertEquals(0, byName!!.selection.channelIndex)
        assertEquals(0, byName.selection.sourceIndex)
        assertNull(byName.fallbackChannelName)

        val fallbackLoader = ChannelLoader(
            loadRemoteChannels = { null },
            loadLocalChannels = {
                listOf(
                    Channel("CCTV 9", null, null, "url-9")
                )
            },
            getLastPlayedState = { PlaybackHistoryStore.LastPlayedState(null, 0, null) }
        )
        val fallback = fallbackLoader.reloadKeepingCurrent(currentGroups, currentChannelIndex = 0, currentSourceIndex = 1)
        assertEquals(0, fallback!!.selection.channelIndex)
        assertEquals(0, fallback.selection.sourceIndex)
        assertEquals("CCTV 9", fallback.fallbackChannelName)
    }
}
