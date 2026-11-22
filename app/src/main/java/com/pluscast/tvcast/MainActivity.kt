package com.pluscast.tvcast

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "pluscast_iptv_prefs"
        private const val KEY_PLAYLIST_URL = "last_playlist_url"

        // Minimum gap between automatic resume refreshes (5 minutes)
        private const val AUTO_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L

        // India IPTV M3U playlist link (UI mein kahin nahi dikhega)
        private const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"

        // Refined Dark Broadcast / TV Palette
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
    private var tvChannelCount: TextView? = null
    private var etSearch: EditText? = null
    private var btnRefresh: TextView? = null
    private var progressBar: ProgressBar? = null
    private var groupContainer: LinearLayout? = null
    private var recyclerView: RecyclerView? = null

    private var allChannels: List<IptvChannel> = emptyList()
    private var selectedGroup: String = "All"
    private var channelAdapter: ChannelCardAdapter? = null

    private var isLoadingPlaylist: Boolean = false
    private var lastPlaylistFetchAtMs: Long = 0L
    private var hasCompletedInitialLoad: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.parseColor(BG_PRIMARY)
        window.navigationBarColor = Color.parseColor(BG_PRIMARY)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val rootView = buildProfessionalUi()
        setContentView(rootView)
        applySystemBarInsets(rootView)

        updateDlnaStatusBadge()
        setupListeners()

        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isNotEmpty()) {
            loadPlaylist(activeUrl, showToast = true)
        } else {
            tvChannelCount?.text = "Playlist link configure karein"
        }
    }

    override fun onResume() {
        super.onResume()
        updateDlnaStatusBadge()

        if (!hasCompletedInitialLoad) return
        val activeUrl = resolveHiddenPlaylistUrl()
        if (activeUrl.isEmpty() || isLoadingPlaylist) return

        val elapsed = System.currentTimeMillis() - lastPlaylistFetchAtMs
        if (allChannels.isEmpty() || elapsed >= AUTO_REFRESH_COOLDOWN_MS) {
            loadPlaylist(activeUrl, showToast = false)
        }
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

        val dlnaBadge = TextView(this).apply {
            text = "DLNA: Scan"
            setTextColor(Color.parseColor(ACCENT_CYAN))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = createPillDrawable(
                fillHex = BG_BADGE,
                strokeHex = "#1E4966",
                radiusDp = 999
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        tvDlnaStatus = dlnaBadge

        val refreshBtn = TextView(this).apply {
            text = "↻ Refresh"
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = createPillDrawable(
                fillHex = BG_SURFACE,
                strokeHex = BORDER_SUBTLE,
                radiusDp = 12
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        btnRefresh = refreshBtn

        brandBlock.addView(tvTitle)
        brandBlock.addView(dlnaBadge)
        headerRow.addView(logoView)
        headerRow.addView(brandBlock)
        headerRow.addView(refreshBtn)
        root.addView(headerRow)

        val searchBox = EditText(this).apply {
            hint = "Channel ya group khojein..."
            setHintTextColor(Color.parseColor(TEXT_SECONDARY))
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            background = createPillDrawable(
                fillHex = BG_SURFACE,
                strokeHex = BORDER_SUBTLE,
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
            text = "Channels: 0"
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvChannelCount = countView

        val spinner = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(ACCENT_CYAN))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        progressBar = spinner

        metaRow.addView(countView)
        metaRow.addView(spinner)
        root.addView(metaRow)

        val fallbackLogo = createLogoDrawable()
        val adapter = ChannelCardAdapter(
            fallbackDrawable = fallbackLogo,
            onClick = { channel -> openChannelInPlayer(channel) }
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
                loadPlaylist(url, showToast = true)
            } else {
                Toast.makeText(this, "DEFAULT_PLAYLIST_URL set nahi hai", Toast.LENGTH_SHORT).show()
            }
        }

        tvDlnaStatus?.setOnClickListener {
            discoverAndPickDlnaDevice()
        }

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
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

    private fun loadPlaylist(url: String, showToast: Boolean) {
        if (isLoadingPlaylist) return
        isLoadingPlaylist = true
        lastPlaylistFetchAtMs = System.currentTimeMillis()

        progressBar?.visibility = View.VISIBLE
        btnRefresh?.isEnabled = false
        if (allChannels.isEmpty()) {
            tvChannelCount?.text = "Channels load ho rahe hain..."
        }

        lifecycleScope.launch {
            val fetched = M3uPlaylistParser.fetchChannels(url)
            if (isFinishing || isDestroyed) return@launch

            isLoadingPlaylist = false
            hasCompletedInitialLoad = true
            progressBar?.visibility = View.GONE
            btnRefresh?.isEnabled = true

            if (fetched.isNotEmpty()) {
                allChannels = fetched
                selectedGroup = "All"
                rebuildGroupChips(fetched)
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
                    "Nayi list nahi mili, purani list dikha rahe hain",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun rebuildGroupChips(channels: List<IptvChannel>) {
        val container = groupContainer ?: return
        container.removeAllViews()

        val groups = buildList {
            add("All")
            channels.map { it.group.trim() }
                .filter { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
                .distinct()
                .take(20)
                .forEach { add(it) }
        }

        for (groupName in groups) {
            val isSelected = selectedGroup.equals(groupName, ignoreCase = true)
            val chip = TextView(this).apply {
                text = groupName
                textSize = 12f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextColor(
                    if (isSelected) Color.parseColor(BG_PRIMARY)
                    else Color.parseColor(TEXT_PRIMARY)
                )
                background = createPillDrawable(
                    fillHex = if (isSelected) ACCENT_CYAN else BG_SURFACE,
                    strokeHex = if (isSelected) ACCENT_CYAN else BORDER_SUBTLE,
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

        val filtered = allChannels.filter { ch ->
            val groupMatch = selectedGroup.equals("All", ignoreCase = true) ||
                ch.group.equals(selectedGroup, ignoreCase = true)
            val queryMatch = q.isEmpty() ||
                ch.name.lowercase(Locale.US).contains(q) ||
                ch.group.lowercase(Locale.US).contains(q)
            groupMatch && queryMatch
        }

        channelAdapter?.submitList(filtered)
        tvChannelCount?.text = "Showing ${filtered.size} of ${allChannels.size} channels"
    }

    private fun openChannelInPlayer(channel: IptvChannel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("MEDIA_URL", channel.url)
            putExtra("MEDIA_TITLE", channel.name)
            putExtra(PlayerActivity.EXTRA_MEDIA_URL, channel.url)
            putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, channel.name)

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

        // Broadcast play mark
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

        // Subtle gold live dot at top-right
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ACCENT_GOLD)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(fSize * 0.74f, fSize * 0.28f, (fSize * 0.065f).coerceAtLeast(2f), dotPaint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createPillDrawable(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private class ChannelCardAdapter(
        private val fallbackDrawable: Drawable,
        private val onClick: (IptvChannel) -> Unit
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

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = d(14).toFloat()
                    setColor(Color.parseColor(BG_CARD))
                    setStroke(d(1), Color.parseColor(BORDER_CARD))
                }
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
            holder.bind(item, fallbackDrawable, logoCache, onClick)
        }

        override fun getItemCount(): Int = items.size

        class ChannelViewHolder(
            itemView: View,
            private val logoIv: ImageView,
            private val titleTv: TextView,
            private val groupTv: TextView
        ) : RecyclerView.ViewHolder(itemView) {

            private var loadJob: Job? = null

            fun bind(
                channel: IptvChannel,
                fallbackDrawable: Drawable,
                logoCache: LruCache<String, Bitmap>,
                onClick: (IptvChannel) -> Unit
            ) {
                loadJob?.cancel()
                titleTv.text = channel.name
                groupTv.text = channel.group
                itemView.setOnClickListener { onClick(channel) }

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
                loadJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
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
