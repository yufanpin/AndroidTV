package com.tivimatelite.player

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerSourceTest {

    private val source: String by lazy {
        java.io.File("src/main/java/com/tivimatelite/player/PlayerManager.kt").readText()
    }

    @Test
    fun source_uses_ijkplayer_instead_of_media3() {
        assertTrue(source.contains("IjkMediaPlayer"))
        assertTrue(source.contains("tv.danmaku.ijk.media.player"))
    }

    @Test
    fun source_copies_diyp_player_options() {
        // PLAYER(4)
        assertTrue(source.contains("OPT_CATEGORY_PLAYER, \"mediacodec\""))
        assertTrue(source.contains("OPT_CATEGORY_PLAYER, \"framedrop\""))
        assertTrue(source.contains("OPT_CATEGORY_PLAYER, \"packet-buffering\""))
        assertTrue(source.contains("OPT_CATEGORY_PLAYER, \"overlay-format\""))
    }

    @Test
    fun source_copies_diyp_format_options() {
        // FORMAT(1)
        assertTrue(source.contains("OPT_CATEGORY_FORMAT, \"user_agent\""))
        assertTrue(source.contains("OPT_CATEGORY_FORMAT, \"reconnect\""))
        assertTrue(source.contains("OPT_CATEGORY_FORMAT, \"probesize\""))
        assertTrue(source.contains("OPT_CATEGORY_FORMAT, \"analyzeduration\""))
        // CODEC(2)
        assertTrue(source.contains("OPT_CATEGORY_CODEC, \"skip_loop_filter\""))
    }

    @Test
    fun source_sets_diy_user_agent() {
        assertTrue(source.contains("\"SYTV/1.6\""))
    }

    @Test
    fun source_enables_avfilter_audio_gain() {
        // 音频增益：avfilter 链 volume(提增益) + alimiter(砖墙限幅，防削波)
        assertTrue(source.contains("OPT_CATEGORY_PLAYER, \"af\""))
        assertTrue(source.contains("volume=%s"))
        assertTrue(source.contains("alimiter=limit=0.95"))
    }

    @Test
    fun source_exposes_clamped_adjustable_audio_gain() {
        // 后台可调：范围常量 + 钳位纯函数 + 唯一写入入口
        assertTrue(source.contains("AUDIO_GAIN_MIN"))
        assertTrue(source.contains("AUDIO_GAIN_MAX"))
        assertTrue(source.contains("fun clampAudioGain"))
        assertTrue(source.contains("fun setAudioGain"))
        assertTrue(source.contains("coerceIn(AUDIO_GAIN_MIN, AUDIO_GAIN_MAX)"))
    }
}
