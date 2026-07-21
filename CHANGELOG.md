# KRON Changelog

## 1.3.14 - 2026-07-21

- Fix: Ganti intent-based camera dengan CameraX (preview + capture). Kamera tidak lagi force close.
- Breaking change: Izin CAMERA diperlukan untuk mengambil foto.

## 1.3.13 - 2026-07-21

- Fix: Force close kamera -- ganti ActivityResultContracts.TakePicture dengan StartActivityForResult + MediaStore.ACTION_IMAGE_CAPTURE. Lebih kompatibel antar perangkat.

## 1.3.12 - 2026-07-21

- F3: Tombol kamera (TakePicture) dan galeri (PickVisualMedia) di dialog input Pengeluaran dan Pemasukan.
- F3: Foto langsung dikompresi (resize 1920px + JPEG80 + EXIF) dan dienkripsi setelah transaksi tercatat.
- Fix: Force close kamera -- pindahkan ActivityResultLauncher ke KronApp level (konflik dua launcher).
- Fix: Ganti ActivityResultContracts.TakePicture dengan StartActivityForResult + MediaStore.ACTION_IMAGE_CAPTURE.
- Fix: FileProvider path -- simpan file kamera di subdirektori receipts/ sesuai file_paths.xml.

## 1.3.11 - 2026-07-21

## 1.3.10 - 2026-07-21

- Fix: Unexpected expense now reduces vault (uses VAULT bucket instead of UNEXPECTED).
- Fix: Flow chain resilience - add retry(Long.MAX_VALUE) on uiState combine to prevent silent crash on account switch.
- Fix: Migration 10->11 resets accountId=0 for migrated data -- existing data from v1.1.5 is visible in all accounts.
- Fix: DAO queries use accountId IN (0, :accountId) and repository filters include `accountId == 0L` untuk mencegah data hilang saat upgrade dari versi lama.

## 1.3.7 - 2026-07-21

- F2: Akun terpisah penuh untuk portfolio, aktivitas, vault, unallocated, rollover, dan report.
- F3: Kamera ambil foto bukti via TakePicture (delegasi, tanpa izin CAMERA).
- F3: Kompresi resize 1920px + JPEG80 + EXIF (DateTimeOriginal, GPS) via ImageCompressor.
- F3: Gallery via PickVisualMedia, kamera via TakePicture, dua tombol di AuditDialog.
- F7: accountId ditambahkan ke ActivityEventEntity, PortfolioEntity, BudgetJournalLineEntity.
- Migration 6->7 (ALTER receipts: capturedAt, latitude, longitude).
- Migration 7->8 (ALTER 3 tabel: accountId INTEGER NOT NULL DEFAULT 0).
- Migration 8->9 (Backfill accountId dari cash_journal_lines dan portfolio chain).
- Migration 9->10 (Backfill budget_journal_lines tersisa dari activity_events).
- Isolasi data: semua DAO read query difilter oleh akun aktif via flatMapLatest.
- Fix: totalCashAssets/totalEBudgetAssets di KronUiState pakai activeAccountBalance.
- Fix: rules (jadwal transaksi) difilter per akun (sebelumnya bocor antar akun).
- Drive sync dinonaktifkan.

## 1.2.2 - 2026-07-20

- Fix: Pengeluaran tak terduga tidak terhitung di HomeScreen karena query `observeCashflow()` tidak menyertakan tipe `UNEXPECTED_EXPENSE`.

## 1.2.1 - 2026-07-20

- F1: Indikator perubahan (nominal + persentase) di ringkasan laporan dan mode Tabel. Bandingkan periode sebelumnya dengan durasi sama. Warna hijau/merah sesuai arah. Edge cases: "Baru" untuk kategori baru, sembunyi jika sama.
- F1: Mode Tabel urutan baru ke lama (terbalik dari sebelumnya).
- F4: Kategori pengeluaran tak terduga hanya menampilkan 7 kategori seed EXPENSE tanpa campuran portfolio. Opsi "Buat kategori custom..." di catatan transaksi.
- F4: Pengeluaran tak terduga kini bisa memilih kanal Cash atau eBudget.
- F6: Tata letak ulang BudgetDetailDialog: badge baris terpisah, nama kategori full width, Rencana/Terpakai/Sisa label-value, compactIdr.
- Form dialog: tambah `imePadding()` agar kolom input tidak tertutup keyboard.
- Fix: Nama file APK release otomatis mengikuti versi.

## 1.1.5 - 2026-07-19

- Hapus FLAG_SECURE saat valuesVisible (screenshot bebas di layar laporan).
- Warna nominal Net di laporan jadi KronGold (emas) sesuai warna grafik.

## 1.1.4 - 2026-07-19

- Hapus network check dari connect() dan reauthorizeCurrent() -- koneksi Drive tidak lagi diblokir oleh jaringan metered atau koneksi internet apapun.

## 1.1.3 - 2026-07-19

- Bypass Credential Manager untuk koneksi Drive. Pakai AuthorizationClient langsung dengan account picker bawaan Google Play Services. Tidak perlu koneksi internet ke server Google untuk memilih akun.
- Ambil email akun dari userinfo API setelah otorisasi selesai.

## 1.1.2 - 2026-07-19

- Memperbaiki chart mode Saldo agar hanya menampilkan 1 garis (Net).
- Menampilkan toggle "Hanya jaringan tanpa meter" sebelum Drive terhubung.

## 1.1.1 - 2026-07-19

- Memperbaiki pemilih akun Google OAuth yang tidak muncul di jaringan seluler (timeout 30 detik, logging error, pesan error eksplisit).
- Menambahkan mode laporan akumulatif (Saldo) sebagai default dengan toggle Arus Kas/Saldo.
- Memperbaiki propagasi error IllegalStateException dari account selector agar tidak ditelan catch generik.
- Menambahkan logging untuk diagnosis kegagalan Credential Manager.

## 1.1.0 LTS - 2026-07-18

- Menetapkan KRON 1.1.0 sebagai Full Release LTS dengan baseline kompatibilitas data mulai 1.0.21.
- Menambahkan Room schema 6, migration 5 ke 6, rekonstruksi migration 3 ke 4, exported schema, dan gate migration non-destruktif.
- Mengenkripsi database menggunakan SQLCipher dengan data key 256-bit yang dibungkus Android Keystore serta rollback bila upgrade gagal.
- Menambahkan `.kronbackup` v2 dengan lampiran, checksum, batas ukuran, perlindungan ZIP berbahaya, staging, dan importer backup v1.
- Menambahkan penyimpanan foto bukti terenkripsi, proteksi screenshot dan overlay, notifikasi privat, larangan cleartext, R8, serta ekspor CSV yang aman dari formula injection.
- Menambahkan fondasi sinkronisasi Google Drive opsional pada `appDataFolder` akun pilihan, enkripsi end-to-end, retention sepuluh snapshot, status, retry, dan Conflict Center tanpa silent overwrite.
- Merapikan form layar penuh, pencarian dan filter timeline, laporan adaptif, grafik interaktif, tabel aksesibel, Pengaturan, empty state, dan tampilan font besar.
- Menampilkan `KRON 1.1.0 Full Release LTS` secara dinamis pada aplikasi.

## 1.0.21 - 2026-07-18

- Menambahkan tab Aktif dan Arsip untuk budget serta akun, lengkap dengan pemulihan tanpa menghapus riwayat.
- Memisahkan tindakan Jeda, Lanjutkan, Arsipkan, Pulihkan, dan Pulihkan lalu Aktifkan.
- Menyelesaikan sisa budget ke Main Vault sesuai kanal saat arsip dan menolak arsip jika masih ada kategori minus.
- Menjeda aturan otomatis terkait saat arsip dan hanya melanjutkan aturan yang dijeda oleh proses arsip.
- Menambahkan migration Room versi 5 yang non-destruktif serta event audit ARCHIVE dan RESTORE.
- Menggunakan format nominal kata Indonesia adaptif pada Posisi Keuangan dengan dialog nominal lengkap saat diketuk.

## 1.0.20 - 2026-07-17

- Mengubah satu akun agar selalu memiliki saldo Cash dan eBudget secara terpisah.
- Menambahkan alokasi manual antar kanal dalam akun yang sama dan transfer lintas akun serta kanal.
- Menambahkan satu akun aktif sebagai sumber transaksi utama dan pilihan untuk mengganti akun aktif.
- Memindahkan identitas kanal ke setiap baris jurnal kas agar saldo per kanal dapat direkonstruksi akurat.
- Mengganti database testing ke skema versi 4 tanpa membawa data lama.

## 1.0.19 - 2026-07-17

- Menambahkan CRUD akun: edit metadata dan arsipkan akun dengan konfirmasi serta alasan.
- Jurnal akun tetap dipertahankan; akun bersaldo tidak dapat diarsipkan.
- Validasi perubahan kanal mencegah perubahan saat saldo masih tidak nol.

## 1.0.18 - 2026-07-17

- Menyesuaikan arah slide halaman berdasarkan urutan menu navigasi.
- Perpindahan ke menu di kanan bergerak dari kanan ke kiri, dan sebaliknya.

## 1.0.17 - 2026-07-17

- Mengganti transisi antar halaman dari fade menjadi slide horizontal ringan.

## 1.0.16 - 2026-07-17

- Merapikan hierarchy, filter, grafik, kartu aset, dan rincian budget pada halaman Laporan.
- Membuat filter rentang dapat digulir agar tidak meluber pada layar kecil.
- Menyingkat nominal besar pada Posisi Keuangan hingga satuan juta, miliar, triliun, dan kuadriliun.
- Merapikan fallback form pengeluaran ketika belum ada budget aktif.

## 1.0.15 - 2026-07-17

- Memperketat pengeluaran agar saldo akun tidak dapat menjadi negatif.
- Budget yang belum dapat dibooking karena kas kurang tetap berstatus DRAFT/UNDERFUNDED dan dapat dibooking kemudian melalui tombol Booking dari Main Vault.

## 1.0.14 - 2026-07-17

- Merapikan form pengeluaran agar hanya menampilkan pemilihan alokasi budget baru.

## 1.0.13 - 2026-07-17

- Menambahkan throttling autentikasi, target budget pemasukan, dan mode pengeluaran tak terduga.
- Menghapus FAB pengeluaran global dan memperjelas alur form pengeluaran serta transfer.
- Menambahkan grafik cash flow interaktif, filter rentang, dan rincian aktual per budget.
- Menambahkan konfirmasi beralasan untuk menghentikan portfolio atau jadwal otomatis.
- Menampilkan nomor versi dinamis pada Pengaturan.

## 1.0.12 - 2026-07-17

- Menambahkan tombol Hentikan jadwal pada transaksi otomatis.
- Penghentian aturan otomatis tercatat sebagai event dan audit tanpa menghapus occurrence sebelumnya.

## 1.0.11 - 2026-07-17

- Menambahkan tombol Hentikan portfolio.
- Portfolio yang dihentikan tidak membuat periode baru, sementara seluruh periode dan jurnal lama tetap dipertahankan.
- Penghentian tercatat sebagai event sistem dan audit.

## 1.0.10 - 2026-07-17

- Setelah autentikasi app lock, visibilitas mengembalikan pilihan terakhir jika fitur pengingat aktif.
- Form koreksi budget dipindahkan ke dalam kartu kategori yang sedang dikoreksi.
- Jadwal otomatis diberi penjelasan bahwa revert occurrence tidak membatalkan aturan jadwal.

## 1.0.9 - 2026-07-17

- Memperbaiki penyimpanan preferensi "Ingat visibilitas" agar saat dinonaktifkan selalu kembali tersembunyi dan tidak memakai nilai lama.

## 1.0.8 - 2026-07-17

- Menambahkan tampilan detail setiap periode budget.
- Menambahkan koreksi nominal budget melalui jurnal koreksi dan audit.
- Koreksi tidak menghapus jurnal lama dan memeriksa saldo Main Vault.

## 1.0.7 - 2026-07-17

- Memasang aset logo launcher KRON pada layar kunci.
- Menghapus monogram teks sementara dari komponen logo layar kunci.

## 1.0.6 - 2026-07-17

- Mengganti background layar kunci menjadi warna solid sesuai tema.
- Memperbaiki warna judul layar kunci agar selalu memakai warna `onSurface`.
- Menjadikan versi 1.0.6 sebagai baseline rekaman rollback pertama.
