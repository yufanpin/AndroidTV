package com.tivimatelite.web

import android.content.Context
import android.content.SharedPreferences
import com.tivimatelite.player.PlayerManager

/**
 * 音频增益持久化：Web 后台可调增益的落盘位置。
 *
 * 范围与默认值统一取自 [PlayerManager]（增益属于播放器语义，钳位规则只写一处），
 * 本类只负责读写 SharedPreferences，避免两端各写一套范围导致互相矛盾。
 */
object AudioGainPrefs {
    private const val PREFS_NAME = "audio_prefs"
    private const val KEY_GAIN = "gain"

    /**
     * 读取持久化增益；存储值缺失或越界时返回钳位后的值（不回写，
     * 由 [saveGain] 负责落盘，读路径保持无副作用）。
     */
    fun loadGain(context: Context): Float {
        val stored = getPrefs(context).getFloat(KEY_GAIN, PlayerManager.DEFAULT_AUDIO_GAIN)
        return PlayerManager.clampAudioGain(stored)
    }

    /** 落盘增益（先钳位再写），供 Web 后台 `/audio/gain` 调用。 */
    fun saveGain(context: Context, value: Float) {
        val clamped = PlayerManager.clampAudioGain(value)
        getPrefs(context).edit().putFloat(KEY_GAIN, clamped).apply()
        AppLogStore.i("AudioGainPrefs", "Audio gain set to $clamped")
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
