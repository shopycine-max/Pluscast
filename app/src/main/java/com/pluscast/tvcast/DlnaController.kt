package com.pluscast.tvcast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String,
    val serviceType: String = "urn:schemas-upnp-org:service:AVTransport:1"
)

data class DlnaPlayResult(
    val success: Boolean,
    val message: String
)

object DlnaDeviceStore {

    private const val PREFS_NAME = "pluscast_dlna_prefs"
    private const val KEY_NAME = "last_friendly_name"
    private const val KEY_CONTROL_URL = "last_control_url"
    private const val KEY_SERVICE_TYPE = "last_service_type"

    fun saveLastDevice(context: Context, device: DlnaDevice) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, device.friendlyName)
            .putString(KEY_CONTROL_URL, device.controlUrl)
            .putString(KEY_SERVICE_TYPE, device.serviceType)
            .apply()
    }

    fun getLastDevice(context: Context): DlnaDevice? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)?.trim().orEmpty()
        val controlUrl = prefs.getString(KEY_CONTROL_URL, null)?.trim().orEmpty()
        if (name.isEmpty() || controlUrl.isEmpty()) return null

        val serviceType = prefs.getString(KEY_SERVICE_TYPE, null)?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: "urn:schemas-upnp-org:service:AVTransport:1"

        return DlnaDevice(
            friendlyName = name,
            controlUrl = controlUrl,
            serviceType = serviceType
        )
    }

    fun clearLastDevice(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

class DlnaController(private val context: Context? = null) {

    companion object {
        private const val TAG = "PlusCastDlna"
        private const val USER_AGENT = "PlusCast/1.0 (UPnP/1.0; Android)"
    }

    suspend fun discoverRenderers(timeoutMs: Int = 5000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, DlnaDevice>()
            val locations = linkedSetOf<String>()

            val appContext = context?.applicationContext
            if (appContext != null) {
                LocalMediaServer.init(appContext)
            }
            val wifiManager = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = try {
                wifiManager?.createMulticastLock("PlusCastDlnaLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {
                null
            }

            val localWifiIp = getLocalWifiIpv4Address(wifiManager)
            val networkInterface = localWifiIp?.let {
                try {
                    NetworkInterface.getByInetAddress(it)
                } catch (_: Exception) {
                    null
                }
            }

            val searchTargets = listOf(
                "ssdp:all",
                "upnp:rootdevice",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:AVTransport:2"
            )

            var unicastSocket: DatagramSocket? = null
            var multicastSocket: MulticastSocket? = null
            val group = InetAddress.getByName("239.255.255.250")

            try {
                unicastSocket = if (localWifiIp != null) {
                    DatagramSocket(InetSocketAddress(localWifiIp, 0))
                } else {
                    DatagramSocket()
                }.apply {
                    broadcast = true
                    soTimeout = 500
                }

                multicastSocket = MulticastSocket(1900).apply {
                    reuseAddress = true
                    soTimeout = 500
                    try {
                        if (networkInterface != null) {
                            setNetworkInterface(networkInterface)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        if (networkInterface != null) {
                            joinGroup(InetSocketAddress(group, 1900), networkInterface)
                        } else {
                            @Suppress("DEPRECATION")
                            joinGroup(group)
                        }
                    } catch (_: Exception) {
                    }
                }

                for (st in searchTargets) {
                    val query = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 3\r\n")
                        append("ST: $st\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)

                    val packet = DatagramPacket(query, query.size, group, 1900)
                    try {
                        unicastSocket.send(packet)
                    } catch (_: Exception) {
                    }
                    try {
                        multicastSocket.send(packet)
                    } catch (_: Exception) {
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)

                while (System.currentTimeMillis() < deadline) {
                    for (sock in listOfNotNull(unicastSocket, multicastSocket)) {
                        try {
                            val respPacket = DatagramPacket(buffer, buffer.size)
                            sock.receive(respPacket)
                            val text = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
                            extractHeader(text, "LOCATION")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                locations.add(it)
                                Log.d(TAG, "SSDP LOCATION: $it")
                            }
                        } catch (_: SocketTimeoutException) {
                        } catch (e: Exception) {
                            Log.w(TAG, "SSDP receive warning", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SSDP scan error", e)
            } finally {
                try {
                    if (networkInterface != null) {
                        multicastSocket?.leaveGroup(InetSocketAddress(group, 1900), networkInterface)
                    } else {
                        @Suppress("DEPRECATION")
                        multicastSocket?.leaveGroup(group)
                    }
                } catch (_: Exception) {
                }
                try {
                    multicastSocket?.close()
                } catch (_: Exception) {
                }
                try {
                    unicastSocket?.close()
                } catch (_: Exception) {
                }
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {
                }
            }

            for (loc in locations) {
                val parsed = parseDeviceDescription(loc) ?: continue
                if (parsed.controlUrl.isNotBlank()) {
                    found["${parsed.friendlyName}|${parsed.controlUrl}"] = parsed
                }
            }

            found.values.toList()
        }

    suspend fun playMediaOnDevice(
        device: DlnaDevice,
        mediaUrl: String,
        title: String = "PlusCast Media",
        mimeType: String = "video/mp4"
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        if (device.controlUrl.isBlank()) {
            return@withContext DlnaPlayResult(false, "Control URL missing")
        }

        val appContext = context?.applicationContext
        if (appContext != null) {
            LocalMediaServer.init(appContext)
        }

        val effectiveUrl = if (isLocalSource(mediaUrl)) {
            if (appContext == null) {
                return@withContext DlnaPlayResult(false, "Context missing for local media")
            }
            LocalMediaServer.registerAndGetUrl(appContext, mediaUrl, mimeType)
                ?: return@withContext DlnaPlayResult(false, "Local Wi-Fi IP / server start fail")
        } else {
            mediaUrl
        }

        if (effectiveUrl.contains("://127.0.0.1") || effectiveUrl.contains("://localhost")) {
            return@withContext DlnaPlayResult(false, "Loopback URL not reachable by TV")
        }

        val serviceUrn = device.serviceType.ifBlank { "urn:schemas-upnp-org:service:AVTransport:1" }
        val resolvedMime = resolveMimeType(effectiveUrl, mimeType)

        // Best-effort Stop before setting a new URI so previous IPTV/media session closes cleanly
        val stopSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Stop xmlns:u="$serviceUrn">
                  <InstanceID>0</InstanceID>
                </u:Stop>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        sendSoapAction(
            controlUrl = device.controlUrl,
            soapAction = "\"$serviceUrn#Stop\"",
            body = stopSoap
        )

        val escapedUrl = escapeXml(effectiveUrl)
        val escapedTitle = escapeXml(title)
        val escapedMime = escapeXml(resolvedMime)
        val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val protocolInfo = "http-get:*:$escapedMime:$dlnaFlags"

        val rawDidl =
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="$protocolInfo">$escapedUrl</res></item></DIDL-Lite>"""

        fun buildSetUriSoap(metaDataXml: String): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="$serviceUrn">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>$escapedUrl</CurrentURI>
                  <CurrentURIMetaData>${escapeXml(metaDataXml)}</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        var setUriRes = sendSoapAction(
            controlUrl = device.controlUrl,
            soapAction = "\"$serviceUrn#SetAVTransportURI\"",
            body = buildSetUriSoap(rawDidl)
        )

        if (!setUriRes.success) {
            setUriRes = sendSoapAction(
                controlUrl = device.controlUrl,
                soapAction = "\"$serviceUrn#SetAVTransportURI\"",
                body = buildSetUriSoap("")
            )
        }

        if (!setUriRes.success) {
            return@withContext DlnaPlayResult(false, "SetURI fail: ${setUriRes.message}")
        }

        delay(300)

        val playSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="$serviceUrn">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val playRes = sendSoapAction(
            controlUrl = device.controlUrl,
            soapAction = "\"$serviceUrn#Play\"",
            body = playSoap
        )

        if (!playRes.success) {
            return@withContext DlnaPlayResult(false, "Play fail: ${playRes.message}")
        }

        DlnaPlayResult(true, "Playing")
    }

    private fun isLocalSource(url: String): Boolean {
        val lower = url.trim().lowercase(Locale.US)
        return lower.startsWith("content://") ||
            lower.startsWith("file://") ||
            lower.startsWith("/storage/") ||
            lower.startsWith("/sdcard/") ||
            lower.startsWith("/mnt/")
    }

    private fun resolveMimeType(url: String, fallbackMime: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".ts") || lower.contains(".ts?") -> "video/mp2t"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mp3") -> "audio/mpeg"
            else -> fallbackMime.ifBlank { "video/mp4" }
        }
    }

    private fun getLocalWifiIpv4Address(wifiManager: WifiManager?): InetAddress? {
        try {
            @Suppress("DEPRECATION")
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ipInt).array()
                val addr = InetAddress.getByAddress(bytes)
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr
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
                        return addr
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun parseDeviceDescription(locationUrl: String): DlnaDevice? {
        var conn: HttpURLConnection? = null
        return try {
            val baseUrl = URL(locationUrl)
            conn = (baseUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return null

            val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            val friendlyName = extractTagValue(xml, "friendlyName")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val (serviceType, rawControlUrl) = extractAvTransportInfo(xml)
            val resolvedControlUrl = if (!rawControlUrl.isNullOrBlank()) {
                URL(baseUrl, rawControlUrl.trim()).toString()
            } else {
                ""
            }

            DlnaDevice(
                friendlyName = friendlyName,
                controlUrl = resolvedControlUrl,
                serviceType = serviceType ?: "urn:schemas-upnp-org:service:AVTransport:1"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Device description parse failed for $locationUrl", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractAvTransportInfo(xml: String): Pair<String?, String?> {
        val serviceRegex = Regex(
            "<(?:[a-zA-Z0-9_-]+:)?service\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?service>",
            RegexOption.IGNORE_CASE
        )
        for (match in serviceRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val serviceType = extractTagValue(block, "serviceType")?.trim()
            if (serviceType != null && serviceType.contains("AVTransport", ignoreCase = true)) {
                val ctrl = extractTagValue(block, "controlURL")?.trim()
                if (!ctrl.isNullOrBlank()) {
                    return serviceType to ctrl
                }
            }
        }
        return null to extractTagValue(xml, "controlURL")?.trim()
    }

    private fun sendSoapAction(controlUrl: String, soapAction: String, body: String): DlnaPlayResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(controlUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", soapAction)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = try {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val upnpCode = extractTagValue(errBody, "errorCode")?.trim()
                val upnpDesc = extractTagValue(errBody, "errorDescription")?.trim()
                val detail = listOfNotNull(
                    "HTTP $code",
                    upnpCode?.let { "UPnP $it" },
                    upnpDesc?.takeIf { it.isNotEmpty() }
                ).joinToString(" - ")
                return DlnaPlayResult(false, detail)
            }
            DlnaPlayResult(true, "OK")
        } catch (e: Exception) {
            DlnaPlayResult(false, e.message ?: "Network error")
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
