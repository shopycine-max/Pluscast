package com.pluscast.tvcast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String
)

class DlnaController {

    suspend fun discoverRenderers(timeoutMs: Int = 3000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val foundByControlUrl = linkedMapOf<String, DlnaDevice>()
            val locations = linkedSetOf<String>()

            val ssdpQuery = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: 239.255.255.250:1900\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)

            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket()
                socket.soTimeout = 700
                val group = InetAddress.getByName("239.255.255.250")
                val sendPacket = DatagramPacket(ssdpQuery, ssdpQuery.size, group, 1900)
                socket.send(sendPacket)

                val endTime = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(2048)

                while (System.currentTimeMillis() < endTime) {
                    try {
                        val recvPacket = DatagramPacket(buf, buf.size)
                        socket.receive(recvPacket)
                        val response = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                        val location = extractHeader(response, "LOCATION")
                        if (!location.isNullOrBlank()) {
                            locations.add(location.trim())
                        }
                    } catch (_: SocketTimeoutException) {
                        // Continue until timeoutMs expires
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }

            for (loc in locations) {
                val device = parseDeviceDescription(loc)
                if (device != null) {
                    foundByControlUrl[device.controlUrl] = device
                }
            }

            foundByControlUrl.values.toList()
        }

    suspend fun playMediaOnDevice(
        device: DlnaDevice,
        mediaUrl: String,
        title: String = "PlusCast Media",
        mimeType: String = "video/mp4"
    ): Boolean = withContext(Dispatchers.IO) {
        val escapedUrl = escapeXml(mediaUrl)
        val escapedTitle = escapeXml(title)
        val protocolInfo = "http-get:*:$mimeType:*"

        val didlMetadata = escapeXml(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocolInfo">$escapedUrl</res></item></DIDL-Lite>"""
        )

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

        val uriSetOk = sendSoapAction(
            controlUrl = device.controlUrl,
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = setUriSoap
        )
        if (!uriSetOk) return@withContext false

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
            val url = URL(locationUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null

            val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            val friendlyName = extractTagValue(xml, "friendlyName")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "DLNA Renderer"

            val rawControlUrl = extractAvTransportControlUrl(xml)?.trim()
            if (rawControlUrl.isNullOrEmpty()) return null

            val resolvedControlUrl = URL(url, rawControlUrl).toString()
            DlnaDevice(
                friendlyName = friendlyName,
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
        } catch (e: Exception) {
            e.printStackTrace()
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
        val regex = Regex("<(?:[a-zA-Z0-9_-]+:)?$tagName\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?$tagName>", RegexOption.IGNORE_CASE)
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
