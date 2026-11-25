package com.pluscast.tvcast

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

enum class MediaSourceType {
    STREAM,
    YOUTUBE,
    LOCAL
}

data class IptvChannel(
    val name: String,
    val url: String,
    val group: String = "All",
    val logoUrl: String = "",
    val language: String = "",
    val userAgent: String = "",
    val referrer: String = "",
    val sourceType: MediaSourceType = MediaSourceType.STREAM,
    val youtubeVideoId: String = ""
) {
    val isYouTube: Boolean get() = sourceType == MediaSourceType.YOUTUBE
    val isLocal: Boolean get() = sourceType == MediaSourceType.LOCAL
}

object M3uPlaylistParser {

    private const val TAG = "PlusCastM3uParser"
    private const val CACHE_PREFIX = "pluscast_m3u_"
    private const val PREFS_NAME = "pluscast_m3u_cache_prefs"
    private const val KEY_LAST_FETCH_MS = "last_fetch_ms"
    private const val MAX_PLAYLIST_BYTES = 20 * 1024 * 1024 // 20 MB safety guard

    const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
    const val DAILY_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val GROUP_REGEX = Regex("""group-title=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_NAME_REGEX = Regex("""tvg-name=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LOGO_REGEX = Regex("""tvg-logo=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val TVG_LANG_REGEX = Regex("""(?:tvg-language|language)=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val VLC_UA_REGEX = Regex("""#EXTVLCOPT:http-user-agent=(.+)""", RegexOption.IGNORE_CASE)
    private val VLC_REF_REGEX = Regex("""#EXTVLCOPT:http-referrer=(.+)""", RegexOption.IGNORE_CASE)
    private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val LOCAL_MEDIA_EXT_REGEX = Regex(
        """.*\.(mp4|m4v|mkv|webm|avi|mov|ts|m2ts|mp3|m4a|aac|wav|flac|ogg)$""",
        RegexOption.IGNORE_CASE
    )

    fun isDailyRefreshDue(
        context: Context?,
        playlistUrl: String = DEFAULT_PLAYLIST_URL
    ): Boolean {
        val ctx = context?.applicationContext ?: return true
        val key = lastFetchKeyForUrl(playlistUrl)
        val lastMs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(key, 0L)
        if (lastMs <= 0L) return true
        return (System.currentTimeMillis() - lastMs) >= DAILY_REFRESH_INTERVAL_MS
    }

    fun markRefreshedNow(
        context: Context?,
        playlistUrl: String = DEFAULT_PLAYLIST_URL
    ) {
        val ctx = context?.applicationContext ?: return
        val key = lastFetchKeyForUrl(playlistUrl)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, System.currentTimeMillis())
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
            val rawUrl = playlistUrl.trim().ifEmpty { DEFAULT_PLAYLIST_URL }
            val cleanUrl = if (isValidHttpUrl(rawUrl)) rawUrl else DEFAULT_PLAYLIST_URL
            val cacheFile = appCtx?.let { File(it.cacheDir, cacheFilenameForUrl(cleanUrl)) }

            val hasValidCache = cacheFile != null && cacheFile.exists() && cacheFile.length() > 0L
            if (!forceRefresh && hasValidCache && !isDailyRefreshDue(appCtx, cleanUrl)) {
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

                val bytes = readBoundedBytes(conn.inputStream, MAX_PLAYLIST_BYTES)
                if (bytes.isEmpty()) {
                    return@withContext readFromCache(cacheFile)
                }

                val parsed = BufferedReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8), 32 * 1024)
                    .use { parseReader(it) }

                if (parsed.isNotEmpty()) {
                    markRefreshedNow(appCtx, cleanUrl)
                    if (cacheFile != null) {
                        try {
                            cacheFile.writeBytes(bytes)
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

    fun extractYouTubeVideoId(raw: String): String? {
        val trimmed = raw.trim()
        if (YT_ID_REGEX.matches(trimmed)) {
            return trimmed
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        // Kodi YouTube plugin URLs e.g. plugin://plugin.video.youtube/?action=play_video&videoid=XXXXXXXXXXX
        if (scheme == "plugin" && host.contains("youtube")) {
            uri.getQueryParameter("videoid")?.trim()?.takeIf { YT_ID_REGEX.matches(it) }?.let {
                return it
            }
        }

        if (host.contains("youtu.be")) {
            val seg = uri.lastPathSegment?.trim().orEmpty()
            if (YT_ID_REGEX.matches(seg)) return seg
        }

        if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
            uri.getQueryParameter("v")?.trim()?.takeIf { YT_ID_REGEX.matches(it) }?.let {
                return it
            }

            val segments = uri.pathSegments.orEmpty()
            for (i in 0 until segments.size - 1) {
                val tag = segments[i].lowercase(Locale.US)
                if (tag == "shorts" || tag == "embed" || tag == "live" || tag == "v") {
                    val candidate = segments[i + 1].trim()
                    if (YT_ID_REGEX.matches(candidate)) return candidate
                }
            }
        }

        return null
    }

    private fun classifyEntry(rawEntry: String): Triple<MediaSourceType, String, String>? {
        val trimmed = rawEntry.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isISOControl() }) return null

        val ytId = extractYouTubeVideoId(trimmed)
        if (ytId != null) {
            val normalizedUrl = if (isValidHttpUrl(trimmed)) {
                trimmed
            } else {
                "https://www.youtube.com/watch?v=$ytId"
            }
            return Triple(MediaSourceType.YOUTUBE, normalizedUrl, ytId)
        }

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("content://") || lower.startsWith("file://")) {
            return Triple(MediaSourceType.LOCAL, trimmed, "")
        }

        if ((trimmed.startsWith("/") || lower.startsWith("/storage/") || lower.startsWith("/sdcard/")) &&
            LOCAL_MEDIA_EXT_REGEX.matches(trimmed)
        ) {
            val fileUri = Uri.fromFile(File(trimmed)).toString()
            return Triple(MediaSourceType.LOCAL, fileUri, "")
        }

        if (isValidHttpUrl(trimmed)) {
            return Triple(MediaSourceType.STREAM, trimmed, "")
        }

        return null
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IllegalStateException("Playlist size exceeded limit ($maxBytes bytes)")
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
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

                val rawLogo = TVG_LOGO_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                pendingLogo = if (isValidHttpUrl(rawLogo)) rawLogo else ""

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
                VLC_UA_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()
                    ?.let(::sanitizeHeaderValue)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { pendingUserAgent = it }

                VLC_REF_REGEX.find(line)?.groupValues?.getOrNull(1)?.trim()
                    ?.let(::sanitizeHeaderValue)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { pendingReferrer = it }
                continue
            }

            if (line.startsWith("#")) {
                continue
            }

            val classified = classifyEntry(line)
            if (classified != null) {
                val (sourceType, resolvedUrl, ytVideoId) = classified
                val defaultGroup = when (sourceType) {
                    MediaSourceType.YOUTUBE -> if (pendingGroup == "All") "YouTube" else pendingGroup
                    MediaSourceType.LOCAL -> if (pendingGroup == "All") "Local Media" else pendingGroup
                    MediaSourceType.STREAM -> pendingGroup
                }
                val finalName = pendingName?.takeIf { it.isNotBlank() } ?: when (sourceType) {
                    MediaSourceType.YOUTUBE -> "YouTube ($ytVideoId)"
                    MediaSourceType.LOCAL -> Uri.parse(resolvedUrl).lastPathSegment ?: "Local Media $indexCounter"
                    MediaSourceType.STREAM -> "Channel $indexCounter"
                }

                channels.add(
                    IptvChannel(
                        name = finalName,
                        url = resolvedUrl,
                        group = defaultGroup,
                        logoUrl = pendingLogo,
                        language = pendingLang,
                        userAgent = pendingUserAgent,
                        referrer = pendingReferrer,
                        sourceType = sourceType,
                        youtubeVideoId = ytVideoId
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

        Log.d(TAG, "Parsed playlist -> ${channels.size} total entries")
        return channels
    }

    private fun cacheFilenameForUrl(url: String): String {
        return "${CACHE_PREFIX}${sha256Short(url)}.m3u"
    }

    private fun lastFetchKeyForUrl(url: String): String {
        return "${KEY_LAST_FETCH_MS}_${sha256Short(url)}"
    }

    private fun sha256Short(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.trim().toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            input.hashCode().toUInt().toString(16)
        }
    }

    private fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank() || url.any { it.isISOControl() || it.isWhitespace() }) return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun sanitizeHeaderValue(value: String): String {
        return value.filterNot { it == '\r' || it == '\n' || it.isISOControl() }.trim()
    }
}
