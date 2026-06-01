package com.tivimatelite.input

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.tivimatelite.web.AppLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InputHandler(
    private val scope: CoroutineScope,
    private val channelCountProvider: () -> Int,
    private val onChannelRequest: (Int) -> Unit,
    private val showOverlay: (String) -> Unit,
    private val hideOverlay: () -> Unit,
    private val onOutOfRange: (Int) -> Unit = { number ->
        AppLogStore.w(TAG, "Numeric channel out of range: $number")
    }
) {
    constructor(
        scope: CoroutineScope,
        channelCountProvider: () -> Int,
        onChannelRequest: (Int) -> Unit,
        channelNumberText: TextView
    ) : this(
        scope = scope,
        channelCountProvider = channelCountProvider,
        onChannelRequest = onChannelRequest,
        showOverlay = { text ->
            channelNumberText.text = text
            channelNumberText.visibility = View.VISIBLE
        },
        hideOverlay = {
            channelNumberText.visibility = View.GONE
        }
    )

    private val numericInputBuffer = StringBuilder(4)
    private var channelNumberHideJob: Job? = null
    private var numericCommitJob: Job? = null

    fun handleKeyCode(keyCode: Int): Boolean = handleNumericKey(keyCode)

    fun handleNumericKey(keyCode: Int): Boolean {
        val digit = keyCodeToDigit(keyCode) ?: return false
        if (numericInputBuffer.length >= MAX_NUMERIC_INPUT_DIGITS) numericInputBuffer.clear()
        numericInputBuffer.append(digit)
        showChannelNumberOverlay(numericInputBuffer.toString())

        numericCommitJob?.cancel()
        numericCommitJob = scope.launch {
            delay(NUMERIC_INPUT_COMMIT_MS)
            val number = numericInputBuffer.toString().toIntOrNull()
            numericInputBuffer.clear()
            val channelCount = channelCountProvider()
            if (number == null || number <= 0 || channelCount == 0) return@launch

            val targetIndex = number - 1
            if (targetIndex !in 0 until channelCount) {
                onOutOfRange(number)
                return@launch
            }
            onChannelRequest(targetIndex)
        }
        return true
    }

    fun showChannelNumberOverlay(text: String) {
        showOverlay(text)
        channelNumberHideJob?.cancel()
        channelNumberHideJob = scope.launch {
            delay(CHANNEL_NUMBER_HIDE_MS)
            hideOverlay()
        }
    }

    fun cancel() {
        channelNumberHideJob?.cancel()
        numericCommitJob?.cancel()
    }

    private fun keyCodeToDigit(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
            else -> null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val MAX_NUMERIC_INPUT_DIGITS = 4
        private const val CHANNEL_NUMBER_HIDE_MS = 1500L
        private const val NUMERIC_INPUT_COMMIT_MS = 900L
    }
}
