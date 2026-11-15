package com.panglink.companion

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Setiap hop diperiksa sebelum koneksi dibuka; redirect otomatis harus tetap mati. */
internal class UpdateDownloader(
    private val policy: UpdatePolicy,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    fun download(
        releaseUrl: String,
        expectedSha256: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        try {
            require(policy.isAllowedReleaseUrl(releaseUrl)) { "Alamat rilis APK tidak diizinkan." }
            require(UpdatePolicy.isValidSha256(expectedSha256)) { "Checksum SHA-256 pembaruan wajib tersedia." }
            var currentUrl = URL(releaseUrl)
            var redirects = 0
            while (true) {
                require(policy.isAllowedDownloadUrl(currentUrl.toString())) { "Redirect unduhan tidak diizinkan." }
                val connection = openConnection(currentUrl)
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "PangLink-OTA")
                    connection.setRequestProperty("Accept", "application/octet-stream, application/vnd.android.package-archive")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location")
                        require(!location.isNullOrBlank() && redirects < 5) { "Redirect unduhan tidak valid atau terlalu banyak." }
                        currentUrl = URL(currentUrl, location)
                        redirects++
                        continue
                    }
                    check(status == HttpURLConnection.HTTP_OK) { "Server unduhan mengembalikan HTTP $status." }
                    val total = connection.contentLengthLong
                    check(total <= MAX_APK_BYTES) { "Ukuran APK melebihi batas pembaruan." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                downloaded += read
                                check(downloaded <= MAX_APK_BYTES) { "Ukuran APK melebihi batas pembaruan." }
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                onProgress(downloaded, total)
                            }
                        }
                    }
                    check(total < 0 || downloaded == total) { "File APK hasil unduhan tidak lengkap." }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    check(actualHash.equals(expectedSha256, ignoreCase = true)) { "Checksum APK tidak cocok. Unduhan dibatalkan." }
                    return
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    companion object {
        const val MAX_APK_BYTES = 128L * 1024L * 1024L
    }
}
