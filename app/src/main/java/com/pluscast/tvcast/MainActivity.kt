package com.pluscast.tvcast

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.pluscast.tvcast.databinding.ActivityMainBinding
import com.pluscast.tvcast.iptv.IptvChannel
import com.pluscast.tvcast.iptv.M3uParser
import com.pluscast.tvcast.server.LocalMediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var castContext: CastContext? = null
    private lateinit var localServer: LocalMediaServer
    private var channelList: List<IptvChannel> = emptyList()

    private val pickLocalMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { prepareAndPlayLocalMedia(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        try {
            castContext = CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Cast service available nahi hai, baaki features chalenge", Toast.LENGTH_SHORT).show()
        }

        try {
            localServer = LocalMediaServer(this, 8080)
            localServer.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        binding.cardLocalMedia.setOnClickListener {
            pickLocalMedia.launch("video/*")
        }

        binding.btnPlayStream.setOnClickListener {
            val url = binding.etStreamUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                openPlayer(url, "Live Stream")
            } else {
                Toast.makeText(this, "Stream URL dalein", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLoadIptv.setOnClickListener {
            val m3uUrl = binding.etM3uUrl.text.toString().trim()
            if (m3uUrl.isNotEmpty()) {
                loadM3uPlaylist(m3uUrl)
            } else {
                Toast.makeText(this, "M3U Playlist URL dalein", Toast.LENGTH_SHORT).show()
            }
        }

        binding.lvIptvChannels.setOnItemClickListener { _, _, position, _ ->
            val channel = channelList[position]
            openPlayer(channel.url, channel.name)
        }

        binding.cardYoutube.setOnClickListener {
            startActivity(Intent(this, YouTubeActivity::class.java))
        }
    }

    private fun prepareAndPlayLocalMedia(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tempFile = File(cacheDir, "cast_temp_media.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                if (::localServer.isInitialized) {
                    localServer.setFileToServe(tempFile)
                    val streamUrl = localServer.getStreamUrl()
                    withContext(Dispatchers.Main) {
                        openPlayer(streamUrl, "Local Video")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "File load error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadM3uPlaylist(url: String) {
        Toast.makeText(this, "Channels load ho rahe hain...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val content = URL(url).readText()
                channelList = M3uParser.parse(content)
                withContext(Dispatchers.Main) {
                    val names = channelList.map { it.name }
                    binding.lvIptvChannels.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        names
                    )
                    Toast.makeText(this@MainActivity, "${channelList.size} channels loaded!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Playlist error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPlayer(mediaUrl: String, title: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("MEDIA_URL", mediaUrl)
            putExtra("MEDIA_TITLE", title)
        }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cast_menu, menu)
        try {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::localServer.isInitialized) {
                localServer.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
