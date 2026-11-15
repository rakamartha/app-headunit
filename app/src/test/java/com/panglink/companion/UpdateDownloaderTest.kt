package com.panglink.companion

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val release = "https://github.com/rakamartha/app-headunit/releases/download/v1.2-b11/PangLink-Companion-App.apk"
    private val cdn = "https://release-assets.githubusercontent.com/123/456?sig=abc"
    private val body = "test APK bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    private class Response(
        url: URL,
        private val status: Int,
        private val location: String? = null,
        private val body: ByteArray = ByteArray(0),
        private val length: Long = body.size.toLong()
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            assertFalse("HttpURLConnection must never auto-follow redirects", instanceFollowRedirects)
            return status
        }
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(body)
    }

    @Test fun followsValidatedCdnAndVerifiesDownloadedBytes() {
        val responses = mutableListOf<Response>()
        val downloader = UpdateDownloader(UpdatePolicy("rakamartha/app-headunit")) { url ->
            Response(url, if (url.toString() == release) 302 else 200, cdn, body).also { responses.add(it) }
        }
        val target = temporary.newFile()
        downloader.download(release, hash, target) { _, _ -> }
        assertArrayEquals(body, target.readBytes())
        assertEquals(listOf(release, cdn), responses.map { it.url.toString() })
        assertTrue(responses.all { it.disconnected })
    }

    @Test fun rejectsUntrustedRedirectBeforeOpeningItsConnection() {
        for (location in listOf("https://example.com/payload.apk", "http://release-assets.githubusercontent.com/payload.apk", release.replace("rakamartha", "other"))) {
            var requests = 0
            val downloader = UpdateDownloader(UpdatePolicy("rakamartha/app-headunit")) { url ->
                requests++
                Response(url, 302, location)
            }
            val target = temporary.newFile()
            assertThrows(IllegalArgumentException::class.java) { downloader.download(release, hash, target) { _, _ -> } }
            assertEquals(1, requests)
            assertFalse(target.exists())
        }
    }

    @Test fun rejectsMissingHashAndForeignInitialUrlWithoutNetwork() {
        val downloader = UpdateDownloader(UpdatePolicy("rakamartha/app-headunit")) { error("Network must not be reached") }
        for ((url, checksum) in listOf(release to "", release to "invalid", cdn to hash)) {
            val target = temporary.newFile()
            assertThrows(IllegalArgumentException::class.java) { downloader.download(url, checksum, target) { _, _ -> } }
            assertFalse(target.exists())
        }
    }

    @Test fun rejectsModifiedOrTruncatedFileAndDeletesPartialDownload() {
        for ((bytes, length) in listOf("changed APK".toByteArray() to 11L, body to (body.size + 1L))) {
            val downloader = UpdateDownloader(UpdatePolicy("rakamartha/app-headunit")) { url ->
                Response(url, 200, body = bytes, length = length)
            }
            val target = temporary.newFile()
            assertThrows(IllegalStateException::class.java) { downloader.download(release, hash, target) { _, _ -> } }
            assertFalse(target.exists())
        }
    }

    @Test fun failsClosedOnRedirectLoopHttpErrorAndOversizedResponse() {
        for (status in listOf(302, 404, 200)) {
            var requests = 0
            val responses = mutableListOf<Response>()
            val downloader = UpdateDownloader(UpdatePolicy("rakamartha/app-headunit")) { url ->
                requests++
                Response(url, status, release, length = UpdateDownloader.MAX_APK_BYTES + 1).also { responses.add(it) }
            }
            val target = temporary.newFile()
            assertThrows(Exception::class.java) { downloader.download(release, hash, target) { _, _ -> } }
            assertEquals(if (status == 302) 6 else 1, requests)
            assertTrue(responses.all { it.disconnected })
            assertFalse(target.exists())
        }
    }
}
