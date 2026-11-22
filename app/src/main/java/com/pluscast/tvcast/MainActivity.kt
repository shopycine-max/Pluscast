package com.pluscast.tvcast

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "pluscast_iptv_prefs"
        private const val KEY_PLAYLIST_URL = "last_playlist_url"
    }

    private var etPlaylistUrl: EditText? = null
    private var etSearchChannel: EditText? = null
    private var btnLoadPlaylist: Button? = null
    private var progressBar: ProgressBar? = null
    private var tvChannelCount: TextView? = null
    private var recyclerView: RecyclerView? = null
    private var listView: ListView? = null

    private var allChannels: List<IptvChannel> = emptyList()
    private var channelAdapter: ChannelAdapter? = null
    private var listViewAdapter: android.widget.ArrayAdapter<String>? = null
    private var displayedListViewChannels: List<IptvChannel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupChannelList()
        setupListeners()

        val savedUrl = getSavedPlaylistUrl()
        if (savedUrl.isNotEmpty()) {
            etPlaylistUrl?.setText(savedUrl)
            loadPlaylist(savedUrl)
        }
    }

    private fun bindViews() {
        etPlaylistUrl = findViewByIdOrNull("etPlaylistUrl") ?: findViewByIdOrNull("etM3uUrl")
        etSearchChannel = findViewByIdOrNull("etSearchChannel") ?: findViewByIdOrNull("etSearch")
        btnLoadPlaylist = findViewByIdOrNull("btnLoadPlaylist") ?: findViewByIdOrNull("btnLoad")
        progressBar = findViewByIdOrNull("progressBar")
        tvChannelCount = findViewByIdOrNull("tvChannelCount") ?: findViewByIdOrNull("tvStatus")
        recyclerView = findViewByIdOrNull("rvChannels") ?: findViewByIdOrNull("recyclerViewChannels")
        listView = findViewByIdOrNull("lvChannels") ?: findViewByIdOrNull("listViewChannels")

        // Hide legacy YouTube / Local buttons if still present in XML
        listOf(
            "btnYoutube", "btnOpenYoutube", "btnYouTube",
            "btnLocalMedia", "btnLocalVideo", "btnPickLocal"
        ).forEach { idName ->
            findViewByIdOrNull<View>(idName)?.visibility = View.GONE
        }
    }

    private fun <T : View> findViewByIdOrNull(idName: String): T? {
        val id = resources.getIdentifier(idName, "id", packageName)
        return if (id != 0) findViewById(id) else null
    }

    private fun setupChannelList() {
        val adapter = ChannelAdapter { channel ->
            openChannelInPlayer(channel)
        }
        channelAdapter = adapter

        recyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            this.adapter = adapter
        }

        if (recyclerView == null && listView != null) {
            val lvAdapter = android.widget.ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                mutableListOf()
            )
            listViewAdapter = lvAdapter
            listView?.adapter = lvAdapter
            listView?.setOnItemClickListener { _, _, position, _ ->
                displayedListViewChannels.getOrNull(position)?.let { openChannelInPlayer(it) }
            }
        }
    }

    private fun setupListeners() {
        btnLoadPlaylist?.setOnClickListener {
            val url = etPlaylistUrl?.text?.toString()?.trim().orEmpty()
            if (url.isEmpty()) {
                Toast.makeText(this, "IPTV M3U playlist link dalein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savePlaylistUrl(url)
            loadPlaylist(url)
        }

        etSearchChannel?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterChannels(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadPlaylist(url: String) {
        progressBar?.visibility = View.VISIBLE
        btnLoadPlaylist?.isEnabled = false
        tvChannelCount?.text = "Channels load ho rahe hain..."

        lifecycleScope.launch {
            val fetched = M3uPlaylistParser.fetchChannels(url)
            if (isFinishing || isDestroyed) return@launch

            progressBar?.visibility = View.GONE
            btnLoadPlaylist?.isEnabled = true

            allChannels = fetched
            val query = etSearchChannel?.text?.toString().orEmpty()
            filterChannels(query)

            if (fetched.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "Playlist load nahi ho payi ya koi channel nahi mila",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "${fetched.size} channels load hue",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun filterChannels(rawQuery: String) {
        val q = rawQuery.trim().lowercase(Locale.US)
        val filtered = if (q.isEmpty()) {
            allChannels
        } else {
            allChannels.filter { ch ->
                ch.name.lowercase(Locale.US).contains(q) ||
                    ch.group.lowercase(Locale.US).contains(q)
            }
        }

        channelAdapter?.submitList(filtered)

        if (listViewAdapter != null) {
            displayedListViewChannels = filtered
            listViewAdapter?.clear()
            listViewAdapter?.addAll(filtered.map { "${it.name} (${it.group})" })
            listViewAdapter?.notifyDataSetChanged()
        }

        tvChannelCount?.text = "Channels: ${filtered.size} / ${allChannels.size}"
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

    private class ChannelAdapter(
        private val onClick: (IptvChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

        private var items: List<IptvChannel> = emptyList()

        fun submitList(newItems: List<IptvChannel>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ChannelViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, position + 1, onClick)
        }

        override fun getItemCount(): Int = items.size

        class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(android.R.id.text1)
            private val subtitleView: TextView = itemView.findViewById(android.R.id.text2)

            fun bind(channel: IptvChannel, number: Int, onClick: (IptvChannel) -> Unit) {
                titleView.text = "$number. ${channel.name}"
                subtitleView.text = channel.group
                itemView.setOnClickListener { onClick(channel) }
            }
        }
    }
}
