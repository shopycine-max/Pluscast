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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"

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

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var progressBar: ProgressBar? = null
    private var topBarView: View? = null
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
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun isLandscape(orientation: Int): Boolean =
        orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleScreenOrientation() {
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
            topBarView?.visibility = View.GONE
            playerView?.useController = false
            unlockPillView?.visibility = View.VISIBLE
        } else {
            unlockPillView?.visibility = View.GONE
            topBarView?.visibility = View.VISIBLE
            playerView?.useController = true
            syncOrientationControls(resources.configuration.orientation)
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

        val pv = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.parseColor(BG_PLAYER))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        playerView = pv
        root.addView(pv)

        val spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            val lp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            layoutParams = lp
        }
        progressBar = spinner
        root.addView(spinner)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#D9101826"))
                setStroke(dp(1), Color.parseColor(BORDER_BAR))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            lp.marginStart = dp(10)
            lp.marginEnd = dp(10)
            lp.topMargin = dp(10)
            layoutParams = lp
        }
        topBarView = topBar

        val btnBack = TextView(this).apply {
            text = "←"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "transparent",
                normalStroke = "transparent",
                focusStroke = ACCENT_CYAN,
                radiusDp = 10
            )
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = mediaTitle
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 13.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(6)
            layoutParams = lp
        }

        val controlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val rotateBtn = TextView(this).apply {
            text = "↻ Landscape"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_BAR,
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
            setOnClickListener { toggleScreenOrientation() }
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
                normalFill = BG_BAR,
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
            setOnClickListener { cycleZoomMode() }
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
                normalFill = BG_BAR,
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
            setOnClickListener { toggleScreenLock() }
        }
        btnLock = lockBtn

        val castBtn = ImageButton(this).apply {
            setImageDrawable(createCastScreenIconDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Cast to DLNA TV"
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_BAR,
                normalStroke = BORDER_BAR,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { triggerCastAction() }
        }
        btnCast = castBtn

        controlsRow.addView(rotateBtn)
        controlsRow.addView(zoomBtn)
        controlsRow.addView(lockBtn)
        controlsRow.addView(castBtn)
        controlsScroll.addView(controlsRow)

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        topBar.addView(controlsScroll)
        root.addView(topBar)

        val unlockPill = TextView(this).apply {
            text = "🔓 Unlock"
            visibility = View.GONE
            setTextColor(Color.parseColor(ACCENT_GOLD))
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = "#D9101826",
                normalStroke = ACCENT_GOLD,
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            lp.topMargin = dp(12)
            lp.marginEnd = dp(12)
            layoutParams = lp
            setOnClickListener { toggleScreenLock() }
        }
        unlockPillView = unlockPill
        root.addView(unlockPill)

        return root
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
        val exo = ExoPlayer.Builder(this).build().apply {
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
                        "Stream play nahi ho paya: ${error.errorCodeName}",
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
