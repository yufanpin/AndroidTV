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
}
