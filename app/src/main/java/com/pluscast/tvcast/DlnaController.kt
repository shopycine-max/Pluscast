package com.pluscast.tvcast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String = "",
    val serviceType: String = "urn:schemas-upnp-org:service:AVTransport:1",
    val dialAppUrl: String = "",
    val hostIp: String = ""
) {
    val supportsDlna: Boolean get() = controlUrl.isNotBlank()
    val supportsDial: Boolean get() = dialAppUrl.isNotBlank() || hostIp.isNotBlank()
}

data class DlnaPlayResult(
    val success: Boolean,
    val message: String,
    val suggestSystemCast: Boolean = false
)

data class CapturedYouTubeStream(
    val videoId: String,
    val streamUrl: String,
    val title: String,
    val mimeType: String,
    val userAgent: String,
    val referrer: String,
    val cookie: String
)

class YouTubeStreamSniffer {

    @Volatile
    var latestStream: CapturedYouTubeStream? = null
        private set

    fun onPageTitleUpdated(currentUrl: String?, title: String?) {
        val current = latestStream ?: return
        val vid = currentUrl?.let { extractVideoId(it) }
        if (vid != null && vid == current.videoId && !title.isNullOrBlank()) {
            latestStream = current.copy(title = title.trim())
        }
    }

    fun inspectRequest(
        pageUrl: String?,
        requestUrl: String?,
        headers: Map<String, String>?,
        currentTitle: String?
    ) {
        val url = requestUrl?.trim().orEmpty()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        if (!isPlayableYouTubeMediaUrl(url)) return

        val lower = url.lowercase(Locale.US)
        val isMp4 = lower.contains("mime=video%2fmp4") || lower.contains("mime=video/mp4")

        val existing = latestStream
        val videoId = pageUrl?.let { extractVideoId(it) }
            ?: existing?.videoId
            ?: "unknown"

        if (existing != null &&
            existing.videoId == videoId &&
            existing.mimeType == "video/mp4" &&
            !isMp4
        ) {
            return
        }

        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        val ua = headers?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
            ?: "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val ref = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
            ?: pageUrl?.takeIf { it.isNotBlank() }
            ?: "https://m.youtube.com/"

        val mime = when {
            isMp4 -> "video/mp4"
            lower.contains("mime=video%2fwebm") || lower.contains("mime=video/webm") -> "video/webm"
            lower.contains(".m3u8") -> "application/x-mpegURL"
            else -> "video/mp4"
        }

        latestStream = CapturedYouTubeStream(
            videoId = videoId,
            streamUrl = url,
            title = currentTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "YouTube Video ($videoId)",
            mimeType = mime,
            userAgent = ua,
            referrer = ref,
            cookie = cookie
        )
    }

    fun clear() {
        latestStream = null
    }

    private fun isPlayableYouTubeMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.contains("googlevideo.com/videoplayback")) return false
        val isAudioOnly = lower.contains("mime=audio%2f") || lower.contains("mime=audio/")
        return !isAudioOnly
    }

    private fun extractVideoId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        if (host.contains("youtu.be")) {
            val seg = uri.lastPathSegment?.trim().orEmpty()
            if (seg.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return seg
        }

        uri.getQueryParameter("v")?.trim()
            ?.takeIf { it.matches(Regex("^[a-zA-Z0-9_-]{11}$")) }
            ?.let { return it }

        val segs = uri.pathSegments.orEmpty()
        for (i in 0 until segs.size - 1) {
            if (segs[i].lowercase(Locale.US) in setOf("shorts", "embed", "live", "v")) {
                val cand = segs[i + 1].trim()
                if (cand.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return cand
            }
        }
        return null
    }
}

object DlnaDeviceStore {

    private const val PREFS_NAME = "pluscast_dlna_prefs"
    private const val KEY_NAME = "last_friendly_name"
    private const val KEY_CONTROL_URL = "last_control_url"
    private const val KEY_SERVICE_TYPE = "last_service_type"
    private const val KEY_DIAL_APP_URL = "last_dial_app_url"
    private const val KEY_HOST_IP = "last_host_ip"

    fun saveLastDevice(context: Context, device: DlnaDevice) {
        val safeName = device.friendlyName.trim()
        val safeControlUrl = device.controlUrl.trim()
        val safeDialUrl = device.dialAppUrl.trim()
        val safeHostIp = device.hostIp.trim()
        if (safeName.isEmpty() || (!isValidHttpUrl(safeControlUrl) && !isValidHttpUrl(safeDialUrl) && safeHostIp.isEmpty())) {
            return
        }

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, safeName)
            .putString(KEY_CONTROL_URL, safeControlUrl)
            .putString(
                KEY_SERVICE_TYPE,
                device.serviceType.trim().ifBlank { "urn:schemas-upnp-org:service:AVTransport:1" }
            )
            .putString(KEY_DIAL_APP_URL, safeDialUrl)
            .putString(KEY_HOST_IP, safeHostIp)
            .apply()
    }

    fun getLastDevice(context: Context): DlnaDevice? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null)?.trim().orEmpty()
        val controlUrl = prefs.getString(KEY_CONTROL_URL, null)?.trim().orEmpty()
        val dialAppUrl = prefs.getString(KEY_DIAL_APP_URL, null)?.trim().orEmpty()
        val hostIp = prefs.getString(KEY_HOST_IP, null)?.trim().orEmpty()

        if (name.isEmpty() || (!isValidHttpUrl(controlUrl) && !isValidHttpUrl(dialAppUrl) && hostIp.isEmpty())) {
            return null
        }

        val serviceType = prefs.getString(KEY_SERVICE_TYPE, null)?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: "urn:schemas-upnp-org:service:AVTransport:1"

        return DlnaDevice(
            friendlyName = name,
            controlUrl = controlUrl,
            serviceType = serviceType,
            dialAppUrl = dialAppUrl,
            hostIp = hostIp
        )
    }

    fun clearLastDevice(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun isValidHttpUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }
}

class DlnaController(
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "PlusCastDlna"
        private const val USER_AGENT = "PlusCast/1.0 (UPnP/1.0; Android)"
        private const val SSDP_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    }

    private var localMediaServer: LocalMediaHttpServer? = null
    private var remoteStreamServer: RemoteStreamHttpServer? = null

    suspend fun discoverRenderers(timeoutMs: Int = 5000): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            val foundByHost = linkedMapOf<String, DlnaDevice>()
            val locations = linkedMapOf<String, String>()

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

            val localWifiIp = getLocalWifiIpv4Address(wifiManager)
            val networkInterface = localWifiIp?.let {
                try {
                    NetworkInterface.getByInetAddress(it)
                } catch (_: Exception) {
                    null
                }
            }

            val searchTargets = listOf(
                "urn:dial-multiscreen-org:service:dial:1",
                "urn:dial-multiscreen-org:device:dial:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:service:AVTransport:2",
                "upnp:rootdevice"
            )

            var unicastSocket: DatagramSocket? = null
            var multicastSocket: MulticastSocket? = null
            var joinedMulticast = false
            val group = InetAddress.getByName(SSDP_GROUP)

            try {
                unicastSocket = if (localWifiIp != null) {
                    DatagramSocket(InetSocketAddress(localWifiIp, 0))
                } else {
                    DatagramSocket()
                }.apply {
                    soTimeout = 300
                }

                multicastSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true
                    soTimeout = 300
                    try {
                        if (networkInterface != null) {
                            setNetworkInterface(networkInterface)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        if (networkInterface != null) {
                            joinGroup(InetSocketAddress(group, SSDP_PORT), networkInterface)
                            joinedMulticast = true
                        } else {
                            @Suppress("DEPRECATION")
                            joinGroup(group)
                            joinedMulticast = true
                        }
                    } catch (_: Exception) {
                        joinedMulticast = false
                    }
                }

                repeat(2) {
                    for (st in searchTargets) {
                        val query = buildString {
                            append("M-SEARCH * HTTP/1.1\r\n")
                            append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
                            append("MAN: \"ssdp:discover\"\r\n")
                            append("MX: 2\r\n")
                            append("ST: $st\r\n\r\n")
                        }.toByteArray(Charsets.UTF_8)

                        val packet = DatagramPacket(query, query.size, group, SSDP_PORT)
                        try {
                            if (joinedMulticast) {
                                multicastSocket.send(packet)
                            } else {
                                unicastSocket.send(packet)
                            }
                        } catch (_: Exception) {
                            try {
                                unicastSocket.send(packet)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    delay(120)
                }

                val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1500, 15000)
                val buffer = ByteArray(4096)

                while (System.currentTimeMillis() < deadline) {
                    for (sock in listOfNotNull(unicastSocket, multicastSocket)) {
                        try {
                            val respPacket = DatagramPacket(buffer, buffer.size)
                            sock.receive(respPacket)
                            val text = String(respPacket.data, 0, respPacket.length, Charsets.UTF_8)
                            val loc = extractHeader(text, "LOCATION")?.trim()?.takeIf { isValidHttpUrl(it) }
                            if (loc != null) {
                                val dialUrl = extractHeader(text, "APPLICATION-URL")?.trim().orEmpty()
                                val existingDial = locations[loc].orEmpty()
                                locations[loc] = existingDial.ifBlank { dialUrl }
                                Log.d(TAG, "SSDP LOCATION: $loc | DIAL: ${locations[loc]}")
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
                    if (joinedMulticast) {
                        if (networkInterface != null) {
                            multicastSocket?.leaveGroup(InetSocketAddress(group, SSDP_PORT), networkInterface)
                        } else {
                            @Suppress("DEPRECATION")
                            multicastSocket?.leaveGroup(group)
                        }
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

            for ((loc, hintDial) in locations) {
                val parsed = parseDeviceDescription(loc, hintDial) ?: continue
                val key = parsed.hostIp.ifBlank { parsed.friendlyName.lowercase(Locale.US) }
                val existing = foundByHost[key]
                if (existing == null) {
                    foundByHost[key] = parsed
                } else {
                    foundByHost[key] = existing.copy(
                        friendlyName = existing.friendlyName.ifBlank { parsed.friendlyName },
                        controlUrl = existing.controlUrl.ifBlank { parsed.controlUrl },
                        serviceType = if (existing.controlUrl.isNotBlank()) existing.serviceType else parsed.serviceType,
                        dialAppUrl = existing.dialAppUrl.ifBlank { parsed.dialAppUrl },
                        hostIp = existing.hostIp.ifBlank { parsed.hostIp }
                    )
                }
            }

            foundByHost.values.toList()
        }

    private fun enrichWithStoredDevice(device: DlnaDevice): DlnaDevice {
        val appContext = context?.applicationContext ?: return device
        val stored = DlnaDeviceStore.getLastDevice(appContext) ?: return device
        val sameDevice = stored.friendlyName.equals(device.friendlyName, ignoreCase = true) ||
            (stored.hostIp.isNotBlank() && stored.hostIp == device.hostIp) ||
            (stored.controlUrl.isNotBlank() && stored.controlUrl == device.controlUrl)
        if (!sameDevice) return device

        return device.copy(
            controlUrl = device.controlUrl.ifBlank { stored.controlUrl },
            serviceType = if (device.controlUrl.isNotBlank()) device.serviceType else stored.serviceType,
            dialAppUrl = device.dialAppUrl.ifBlank { stored.dialAppUrl },
            hostIp = device.hostIp.ifBlank { stored.hostIp }
        )
    }

    suspend fun castMedia(
        device: DlnaDevice,
        mediaUrlOrUri: String,
        title: String = "PlusCast Media",
        mimeType: String = "application/x-mpegURL",
        capturedStream: CapturedYouTubeStream? = null,
        userAgent: String = "",
        referrer: String = "",
        cookie: String = ""
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val enriched = enrichWithStoredDevice(device)
        val trimmed = mediaUrlOrUri.trim()
        if (trimmed.isEmpty()) {
            return@withContext DlnaPlayResult(false, "Empty media URL or URI")
        }

        if (isYouTubeInput(trimmed)) {
            return@withContext playYouTubeTubioStyle(
                device = enriched,
                youtubeUrlOrId = trimmed,
                capturedStream = capturedStream,
                fallbackTitle = title,
                fallbackMimeType = mimeType
            )
        }

        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("content://") || lower.startsWith("file://")) {
            return@withContext playLocalMediaOnDevice(
                device = enriched,
                uri = Uri.parse(trimmed),
                title = title,
                mimeType = mimeType
            )
        }

        return@withContext playIptvOrRemoteStream(
            device = enriched,
            mediaUrl = trimmed,
            title = title,
            mimeType = mimeType,
            userAgent = userAgent,
            referrer = referrer,
            cookie = cookie
        )
    }

    suspend fun playIptvOrRemoteStream(
        device: DlnaDevice,
        mediaUrl: String,
        title: String = "PlusCast IPTV",
        mimeType: String = "application/x-mpegURL",
        userAgent: String = "",
        referrer: String = "",
        cookie: String = ""
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val resolvedDevice = enrichWithStoredDevice(device)
        val cleanUrl = mediaUrl.trim()
        if (!isValidHttpUrl(cleanUrl) || isLoopbackOrLocalhost(cleanUrl)) {
            return@withContext playMediaOnDevice(
                device = resolvedDevice,
                mediaUrl = cleanUrl,
                title = title,
                mimeType = mimeType
            )
        }

        val directResult = playMediaOnDevice(
            device = resolvedDevice,
            mediaUrl = cleanUrl,
            title = title,
            mimeType = mimeType
        )
        if (directResult.success) {
            return@withContext directResult
        }

        Log.w(TAG, "Direct DLNA failed for $title (${directResult.message}), trying local HTTP relay")
        val proxyResult = playViaLocalRelay(
            device = resolvedDevice,
            remoteUrl = cleanUrl,
            title = title,
            mimeType = resolveMimeType(cleanUrl, mimeType),
            userAgent = userAgent,
            referrer = referrer,
            cookie = cookie
        )
        return@withContext if (proxyResult.success) {
            proxyResult
        } else {
            DlnaPlayResult(
                success = false,
                message = "$title play nahi ho saka (${directResult.message} / ${proxyResult.message})"
            )
        }
    }

    private suspend fun playViaLocalRelay(
        device: DlnaDevice,
        remoteUrl: String,
        title: String,
        mimeType: String,
        userAgent: String,
        referrer: String,
        cookie: String
    ): DlnaPlayResult {
        val appContext = context?.applicationContext
            ?: return DlnaPlayResult(false, "Context unavailable for relay")
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val localIp = getLocalWifiIpv4Address(wifiManager)?.hostAddress
            ?: return DlnaPlayResult(false, "Wi-Fi IPv4 address nahi mila")

        val effectiveUa = userAgent.trim().ifEmpty {
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        val effectiveRef = referrer.trim().ifEmpty {
            runCatching {
                val u = URL(remoteUrl)
                "${u.protocol}://${u.host}/"
            }.getOrDefault("")
        }

        remoteStreamServer?.stopServer()
        val server = RemoteStreamHttpServer(
            bindIp = localIp,
            remoteUrl = remoteUrl,
            mimeType = mimeType.ifBlank { "application/x-mpegURL" },
            userAgent = effectiveUa,
            referrer = effectiveRef,
            cookie = cookie.trim()
        )
        val port = server.startServer()
            ?: return DlnaPlayResult(false, "Relay server start nahi ho saka")

        remoteStreamServer = server
        val proxyUrl = "http://$localIp:$port/stream"
        Log.d(TAG, "Relay URL: $proxyUrl | mime=$mimeType | target=$remoteUrl")

        val result = playMediaOnDevice(
            device = device,
            mediaUrl = proxyUrl,
            title = title.trim().ifEmpty { "PlusCast IPTV" },
            mimeType = mimeType.ifBlank { "application/x-mpegURL" }
        )
        if (!result.success) {
            server.stopServer()
            if (remoteStreamServer === server) {
                remoteStreamServer = null
            }
        }
        return result
    }

    suspend fun playYouTubeTubioStyle(
        device: DlnaDevice,
        youtubeUrlOrId: String,
        capturedStream: CapturedYouTubeStream? = null,
        fallbackTitle: String = "YouTube Video",
        fallbackMimeType: String = "video/mp4"
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val resolvedDevice = enrichWithStoredDevice(device)
        val videoId = extractYouTubeVideoId(youtubeUrlOrId)
            ?: capturedStream?.videoId?.takeIf { it.matches(YT_ID_REGEX) }
            ?: return@withContext DlnaPlayResult(
                success = false,
                message = "Pehle koi YouTube video open karein (video link/ID nahi mila)",
                suggestSystemCast = false
            )

        val dialCandidates = resolveDialBaseUrls(resolvedDevice)
        Log.d(TAG, "Tubio-style YouTube -> DIAL check | videoId=$videoId | candidates=$dialCandidates")

        for (dialBase in dialCandidates) {
            if (tryLaunchYouTubeViaDial(dialBase, videoId)) {
                return@withContext DlnaPlayResult(
                    success = true,
                    message = "${resolvedDevice.friendlyName} ke YouTube app mein video play ho raha hai",
                    suggestSystemCast = false
                )
            }
        }

        val matchingStream = capturedStream?.takeIf {
            it.videoId == "unknown" || it.videoId == videoId
        }
        val cleanStreamUrl = matchingStream?.streamUrl?.trim().orEmpty()

        if (isValidHttpUrl(cleanStreamUrl) && resolvedDevice.controlUrl.isNotBlank()) {
            Log.d(TAG, "Tubio-style YouTube -> DIAL unavailable, trying DLNA proxy for captured stream (mime=${matchingStream?.mimeType})")
            val proxyResult = playCapturedYouTubeStream(
                device = resolvedDevice,
                streamUrl = cleanStreamUrl,
                title = matchingStream?.title?.trim()?.takeIf { it.isNotEmpty() }
                    ?: fallbackTitle.trim().ifEmpty { "YouTube Video ($videoId)" },
                userAgent = matchingStream?.userAgent.orEmpty(),
                referrer = matchingStream?.referrer.orEmpty(),
                cookie = matchingStream?.cookie.orEmpty(),
                mimeType = matchingStream?.mimeType?.ifBlank { fallbackMimeType } ?: fallbackMimeType
            )
            if (proxyResult.success) {
                return@withContext DlnaPlayResult(
                    success = true,
                    message = "${resolvedDevice.friendlyName} par YouTube stream play ho raha hai",
                    suggestSystemCast = false
                )
            } else {
                return@withContext DlnaPlayResult(
                    success = false,
                    message = "${resolvedDevice.friendlyName} par YouTube play nahi ho saka (DIAL band hai aur DLNA stream ${proxyResult.message} hai)",
                    suggestSystemCast = true
                )
            }
        }

        return@withContext DlnaPlayResult(
            success = false,
            message = if (cleanStreamUrl.isBlank()) {
                "${resolvedDevice.friendlyName} par YouTube DIAL nahi mila aur stream URL bhi capture nahi hua. Video play karke dekhein ya Cast/Mirroring use karein."
            } else {
                "${resolvedDevice.friendlyName} par YouTube DIAL aur DLNA stream dono play nahi ho sake."
            },
            suggestSystemCast = true
        )
    }

    suspend fun playCapturedYouTubeStream(
        device: DlnaDevice,
        streamUrl: String,
        title: String = "YouTube Video",
        userAgent: String = "",
        referrer: String = "",
        cookie: String = "",
        mimeType: String = "video/mp4"
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val resolvedDevice = enrichWithStoredDevice(device)
        val cleanStreamUrl = streamUrl.trim()
        if (!isValidHttpUrl(cleanStreamUrl)) {
            return@withContext DlnaPlayResult(false, "Captured YouTube stream URL invalid hai")
        }

        playViaLocalRelay(
            device = resolvedDevice,
            remoteUrl = cleanStreamUrl,
            title = title.trim().ifEmpty { "YouTube Video" },
            mimeType = mimeType.ifBlank { "video/mp4" },
            userAgent = userAgent,
            referrer = referrer.ifEmpty { "https://m.youtube.com/" },
            cookie = cookie
        )
    }

    suspend fun playYouTubeOnDevice(
        device: DlnaDevice,
        youtubeUrlOrId: String,
        capturedStream: CapturedYouTubeStream? = null
    ): DlnaPlayResult = playYouTubeTubioStyle(
        device = device,
        youtubeUrlOrId = youtubeUrlOrId,
        capturedStream = capturedStream
    )

    fun openYouTubeOnPhone(youtubeUrlOrId: String): Boolean {
        val appContext = context?.applicationContext ?: return false
        val videoId = extractYouTubeVideoId(youtubeUrlOrId)
        val targetUrl = if (videoId != null) {
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            youtubeUrlOrId.trim().takeIf { isValidHttpUrl(it) } ?: return false
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openSystemCastSettings(): Boolean {
        val appContext = context?.applicationContext ?: return false
        return try {
            val intent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun resolveDialBaseUrls(device: DlnaDevice): List<String> {
        val urls = linkedSetOf<String>()

        fun addDialBase(raw: String) {
            val cleaned = raw.trim().trimEnd('/')
            if (!isValidHttpUrl(cleaned)) return
            urls.add(cleaned)
            if (!cleaned.endsWith("/apps", ignoreCase = true) && !cleaned.endsWith("/ws/apps", ignoreCase = true)) {
                urls.add("$cleaned/apps")
            }
        }

        if (device.dialAppUrl.isNotBlank()) {
            addDialBase(device.dialAppUrl)
        }

        val controlParsedUrl = runCatching {
            if (device.controlUrl.isNotBlank()) URL(device.controlUrl) else null
        }.getOrNull()

        val host = device.hostIp.ifBlank { controlParsedUrl?.host.orEmpty() }
        val controlPort = controlParsedUrl?.port?.takeIf { it > 0 }

        if (controlParsedUrl != null && host.isNotBlank()) {
            val effectivePort = controlPort ?: if (controlParsedUrl.protocol.equals("https", true)) 443 else 80
            val baseOrigin = "${controlParsedUrl.protocol}://$host:$effectivePort"
            addDialBase("$baseOrigin/apps")
            addDialBase("$baseOrigin/ws/apps")
            addDialBase("$baseOrigin/dial/apps")
        }

        if (host.isNotBlank()) {
            val descUrls = buildList {
                if (controlPort != null) {
                    add("http://$host:$controlPort/ssdp/device-desc.xml")
                    add("http://$host:$controlPort/dd.xml")
                    add("http://$host:$controlPort/dial/dd.xml")
                }
                addAll(
                    listOf(
                        "http://$host:8008/ssdp/device-desc.xml",
                        "http://$host:8008/dd.xml",
                        "http://$host:8060/dial/dd.xml",
                        "http://$host:56789/dial/dd.xml",
                        "http://$host:7676/smp_2_",
                        "http://$host:36299/dd.xml",
                        "http://$host:52235/dd.xml"
                    )
                )
            }
            for (descUrl in descUrls) {
                val dialFromDesc = probeDialApplicationUrl(descUrl)
                if (!dialFromDesc.isNullOrBlank()) {
                    addDialBase(dialFromDesc)
                }
            }

            addDialBase("http://$host:8008/apps")
            addDialBase("http://$host:8008/ws/apps")
            addDialBase("http://$host:8060/apps")
            addDialBase("http://$host:56789/apps")
            addDialBase("http://$host:36299/apps")
            addDialBase("http://$host:52235/apps")
        }

        return urls.toList()
    }

    private fun probeDialApplicationUrl(descUrl: String): String? {
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(descUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1200
                    readTimeout = 1200
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection = conn
                if (conn.responseCode !in 200..299) return null

                val headerUrl = conn.getHeaderField("Application-URL")?.trim()
                if (!headerUrl.isNullOrBlank() && isValidHttpUrl(headerUrl)) {
                    return headerUrl
                }

                val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                val xmlAppUrl = extractTagValue(xml, "applicationURL")?.trim()
                if (!xmlAppUrl.isNullOrBlank() && isValidHttpUrl(xmlAppUrl)) {
                    return xmlAppUrl
                }
                null
            } finally {
                connection?.disconnect()
            }
        }.getOrNull()
    }

    private fun tryLaunchYouTubeViaDial(dialBaseUrl: String, videoId: String): Boolean {
        val cleanBase = dialBaseUrl.trim().trimEnd('/')
        val appEndpoints = linkedSetOf(
            "$cleanBase/YouTube",
            "$cleanBase/youtube"
        )
        if (!cleanBase.endsWith("/apps", ignoreCase = true) && !cleanBase.endsWith("/ws/apps", ignoreCase = true)) {
            appEndpoints.add("$cleanBase/apps/YouTube")
            appEndpoints.add("$cleanBase/apps/youtube")
        }

        val payloads = listOf(
            "v=$videoId" to "text/plain; charset=utf-8",
            "v=$videoId&t=0" to "text/plain; charset=utf-8",
            "v=$videoId" to "application/x-www-form-urlencoded; charset=utf-8"
        )

        for (endpoint in appEndpoints) {
            val probeCode = probeDialAppStatus(endpoint)
            if (probeCode == 404) continue

            for ((payload, contentType) in payloads) {
                val launched = runCatching {
                    var connection: HttpURLConnection? = null
                    try {
                        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 2500
                            readTimeout = 2500
                            requestMethod = "POST"
                            doOutput = true
                            instanceFollowRedirects = false
                            setRequestProperty("User-Agent", USER_AGENT)
                            setRequestProperty("Origin", "https://www.youtube.com")
                            setRequestProperty("Content-Type", contentType)
                        }
                        connection = conn
                        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                            writer.write(payload)
                            writer.flush()
                        }
                        val code = conn.responseCode
                        val loc = conn.getHeaderField("Location")
                        Log.d(TAG, "DIAL POST $endpoint ($payload) -> HTTP $code | Location=$loc")
                        code in 200..204 || code == 302
                    } finally {
                        connection?.disconnect()
                    }
                }.getOrElse { e ->
                    Log.d(TAG, "DIAL POST error on $endpoint: ${e.message}")
                    false
                }
                if (launched) return true
            }
        }
        return false
    }

    private fun probeDialAppStatus(endpoint: String): Int {
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1000
                    readTimeout = 1000
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection = conn
                conn.responseCode
            } finally {
                connection?.disconnect()
            }
        }.getOrDefault(-1)
    }

    suspend fun playLocalMediaOnDevice(
        device: DlnaDevice,
        uri: Uri,
        title: String = "Local Media",
        mimeType: String = "video/mp4"
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val resolvedDevice = enrichWithStoredDevice(device)
        val appContext = context?.applicationContext
            ?: return@withContext DlnaPlayResult(false, "Context unavailable for local media streaming")

        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val localIp = getLocalWifiIpv4Address(wifiManager)?.hostAddress
            ?: return@withContext DlnaPlayResult(false, "Wi-Fi IPv4 address nahi mila")

        val resolvedMime = appContext.contentResolver.getType(uri)
            ?: guessMimeTypeFromUri(uri, mimeType)
        val fileSize = queryFileSize(appContext, uri)
        val safeTitle = title.trim().ifEmpty {
            queryDisplayName(appContext, uri) ?: "Local Media"
        }

        localMediaServer?.stopServer()
        val server = LocalMediaHttpServer(
            context = appContext,
            bindIp = localIp,
            mediaUri = uri,
            mimeType = resolvedMime,
            contentLength = fileSize
        )
        val port = server.startServer()
            ?: return@withContext DlnaPlayResult(false, "Local HTTP relay server start nahi ho saka")

        localMediaServer = server
        val streamUrl = "http://$localIp:$port/media"
        Log.d(TAG, "Local media relay URL: $streamUrl (mime=$resolvedMime, size=$fileSize)")

        val result = playMediaOnDevice(
            device = resolvedDevice,
            mediaUrl = streamUrl,
            title = safeTitle,
            mimeType = resolvedMime
        )
        if (!result.success) {
            server.stopServer()
            if (localMediaServer === server) {
                localMediaServer = null
            }
        }
        result
    }

    fun releaseLocalMediaServer() {
        localMediaServer?.stopServer()
        localMediaServer = null
        remoteStreamServer?.stopServer()
        remoteStreamServer = null
    }

    suspend fun playMediaOnDevice(
        device: DlnaDevice,
        mediaUrl: String,
        title: String = "PlusCast IPTV",
        mimeType: String = "application/x-mpegURL"
    ): DlnaPlayResult = withContext(Dispatchers.IO) {
        val resolvedDevice = enrichWithStoredDevice(device)
        val controlUrl = resolvedDevice.controlUrl.trim()
        if (controlUrl.isBlank() || !isValidHttpUrl(controlUrl)) {
            return@withContext DlnaPlayResult(false, "Is device par DLNA AVTransport control URL nahi mila")
        }

        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isEmpty() || !isValidHttpUrl(cleanUrl)) {
            return@withContext DlnaPlayResult(false, "Sirf HTTP/HTTPS stream URL supported hai")
        }

        if (isLoopbackOrLocalhost(cleanUrl)) {
            return@withContext DlnaPlayResult(false, "Loopback URL TV se reachable nahi hai")
        }

        val serviceUrn = resolvedDevice.serviceType.trim().ifBlank {
            "urn:schemas-upnp-org:service:AVTransport:1"
        }
        val safeTitle = title.trim().ifEmpty { "PlusCast IPTV" }
        val resolvedMime = resolveMimeType(cleanUrl, mimeType)
        val upnpClass = if (resolvedMime.startsWith("audio/", ignoreCase = true)) {
            "object.item.audioItem.musicTrack"
        } else {
            "object.item.videoItem"
        }

        sendStopAction(controlUrl, serviceUrn)

        return@withContext trySetUriAndPlay(
            controlUrl = controlUrl,
            serviceUrn = serviceUrn,
            mediaUrl = cleanUrl,
            title = safeTitle,
            mimeType = resolvedMime,
            upnpClass = upnpClass
        )
    }

    private suspend fun trySetUriAndPlay(
        controlUrl: String,
        serviceUrn: String,
        mediaUrl: String,
        title: String,
        mimeType: String,
        upnpClass: String
    ): DlnaPlayResult {
        val escapedUrl = escapeXml(mediaUrl)
        val escapedTitle = escapeXml(title)
        val escapedMime = escapeXml(mimeType)
        val protocolInfo = "http-get:*:$escapedMime:*"

        val didl =
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>$escapedTitle</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="$protocolInfo">$escapedUrl</res></item></DIDL-Lite>"""

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
            controlUrl = controlUrl,
            soapAction = "\"$serviceUrn#SetAVTransportURI\"",
            body = buildSetUriSoap(didl)
        )

        if (!setUriRes.success) {
            Log.w(TAG, "SetAVTransportURI with DIDL failed (${setUriRes.message}), retrying with empty metadata")
            setUriRes = sendSoapAction(
                controlUrl = controlUrl,
                soapAction = "\"$serviceUrn#SetAVTransportURI\"",
                body = buildSetUriSoap("")
            )
        }

        if (!setUriRes.success) {
            Log.w(TAG, "SetAVTransportURI failed on $controlUrl: ${setUriRes.message}")
            return DlnaPlayResult(false, "SetURI fail: ${setUriRes.message}")
        }

        delay(350)
        val playRes = sendPlayAction(controlUrl, serviceUrn)
        if (!playRes.success) return playRes

        delay(1200)
        var transportState = queryTransportState(controlUrl, serviceUrn)
        if (transportState.equals("TRANSITIONING", ignoreCase = true) ||
            transportState.equals("UNKNOWN", ignoreCase = true)
        ) {
            delay(1600)
            transportState = queryTransportState(controlUrl, serviceUrn)
        }

        Log.d(TAG, "Post-Play TransportState on $controlUrl -> $transportState")
        if (transportState.equals("STOPPED", ignoreCase = true) ||
            transportState.equals("NO_MEDIA_PRESENT", ignoreCase = true)
        ) {
            return DlnaPlayResult(
                false,
                "Renderer stream decode/play nahi kar paya ($transportState)"
            )
        }

        return DlnaPlayResult(true, "Playing")
    }

    private fun sendStopAction(controlUrl: String, serviceUrn: String) {
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
            controlUrl = controlUrl,
            soapAction = "\"$serviceUrn#Stop\"",
            body = stopSoap
        )
    }

    private fun sendPlayAction(controlUrl: String, serviceUrn: String): DlnaPlayResult {
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
            controlUrl = controlUrl,
            soapAction = "\"$serviceUrn#Play\"",
            body = playSoap
        )
        return if (!playRes.success) {
            Log.w(TAG, "Play action failed on $controlUrl: ${playRes.message}")
            DlnaPlayResult(false, "Play fail: ${playRes.message}")
        } else {
            DlnaPlayResult(true, "Playing")
        }
    }

    private fun queryTransportState(controlUrl: String, serviceUrn: String): String {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetTransportInfo xmlns:u="$serviceUrn">
                  <InstanceID>0</InstanceID>
                </u:GetTransportInfo>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("SOAPAction", "\"$serviceUrn#GetTransportInfo\"")
                }
                connection = conn
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(soapBody)
                    writer.flush()
                }
                if (conn.responseCode !in 200..299) return "UNKNOWN"
                val respXml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                extractTagValue(respXml, "CurrentTransportState")?.trim().orEmpty().ifBlank { "UNKNOWN" }
            } finally {
                connection?.disconnect()
            }
        }.getOrDefault("UNKNOWN")
    }

    private fun isYouTubeInput(input: String): Boolean {
        if (input.matches(YT_ID_REGEX)) return true
        val lower = input.lowercase(Locale.US)
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }

    private fun extractYouTubeVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.matches(YT_ID_REGEX)) {
            return trimmed
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        if (host.contains("youtu.be")) {
            val seg = uri.lastPathSegment?.trim().orEmpty()
            if (seg.matches(YT_ID_REGEX)) return seg
        }

        uri.getQueryParameter("v")?.trim()?.let { v ->
            if (v.matches(YT_ID_REGEX)) return v
        }

        val segments = uri.pathSegments.orEmpty()
        for (i in 0 until segments.size - 1) {
            val tag = segments[i].lowercase(Locale.US)
            if (tag == "shorts" || tag == "embed" || tag == "live" || tag == "v") {
                val candidate = segments[i + 1].trim()
                if (candidate.matches(YT_ID_REGEX)) return candidate
            }
        }
        return null
    }

    private fun guessMimeTypeFromUri(uri: Uri, fallback: String): String {
        val lower = uri.toString().lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".flac") -> "audio/flac"
            else -> fallback.ifBlank { "video/mp4" }
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
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

    private fun queryDisplayName(context: Context, uri: Uri): String? {
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

    private fun isValidHttpUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun isLoopbackOrLocalhost(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("://127.0.0.1") ||
            lower.contains("://localhost") ||
            lower.contains("://0.0.0.0") ||
            lower.contains("://[::1]")
    }

    private fun resolveMimeType(url: String, fallbackMime: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".ts") || lower.contains(".ts?") -> "video/mp2t"
            lower.contains(".mp4") || lower.contains("mime=video%2fmp4") || lower.contains("mime=video/mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") || lower.contains("mime=video%2fwebm") || lower.contains("mime=video/webm") -> "video/webm"
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

    private fun parseDeviceDescription(locationUrl: String, hintDialUrl: String): DlnaDevice? {
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val baseUrl = URL(locationUrl)
                val conn = (baseUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection = conn
                if (conn.responseCode !in 200..299) return null

                val headerDial = conn.getHeaderField("Application-URL")?.trim().orEmpty()
                val xml = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                val xmlAppUrl = extractTagValue(xml, "applicationURL")?.trim().orEmpty()
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

                val resolvedDialUrl = when {
                    headerDial.isNotBlank() && isValidHttpUrl(headerDial) -> headerDial
                    xmlAppUrl.isNotBlank() && isValidHttpUrl(xmlAppUrl) -> xmlAppUrl
                    hintDialUrl.isNotBlank() && isValidHttpUrl(hintDialUrl) -> hintDialUrl
                    else -> ""
                }

                val hostIp = baseUrl.host.orEmpty()
                if (!isValidHttpUrl(resolvedControlUrl) && !isValidHttpUrl(resolvedDialUrl) && hostIp.isBlank()) {
                    return null
                }

                DlnaDevice(
                    friendlyName = friendlyName,
                    controlUrl = resolvedControlUrl,
                    serviceType = serviceType ?: "urn:schemas-upnp-org:service:AVTransport:1",
                    dialAppUrl = resolvedDialUrl,
                    hostIp = hostIp
                )
            } finally {
                connection?.disconnect()
            }
        }.getOrElse { e ->
            Log.w(TAG, "Device description parse failed for $locationUrl", e)
            null
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
        return null to null
    }

    private fun sendSoapAction(controlUrl: String, soapAction: String, body: String): DlnaPlayResult {
        return runCatching {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(controlUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 5000
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("SOAPAction", soapAction)
                }
                connection = conn
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
                    Log.w(TAG, "SOAP error [$soapAction] on $controlUrl -> $detail")
                    return DlnaPlayResult(false, detail)
                }
                DlnaPlayResult(true, "OK")
            } finally {
                connection?.disconnect()
            }
        }.getOrElse { e ->
            Log.w(TAG, "SOAP network exception [$soapAction] on $controlUrl: ${e.message}")
            DlnaPlayResult(false, e.message ?: "Network error")
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

    private class RemoteStreamHttpServer(
        private val bindIp: String,
        private val remoteUrl: String,
        private val mimeType: String,
        private val userAgent: String,
        private val referrer: String,
        private val cookie: String
    ) {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val clientPool = Executors.newCachedThreadPool()

        fun startServer(): Int? {
            return try {
                val addr = InetAddress.getByName(bindIp)
                val sock = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(addr, 0), 16)
                    soTimeout = 1000
                }
                serverSocket = sock
                running.set(true)

                acceptThread = Thread({
                    while (running.get()) {
                        try {
                            val client = sock.accept()
                            clientPool.execute { handleClient(client) }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                            if (!running.get()) break
                        }
                    }
                }, "PlusCastStreamProxy").apply {
                    isDaemon = true
                    start()
                }

                sock.localPort
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start remote stream proxy", e)
                stopServer()
                null
            }
        }

        fun stopServer() {
            running.set(false)
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            try {
                acceptThread?.join(500)
            } catch (_: InterruptedException) {
            }
            acceptThread = null
            clientPool.shutdownNow()
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.use { client ->
                    client.soTimeout = 15000
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    val method = parts.getOrNull(0)?.uppercase(Locale.US).orEmpty()

                    var rangeHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            rangeHeader = line.substringAfter(':').trim()
                        }
                    }

                    if (method != "GET" && method != "HEAD") {
                        writeSimpleResponse(client, "405 Method Not Allowed", "Method Not Allowed")
                        return
                    }

                    var upstream: HttpURLConnection? = null
                    try {
                        val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 25000
                            requestMethod = if (method == "HEAD") "HEAD" else "GET"
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", userAgent)
                            if (referrer.isNotBlank()) {
                                setRequestProperty("Referer", referrer)
                                val origin = runCatching {
                                    val u = URL(referrer)
                                    "${u.protocol}://${u.host}"
                                }.getOrDefault("https://m.youtube.com")
                                setRequestProperty("Origin", origin)
                            }
                            if (cookie.isNotBlank()) {
                                setRequestProperty("Cookie", cookie)
                            }
                            if (!rangeHeader.isNullOrBlank()) {
                                setRequestProperty("Range", rangeHeader)
                            }
                        }
                        upstream = conn
                        val code = conn.responseCode
                        Log.d(TAG, "Relay $method range=${rangeHeader ?: "-"} -> upstream HTTP $code")
                        if (code !in 200..299 && code != 206) {
                            writeSimpleResponse(client, "502 Bad Gateway", "Upstream HTTP $code")
                            return
                        }

                        val upMime = conn.contentType?.substringBefore(';')?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: mimeType
                        val contentLength = conn.contentLengthLong
                        val contentRange = conn.getHeaderField("Content-Range")
                        val acceptRanges = conn.getHeaderField("Accept-Ranges") ?: "bytes"

                        val headerBuilder = StringBuilder().apply {
                            append("HTTP/1.1 $code ${if (code == 206) "Partial Content" else "OK"}\r\n")
                            append("Content-Type: $upMime\r\n")
                            append("Accept-Ranges: $acceptRanges\r\n")
                            if (!contentRange.isNullOrBlank()) {
                                append("Content-Range: $contentRange\r\n")
                            }
                            if (contentLength >= 0L) {
                                append("Content-Length: $contentLength\r\n")
                            }
                            append("Connection: close\r\n\r\n")
                        }

                        val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
                        out.write(headerBuilder.toString().toByteArray(Charsets.ISO_8859_1))
                        out.flush()

                        if (method == "HEAD") return

                        conn.inputStream?.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var timeoutRetries = 0
                            while (running.get()) {
                                try {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    timeoutRetries = 0
                                    out.write(buffer, 0, read)
                                } catch (_: SocketTimeoutException) {
                                    timeoutRetries++
                                    if (timeoutRetries >= 3) break
                                }
                            }
                            out.flush()
                        }
                    } finally {
                        upstream?.disconnect()
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun writeSimpleResponse(socket: Socket, status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private class LocalMediaHttpServer(
        private val context: Context,
        private val bindIp: String,
        private val mediaUri: Uri,
        private val mimeType: String,
        private val contentLength: Long
    ) {
        private val running = AtomicBoolean(false)
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val clientPool = Executors.newCachedThreadPool()

        fun startServer(): Int? {
            return try {
                val addr = InetAddress.getByName(bindIp)
                val sock = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(addr, 0), 16)
                    soTimeout = 1000
                }
                serverSocket = sock
                running.set(true)

                acceptThread = Thread({
                    while (running.get()) {
                        try {
                            val client = sock.accept()
                            clientPool.execute { handleClient(client) }
                        } catch (_: SocketTimeoutException) {
                        } catch (_: Exception) {
                            if (!running.get()) break
                        }
                    }
                }, "PlusCastRelayServer").apply {
                    isDaemon = true
                    start()
                }

                sock.localPort
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local media HTTP server", e)
                stopServer()
                null
            }
        }

        fun stopServer() {
            running.set(false)
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            try {
                acceptThread?.join(500)
            } catch (_: InterruptedException) {
            }
            acceptThread = null
            clientPool.shutdownNow()
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    val method = parts.getOrNull(0)?.uppercase(Locale.US).orEmpty()

                    var rangeHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            rangeHeader = line.substringAfter(':').trim()
                        }
                    }

                    if (method != "GET" && method != "HEAD") {
                        writeSimpleResponse(client, "405 Method Not Allowed", "Method Not Allowed")
                        return
                    }

                    val totalLen = contentLength.takeIf { it > 0L } ?: -1L
                    val hasRange = !rangeHeader.isNullOrBlank() &&
                        rangeHeader.startsWith("bytes=", ignoreCase = true) &&
                        totalLen > 0L
                    val (startByte, endByte, validRange) = parseRange(rangeHeader, totalLen)

                    if (hasRange && (!validRange || startByte >= totalLen || endByte < startByte)) {
                        val headers = buildString {
                            append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                            append("Content-Range: bytes */$totalLen\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        client.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
                        client.getOutputStream().flush()
                        return
                    }

                    val statusLine = if (hasRange) {
                        "HTTP/1.1 206 Partial Content"
                    } else {
                        "HTTP/1.1 200 OK"
                    }

                    val responseLength = when {
                        hasRange -> (endByte - startByte + 1L).coerceAtLeast(0L)
                        totalLen > 0L -> totalLen
                        else -> -1L
                    }

                    val headerBuilder = StringBuilder().apply {
                        append("$statusLine\r\n")
                        append("Content-Type: $mimeType\r\n")
                        if (totalLen > 0L) {
                            append("Accept-Ranges: bytes\r\n")
                        }
                        if (hasRange) {
                            append("Content-Range: bytes $startByte-$endByte/$totalLen\r\n")
                        }
                        if (responseLength >= 0L) {
                            append("Content-Length: $responseLength\r\n")
                        }
                        append("Connection: close\r\n\r\n")
                    }

                    val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
                    out.write(headerBuilder.toString().toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    if (method == "HEAD") return

                    val input: InputStream? = try {
                        context.contentResolver.openInputStream(mediaUri)
                    } catch (_: Exception) {
                        null
                    }

                    input?.use { stream ->
                        val effectiveStart = if (hasRange) startByte.coerceAtLeast(0L) else 0L
                        if (effectiveStart > 0L) {
                            skipFully(stream, effectiveStart)
                        }
                        val buffer = ByteArray(64 * 1024)
                        var remaining = if (responseLength >= 0L) responseLength else Long.MAX_VALUE
                        while (running.get() && remaining > 0L) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = stream.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            if (remaining != Long.MAX_VALUE) {
                                remaining -= read.toLong()
                            }
                        }
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }

        private fun parseRange(rangeHeader: String?, totalLen: Long): Triple<Long, Long, Boolean> {
            if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=", ignoreCase = true) || totalLen <= 0L) {
                return Triple(0L, totalLen - 1L, false)
            }
            val spec = rangeHeader.substringAfter('=').split(',').firstOrNull()?.trim().orEmpty()
            val dashIdx = spec.indexOf('-')
            if (dashIdx < 0) return Triple(0L, totalLen - 1L, false)

            val startStr = spec.substring(0, dashIdx).trim()
            val endStr = spec.substring(dashIdx + 1).trim()

            return try {
                when {
                    startStr.isEmpty() && endStr.isNotEmpty() -> {
                        val suffix = endStr.toLong().coerceAtLeast(1L)
                        val start = (totalLen - suffix).coerceAtLeast(0L)
                        Triple(start, totalLen - 1L, true)
                    }
                    startStr.isNotEmpty() && endStr.isEmpty() -> {
                        val start = startStr.toLong().coerceAtLeast(0L)
                        Triple(start, totalLen - 1L, true)
                    }
                    startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                        val start = startStr.toLong().coerceAtLeast(0L)
                        val end = endStr.toLong().coerceAtMost(totalLen - 1L)
                        Triple(start, end, true)
                    }
                    else -> Triple(0L, totalLen - 1L, false)
                }
            } catch (_: Exception) {
                Triple(0L, totalLen - 1L, false)
            }
        }

        private fun skipFully(stream: InputStream, bytesToSkip: Long) {
            var skippedTotal = 0L
            while (skippedTotal < bytesToSkip) {
                val n = stream.skip(bytesToSkip - skippedTotal)
                if (n > 0L) {
                    skippedTotal += n
                } else {
                    if (stream.read() < 0) break
                    skippedTotal += 1L
                }
            }
        }

        private fun writeSimpleResponse(socket: Socket, status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(headers.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }
}
