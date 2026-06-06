package com.tivimatelite.util

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object HttpFetcher {
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 7000
    private const val MAX_REDIRECT_HOPS = 3
    private val redirectCache = ConcurrentHashMap<String, String>()

    fun openConnection(url: String): HttpURLConnection {
        val targetUrl = getCachedRedirect(url) ?: url
        return (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
        }
    }

    fun getCachedRedirect(url: String): String? = redirectCache[url]

    fun invalidateRedirect(url: String) {
        redirectCache.remove(url)
    }

    fun resolveRedirectedUrl(url: String): String {
        getCachedRedirect(url)?.let { return it }

        var currentUrl = url
        repeat(MAX_REDIRECT_HOPS) {
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Range", "bytes=0-0")
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 300..399) {
                    if (currentUrl != url) {
                        redirectCache[url] = currentUrl
                    }
                    return currentUrl
                }

                val location = connection.getHeaderField("Location") ?: return currentUrl
                currentUrl = URL(URL(currentUrl), location).toString()
            } finally {
                connection.disconnect()
            }
        }

        if (currentUrl != url) {
            redirectCache[url] = currentUrl
        }
        return currentUrl
    }

    fun probePlayableUrl(url: String): String? {
        val resolvedUrl = runCatching { resolveRedirectedUrl(url) }.getOrElse { return null }
        val connection = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode in 200..299) resolvedUrl else null
        } finally {
            connection.disconnect()
        }
    }
}
