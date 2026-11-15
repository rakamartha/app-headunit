# Draf Banding Google Play Protect — PangLink

**Status:** draf, belum dikirim. Tanggal 15 November 2026.
**Kanal:** formulir resmi <https://support.google.com/googleplay/android-developer/contact/protectappeals>
**Panduan yang sudah dibaca:** <https://developers.google.com/android/play-protect/warning-dev-guidance>

> Formulir meminta **email developer**, **package name**, **SHA-256 APK yang diunggah ke VirusTotal**, dan informasi pendukung.
> Jangan menulis "false positive" sebagai kepastian. Sampaikan bahwa aplikasi sudah diaudit, tidak memuat perilaku berbahaya,
> dan minta peninjauan ulang. Jangan mengunggah/berbagi keystore, password, atau kunci API ke mana pun.

## 0. APK terbaru untuk banding (disarankan)

Versi yang **paling layak** diunggah ke VirusTotal dan dipakai di formulir adalah build perbaikan terbaru:

| Item | Nilai |
|---|---|
| Versi | 1.2 (versionCode 11) |
| Ukuran | 980.698 byte |
| SHA-256 APK | `bd2bc488baee506ae952c0ce40b483b5f5c0588b0c1281ba0f8afcae11bd44d3` |
| Tanda tangan | v2 + v3, sertifikat `196b4a0a…738d` |
| Rilis | <https://github.com/rakamartha/app-headunit/releases/tag/v1.2-b11> |

Bagian 1 di bawah tetap menyimpan fakta b10 (APK yang memunculkan peringatan). Jika formulir hanya menerima satu APK,
pakai **b11** dan sesuaikan versi/hash/sumber di teks formulir. Jika Google meminta APK yang persis diblokir, gunakan b10.

---

## 1. Fakta APK yang diblokir

| Item | Nilai |
|---|---|
| Nama aplikasi | PangLink |
| Package | `com.panglink.companion` |
| Versi | 1.2 (versionCode 10) |
| Ukuran | 971.054 byte |
| minSdk / targetSdk | 24 / 34 |
| SHA-256 APK | `372e24e70befb272319a37eadb72034a20f9847141c8ad5326374b5614caef51` |
| Sertifikat penanda tangan (SHA-256) | `196b4a0a125b2607003944447f02b250abc11d2c6f054cdabb88bc760efa738d` |
| Sumber unduhan | <https://github.com/rakamartha/app-headunit/releases/tag/v1.2-b10> |
| Kode sumber | <https://github.com/rakamartha/app-headunit> |

Izin yang dideklarasikan (hanya tiga):

1. `android.permission.INTERNET` — sinkronisasi data kendaraan dan pemeriksaan pembaruan.
2. `android.permission.ACCESS_NETWORK_STATE` — menampilkan status online/offline.
3. `android.permission.REQUEST_INSTALL_PACKAGES` — pemasangan pembaruan aplikasi sendiri setelah pengguna menyetujui.

Tidak ada: Accessibility Service, Notification Listener, Device Admin, SMS/Telephony, Kontak, Lokasi,
kamera, mikrofon, `QUERY_ALL_PACKAGES`, atau layanan latar depan yang berjalan terus-menerus.
Tidak ada SDK iklan, analytics, atau pelacakan pihak ketiga.

---

## 2. Hal yang perlu dilengkapi pemilik akun sebelum mengirim

1. **Unggah APK ke VirusTotal** (pilih salah satu):
   - APK b10 yang diblokir: `PangLink-Companion-App.apk` dengan SHA-256 di atas, **atau**
   - APK build baru setelah perbaikan keamanan (lebih baik bila banding dikirim setelah rilis berikutnya).
2. Salin **SHA-256 yang ditampilkan VirusTotal** dan **tautan laporan VirusTotal**.
3. Isi alamat email developer yang terdaftar/aktif.
4. Tempel teks pada bagian 3 (ringkas) atau bagian 4 (lengkap) ke formulir.
5. Simpan tangkapan layar peringatan "Aplikasi berbahaya diblokir" sebagai lampiran bila formulir menyediakan kolom unggah.

> Jika banding dikirim untuk APK baru, ganti versi/hash/sumber di bagian 1 dan 3–4 dengan data APK baru tersebut.
> Satu banding = satu APK yang diunggah.

---

## 3. Teks ringkas untuk formulir (Bahasa Inggris)

```text
Hello Play Protect team,

I am requesting a manual review of PangLink (package com.panglink.companion, version 1.2,
versionCode 10), which is currently blocked by Play Protect with a "harmful app" warning.

PangLink is a companion dashboard for an Android head-unit launcher that I also develop
(PangDash). The phone app receives trip statistics, parking location and service-reminder
data from the head unit over an encrypted channel (AES-256-GCM, topic names derived with
SHA-256 from a 12-character pairing code), and displays them. It has no ads, no analytics,
and no third-party tracking SDK.

The app requests only three permissions:
- INTERNET: sync with the paired head unit and check for updates.
- ACCESS_NETWORK_STATE: show online/offline status.
- REQUEST_INSTALL_PACKAGES: install updates of this same app, after the user confirms
  through the standard Android package installer.

The update path is deliberately restricted: the app only accepts HTTPS downloads from the
GitHub Releases of its own repository, requires a SHA-256 checksum published with the
release, verifies that the downloaded APK has the same package name, a higher versionCode
and the same signing certificate as the installed app, and never installs silently.

The app does not use Accessibility Service, Notification Listener, Device Admin, SMS,
contacts, location, camera or microphone, and does not run a persistent background service.
All HTML/JS is bundled inside the APK; no remote code is loaded into the app.

APK SHA-256 (also uploaded to VirusTotal): <ISI_SHA256_DARI_VIRUSTOTAL>
VirusTotal report: <ISI_LINK_VIRUSTOTAL>
Signing certificate SHA-256: 196b4a0a125b2607003944447f02b250abc11d2c6f054cdabb88bc760efa738d
Source code: https://github.com/rakamartha/app-headunit

I have audited the app and removed unused or risky components. I would appreciate a manual
review so the warning can be removed. I am happy to provide any additional information.

Thank you.
```

---

## 4. Teks lengkap (opsional, untuk kolom keterangan tambahan)

```text
App: PangLink
Package: com.panglink.companion
Version: 1.2 (versionCode 10)
Distribution: direct APK from GitHub Releases (not published on Google Play)
APK SHA-256: 372e24e70befb272319a37eadb72034a20f9847141c8ad5326374b5614caef51
Signer certificate SHA-256: 196b4a0a125b2607003944447f02b250abc11d2c6f054cdabb88bc760efa738d
Source: https://github.com/rakamartha/app-headunit

1. What the app does
PangLink is an optional phone companion for PangDash, a launcher for Android car head units.
After pairing with a 12-character code, the phone shows the vehicle's monthly distance, trip
history (including route polylines), last parking position on a map, and service reminders.
Data is sent by the head unit through Ably or ntfy; payloads are encrypted with AES-256-GCM
and authenticated, with per-message nonces that reject replays.

2. Permissions and why they are needed
- INTERNET: receive encrypted vehicle data and check for app updates.
- ACCESS_NETWORK_STATE: display online/offline state in the UI.
- REQUEST_INSTALL_PACKAGES: install updates of PangLink itself. The user always confirms
  installation in the Android system installer; there is no silent or background install.

No Accessibility, Notification Listener, Device Admin, SMS, contacts, location, camera or
microphone permissions are declared. There are no ads, analytics or tracking SDKs. The app
does not request the ability to list or manage other installed apps.

3. Update mechanism
- Release metadata is read from the GitHub Releases API of the app's own repository.
- Downloads are HTTPS-only and restricted to the repository's own release assets.
- Every APK must match a SHA-256 checksum published next to it (or GitHub's asset digest).
- Before installation the app verifies package name, a strictly higher versionCode and that
  the signing certificate equals the installed app's certificate.
- Files are stored in the app's private cache and deleted if verification fails.
- Server redirects are validated hop by hop; cleartext HTTP is disabled at the application level.

4. WebView usage
- The UI is loaded from file:///android_asset/index.html, bundled in the APK.
- File and content access are disabled in WebView settings; mixed content is blocked.
- Navigation of the bridged WebView is limited to the bundled page; other HTTPS links open in
  the system browser.
- A Content-Security-Policy blocks frames, objects, base-uri and form submission.
- No remote JavaScript is loaded or evaluated; the asset build fails if iframe/srcdoc/Worker/eval
  patterns are introduced.

5. Data handling
- No account is required and no analytics are collected.
- Pairing secrets are stored locally on the device. Payload content is encrypted in transit.
- Backup is disabled (android:allowBackup="false"); cleartext traffic is disabled.

6. Other hardening
- Release APKs are signed with a stable certificate; v2 signatures remain for Android 7-8 and
  v3 is enabled for Android 9+.
- The app has no exported components other than the main launcher activity.
- It does not download or execute code dynamically.

I believe the warning does not reflect the app's current behavior and request a manual review.
I can provide the APK, build logs, or any further detail needed.
```

---

## 5. Setelah banding dikirim

- Simpan nomor kasus/email konfirmasi Google.
- Jangan mengganti package name atau identitas penanda tangan; keduanya sudah dipakai pengguna.
- Jika Google meminta APK baru, kirim versi yang lebih tinggi (mis. `versionCode 11`) beserta hash barunya.
- Perbarui `AUDIT-REVIEW.md` dengan tanggal banding, nomor kasus, dan hasilnya.
