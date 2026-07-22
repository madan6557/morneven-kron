# KRON

Current release: KRON 1.4.7 Full Release LTS.

KRON adalah aplikasi pencatatan keuangan Android local-first untuk pemasukan, pengeluaran, transfer, RAB bulanan atau tahunan, otomatisasi, resolving budget, audit immutable, laporan, serta backup terenkripsi.

## Model dana

- Satu akun memiliki dua kanal saldo, `Cash` dan `eBudget`.
- Hanya satu akun yang aktif sebagai ruang kerja transaksi pada satu waktu.
- Total aset merupakan gabungan saldo nyata kedua kanal pada seluruh akun aktif.
- Main Vault menyimpan dana tersedia yang belum dibooking.
- Kategori budget dapat memakai komposisi Cash dan eBudget.
- Transfer dapat memindahkan dana antar akun, antar kanal, atau keduanya.
- Seluruh perubahan finansial memakai jurnal append-only. Koreksi dan revert menghasilkan event baru.
- Invariant total dan per kanal diperiksa setelah operasi finansial.

## Data dan keamanan

- Database Room schema 12 dienkripsi menggunakan SQLCipher dan kunci acak yang dibungkus Android Keystore. Upgrade dari database plaintext lama dilakukan melalui staging tervalidasi agar database aktif tidak diganti bila validasi gagal.
- `.kronbackup` v2 memakai AES-256-GCM, PBKDF2-HMAC-SHA256, checksum, staging, dan validasi sebelum restore.
- Importer tetap membaca backup v1.
- Foto bukti disimpan terenkripsi pada penyimpanan privat aplikasi dan ikut dalam backup v2.
- Sinkronisasi Google Drive bersifat opsional. Snapshot dienkripsi sebelum disimpan pada `appDataFolder` akun yang dipilih.
- KRON tidak memakai backend, Firebase, analytics, iklan, atau billing Google Cloud.

Aturan kompatibilitas LTS terdapat pada [AGENTS.md](AGENTS.md). KRON 1.0.21 adalah baseline produksi yang harus selalu dapat ditingkatkan tanpa kehilangan data.

## Build

Persyaratan:

- JDK 17
- Android SDK 37
- Signing key KRON yang sama dengan rilis sebelumnya

Perintah verifikasi:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleRelease
```

APK release berada di `app/build/outputs/apk/release/KRON-1.4.7.apk`.

Konfigurasi signing dibaca dari `%USERPROFILE%/.android/kron-signing.properties`. Konfigurasi OAuth opsional dijelaskan pada [OAUTH_SETUP.md](OAUTH_SETUP.md). Jangan pernah menyimpan signing key, password, token, client secret, atau data finansial di repository.
