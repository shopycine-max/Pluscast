package com.pluscast.tvcast.server

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class LocalMediaServer(
    private val context: Context,
    private val port: Int = 8080
) : NanoHTTPD(port) {

    @Volatile
    private var mediaFile: File? = null

    @Volatile
    private var mediaUri: Uri? = null

    @Volatile
    private var overrideMimeType: String? = null

    @Volatile
    private var overrideDisplayName: String? = null

    @Volatile
    private var youtubeSourceUrl: String? = null

    @Volatile
    private var youtubeVideoId: String? = null

    private val ytIdRegex = Regex("^[a-zA-Z0-9_-]{11}$")

    fun setFileToServe(file: File?, mimeType: String? = null) {
        mediaFile = file
        mediaUri = file?.let { Uri.fromFile(it) }
        overrideMimeType = mimeType?.takeIf { it.isNotBlank() }
        overrideDisplayName = file?.name
        youtubeSourceUrl = null
        youtubeVideoId = null
    }

    fun setUriToServe(uri: Uri?, mimeType: String? = null, displayName: String? = null) {
        mediaUri = uri
        mediaFile = if (uri != null && uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let(::File)
        } else {
            null
        }
        overrideMimeType = mimeType?.takeIf { it.isNotBlank() }
        overrideDisplayName = displayName?.takeIf { it.isNotBlank() } ?: uri?.let { queryDisplayName(it) }
        youtubeSourceUrl = null
        youtubeVideoId = null
    }

    fun setYouTubeMedia(youtubeUrlOrId: String, title: String? = null) {
        val trimmed = youtubeUrlOrId.trim()
        val vid = extractYouTubeVideoId(trimmed)
        youtubeVideoId = vid
        youtubeSourceUrl = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            vid != null -> "https://www.youtube.com/watch?v=$vid"
            else -> null
        }
        overrideDisplayName = title?.takeIf { it.isNotBlank() } ?: vid?.let { "YouTube ($it)" }
    }

    fun getStreamUrl(): String {
        val ip = getDeviceIpAddress()
        return if (youtubeSourceUrl != null && mediaUri == null && mediaFile == null) {
            "http://$ip:$port/youtube"
        } else {
            "http://$ip:$port/media"
        }
    }

    fun getYouTubeStreamUrl(): String? {
        if (youtubeSourceUrl == null) return null
        return "http://${getDeviceIpAddress()}:$port/youtube"
    }

    fun startServerSafe(): Boolean {
        return try {
            if (!isAlive) {
                start(SOCKET_READ_TIMEOUT, false)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stopServerSafe() {
        try {
            if (isAlive) {
                stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                val addr = InetAddress.getByAddress(bytes)
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return try {
            InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.orEmpty()

        if (path.startsWith("/youtube", ignoreCase = true)) {
            return handleYouTubeRequest()
        }

        return handleLocalMediaRequest(session)
    }

    private fun handleYouTubeRequest(): Response {
        val ytUrl = youtubeSourceUrl
        val vid = youtubeVideoId
        if (ytUrl.isNullOrBlank() || vid.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "No valid YouTube URL or Video ID configured"
            )
        }

        val info = """
            PlusCast Local Relay - YouTube Cast Target
            Video ID: $vid
            Source URL: $ytUrl
            Note: Direct raw video stream requires an external stream extractor; DLNA/Cast player handles YouTube target.
        """.trimIndent()

        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_PLAINTEXT,
            info
        )
    }

    private fun handleLocalMediaRequest(session: IHTTPSession): Response {
        val file = mediaFile
        val uri = mediaUri

        val resolvedMime = overrideMimeType
            ?: uri?.let { context.contentResolver.getType(it) }
            ?: guessMimeType(overrideDisplayName ?: file?.name ?: uri?.lastPathSegment ?: "media")

        val totalLength: Long = when {
            file != null && file.exists() && file.isFile -> file.length()
            uri != null -> queryUriLength(uri)
            else -> -1L
        }

        if (totalLength <= 0L && (file == null || !file.exists()) && uri == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Media file not available"
            )
        }

        val isHead = session.method == Method.HEAD
        val rangeHeader = session.headers["range"]

        return try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true) && totalLength > 0L) {
                val rangeValue = rangeHeader.substringAfter('=').split(',').firstOrNull()?.trim().orEmpty()
                val parts = rangeValue.split("-")
                val start = parts[0].trim().toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].trim().isNotEmpty()) {
                    parts[1].trim().toLongOrNull() ?: (totalLength - 1)
                } else {
                    totalLength - 1
                }.coerceAtMost(totalLength - 1)

                if (start > end || start < 0L || start >= totalLength) {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Invalid range"
                    ).apply {
                        addHeader("Content-Range", "bytes */$totalLength")
                    }
                }

                val contentLength = end - start + 1
                val stream: InputStream? = if (isHead) {
                    null
                } else {
                    openMediaStream(file, uri)?.also { it.skipFully(start) }
                        ?: return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "Unable to open media stream"
                        )
                }

                newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    resolvedMime,
                    stream,
                    contentLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$totalLength")
                    addHeader("Content-Length", contentLength.toString())
                }
            } else {
                val stream: InputStream? = if (isHead) {
                    null
                } else {
                    openMediaStream(file, uri) ?: return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "Unable to open media stream"
                    )
                }

                if (totalLength > 0L) {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        resolvedMime,
                        stream,
                        totalLength
                    ).apply {
                        addHeader("Accept-Ranges", "bytes")
                        addHeader("Content-Length", totalLength.toString())
                    }
                } else {
                    newChunkedResponse(
                        Response.Status.OK,
                        resolvedMime,
                        stream
                    ).apply {
                        addHeader("Accept-Ranges", "bytes")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error serving media: ${e.message}"
            )
        }
    }

    private fun openMediaStream(file: File?, uri: Uri?): InputStream? {
        if (file != null && file.exists() && file.isFile) {
            return FileInputStream(file)
        }
        if (uri != null) {
            return try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        return null
    }

    private fun queryUriLength(uri: Uri): Long {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val f = uri.path?.let(::File)
            if (f != null && f.exists()) return f.length()
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
                    } else -1L
                } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun InputStream.skipFully(bytesToSkip: Long) {
        if (bytesToSkip <= 0L) return
        var skippedTotal = 0L
        while (skippedTotal < bytesToSkip) {
            val skipped = skip(bytesToSkip - skippedTotal)
            if (skipped > 0L) {
                skippedTotal += skipped
            } else {
                if (read() < 0) break
                skippedTotal += 1L
            }
        }
    }

    private fun extractYouTubeVideoId(raw: String): String? {
        val trimmed = raw.trim()
        if (ytIdRegex.matches(trimmed)) return trimmed

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        if (host.contains("youtu.be")) {
            val seg = uri.lastPathSegment?.trim().orEmpty()
            if (ytIdRegex.matches(seg)) return seg
        }

        if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
            uri.getQueryParameter("v")?.trim()?.takeIf { ytIdRegex.matches(it) }?.let {
                return it
            }
            val segments = uri.pathSegments.orEmpty()
            for (i in 0 until segments.size - 1) {
                val tag = segments[i].lowercase(Locale.US)
                if (tag == "shorts" || tag == "embed" || tag == "live" || tag == "v") {
                    val candidate = segments[i + 1].trim()
                    if (ytIdRegex.matches(candidate)) return candidate
                }
            }
        }
        return null
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".flac") -> "audio/flac"
            else -> "video/*"
        }
    }
}
