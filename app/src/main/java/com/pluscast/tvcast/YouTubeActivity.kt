package com.pluscast.tvcast

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class YouTubeActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_youtube)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "YouTube layout load nahi ho paya", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val wv = findViewById<WebView>(R.id.webView)
            webView = wv

            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.loadUrl("https://m.youtube.com")

            val etUrl = findViewById<EditText>(R.id.etYoutubeUrl)
            val btnGo = findViewById<Button>(R.id.btnLoadYoutube)

            btnGo.setOnClickListener {
                val input = etUrl.text.toString().trim()
                if (input.isNotEmpty()) {
                    val targetUrl = if (input.startsWith("http://") || input.startsWith("https://")) {
                        input
                    } else {
                        "https://m.youtube.com/results?search_query=${Uri.encode(input)}"
                    }
                    webView?.loadUrl(targetUrl)
                } else {
                    Toast.makeText(this, "YouTube link ya search text dalein", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "YouTube player error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                destroy()
            }
            webView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
