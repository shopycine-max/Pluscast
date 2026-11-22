package com.pluscast.tvcast

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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        // Daily auto-refresh interval (24 hours)
        private const val DAILY_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

        // India IPTV M3U playlist link (UI mein kahin nahi dikhega)
        private const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"

        // Allowed filter chips
        private val FILTER_TABS = listOf("All", "Favorites", "Hindi", "Bhojpuri", "Bengali News")

        // Dark Broadcast / TV Palette
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
        private const val TEXT_PRIMARY = "#F8FAFC"
        private const val TEXT_SECONDARY = "#94A3B8"
    }

    private val dlnaController by lazy { DlnaController(this) }

    private var tvDlnaStatus: TextView? = null
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
    private var channelAdapter: ChannelCardAdapter? = null

    private var isLoadingPlaylist: Boolean = false
    private var dailyTickerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor(BG_PRIMARY)
        window.navigationBarColor = Color.parseColor(BG_PRIMARY)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        favoriteUrls = loadFavorites()

        val rootView = buildProfessionalUi()
        setContentView(rootView)
        applySystemBarInsets(rootView)

        updateDlnaStatusBadge()
        updateLastWatchedPill()
        rebuildGroupChips()
        setupListeners()

        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isNotEmpty()) {
            loadPlaylist(activeUrl, force = true, showToast = true)
            startDailyAutoRefreshLoop()
        } else {
            tvChannelCount?.text = "Playlist link configure karein"
        }
    }

    override fun onResume() {
        super.onResume()
        updateDlnaStatusBadge()
        updateLastWatchedPill()

        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isNotEmpty() && !isLoadingPlaylist) {
            val dueDaily = isDailyRefreshDue()
            if (allChannels.isEmpty() || dueDaily) {
                loadPlaylist(activeUrl, force = allChannels.isEmpty(), showToast = false)
            }
        }
    }

    override fun onDestroy() {
        dailyTickerJob?.cancel()
        dailyTickerJob = null
        super.onDestroy()
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
        return (System.currentTimeMillis() - lastMs) >= DAILY_REFRESH_INTERVAL_MS
    }

    private fun markPlaylistRefreshedNow() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REFRESH_MS, System.currentTimeMillis())
            .apply()
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

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val logoView = ImageView(this).apply {
            setImageDrawable(createLogoDrawable())
            contentDescription = "PlusCast IPTV Logo"
            val lp = LinearLayout.LayoutParams(dp(44), dp(44))
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

        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
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
        badgesRow.addView(lastWatchedBadge)

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
        brandBlock.addView(badgesRow)
        headerRow.addView(logoView)
        headerRow.addView(brandBlock)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        val searchBox = EditText(this).apply {
            hint = "Hindi, Bhojpuri ya Bengali News khojein..."
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

    private fun updateDlnaStatusBadge() {
        val saved = DlnaDeviceStore.getLastDevice(this)
        if (saved != null) {
            tvDlnaStatus?.text = "TV: ${saved.friendlyName}"
            tvDlnaStatus?.setTextColor(Color.parseColor(ACCENT_GOLD))
        } else {
            tvDlnaStatus?.text = "DLNA: Device Chunein"
            tvDlnaStatus?.setTextColor(Color.parseColor(ACCENT_CYAN))
        }
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
                    updateDlnaStatusBadge()
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
            val fetched = M3uPlaylistParser.fetchChannels(url, applicationContext)
            if (isFinishing || isDestroyed) return@launch

            isLoadingPlaylist = false
            progressBar?.visibility = View.GONE
            btnRefresh?.isEnabled = true

            if (fetched.isNotEmpty()) {
                markPlaylistRefreshedNow()
                allChannels = fetched
                if (!FILTER_TABS.any { it.equals(selectedGroup, ignoreCase = true) }) {
                    selectedGroup = "All"
                }
                rebuildGroupChips()
                applyFilters()
                if (showToast) {
                    Toast.makeText(
                        this@MainActivity,
                        "${fetched.size} channels ready",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (allChannels.isEmpty()) {
                tvChannelCount?.text = "Filtered channels nahi mile"
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

    private fun rebuildGroupChips() {
        val container = groupContainer ?: return
        container.removeAllViews()

        for (groupName in FILTER_TABS) {
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
                    rebuildGroupChips()
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
                val category = M3uPlaylistParser.resolveCategory(ch) ?: return@filter false
                val groupMatch = when {
                    selectedGroup.equals("All", ignoreCase = true) -> true
                    selectedGroup.equals("Favorites", ignoreCase = true) -> favoriteUrls.contains(ch.url)
                    else -> category.equals(selectedGroup, ignoreCase = true)
                }
                val queryMatch = q.isEmpty() ||
                    ch.name.lowercase(Locale.US).contains(q) ||
                    category.lowercase(Locale.US).contains(q) ||
                    ch.group.lowercase(Locale.US).contains(q)
                groupMatch && queryMatch
            }
            .sortedByDescending { favoriteUrls.contains(it.url) }

        channelAdapter?.submitList(filtered)
        tvChannelCount?.text = "Showing ${filtered.size} of ${allChannels.size} • Long-press = Pin"
    }

    private fun openChannelInPlayer(channel: IptvChannel) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_URL, channel.url)
            .putString(KEY_LAST_TITLE, channel.name)
            .apply()
        updateLastWatchedPill()
        launchPlayer(channel.url, channel.name)
    }

    private fun launchPlayer(url: String, title: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("MEDIA_URL", url)
            putExtra("MEDIA_TITLE", title)
            putExtra(PlayerActivity.EXTRA_MEDIA_URL, url)
            putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, title)

            val savedDevice = DlnaDeviceStore.getLastDevice(this@MainActivity)
            if (savedDevice != null) {
                putExtra("DLNA_DEVICE_NAME", savedDevice.friendlyName)
                putExtra("DLNA_CONTROL_URL", savedDevice.controlUrl)
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
        val size = dp(44).coerceAtLeast(28)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fSize = size.toFloat()
        val inset = fSize * 0.06f
        val rect = RectF(inset, inset, fSize - inset, fSize - inset)
        val corner = fSize * 0.28f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, fSize, fSize,
                intArrayOf(
                    Color.parseColor("#13253E"),
                    Color.parseColor("#0B1322")
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (fSize * 0.048f).coerceAtLeast(2f)
            shader = LinearGradient(
                0f, 0f, fSize, fSize,
                intArrayOf(
                    Color.parseColor(ACCENT_CYAN),
                    Color.parseColor(ACCENT_SKY)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawRoundRect(rect, corner, corner, bgPaint)
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_CYAN)
            style = Paint.Style.FILL
        }
        val cx = fSize * 0.46f
        val cy = fSize * 0.54f
        val r = fSize * 0.20f
        val playPath = Path().apply {
            moveTo(cx - r * 0.65f, cy - r)
            lineTo(cx + r * 1.15f, cy)
            lineTo(cx - r * 0.65f, cy + r)
            close()
        }
        canvas.drawPath(playPath, playPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_GOLD)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(fSize * 0.74f, fSize * 0.28f, (fSize * 0.065f).coerceAtLeast(2f), dotPaint)

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
                val categoryLabel = M3uPlaylistParser.resolveCategory(channel) ?: channel.group
                titleTv.text = if (fav) "★ ${channel.name}" else channel.name
                groupTv.text = if (channel.group.isNotBlank() && !channel.group.equals("All", true)) {
                    "$categoryLabel • ${channel.group}"
                } else {
                    categoryLabel
                }
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
