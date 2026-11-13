package com.panglink.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
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
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class MainActivity : Activity() {

    private var webView: WebView? = null
    private var connectivityManager: ConnectivityManager? = null

    @Volatile private var pendingOtaApkFile: File? = null
    @Volatile private var isOtaDownloadingNow: Boolean = false
    @Volatile private var otaSessionToken: String? = null
    private val otaAction = "com.panglink.companion.OTA_INSTALL_COMMIT"

    private val otaDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != otaAction) return
            // Token acak per sesi: membuktikan broadcast berasal dari PendingIntent
            // PackageInstaller milik aplikasi ini, bukan dari aplikasi lain.
            val token = intent.getStringExtra("panglink_ota_token")
            if (token == null || token != otaSessionToken) return

            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent == null) {
                    pushOtaStatusToWeb(0, "ERROR", "Installer tidak mengirim intent konfirmasi.")
                    return
                }
                try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(confirmIntent)
                } catch (e: Throwable) {
                    pushOtaStatusToWeb(0, "ERROR", "Gagal membuka installer: ${e.message}")
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Gagal memasang paket"
                pushOtaStatusToWeb(0, "ERROR", "Instalasi dibatalkan / gagal: $msg")
            }
        }
    }

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
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; PangLinkCompanion) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36" 
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(otaDownloadReceiver, IntentFilter(otaAction), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(otaDownloadReceiver, IntentFilter(otaAction))
        }
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

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        val fileToInstall = pendingOtaApkFile
        if (fileToInstall != null && fileToInstall.exists() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()
        ) {
            pendingOtaApkFile = null
            window?.decorView?.postDelayed({ installApkFromFile(fileToInstall) }, 350)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(otaDownloadReceiver) } catch (_: Throwable) {}
    }

    private fun pushOtaStatusToWeb(pct: Int, state: String, message: String) {
        val safeMsg = org.json.JSONObject.quote(message)
        val safeState = org.json.JSONObject.quote(state)
        runOnUiThread {
            webView?.evaluateJavascript(
                "if(window.onNativeOtaProgress) window.onNativeOtaProgress($pct, $safeState, $safeMsg);",
                null
            )
        }
    }

    private fun installApkFromFile(apkFile: File) {
        runOnUiThread {
            try {
                if (!apkFile.exists() || apkFile.length() < 50_000L) {
                    pushOtaStatusToWeb(0, "ERROR", "File APK tidak ditemukan atau belum utuh.")
                    return@runOnUiThread
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                    pendingOtaApkFile = apkFile
                    pushOtaStatusToWeb(100, "COMPLETE", "Izinkan 'Instal aplikasi tidak dikenal' di layar berikutnya, lalu tekan Kembali.")
                    Toast.makeText(
                        this@MainActivity,
                        "Aktifkan izin 'Instal aplikasi tidak dikenal' untuk PangLink, lalu kembali",
                        Toast.LENGTH_LONG
                    ).show()
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(permIntent)
                    return@runOnUiThread
                }

                pendingOtaApkFile = null

                val packageInstaller = packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                FileInputStream(apkFile).use { input ->
                    session.openWrite("PangLink_Update.apk", 0, apkFile.length()).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        session.fsync(output)
                    }
                }

                val sessionToken = UUID.randomUUID().toString()
                otaSessionToken = sessionToken
                val callbackIntent = Intent(otaAction).apply {
                    setPackage(packageName)
                    putExtra("panglink_ota_token", sessionToken)
                }
                val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(this@MainActivity, sessionId, callbackIntent, pendingFlags)
                session.commit(pendingIntent.intentSender)
                session.close()
            } catch (e: Throwable) {
                pushOtaStatusToWeb(0, "ERROR", "Gagal membuka installer: ${e.message}")
                Toast.makeText(this@MainActivity, "Gagal membuka installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isAllowedUpdateUrl(rawUrl: String): Boolean {
        return try {
            val url = URL(rawUrl)
            if (url.protocol != "https") return false
            val host = url.host?.lowercase() ?: return false
            if (host != "github.com" && !host.endsWith(".githubusercontent.com")) return false
            (url.path ?: "").lowercase().endsWith(".apk")
        } catch (_: Throwable) {
            false
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun signatureSha256Set(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: return emptySet()
        }
        return signatures.filterNotNull().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }.toSet()
    }

    private fun apkSignerMatchesInstalled(apkFile: File): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false
            val installedInfo = packageManager.getPackageInfo(packageName, flags)
            val archiveSigners = signatureSha256Set(archiveInfo)
            val installedSigners = signatureSha256Set(installedInfo)
            archiveSigners.isNotEmpty() && installedSigners.isNotEmpty() && archiveSigners.any { it in installedSigners }
        } catch (_: Throwable) {
            false
        }
    }

    inner class CompanionBridge(private val context: Context) {
        @JavascriptInterface
        fun getSyncConfigJson(): String {
            return try {
                val key = BuildConfig.ABLY_API_KEY
                val configured = BuildConfig.SYNC_PROVIDER.ifBlank { "ntfy" }.lowercase()
                val provider = if (configured == "ably" && key.isBlank()) "ntfy" else configured
                val ably = org.json.JSONObject()
                ably.put("key", key)
                ably.put("namespace", "pangdash")
                val root = org.json.JSONObject()
                root.put("provider", provider)
                root.put("ably", ably)
                root.toString()
            } catch (_: Throwable) {
                "{\"provider\":\"ntfy\"}"
            }
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1"
            } catch (_: Throwable) {
                "1.1"
            }
        }

        @JavascriptInterface
        fun getAppVersionCode(): Int {
            return try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (_: Throwable) {
                7
            }
        }

        @JavascriptInterface
        fun startUpdateDownload(apkUrl: String) {
            startUpdateDownloadWithHash(apkUrl, "")
        }

        @JavascriptInterface
        fun startUpdateDownloadWithHash(apkUrl: String, expectedSha256: String) {
            if (isOtaDownloadingNow) return
            if (!isAllowedUpdateUrl(apkUrl)) {
                pushOtaStatusToWeb(0, "ERROR", "Alamat unduhan APK tidak diizinkan.")
                return
            }

            isOtaDownloadingNow = true
            pushOtaStatusToWeb(4, "DOWNLOADING", "Menghubungi server GitHub Release...")

            Thread {
                try {
                    val outDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                    if (!outDir.exists()) outDir.mkdirs()
                    val targetFile = File(outDir, "PangLink_Update.apk")
                    if (targetFile.exists()) targetFile.delete()

                    var currentUrl = apkUrl
                    var connection: HttpURLConnection? = null
                    var redirectCount = 0

                    while (redirectCount < 6) {
                        val urlObj = URL(currentUrl)
                        val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; PangLink-OTA) AppleWebKit/537.36")
                            setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                        }
                        conn.connect()
                        val code = conn.responseCode
                        if (code in listOf(
                                HttpURLConnection.HTTP_MOVED_PERM,
                                HttpURLConnection.HTTP_MOVED_TEMP,
                                HttpURLConnection.HTTP_SEE_OTHER,
                                307,
                                308
                            )
                        ) {
                            val nextLoc = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (nextLoc.isNullOrBlank()) break
                            currentUrl = nextLoc
                            redirectCount++
                        } else {
                            connection = conn
                            break
                        }
                    }

                    val finalConn = connection ?: throw Exception("Tidak dapat terhubung ke server unduhan")
                    if (finalConn.responseCode != HttpURLConnection.HTTP_OK) {
                        val errCode = finalConn.responseCode
                        finalConn.disconnect()
                        throw Exception("Server mengembalikan status HTTP $errCode (Pastikan repositori publik & rilis APK tersedia)")
                    }

                    val totalBytes = finalConn.contentLengthLong
                    var downloadedBytes = 0L
                    var lastReportTime = 0L

                    finalConn.inputStream.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(32768)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReportTime > 180L) {
                                    lastReportTime = now
                                    val pct = if (totalBytes > 0) {
                                        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(5, 99)
                                    } else {
                                        50
                                    }
                                    val mbDown = String.format(java.util.Locale.US, "%.2f", downloadedBytes / (1024.0 * 1024.0))
                                    val mbTot = if (totalBytes > 0) String.format(java.util.Locale.US, " / %.2f MB", totalBytes / (1024.0 * 1024.0)) else " MB"
                                    pushOtaStatusToWeb(pct, "DOWNLOADING", "Mengunduh PangLink APK... ($pct% • $mbDown$mbTot)")
                                }
                            }
                            output.flush()
                        }
                    }
                    finalConn.disconnect()

                    if (!targetFile.exists() || targetFile.length() < 50_000L) {
                        throw Exception("File APK hasil unduhan tidak lengkap (${targetFile.length()} bytes)")
                    }

                    val expectedHash = expectedSha256.trim().lowercase()
                    if (expectedHash.matches(Regex("^[a-f0-9]{64}$"))) {
                        val actualHash = sha256Of(targetFile)
                        if (actualHash != expectedHash) {
                            targetFile.delete()
                            throw Exception("Checksum APK tidak cocok. Unduhan dibatalkan demi keamanan.")
                        }
                    }

                    if (!apkSignerMatchesInstalled(targetFile)) {
                        targetFile.delete()
                        throw Exception("Sertifikat APK berbeda dengan aplikasi terpasang. Lakukan instal ulang manual satu kali.")
                    }

                    isOtaDownloadingNow = false
                    pushOtaStatusToWeb(100, "COMPLETE", "Unduhan selesai 100%! Membuka penginstal Android...")
                    installApkFromFile(targetFile)
                } catch (e: Throwable) {
                    isOtaDownloadingNow = false
                    pushOtaStatusToWeb(0, "ERROR", "Unduhan gagal: ${e.message}")
                }
            }.start()
        }

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
