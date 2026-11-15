import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Konfigurasi signing rilis (opsional untuk companion).
// Urutan pencarian: -PKEYSTORE_PATH=... / env KEYSTORE_PATH / keystore.properties
// Jangan pernah commit file keystore atau keystore.properties ke repositori.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingValue(name: String): String? {
    val fromGradle = (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(name)?.takeIf { it.isNotBlank() }
    val fromFile = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
    return fromGradle ?: fromEnv ?: fromFile
}

val releaseKeystorePath = signingValue("KEYSTORE_PATH")
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

// ---------------------------------------------------------------------------
// Konfigurasi sync. Kunci Ably dibaca dari env/secret, tidak pernah dari repo.
// Jika kunci kosong, aplikasi tetap memakai ntfy sebagai cadangan.
// ---------------------------------------------------------------------------
val ablyApiKey = ((findProperty("ablyKey") as String?) ?: System.getenv("ABLY_API_KEY") ?: "").trim()
val syncProvider = ((findProperty("syncProvider") as String?) ?: System.getenv("SYNC_PROVIDER") ?: "")
    .trim()
    .ifBlank { if (ablyApiKey.isNotBlank()) "ably" else "ntfy" }
val otaRepository = (System.getenv("GITHUB_REPOSITORY") ?: "rakamartha/app-headunit").trim()
require(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(otaRepository)) {
    "GITHUB_REPOSITORY harus berformat pemilik/repo"
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.panglink.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.panglink.companion"
        minSdk = 24
        targetSdk = 34
        versionCode = (findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 7
        versionName = (findProperty("versionNameOverride") as String?)?.takeIf { it.isNotBlank() } ?: "1.1"
        buildConfigField("String", "SYNC_PROVIDER", buildConfigString(syncProvider))
        buildConfigField("String", "ABLY_API_KEY", buildConfigString(ablyApiKey))
        buildConfigField("String", "OTA_REPOSITORY", buildConfigString(otaRepository))
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseKeystorePath as String)
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
                // v2 tetap dipakai Android 7-8; v3 menambah verifikasi lebih ketat di Android 9+.
                // Identitas sertifikat dan package tidak berubah, jadi update in-place tetap aman.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Error lint harus menghentikan build, seperti pada PangDash headunit.
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
