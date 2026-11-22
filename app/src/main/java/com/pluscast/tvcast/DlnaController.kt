package com.pluscast.tvcast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String
)

class DlnaController(private val context: Context? = null) {

    companion object {
        private const val TAG = "PlusCastDlna"
    }

    suspend fun discoverRenderers(timeoutMs: Int = 4000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, DlnaDevice>()
            val locations = linkedSetOf<String>()

            val appContext = context?.applicationContext
            val wifiManager = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = try {
                wifiManager?.createMulticastLock("PlusCastDlnaLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                null
            }

            val searchTargets = listOf(
                "ssdp:all",
                "upnp:rootdevice",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1"
            )

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 600
                }

                val group = InetAddress.getByName("239.255.255.250")
                for (st in searchTargets) {
                    val query = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)

                    socket.send(DatagramPacket(query, query.size, group, 1900))
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)

                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        extractHeader(text, "LOCATION")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            locations.add(it)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Keep listening until timeout
                    } catch (e: Exception) {
                        Log.w(TAG, "SSDP receive error", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSDP send/receive error", e)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {
                }
            }

            for (loc in locations) {
                val parsed = parseDeviceDescription(loc) ?: continue
                found["${parsed.friendlyName}|${parsed.controlUrl}"] = parsed
            }

            found.values.toList()
        }

    suspend fun playMediaOnDevice(
        device: DlnaDevice,
        mediaUrl: String,
        title: String = "PlusCast Media",
        mimeType: String = "video/mp4"
    ): Boolean = withContext(Dispatchers.IO) {
        if (device.controlUrl.isBlank()) return@withContext false

        val escapedUrl = escapeXml(mediaUrl)
        val escapedTitle = escapeXml(title)
        val escapedMime = escapeXml(mimeType)
        val protocolInfo = "http-get:*:$escapedMime:*"

        val rawDidl =
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocolInfo">$escapedUrl</res></item></DIDL-Lite>"""
        val didlMetadata = escapeXml(rawDidl)

        val setUriSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>$escapedUrl</CurrentURI>
                  <CurrentURIMetaData>$didlMetadata</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        if (!sendSoapAction(
                controlUrl = device.controlUrl,
                soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
                body = setUriSoap
            )
        ) {
            return@withContext false
        }

        val playSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        sendSoapAction(
            controlUrl = device.controlUrl,
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = playSoap
        )
    }

    private fun parseDeviceDescription(locationUrl: String): DlnaDevice? {
        var conn: HttpURLConnection? = null
        return try {
            val baseUrl = URL(locationUrl)
            conn = (baseUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null

            val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            val friendlyName = extractTagValue(xml, "friendlyName")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val rawControlUrl = extractAvTransportControlUrl(xml)?.trim()
            val resolvedControlUrl = if (!rawControlUrl.isNullOrEmpty()) {
                URL(baseUrl, rawControlUrl).toString()
            } else {
                ""
            }

            DlnaDevice(
                friendlyName = if (resolvedControlUrl.isEmpty()) "$friendlyName (No AVTransport)" else friendlyName,
                controlUrl = resolvedControlUrl
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractAvTransportControlUrl(xml: String): String? {
        val serviceRegex = Regex("<service>([\\s\\S]*?)</service>", RegexOption.IGNORE_CASE)
        for (match in serviceRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val serviceType = extractTagValue(block, "serviceType") ?: ""
            if (serviceType.contains("AVTransport", ignoreCase = true)) {
                val ctrl = extractTagValue(block, "controlURL")
                if (!ctrl.isNullOrBlank()) return ctrl
            }
        }
        return null
    }

    private fun sendSoapAction(controlUrl: String, soapAction: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(controlUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", soapAction)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val target = headerName.lowercase(Locale.US)
        for (line in response.split("\r\n", "\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase(Locale.US)
                if (key == target) {
                    return line.substring(idx + 1).trim()
                }
            }
        }
        return null
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        val regex = Regex(
            "<(?:[a-zA-Z0-9_-]+:)?$tagName\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?$tagName>",
            RegexOption.IGNORE_CASE
        )
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
