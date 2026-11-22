package com.pluscast.tvcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class IptvChannel(
    val name: String,
    val url: String,
    val group: String = "All"
)

object M3uPlaylistParser {

    suspend fun fetchChannels(playlistUrl: String): List<IptvChannel> =
        withContext(Dispatchers.IO) {
            val cleanUrl = playlistUrl.trim()
            if (cleanUrl.isEmpty()) return@withContext emptyList()

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PlusCast/1.0 (IPTV)")
                    setRequestProperty("Cache-Control", "no-cache")
                }

                if (conn.responseCode !in 200..299) {
                    return@withContext emptyList()
                }

                val channels = ArrayList<IptvChannel>(1600)
                var pendingName: String? = null
                var pendingGroup: String = "All"
                var indexCounter = 1

                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val rawLine = reader.readLine() ?: break
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue

                        if (line.startsWith("#EXTINF", ignoreCase = true)) {
                            val groupMatch = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                                .find(line)
                            pendingGroup = groupMatch?.groupValues?.getOrNull(1)?.trim()
                                .takeUnless { it.isNullOrEmpty() } ?: "All"

                            val commaIdx = line.lastIndexOf(',')
                            val rawTitle = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
                            pendingName = rawTitle.ifEmpty { "Channel $indexCounter" }
                            continue
                        }

                        if (line.startsWith("#")) {
                            continue
                        }

                        if (line.startsWith("http://", ignoreCase = true) ||
                            line.startsWith("https://", ignoreCase = true)
                        ) {
                            val finalName = pendingName ?: "Channel $indexCounter"
                            channels.add(
                                IptvChannel(
                                    name = finalName,
                                    url = line,
                                    group = pendingGroup
                                )
                            )
                            indexCounter++
                            pendingName = null
                            pendingGroup = "All"
                        }
                    }
                }

                channels
            } catch (_: Exception) {
                emptyList()
            } finally {
                conn?.disconnect()
            }
        }
}
