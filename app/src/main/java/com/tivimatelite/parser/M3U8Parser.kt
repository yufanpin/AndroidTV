package com.tivimatelite.parser

import com.tivimatelite.model.Channel
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

object M3U8Parser {
    private const val EXTINF = "#EXTINF"
    private const val URL_SEPARATOR = ','
    private val attributeRegex = Regex("""([A-Za-z0-9_-]+)=\"([^\"]*)\"""")

    fun parse(inputStream: InputStream): Flow<Channel> = channelFlow {
        withContext(Dispatchers.IO) {
            inputStream.bufferedReader().use { reader ->
                var pendingInfo: ExtInfInfo? = null

                for (rawLine in reader.lineSequence()) {
                    val line = rawLine.trim()
                    when {
                        line.startsWith(EXTINF) -> pendingInfo = parseExtInf(line)
                        pendingInfo != null && line.isNotEmpty() && !line.startsWith("#") -> {
                            val info = pendingInfo
                            pendingInfo = null
                            send(
                                Channel(
                                    name = info.name,
                                    logoUrl = info.logoUrl,
                                    groupName = info.groupName,
                                    streamUrl = line,
                                    epgText = info.groupName ?: "No EPG data"
                                )
                            )
                        }
                        pendingInfo == null && line.isSimpleChannelLine() -> {
                            val separator = line.indexOf(URL_SEPARATOR)
                            send(
                                Channel(
                                    name = line.substring(0, separator).trim(),
                                    logoUrl = null,
                                    groupName = null,
                                    streamUrl = line.substring(separator + 1).trim()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun String.isSimpleChannelLine(): Boolean {
        val separator = indexOf(URL_SEPARATOR)
        if (separator <= 0 || separator >= lastIndex) return false
        val urlStart = separator + 1
        return regionMatches(urlStart, "http://", 0, 7, ignoreCase = true) ||
            regionMatches(urlStart, "https://", 0, 8, ignoreCase = true)
    }

    private fun parseExtInf(line: String): ExtInfInfo {
        var tvgName: String? = null
        var logoUrl: String? = null
        var groupName: String? = null

        for (match in attributeRegex.findAll(line)) {
            when (match.groupValues[1]) {
                "tvg-name" -> tvgName = match.groupValues[2]
                "tvg-logo" -> logoUrl = match.groupValues[2]
                "group-title" -> groupName = match.groupValues[2]
            }
        }

        val displayName = line.substringAfter(',', tvgName ?: "Unknown Channel").trim()
        return ExtInfInfo(
            name = displayName.ifEmpty { tvgName ?: "Unknown Channel" },
            logoUrl = logoUrl,
            groupName = groupName
        )
    }

    private data class ExtInfInfo(
        val name: String,
        val logoUrl: String?,
        val groupName: String?
    )
}
