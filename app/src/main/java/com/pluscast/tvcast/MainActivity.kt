package com.pluscast.tvcast

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.pluscast.tvcast.iptv.IptvChannel
import com.pluscast.tvcast.iptv.M3uParser
import com.pluscast.tvcast.server.LocalMediaServer
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PICK_LOCAL_MEDIA = 1001
        private const val DEFAULT_INDIA_M3U_URL = "https://iptv-org.github.io/iptv/countries/in.m3u"
    }

    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val logoCache = ConcurrentHashMap<String, Bitmap>()

    private var localServer: LocalMediaServer? = null
    private var channels: List<IptvChannel> = emptyList()
    private var visibleChannels: List<IptvChannel> = emptyList()
    private var channelAdapter: ChannelListAdapter? = null

    private var etStreamUrl: EditText? = null
    private var etM3uUrl: EditText? = null
    private var etSearchChannel: EditText? = null
    private var lvIptvChannels: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Main layout load nahi ho paya", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        initLocalServer()
        initViewsAndListeners()
        autoLoadIndiaIptv()
    }

    private fun setupToolbar() {
        try {
            val toolbar = findViewById<Toolbar?>(R.id.toolbar)
            if (toolbar != null) {
                setSupportActionBar(toolbar)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initLocalServer() {
        try {
            val server = LocalMediaServer(this, 8080)
            server.start()
            localServer = server
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initViewsAndListeners() {
        etStreamUrl = findViewById(R.id.etStreamUrl)
        etM3uUrl = findViewById(R.id.etM3uUrl)
        etSearchChannel = findViewById(R.id.etSearchChannel)
        lvIptvChannels = findViewById(R.id.lvIptvChannels)

        channelAdapter = ChannelListAdapter()
        lvIptvChannels?.adapter = channelAdapter

        etSearchChannel?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyChannelFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 1. Local Media Picker
        findViewById<Button?>(R.id.cardLocalMedia)?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_PICK_LOCAL_MEDIA)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "File picker nahi khul paya", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Direct Stream Play
        findViewById<Button?>(R.id.btnPlayStream)?.setOnClickListener {
            val url = etStreamUrl?.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                openPlayer(url, "Direct Stream")
            } else {
                Toast.makeText(this, "Stream URL dalein", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. IPTV M3U Load / Manual Refresh
        findViewById<Button?>(R.id.btnLoadIptv)?.setOnClickListener {
            val m3uUrl = etM3uUrl?.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_INDIA_M3U_URL }
            etM3uUrl?.setText(m3uUrl)
            loadM3uPlaylist(m3uUrl)
        }

        // 4. Channel Tap -> Open Player
        lvIptvChannels?.setOnItemClickListener { _, _, position, _ ->
            val selected = visibleChannels.getOrNull(position) ?: return@setOnItemClickListener
            openPlayer(selected.url, selected.name)
        }

        // 5. YouTube Player
        findViewById<Button?>(R.id.cardYoutube)?.setOnClickListener {
            try {
                startActivity(Intent(this, YouTubeActivity::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "YouTube screen nahi khul payi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoLoadIndiaIptv() {
        etM3uUrl?.setText(DEFAULT_INDIA_M3U_URL)
        loadM3uPlaylist(DEFAULT_INDIA_M3U_URL)
    }

    private fun applyChannelFilter(query: String) {
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) {
            channels
        } else {
            channels.filter { channel ->
                val cat = channel.group?.takeIf { it.isNotBlank() } ?: "General"
                channel.name.lowercase().contains(q) || cat.lowercase().contains(q)
            }
        }

        // Category-wise grouping: pehle category ke naam se group karke sort karein
        val grouped = matched.groupBy { channel ->
            channel.group?.trim()?.takeIf { it.isNotEmpty() } ?: "General"
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)

        val orderedList = mutableListOf<IptvChannel>()
        for ((_, listInCategory) in grouped) {
            orderedList.addAll(listInCategory.sortedBy { it.name.lowercase() })
        }

        visibleChannels = orderedList
        channelAdapter?.notifyDataSetChanged()
    }

    private fun loadM3uPlaylist(m3uUrl: String) {
        Toast.makeText(this, "IPTV playlist load ho rahi hai...", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            try {
                val connection = (URL(m3uUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PlusCast/1.0")
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    runOnUiThread {
                        Toast.makeText(this, "Playlist load fail ($responseCode)", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val content = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
                connection.disconnect()

                val parsedChannels = M3uParser.parse(content)

                runOnUiThread {
                    channels = parsedChannels
                    applyChannelFilter(etSearchChannel?.text?.toString().orEmpty())
                    Toast.makeText(
                        this,
                        "${parsedChannels.size} channels load hue",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "IPTV error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadChannelLogo(logoUrl: String?, imageView: ImageView) {
        val defaultIcon = android.R.drawable.ic_menu_gallery
        imageView.setImageResource(defaultIcon)

        val cleanUrl = logoUrl?.trim().orEmpty()
        if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
            return
        }

        logoCache[cleanUrl]?.let { cachedBitmap ->
            imageView.setImageBitmap(cachedBitmap)
            return
        }

        imageView.tag = cleanUrl
        ioExecutor.execute {
            try {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PlusCast/1.0")
                }
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            logoCache[cleanUrl] = bitmap
                            runOnUiThread {
                                if (imageView.tag == cleanUrl) {
                                    imageView.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private inner class ChannelListAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleChannels.size

        override fun getItem(position: Int): IptvChannel = visibleChannels[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowView = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_iptv_channel, parent, false)

            val ivLogo = rowView.findViewById<ImageView>(R.id.ivChannelLogo)
            val tvName = rowView.findViewById<TextView>(R.id.tvChannelName)
            val tvCategory = rowView.findViewById<TextView>(R.id.tvChannelCategory)

            val channel = getItem(position)
            val categoryName = channel.group?.trim()?.takeIf { it.isNotEmpty() } ?: "General"

            tvName.text = channel.name
            tvCategory.text = "Category: $categoryName"
            loadChannelLogo(channel.logo, ivLogo)

            return rowView
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PICK_LOCAL_MEDIA && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            copyUriToCacheAndServe(uri)
        }
    }

    private fun copyUriToCacheAndServe(uri: Uri) {
        Toast.makeText(this, "Local media taiyar ho raha hai...", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            try {
                val outFile = File(cacheDir, "cast_local_media")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val server = localServer
                if (server != null && outFile.exists() && outFile.length() > 0L) {
                    server.setFileToServe(outFile)
                    val streamUrl = server.getStreamUrl()
                    runOnUiThread {
                        openPlayer(streamUrl, "Local Media")
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "File read nahi ho payi", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Local media error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPlayer(url: String, title: String) {
        try {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("MEDIA_URL", url)
                putExtra("MEDIA_TITLE", title)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Player nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return try {
            menuInflater.inflate(R.menu.cast_menu, menu)
            val menuItem = menu.findItem(R.id.media_route_menu_item)
            if (menuItem != null) {
                CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            CastContext.getSharedInstance(this)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            localServer?.stop()
            localServer = null
            ioExecutor.shutdownNow()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
