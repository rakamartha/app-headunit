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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private var webView: WebView? = null
    private var backNavigationPending = false
    private var connectivityManager: ConnectivityManager? = null

    private data class PendingUpdate(val file: File, val sha256: String)
    @Volatile private var pendingOtaUpdate: PendingUpdate? = null
    private val otaBusy = AtomicBoolean(false)
    private val updatePolicy = UpdatePolicy(BuildConfig.OTA_REPOSITORY)
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
                    failOtaUpdate(null, "Installer tidak mengirim intent konfirmasi.")
                    return
                }
                try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(confirmIntent)
                } catch (e: Throwable) {
                    failOtaUpdate(null, "Gagal membuka installer: ${e.message}")
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Gagal memasang paket"
                failOtaUpdate(null, "Instalasi dibatalkan / gagal: $msg")
            } else {
                otaSessionToken = null
                otaBusy.set(false)
                pushOtaStatusToWeb(100, "COMPLETE", "Pembaruan berhasil dipasang.")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag")
    // Suppress lint: flag RECEIVER_NOT_EXPORTED sudah eksplisit di Android 13+;
    // konstanta RECEIVER_* tidak tersedia untuk cabang Android 12 ke bawah.
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
                    val target = request?.url ?: return true
                    if (!request.isForMainFrame) return true
                    if (UpdatePolicy.isTrustedAppPage(target.toString())) return false
                    // Halaman yang memiliki native bridge hanya boleh berasal dari aset aplikasi.
                    if (request.hasGesture() && target.scheme == "https") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Throwable) {}
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request?.isForMainFrame == true && !UpdatePolicy.isTrustedAppPage(request.url.toString())) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val view = webView
        if (view == null) {
            super.onBackPressed()
            return
        }
        if (backNavigationPending) return
        backNavigationPending = true
        try {
            view.evaluateJavascript(
                "typeof window.handleNativeBack === 'function' && window.handleNativeBack() === true"
            ) { handled ->
                backNavigationPending = false
                if (handled != "true" && !isFinishing && !isDestroyed) {
                    super.onBackPressed()
                }
            }
        } catch (_: Throwable) {
            backNavigationPending = false
            super.onBackPressed()
        }
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
        val update = pendingOtaUpdate
        if (update != null) {
            pendingOtaUpdate = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                // File, checksum, package, versi, dan sertifikat diperiksa lagi setelah kembali dari Setelan.
                installApkFromFile(update)
            } else {
                failOtaUpdate(update, "Izin pemasangan belum diberikan. Pembaruan dibatalkan.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(otaDownloadReceiver) } catch (_: Throwable) {}
        pendingOtaUpdate?.file?.delete()
        pendingOtaUpdate = null
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

    private fun failOtaUpdate(update: PendingUpdate?, message: String) {
        update?.file?.delete()
        pendingOtaUpdate = null
        otaSessionToken = null
        otaBusy.set(false)
        pushOtaStatusToWeb(0, "ERROR", message)
    }

    private fun installApkFromFile(update: PendingUpdate) {
        Thread {
            try {
                verifyUpdate(update)
                runOnUiThread {
                    try {
                        check(!isFinishing && !isDestroyed) { "Layar pembaruan sudah ditutup." }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                            pendingOtaUpdate = update
                            pushOtaStatusToWeb(100, "COMPLETE", "Pembaruan terverifikasi. Izinkan pemasangan dari PangLink, lalu kembali.")
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                        } else {
                            commitOtaInstall(update)
                        }
                    } catch (e: Exception) {
                        failOtaUpdate(update, "Gagal membuka installer: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                failOtaUpdate(update, "Pembaruan ditolak: ${e.message}")
            }
        }.start()
    }

    private fun commitOtaInstall(update: PendingUpdate) {
        Thread {
            val packageInstaller = packageManager.packageInstaller
            var sessionId: Int? = null
            var committed = false
            try {
                verifyUpdate(update)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                params.setSize(update.file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val createdSessionId = packageInstaller.createSession(params)
                sessionId = createdSessionId
                packageInstaller.openSession(createdSessionId).use { session ->
                    // Cocokkan juga byte yang benar-benar disalin ke sesi installer.
                    val digest = MessageDigest.getInstance("SHA-256")
                    FileInputStream(update.file).use { input ->
                        session.openWrite("PangLink_Update.apk", 0, update.file.length()).use { output ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                digest.update(buffer, 0, bytesRead)
                            }
                            session.fsync(output)
                        }
                    }
                    val copiedHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(copiedHash == update.sha256) { "File APK berubah sebelum instalasi." }

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
                    val pendingIntent = PendingIntent.getBroadcast(this@MainActivity, createdSessionId, callbackIntent, pendingFlags)
                    pushOtaStatusToWeb(100, "COMPLETE", "Pembaruan terverifikasi. Konfirmasikan pemasangan di installer Android.")
                    session.commit(pendingIntent.intentSender)
                    committed = true
                }
            } catch (e: Exception) {
                failOtaUpdate(update, "Gagal memasang pembaruan: ${e.message}")
            } finally {
                if (!committed) {
                    sessionId?.let { try { packageInstaller.abandonSession(it) } catch (_: Exception) {} }
                }
                update.file.delete()
            }
        }.start()
    }

    private fun verifyUpdate(update: PendingUpdate) {
        check(update.file.canonicalFile.parentFile == File(cacheDir, "ota").canonicalFile &&
            update.file.isFile && update.file.length() in 50_000L..UpdateDownloader.MAX_APK_BYTES
        ) { "File APK privat tidak ditemukan atau tidak utuh." }
        check(UpdatePolicy.isValidSha256(update.sha256) && sha256Of(update.file) == update.sha256) {
            "Checksum APK tidak cocok. Unduh pembaruan kembali."
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = packageManager.getPackageArchiveInfo(update.file.absolutePath, flags)
            ?: error("File bukan APK yang valid.")
        val installed = packageManager.getPackageInfo(packageName, flags)
        check(UpdatePolicy.isNewerOwnPackage(packageName, packageVersionCode(installed), archive.packageName, packageVersionCode(archive))) {
            "APK harus pembaruan PangLink dengan versionCode lebih baru."
        }
        val installedSigners = signatureSha256Set(installed)
        check(installedSigners.isNotEmpty() && signatureSha256Set(archive) == installedSigners) {
            "Sertifikat APK berbeda dari aplikasi terpasang."
        }
    }

    private fun packageVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
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
            signingInfo.apkContentsSigners
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
        fun getOtaRepository(): String = BuildConfig.OTA_REPOSITORY

        @JavascriptInterface
        fun startUpdateDownload(apkUrl: String) {
            startUpdateDownloadWithHash(apkUrl, "")
        }

        @JavascriptInterface
        fun startUpdateDownloadWithHash(apkUrl: String, expectedSha256: String) {
            if (!updatePolicy.isAllowedReleaseUrl(apkUrl)) {
                pushOtaStatusToWeb(0, "ERROR", "Alamat unduhan APK tidak diizinkan.")
                return
            }

            val expectedHash = expectedSha256.trim().lowercase()
            if (!UpdatePolicy.isValidSha256(expectedHash)) {
                pushOtaStatusToWeb(0, "ERROR", "Checksum SHA-256 pembaruan wajib tersedia.")
                return
            }
            if (!otaBusy.compareAndSet(false, true)) return
            pushOtaStatusToWeb(4, "DOWNLOADING", "Menghubungi server GitHub Release...")

            Thread {
                var update: PendingUpdate? = null
                try {
                    val outDir = File(cacheDir, "ota")
                    check(outDir.isDirectory || outDir.mkdirs()) { "Folder cache pembaruan tidak tersedia." }
                    val targetFile = File.createTempFile("PangLink_Update_", ".apk", outDir)
                    val candidate = PendingUpdate(targetFile, expectedHash)
                    update = candidate
                    var lastReportTime = 0L
                    UpdateDownloader(updatePolicy).download(apkUrl, expectedHash, targetFile) { downloadedBytes, totalBytes ->
                        val now = SystemClock.uptimeMillis()
                        if (now - lastReportTime > 180L) {
                            lastReportTime = now
                            val pct = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(5, 99) else 50
                            pushOtaStatusToWeb(pct, "DOWNLOADING", "Mengunduh PangLink APK... ($pct%)")
                        }
                    }
                    pushOtaStatusToWeb(99, "DOWNLOADING", "Memverifikasi identitas APK...")
                    installApkFromFile(candidate)
                } catch (e: Exception) {
                    failOtaUpdate(update, "Unduhan gagal: ${e.message}")
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
