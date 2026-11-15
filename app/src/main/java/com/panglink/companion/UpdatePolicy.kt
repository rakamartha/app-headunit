package com.panglink.companion

import java.net.URI

/** Aturan OTA yang dapat diuji tanpa Android; tidak menerima sumber dari localStorage. */
internal class UpdatePolicy(private val repository: String) {
    private val releasePath = Regex(
        "^/${Regex.escape(repository)}/releases/download/[A-Za-z0-9_.-]+/PangLink-Companion-App\\.apk$"
    )
    private val assetHosts = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    fun isAllowedReleaseUrl(rawUrl: String): Boolean {
        val uri = secureUri(rawUrl) ?: return false
        return uri.host.equals("github.com", ignoreCase = true) &&
            uri.rawQuery == null && releasePath.matches(uri.rawPath ?: "") &&
            uri.normalize().rawPath == uri.rawPath
    }

    // CDN hanya boleh dipakai setelah request awal ke rilis repo yang dikompilasi.
    fun isAllowedDownloadUrl(rawUrl: String): Boolean {
        if (isAllowedReleaseUrl(rawUrl)) return true
        val uri = secureUri(rawUrl) ?: return false
        return assetHosts.any { it.equals(uri.host, ignoreCase = true) }
    }

    private fun secureUri(rawUrl: String): URI? = try {
        URI(rawUrl).takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.host != null &&
                it.rawUserInfo == null && (it.port == -1 || it.port == 443) && it.rawFragment == null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun isValidSha256(value: String): Boolean = Regex("^[a-fA-F0-9]{64}$").matches(value)

        fun isTrustedAppPage(rawUrl: String): Boolean = try {
            val uri = URI(rawUrl)
            uri.scheme == "file" && uri.rawAuthority == null && uri.rawQuery == null &&
                uri.rawPath == "/android_asset/index.html"
        } catch (_: Exception) {
            false
        }

        fun isNewerOwnPackage(
            installedPackage: String,
            installedVersion: Long,
            archivePackage: String,
            archiveVersion: Long
        ): Boolean = archivePackage == installedPackage && archiveVersion > installedVersion
    }
}
