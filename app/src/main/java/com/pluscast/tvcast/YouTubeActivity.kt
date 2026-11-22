package com.pluscast.tvcast

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class YouTubeActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    return try {
                        val intent = if (scheme == "intent") {
                            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        } else {
                            Intent(Intent.ACTION_VIEW, uri)
                        }
                        intent.addCategory(Intent.CATEGORY_BROWSABLE)
                        intent.component = null
                        intent.selector = null
                        startActivity(intent)
                        true
                    } catch (_: Exception) {
                        true
                    }
                }
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    val decorView = window.decorView as? ViewGroup ?: return
                    decorView.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    webView?.visibility = View.GONE
                }

                override fun onHideCustomView() {
                    val decorView = window.decorView as? ViewGroup ?: return
                    customView?.let { decorView.removeView(it) }
                    customView = null
                    webView?.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
            }

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

    override fun onResume() {
        super.onResume()
        try {
            webView?.onResume()
            webView?.resumeTimers()
        } catch (_: Exception) {
        }
    }

    override fun onPause() {
        try {
            webView?.onPause()
            webView?.pauseTimers()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            (webView?.webChromeClient as? WebChromeClient)?.onHideCustomView()
            return
        }
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
            if (customView != null) {
                (webView?.webChromeClient as? WebChromeClient)?.onHideCustomView()
            }
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
