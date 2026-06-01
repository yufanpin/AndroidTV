package com.tivimatelite.switcher

import com.tivimatelite.loader.ChannelGroup
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSwitcherTest {

    @Test
    fun `requestSwitchByDelta debounces and switches to computed target`() = runTest {
        var currentChannelIndex = 1
        var currentSourceIndex = 0
        val overlays = mutableListOf<String>()
        val plays = mutableListOf<Pair<Int, Boolean>>()

        val switcher = ChannelSwitcher(
            scope = this,
            getChannelGroups = {
                listOf(
                    ChannelGroup("A", listOf("a")),
                    ChannelGroup("B", listOf("b")),
                    ChannelGroup("C", listOf("c"))
                )
            },
            getCurrentChannelIndex = { currentChannelIndex },
            setCurrentChannelIndex = { currentChannelIndex = it },
            getCurrentSourceIndex = { currentSourceIndex },
            setCurrentSourceIndex = { currentSourceIndex = it },
            getActivePlaylistSource = { "remote" },
            showChannelNumberOverlay = overlays::add,
            onReadyStallWarmup = {},
            savePlayback = { _, _, _ -> },
            playUrl = { _, forceHls -> plays += currentChannelIndex to forceHls },
            logInfo = { _ -> },
            logWarning = { _ -> },
            logError = { _ -> },
            getNowMs = { 0L },
            isPlayerBufferingAndPlaying = { false }
        )

        switcher.requestSwitchByDelta(1)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1, currentChannelIndex)
        assertEquals(emptyList<Pair<Int, Boolean>>(), plays)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, currentChannelIndex)
        assertEquals(listOf("3", "3"), overlays)
        assertEquals(listOf(2 to false), plays)
    }

    @Test
    fun `playNextSourceForCurrentChannel advances through sources and retries single source`() = runTest {
        var currentChannelIndex = 0
        var currentSourceIndex = 0
        val warnings = mutableListOf<String>()
        val plays = mutableListOf<Pair<Int, Boolean>>()
        var nowMs = 1000L

        val switcher = ChannelSwitcher(
            scope = this,
            getChannelGroups = {
                listOf(
                    ChannelGroup("A", listOf("a1", "a2")),
                    ChannelGroup("B", listOf("b1"))
                )
            },
            getCurrentChannelIndex = { currentChannelIndex },
            setCurrentChannelIndex = { currentChannelIndex = it },
            getCurrentSourceIndex = { currentSourceIndex },
            setCurrentSourceIndex = { currentSourceIndex = it },
            getActivePlaylistSource = { "remote" },
            showChannelNumberOverlay = {},
            onReadyStallWarmup = {},
            savePlayback = { _, _, _ -> },
            playUrl = { _, forceHls -> plays += currentSourceIndex to forceHls },
            logInfo = { _ -> },
            logWarning = warnings::add,
            logError = { _ -> },
            getNowMs = { nowMs },
            isPlayerBufferingAndPlaying = { false }
        )

        switcher.playCurrentSource(resetAttempts = true)
        switcher.playNextSourceForCurrentChannel("player_error")
        assertEquals(1, currentSourceIndex)
        assertEquals(listOf(0 to false, 1 to false), plays)

        currentChannelIndex = 1
        currentSourceIndex = 0
        nowMs += 20000L
        switcher.playCurrentSource(resetAttempts = true)
        switcher.playNextSourceForCurrentChannel("single_source")
        advanceTimeBy(5000)
        runCurrent()
        assertTrue(warnings.any { it.contains("Single-source retry") })
        assertEquals(listOf(0 to false, 1 to false, 0 to false, 0 to false), plays)
    }

    @Test
    fun `force hls retry happens once per source and buffering failover respects player state`() = runTest {
        var currentChannelIndex = 0
        var currentSourceIndex = 0
        var buffering = true
        val warnings = mutableListOf<String>()
        val plays = mutableListOf<Pair<Int, Boolean>>()

        val switcher = ChannelSwitcher(
            scope = this,
            getChannelGroups = {
                listOf(ChannelGroup("A", listOf("a1", "a2")))
            },
            getCurrentChannelIndex = { currentChannelIndex },
            setCurrentChannelIndex = { currentChannelIndex = it },
            getCurrentSourceIndex = { currentSourceIndex },
            setCurrentSourceIndex = { currentSourceIndex = it },
            getActivePlaylistSource = { "remote" },
            showChannelNumberOverlay = {},
            onReadyStallWarmup = {},
            savePlayback = { _, _, _ -> },
            playUrl = { _, forceHls -> plays += currentSourceIndex to forceHls },
            logInfo = { _ -> },
            logWarning = warnings::add,
            logError = { _ -> },
            getNowMs = { 0L },
            isPlayerBufferingAndPlaying = { buffering }
        )

        switcher.playCurrentSource(resetAttempts = true)
        assertTrue(switcher.tryForceHlsForCurrentSource(RuntimeException("UnrecognizedInputFormatException")))
        assertFalse(switcher.tryForceHlsForCurrentSource(RuntimeException("UnrecognizedInputFormatException")))

        switcher.scheduleBufferingFailover()
        advanceTimeBy(35000)
        runCurrent()
        assertEquals(listOf(0 to false, 0 to true, 1 to false), plays)

        buffering = false
        switcher.scheduleBufferingFailover()
        advanceTimeBy(35000)
        runCurrent()
        assertEquals(listOf(0 to false, 0 to true, 1 to false), plays)
        assertTrue(warnings.any { it.contains("Retrying as HLS") })
    }
}
