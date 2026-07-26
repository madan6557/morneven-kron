# KRON Changelog

## 1.5.0 - 2026-07-22

- Menambahkan general ledger double-entry schema 13 dengan akun aset Cash/eBudget, modal awal, pemasukan, pengeluaran, transfer, reversal, dan legacy clearing.
- Seluruh event finansial baru divalidasi agar debit sama dengan kredit. Subledger budget divalidasi seimbang per event.
- Menambahkan rantai hash append-only dan tanda tangan ECDSA P-256 dari Android Keystore, termasuk actor lokal, zona waktu, device ID, dan versi aplikasi.
- Event, baris kas, subledger budget, split, audit, seal, dan metadata bukti dilindungi trigger database dari update atau delete.
- Reversal tidak lagi mengubah event asli. Koreksi membuat grup atomik berisi event koreksi, reversal, event pengganti, alasan, dan snapshot audit.
- Foto bukti kamera disimpan dari byte asli tanpa kompresi. Asal CAMERA, GALLERY, atau LEGACY, checksum SHA-256, ukuran, waktu capture, dan lokasi opsional disimpan sebagai metadata immutable.
- Menambahkan Pusat Bukti untuk pemeriksaan kesehatan ledger, PDF pertanggungjawaban, paket `.kronevidence` terenkripsi, dan verifikasi checksum serta signature.
- Paket bukti memakai AES-256-GCM dan PBKDF2-HMAC-SHA256 600.000 iterasi. Nominal `Long` ditulis sebagai string dalam canonical JSON.
- CSV ditambah event ID, waktu efektif dan pencatatan, debit, kredit, kanal, reversal, hash lampiran, dan status audit.
- Sinkronisasi Google Drive kembali dapat dikendalikan dari Pengaturan dan data seluler menjadi perilaku bawaan ketika opsi Wi-Fi saja mati.
- Daftar operasional memakai komponen lazy dan detail bukti membatasi rendering awal agar tetap ringan pada dataset besar.
- UI hanya menampilkan `KRON 1.5.0` tanpa label Full Release atau LTS.
- Kontrak SQLCipher passphrase KRON 1.4.7 dipertahankan tanpa rekey. Raw-key 1.4.x tetap hanya menjadi mode baca historis.

## 1.5.23 - 2026-07-25

- Halaman Beranda sekarang menyembunyikan log sistem (otomatisasi, rollover, reversal, arsip, pemulihan) dari daftar aktivitas terbaru.
- Akses `ReceiptEntity.localPath` sekarang aman saat nilai path bukti hilang setelah penghapusan atau reversal.
- AuditDialog untuk event `ATTACH_EVIDENCE` sekarang menampilkan transaksi induk (judul, tipe, tanggal, dampak akun) dan bukti yang terhubung.
- AuditDialog sekarang menampilkan badge "Hilang" pada setiap bukti yang file-nya tidak ditemukan di disk.

## 1.5.22 - 2026-07-25

- Sync on-change debounce dikurangi dari 30 detik menjadi 7 detik agar data lebih cepat muncul di perangkat lain.
- Setiap log aktivitas sekarang menampilkan timestamp HH:mm:ss untuk tracking skala harian.
- Toggle "Izinkan screenshot" di Pengaturan > Privasi dan keamanan. Mengaktifkan toggle memerlukan autentikasi biometric atau PIN perangkat. Screenshot diblokir secara default saat nilai atau dialog sensitif ditampilkan.
- Card "Pengeluaran tak terduga" sekarang full width dan expandable. Ketuk untuk melihat rincian pengeluaran per kategori.
- CSV export ditingkatkan: kolom catatan dan jumlah split pada Activity Journal, section "KRON TRANSACTION SPLITS" per-event per-kategori, section "KRON SUMMARY" berisi ringkasan metrik (total event, pemasukan, pengeluaran, pengeluaran tak terduga, reversal, lampiran).

## 1.5.21 - 2026-07-25

- Fix: sync Drive tidak perlu restart — hapus raw SQL write ke sync_state, ganti dengan drop sync_write_guard trigger sebelum copy data dan recreate setelah commit. Sync_state hanya ditulis via Room DAO (stateStore.update) agar InvalidationTracker terpicu dan UI langsung update.

## 1.5.20 - 2026-07-25

- Fix: sync snapshot langsung (`applyPortableSnapshotDirectly`) — drop `append_only_*` trigger sebelum copy, `DELETE + INSERT` semua tabel dari restore_db, set `lastSyncedGeneration = localGeneration`, recreates trigger setelah commit. Status `SYNCED` ditulis sebelum copy agar `sync_write_guard` tidak blokir.
- Fix: vault unexpected expense sekarang kurangi bucket VAULT (bukan UNEXPECTED) di `KronRepository.kt`.
- Fix: tambah `Log.w` tracing di `DriveSyncCoordinator` dan `BackupManager` untuk diagnostik sync stuck.
- Panggil `database.kronDao().syncState()` setelah transaksi raw SQL commit untuk memaksa Room baca ulang `sync_state`.

## 1.5.18 - 2026-07-23

- Fix: CameraCaptureScreen bocor thread executor — tambah DisposableEffect untuk shutdown executor saat komposisi dibuang.

## 1.5.17 - 2026-07-23

- Backup tidak lagi gagal jika ada file lampiran yang hilang — lampiran tersebut dilewati.
- Restore backup dengan lampiran tidak lengkap tetap berjalan; receipt tanpa lampiran dikosongkan.

## 1.5.16 - 2026-07-23

- Pindahkan LedgerSlice, MetadataSlice, PreferenceSlice ke level top-level dengan indentasi konsisten.
- Hapus pengecekan isNotEmpty() redundan di assertInvariant().
- Sederhanakan parameter transferBookedChannel: fromAccountId+toAccountId menjadi accountId tunggal.
- Zero-out key array setelah validateEncrypted() selesai.

## 1.5.15 - 2026-07-23

- RESTORE_REVERSAL bukan lagi lifecycle event — bisa di-revert seperti transaksi biasa.
- Hapus prefiks "Revert: " dan "Dipulihkan: " dari title — badge sudah cukup.

## 1.5.14 - 2026-07-23

- Fix: deteksi restore reversal via parameter eksplisit `reversalRestored` di AuditDialog, dihitung di KronApp dari `state.activities` — tidak bergantung perbandingan di dialog.

## 1.5.13 - 2026-07-23

- Tipe `RESTORE_REVERSAL`: event restore reversal menggunakan type khusus agar duplikasi terdeteksi.
- Cegah duplikasi restore: DAO query `isEventRestored`, UI disabled + "Event sudah dipulihkan" jika sudah direstore.
- AuditDialog: `RESTORE_REVERSAL` masuk `lifecycleEvent` (read-only).
- ActivityScreen: badge DIPULIHKAN, filter SYSTEM, auditOnly untuk `RESTORE_REVERSAL`.

## 1.5.12 - 2026-07-23

- Pindah tombol "Pulihkan transaksi" ke log Audit bertipe REVERSAL (bukan event yang dibatalkan).
- Pesan sisa waktu dinamis: tampilkan hari/jam/menit tersisa, update tiap 60 detik. Tombol disabled jika >7 hari.

## 1.5.11 - 2026-07-23

- Camera: fullscreen + 1:1 square dari preview hingga hasil akhir.
- Hapus foto bukti sementara jika transaksi dibatalkan (dialog onDismiss).
- Restore reversal dalam 7 hari — AuditDialog menampilkan tombol "Pulihkan dalam 7 hari" untuk event yang sudah dibalik.
- Retensi foto bukti reversal 7 hari — file dihapus otomatis setelah 7 hari reversal.
- Fix: CameraX fullscreen Dialog dengan `usePlatformDefaultWidth = false`.

## 1.5.10 - 2026-07-23

- Fix: CameraX tertimpa dialog form transaksi — render CameraCaptureScreen di window Dialog terpisah dengan `lifecycleOwner` eksplisit (activity).

## 1.5.8 - 2026-07-22

- Fix: Ganti CameraX embedded preview dengan system camera intent (`TakePicture`) — tombol "Ambil gambar" di form transaksi sekarang langsung buka kamera system, tanpa overlay dialog yang nutup kamera.

## 1.5.7 - 2026-07-22

- Fix: Filter TRANSFER/CHANNEL_TRANSFER dari overview HomeScreen — hanya tampilkan transaksi finansial (INCOME, EXPENSE, REVERSAL, dll).
- Fix: Strikethrough title + sembunyikan nominal untuk transaksi reversal di HomeScreen.

## 1.5.6 - 2026-07-22

- Fix: Reset status SYNCING ke ERROR saat startup agar sync tidak stuck selamanya kalau proses sebelumnya crash/interrupt.
- Fix: Tambah timeout 180 detik di `syncNowLocked()` — sinkron yang menggantung (network timeout, process death) akan gagal dengan status ERROR + retryable, bukan stuck SYNCING.

## 1.5.5 - 2026-07-22

- Fix: Hapus `setOptOutIncludingGrantedScopes(true)` agar Play Services bisa silent refresh token tanpa minta otorisasi ulang tiap kali.
- Validasi grantedScopes hanya untuk request interaktif (koneksi pertama).

## 1.5.3 - 2026-07-22

- Layout Drive section: grup tombol normal vs destruktif, divider pemisah, tombol merah konsisten.

## 1.5.2 - 2026-07-22

- Fix: Credential Manager timeout "Google tidak merespon" di perangkat Xiaomi/HyperOS. Fallback otomatis ke AccountManager system picker setelah 15 detik -- tanpa permission tambahan.
- Menambahkan tombol "Hapus data Drive" di Pengaturan untuk menghapus seluruh snapshot KRON dari Google Drive (data lokal tetap aman).
- Mengubah timeout Credential Manager dari 30s ke 15s agar fallback lebih cepat.

## 1.5.1 - 2026-07-22

- Fix: Validasi seal jurnal untuk pemasukan (income) salah -- cuma bandingkan total split dengan cash outflow (negatif), padahal income punya cash inflow (positif). Setiap tambah pemasukan error "Total split tidak sama dengan nominal transaksi".
- Fix: Ganti `filter { it.amount < 0 }` dengan `sumOf { abs(it.amount) }` di `LedgerPostingEngine.validateSealable()`.

## 1.4.7 - 2026-07-22

- Menetapkan KRON 1.3.20 sebagai baseline penyimpanan stabil dan mengembalikan mode passphrase SQLCipher sebagai default permanen.
- Database passphrase 1.3.x, raw-key 1.4.x, plaintext, dan empty-key legacy diperiksa secara read-only sebelum akses tulis diaktifkan.
- Database valid menjadi sumber kebenaran. Profil kunci lama tidak lagi dapat memblokir database yang terbukti dapat dibuka dan lolos pemeriksaan integritas.
- Menambahkan profil kunci v2 yang menyimpan fingerprint, mode passphrase atau raw-key, serta kompatibilitas SQLCipher 4 setelah database berhasil dibuka.
- Menambahkan bootstrap barrier agar automation, WorkManager, Drive Sync, repository, dan UI operasional tidak membuka database sebelum pemeriksaan selesai.
- Upgrade dari versi lama mewajibkan backup pra-upgrade terenkripsi dengan recovery passphrase minimal 12 karakter melalui pemilih file Android.
- Backup baru memakai header `.kronbackup` v3. Importer v1 dan v2 tetap dipertahankan.
- Database, WAL, SHM, envelope, profil, dan lampiran dipertahankan ketika migration atau pembukaan ulang gagal.
- Menambahkan salinan pra-upgrade yang dapat dipulihkan tanpa uninstall, clear data, reset, atau downgrade.
- Menaikkan versi aplikasi dan label UI menjadi `KRON 1.4.7 Full Release LTS`.

## 1.4.6 - 2026-07-22

- Kunci database kini memiliki profil permanen yang mengikat envelope perangkat, fingerprint kunci, format raw hex, dan kompatibilitas SQLCipher 4.
- KRON tidak akan membuat kunci baru ketika database, WAL, staging, salinan pemulihan, restore tertunda, atau lampiran terenkripsi lama masih tersedia.
- Profil kunci baru hanya dikunci setelah database berhasil dibuka dan melewati validasi Room, integritas, foreign key, jurnal, Vault, Cash, eBudget, dan allocation.
- Database dengan envelope hilang, profil tidak cocok, atau artefak pemulihan tanpa database utama masuk ke mode pemulihan read-only tanpa penimpaan data.
- Tambahkan pengujian untuk ikatan profil kunci dan pencegahan pembuatan kunci ketika salinan pra-enkripsi masih tersedia.

## 1.4.5 - 2026-07-22

- Tambahkan pemeriksaan diagnostik read-only pada layar pemulihan database.
- Pemeriksaan tidak membuat kunci baru, tidak mengubah database, dan menampilkan kecocokan plaintext, empty key, envelope, raw key, passphrase, serta salinan pra-enkripsi.
- Rilis ini bersifat forward-only untuk memulihkan data penting tanpa uninstall, clear data, atau downgrade.

## 1.4.4 - 2026-07-22

- Upgrade tidak lagi membuat kunci perangkat baru saat database terenkripsi sudah ada tetapi envelope kunci lama tidak tersedia.
- Tambahkan pemulihan terkonfirmasi dari salinan pra-enkripsi yang tervalidasi. Database utama yang tidak dapat dibuka tetap disimpan sebagai salinan karantina.
- Layar pemulihan membedakan kegagalan format database dari kunci perangkat yang benar-benar tidak tersedia.

## 1.4.3 - 2026-07-22

- Perbaikan kritis kompatibilitas SQLCipher: database yang sebelumnya dibuat dengan raw key kini dibuka menggunakan spesifikasi raw key yang sama, bukan diperlakukan sebagai passphrase biasa.
- Upgrade akan memvalidasi database dengan raw key, passphrase legacy, dan plaintext secara terpisah. Database yang berhasil dikenali tetap dipertahankan sampai aplikasi selesai dibuka.

## 1.4.2 - 2026-07-22

- Perbaikan upgrade database SQLCipher lama: database yang dapat dibuka dengan passphrase kosong dari rilis sebelumnya kini diekspor ulang secara atomik ke kunci perangkat KRON saat ini.
- Database plaintext, SQLCipher modern, dan SQLCipher legacy dideteksi melalui integrity check sebelum migrasi. Database asli tetap menjadi rollback hingga aplikasi berhasil dibuka.

## 1.4.1 - 2026-07-22

- Perbaikan upgrade database: deteksi SQLite plaintext tidak lagi bergantung pada header 16 byte yang kaku. Database lama yang dapat dibuka divalidasi melalui SQLite integrity check sebelum dienkripsi secara atomik.
- Pesan pemulihan database dibuat lebih tepat untuk membedakan database rusak, database terenkripsi, dan kunci yang tidak cocok.

## 1.4.0 - 2026-07-22

- Full Release: meningkatkan schema database ke 12 dengan migration non-destruktif yang mengisolasi data per akun. Data lama yang tidak dapat dipastikan pemiliknya dipindahkan ke akun nonaktif `Data KRON Lama` tanpa mengubah jurnal atau audit.
- Backup dan restore: versi Room aktif menjadi satu-satunya sumber versi schema, backup dibuat dan diverifikasi di staging privat sebelum disalin melalui pemilih file, dan database staging sekarang benar-benar diekspor ke SQLCipher sebelum swap atomik.
- Drive: pemilih akun Credential Manager berjalan sebelum otorisasi `drive.appdata`; sinkronisasi seluler diizinkan secara bawaan dan opsi `Gunakan Wi-Fi saja` bersifat pilihan.
- Integritas: invariant diperiksa untuk setiap akun dan kanal Cash/eBudget. Transfer antar akun selalu memindahkan Main Vault tujuan, sedangkan dana budget terbooking tetap dibatasi di akun aktif yang sama.
- UX: penyaring laporan tetap account-scoped, teks transfer diperjelas, dan label versi dalam aplikasi menjadi `KRON 1.4.0 Full Release LTS`.


## 1.3.19 - 2026-07-21

- UI: Theme container -- LazyRow sekarang fillMaxWidth (rata kiri/kanan dengan konten lain).
- UI: Icon "Sistem" -- ganti BrightnessAuto dengan Settings (gear).

## 1.3.18 - 2026-07-21

- UI: BudgetHistoryDialog -- tambah grafik batang planned vs spent per periode.
- Grafik: bar terang = rencana, bar warna = terpakai (hijau jika sesuai, merah jika overspent).

## 1.3.17 - 2026-07-21

- Fitur: Riwayat budget per portfolio -- semua periode tercatat dengan planned, spent, remaining.
- BudgetScreen: tombol "Riwayat" di kartu portfolio aktif dan arsip.
- BudgetHistoryDialog: daftar semua periode, bisa tap "Detail periode ini".

## 1.3.16 - 2026-07-21

- UI: Theme chips -- icon benar per mode (sun, moon, auto) dan ditampilkan di semua chip.
- UI: Theme container -- LazyRow padding rata kiri/kanan dengan konten HudCard lain.
- UI: Light mode colors -- primaryContainer, surface, background dimuted agar tidak silau.

## 1.3.15 - 2026-07-21

- Fix: Tombol foto bukti hanya muncul untuk transaksi INCOME dan EXPENSE di dialog audit. Transfer, portfolio booking, resolve, dan jenis lainnya tidak perlu lampiran foto.

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
