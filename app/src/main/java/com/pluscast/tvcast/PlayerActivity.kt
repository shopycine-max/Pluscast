package com.pluscast.tvcast

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
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
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URL = "extra_media_url"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
        const val EXTRA_MEDIA_USER_AGENT = "extra_media_user_agent"
        const val EXTRA_MEDIA_REFERRER = "extra_media_referrer"

        const val EXTRA_CAST_MODE = "CAST_MODE"
        const val EXTRA_YOUTUBE_URL = "YOUTUBE_URL"
        const val EXTRA_YOUTUBE_VIDEO_ID = "YOUTUBE_VIDEO_ID"
        const val EXTRA_LOCAL_MEDIA_URI = "LOCAL_MEDIA_URI"
        const val EXTRA_LOCAL_MEDIA_MIME = "LOCAL_MEDIA_MIME"

        const val CAST_MODE_STREAM = "STREAM"
        const val CAST_MODE_YOUTUBE = "YOUTUBE"
        const val CAST_MODE_LOCAL = "LOCAL"

        private const val AUTO_HIDE_DELAY_MS = 1600L

        private const val BG_PLAYER = "#05080F"
        private const val BG_BADGE = "#12243B"
        private const val BORDER_BAR = "#22324F"
        private const val ACCENT_CYAN = "#00E5FF"
        private const val ACCENT_GOLD = "#FBBF24"
        private const val ICON_CAST_SOFT = "#7DD3FC"
        private const val TEXT_PRIMARY = "#F8FAFC"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    }

    private val dlnaController by lazy { DlnaController(this) }
    private val autoHideHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var youtubeWebView: WebView? = null
    private var progressBar: ProgressBar? = null
    private var controlsClusterView: View? = null
    private var unlockPillView: TextView? = null
    private var btnRotate: TextView? = null
    private var btnZoom: TextView? = null
    private var btnYtApp: TextView? = null
    private var btnLock: TextView? = null
    private var btnCast: ImageButton? = null

    private var mediaUrl: String = ""
    private var mediaTitle: String = "PlusCast IPTV"
    private var mediaUserAgent: String = ""
    private var mediaReferrer: String = ""
    private var castMode: String = CAST_MODE_STREAM
    private var youtubeUrl: String = ""
    private var youtubeVideoId: String = ""
    private var localMediaUri: String = ""
    private var localMediaMime: String = ""
    private var isYouTubeContent: Boolean = false

    @Volatile
    private var capturedStreamUrl: String = ""

    @Volatile
    private var capturedStreamMime: String = "video/mp4"

    @Volatile
    private var capturedStreamUa: String = ""

    @Volatile
    private var capturedStreamRef: String = ""

    @Volatile
    private var capturedStreamCookie: String = ""

    private var isScreenLocked: Boolean = false
    private var zoomModeIndex: Int = 0
    private var selectedDlnaDevice: DlnaDevice? = null

    private val zoomModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
    )

    private val hideControlsRunnable = Runnable {
        if (!isScreenLocked && !isYouTubeContent) {
            hideControlsCluster()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor(BG_PLAYER)
        window.navigationBarColor = Color.parseColor(BG_PLAYER)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        castMode = intent.getStringExtra(EXTRA_CAST_MODE)?.trim()?.uppercase(Locale.US)
            ?.takeIf { it in setOf(CAST_MODE_STREAM, CAST_MODE_YOUTUBE, CAST_MODE_LOCAL) }
            ?: CAST_MODE_STREAM

        youtubeUrl = intent.getStringExtra(EXTRA_YOUTUBE_URL)?.trim().orEmpty()
        youtubeVideoId = intent.getStringExtra(EXTRA_YOUTUBE_VIDEO_ID)?.trim().orEmpty()
        localMediaUri = intent.getStringExtra(EXTRA_LOCAL_MEDIA_URI)?.trim().orEmpty()
        localMediaMime = intent.getStringExtra(EXTRA_LOCAL_MEDIA_MIME)?.trim().orEmpty()

        val rawUrl = intent.getStringExtra("MEDIA_URL")?.trim()?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (castMode) {
                CAST_MODE_LOCAL -> localMediaUri
                CAST_MODE_YOUTUBE -> youtubeUrl.ifEmpty {
                    if (youtubeVideoId.isNotEmpty()) "https://m.youtube.com/watch?v=$youtubeVideoId" else ""
                }
                else -> ""
            }

        val candidateForYt = youtubeUrl.ifEmpty {
            if (youtubeVideoId.isNotEmpty()) youtubeVideoId else rawUrl
        }
        isYouTubeContent = castMode == CAST_MODE_YOUTUBE || isYouTubeUrlOrId(candidateForYt)
        if (isYouTubeContent) {
            castMode = CAST_MODE_YOUTUBE
            if (youtubeVideoId.isEmpty()) {
                youtubeVideoId = extractYouTubeId(candidateForYt).orEmpty()
            }
            if (youtubeUrl.isEmpty() && youtubeVideoId.isNotEmpty()) {
                youtubeUrl = "https://m.youtube.com/watch?v=$youtubeVideoId"
            }
        }

        mediaUrl = rawUrl.takeIf(::isValidMediaUrl).orEmpty()
        if (mediaUrl.isNotEmpty()) {
            val lower = mediaUrl.lowercase(Locale.US)
            if (castMode == CAST_MODE_STREAM && (lower.startsWith("content://") || lower.startsWith("file://"))) {
                castMode = CAST_MODE_LOCAL
                if (localMediaUri.isEmpty()) localMediaUri = mediaUrl
            }
        }

        mediaTitle = intent.getStringExtra("MEDIA_TITLE")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_MEDIA_TITLE)?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: when (castMode) {
                CAST_MODE_LOCAL -> "Local Media"
                CAST_MODE_YOUTUBE -> "YouTube"
                else -> "PlusCast IPTV"
            }

        mediaUserAgent = sanitizeHeader(
            intent.getStringExtra("MEDIA_USER_AGENT")?.trim()?.takeIf { it.isNotEmpty() }
                ?: intent.getStringExtra(EXTRA_MEDIA_USER_AGENT)?.trim().orEmpty()
        )

        mediaReferrer = sanitizeHeader(
            intent.getStringExtra("MEDIA_REFERRER")?.trim()?.takeIf { it.isNotEmpty() }
                ?: intent.getStringExtra(EXTRA_MEDIA_REFERRER)?.trim().orEmpty()
        )

        val savedName = intent.getStringExtra("DLNA_DEVICE_NAME")?.trim().orEmpty()
        val savedCtrl = intent.getStringExtra("DLNA_CONTROL_URL")?.trim().orEmpty()
        val savedDial = intent.getStringExtra("DLNA_DIAL_APP_URL")?.trim().orEmpty()
        val savedHost = intent.getStringExtra("DLNA_HOST_IP")?.trim().orEmpty()
        if (savedName.isNotEmpty() && (isValidHttpUrl(savedCtrl) || isValidHttpUrl(savedDial) || savedHost.isNotEmpty())) {
            selectedDlnaDevice = DlnaDevice(
                friendlyName = savedName,
                controlUrl = savedCtrl,
                dialAppUrl = savedDial,
                hostIp = savedHost
            )
        } else {
            selectedDlnaDevice = DlnaDeviceStore.getLastDevice(this)
        }

        val root = buildPlayerUi()
        setContentView(root)
        applySystemBarInsets(root)

        syncOrientationControls(resources.configuration.orientation)
        syncLockState()

        if (isYouTubeContent) {
            val videoId = youtubeVideoId.ifEmpty { extractYouTubeId(candidateForYt).orEmpty() }
            val watchUrl = when {
                videoId.isNotEmpty() -> "https://m.youtube.com/watch?v=$videoId"
                isValidHttpUrl(youtubeUrl) -> youtubeUrl
                else -> ""
            }
            if (watchUrl.isEmpty()) {
                Toast.makeText(this, "YouTube video link nahi mila", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            initYouTubeWebPlayer(watchUrl)
            return
        }

        if (mediaUrl.isEmpty()) {
            Toast.makeText(this, "Valid media URL ya file nahi mila", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initPlayer(mediaUrl)
    }

    private fun extractYouTubeId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.matches(YT_ID_REGEX)) return trimmed
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US).orEmpty()

        if (host.contains("youtu.be")) {
            val seg = uri.lastPathSegment?.trim().orEmpty()
            if (seg.matches(YT_ID_REGEX)) return seg
        }

        uri.getQueryParameter("v")?.trim()?.takeIf { it.matches(YT_ID_REGEX) }?.let { return it }

        val segments = uri.pathSegments.orEmpty()
        for (i in 0 until segments.size - 1) {
            val tag = segments[i].lowercase(Locale.US)
            if (tag == "shorts" || tag == "embed" || tag == "live" || tag == "v") {
                val candidate = segments[i + 1].trim()
                if (candidate.matches(YT_ID_REGEX)) return candidate
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initYouTubeWebPlayer(watchUrl: String) {
        playerView?.visibility = View.GONE
        btnYtApp?.visibility = View.VISIBLE

        val effectiveUa = mediaUserAgent.ifEmpty {
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        youtubeWebView?.let { wv ->
            wv.visibility = View.VISIBLE
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = effectiveUa
            }

            wv.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE
                ) {
                    if (!isScreenLocked) {
                        controlsClusterView?.animate()?.cancel()
                        controlsClusterView?.visibility = View.VISIBLE
                        controlsClusterView?.alpha = 1f
                    }
                }
                false
            }

            wv.setOnScrollChangeListener { _, _, _, _, _ ->
                if (!isScreenLocked) {
                    controlsClusterView?.animate()?.cancel()
                    controlsClusterView?.visibility = View.VISIBLE
                    controlsClusterView?.alpha = 1f
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val updatedId = url?.let(::extractYouTubeId).orEmpty()
                    if (updatedId.isNotEmpty() && updatedId != youtubeVideoId) {
                        youtubeVideoId = updatedId
                        youtubeUrl = "https://m.youtube.com/watch?v=$updatedId"
                        capturedStreamUrl = ""
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val targetUri = request?.url ?: return false
                    val scheme = targetUri.scheme?.lowercase(Locale.US).orEmpty()
                    if (scheme != "http" && scheme != "https") {
                        openExternalYouTube(targetUri)
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (isPlayableVideoStreamUrl(reqUrl)) {
                        val lower = reqUrl.lowercase(Locale.US)
                        val isMuxedMp4 = lower.contains("mime=video%2fmp4") || lower.contains("mime=video/mp4")
                        if (capturedStreamUrl.isEmpty() || isMuxedMp4) {
                            capturedStreamUrl = reqUrl
                            capturedStreamMime = if (lower.contains("webm")) "video/webm" else "video/mp4"
                            capturedStreamUa = request?.requestHeaders?.get("User-Agent").orEmpty().ifBlank { effectiveUa }
                            capturedStreamRef = request?.requestHeaders?.get("Referer").orEmpty().ifBlank {
                                mediaReferrer.ifBlank { "https://m.youtube.com/" }
                            }
                            capturedStreamCookie = runCatching {
                                CookieManager.getInstance().getCookie(reqUrl).orEmpty()
                            }.getOrDefault("")
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar?.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            val headers = if (mediaReferrer.isNotEmpty()) {
                mapOf("Referer" to mediaReferrer)
            } else {
                emptyMap()
            }
            if (headers.isNotEmpty()) {
                wv.loadUrl(watchUrl, headers)
            } else {
                wv.loadUrl(watchUrl)
            }
        }
    }

    private fun isPlayableVideoStreamUrl(url: String): Boolean {
        if (!isValidHttpUrl(url)) return false
        val lower = url.lowercase(Locale.US)
        if (!lower.contains("googlevideo.com/videoplayback")) return false
        if (lower.contains("mime=audio%2f") || lower.contains("mime=audio/")) return false
        return true
    }

    private fun openExternalYouTube(uri: Uri) {
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(appIntent)
        } catch (_: ActivityNotFoundException) {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun openCurrentYouTubeInApp() {
        scheduleAutoHide()
        val currentWvUrl = youtubeWebView?.url.orEmpty()
        val id = extractYouTubeId(currentWvUrl)
            ?: youtubeVideoId.ifEmpty { extractYouTubeId(youtubeUrl).orEmpty() }
        val target = when {
            id.isNotEmpty() -> Uri.parse("https://www.youtube.com/watch?v=$id")
            isValidHttpUrl(currentWvUrl) -> Uri.parse(currentWvUrl)
            youtubeUrl.isNotEmpty() -> Uri.parse(youtubeUrl)
            else -> null
        } ?: run {
            Toast.makeText(this, "YouTube link nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        openExternalYouTube(target)
    }

    private fun isYouTubeUrlOrId(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.US)
        if (lower.contains("youtube.com/") ||
            lower.contains("youtu.be/") ||
            lower.contains("youtube-nocookie.com/")
        ) {
            return true
        }
        return extractYouTubeId(trimmed) != null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncOrientationControls(newConfig.orientation)
        if (!isScreenLocked) {
            controlsClusterView?.visibility = View.VISIBLE
            controlsClusterView?.alpha = 1f
            scheduleAutoHide()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isYouTubeContent) {
            if (player == null && mediaUrl.isNotEmpty()) {
                initPlayer(mediaUrl)
            } else {
                player?.playWhenReady = true
            }
        } else {
            youtubeWebView?.onResume()
        }
    }

    override fun onStop() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        player?.playWhenReady = false
        if (isYouTubeContent) {
            youtubeWebView?.onPause()
        }
        super.onStop()
    }

    override fun onDestroy() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        releasePlayer()
        youtubeWebView?.apply {
            stopLoading()
            destroy()
        }
        youtubeWebView = null
        dlnaController.releaseLocalMediaServer()
        super.onDestroy()
    }

    private fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank() || url.any { it.isISOControl() || it.isWhitespace() }) return false
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun isValidMediaUrl(url: String): Boolean {
        if (url.isBlank() || url.any { it.isISOControl() || it.isWhitespace() }) return false
        val lower = url.lowercase(Locale.US)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
    }

    private fun sanitizeHeader(value: String): String {
        return value.filterNot { it == '\r' || it == '\n' || it.isISOControl() }.trim()
    }

    private fun isLandscape(orientation: Int): Boolean =
        orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun blinkControlsOnTouch() {
        if (isScreenLocked) {
            unlockPillView?.let { pill ->
                pill.animate().cancel()
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
        if (isYouTubeContent || isScreenLocked) return
        controlsClusterView?.let { cluster ->
            cluster.animate().cancel()
            cluster.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    if (!isScreenLocked && !isYouTubeContent) {
                        cluster.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    private fun scheduleAutoHide() {
        autoHideHandler.removeCallbacks(hideControlsRunnable)
        if (!isScreenLocked && !isYouTubeContent) {
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
        if (land && !isYouTubeContent) {
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

        val wv = WebView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor(BG_PLAYER))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        youtubeWebView = wv
        root.addView(wv)

        if (!isYouTubeContent) {
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
        }

        val spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            val lp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
            layoutParams = lp
        }
        progressBar = spinner
        root.addView(spinner)

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

        val ytAppBtn = TextView(this).apply {
            text = "▶ YT App"
            visibility = View.GONE
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
                openCurrentYouTubeInApp()
            }
        }
        btnYtApp = ytAppBtn

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
        controlsCluster.addView(ytAppBtn)
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
        v.animate().cancel()
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

        val effectiveUa = mediaUserAgent.ifEmpty { "PlusCast/1.0 (Android; IPTV)" }
        val headers = buildMap {
            put("User-Agent", effectiveUa)
            if (mediaReferrer.isNotEmpty()) {
                put("Referer", mediaReferrer)
            }
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(effectiveUa)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)

        val combinedDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(combinedDataSourceFactory)

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
                            "Media unavailable (${error.errorCodeName})",
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

        discoverAndPickCastDevice()
    }

    private fun discoverAndPickCastDevice() {
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
            val res = when {
                isYouTubeContent -> {
                    val activeUrl = youtubeWebView?.url.orEmpty()
                    val ytTarget = activeUrl.takeIf(::isValidHttpUrl)
                        ?: youtubeUrl.ifEmpty {
                            if (youtubeVideoId.isNotEmpty()) youtubeVideoId else mediaUrl
                        }
                    val streamCandidate = capturedStreamUrl.takeIf(::isValidHttpUrl)
                    val capturedPayload = streamCandidate?.let { streamUrl ->
                        val activeVideoId = extractYouTubeId(ytTarget)
                            ?: youtubeVideoId.ifEmpty { "unknown" }
                        CapturedYouTubeStream(
                            videoId = activeVideoId,
                            streamUrl = streamUrl,
                            title = youtubeWebView?.title?.trim()?.takeIf { it.isNotEmpty() } ?: mediaTitle,
                            mimeType = capturedStreamMime,
                            userAgent = capturedStreamUa,
                            referrer = capturedStreamRef,
                            cookie = capturedStreamCookie
                        )
                    }
                    dlnaController.playYouTubeTubioStyle(
                        device = device,
                        youtubeUrlOrId = ytTarget,
                        capturedStream = capturedPayload,
                        fallbackTitle = mediaTitle,
                        fallbackMimeType = capturedStreamMime
                    )
                }
                castMode == CAST_MODE_LOCAL -> {
                    val targetUri = Uri.parse(localMediaUri.ifEmpty { mediaUrl })
                    dlnaController.playLocalMediaOnDevice(
                        device = device,
                        uri = targetUri,
                        title = mediaTitle,
                        mimeType = localMediaMime.ifEmpty { "video/mp4" }
                    )
                }
                else -> {
                    dlnaController.playIptvOrRemoteStream(
                        device = device,
                        mediaUrl = mediaUrl,
                        title = mediaTitle,
                        userAgent = mediaUserAgent,
                        referrer = mediaReferrer
                    )
                }
            }
            if (isFinishing || isDestroyed) return@launch

            if (res.success) {
                Toast.makeText(
                    this@PlayerActivity,
                    "${device.friendlyName} par chal raha hai",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@PlayerActivity,
                    res.message,
                    Toast.LENGTH_LONG
                ).show()
            }
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
