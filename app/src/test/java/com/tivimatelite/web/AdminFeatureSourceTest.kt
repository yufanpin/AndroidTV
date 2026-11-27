package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class AdminFeatureSourceTest {

    @Test
    fun localAdminServer_containsYspConsoleRoutes() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/LocalAdminServer.kt"
        ).readText()

        assertTrue(source.contains("/api/status"))
        assertTrue(source.contains("/api/events"))
        assertTrue(source.contains("/api/logs"))
        assertTrue(source.contains("/config"))
        assertTrue(source.contains("/engine/restart"))
        assertTrue(source.contains("/boot/toggle"))
        assertTrue(source.contains("MIGU TV"))
        // ysp 设备协议遗留项已从 migu 后台移除
        assertTrue(!source.contains("高码率"))
        assertTrue(!source.contains("事件流"))
        assertTrue(!source.contains("GUID"))
    }

    @Test
    fun playlistStore_containsPastedContentSupport() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/PlaylistStore.kt"
        ).readText()

        assertTrue(source.contains("addCustomSourceWithContent"))
        assertTrue(source.contains("KEY_PASTED_CONTENT_JSON"))
        assertTrue(source.contains("loadFromStoredContent"))
        assertTrue(source.contains("savePastedContent"))
    }

    @Test
    fun localAdminServer_exposesAdjustableAudioGain() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/LocalAdminServer.kt"
        ).readText()

        assertTrue(source.contains("/audio/gain"))
        assertTrue(source.contains("音频增益"))
        assertTrue(source.contains("type=\"range\""))
        assertTrue(source.contains("name=\"gain\""))
    }

    @Test
    fun audioGainPrefs_persistsClampedGainViaPlayerManager() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/AudioGainPrefs.kt"
        ).readText()

        assertTrue(source.contains("audio_prefs"))
        assertTrue(source.contains("\"gain\""))
        assertTrue(source.contains("fun loadGain"))
        assertTrue(source.contains("fun saveGain"))
        assertTrue(source.contains("PlayerManager.clampAudioGain"))
    }

    @Test
    fun manifest_containsBootReceiverPermissionAndRegistration() {
        val source = java.io.File(
            "src/main/AndroidManifest.xml"
        ).readText()

        assertTrue(source.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(source.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(source.contains(".BootReceiver"))
    }
}
