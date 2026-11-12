package com.greycoat.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.content.Intent
import android.net.Uri
import android.widget.Toast

class MainActivity : Activity() {

    private var webView: WebView? = null
    private var connectivityManager: ConnectivityManager? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        } catch (_: Throwable) {}

        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Throwable) {}

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0d0d0d"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        try {
            val wv = WebView(this)
            wv.setBackgroundColor(Color.parseColor("#0d0d0d"))
            wv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            val settings: WebSettings = wv.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Aset lokal tetap terbaca dari file:///android_asset meski akses filesystem umum dimatikan.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; GreycoatCompanion) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36" 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            wv.overScrollMode = View.OVER_SCROLL_NEVER

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    val scheme = target.scheme?.lowercase() ?: return false
                    if (scheme == "file" || scheme == "about" || scheme == "data") return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (_: Throwable) {
                        true
                    }
                }
            }
            wv.webChromeClient = WebChromeClient()
            wv.addJavascriptInterface(CompanionBridge(this), "AndroidBridge")

            container.addView(wv)
            webView = wv
            setContentView(container)

            wv.loadUrl("file:///android_asset/index.html")
        } catch (t: Throwable) {
            setContentView(container)
            Toast.makeText(this, "WebView error: ${t.message}", Toast.LENGTH_LONG).show()
        }

        registerAutoNetworkSync()
        window?.decorView?.post { hideSystemUI() }
    }

    private fun registerAutoNetworkSync() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runOnUiThread { webView?.evaluateJavascript("if(window.onNativeNetworkChange) window.onNativeNetworkChange(true);", null) }
                    }
                    override fun onLost(network: Network) {
                        runOnUiThread { webView?.evaluateJavascript("if(window.onNativeNetworkChange) window.onNativeNetworkChange(false);", null) }
                    }
                })
            }
        } catch (_: Throwable) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (_: Throwable) {}
    }

    inner class CompanionBridge(private val context: Context) {
        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun openParkingMap(lat: Double, lng: Double, label: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + Uri.encode(label) + ")")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Throwable) {
                    Toast.makeText(context, "Koordinat Parkir: $lat, $lng", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
