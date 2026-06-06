package com.tivimatelite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

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
    fun `resolveRedirectedUrl follows local redirect and caches final url`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/redirect", HttpHandler { exchange: HttpExchange ->
            exchange.responseHeaders.add("Location", "/final")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        })
        server.createContext("/final", HttpHandler { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        })
        server.start()

        try {
            val url = "http://127.0.0.1:${server.address.port}/redirect"
            val finalUrl = HttpFetcher.resolveRedirectedUrl(url)
            assertEquals("http://127.0.0.1:${server.address.port}/final", finalUrl)
            assertEquals(finalUrl, HttpFetcher.getCachedRedirect(url))
        } finally {
            server.stop(0)
        }
    }
}
