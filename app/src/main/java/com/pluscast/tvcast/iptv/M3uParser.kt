package com.pluscast.tvcast.iptv

data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null
)

object M3uParser {

    const val DEFAULT_INDIA_M3U_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"

    fun parse(m3uContent: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null

        m3uContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                currentLogo = extractAttribute(line, "tvg-logo")
                currentGroup = extractAttribute(line, "group-title")

                val commaIndex = line.lastIndexOf(',')
                currentName = if (commaIndex != -1 && commaIndex + 1 < line.length) {
                    line.substring(commaIndex + 1).trim().ifEmpty { "Unknown Channel" }
                } else {
                    "Unknown Channel"
                }
            } else if (!line.startsWith("#")) {
                val streamUrl = line
                if (streamUrl.startsWith("http://", ignoreCase = true) ||
                    streamUrl.startsWith("https://", ignoreCase = true)
                ) {
                    val channelName = currentName ?: "Channel ${channels.size + 1}"
                    channels.add(
                        IptvChannel(
                            name = channelName,
                            url = streamUrl,
                            logo = currentLogo,
                            group = currentGroup
                        )
                    )
                }
                currentName = null
                currentLogo = null
                currentGroup = null
            }
        }

        return channels
    }

    private fun extractAttribute(extInfLine: String, attrName: String): String? {
        val pattern = """$attrName="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
        val match = pattern.find(extInfLine) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
