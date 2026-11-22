package com.pluscast.tvcast

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var progressBar: ProgressBar? = null
    private var mediaUrl: String = ""
    private var mediaTitle: String = "PlusCast Media"
    private var mimeType: String = "video/mp4"
    private var isCastingToTv: Boolean = false
    private var isScreenLocked: Boolean = false
    private var resizeModeIndex: Int = 0 // 0 = FIT (default), 1 = FILL
    private var retryCount: Int = 0
    private var retryJob: Job? = null
    private val overlayButtons = mutableListOf<ImageButton>()
    private var unlockButton: ImageButton? = null
    private val dlnaController by lazy { DlnaController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        val pbId = resources.getIdentifier("progressBar", "id", packageName)
        if (pbId != 0) {
            progressBar = findViewById(pbId)
        }

        mediaUrl = intent.getStringExtra("MEDIA_URL")?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_URL)?.trim().orEmpty()

        mediaTitle = intent.getStringExtra("MEDIA_TITLE")?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_TITLE)?.trim()
                .takeUnless { it.isNullOrEmpty() }
            ?: "PlusCast Media"

        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: detectMimeType(mediaUrl)

        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Media URL nahi mila", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        applyImmersiveSystemBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        addRotateButton()
        addZoomToggleButton()
        addDlnaButton()
        addLockButton()
        addUnlockButton()
        setupControllerVisibility()
        setupXmlCastControls()
        initializePlayer()
        tryCastMediaIfConnected()

        val preselectedControlUrl = intent.getStringExtra("DLNA_CONTROL_URL")?.trim().orEmpty()
        if (preselectedControlUrl.isNotEmpty()) {
            val preselectedName = intent.getStringExtra("DLNA_DEVICE_NAME")?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: "DLNA Renderer"
            val device = DlnaDevice(
                friendlyName = preselectedName,
                controlUrl = preselectedControlUrl
            )
            DlnaDeviceStore.saveLastDevice(this, device)
            startDlnaPlayback(device)
        }
    }

    private fun applyImmersiveSystemBars(isLandscape: Boolean) {
        try {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape || isScreenLocked) {
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

    private fun setupControllerVisibility() {
        playerView?.apply {
            controllerShowTimeoutMs = 3500
            controllerHideOnTouch = true
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    if (isScreenLocked) {
                        overlayButtons.forEach { it.visibility = View.GONE }
                        unlockButton?.visibility = View.VISIBLE
                        return@ControllerVisibilityListener
                    }
                    overlayButtons.forEach { btn ->
                        btn.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
                    }
                }
            )
        }
    }

    private fun setupXmlCastControls() {
        val castId = resources.getIdentifier("btnCastDlna", "id", packageName)
        val rescanId = resources.getIdentifier("btnRescanDlna", "id", packageName)

        if (castId != 0) {
            findViewById<View>(castId)?.setOnClickListener {
                val saved = DlnaDeviceStore.getLastDevice(this)
                if (saved != null) {
                    startDlnaPlayback(saved)
                } else {
                    discoverAndShowDlnaDevices()
                }
            }
        }

        if (rescanId != 0) {
            findViewById<View>(rescanId)?.setOnClickListener {
                discoverAndShowDlnaDevices()
            }
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
            overlayButtons.add(rotateButton)
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
                contentDescription = "Toggle Fill"
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
            overlayButtons.add(zoomButton)
            rootView.addView(zoomButton)
            zoomButton.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addDlnaButton() {
        try {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val sizePx = (48 * resources.displayMetrics.density).toInt()
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            val gapPx = (10 * resources.displayMetrics.density).toInt()

            val dlnaButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.stat_sys_data_bluetooth)
                setColorFilter(Color.parseColor("#00E5FF"))
                setBackgroundColor(Color.parseColor("#99000000"))
                contentDescription = "Cast to DLNA"
                elevation = 12f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = marginPx + (sizePx + gapPx) * 2
                    rightMargin = marginPx
                }
                setOnClickListener {
                    val saved = DlnaDeviceStore.getLastDevice(this@PlayerActivity)
                    if (saved != null) {
                        startDlnaPlayback(saved)
                    } else {
                        discoverAndShowDlnaDevices()
                    }
                }
                setOnLongClickListener {
                    discoverAndShowDlnaDevices()
                    true
                }
            }
            overlayButtons.add(dlnaButton)
            rootView.addView(dlnaButton)
            dlnaButton.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addLockButton() {
        try {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val sizePx = (48 * resources.displayMetrics.density).toInt()
            val marginPx = (16 * resources.displayMetrics.density).toInt()
            val gapPx = (10 * resources.displayMetrics.density).toInt()

            val lockButton = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_lock_lock)
                setColorFilter(Color.parseColor("#FACC15"))
                setBackgroundColor(Color.parseColor("#99000000"))
                contentDescription = "Lock Screen"
                elevation = 12f
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = marginPx + (sizePx + gapPx) * 3
                    rightMargin = marginPx
                }
                setOnClickListener {
                    setScreenLocked(true)
                }
            }
            overlayButtons.add(lockButton)
            rootView.addView(lockButton)
            lockButton.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addUnlockButton() {
        try {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val sizePx = (52 * resources.displayMetrics.density).toInt()
            val marginPx = (20 * resources.displayMetrics.density).toInt()

            val btn = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_lock_idle_lock)
                setColorFilter(Color.parseColor("#FACC15"))
                setBackgroundColor(Color.parseColor("#CC0F172A"))
                contentDescription = "Unlock Screen"
                elevation = 16f
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                    gravity = Gravity.TOP or Gravity.START
                    topMargin = marginPx
                    leftMargin = marginPx
                }
                setOnClickListener {
                    setScreenLocked(false)
                }
            }
            unlockButton = btn
            rootView.addView(btn)
            btn.bringToFront()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setScreenLocked(locked: Boolean) {
        isScreenLocked = locked
        applyImmersiveSystemBars(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

        if (locked) {
            playerView?.useController = false
            playerView?.hideController()
            overlayButtons.forEach { it.visibility = View.GONE }
            unlockButton?.visibility = View.VISIBLE
            unlockButton?.bringToFront()
            Toast.makeText(this, "Screen Lock ON 🔐", Toast.LENGTH_SHORT).show()
        } else {
            unlockButton?.visibility = View.GONE
            playerView?.useController = true
            playerView?.showController()
            overlayButtons.forEach { it.visibility = View.VISIBLE }
            Toast.makeText(this, "Screen Lock OFF 🔓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun discoverAndShowDlnaDevices() {
        Toast.makeText(this, "DLNA devices khoj raha hai...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val devices = dlnaController.discoverRenderers()
            if (isFinishing || isDestroyed) return@launch

            if (devices.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Koi DLNA device nahi mila. Same Wi-Fi aur DLNA mode check karein.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val names = devices.map { it.friendlyName }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("DLNA Device Chunein")
                .setItems(names) { _, which ->
                    val selectedDevice = devices[which]
                    DlnaDeviceStore.saveLastDevice(this@PlayerActivity, selectedDevice)
                    startDlnaPlayback(selectedDevice)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startDlnaPlayback(device: DlnaDevice) {
        val resolvedMime = mimeType.ifBlank { detectMimeType(mediaUrl) }
        Toast.makeText(this, "${device.friendlyName} par bhej raha hai...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = dlnaController.playMediaOnDevice(
                device = device,
                mediaUrl = mediaUrl,
                title = mediaTitle,
                mimeType = resolvedMime
            )
            if (isFinishing || isDestroyed) return@launch

            if (result.success) {
                isCastingToTv = true
                player?.pause()
                Toast.makeText(
                    this@PlayerActivity,
                    "${device.friendlyName} par play ho raha hai",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@PlayerActivity,
                    "DLNA fail: ${result.message} — dobara scan kar rahe hain",
                    Toast.LENGTH_LONG
                ).show()
                DlnaDeviceStore.clearLastDevice(this@PlayerActivity)
                discoverAndShowDlnaDevices()
            }
        }
    }

    private fun detectMimeType(url: String): String {
        return when {
            url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            url.endsWith(".ts", ignoreCase = true) || url.contains(".ts?", ignoreCase = true) -> "video/mp2t"
            url.contains(".mp4", ignoreCase = true) -> "video/mp4"
            url.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            url.contains(".webm", ignoreCase = true) -> "video/webm"
            url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    private fun toggleResizeMode() {
        val pv = playerView ?: return
        if (resizeModeIndex == 0) {
            resizeModeIndex = 1
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            Toast.makeText(this, "Fill Mode (Kam crop)", Toast.LENGTH_SHORT).show()
        } else {
            resizeModeIndex = 0
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            Toast.makeText(this, "Fit Mode (Bina crop)", Toast.LENGTH_SHORT).show()
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
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        playerView?.requestLayout()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isScreenLocked) {
            Toast.makeText(this, "Screen lock hai, pehle unlock karein", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && mediaUrl.isNotEmpty()) {
            initializePlayer()
        } else if (!isCastingToTv) {
            player?.playWhenReady = true
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        try {
            releasePlayer()

            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("PlusCast/1.0 (Android)")
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(25_000)
                .setAllowCrossProtocolRedirects(true)

            val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            val loadControl: LoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 20_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val renderersFactory = DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .build()

            player = exoPlayer
            playerView?.player = exoPlayer
            playerView?.resizeMode = if (resizeModeIndex == 1) {
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            progressBar?.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            retryCount = 0
                            progressBar?.visibility = View.GONE
                        }
                        Player.STATE_ENDED, Player.STATE_IDLE -> {
                            progressBar?.visibility = View.GONE
                        }
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val hasAudioTrack = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
                    if (!hasAudioTrack) {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Is channel mein audio track nahi mila",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    progressBar?.visibility = View.GONE
                    if (retryCount < 3) {
                        retryCount++
                        val waitMs = retryCount * 1500L
                        Toast.makeText(
                            this@PlayerActivity,
                            "Stream reconnect kar raha hai ($retryCount/3)...",
                            Toast.LENGTH_SHORT
                        ).show()
                        retryJob?.cancel()
                        retryJob = lifecycleScope.launch {
                            delay(waitMs)
                            if (!isFinishing && !isDestroyed && !isCastingToTv) {
                                player?.prepare()
                                player?.playWhenReady = true
                            }
                        }
                    } else {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Error (${error.errorCodeName}): ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })

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
            val castMimeType = when {
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
                .setContentType(castMimeType)
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
        retryJob?.cancel()
        retryJob = null
        progressBar?.visibility = View.GONE
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
        isCastingToTv = false
        isScreenLocked = false
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
