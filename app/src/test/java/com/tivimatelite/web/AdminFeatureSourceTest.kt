package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class AdminFeatureSourceTest {

    @Test
    fun localAdminServer_containsBootToggleAndPasteContentRoutes() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/LocalAdminServer.kt"
        ).readText()

        assertTrue(source.contains("/boot/toggle"))
        assertTrue(source.contains("/profile/buffer"))
        assertTrue(source.contains("/profile/decoder"))
        assertTrue(source.contains("/source/add-content"))
        assertTrue(source.contains("开机自动启动 App"))
        assertTrue(source.contains("缓冲策略"))
        assertTrue(source.contains("解码回退策略"))
        assertTrue(source.contains("直接粘贴直播源内容"))
        assertTrue(source.contains("可选方式"))
        assertTrue(source.contains("<details"))
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
    fun manifest_containsBootReceiverPermissionAndRegistration() {
        val source = java.io.File(
            "src/main/AndroidManifest.xml"
        ).readText()

        assertTrue(source.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(source.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(source.contains(".BootReceiver"))
    }
}
