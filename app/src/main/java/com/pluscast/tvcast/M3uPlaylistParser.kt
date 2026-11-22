package com.pluscast.tvcast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class IptvChannel(
    val name: String,
    val url: String,
    val group: String = "All",
    val logoUrl: String = "",
    val language: String = "",
    val userAgent: String = "",
    val referrer: String = ""
)

object M3uPlaylistParser {

    private const val TAG = "PlusCastM3uParser"
    private const val CACHE_FILENAME = "pluscast_in_cache.m3u"
    private const val PREFS_NAME = "pluscast_m3u_cache_prefs"
    private const val KEY_LAST_FETCH_MS = "last_fetch_ms"

    const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
    const val DAILY_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val GROUP_REGEX = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_NAME_REGEX = Regex("""tvg-name=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LOGO_REGEX = Regex("""tvg-logo=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LANG_REGEX = Regex("""(?:tvg-language|language)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val VLC_UA_REGEX = Regex("""#EXTVLCOPT:http-user-agent=(.+)""", RegexOption.IGNORE_CASE)
    private val VLC_REF_REGEX = Regex("""#EXTVLCOPT:http-referrer=(.+)""", RegexOption.IGNORE_CASE)

    fun isDailyRefreshDue(context: Context?): Boolean {
        val ctx = context?.applicationContext ?: return true
        val lastMs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_FETCH_MS, 0L)
        if (lastMs <= 0L) return true
        return (System.currentTimeMillis() - lastMs) >= DAILY_REFRESH_INTERVAL_MS
    }

    fun markRefreshedNow(context: Context?) {
        val ctx = context?.applicationContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_FETCH_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Fetches playlist. When [forceRefresh] is false and cache is fresh (< 24h),
     * returns cached channels immediately; otherwise fetches fresh playlist daily.
     */
    suspend fun fetchChannels(
        playlistUrl: String = DEFAULT_PLAYLIST_URL,
        context: Context? = null,
        forceRefresh: Boolean = false
    ): List<IptvChannel> =
        withContext(Dispatchers.IO) {
            val appCtx = context?.applicationContext
            val cleanUrl = playlistUrl.trim().ifEmpty { DEFAULT_PLAYLIST_URL }
            val cacheFile = appCtx?.let { File(it.cacheDir, CACHE_FILENAME) }

            val hasValidCache = cacheFile != null && cacheFile.exists() && cacheFile.length() > 0L
            if (!forceRefresh && hasValidCache && !isDailyRefreshDue(appCtx)) {
                val cachedList = readFromCache(cacheFile)
                if (cachedList.isNotEmpty()) {
                    return@withContext cachedList
                }
            }

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 35_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PlusCast/1.0 (Android; IPTV)")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Playlist HTTP error $code for $cleanUrl")
                    return@withContext readFromCache(cacheFile)
                }

                val rawText = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 64 * 1024)
                    .use(BufferedReader::readText)

                if (rawText.isBlank()) {
                    return@withContext readFromCache(cacheFile)
                }

                val parsed = parseReader(BufferedReader(rawText.reader(), 32 * 1024))
                if (parsed.isNotEmpty()) {
                    markRefreshedNow(appCtx)
                    if (cacheFile != null) {
                        try {
                            cacheFile.writeText(rawText, Charsets.UTF_8)
                        } catch (_: Exception) {
                        }
                    }
                    parsed
                } else {
                    readFromCache(cacheFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch/parse M3U playlist, trying cache", e)
                readFromCache(cacheFile)
            } finally {
                conn?.disconnect()
            }
        }

    private fun readFromCache(cacheFile: File?): List<IptvChannel> {
        if (cacheFile == null || !cacheFile.exists() || cacheFile.length() <= 0L) {
            return emptyList()
        }
        return try {
            BufferedReader(InputStreamReader(cacheFile.inputStream(), Charsets.UTF_8), 64 * 1024).use {
                parseReader(it)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseReader(reader: BufferedReader): List<IptvChannel> {
        val channels = ArrayList<IptvChannel>(2048)
        var pendingName: String? = null
        var pendingGroup: String = "All"
        var pendingLogo: String = ""
        var pendingLang: String = ""
        var pendingUserAgent: String = ""
        var pendingReferrer: String = ""
        var indexCounter = 1

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

            if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                VLC_UA_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    pendingUserAgent = it
                }
                VLC_REF_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    pendingReferrer = it
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
                channels.add(
                    IptvChannel(
                        name = finalName,
                        url = line,
                        group = pendingGroup,
                        logoUrl = pendingLogo,
                        language = pendingLang,
                        userAgent = pendingUserAgent,
                        referrer = pendingReferrer
                    )
                )

                indexCounter++
                pendingName = null
                pendingGroup = "All"
                pendingLogo = ""
                pendingLang = ""
                pendingUserAgent = ""
                pendingReferrer = ""
            }
        }

        Log.d(TAG, "Parsed playlist -> ${channels.size} total channels")
        return channels
    }
}
