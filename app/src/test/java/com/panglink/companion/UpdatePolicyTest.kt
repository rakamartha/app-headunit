package com.panglink.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val policy = UpdatePolicy("rakamartha/app-headunit")
    private val release = "https://github.com/rakamartha/app-headunit/releases/download/v1.2-b11/PangLink-Companion-App.apk"

    @Test fun officialReleaseAndCdnRedirectAreAllowed() {
        assertTrue(policy.isAllowedReleaseUrl(release))
        assertTrue(policy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123/456?sig=abc"))
        assertFalse(policy.isAllowedReleaseUrl("https://release-assets.githubusercontent.com/anything.apk"))
    }

    @Test fun otherRepositoriesAndLookalikeHostsAreRejected() {
        assertFalse(policy.isAllowedReleaseUrl(release.replace("rakamartha", "other-owner")))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("app-headunit", "headunit-launcher")))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("github.com", "github.com.evil.example")))
        assertFalse(policy.isAllowedDownloadUrl("https://untrusted.githubusercontent.com/payload.apk"))
        assertFalse(policy.isAllowedDownloadUrl("https://release-assets.githubusercontent.com.evil.example/payload.apk"))
    }

    @Test fun downgradeCredentialsPortsAndTraversalAreRejected() {
        assertFalse(policy.isAllowedReleaseUrl(release.replace("https:", "http:")))
        assertFalse(policy.isAllowedDownloadUrl("http://release-assets.githubusercontent.com/payload.apk"))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("github.com", "user:pass@github.com")))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("github.com", "github.com:444")))
        assertFalse(policy.isAllowedReleaseUrl("$release#fragment"))
        assertFalse(policy.isAllowedReleaseUrl("$release?redirect=other"))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("v1.2-b11", "..")))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("v1.2-b11", "%2e%2e")))
        assertFalse(policy.isAllowedReleaseUrl(release.replace("v1.2-b11", "v1.2-b11%2fother")))
    }

    @Test fun compiledForkUsesOnlyItsOwnRepository() {
        val fork = UpdatePolicy("example/panglink")
        assertFalse(fork.isAllowedReleaseUrl(release))
        assertTrue(fork.isAllowedReleaseUrl(release.replace("rakamartha/app-headunit", "example/panglink")))
    }

    @Test fun checksumIsMandatoryAndExact() {
        assertTrue(UpdatePolicy.isValidSha256("a1".repeat(32)))
        assertFalse(UpdatePolicy.isValidSha256(""))
        assertFalse(UpdatePolicy.isValidSha256("a".repeat(63)))
        assertFalse(UpdatePolicy.isValidSha256("z".repeat(64)))
    }

    @Test fun sameSignerCannotAuthorizeAnotherAppOrDowngrade() {
        assertTrue(UpdatePolicy.isNewerOwnPackage("com.panglink.companion", 10, "com.panglink.companion", 11))
        assertFalse(UpdatePolicy.isNewerOwnPackage("com.panglink.companion", 10, "com.pangdash.launcher", 155))
        assertFalse(UpdatePolicy.isNewerOwnPackage("com.panglink.companion", 10, "com.panglink.companion", 9))
        assertFalse(UpdatePolicy.isNewerOwnPackage("com.panglink.companion", 10, "com.panglink.companion", 10))
    }

    @Test fun nativeBridgePageCannotBeReplacedWithRemoteOrInlineContent() {
        assertTrue(UpdatePolicy.isTrustedAppPage("file:///android_asset/index.html"))
        assertTrue(UpdatePolicy.isTrustedAppPage("file:///android_asset/index.html#settings"))
        assertFalse(UpdatePolicy.isTrustedAppPage("file:///sdcard/index.html"))
        assertFalse(UpdatePolicy.isTrustedAppPage("file://untrusted/android_asset/index.html"))
        assertFalse(UpdatePolicy.isTrustedAppPage("data:text/html,<script>alert(1)</script>"))
        assertFalse(UpdatePolicy.isTrustedAppPage("https://example.com/index.html"))
        assertFalse(UpdatePolicy.isTrustedAppPage("about:blank"))
    }
}
