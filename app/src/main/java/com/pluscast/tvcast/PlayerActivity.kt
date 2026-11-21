package com.pluscast.tvcast

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
    private var isCastingToTv: Boolean = false
    private var resizeModeIndex: Int = 1 // 1 = ZOOM, 0 = FIT

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

        applyImmersiveSystemBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        addRotateButton()
        addZoomToggleButton()
        tryCastMediaIfConnected()
    }

    private fun applyImmersiveSystemBars(isLandscape: Boolean) {
        try {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addRotateButton() {
        try {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val rotateButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.parseColor("#99000000"))
                contentDescription = "Rotate Screen"
                elevation = 12f
                val sizePx = (48 * resources.displayMetrics.density).toInt()
                val marginPx = (16 * resources.displayMetrics.density).toInt()
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = marginPx
                    rightMargin = marginPx
                }
                setOnClickListener {
                    toggleScreenOrientation()
                }
            }
            rootView.addView(rotateButton)
            rotateButton.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addZoomToggleButton() {
        try {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val sizePx = (48 * resources.displayMetrics.density).toInt()
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            val gapPx = (10 * resources.displayMetrics.density).toInt()

            val zoomButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_crop)
                setColorFilter(Color.WHITE)
                setBackgroundColor(Color.parseColor("#99000000"))
                contentDescription = "Toggle Zoom"
                elevation = 12f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = marginPx + sizePx + gapPx
                    rightMargin = marginPx
                }
                setOnClickListener {
                    toggleResizeMode()
                }
            }
            rootView.addView(zoomButton)
            zoomButton.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleResizeMode() {
        val pv = playerView ?: return
        if (resizeModeIndex == 1) {
            resizeModeIndex = 0
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            Toast.makeText(this, "Fit Mode (Bina crop)", Toast.LENGTH_SHORT).show()
        } else {
            resizeModeIndex = 1
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            Toast.makeText(this, "Zoom Mode (Full screen)", Toast.LENGTH_SHORT).show()
        }
        pv.requestLayout()
    }

    private fun toggleScreenOrientation() {
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveSystemBars(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        playerView?.resizeMode = if (resizeModeIndex == 1) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView?.requestLayout()
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
            playerView?.resizeMode = if (resizeModeIndex == 1) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

            val mediaItem = MediaItem.fromUri(Uri.parse(mediaUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = !isCastingToTv
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
            isCastingToTv = true
            player?.pause()
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
