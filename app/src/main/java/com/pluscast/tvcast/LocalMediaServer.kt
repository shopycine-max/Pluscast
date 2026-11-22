package com.pluscast.tvcast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object LocalMediaServer {

    private const val TAG = "PlusCastLocalServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var boundPort: Int = 0
    private val mediaEntries = ConcurrentHashMap<String, MediaEntry>()

    @Volatile
    private var appContextRef: Context? = null

    private data class MediaEntry(
        val uri: Uri,
        val mimeType: String,
        val length: Long
    )

    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    suspend fun registerAndGetUrl(
        context: Context,
        rawUriOrPath: String,
        fallbackMime: String = "video/mp4"
    ): String? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        init(appContext)

        val uri = when {
            rawUriOrPath.startsWith("content://") || rawUriOrPath.startsWith("file://") ->
                Uri.parse(rawUriOrPath)
            rawUriOrPath.startsWith("/") ->
                Uri.fromFile(File(rawUriOrPath))
            else -> return@withContext null
        }

        val mimeType = resolveMimeType(appContext, uri, fallbackMime)
        val length = resolveContentLength(appContext, uri)
        val hostIp = getLocalLanIpv4(appContext) ?: return@withContext null

        ensureServerStarted()
        val port = boundPort
        if (port <= 0) return@withContext null

        val id = Integer.toHexString((rawUriOrPath + System.nanoTime()).hashCode())
        mediaEntries[id] = MediaEntry(uri = uri, mimeType = mimeType, length = length)
        val streamUrl = "http://$hostIp:$port/media/$id"
        Log.d(TAG, "Registered local media URL: $streamUrl (mime=$mimeType, len=$length)")
        return@withContext streamUrl
    }

    fun stopServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        boundPort = 0
        mediaEntries.clear()
    }

    @Synchronized
    private fun ensureServerStarted() {
        if (isRunning.get() && serverSocket?.isClosed == false) return

        val candidatePorts = listOf(8585, 8686, 8787, 0)
        for (p in candidatePorts) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                serverSocket = ss
                boundPort = ss.localPort
                isRunning.set(true)
                Log.d(TAG, "LocalMediaServer started on port $boundPort")

                Thread {
                    while (isRunning.get()) {
                        try {
                            val client = ss.accept()
                            Thread { handleClient(client) }.start()
                        } catch (_: Exception) {
                            if (!isRunning.get()) break
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "PlusCastLocalHttpServer"
                    start()
                }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 15_000
                val input = BufferedInputStream(sock.getInputStream())
                val output = BufferedOutputStream(sock.getOutputStream())

                val requestLine = readLineCrLf(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = URLDecoder.decode(parts[1], "UTF-8")

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLineCrLf(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                            line.substring(idx + 1).trim()
                    }
                }

                val id = rawPath.removePrefix("/media/").substringBefore("?").trim()
                val entry = mediaEntries[id]
                if (entry == null) {
                    writeNotFound(output)
                    return
                }

                val totalLength = entry.length
                var startByte = 0L
                var endByte = if (totalLength > 0) totalLength - 1 else -1L
                var isPartial = false

                val rangeHeader = headers["range"]
                if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true) && totalLength > 0) {
                    val rangeSpec = rangeHeader.substringAfter("=").substringBefore(",").trim()
                    val dashIdx = rangeSpec.indexOf('-')
                    if (dashIdx >= 0) {
                        val startStr = rangeSpec.substring(0, dashIdx).trim()
                        val endStr = rangeSpec.substring(dashIdx + 1).trim()
                        if (startStr.isEmpty() && endStr.isNotEmpty()) {
                            val suffixLen = endStr.toLongOrNull() ?: 0L
                            if (suffixLen > 0) {
                                startByte = (totalLength - suffixLen).coerceAtLeast(0L)
                                endByte = totalLength - 1
                                isPartial = true
                            }
                        } else {
                            startByte = startStr.toLongOrNull() ?: 0L
                            endByte = endStr.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
                            if (startByte <= endByte && startByte < totalLength) {
                                isPartial = true
                            } else {
                                startByte = 0L
                                endByte = totalLength - 1
                            }
                        }
                    }
                }

                val contentLength = if (totalLength > 0 && endByte >= startByte) {
                    endByte - startByte + 1
                } else {
                    -1L
                }

                val statusLine = if (isPartial) {
                    "HTTP/1.1 206 Partial Content"
                } else {
                    "HTTP/1.1 200 OK"
                }

                val responseHeaders = buildString {
                    append("$statusLine\r\n")
                    append("Content-Type: ${entry.mimeType}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                    if (isPartial && totalLength > 0) {
                        append("Content-Range: bytes $startByte-$endByte/$totalLength\r\n")
                    }
                    if (contentLength >= 0) {
                        append("Content-Length: $contentLength\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }

                output.write(responseHeaders.toByteArray(Charsets.UTF_8))
                output.flush()

                if (method == "HEAD") return

                openStream(entry.uri)?.use { stream ->
                    if (startByte > 0) {
                        skipFully(stream, startByte)
                    }
                    val buffer = ByteArray(64 * 1024)
                    var remaining = if (contentLength >= 0) contentLength else Long.MAX_VALUE
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = stream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        if (contentLength >= 0) {
                            remaining -= read.toLong()
                        }
                    }
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun openStream(uri: Uri): InputStream? {
        return try {
            if ("file".equals(uri.scheme, ignoreCase = true) && uri.path != null) {
                FileInputStream(File(uri.path!!))
            } else {
                val appContext = appContextRef ?: return null
                appContext.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMimeType(context: Context, uri: Uri, fallbackMime: String): String {
        val resolverMime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        if (!resolverMime.isNullOrBlank() && resolverMime != "application/octet-stream") {
            return resolverMime
        }
        val lower = uri.toString().lowercase(Locale.US)
        return when {
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            else -> fallbackMime.ifBlank { "video/mp4" }
        }
    }

    private fun resolveContentLength(context: Context, uri: Uri): Long {
        if ("file".equals(uri.scheme, ignoreCase = true) && uri.path != null) {
            val f = File(uri.path!!)
            if (f.exists()) return f.length()
        }
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) return afd.length
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    private fun getLocalLanIpv4(context: Context): String? {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                val addr = InetAddress.getByAddress(bytes)
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return addr.hostAddress
                }
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var left = bytesToSkip
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n.toLong()
            }
        }
    }

    private fun readLineCrLf(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString("UTF-8").trimEnd('\r')
            }
            if (b == '\n'.code) {
                return out.toString("UTF-8").trimEnd('\r')
            }
            out.write(b)
        }
    }

    private fun writeNotFound(output: OutputStream) {
        val body = "Not Found"
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        output.write(resp.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
