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
}
