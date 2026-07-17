# KRON

KRON adalah aplikasi pencatatan keuangan Android local-first untuk pemasukan, pengeluaran, transfer, RAB bulanan atau tahunan, otomatisasi, resolving budget, audit, backup terenkripsi, dan laporan CSV.

## Model dana

- Setiap akun memakai kanal `Cash` atau `eBudget`.
- Total aset adalah gabungan saldo nyata kedua kanal.
- Main Vault menyimpan dana tersedia yang belum dibooking.
- Setiap kategori dapat memakai komposisi Cash dan eBudget dari 0 sampai 100 persen.
- Perpindahan kanal memindahkan saldo akun nyata dan booking kategori dalam satu event atomik.
- Semua perubahan finansial memakai jurnal append-only. Edit dan revert menghasilkan event baru.
- Invariant total dan per kanal diperiksa setelah operasi finansial.

## Build

Persyaratan:

- JDK 17
- Android SDK 36
- Build Tools 36.0.0

Perintah verifikasi:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleRelease
```

APK release berada di `app/build/outputs/apk/release/KRON-1.0.0.apk`.

Konfigurasi signing dibaca dari `%USERPROFILE%/.android/kron-signing.properties`. Keystore harus disimpan dengan aman karena diperlukan untuk memperbarui instalasi KRON tanpa kehilangan jalur upgrade.
