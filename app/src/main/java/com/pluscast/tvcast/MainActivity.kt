package com.pluscast.tvcast

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.appbar.MaterialToolbar
import com.pluscast.tvcast.cast.CastOptionsProvider
import com.pluscast.tvcast.server.LocalMediaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "pluscast_iptv_prefs"
        private const val KEY_PLAYLIST_URL = "last_playlist_url"
        private const val KEY_LAST_REFRESH_MS = "last_playlist_refresh_ms"
        private const val KEY_FAVORITES = "favorite_channel_urls"
        private const val KEY_LAST_URL = "last_watched_url"
        private const val KEY_LAST_TITLE = "last_watched_title"

        const val EXTRA_CAST_MODE = "CAST_MODE"
        const val EXTRA_YOUTUBE_URL = "YOUTUBE_URL"
        const val EXTRA_YOUTUBE_VIDEO_ID = "YOUTUBE_VIDEO_ID"
        const val EXTRA_LOCAL_MEDIA_URI = "LOCAL_MEDIA_URI"
        const val EXTRA_LOCAL_MEDIA_MIME = "LOCAL_MEDIA_MIME"

        const val CAST_MODE_STREAM = "STREAM"
        const val CAST_MODE_YOUTUBE = "YOUTUBE"
        const val CAST_MODE_LOCAL = "LOCAL"

        private const val YOUTUBE_MOBILE_HOME_URL = "https://m.youtube.com/"
        private const val DAILY_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val DEFAULT_PLAYLIST_URL = M3uPlaylistParser.DEFAULT_PLAYLIST_URL

        private const val BG_PRIMARY = "#070B12"
        private const val BG_GRADIENT_TOP = "#0D1524"
        private const val BG_SURFACE = "#101826"
        private const val BG_CARD = "#131D30"
        private const val BG_BADGE = "#12243B"
        private const val BORDER_SUBTLE = "#1F2D46"
        private const val BORDER_CARD = "#22324F"
        private const val ACCENT_CYAN = "#00E5FF"
        private const val ACCENT_SKY = "#38BDF8"
        private const val ACCENT_GOLD = "#FBBF24"
        private const val ACCENT_RED = "#FF4D6D"
        private const val TEXT_PRIMARY = "#F8FAFC"
        private const val TEXT_SECONDARY = "#94A3B8"
    }

    private val dlnaController by lazy {
        DlnaController(context = this)
    }
    private val youtubeStreamSniffer = YouTubeStreamSniffer()
    private var localMediaServer: LocalMediaServer? = null
    private var castContext: CastContext? = null

    private var toolbarView: MaterialToolbar? = null
    private var tvDlnaStatus: TextView? = null
    private var btnYouTubeCast: TextView? = null
    private var btnLocalCast: TextView? = null
    private var tvLastWatched: TextView? = null
    private var tvChannelCount: TextView? = null
    private var etSearch: EditText? = null
    private var btnRefresh: TextView? = null
    private var progressBar: ProgressBar? = null
    private var groupContainer: LinearLayout? = null
    private var recyclerView: RecyclerView? = null

    private var allChannels: List<IptvChannel> = emptyList()
    private var favoriteUrls: MutableSet<String> = mutableSetOf()
    private var selectedGroup: String = "All"
    private var availableTabs: List<String> = listOf("All", "Favorites")
    private var channelAdapter: ChannelCardAdapter? = null

    private var isLoadingPlaylist: Boolean = false
    private var dailyTickerJob: Job? = null

    private val castSessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            updateCastStatusIndicator()
        }
        override fun onSessionStartFailed(session: CastSession, errorCode: Int) {
            updateCastStatusIndicator()
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, errorCode: Int) {
            updateCastStatusIndicator()
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            updateCastStatusIndicator()
        }
        override fun onSessionResumeFailed(session: CastSession, errorCode: Int) {
            updateCastStatusIndicator()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            updateCastStatusIndicator()
        }
    }

    private val localMediaPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            handleLocalMediaSelected(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor(BG_PRIMARY)
        window.navigationBarColor = Color.parseColor(BG_PRIMARY)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        castContext = runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        favoriteUrls = loadFavorites()

        val rootView = buildProfessionalUi()
        setContentView(rootView)
        setupToolbarAndMenu()
        applySystemBarInsets(rootView)

        updateCastStatusIndicator()
        updateLastWatchedPill()
        rebuildGroupChips(emptyList())
        setupListeners()

        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isNotEmpty()) {
            loadPlaylist(activeUrl, force = true, showToast = true)
            startDailyAutoRefreshLoop()
        } else {
            tvChannelCount?.text = "Playlist link configure karein"
        }

        handleIncomingYouTubeOrShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingYouTubeOrShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        castContext?.sessionManager?.addSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        updateCastStatusIndicator()
    }

    override fun onStop() {
        castContext?.sessionManager?.removeSessionManagerListener(
            castSessionListener,
            CastSession::class.java
        )
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateCastStatusIndicator()
        updateLastWatchedPill()

        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isNotEmpty() && !isLoadingPlaylist) {
            val dueDaily = isDailyRefreshDue()
            if (allChannels.isEmpty() || dueDaily) {
                loadPlaylist(activeUrl, force = allChannels.isEmpty() || dueDaily, showToast = false)
            }
        }
    }

    override fun onDestroy() {
        dailyTickerJob?.cancel()
        dailyTickerJob = null
        youtubeStreamSniffer.clear()
        dlnaController.releaseLocalMediaServer()
        stopLocalRelayServer()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_cast, menu)
        runCatching {
            CastButtonFactory.setUpMediaRouteButton(
                applicationContext,
                menu,
                R.id.media_route_menu_item
            )
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_cast_youtube -> {
                triggerDirectYouTubeFlow()
                true
            }
            R.id.action_cast_local -> {
                openLocalMediaPicker()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupToolbarAndMenu() {
        val tb = toolbarView ?: return
        setSupportActionBar(tb)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        tb.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_cast_youtube -> {
                    triggerDirectYouTubeFlow()
                    true
                }
                R.id.action_cast_local -> {
                    openLocalMediaPicker()
                    true
                }
                else -> false
            }
        }
    }

    private fun getActiveCastSession(): CastSession? {
        val session = castContext?.sessionManager?.currentCastSession
        return if (session != null && session.isConnected) session else null
    }

    private fun updateCastStatusIndicator() {
        val castSession = getActiveCastSession()
        if (castSession != null) {
            val deviceName = castSession.castDevice?.friendlyName?.trim().orEmpty()
                .ifEmpty { "Cast Device" }
            tvDlnaStatus?.text = "Cast: $deviceName"
            tvDlnaStatus?.setTextColor(ContextCompat.getColor(this, R.color.pc_cast_active))
            return
        }

        val saved = DlnaDeviceStore.getLastDevice(this)
        if (saved != null) {
            tvDlnaStatus?.text = "TV: ${saved.friendlyName}"
            tvDlnaStatus?.setTextColor(ContextCompat.getColor(this, R.color.pc_cast_active))
        } else {
            tvDlnaStatus?.text = "DLNA: Device Chunein"
            tvDlnaStatus?.setTextColor(ContextCompat.getColor(this, R.color.pc_accent_cyan))
        }
    }

    private fun startDailyAutoRefreshLoop() {
        dailyTickerJob?.cancel()
        dailyTickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(60 * 60 * 1000L)
                val activeUrl = resolveHiddenPlaylistUrl()
                if (activeUrl.isNotEmpty() && !isLoadingPlaylist && isDailyRefreshDue()) {
                    loadPlaylist(activeUrl, force = true, showToast = false)
                }
            }
        }
    }

    private fun isDailyRefreshDue(): Boolean {
        val lastMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_LAST_REFRESH_MS, 0L)
        if (lastMs <= 0L) return true
        return (System.currentTimeMillis() - lastMs) >= DAILY_REFRESH_INTERVAL_MS ||
            M3uPlaylistParser.isDailyRefreshDue(applicationContext)
    }

    private fun markPlaylistRefreshedNow() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REFRESH_MS, System.currentTimeMillis())
            .apply()
        M3uPlaylistParser.markRefreshedNow(applicationContext)
    }

    private fun applySystemBarInsets(root: View) {
        val baseSide = dp(16)
        val baseTop = dp(24)
        val baseBottom = dp(12)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseSide + bars.left,
                maxOf(baseTop, bars.top + dp(12)),
                baseSide + bars.right,
                maxOf(baseBottom, bars.bottom + dp(8))
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildProfessionalUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor(BG_GRADIENT_TOP),
                    Color.parseColor(BG_PRIMARY)
                )
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }

        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContentInsetsRelative(0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }
        toolbarView = toolbar
        root.addView(toolbar)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        }

        val logoView = ImageView(this).apply {
            setImageDrawable(createLogoDrawable())
            contentDescription = "PlusCast IPTV Logo"
            val lp = LinearLayout.LayoutParams(dp(48), dp(48))
            lp.marginEnd = dp(12)
            layoutParams = lp
        }

        val brandBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(this).apply {
            text = "PLUSCAST IPTV"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 19.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.04f
        }

        val badgesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dlnaBadge = TextView(this).apply {
            text = "DLNA: Scan"
            setTextColor(Color.parseColor(ACCENT_CYAN))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_BADGE,
                normalStroke = "#1E4966",
                focusStroke = ACCENT_CYAN,
                radiusDp = 999
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        tvDlnaStatus = dlnaBadge

        val ytCastBadge = TextView(this).apply {
            text = "▶ YouTube"
            setTextColor(Color.parseColor(ACCENT_RED))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_SURFACE,
                normalStroke = "#5E1F2D",
                focusStroke = ACCENT_RED,
                radiusDp = 999
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        btnYouTubeCast = ytCastBadge

        val localCastBadge = TextView(this).apply {
            text = "📁 Local Cast"
            setTextColor(Color.parseColor(ACCENT_SKY))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_SURFACE,
                normalStroke = "#1E3A5F",
                focusStroke = ACCENT_SKY,
                radiusDp = 999
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        btnLocalCast = localCastBadge

        val lastWatchedBadge = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor(ACCENT_GOLD))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_SURFACE,
                normalStroke = "#5B4613",
                focusStroke = ACCENT_GOLD,
                radiusDp = 999
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        tvLastWatched = lastWatchedBadge

        badgesRow.addView(dlnaBadge)
        badgesRow.addView(ytCastBadge)
        badgesRow.addView(localCastBadge)
        badgesRow.addView(lastWatchedBadge)
        badgesScroll.addView(badgesRow)

        val refreshBtn = TextView(this).apply {
            text = "↻ Refresh"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isFocusable = true
            isClickable = true
            background = createFocusPillDrawable(
                normalFill = BG_SURFACE,
                normalStroke = BORDER_SUBTLE,
                focusStroke = ACCENT_CYAN,
                radiusDp = 12
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        btnRefresh = refreshBtn

        brandBlock.addView(tvTitle)
        brandBlock.addView(badgesScroll)
        headerRow.addView(logoView)
        headerRow.addView(brandBlock)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        val searchBox = EditText(this).apply {
            hint = "Channel, language ya group khojein..."
            setHintTextColor(Color.parseColor(TEXT_SECONDARY))
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            background = createFocusPillDrawable(
                normalFill = BG_SURFACE,
                normalStroke = BORDER_SUBTLE,
                focusStroke = ACCENT_CYAN,
                radiusDp = 14
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14)
            layoutParams = lp
        }
        etSearch = searchBox
        root.addView(searchBox)

        val groupScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }

        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        groupContainer = chipsRow
        groupScroll.addView(chipsRow)
        root.addView(groupScroll)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val countView = TextView(this).apply {
            text = "Channels: 0 • Long-press to Pin"
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvChannelCount = countView

        val spinner = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        progressBar = spinner

        metaRow.addView(countView)
        metaRow.addView(spinner)
        root.addView(metaRow)

        val fallbackLogo = createLogoDrawable()
        val adapter = ChannelCardAdapter(
            lifecycleScope = lifecycleScope,
            fallbackDrawable = fallbackLogo,
            isFavorite = { ch -> favoriteUrls.contains(ch.url) },
            onClick = { channel -> openChannelInPlayer(channel) },
            onLongClick = { channel -> toggleFavorite(channel) }
        )
        channelAdapter = adapter

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            overScrollMode = View.OVER_SCROLL_NEVER
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        recyclerView = rv
        root.addView(rv)

        return root
    }

    private fun setupListeners() {
        btnRefresh?.setOnClickListener {
            val url = resolveHiddenPlaylistUrl()
            if (url.isNotEmpty()) {
                loadPlaylist(url, force = true, showToast = true)
            } else {
                Toast.makeText(this, "DEFAULT_PLAYLIST_URL set nahi hai", Toast.LENGTH_SHORT).show()
            }
        }

        tvDlnaStatus?.setOnClickListener {
            discoverAndPickDlnaDevice()
        }

        btnYouTubeCast?.setOnClickListener {
            triggerDirectYouTubeFlow()
        }

        btnLocalCast?.setOnClickListener {
            openLocalMediaPicker()
        }

        tvLastWatched?.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val lastUrl = prefs.getString(KEY_LAST_URL, null)?.trim().orEmpty()
            val lastTitle = prefs.getString(KEY_LAST_TITLE, null)?.trim().orEmpty()
            if (lastUrl.isNotEmpty()) {
                launchPlayer(
                    url = lastUrl,
                    title = lastTitle.ifEmpty { "PlusCast IPTV" }
                )
            }
        }

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // ==================== EXTERNAL SHARE / INTENT HANDLING ====================

    private fun handleIncomingYouTubeOrShareIntent(incoming: Intent?) {
        val intent = incoming ?: return
        val sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }.trim()

        if (sharedText.isEmpty()) return

        val extractedUrl = Regex("https?://\\S+")
            .find(sharedText)
            ?.value
            ?.trim(')', ']', '>', '"', '\'', ',')
            ?: sharedText

        val videoId = M3uPlaylistParser.extractYouTubeVideoId(extractedUrl).orEmpty()
        if (videoId.isEmpty()) return

        castYouTubeVideoToActiveTarget(
            videoId = videoId,
            title = "YouTube ($videoId)",
            sourceUrl = "https://www.youtube.com/watch?v=$videoId",
            capturedStream = youtubeStreamSniffer.latestStream
        )
    }

    private fun castYouTubeVideoToActiveTarget(
        videoId: String,
        title: String,
        sourceUrl: String,
        capturedStream: CapturedYouTubeStream? = null
    ) {
        val ytWatchUrl = "https://www.youtube.com/watch?v=$videoId"
        val effectiveStream = capturedStream
            ?: youtubeStreamSniffer.latestStream?.takeIf {
                it.videoId == "unknown" || it.videoId == videoId
            }

        saveLastWatched(url = sourceUrl, title = title)
        updateLastWatchedPill()

        val activeCast = getActiveCastSession()
        val savedDevice = DlnaDeviceStore.getLastDevice(this)

        if (activeCast == null && savedDevice == null) {
            Toast.makeText(
                this,
                "Pehle DLNA TV ya Cast device chunein",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(this, "TV par cast bhej rahe hain...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            var anySuccess = false
            var shouldOfferSystemCast = false
            var lastMsg = ""

            if (activeCast != null && effectiveStream != null && effectiveStream.streamUrl.isNotBlank()) {
                val castLoaded = runCatching {
                    val mediaInfo = MediaInfo.Builder(effectiveStream.streamUrl)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType(effectiveStream.mimeType.ifBlank { "video/mp4" })
                        .setMetadata(
                            MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                                putString(MediaMetadata.KEY_TITLE, title)
                            }
                        )
                        .build()
                    val req = MediaLoadRequestData.Builder()
                        .setMediaInfo(mediaInfo)
                        .setAutoplay(true)
                        .build()
                    activeCast.remoteMediaClient?.load(req)
                    true
                }.getOrElse { false }
                if (castLoaded) {
                    anySuccess = true
                    lastMsg = "${activeCast.castDevice?.friendlyName ?: "Cast TV"} par bhej diya"
                }
            }

            if (savedDevice != null) {
                val dlnaRes = dlnaController.playYouTubeTubioStyle(
                    device = savedDevice,
                    youtubeUrlOrId = ytWatchUrl,
                    capturedStream = effectiveStream,
                    fallbackTitle = title,
                    fallbackMimeType = effectiveStream?.mimeType ?: "video/mp4"
                )
                if (dlnaRes.success) {
                    anySuccess = true
                    lastMsg = dlnaRes.message
                } else if (!anySuccess) {
                    lastMsg = dlnaRes.message
                    shouldOfferSystemCast = true
                }
            }

            if (isFinishing || isDestroyed) return@launch

            if (!anySuccess && shouldOfferSystemCast) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Cast Options")
                    .setMessage(lastMsg.ifBlank { "TV par YouTube auto-launch nahi hua." })
                    .setPositiveButton("Open Cast Settings") { _, _ ->
                        openSystemCastSettings()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    lastMsg.ifBlank { "Cast bhej nahi paye" },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openSystemCastSettings() {
        val castIntent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(castIntent) }
            .recoverCatching { startActivity(fallbackIntent) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Cast settings open nahi ho saki",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    // ==================== IN-APP YOUTUBE BROWSER ====================

    private fun triggerDirectYouTubeFlow() {
        youtubeStreamSniffer.clear()
        openYouTubeBrowserScreen(initialUrl = YOUTUBE_MOBILE_HOME_URL, title = "YouTube")
    }

    private fun openYouTubeBrowserScreen(
        initialUrl: String = YOUTUBE_MOBILE_HOME_URL,
        title: String = "YouTube"
    ) {
        val normalizedStart = normalizeYouTubeBrowserUrl(initialUrl)
        saveLastWatched(url = normalizedStart, title = title)
        updateLastWatchedPill()

        var latestPageUrl = normalizedStart
        var latestPageTitle = title
        var activeVideoId = M3uPlaylistParser.extractYouTubeVideoId(normalizedStart).orEmpty()

        CookieManager.getInstance().setAcceptCookie(true)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG_PRIMARY))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(BG_SURFACE))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val tvUrl = TextView(this).apply {
            text = normalizedStart
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnCastCurrent = TextView(this).apply {
            text = "Cast TV"
            setTextColor(Color.parseColor(ACCENT_CYAN))
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor(ACCENT_RED))
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(4), dp(6), dp(4))
        }

        topBar.addView(tvUrl)
        topBar.addView(btnCastCurrent)
        topBar.addView(btnClose)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 15
            indeterminateTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            progressTintList = ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
            )
        }

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor(BG_PRIMARY))
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                    super.onReceivedTitle(view, pageTitle)
                    if (!pageTitle.isNullOrBlank()) {
                        latestPageTitle = pageTitle.trim()
                    }
                    youtubeStreamSniffer.onPageTitleUpdated(latestPageUrl, latestPageTitle)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val targetUrl = request?.url?.toString().orEmpty()
                    if (targetUrl.startsWith("http://", true) || targetUrl.startsWith("https://", true)) {
                        return false
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (reqUrl.isNotEmpty()) {
                        youtubeStreamSniffer.inspectRequest(
                            pageUrl = latestPageUrl,
                            requestUrl = reqUrl,
                            headers = request?.requestHeaders,
                            currentTitle = latestPageTitle
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    progress.visibility = View.VISIBLE
                    val current = url.orEmpty()
                    if (current.isNotEmpty()) {
                        latestPageUrl = current
                        tvUrl.text = current
                        val vid = M3uPlaylistParser.extractYouTubeVideoId(current).orEmpty()
                        if (vid.isNotEmpty() && vid != activeVideoId) {
                            activeVideoId = vid
                            youtubeStreamSniffer.clear()
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = View.GONE
                    val current = url.orEmpty()
                    if (current.isNotEmpty()) {
                        latestPageUrl = current
                        tvUrl.text = current
                        val pageTitle = latestPageTitle.takeIf { it.isNotBlank() } ?: title
                        youtubeStreamSniffer.onPageTitleUpdated(current, pageTitle)
                        saveLastWatched(
                            url = current,
                            title = pageTitle
                        )
                        updateLastWatchedPill()
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val current = url.orEmpty()
                    if (current.isNotEmpty()) {
                        latestPageUrl = current
                        tvUrl.text = current
                        val vid = M3uPlaylistParser.extractYouTubeVideoId(current).orEmpty()
                        if (vid.isNotEmpty() && vid != activeVideoId) {
                            activeVideoId = vid
                            youtubeStreamSniffer.clear()
                        }
                        val pageTitle = latestPageTitle.takeIf { it.isNotBlank() } ?: title
                        youtubeStreamSniffer.onPageTitleUpdated(current, pageTitle)
                        saveLastWatched(
                            url = current,
                            title = pageTitle
                        )
                        updateLastWatchedPill()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        progress.visibility = View.GONE
                        val desc = error?.description?.toString().orEmpty()
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(
                                this@MainActivity,
                                "YouTube load error: ${desc.ifBlank { "Connection issue" }}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    progress.visibility = View.GONE
                    runCatching {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                    }
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this@MainActivity,
                            "WebView render reset hua, dobara kholein",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }
            }
            loadUrl(normalizedStart)
        }

        container.addView(topBar)
        container.addView(progress)
        container.addView(webView)

        dialog.setContentView(container)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor(BG_PRIMARY)))

        btnClose.setOnClickListener {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
            dialog.dismiss()
        }

        btnCastCurrent.setOnClickListener {
            val currentUrl = latestPageUrl.trim().ifEmpty {
                tvUrl.text?.toString()?.trim().orEmpty().ifEmpty { normalizedStart }
            }
            val currentTitle = latestPageTitle.trim().takeIf { it.isNotBlank() } ?: title
            val videoId = M3uPlaylistParser.extractYouTubeVideoId(currentUrl).orEmpty()

            if (videoId.isEmpty()) {
                Toast.makeText(
                    this,
                    "Pehle YouTube par koi video open karein, phir Cast TV dabayein",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            castYouTubeVideoToActiveTarget(
                videoId = videoId,
                title = currentTitle,
                sourceUrl = currentUrl,
                capturedStream = youtubeStreamSniffer.latestStream
            )
        }

        dialog.setOnDismissListener {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }

        dialog.show()
        webView.requestFocus()
    }

    private fun normalizeYouTubeBrowserUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return YOUTUBE_MOBILE_HOME_URL
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val videoId = M3uPlaylistParser.extractYouTubeVideoId(trimmed)
        return if (videoId != null) {
            "https://m.youtube.com/watch?v=$videoId"
        } else {
            YOUTUBE_MOBILE_HOME_URL
        }
    }

    // ==================== LOCAL MEDIA CASTING ====================

    private fun openLocalMediaPicker() {
        localMediaPickerLauncher.launch(arrayOf("video/*", "audio/*"))
    }

    private fun handleLocalMediaSelected(uri: Uri) {
        val mimeType = contentResolver.getType(uri)
            ?: guessMimeFromUri(uri)
            ?: "video/mp4"
        val displayName = resolveDisplayName(uri) ?: "Local Media"

        saveLastWatched(url = uri.toString(), title = displayName)
        updateLastWatchedPill()

        val savedDevice = DlnaDeviceStore.getLastDevice(this)
        val hasCastSession = getActiveCastSession() != null

        if (savedDevice != null || hasCastSession) {
            val targetLabel = savedDevice?.friendlyName ?: "Connected Cast Device"
            AlertDialog.Builder(this)
                .setTitle("Local Media Cast")
                .setMessage("\"$displayName\" ko $targetLabel par cast karein ya phone player mein chalayein?")
                .setPositiveButton("TV par Cast Karein") { _, _ ->
                    lifecycleScope.launch {
                        var castStarted = false

                        if (hasCastSession) {
                            val relayUrl = startLocalRelayForUri(uri, mimeType, displayName)
                            val castReq = relayUrl?.let {
                                CastOptionsProvider.buildLocalStreamMediaLoadRequest(
                                    relayHttpUrl = it,
                                    title = displayName,
                                    mimeType = mimeType
                                )
                            }
                            if (castReq != null) {
                                CastOptionsProvider.loadMediaOnActiveCastSession(this@MainActivity, castReq)
                                castStarted = true
                            }
                        }

                        if (savedDevice != null) {
                            val dlnaRes = dlnaController.playLocalMediaOnDevice(
                                device = savedDevice,
                                uri = uri,
                                title = displayName,
                                mimeType = mimeType
                            )
                            if (dlnaRes.success) {
                                castStarted = true
                            }
                        }

                        if (isFinishing || isDestroyed) return@launch
                        if (castStarted) {
                            Toast.makeText(
                                this@MainActivity,
                                "$displayName → $targetLabel",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Cast bhej nahi paya, player khol rahe hain",
                                Toast.LENGTH_SHORT
                            ).show()
                            launchPlayer(
                                url = uri.toString(),
                                title = displayName,
                                castMode = CAST_MODE_LOCAL,
                                localMediaUri = uri.toString(),
                                localMediaMime = mimeType
                            )
                        }
                    }
                }
                .setNeutralButton("Player Mein Chalayein") { _, _ ->
                    launchPlayer(
                        url = uri.toString(),
                        title = displayName,
                        castMode = CAST_MODE_LOCAL,
                        localMediaUri = uri.toString(),
                        localMediaMime = mimeType
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            launchPlayer(
                url = uri.toString(),
                title = displayName,
                castMode = CAST_MODE_LOCAL,
                localMediaUri = uri.toString(),
                localMediaMime = mimeType
            )
        }
    }

    private suspend fun startLocalRelayForUri(
        uri: Uri,
        mimeType: String,
        displayName: String
    ): String? = withContext(Dispatchers.IO) {
        stopLocalRelayServer()
        val server = LocalMediaServer(applicationContext, port = 8080).apply {
            setUriToServe(uri = uri, mimeType = mimeType, displayName = displayName)
        }
        if (server.startServerSafe()) {
            localMediaServer = server
            server.getStreamUrl()
        } else {
            null
        }
    }

    private fun stopLocalRelayServer() {
        localMediaServer?.stopServerSafe()
        localMediaServer = null
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }

    private fun guessMimeFromUri(uri: Uri): String? {
        val lower = uri.toString().lowercase(Locale.US)
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".flac") -> "audio/flac"
            else -> null
        }
    }

    // ==================== EXISTING FLOWS ====================

    private fun saveLastWatched(url: String, title: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_URL, url)
            .putString(KEY_LAST_TITLE, title)
            .apply()
    }

    private fun updateLastWatchedPill() {
        val lastTitle = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_TITLE, null)?.trim().orEmpty()
        val lastUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_LAST_URL, null)?.trim().orEmpty()

        if (lastUrl.isNotEmpty() && lastTitle.isNotEmpty()) {
            tvLastWatched?.visibility = View.VISIBLE
            tvLastWatched?.text = "▶ $lastTitle"
        } else {
            tvLastWatched?.visibility = View.GONE
        }
    }

    private fun toggleFavorite(channel: IptvChannel) {
        val added = if (favoriteUrls.contains(channel.url)) {
            favoriteUrls.remove(channel.url)
            false
        } else {
            favoriteUrls.add(channel.url)
            true
        }
        saveFavorites(favoriteUrls)
        applyFilters()
        Toast.makeText(
            this,
            if (added) "${channel.name} Favorites mein pin ho gaya"
            else "${channel.name} Favorites se hat gaya",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadFavorites(): MutableSet<String> {
        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet())
            .orEmpty()
        return saved.toMutableSet()
    }

    private fun saveFavorites(set: Set<String>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITES, set.toSet())
            .apply()
    }

    private fun discoverAndPickDlnaDevice() {
        Toast.makeText(this, "DLNA devices khoj raha hai...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val devices = dlnaController.discoverRenderers()
            if (isFinishing || isDestroyed) return@launch

            if (devices.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Koi DLNA renderer nahi mila",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val names = devices.map { it.friendlyName }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("DLNA Renderer Chunein")
                .setItems(names) { _, which ->
                    val chosen = devices[which]
                    DlnaDeviceStore.saveLastDevice(this@MainActivity, chosen)
                    updateCastStatusIndicator()
                    Toast.makeText(
                        this@MainActivity,
                        "${chosen.friendlyName} set ho gaya",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun resolveHiddenPlaylistUrl(): String {
        val defaultUrl = DEFAULT_PLAYLIST_URL.trim()
        if (defaultUrl.isNotEmpty()) {
            savePlaylistUrl(defaultUrl)
            return defaultUrl
        }
        return getSavedPlaylistUrl()
    }

    private fun loadPlaylist(url: String, force: Boolean, showToast: Boolean) {
        if (isLoadingPlaylist) return
        if (!force && allChannels.isNotEmpty() && !isDailyRefreshDue()) return

        isLoadingPlaylist = true
        progressBar?.visibility = View.VISIBLE
        btnRefresh?.isEnabled = false
        if (allChannels.isEmpty()) {
            tvChannelCount?.text = "Channels load ho rahe hain..."
        }

        lifecycleScope.launch {
            val fetched = M3uPlaylistParser.fetchChannels(
                playlistUrl = url,
                context = applicationContext,
                forceRefresh = force
            )
            if (isFinishing || isDestroyed) return@launch

            isLoadingPlaylist = false
            progressBar?.visibility = View.GONE
            btnRefresh?.isEnabled = true

            if (fetched.isNotEmpty()) {
                markPlaylistRefreshedNow()
                allChannels = fetched
                rebuildGroupChips(fetched)
                if (!availableTabs.any { it.equals(selectedGroup, ignoreCase = true) }) {
                    selectedGroup = "All"
                }
                applyFilters()
                if (showToast) {
                    Toast.makeText(
                        this@MainActivity,
                        "${fetched.size} channels ready",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (allChannels.isEmpty()) {
                tvChannelCount?.text = "Channels load nahi ho paye"
                if (showToast) {
                    Toast.makeText(
                        this@MainActivity,
                        "Channels load nahi ho paye",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (showToast) {
                Toast.makeText(
                    this@MainActivity,
                    "Nayi list nahi mili, cached list dikha rahe hain",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun rebuildGroupChips(channels: List<IptvChannel>) {
        val container = groupContainer ?: return
        container.removeAllViews()

        val dynamicGroups = channels
            .mapNotNull { ch ->
                when {
                    ch.language.isNotBlank() -> ch.language.trim()
                    ch.group.isNotBlank() && !ch.group.equals("All", ignoreCase = true) -> ch.group.trim()
                    else -> null
                }
            }
            .distinct()
            .take(30)

        availableTabs = buildList {
            add("All")
            add("Favorites")
            addAll(dynamicGroups)
        }

        for (groupName in availableTabs) {
            val isSelected = selectedGroup.equals(groupName, ignoreCase = true)
            val chip = TextView(this).apply {
                text = if (groupName == "Favorites") "★ Favorites" else groupName
                textSize = 12f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isFocusable = true
                isClickable = true
                setTextColor(
                    if (isSelected) Color.parseColor(BG_PRIMARY)
                    else Color.parseColor(TEXT_PRIMARY)
                )
                background = createFocusPillDrawable(
                    normalFill = if (isSelected) ACCENT_CYAN else BG_SURFACE,
                    normalStroke = if (isSelected) ACCENT_CYAN else BORDER_SUBTLE,
                    focusStroke = ACCENT_GOLD,
                    radiusDp = 999
                )
                setPadding(dp(12), dp(7), dp(12), dp(7))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener {
                    selectedGroup = groupName
                    rebuildGroupChips(allChannels)
                    applyFilters()
                }
            }
            container.addView(chip)
        }
    }

    private fun applyFilters() {
        val q = etSearch?.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty()

        val filtered = allChannels
            .filter { ch ->
                val groupMatch = when {
                    selectedGroup.equals("All", ignoreCase = true) -> true
                    selectedGroup.equals("Favorites", ignoreCase = true) -> favoriteUrls.contains(ch.url)
                    else -> ch.language.equals(selectedGroup, ignoreCase = true) ||
                        ch.group.equals(selectedGroup, ignoreCase = true)
                }
                val queryMatch = q.isEmpty() ||
                    ch.name.lowercase(Locale.US).contains(q) ||
                    ch.language.lowercase(Locale.US).contains(q) ||
                    ch.group.lowercase(Locale.US).contains(q)
                groupMatch && queryMatch
            }
            .sortedByDescending { favoriteUrls.contains(it.url) }

        channelAdapter?.submitList(filtered)
        tvChannelCount?.text = "Showing ${filtered.size} of ${allChannels.size} • Long-press = Pin"
    }

    private fun openChannelInPlayer(channel: IptvChannel) {
        saveLastWatched(url = channel.url, title = channel.name)
        updateLastWatchedPill()

        when (channel.sourceType) {
            MediaSourceType.YOUTUBE -> openYouTubeBrowserScreen(
                initialUrl = channel.url,
                title = channel.name
            )
            MediaSourceType.LOCAL -> launchPlayer(
                url = channel.url,
                title = channel.name,
                userAgent = channel.userAgent,
                referrer = channel.referrer,
                castMode = CAST_MODE_LOCAL,
                localMediaUri = channel.url
            )
            MediaSourceType.STREAM -> launchPlayer(
                url = channel.url,
                title = channel.name,
                userAgent = channel.userAgent,
                referrer = channel.referrer,
                castMode = CAST_MODE_STREAM
            )
        }
    }

    private fun launchPlayer(
        url: String,
        title: String,
        userAgent: String = "",
        referrer: String = "",
        castMode: String = CAST_MODE_STREAM,
        youtubeUrl: String = "",
        youtubeVideoId: String = "",
        localMediaUri: String = "",
        localMediaMime: String = ""
    ) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("MEDIA_URL", url)
            putExtra("MEDIA_TITLE", title)
            putExtra(PlayerActivity.EXTRA_MEDIA_URL, url)
            putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, title)
            putExtra(EXTRA_CAST_MODE, castMode)

            if (userAgent.isNotBlank()) {
                putExtra("MEDIA_USER_AGENT", userAgent)
                putExtra(PlayerActivity.EXTRA_MEDIA_USER_AGENT, userAgent)
            }
            if (referrer.isNotBlank()) {
                putExtra("MEDIA_REFERRER", referrer)
                putExtra(PlayerActivity.EXTRA_MEDIA_REFERRER, referrer)
            }
            if (youtubeUrl.isNotBlank()) {
                putExtra(EXTRA_YOUTUBE_URL, youtubeUrl)
            }
            if (youtubeVideoId.isNotBlank()) {
                putExtra(EXTRA_YOUTUBE_VIDEO_ID, youtubeVideoId)
            }
            if (localMediaUri.isNotBlank()) {
                putExtra(EXTRA_LOCAL_MEDIA_URI, localMediaUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (localMediaMime.isNotBlank()) {
                putExtra(EXTRA_LOCAL_MEDIA_MIME, localMediaMime)
            }

            val savedDevice = DlnaDeviceStore.getLastDevice(this@MainActivity)
            if (savedDevice != null) {
                putExtra("DLNA_DEVICE_NAME", savedDevice.friendlyName)
                putExtra("DLNA_CONTROL_URL", savedDevice.controlUrl)
                putExtra("DLNA_DIAL_APP_URL", savedDevice.dialAppUrl)
                putExtra("DLNA_HOST_IP", savedDevice.hostIp)
            }
        }
        startActivity(intent)
    }

    private fun savePlaylistUrl(url: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAYLIST_URL, url)
            .apply()
    }

    private fun getSavedPlaylistUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_PLAYLIST_URL, null)
            ?.trim()
            .orEmpty()
    }

    private fun createLogoDrawable(): Drawable {
        val size = dp(48).coerceAtLeast(36)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val f = size.toFloat()
        val pad = f * 0.06f
        val cardRect = RectF(pad, pad, f - pad, f - pad)
        val corner = f * 0.25f

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, cardRect.top, 0f, cardRect.bottom,
                intArrayOf(
                    Color.parseColor("#132036"),
                    Color.parseColor("#070C16")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardRect, corner, corner, basePaint)

        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, cardRect.top, 0f, cardRect.centerY(),
                intArrayOf(
                    Color.parseColor("#2438BDF8"),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        val sheenRect = RectF(cardRect.left, cardRect.top, cardRect.right, cardRect.centerY() + f * 0.04f)
        canvas.drawRoundRect(sheenRect, corner, corner, sheenPaint)

        val outerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.024f).coerceAtLeast(1.1f)
            color = Color.parseColor("#284B73")
        }
        canvas.drawRoundRect(cardRect, corner, corner, outerBorder)

        val tvLeft = f * 0.21f
        val tvTop = f * 0.25f
        val tvRight = f * 0.79f
        val tvBottom = f * 0.67f
        val tvRect = RectF(tvLeft, tvTop, tvRight, tvBottom)
        val tvRadius = f * 0.075f

        val tvFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#09111E")
        }
        val tvStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.042f).coerceAtLeast(1.6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor(ACCENT_CYAN)
        }
        canvas.drawRoundRect(tvRect, tvRadius, tvRadius, tvFill)
        canvas.drawRoundRect(tvRect, tvRadius, tvRadius, tvStroke)

        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.032f).coerceAtLeast(1.25f)
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(ACCENT_SKY)
        }
        canvas.drawArc(
            RectF(f * 0.27f, f * 0.40f, f * 0.37f, f * 0.50f),
            -70f, 70f, false, wavePaint
        )
        canvas.drawArc(
            RectF(f * 0.27f, f * 0.33f, f * 0.44f, f * 0.50f),
            -70f, 70f, false, wavePaint
        )

        val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(ACCENT_CYAN)
        }
        val playPath = Path().apply {
            moveTo(f * 0.46f, f * 0.37f)
            lineTo(f * 0.66f, f * 0.46f)
            lineTo(f * 0.46f, f * 0.55f)
            close()
        }
        canvas.drawPath(playPath, playPaint)

        val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor(ACCENT_GOLD)
        }
        canvas.drawCircle(
            f * 0.70f,
            f * 0.33f,
            (f * 0.036f).coerceAtLeast(1.4f),
            liveDotPaint
        )

        val standPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (f * 0.040f).coerceAtLeast(1.5f)
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(ACCENT_CYAN)
        }
        canvas.drawLine(f * 0.50f, tvBottom, f * 0.50f, f * 0.76f, standPaint)
        canvas.drawLine(f * 0.38f, f * 0.76f, f * 0.62f, f * 0.76f, standPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createFocusPillDrawable(
        normalFill: String,
        normalStroke: String,
        focusStroke: String,
        radiusDp: Int
    ): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(normalFill))
            setStroke(dp(1), Color.parseColor(normalStroke))
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

    private class ChannelCardAdapter(
        private val lifecycleScope: LifecycleCoroutineScope,
        private val fallbackDrawable: Drawable,
        private val isFavorite: (IptvChannel) -> Boolean,
        private val onClick: (IptvChannel) -> Unit,
        private val onLongClick: (IptvChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelCardAdapter.ChannelViewHolder>() {

        private var items: List<IptvChannel> = emptyList()

        private val logoCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        fun submitList(newItems: List<IptvChannel>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun d(v: Int): Int = (v * density).toInt()

            val normalBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = d(14).toFloat()
                setColor(Color.parseColor(BG_CARD))
                setStroke(d(1), Color.parseColor(BORDER_CARD))
            }
            val focusBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = d(14).toFloat()
                setColor(Color.parseColor(BG_BADGE))
                setStroke(d(2), Color.parseColor(ACCENT_CYAN))
            }
            val selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusBg)
                addState(intArrayOf(), normalBg)
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = selector
                setPadding(d(12), d(12), d(12), d(12))
                val lp = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = d(8)
                layoutParams = lp
            }

            val logoBox = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = d(10).toFloat()
                    setColor(Color.parseColor(BG_BADGE))
                    setStroke(d(1), Color.parseColor("#1E4966"))
                }
                setPadding(d(4), d(4), d(4), d(4))
                layoutParams = LinearLayout.LayoutParams(d(48), d(40))
            }

            val logoIv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            logoBox.addView(logoIv)

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = d(12)
                lp.marginEnd = d(10)
                layoutParams = lp
            }

            val titleTv = TextView(ctx).apply {
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                textSize = 14.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val groupTv = TextView(ctx).apply {
                setTextColor(Color.parseColor(TEXT_SECONDARY))
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = d(2)
                layoutParams = lp
            }

            val actionTv = TextView(ctx).apply {
                text = "PLAY ▶"
                setTextColor(Color.parseColor(BG_PRIMARY))
                textSize = 11f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = d(999).toFloat()
                    setColor(Color.parseColor(ACCENT_CYAN))
                }
                setPadding(d(10), d(6), d(10), d(6))
            }

            infoCol.addView(titleTv)
            infoCol.addView(groupTv)
            card.addView(logoBox)
            card.addView(infoCol)
            card.addView(actionTv)

            return ChannelViewHolder(card, logoIv, titleTv, groupTv)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, isFavorite(item), lifecycleScope, fallbackDrawable, logoCache, onClick, onLongClick)
        }

        override fun onViewRecycled(holder: ChannelViewHolder) {
            holder.cancelLogoLoad()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = items.size

        class ChannelViewHolder(
            itemView: View,
            private val logoIv: ImageView,
            private val titleTv: TextView,
            private val groupTv: TextView
        ) : RecyclerView.ViewHolder(itemView) {

            private var loadJob: Job? = null

            fun cancelLogoLoad() {
                loadJob?.cancel()
                loadJob = null
            }

            fun bind(
                channel: IptvChannel,
                fav: Boolean,
                scope: LifecycleCoroutineScope,
                fallbackDrawable: Drawable,
                logoCache: LruCache<String, Bitmap>,
                onClick: (IptvChannel) -> Unit,
                onLongClick: (IptvChannel) -> Unit
            ) {
                cancelLogoLoad()
                titleTv.text = if (fav) "★ ${channel.name}" else channel.name
                val sourceTag = when (channel.sourceType) {
                    MediaSourceType.YOUTUBE -> "YouTube"
                    MediaSourceType.LOCAL -> "Local"
                    MediaSourceType.STREAM -> null
                }
                val metaParts = listOfNotNull(
                    sourceTag,
                    channel.language.trim().takeIf { it.isNotEmpty() },
                    channel.group.trim().takeIf { it.isNotEmpty() && !it.equals("All", true) }
                ).distinct()
                groupTv.text = if (metaParts.isNotEmpty()) metaParts.joinToString(" • ") else "All"

                itemView.setOnClickListener { onClick(channel) }
                itemView.setOnLongClickListener {
                    onLongClick(channel)
                    true
                }

                val logoUrl = channel.logoUrl.trim()
                logoIv.tag = logoUrl

                if (logoUrl.isEmpty() ||
                    !(logoUrl.startsWith("http://", ignoreCase = true) ||
                        logoUrl.startsWith("https://", ignoreCase = true))
                ) {
                    logoIv.setImageDrawable(fallbackDrawable)
                    return
                }

                val cached = logoCache.get(logoUrl)
                if (cached != null && !cached.isRecycled) {
                    logoIv.setImageBitmap(cached)
                    return
                }

                logoIv.setImageDrawable(fallbackDrawable)
                loadJob = scope.launch(Dispatchers.IO) {
                    val bmp = downloadScaledLogo(logoUrl, 96, 96)
                    if (bmp != null) {
                        logoCache.put(logoUrl, bmp)
                        withContext(Dispatchers.Main) {
                            if (logoIv.tag == logoUrl && !bmp.isRecycled) {
                                logoIv.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }

            private fun downloadScaledLogo(urlStr: String, reqW: Int, reqH: Int): Bitmap? {
                var conn: HttpURLConnection? = null
                return try {
                    conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000
                        readTimeout = 6_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "PlusCast/1.0 (IPTV)")
                    }
                    if (conn.responseCode !in 200..299) return null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.isEmpty()) return null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                    var sample = 1
                    while (bounds.outHeight / sample > reqH * 2 || bounds.outWidth / sample > reqW * 2) {
                        sample *= 2
                    }

                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample.coerceAtLeast(1)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } catch (_: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }
}
