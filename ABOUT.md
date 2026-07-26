# KRON

**Setiap rupiah punya jejak.**

KRON adalah aplikasi pencatatan keuangan pribadi untuk Android yang bekerja sepenuhnya secara lokal tanpa memerlukan koneksi internet, server, atau akun cloud. Dirancang untuk pengguna yang mengutamakan privasi, kepemilikan data penuh, dan akuntabilitas finansial.

## Kenapa KRON?

Kebanyakan aplikasi keuangan menyimpan data pengguna di server pihak ketiga. KRON melakukan sebaliknya: semua data tetap di perangkat Anda, dienkripsi, dan hanya Anda yang mengontrolnya.

## Fitur Utama

- **Pencatatan Transaksi** -- pemasukan, pengeluaran, transfer antar akun, dan transfer antar kanal (Cash / eBudget)
- **Sistem Budget (RAB)** -- portofolio bulanan atau tahunan, alokasi per kategori, booking dari vault, rollover sisa budget, dan resolving ketika terjadi kelebihan anggaran
- **Otomatisasi** -- transaksi berulang yang dijadwalkan otomatis berdasarkan aturan yang ditentukan
- **Bukti Transaksi** -- lampirkan foto dari kamera atau galeri ke setiap transaksi, disimpan terenkripsi dengan integritas SHA-256
- **Laporan & Ekspor** -- laporan arus kas, perbandingan periode, grafik, detail pengeluaran tak terduga, dan ekspor CSV
- **Backup Terenkripsi** -- file `.kronbackup` dengan enkripsi AES-256-GCM dan dapat dipulihkan antar versi
- **Sinkronisasi Google Drive** -- opsional, terenkripsi ujung-ke-ujung, tanpa billing
- **Audit Immutable** -- setiap perubahan finansial tercatat sebagai event baru dengan jejak kriptografis yang tidak dapat diubah

## Privasi & Keamanan

- Database dienkripsi dengan SQLCipher dan kunci Android Keystore
- Cadangan dienkripsi dengan AES-256-GCM dan PBKDF2 600.000 iterasi
- Tidak ada backend, tidak ada Firebase, tidak ada analytics, tidak ada iklan
- Kunci aplikasi dengan biometrik atau PIN
- Perlindungan tangkapan layar dan visibilitas nilai
- Seluruh fitur utama berfungsi tanpa koneksi internet

## Model Dana

KRON menggunakan sistem dua kanal per akun:

- **Cash** -- dana tunai atau rekening bank
- **eBudget** -- dana anggaran elektronik
- **Main Vault** -- penyimpanan dana yang belum dialokasikan ke kategori budget
- **Reserve Rollover** -- sisa budget dari periode sebelumnya yang dibawa ke periode berikutnya

## Kompatibilitas

KRON 1.0.21 adalah baseline produksi permanen. Setiap rilis baru harus membuktikan kemampuan upgrade dari versi sebelumnya tanpa kehilangan data.
