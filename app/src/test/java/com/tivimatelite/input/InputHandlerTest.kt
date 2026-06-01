package com.tivimatelite.input

import android.view.KeyEvent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputHandlerTest {

    @Test
    fun `numeric input buffers digits then requests channel after delay`() = runTest {
        val overlays = mutableListOf<String>()
        val requests = mutableListOf<Int>()
        val handler = InputHandler(
            scope = this,
            channelCountProvider = { 20 },
            onChannelRequest = requests::add,
            showOverlay = overlays::add,
            hideOverlay = {}
        )

        assertTrue(handler.handleNumericKey(KeyEvent.KEYCODE_1))
        assertTrue(handler.handleNumericKey(KeyEvent.KEYCODE_2))
        advanceTimeBy(899)
        runCurrent()

        assertEquals(listOf("1", "12"), overlays)
        assertEquals(emptyList<Int>(), requests)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(11), requests)
    }

    @Test
    fun `numeric input resets after four digits and keeps numpad mapping`() = runTest {
        val overlays = mutableListOf<String>()
        val requests = mutableListOf<Int>()
        val handler = InputHandler(
            scope = this,
            channelCountProvider = { 100 },
            onChannelRequest = requests::add,
            showOverlay = overlays::add,
            hideOverlay = {}
        )

        handler.handleNumericKey(KeyEvent.KEYCODE_1)
        handler.handleNumericKey(KeyEvent.KEYCODE_2)
        handler.handleNumericKey(KeyEvent.KEYCODE_3)
        handler.handleNumericKey(KeyEvent.KEYCODE_4)
        handler.handleNumericKey(KeyEvent.KEYCODE_NUMPAD_5)
        advanceTimeBy(900)
        runCurrent()

        assertEquals(listOf("1", "12", "123", "1234", "5"), overlays)
        assertEquals(listOf(4), requests)
    }

    @Test
    fun `out of range zero empty and unknown keys do not request channel`() = runTest {
        val overlays = mutableListOf<String>()
        val requests = mutableListOf<Int>()
        val handler = InputHandler(
            scope = this,
            channelCountProvider = { 3 },
            onChannelRequest = requests::add,
            showOverlay = overlays::add,
            hideOverlay = {}
        )

        assertTrue(handler.handleNumericKey(KeyEvent.KEYCODE_0))
        advanceTimeBy(900)
        runCurrent()
        assertTrue(handler.handleNumericKey(KeyEvent.KEYCODE_9))
        advanceTimeBy(900)
        runCurrent()

        assertEquals(false, handler.handleNumericKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("0", "9"), overlays)
        assertEquals(emptyList<Int>(), requests)
    }

    @Test
    fun `overlay hides after unchanged delay`() = runTest {
        val overlays = mutableListOf<String>()
        var hideCount = 0
        val handler = InputHandler(
            scope = this,
            channelCountProvider = { 10 },
            onChannelRequest = {},
            showOverlay = overlays::add,
            hideOverlay = { hideCount += 1 }
        )

        handler.showChannelNumberOverlay("7")
        advanceTimeBy(1499)
        runCurrent()
        assertEquals(0, hideCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("7"), overlays)
        assertEquals(1, hideCount)
    }
}
