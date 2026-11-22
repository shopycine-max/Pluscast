package com.pluscast.tvcast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class IptvChannel(
    val name: String,
    val url: String,
    val group: String = "All",
    val logoUrl: String = "",
    val language: String = ""
)

object M3uPlaylistParser {

    private const val TAG = "PlusCastM3uParser"
    private val GROUP_REGEX = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_NAME_REGEX = Regex("""tvg-name=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LOGO_REGEX = Regex("""tvg-logo=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LANG_REGEX = Regex("""(?:tvg-language|language)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    suspend fun fetchChannels(playlistUrl: String): List<IptvChannel> =
        withContext(Dispatchers.IO) {
            val cleanUrl = playlistUrl.trim()
            if (cleanUrl.isEmpty()) return@withContext emptyList()

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 35_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PlusCast/1.0 (IPTV)")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Playlist HTTP error $code for $cleanUrl")
                    return@withContext emptyList()
                }

                val channels = ArrayList<IptvChannel>(1024)
                var pendingName: String? = null
                var pendingGroup: String = "All"
                var pendingLogo: String = ""
                var pendingLang: String = ""
                var indexCounter = 1

                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 64 * 1024).use { reader ->
                    while (true) {
                        val rawLine = reader.readLine() ?: break
                        val line = rawLine.removePrefix("\uFEFF").trim()
                        if (line.isEmpty()) continue

                        if (line.startsWith("#EXTINF", ignoreCase = true)) {
                            val groupMatch = GROUP_REGEX.find(line)
                            pendingGroup = groupMatch?.groupValues?.getOrNull(1)?.trim()
                                .takeUnless { it.isNullOrEmpty() } ?: "All"

                            pendingLogo = TVG_LOGO_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                            pendingLang = TVG_LANG_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

                            val commaIdx = line.lastIndexOf(',')
                            val afterComma = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
                            val tvgName = TVG_NAME_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()

                            pendingName = when {
                                afterComma.isNotEmpty() -> afterComma
                                tvgName.isNotEmpty() -> tvgName
                                else -> "Channel $indexCounter"
                            }
                            continue
                        }

                        if (line.startsWith("#")) {
                            continue
                        }

                        if (line.startsWith("http://", ignoreCase = true) ||
                            line.startsWith("https://", ignoreCase = true)
                        ) {
                            val finalName = pendingName?.takeIf { it.isNotBlank() } ?: "Channel $indexCounter"
                            val candidate = IptvChannel(
                                name = finalName,
                                url = line,
                                group = pendingGroup,
                                logoUrl = pendingLogo,
                                language = pendingLang
                            )

                            if (isAllowedChannel(candidate)) {
                                channels.add(candidate)
                            }

                            indexCounter++
                            pendingName = null
                            pendingGroup = "All"
                            pendingLogo = ""
                            pendingLang = ""
                        }
                    }
                }

                Log.d(TAG, "Filtered playlist -> ${channels.size} allowed channels")
                channels
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/parse M3U playlist", e)
                emptyList()
            } finally {
                conn?.disconnect()
            }
        }

    fun resolveCategory(channel: IptvChannel): String? {
        val hay = buildString {
            append(channel.name.lowercase(Locale.US))
            append(' ')
            append(channel.group.lowercase(Locale.US))
            append(' ')
            append(channel.language.lowercase(Locale.US))
        }

        val hasHindi = Regex("""\bhindi\b""").containsMatchIn(hay)
        val hasBhojpuri = Regex("""\bbhojpuri\b""").containsMatchIn(hay)
        val hasBengali = Regex("""\b(bengali|bangla)\b""").containsMatchIn(hay) ||
            channel.name.any { it.code in 0x0980..0x09FF }
        val hasNews = Regex("""\bnews\b""").containsMatchIn(hay)

        return when {
            hasBhojpuri -> "Bhojpuri"
            hasHindi -> "Hindi"
            hasBengali && hasNews -> "Bengali News"
            else -> null
        }
    }

    fun isAllowedChannel(channel: IptvChannel): Boolean = resolveCategory(channel) != null
}
