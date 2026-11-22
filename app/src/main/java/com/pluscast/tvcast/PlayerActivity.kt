package com.pluscast.tvcast

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
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
        private const val BORDER_BAR = "#22324F"
        private const val ACCENT_CYAN = "#00E5FF"
        private const val ACCENT_GOLD = "#FBBF24"
        private const val TEXT_PRIMARY = "#F8FAFC"
    }

    private val dlnaController by lazy { DlnaController(this) }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var progressBar: ProgressBar? = null
    private var btnZoom: TextView? = null
    private var btnCast: ImageButton? = null

    private var mediaUrl: String = ""
    private var mediaTitle: String = "PlusCast IPTV"
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

        syncZoomVisibilityForOrientation(resources.configuration.orientation)

        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Stream URL nahi mila", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initPlayer(mediaUrl)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncZoomVisibilityForOrientation(newConfig.orientation)
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

    private fun syncZoomVisibilityForOrientation(orientation: Int) {
        val zoomButton = btnZoom ?: return
        if (isLandscape(orientation)) {
            zoomButton.visibility = View.VISIBLE
            zoomButton.isEnabled = true
            applyResizeMode()
        } else {
            // Portrait mein zoom option bilkul band aur normal fit mode par reset
            zoomModeIndex = 0
            zoomButton.visibility = View.GONE
            zoomButton.isEnabled = false
            playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun cycleZoomMode() {
        if (!isLandscape(resources.configuration.orientation)) {
            syncZoomVisibilityForOrientation(resources.configuration.orientation)
            return
        }
        zoomModeIndex = (zoomModeIndex + 1) % zoomModes.size
        applyResizeMode()
        val label = zoomModes[zoomModeIndex].second
        Toast.makeText(this, "Landscape Zoom: $label", Toast.LENGTH_SHORT).show()
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
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
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
                setColor(Color.parseColor("#CC101826"))
                setStroke(dp(1), Color.parseColor(BORDER_BAR))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            lp.marginStart = dp(12)
            lp.marginEnd = dp(12)
            lp.topMargin = dp(12)
            layoutParams = lp
        }

        val btnBack = TextView(this).apply {
            text = "←"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(10), dp(4))
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = mediaTitle
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 14.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val zoomBtn = TextView(this).apply {
            text = "Zoom: Fit"
            setTextColor(Color.parseColor(ACCENT_GOLD))
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor(BG_BAR))
                setStroke(dp(1), Color.parseColor(BORDER_BAR))
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener { cycleZoomMode() }
        }
        btnZoom = zoomBtn

        val castBtn = ImageButton(this).apply {
            setImageDrawable(createCastScreenIconDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Cast to DLNA TV"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#12243B"))
                setStroke(dp(1), Color.parseColor(ACCENT_CYAN))
            }
            setPadding(dp(7), dp(7), dp(7), dp(7))
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            layoutParams = lp
            setOnClickListener { triggerCastAction() }
        }
        btnCast = castBtn

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        topBar.addView(zoomBtn)
        topBar.addView(castBtn)
        root.addView(topBar)

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

    /**
     * Custom TV/Cast icon (screen rectangle + wifi/cast waves) so it never looks like a Bluetooth icon.
     */
    private fun createCastScreenIconDrawable(): Drawable {
        val size = dp(24).coerceAtLeast(18)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val f = size.toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_CYAN)
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.085f).coerceAtLeast(1.8f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // TV screen outline
        val screenRect = RectF(f * 0.14f, f * 0.24f, f * 0.86f, f * 0.72f)
        canvas.drawRoundRect(screenRect, f * 0.08f, f * 0.08f, paint)

        // Small stand line under TV
        canvas.drawLine(f * 0.36f, f * 0.82f, f * 0.64f, f * 0.82f, paint)

        // Cast signal arcs inside lower-left of screen
        val arc1 = RectF(f * 0.14f, f * 0.44f, f * 0.44f, f * 0.74f)
        val arc2 = RectF(f * 0.14f, f * 0.54f, f * 0.34f, f * 0.74f)
        canvas.drawArc(arc1, -90f, 90f, false, paint)
        canvas.drawArc(arc2, -90f, 90f, false, paint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_CYAN)
            style = Paint.Style.FILL
        }
        val dotPath = Path().apply {
            addCircle(f * 0.21f, f * 0.65f, (f * 0.055f).coerceAtLeast(1.4f), Path.Direction.CW)
        }
        canvas.drawPath(dotPath, dotPaint)

        return BitmapDrawable(resources, bmp)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
