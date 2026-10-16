# KRON

Current release: KRON 1.7.28.

KRON adalah aplikasi manajemen keuangan pribadi dan tim Android *local-first* dengan arsitektur bebas backend (*zero backend server*). KRON dirancang untuk pencatatan transaksi presisi tinggi (pemasukan, pengeluaran, transfer), alokasi RAB multi-periode, manajemen hutang/piutang, otomatisasi aturan berulang, audit *immutable*, laporan interaktif visual, kolaborasi akun Team di Google Drive, serta backup terenkripsi tingkat militer.

## Fitur Utama

- **Model Dana Dwi-Kanal**: Setiap akun mengelola dua kanal saldo fisik dan digital: `Cash` dan `eBudget`.
- **Main Vault & Budgeting**: Menyimpan dana likuid bebas sebelum dialokasikan ke pos kategori budget dengan fleksibilitas komposisi kanal.
- **Akun Team & Kolaborasi Drive**: Sinkronisasi multi-perangkat antar anggota tim secara terdesentralisasi melalui snapshot terenkripsi di Google Drive privat tanpa perantara server pihak ketiga.
- **Manajemen Hutang & Piutang**: Pencatatan counterparty, tanggal jatuh tempo, histori cicilan, dan rekapitulasi posisi piutang/hutang.
- **Grafik Interaktif & Laporan Finansial**: Visualisasi arus kas harian/bulanan dengan diagram batang (Bar Chart) adaptif, grafik tren akumulasi net kontinu, penanda tanggal sumbu X, serta kartu audit detail transaksi per titik.
- **Mata Uang KRON (KRM) & Grafik Kurs**: Mata uang sintetis Morneven dengan fluktuasi pasar, pencatatan kronologis riwayat nilai tukar, filter rentang multi-periode (7 hari, 30 hari, 90 hari, 1 tahun, Custom), label mengambang (floating tooltip), serta kartu inspeksi titik real-time.
- **Widget Layar Utama Android**: Widget interaktif di homescreen untuk intip saldo cepat, aksi instan catat transaksi, dan toggle privasi sensor saldo.
- **Tema Ganda Profesional**: Mode gelap *Evening* yang imersif dan mode terang *After Dawn* dengan kanvas hangat bergradien aurora fajar serta elevasi kartu bertingkat.
- **Evaluator Rumus Terintegrasi**: Kalkulator cepat langsung pada baris nominal input transaksi.

## Arsitektur & Integritas Finansial

- **Double-Entry General Ledger**: Seluruh pergerakan finansial dicatat secara *append-only*. Reversal atau koreksi tidak pernah menghapus data lama, melainkan menerbitkan entri penyeimbang baru.
- **Financial Invariant Check**: Integritas total saldo, kanal Cash, kanal eBudget, alokasi budget, dan batas kas diverifikasi secara otomatis pada setiap transaksi.
- **Virtualisasi Render Lazy**: Antarmuka timeline transaksi menggunakan pemuatan bertahap berbasis *lazy column* sehingga tetap responsif pada data ribuan jurnal.

## Data dan Keamanan

- **Enkripsi Database Tingkat Lanjut**: Room database schema 20 dienkripsi penuh menggunakan SQLCipher 4 dengan passphrase bytes acak yang dibungkus oleh Android Keystore (`kron.database.wrap.v1`).
- **Paket Backup Terenkripsi**: Format `.kronbackup` diamankan dengan AES-256-GCM dan derivasi kunci PBKDF2-HMAC-SHA256 (600.000 iterasi), mencakup data transaksi, foto bukti, pengaturan, serta riwayat kurs KRON.
- **Pusat Bukti & Rantai Segel (Hash Chain)**: Foto struk/bukti transaksi disimpan terenkripsi di penyimpanan privat, diverifikasi dengan tanda tangan digital ECDSA P-256, dan dapat diekspor ke format arsip `.kronevidence` maupun dokumen audit PDF.
- **Privasi Total**: KRON beroperasi sepenuhnya di perangkat pengguna (*local-first*). Tidak menggunakan analitik pelacak, iklan, Firebase, atau dependensi cloud berbayar.

Aturan kompatibilitas LTS terdapat pada [AGENTS.md](AGENTS.md). Perubahan pada integrasi Drive wajib mengikuti [Drive Sync Change Control](docs/DRIVE_SYNC_CHANGE_CONTROL.md). KRON 1.0.21 adalah baseline kompatibilitas permanen yang wajib selalu dapat di-upgrade tanpa kehilangan data.

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

File APK release yang ditandatangani otomatis dihasilkan pada:
`app/build/outputs/apk/release/KRON-1.7.28.apk`

Konfigurasi tanda tangan rilis dibaca dari `%USERPROFILE%/.android/kron-signing.properties`. Konfigurasi OAuth opsional untuk integrasi Google Drive dijelaskan pada [OAUTH_SETUP.md](OAUTH_SETUP.md). Jangan pernah menyimpan file signing key, password, token, client secret, atau data finansial ke dalam repositori.
