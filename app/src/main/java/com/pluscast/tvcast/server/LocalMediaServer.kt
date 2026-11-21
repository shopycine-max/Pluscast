package com.pluscast.tvcast.server

import android.content.Context
import android.net.wifi.WifiManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.util.Locale

class LocalMediaServer(
    private val context: Context,
    private val port: Int = 8080
) : NanoHTTPD(port) {

    @Volatile
    private var mediaFile: File? = null

    fun setFileToServe(file: File?) {
        mediaFile = file
    }

    fun getStreamUrl(): String {
        val ip = getDeviceIpAddress()
        return "http://$ip:$port/media"
    }

    private fun getDeviceIpAddress(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else {
                InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "127.0.0.1"
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val file = mediaFile
        if (file == null || !file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Media file not available"
            )
        }

        val mimeType = guessMimeType(file.name)
        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        return try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val rangeValue = rangeHeader.substring("bytes=".length).trim()
                val parts = rangeValue.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }.coerceAtMost(fileLength - 1)

                if (start > end || start < 0L) {
                    return newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE,
                        MIME_PLAINTEXT,
                        "Invalid range"
                    )
                }

                val contentLength = end - start + 1
                val inputStream = FileInputStream(file).apply {
                    skip(start)
                }

                newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT,
                    mimeType,
                    inputStream,
                    contentLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Range", "bytes $start-$end/$fileLength")
                    addHeader("Content-Length", contentLength.toString())
                }
            } else {
                val inputStream = FileInputStream(file)
                newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    fileLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Length", fileLength.toString())
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

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "video/*"
        }
    }
}
