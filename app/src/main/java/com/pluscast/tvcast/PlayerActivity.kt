package com.pluscast.tvcast

import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"

        private const val AUTO_HIDE_DELAY_MS = 1600L

        private const val BG_PLAYER = "#05080F"
        private const val BG_BAR = "#101826"
        private const val BG_BADGE = "#12243B"
        private const val BORDER_BAR = "#22324F"
        private const val ACCENT_CYAN = "#00E5FF"
        private const val ACCENT_GOLD = "#FBBF24"
        private const val ICON_CAST_SOFT = "#7DD3FC"
        private const val TEXT_PRIMARY = "#F8FAFC"
    }

    private val dlnaController by lazy { DlnaController(this) }
    private val autoHideHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var progressBar: ProgressBar? = null
    private var controlsClusterView: View? = null
    private var unlockPillView: TextView? = null
    private var btnRotate: TextView? = null
    private var btnZoom: TextView? = null
    private var btnLock: TextView? = null
    private var btnCast: ImageButton? = null

    private var mediaUrl: String = ""
    private var mediaTitle: String = "PlusCast IPTV"
    private var isScreenLocked: Boolean = false
    private var zoomModeIndex: Int = 0
    private var selectedDlnaDevice: DlnaDevice? = null

    private val zoomModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
    )

    private val hideControlsRunnable = Runnable {
        if (!isScreenLocked) {
            hideControlsCluster()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor(BG_PLAYER)
        window.navigationBarColor = Color.parseColor(BG_PLAYER)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        mediaUrl = intent.getStringExtra("MEDIA_URL")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_URL)?.trim().orEmpty()

        mediaTitle = intent.getStringExtra("MEDIA_TITLE")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_TITLE)?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "PlusCast IPTV"

        val savedName = intent.getStringExtra("DLNA_DEVICE_NAME")?.trim().orEmpty()
        val savedCtrl = intent.getStringExtra("DLNA_CONTROL_URL")?.trim().orEmpty()
        if (savedName.isNotEmpty() && savedCtrl.isNotEmpty()) {
            selectedDlnaDevice = DlnaDevice(
                friendlyName = savedName,
                controlUrl = savedCtrl
            )
        } else {
            selectedDlnaDevice = DlnaDeviceStore.getLastDevice(this)
        }

        val root = buildPlayerUi()
        setContentView(root)
        applySystemBarInsets(root)

        syncOrientationControls(resources.configuration.orientation)
        syncLockState()

        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Stream URL nahi mila", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initPlayer(mediaUrl)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncOrientationControls(newConfig.orientation)
    }

    override fun onStart() {
        super.onStart()
        if (player == null && mediaUrl.isNotEmpty()) {
            initPlayer(mediaUrl)
        } else {
            player?.playWhenReady = true
        }
    }

    override fun onStop() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        releasePlayer()
        super.onDestroy()
    }

    private fun isLandscape(orientation: Int): Boolean =
        orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun blinkControlsOnTouch() {
        if (isScreenLocked) {
            unlockPillView?.let { pill ->
                pill.visibility = View.VISIBLE
                pill.alpha = 0.25f
                pill.animate().alpha(1f).setDuration(130L).start()
            }
            return
        }

        syncOrientationControls(resources.configuration.orientation)
        controlsClusterView?.let { cluster ->
            cluster.animate().cancel()
            cluster.visibility = View.VISIBLE
            cluster.alpha = 0.2f
            cluster.animate()
                .alpha(1f)
                .setDuration(140L)
                .withEndAction { scheduleAutoHide() }
                .start()
        } ?: scheduleAutoHide()
    }

    private fun hideControlsCluster() {
        controlsClusterView?.let { cluster ->
            cluster.animate().cancel()
            cluster.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    if (!isScreenLocked) {
                        cluster.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    private fun scheduleAutoHide() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        if (!isScreenLocked) {
            autoHideHandler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun toggleScreenOrientation() {
        scheduleAutoHide()
        if (isScreenLocked) {
            Toast.makeText(this, "Screen lock hai, pehle Unlock karein", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIsLand = isLandscape(resources.configuration.orientation)
        requestedOrientation = if (currentIsLand) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun syncOrientationControls(orientation: Int) {
        val land = isLandscape(orientation)
        btnRotate?.text = if (land) "↻ Portrait" else "↻ Landscape"

        val zoomButton = btnZoom ?: return
        if (land) {
            zoomButton.visibility = View.VISIBLE
            zoomButton.isEnabled = true
            applyResizeMode()
        } else {
            zoomModeIndex = 0
            zoomButton.visibility = View.GONE
            zoomButton.isEnabled = false
            playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun cycleZoomMode() {
        scheduleAutoHide()
        if (!isLandscape(resources.configuration.orientation)) {
            syncOrientationControls(resources.configuration.orientation)
            return
        }
        zoomModeIndex = (zoomModeIndex + 1) % zoomModes.size
        applyResizeMode()
        val label = zoomModes[zoomModeIndex].second
        Toast.makeText(this, "Zoom: $label", Toast.LENGTH_SHORT).show()
    }

    private fun applyResizeMode() {
        if (!isLandscape(resources.configuration.orientation)) {
            playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            btnZoom?.text = "Zoom: Fit"
            return
        }
        val (mode, label) = zoomModes[zoomModeIndex]
        playerView?.resizeMode = mode
        btnZoom?.text = "Zoom: $label"
    }

    private fun toggleScreenLock() {
        isScreenLocked = !isScreenLocked
        syncLockState()
        Toast.makeText(
            this,
            if (isScreenLocked) "Controls lock ho gaye" else "Controls unlock ho gaye",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun syncLockState() {
        if (isScreenLocked) {
            autoHideHandler.removeCallbacks(hideControlsRunnable)
            controlsClusterView?.animate()?.cancel()
            controlsClusterView?.visibility = View.GONE
            unlockPillView?.visibility = View.VISIBLE
            unlockPillView?.alpha = 1f
        } else {
            unlockPillView?.visibility = View.GONE
            syncOrientationControls(resources.configuration.orientation)
            controlsClusterView?.animate()?.cancel()
            controlsClusterView?.visibility = View.VISIBLE
            controlsClusterView?.alpha = 1f
            scheduleAutoHide()
        }
        btnLock?.text = if (isScreenLocked) "🔓 Unlock" else "🔒 Lock"
    }

    private fun buildPlayerUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(BG_PLAYER))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // useController = false so no full-screen controller bar line overlays the video
        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.parseColor(BG_PLAYER))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        playerView = pv
        root.addView(pv)

        val touchInterceptor = object : View(this) {
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        val dt = System.currentTimeMillis() - downTime
                        if (dx < dp(24) && dy < dp(24) && dt < 500L) {
                            blinkControlsOnTouch()
                        }
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(touchInterceptor)

        val spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            val lp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            layoutParams = lp
        }
        progressBar = spinner
        root.addView(spinner)

        // Compact floating controls only (no full-width line across screen)
        val controlsCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            lp.topMargin = dp(10)
            lp.marginEnd = dp(10)
            layoutParams = lp
        }
        controlsClusterView = controlsCluster

        val rotateBtn = TextView(this).apply {
            text = "↻ Landscape"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#CC101826",
                normalStroke = BORDER_BAR,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(9), dp(5), dp(9), dp(5))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            layoutParams = lp
            setOnClickListener {
                blinkView(it)
                toggleScreenOrientation()
            }
        }
        btnRotate = rotateBtn

        val zoomBtn = TextView(this).apply {
            text = "Zoom: Fit"
            setTextColor(Color.parseColor(ACCENT_GOLD))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#CC101826",
                normalStroke = BORDER_BAR,
                focusStroke = ACCENT_GOLD,
                radiusDp = 999
            )
            setPadding(dp(9), dp(5), dp(9), dp(5))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            layoutParams = lp
            setOnClickListener {
                blinkView(it)
                cycleZoomMode()
            }
        }
        btnZoom = zoomBtn

        val lockBtn = TextView(this).apply {
            text = "🔒 Lock"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#CC101826",
                normalStroke = BORDER_BAR,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(9), dp(5), dp(9), dp(5))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            layoutParams = lp
            setOnClickListener {
                blinkView(it)
                toggleScreenLock()
            }
        }
        btnLock = lockBtn

        val castBtn = ImageButton(this).apply {
            setImageDrawable(createCastScreenIconDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Cast to DLNA TV"
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#CC101826",
                normalStroke = BORDER_BAR,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                blinkView(it)
                triggerCastAction()
            }
        }
        btnCast = castBtn

        controlsCluster.addView(rotateBtn)
        controlsCluster.addView(zoomBtn)
        controlsCluster.addView(lockBtn)
        controlsCluster.addView(castBtn)
        root.addView(controlsCluster)

        val unlockPill = TextView(this).apply {
            text = "🔓 Unlock"
            visibility = View.GONE
            setTextColor(Color.parseColor(ACCENT_GOLD))
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#CC101826",
                normalStroke = ACCENT_GOLD,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            lp.topMargin = dp(10)
            lp.marginEnd = dp(10)
            layoutParams = lp
            setOnClickListener {
                blinkView(it)
                toggleScreenLock()
            }
        }
        unlockPillView = unlockPill
        root.addView(unlockPill)

        return root
    }

    private fun blinkView(v: View) {
        v.alpha = 0.25f
        v.animate().alpha(1f).setDuration(130L).start()
    }

    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun initPlayer(url: String) {
        releasePlayer()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("PlusCast/1.0 (Android; IPTV)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                val item = MediaItem.fromUri(Uri.parse(url))
                setMediaItem(item)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        progressBar?.visibility =
                            if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        progressBar?.visibility = View.GONE
                        Toast.makeText(
                            this@PlayerActivity,
                            "Stream unavailable (${error.errorCodeName})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }

        player = exo
        playerView?.player = exo
    }

    private fun releasePlayer() {
        playerView?.player = null
        player?.release()
        player = null
    }

    private fun triggerCastAction() {
        scheduleAutoHide()
        val currentDevice = selectedDlnaDevice
        if (currentDevice != null) {
            castStreamToDevice(currentDevice)
            return
        }

        Toast.makeText(this, "DLNA TV khoj raha hai...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val devices = dlnaController.discoverRenderers()
            if (isFinishing || isDestroyed) return@launch

            if (devices.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Koi DLNA TV nahi mila",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val names = devices.map { it.friendlyName }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Cast TV Chunein")
                .setItems(names) { _, which ->
                    val chosen = devices[which]
                    selectedDlnaDevice = chosen
                    DlnaDeviceStore.saveLastDevice(this@PlayerActivity, chosen)
                    castStreamToDevice(chosen)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun castStreamToDevice(device: DlnaDevice) {
        Toast.makeText(
            this,
            "${device.friendlyName} par cast bhej raha hai...",
            Toast.LENGTH_SHORT
        ).show()
        lifecycleScope.launch {
            val res = dlnaController.playMediaOnDevice(
                device = device,
                mediaUrl = mediaUrl,
                title = mediaTitle
            )
            if (isFinishing || isDestroyed) return@launch

            Toast.makeText(
                this@PlayerActivity,
                if (res.success) "${device.friendlyName} par chal raha hai" else res.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createCastScreenIconDrawable(): Drawable {
        val size = dp(24).coerceAtLeast(18)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val f = size.toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ICON_CAST_SOFT)
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.075f).coerceAtLeast(1.6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val screenRect = RectF(f * 0.15f, f * 0.25f, f * 0.85f, f * 0.71f)
        canvas.drawRoundRect(screenRect, f * 0.08f, f * 0.08f, paint)

        canvas.drawLine(f * 0.37f, f * 0.81f, f * 0.63f, f * 0.81f, paint)

        val arc1 = RectF(f * 0.15f, f * 0.45f, f * 0.43f, f * 0.73f)
        val arc2 = RectF(f * 0.15f, f * 0.55f, f * 0.33f, f * 0.73f)
        canvas.drawArc(arc1, -90f, 90f, false, paint)
        canvas.drawArc(arc2, -90f, 90f, false, paint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ICON_CAST_SOFT)
            style = Paint.Style.FILL
        }
        val dotPath = Path().apply {
            addCircle(f * 0.22f, f * 0.65f, (f * 0.05f).coerceAtLeast(1.3f), Path.Direction.CW)
        }
        canvas.drawPath(dotPath, dotPaint)

        return BitmapDrawable(resources, bmp)
    }

    private fun createFocusPillDrawable(
        normalFill: String,
        normalStroke: String,
        focusStroke: String,
        radiusDp: Int
    ): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            if (normalFill != "transparent") {
                setColor(Color.parseColor(normalFill))
            }
            if (normalStroke != "transparent") {
                setStroke(dp(1), Color.parseColor(normalStroke))
            }
        }
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(BG_BADGE))
            setStroke(dp(2), Color.parseColor(focusStroke))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
