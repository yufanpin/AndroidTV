package com.tivimatelite.web

import android.content.Context
import com.tivimatelite.player.PlayerManager

object PlaybackTuningPrefs {
    private const val PREFS_NAME = "playback_tuning_prefs"
    private const val KEY_BUFFER_PROFILE = "buffer_profile"
    private const val KEY_DECODER_FALLBACK_POLICY = "decoder_fallback_policy"

    fun getBufferProfile(context: Context): PlayerManager.BufferProfile {
        val value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BUFFER_PROFILE, PlayerManager.BufferProfile.BALANCED.name)
            .orEmpty()
        return PlayerManager.BufferProfile.entries.firstOrNull { it.name == value }
            ?: PlayerManager.BufferProfile.BALANCED
    }

    fun setBufferProfile(context: Context, profile: PlayerManager.BufferProfile) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUFFER_PROFILE, profile.name)
            .apply()
        AppLogStore.i("PlaybackTuningPrefs", "Buffer profile set to ${profile.name}")
    }

    fun getDecoderFallbackPolicy(context: Context): PlayerManager.DecoderFallbackPolicy {
        val value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DECODER_FALLBACK_POLICY, PlayerManager.DecoderFallbackPolicy.HW_ONLY.name)
            .orEmpty()
        return PlayerManager.DecoderFallbackPolicy.entries.firstOrNull { it.name == value }
            ?: PlayerManager.DecoderFallbackPolicy.HW_ONLY
    }

    fun setDecoderFallbackPolicy(context: Context, policy: PlayerManager.DecoderFallbackPolicy) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DECODER_FALLBACK_POLICY, policy.name)
            .apply()
        AppLogStore.i("PlaybackTuningPrefs", "Decoder fallback policy set to ${policy.name}")
    }
}
