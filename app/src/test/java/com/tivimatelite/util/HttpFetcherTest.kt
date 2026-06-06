package com.tivimatelite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection

class HttpFetcherTest {

    @Test
    fun `openConnection returns HttpURLConnection with correct configuration`() {
        val url = "http://example.com/test.m3u"
        val conn = HttpFetcher.openConnection(url)

        assertNotNull(conn)
        assertTrue(conn is HttpURLConnection)
        assertEquals(3500, conn.connectTimeout)
        assertEquals(7000, conn.readTimeout)
        assertEquals("GET", conn.requestMethod)
        assertFalse(conn.useCaches)
    }

    @Test
    fun `source_contains_redirect_cache_and_probe_logic`() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/util/HttpFetcher.kt"
        ).readText()

        assertTrue(source.contains("ConcurrentHashMap<String, String>()"))
        assertTrue(source.contains("private const val MAX_REDIRECT_HOPS = 3"))
        assertTrue(source.contains("fun getCachedRedirect(url: String): String?"))
        assertTrue(source.contains("fun resolveRedirectedUrl(url: String): String"))
        assertTrue(source.contains("fun probePlayableUrl(url: String): String?"))
        assertTrue(source.contains("setRequestProperty(\"Range\", \"bytes=0-0\")"))
    }
}
