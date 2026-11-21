package com.pluscast.tvcast

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var mediaUrl: String = ""
    private var mediaTitle: String = "PlusCast Media"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        mediaUrl = intent.getStringExtra("MEDIA_URL").orEmpty()
        mediaTitle = intent.getStringExtra("MEDIA_TITLE") ?: "PlusCast Media"

        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Media URL nahi mila", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tryCastMediaIfConnected()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && mediaUrl.isNotEmpty()) {
            initializePlayer()
        }
    }

    private fun initializePlayer() {
        try {
            val exoPlayer = ExoPlayer.Builder(this).build()
            player = exoPlayer
            playerView?.player = exoPlayer

            val mediaItem = MediaItem.fromUri(Uri.parse(mediaUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Player error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryCastMediaIfConnected() {
        try {
            val castSession = CastContext.getSharedInstance(this).sessionManager.currentCastSession
            val remoteMediaClient = castSession?.remoteMediaClient ?: return

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, mediaTitle)
            }

            val isHls = mediaUrl.contains(".m3u8", ignoreCase = true)
            val mimeType = when {
                isHls -> "application/x-mpegURL"
                mediaUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                mediaUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
                mediaUrl.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
                else -> "video/*"
            }

            val streamType = if (isHls) {
                MediaInfo.STREAM_TYPE_LIVE
            } else {
                MediaInfo.STREAM_TYPE_BUFFERED
            }

            val mediaInfo = MediaInfo.Builder(mediaUrl)
                .setStreamType(streamType)
                .setContentType(mimeType)
                .setMetadata(metadata)
                .build()

            val loadRequest = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()

            remoteMediaClient.load(loadRequest)
            Toast.makeText(this, "TV par cast ho raha hai...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releasePlayer() {
        try {
            playerView?.player = null
            player?.release()
            player = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
