package com.pluscast.tvcast.iptv

import java.net.URLDecoder

data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null
)

object M3uParser {

    const val DEFAULT_INDIA_M3U_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"

    fun parse(m3uContent: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentUserAgent: String? = null
        var currentReferrer: String? = null

        m3uContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    currentLogo = extractAttribute(line, "tvg-logo")
                    currentGroup = extractAttribute(line, "group-title")
                    extractAttribute(line, "http-user-agent")?.let { currentUserAgent = it }
                    extractAttribute(line, "http-referrer")?.let { currentReferrer = it }
                    extractAttribute(line, "http-referer")?.let { currentReferrer = it }

                    val commaIndex = line.lastIndexOf(',')
                    currentName = if (commaIndex != -1 && commaIndex + 1 < line.length) {
                        line.substring(commaIndex + 1).trim().ifEmpty { "Unknown Channel" }
                    } else {
                        "Unknown Channel"
                    }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':').trim()
                    when {
                        opt.startsWith("http-user-agent=", ignoreCase = true) -> {
                            currentUserAgent = opt.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                        }
                        opt.startsWith("http-referrer=", ignoreCase = true) -> {
                            currentReferrer = opt.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                        }
                        opt.startsWith("http-referer=", ignoreCase = true) -> {
                            currentReferrer = opt.substringAfter('=').trim().takeIf { it.isNotEmpty() }
                        }
                    }
                }

                !line.startsWith("#") -> {
                    val parsed = parseUrlAndPipeHeaders(line)
                    val cleanUrl = parsed.first
                    val pipeUa = parsed.second
                    val pipeRef = parsed.third

                    if (cleanUrl.startsWith("http://", ignoreCase = true) ||
                        cleanUrl.startsWith("https://", ignoreCase = true)
                    ) {
                        val channelName = currentName ?: "Channel ${channels.size + 1}"
                        channels.add(
                            IptvChannel(
                                name = channelName,
                                url = cleanUrl,
                                logo = currentLogo,
                                group = currentGroup,
                                userAgent = pipeUa ?: currentUserAgent,
                                referrer = pipeRef ?: currentReferrer
                            )
                        )
                    }
                    currentName = null
                    currentLogo = null
                    currentGroup = null
                    currentUserAgent = null
                    currentReferrer = null
                }
            }
        }

        return channels
    }

    private fun parseUrlAndPipeHeaders(rawLine: String): Triple<String, String?, String?> {
        val pipeIdx = rawLine.indexOf('|')
        if (pipeIdx == -1) {
            return Triple(rawLine.trim(), null, null)
        }
        val cleanUrl = rawLine.substring(0, pipeIdx).trim()
        val headerPart = rawLine.substring(pipeIdx + 1).trim()
        var ua: String? = null
        var ref: String? = null

        for (pair in headerPart.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = pair.substring(0, eqIdx).trim().lowercase()
            val rawVal = pair.substring(eqIdx + 1).trim()
            val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            if (decodedVal.isEmpty()) continue

            when (key) {
                "user-agent", "http-user-agent" -> ua = decodedVal
                "referer", "referrer", "http-referer", "http-referrer" -> ref = decodedVal
            }
        }
        return Triple(cleanUrl, ua, ref)
    }

    private fun extractAttribute(extInfLine: String, attrName: String): String? {
        val pattern = """$attrName="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = pattern.find(extInfLine) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
