package com.tivimatelite.web

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LocalAdminServerManager {
    private const val TAG = "AdminServerManager"
    private const val PORT = 5220

    @Volatile
    private var server: LocalAdminServer? = null

    @Volatile
    private var lastStartError: String? = null

    fun start(context: Context) {
        if (server != null) return

        FileLogStore.i(TAG, "Starting local admin server on port $PORT")

        runCatching {
            val created = LocalAdminServer(context.applicationContext, PORT)
            created.start(2000, false)
            server = created
            lastStartError = null
            AppLogStore.i(TAG, "Local admin server started on port $PORT")
            FileLogStore.i(TAG, "Local admin server started on port $PORT")
        }.onFailure {
            lastStartError = it.message ?: it.javaClass.simpleName
            AppLogStore.e(TAG, "Local admin server start failed", it)
            FileLogStore.w(TAG, "Local admin server start failed", it)
        }
    }

    fun stop() {
        server?.stop()
        server = null
        AppLogStore.i(TAG, "Local admin server stopped")
        FileLogStore.i(TAG, "Local admin server stopped")
    }

    fun getPort(): Int = PORT

    fun isServerRunning(): Boolean = server != null

    fun getLastStartError(): String? = lastStartError

    fun getLocalIpv4Address(): String? {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    fun getAdminUrl(): String {
        val ip = getLocalIpv4Address() ?: "127.0.0.1"
        return "http://$ip:$PORT/"
    }

    fun getPlaylistUrlCandidates(): List<String> {
        val ip = getLocalIpv4Address()
        return buildList {
            if (!ip.isNullOrBlank()) {
                add("http://$ip:$PORT/channels.m3u")
                add("http://$ip:$PORT/playlist")
            }
            add("http://127.0.0.1:$PORT/channels.m3u")
            add("http://127.0.0.1:$PORT/playlist")
        }
    }
}
