# KRON

Current release: KRON 1.7.30.

KRON adalah aplikasi manajemen keuangan pribadi dan tim di Android dengan arsitektur *local-first* tanpa server backend. KRON mendukung pencatatan transaksi (pemasukan, pengeluaran, transfer), alokasi RAB multi-periode, manajemen hutang/piutang, jadwal transaksi berulang, audit *append-only*, laporan grafik, kolaborasi akun Team melalui Google Drive, dan backup lokal terenkripsi.

## Fitur Utama

- **Model Dana Dwi-Kanal**: Setiap akun mengelola dua kanal saldo: `Cash` dan `eBudget`.
- **Main Vault & Budgeting**: Menyimpan dana likuid di Main Vault sebelum dialokasikan ke kategori budget per periode.
- **Akun Team & Kolaborasi Drive**: Sinkronisasi multi-perangkat antar anggota tim melalui snapshot terenkripsi di Google Drive pengguna.
- **Manajemen Hutang & Piutang**: Pencatatan pihak terkait, jatuh tempo, bunga opsional, histori cicilan, dan status pelunasan.
- **Grafik & Laporan Finansial**: Ringkasan arus kas harian/bulanan dengan grafik batang dan garis akumulasi net, filter periode, serta rincian transaksi per titik tanggal.
- **Multi-Mata Uang & Grafik Kurs**: Konversi tampilan widget ke IDR, KRM (Kron Morneven), USD, EUR, SGD, dan JPY. Mendukung pembaruan kurs pasar setiap 30 menit, simulasi fluktuasi 4-tingkat dan gelombang pasar untuk KRM, serta grafik riwayat kurs hingga 1 tahun untuk seluruh mata uang.
- **Widget Layar Utama Android**: Widget untuk memantau saldo, mengganti mata uang tampilan, menyembunyikan nominal, dan membuka dialog transaksi cepat.
- **Tema Gelap & Terang**: Pilihan tema gelap (*Evening*), tema terang (*After Dawn*), atau mengikuti pengaturan sistem.
- **Kalkulator Input Nominal**: Kalkulator bawaan pada kolom input nominal transaksi.

## Arsitektur & Integritas Finansial

- **Double-Entry General Ledger**: Seluruh mutasi finansial dicatat secara *append-only*. Koreksi atau pembatalan menggunakan entri jurnal pembalik (*reversal*) tanpa menghapus data historis.
- **Financial Invariant Check**: Keseimbangan total saldo, kanal Cash, kanal eBudget, dan alokasi budget diperiksa pada setiap transaksi.
- **Render List Bertahap**: Daftar riwayat transaksi dimuat menggunakan `LazyColumn` agar tetap ringan saat jumlah jurnal bertambah.

## Data dan Keamanan

- **Enkripsi Database**: Database Room (schema 20) dienkripsi menggunakan SQLCipher 4 dengan kunci yang dibungkus melalui Android Keystore (`kron.database.wrap.v1`).
- **Paket Backup Terenkripsi**: File `.kronbackup` dienkripsi menggunakan AES-256-GCM dan PBKDF2-HMAC-SHA256 (600.000 iterasi), mencakup database, foto bukti, pengaturan, dan riwayat kurs.
- **Pusat Bukti & Rantai Hash**: Lampiran bukti transaksi disimpan terenkripsi, ditandatangani dengan ECDSA P-256, dan dapat diekspor ke `.kronevidence` atau PDF.
- **Privasi Lokal**: Berjalan penuh di perangkat tanpa analitik pelacak, iklan, atau layanan cloud berbayar.

Aturan kompatibilitas rilis terdapat pada [AGENTS.md](AGENTS.md). Perubahan pada integrasi Drive mengikuti [Drive Sync Change Control](docs/DRIVE_SYNC_CHANGE_CONTROL.md). KRON 1.0.21 adalah baseline kompatibilitas permanen yang wajib dapat di-upgrade tanpa kehilangan data.

## Build

Persyaratan lingkungan pengembangan:

- JDK 17
- Android SDK 37 (Build Tools 34.0.0+)
- Gradle 8.13+
- Signing key KRON (`kron-release.jks`)

Perintah verifikasi pengujian:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleRelease
```

File APK release yang ditandatangani dihasilkan pada:
`app/build/outputs/apk/release/KRON-1.7.29.apk`

Konfigurasi tanda tangan rilis dibaca dari `%USERPROFILE%/.android/kron-signing.properties`. Konfigurasi OAuth opsional untuk Google Drive dijelaskan pada [OAUTH_SETUP.md](OAUTH_SETUP.md). Jangan menyimpan file signing key, password, token, client secret, atau data finansial ke dalam repositori.
