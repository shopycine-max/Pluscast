package com.pluscast.tvcast.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import java.util.Locale

class CastOptionsProvider : OptionsProvider {

    companion object {
        // Google Cast Default Media Receiver ID (IPTV / HTTP / Local relay streams ke liye)
        const val DEFAULT_MEDIA_RECEIVER_ID = "CC1AD845"

        // Google Cast YouTube Receiver Application ID
        const val YOUTUBE_RECEIVER_APP_ID = "233637DE"

        private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

        fun extractYouTubeVideoId(raw: String): String? {
            val trimmed = raw.trim()
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

        fun buildYouTubeMediaLoadRequest(
            youtubeUrlOrId: String,
            title: String = "YouTube Video"
        ): MediaLoadRequestData? {
            val videoId = extractYouTubeVideoId(youtubeUrlOrId) ?: return null
            val watchUrl = if (
                youtubeUrlOrId.trim().startsWith("http://", true) ||
                youtubeUrlOrId.trim().startsWith("https://", true)
            ) {
                youtubeUrlOrId.trim()
            } else {
                "https://www.youtube.com/watch?v=$videoId"
            }

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title.trim().ifEmpty { "YouTube ($videoId)" })
                putString(MediaMetadata.KEY_SUBTITLE, "YouTube • $videoId")
            }

            val mediaInfo = MediaInfo.Builder(watchUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("video/mp4")
                .setMetadata(metadata)
                .build()

            return MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
        }

        /**
         * Local media ke liye relay HTTP URL (jaise http://<phone-ip>:<port>/media)
         * ko Google Cast MediaLoadRequestData mein wrap karta hai.
         */
        fun buildLocalStreamMediaLoadRequest(
            relayHttpUrl: String,
            title: String = "Local Media",
            mimeType: String = "video/mp4"
        ): MediaLoadRequestData? {
            val cleanUrl = relayHttpUrl.trim()
            if (!(cleanUrl.startsWith("http://", true) || cleanUrl.startsWith("https://", true))) {
                return null
            }

            val isAudio = mimeType.startsWith("audio/", ignoreCase = true)
            val metadata = MediaMetadata(
                if (isAudio) MediaMetadata.MEDIA_TYPE_MUSIC_TRACK else MediaMetadata.MEDIA_TYPE_MOVIE
            ).apply {
                putString(MediaMetadata.KEY_TITLE, title.trim().ifEmpty { "Local Media" })
                putString(MediaMetadata.KEY_SUBTITLE, if (isAudio) "Local Audio" else "Local Video")
            }

            val mediaInfo = MediaInfo.Builder(cleanUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(mimeType.ifBlank { "video/mp4" })
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
            onResult: ((Boolean, String) -> Unit)? = null
        ) {
            val session: CastSession? = runCatching {
                CastContext.getSharedInstance(context.applicationContext)
                    .sessionManager
                    .currentCastSession
            }.getOrNull()

            val remoteMediaClient = session?.remoteMediaClient
            if (session == null || !session.isConnected || remoteMediaClient == null) {
                onResult?.invoke(false, "Koi active Google Cast session connected nahi hai")
                return
            }

            remoteMediaClient.load(requestData)
                .setResultCallback { result ->
                    val ok = result.status.isSuccess
                    val msg = if (ok) {
                        "Cast device par load ho gaya"
                    } else {
                        result.status.statusMessage ?: "Cast load fail ho gaya"
                    }
                    onResult?.invoke(ok, msg)
                }
        }
    }

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(DEFAULT_MEDIA_RECEIVER_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
