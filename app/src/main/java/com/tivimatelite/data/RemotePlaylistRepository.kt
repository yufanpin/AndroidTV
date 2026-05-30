package com.tivimatelite.data

import android.util.Log
import com.tivimatelite.BuildConfig
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import com.tivimatelite.web.AppLogStore
import com.tivimatelite.web.LocalAdminServerManager
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

object RemotePlaylistRepository {
    private const val TAG = "RemotePlaylistRepo"
    private const val BACKEND_PORT = 5220
    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 7000

    @Volatile
    private var lastProbeInfo = PlaylistProbeInfo(
        localIp = null,
        triedUrls = emptyList(),
        selectedUrl = null
    )

    suspend fun loadChannels(): RemotePlaylistResult? = withContext(Dispatchers.IO) {
        val urls = buildCandidateUrls()
        if (urls.isEmpty()) {
            updateProbeInfo(urls, null)
            return@withContext null
        }

        for (url in urls) {
            val channels = loadFromUrl(url)
            if (!channels.isNullOrEmpty()) {
                updateProbeInfo(urls, url)
                AppLogStore.i(TAG, "Remote playlist loaded from $url")
                return@withContext RemotePlaylistResult(
                    sourceUrl = url,
                    channels = channels
                )
            }
        }

        updateProbeInfo(urls, null)
        null
    }

    fun getLastProbeInfo(): PlaylistProbeInfo = lastProbeInfo

    private fun buildCandidateUrls(): List<String> {
        val result = LinkedHashSet<String>(4)
        val configured = BuildConfig.PLAYLIST_URL.trim()

        if (configured.isNotEmpty()) {
            result.add(configured)
        }

        result.addAll(LocalAdminServerManager.getPlaylistUrlCandidates())
        return result.toList()
    }

    private fun updateProbeInfo(triedUrls: List<String>, selectedUrl: String?) {
        lastProbeInfo = PlaylistProbeInfo(
            localIp = resolveLocalIpv4Address(),
            triedUrls = triedUrls,
            selectedUrl = selectedUrl
        )
    }

    private suspend fun loadFromUrl(url: String): List<Channel>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }

            connection.inputStream.use { input ->
                val channels = ArrayList<Channel>(512)
                M3U8Parser.parse(input).collect { channel ->
                    channels.add(channel)
                }
                channels
            }
        }.onFailure {
            Log.w(TAG, "Remote playlist load failed for $url", it)
            AppLogStore.w(TAG, "Remote playlist load failed for $url", it)
        }.getOrNull()
    }

    private fun resolveLocalIpv4Address(): String? {
        return runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrElse {
            if (it !is SocketException) Log.w(TAG, "resolveLocalIpv4Address failed", it)
            null
        }
    }

    data class RemotePlaylistResult(
        val sourceUrl: String,
        val channels: List<Channel>
    )

    data class PlaylistProbeInfo(
        val localIp: String?,
        val triedUrls: List<String>,
        val selectedUrl: String?
    )
}
