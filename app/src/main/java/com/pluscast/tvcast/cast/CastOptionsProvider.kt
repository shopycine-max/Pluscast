package com.pluscast.tvcast.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import java.util.Locale

class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }

    companion object {
        private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

        fun extractYouTubeVideoId(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (YT_ID_REGEX.matches(trimmed)) return trimmed

            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.US).orEmpty()

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

        fun isWatchPageUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.US)
            return lower.contains("youtube.com/watch") ||
                lower.contains("youtu.be/") ||
                lower.contains("youtube.com/shorts/") ||
                lower.contains("youtube.com/live/")
        }

        fun buildYouTubeMediaLoadRequest(
            playableStreamUrlOrId: String,
            title: String = "YouTube Video",
            mimeType: String = "video/mp4"
        ): MediaLoadRequestData? {
            val trimmed = playableStreamUrlOrId.trim()
            if (trimmed.isEmpty() || isWatchPageUrl(trimmed) || YT_ID_REGEX.matches(trimmed)) {
                return null
            }
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }

            val resolvedTitle = title.trim().ifEmpty { "YouTube Video" }
            val resolvedMime = mimeType.trim().ifEmpty { "video/mp4" }

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, resolvedTitle)
            }

            val mediaInfo = MediaInfo.Builder(trimmed)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(resolvedMime)
                .setMetadata(metadata)
                .build()

            return MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
        }

        fun buildLocalStreamMediaLoadRequest(
            relayHttpUrl: String,
            title: String = "Local Media",
            mimeType: String = "video/mp4"
        ): MediaLoadRequestData? {
            val cleanUrl = relayHttpUrl.trim()
            if (cleanUrl.isEmpty() ||
                !(cleanUrl.startsWith("http://", ignoreCase = true) ||
                    cleanUrl.startsWith("https://", ignoreCase = true))
            ) {
                return null
            }

            val resolvedMime = mimeType.trim().ifEmpty { "video/mp4" }
            val isAudio = resolvedMime.startsWith("audio/", ignoreCase = true)
            val mediaType = if (isAudio) {
                MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            } else {
                MediaMetadata.MEDIA_TYPE_MOVIE
            }

            val metadata = MediaMetadata(mediaType).apply {
                putString(MediaMetadata.KEY_TITLE, title.trim().ifEmpty { "Local Media" })
            }

            val mediaInfo = MediaInfo.Builder(cleanUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(resolvedMime)
                .setMetadata(metadata)
                .build()

            return MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
        }

        fun loadMediaOnActiveCastSession(
            context: Context,
            requestData: MediaLoadRequestData,
            onResult: ((success: Boolean, message: String) -> Unit)? = null
        ) {
            val castSession = runCatching {
                CastContext.getSharedInstance(context.applicationContext)
                    .sessionManager
                    .currentCastSession
            }.getOrNull()

            if (castSession == null || !castSession.isConnected) {
                onResult?.invoke(false, "Koi active Google Cast session connected nahi hai")
                return
            }

            val remoteMediaClient = castSession.remoteMediaClient
            if (remoteMediaClient == null) {
                onResult?.invoke(false, "Cast media client available nahi hai")
                return
            }

            remoteMediaClient.load(requestData)
                .setResultCallback { result ->
                    val ok = result.status.isSuccess
                    val msg = result.status.statusMessage
                        ?: if (ok) "Cast media load ho gaya" else "Cast load fail (${result.status.statusCode})"
                    onResult?.invoke(ok, msg)
                }
        }
    }
}
