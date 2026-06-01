package com.tivimatelite

import org.junit.Assert.assertTrue
import org.junit.Test

class SplashSourceTest {

    @Test
    fun splashLayout_has_non_black_first_frame_content_state() {
        val source = java.io.File(
            "src/main/res/layout/activity_splash.xml"
        ).readText()

        assertTrue(source.contains("android:alpha="))
        assertTrue(source.contains("android:scaleX="))
        assertTrue(source.contains("android:scaleY="))
    }

    @Test
    fun splashActivity_posts_animation_after_first_layout() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/SplashActivity.kt"
        ).readText()

        assertTrue(source.contains("binding.splashContent.post"))
    }
}
