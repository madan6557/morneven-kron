# KRON Update Plan

> Dokumen ini mencatat diskusi perencanaan fitur dan perbaikan untuk aplikasi KRON.
> Di-update secara berkala seiring perkembangan diskusi.

---

## Pengenalan KRON

**KRON** adalah aplikasi pencatatan keuangan Android local-first untuk pemasukan, pengeluaran, transfer, RAB bulanan/tahunan, otomatisasi, resolving budget, audit immutable, laporan, serta backup terenkripsi.

**Arsitektur**: MVVM + Single-Activity + Jetpack Compose + Room (SQLCipher) + Hilt DI
**Target pengguna**: Individu dan keluarga yang ingin mencatat keuangan pribadi secara privat dan aman
**Filosofi**: Local-first, no backend, end-to-end encrypted, open data via backup/sync
**Tech stack**: Kotlin, Android SDK 37, Jetpack Compose + Material3, Room + SQLCipher, Hilt, WorkManager, DataStore, Biometric, Google Drive API
**Versi saat ini**: 1.4.7 (versionCode 59, baseline penyimpanan stabil 1.3.20, bootstrap data continuity)

**Fitur utama yang sudah ada:**
- Buku besar double-entry dengan jurnal append-only
- Multi-akun dengan dual-channel (Cash & eBudget)
- Portfolio budgeting (MONTHLY/YEARLY) dengan alokasi per kategori, rollover, resolve deficit
- Aturan transaksi otomatis (recurring income/expense) via WorkManager
- SQLCipher database encryption + Android Keystore
- Biometric app lock + auth throttling
- Google Drive sync end-to-end encrypted (opsional)
- Local backup/restore .kronbackup v2 (AES-256-GCM + PBKDF2)
- Kuitansi/foto bukti terenkripsi
- Laporan cash flow + CSV export
- Pelarangan hard-delete jurnal keuangan, audit trail immutable

---

## Status Diskusi

- **Tanggal Mulai**: 2026-07-20
- **Fase**: Implementasi lanjutan -- perbaikan UI/UX
- **Versi Saat Ini**: 1.4.7 (versionCode 59)

---

## Status Fitur Saat Ini

- **Sync Google Drive (privat)**: TIDAK BERFUNGSI. Koneksi gagal di Credential Manager — selalu timeout ("Google tidak merespon") padahal webClientId sudah benar, GCP sudah diisi scope `drive.appdata`, test user sudah ditambahkan, internet aktif, Google Play Services versi 26. Kemungkinan Credential Manager tidak kompatibel penuh dengan perangkat. Belum ada solusi fix.
- **OAuth GCP config**: Web client ID `677791134689-rb53arqc2k6ror17oku0pvm3nmfrcnkc.apps.googleusercontent.com` cocok dengan `kron-google.properties`. Android client `677791134689-onu28oduh8h4ql632h8aphh4nad1qjbr.apps.googleusercontent.com` untuk Play Services AuthorizationClient. Kedua scope `drive.appdata` (sync private, ekstensi `.krondrive`) dan `drive.file` (team/share, ekstensi `.kronshare`) sudah ditambahkan di OAuth consent screen. Status masih `Testing`.
- **Rencana sync private**: Jika Credential Manager tidak bisa dipakai, opsi alternatif adalah bypass langsung ke Play Services AuthorizationClient (tanpa pemilih akun via Credential Manager).
- **Backup/Restore (.kronbackup)**: TERVALIDASI DI STAGING. Backup v1/v2 tetap diterima, kandidat restore dimigrasikan, diperiksa, dienkripsi SQLCipher, lalu dijadwalkan untuk swap atomik saat cold start.
- **Kamera (foto bukti)**: SUDAH BERFUNGSI. Menggunakan CameraX (bukan delegasi intent). Izin CAMERA diminta runtime.
- **Isolasi akun**: Setiap akun punya portfolio, transaksi, Vault, receipt, dan laporan sendiri. Data lama yang ambigu ditempatkan pada akun nonaktif `Data KRON Lama`, tidak ditampilkan pada akun operasional.

---

## Daftar Permintaan Update

### P1 - Prioritas Tinggi

- [x] **F1 - Laporan: Indikator perubahan (+ / -) dan (+^ / -v)**
  - **Status**: SELESAI (v1.2.x)
  - **Lokasi**:
    - Ringkasan utama (Masuk/Keluar/Net) -- bandingkan periode sebelumnya dengan durasi sama
    - Tiap baris di mode **Tabel** per periode -- bandingkan dengan bucket sebelumnya
  - **Format**: Nominal + Persentase, keduanya
  - **Aturan simbol**:
    - **Masuk**: `+^` (naik, hijau) / `-v` (turun, merah) -- gaya saham
    - **Keluar**: `+^` (naik/nambah keluar, merah) / `-v` (turun/berkurang keluar, hijau) -- terbalik dari Masuk karena naik itu buruk
    - **Net**: tanpa +^ / -v, cukup `+` / `-` saja karena arah sudah jelas dari tanda nominal. Warna hijau/merah ikut nilai.
  - **Format persentase**: `((current - previous) / |previous|) * 100`, dibulatkan 1 desimal (contoh: +22,5%)
  - **Visual ringkasan**:
    ```
    ┌──────────────────────────────┐
    │  MASUK                       │
    │  Rp 12.500.000               │ ← titleLarge
    │  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─     │ ← subtle divider (primary alpha 0.18)
    │  +^ Rp 2,3 jt  (+22,5%)     │ ← KronGreen, bodySmall
    └──────────────────────────────┘
    ```
  - **Visual tabel** (mode Tabel):
    ```
    ┌───────────────────────────────────────┐
    │  1 Jul 2026                           │
    │  Masuk   Rp 500.000     (+5%)   ↑     │ ← green
    │  Keluar  Rp 200.000     (-2%)   ↓     │ ← red
    │  Net     Rp 300.000     (+8%)   ↑     │ ← gold
    └───────────────────────────────────────┘
    ```
  - **Aturan compactIdr()** untuk nominal change:
    - >= Rp 1.000.000 -> compactIdr (Rp 2,3 jt)
    - >= Rp 1.000.000.000 -> compactIdr (Rp 1,5 M)
    - >= Rp 1.000.000.000.000 -> compactIdr (Rp 7,5 T)
    - < Rp 1.000.000 -> full format (Rp 750.000)
  - **Edge cases**:
    - Previous period **tidak ada sama sekali** (app baru, atau data < durasi range): sembunyikan baris indikator
    - Previous period **ada tapi total 0**: tampilkan label **"Baru"** warna KronGreen, bukan persentase (hindari division by zero)
    - Previous == current (selisih Rp 0): sembunyikan baris perubahan (hemat ruang)
  - **Data layer**: Perluas filter `cashFlowEvents` di `ReportsScreen.kt` untuk juga mengambil events periode sebelumnya. Atau buat computation terpisah dengan range start/end yang digeser mundur.
    - Previous start = start - (end - start + 1) days
    - Previous end = start - 1 day
  - **Kompatibilitas valuesVisible**: Indikator ikut mask jika valuesHidden (tampil "Rp ***")
  - **File terkait**: `ui/screens/ReportsScreen.kt`, `ui/components/Components.kt` (mungkin tambah komponen ChangeIndicator)

- [x] **F2 - Input Pengeluaran Budget: Perbaikan UX** (SUDAH FIX, tidak perlu dikerjakan)

- [x] **F3 - Upload/Ambil Gambar pada Transaksi** (SELESAI)
  - **Status saat ini**: CameraX internal (v1.3.14+), kompresi JPEG, enkripsi, dan preview thumbnail sudah berfungsi. Galeri via PickVisualMedia juga sudah ada.
  - **Implementasi**:
    1. **Camera**: CameraX internal (v1.3.14+) -- lebih stabil dari intent-based
    2. **Kompresi**: Resize max 1920px longest side + JPEG quality 80 via ImageCompressor.kt
    3. **Izin**: CAMERA runtime (wajib untuk CameraX)
    4. **EXIF / geotag**: Belum diimplementasikan (ditunda)
    5. **Tombol foto**: Hanya muncul untuk INCOME/EXPENSE (v1.3.15)
  - **Alur**:
    ```
    AuditDialog → tekan "Tambahkan foto bukti"
    → Dialog pilih: [Kamera] [Galeri]
    → Kamera: CameraX preview + capture
    → Galeri: PickVisualMedia
    → Kompresi (resize 1920px + JPEG 80)
    → Enkripsi AES-256-GCM via EncryptedAttachmentStore
    → Simpan ReceiptEntity ke database
    → Tampilkan thumbnail di AuditDialog
    ```
  - **File terkait**: `data/Entities.kt`, `security/ReceiptManager.kt`, `security/EncryptedAttachmentStore.kt`, `security/ImageCompressor.kt`, `ui/KronApp.kt`, `ui/dialogs/Dialogs.kt`

- [x] **F4 - Kategori Khusus Pengeluaran Tak Terduga** (SELESAI)
  - **Opsi A (dipilih)**: Hanya tampilkan 7 kategori seed EXPENSE + opsi "Buat custom" di akhir
  - **Daftar kategori seed EXPENSE**: Belanja, Makanan, Transportasi, Tagihan, Kesehatan, Hiburan, Lainnya
  - **Kategori dari portfolio TIDAK ditampilkan** (tidak campur aduk)
  - **Cara identifikasi seed categories**:
    - Saat seeding di `KronRepository.seedIfNeeded()`, kategori seed diinsert dengan ID auto-increment 1-7
    - Approach 1 (recommended): hardcode ID range 1-7 (simple, tapi riskan jika ada perubahan seeding)
    - Approach 2: tambah field `isBuiltIn: Boolean` di CategoryEntity, set true untuk seed -> butuh migration
    - Approach 3: filter by name list (["Belanja", "Makanan", ...]) -> tidak butuh migration
    - **Keputusan**: Pakai Approach 3 (filter by name) untuk Fase 1. Migration ditunda.
  - **Custom category"**:
    - Item terakhir di dropdown: "Buat kategori custom..."
    - Saat dipilih: muncul OutlinedTextField untuk input nama kategori
    - Category bersifat sekali pakai (tidak di-persist ke tabel categories)
    - Custom category disimpan sebagai string di field `note` transaksi: "[Kategori: {nama}]"
    - Atau simpan di field baru jika butuh query terpisah
  - **UI perubahan di ExpenseDialog**:
    - Ganti `ChoiceField` kategori unexpected dengan custom composable
    - List seed categories + divider + "Buat kategori custom..."
    - State baru: `customCategoryName: String`, `usingCustomCategory: Boolean`
  - **File terkait**: `ui/dialogs/Dialogs.kt` (ExpenseDialog bagian unexpected)

- [x] **F6 - Perbaikan Layout BudgetDetailDialog** (SELESAI)
  - **Masalah** (current code line 340-350):
    - Badge (Cash/eBudget) dan category name dalam 1 Row -> nama terpotong kalau panjang
    - Info "Rencana/Booking/Terpakai/Sisa" bercampur dalam baris teks yang panjang -> susah dibaca
    - Tidak readable untuk nominal besar
  - **Solusi layout baru per card**:
    ```
    ┌──────────────────────────────────────────┐
    │ [Cash]                                    │ ← badge sendiri, baris 1
    │ Kategori Nama Yang Panjang Sekali         │ ← full width, maxLines=2
    │                                           │
    │ Rencana           Rp 12.500.000           │ ← label-small, body-medium
    │ Terpakai          Rp 8.200.000            │
    │ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─        │ ← HorizontalDivider subtle
    │ Sisa              Rp 4.300.000   [Koreksi]│ ← sisa prominent + tombol
    └──────────────────────────────────────────┘
    ```
  - **Nominal besar**: compactIdr() untuk >= 1 jt
    - Rencana Rp 12,5 jt
    - Terpakai Rp 8,2 jt
    - Sisa Rp 4,3 jt
  - **Tombol Koreksi**: Di samping kanan baris Sisa, sejajar
  - **Warna Sisa**: error (merah) jika < 0, tertiary/hijau jika >= 0
  - **Koreksi form**: Setelah tombol diklik, muncul di bawah card (current behavior OK, hanya perlu penyesuaian padding)
  - **File terkait**: `ui/dialogs/Dialogs.kt` (BudgetDetailDialog, baris 340-361)

- [x] **F7 - Isolasi Data per Akun (Account Isolation)** (SELESAI)
  - **Status**: Selesai. Migration 8->9 (hardcode portfolio ke akun aktif, cascade ke budget lines) + Migration 10->11 (reset accountId=0 untuk data netral antar akun).
  - **Rincian**:
    - `ActivityEventEntity`, `PortfolioEntity`, `BudgetJournalLineEntity` punya `accountId`
    - DAO queries pakai `accountId IN (0, :accountId)` untuk vault/unallocated/rollover/cashflow
    - Repository filters include `it.accountId == 0L`
    - Data migrasi (v1.1.5) tampil di akun mana pun via accountId=0
  - **File terkait**: `data/Entities.kt`, `data/KronDatabase.kt` (MIGRATION_8_9, MIGRATION_10_11), `data/KronDao.kt`, `data/KronRepository.kt`, `ui/MainViewModel.kt`

- [ ] **F5 - Akun Team / Kolaborasi** (PERTIMBANGAN -- tidak akan diimplementasikan sampai diputuskan siap)
  - **Target**: Keluarga/teman, 1-5 orang
  - **MVP**: Shared viewer (read-only), edit menyusul di Fase 2
  - **Arsitektur**: Drive-based sharing (tanpa server eksternal)
  - **Status**: Desain dan analisis teknis sudah dilakukan, tetapi implementasi ditunda. Tidak ada timeline.
  - **Catatan**: Nomor migration di desain ini (7->8) tidak relevan lagi. Jika implementasi dilanjutkan, perlu disesuaikan dengan schema terbaru (saat ini sudah di migration 11).
  - **Perubahan data layer (desain lama)**:
    - Field baru di `AccountEntity`:
      - `isTeam: Boolean = false`
      - `teamFileId: String?`
      - `teamEncryptedKey: String?`
      - `isTeamOwner: Boolean = false`
    - Opsional: tabel `TeamMemberEntity`
  - **Format invite code**: `KRON-TM-{base64url(fileId)}-{base64url(wrappedKey)}-{checksum2char}`
    - fileId: Google Drive file ID
    - wrappedKey: AES key dienkripsi dengan passphrase sementara, atau pakai base64 raw key (risiko)
    - checksum: 2 karakter CRC8 atau sederhana untuk validasi format
    - Contoh: `KRON-TM-abc123_XyZ_-a3BkZmdoMTIzNDU=-3f`
    - Owner share manual via WA/Telegram/QR Code/langsung
  - **Key management**:
    - AES-256-GCM key untuk enkripsi blob
    - Key dibungkus di invite code
    - Member simpan key di Android Keystore (via EncryptedSharedPreferences)
  - **Encrypted team blob** (`TeamSnapshotManager`):
    - Format: ZIP terenkripsi mirip kronbackup, berisi data spesifik akun team (transaksi, budget, saldo)
    - Bukan full database, hanya data yang relevan untuk akun team
    - Upload ke Google Drive `appDataFolder` menggunakan existing `DriveAppDataClient`
  - **Sync Owner (auto)**:
    - Trigger: setiap selesai transaksi/data berubah di akun team (hook di KronRepository)
    - Debounce: WorkManager dengan delay 30 detik (tidak upload setiap perubahan kecil)
    - Upload encrypted blob ke Drive file yang sudah ada (update)
    - Status indikator: "Tersimpan" (idle), "Menyimpan..." (syncing), "Gagal" (error)
    - Tidak ada tombol manual untuk owner (fully auto)
  - **Sync Member (manual)**:
    - Pull-to-refresh di halaman yang menampilkan data team
    - Tombol `↻` (sync) di kartu akun team di HomeScreen
    - "Periksa update" button di settings akun team
    - Check on app open: jika > 1 jam sejak sync terakhir, trigger download otomatis
  - **Background polling (member)**:
    - WorkManager periodic 30 menit
    - Cek timestamp file Drive (atau simpan generation counter)
    - Jika ada perubahan: download blob baru, kirim notifikasi
  - **Notifikasi**: 
    - "Data [Nama Tim] diperbarui"
    - Tap notifikasi -> buka app -> refresh data team
    - Hanya untuk member, owner tidak perlu notifikasi perubahan sendiri
  - **UI member (read-only)**:
    - Akun team muncul di halaman Home/Budget/Activity dengan badge "Tim"
    - FAB disembunyikan untuk akun team
    - Tombol aksi (income/expense/transfer) di-disable
    - BudgetDetailDialog: mode readOnly=true (koreksi dinonaktifkan)
    - Settings akun team: hanya menampilkan info, tidak ada edit
  - **Flow lengkap owner**:
    ```
    User buat "Akun Team" dari Settings
    → pilih nama akun
    → KRON generate AES key
    → upload blob kosong/awal ke Drive appDataFolder
    → dapat fileId
    → generate invite code
    → tampilkan kode + QR
    → owner copy/share ke anggota
    ```
  - **Flow lengkap member**:
    ```
    User input invite code
    → parse fileId + key
    → simpan key ke Android Keystore
    → download blob dari Drive
    → dekripsi
    → akun team muncul di dashboard (read-only)
    ```
  - **Error handling**:
    - Drive tidak tersedia: tampilkan pesan "Sync tidak tersedia. Periksa koneksi."
    - File blob tidak ditemukan (dihapus owner): tampilkan "Akses ke akun team tidak ditemukan. Hubungi owner."
    - Dekripsi gagal (key invalid): tampilkan "Gagal mendekripsi data. Minta invite code baru dari owner."
  - **Fase 2 nanti**:
    - Edit oleh member dengan authority EDITOR
    - Conflict resolution (karena Drive-based tidak real-time)
    - Pendekatan: last-write-wins dengan backup versi sebelumnya
    - Notifikasi konflik: "Data tim berubah sejak kamu terakhir edit. Periksa sebelum menyimpan."
  - **File terkait**: `data/Entities.kt` (AccountEntity), `data/KronRepository.kt`, `sync/DriveSyncCoordinator.kt`, `sync/DriveAppDataClient.kt`, `ui/screens/HomeScreen.kt`, `ui/screens/BudgetScreen.kt`, `ui/screens/ActivityScreen.kt`, `ui/screens/SettingsScreen.kt`

---

## Catatan Diskusi

### 2026-07-20 -- Sesi 1: Pengumpulan Permintaan

User menyampaikan 5 area permintaan, kemudian 1 tambahan saat diskusi:

1. **F1** Indikator (+^/-v) di laporan
2. **F2** Perbaikan UX input expense ke budget
3. **F3** Aktivasi upload/ambil gambar (kamera + galeri) dengan kompresi, timestamp, geotag
4. **F4** Kategori general + custom untuk pengeluaran tak terduga
5. **F5** Fitur akun team dengan kolaborator, invitation, authority edit/viewer
6. **F6** Perbaikan layout BudgetDetailDialog (tambahan saat diskusi -- category name terpotong, info tidak rapi)

### 2026-07-20 -- Sesi 1b: Diskusi F1 (Indikator +^/-v)

**Pertanyaan kunci yang dibahas:**
- Lokasi: ringkasan utama (Masuk/Keluar/Net) + tiap baris di mode Tabel
- Simbol: Masuk/Keluar pakai +^ / -v gaya saham; Net cukup + / - tanpa panah
- Format: nominal compact + persentase (keduanya)
- Perbandingan: periode sebelumnya durasi sama (30 hari ini vs 30 hari sebelumnya)

**Keputusan Desain F1 (detail):**
- **Lokasi**: Ringkasan utama (Masuk/Keluar/Net) bandingkan dengan periode sebelumnya durasi sama; Tabel bandingkan dengan bucket sebelumnya
- **Simbol**:
  - Masuk/Keluar: `+^` (naik) / `-v` (turun) -- gaya saham
  - Net: tanpa panah, cukup `+` / `-` saja karena arah sudah jelas dari tanda nominal
- **Format**: Nominal compact + persentase. Contoh:
  - `+^ Rp 2,3 jt  (+22,5%)` -- hijau (Masuk naik itu baik)
  - `-v Rp 1,1 jt  (-11,8%)` -- merah (Keluar naik itu buruk)
  - `+ Rp 3,4 jt  (+380%)` -- hijau (Net positif)
  - `- Rp 1,5 jt  (-250%)` -- merah (Net negatif)
- **Rumus persentase**: `((current - previous) / |previous|) * 100`
- **Edge cases**:
  - Previous tidak ada: sembunyikan
  - Previous = 0: label "Baru" (hijau, hindari division by zero)
  - Previous == current: sembunyikan
- **Kompatibilitas nilai visible**: indikator ikut mask jika valuesHidden (tampil "Rp ***")

### 2026-07-20 -- Sesi 1c: Diskusi F2 (Input Expense Budget)

User mengonfirmasi bahwa **F2 sudah fix di versi 1.1.5 saat ini**. Tidak perlu dikerjakan. Dihapus dari daftar prioritas.

### 2026-07-20 -- Sesi 1d: Diskusi F3 (Upload/Ambil Gambar)

**Analisis awal:**
- Gallery picker (PickVisualMedia) SUDAH ada di KronApp.kt:389
- viewModel.attachReceipt() SUDAH berfungsi (baca URI -> enkripsi -> simpan) di MainViewModel.kt:326
- AuditDialog SUDAH punya tombol "Tambahkan foto bukti" (KronApp.kt:672-674)
- Backend ReceiptManager + EncryptedAttachmentStore SUDAH siap

**Apa yang belum:**
- Camera capture: belum ada, perlu izin CAMERA + ActivityResultContracts.TakePicture
- Kompresi: gambar disimpan full-res (bisa 10-20MB), perlu resize + JPEG compress
- EXIF: ReceiptEntity tidak punya field timestamp/lokasi, perlu migration 6->7
- Preview: hanya tampil nama file dan ukuran, perlu thumbnail dekripsi

**Keputusan Desain F3:**
- **Sumber**: Gallery (sudah ada) + Camera (baru, butuh izin CAMERA)
- **Kompresi**: Resize 1920px longest side + JPEG 80, target 200-500KB sebelum enkripsi
- **EXIF metadata**:
  - Kamera: timestamp otomatis, geotag via FusedLocationProviderClient (opsional, perlu izin ACCESS_FINE_LOCATION)
  - Galeri: baca EXIF DateTimeOriginal/GPSLatitude/GPSLongitude, fallback import time
  - Disimpan di field baru ReceiptEntity (capturedAt, latitude, longitude) -- tidak di encrypted file
- **Preview**: Thumbnail di AuditDialog via dekripsi + FileProvider untuk view fullscreen
- **Room migration**: 6->7 untuk field baru ReceiptEntity
- **Alur**: Form transaksi -> AuditDialog -> [Kamera] [Galeri] -> kompres -> ekstrak EXIF -> enkripsi -> simpan

### 2026-07-20 -- Sesi 1e: Diskusi F4 (Kategori General untuk Tak Terduga)

**Analisis:**
- Kategori seed (KronRepository.kt:109-119): Gaji (income), Pendapatan Lain (income), Belanja, Makanan, Transportasi, Tagihan, Kesehatan, Hiburan, Lainnya (expense)
- Saat bikin portfolio di createPortfolio() (KronRepository.kt:402-407), kategori baru ditambahkan ke `categories` table jika belum ada
- Akibatnya dropdown unexpected expense penuh dengan kategori budget (seperti "Kopi", "Snack", "Bensin") campur aduk dengan seed

**Keputusan Desain F4 (Opsi A):**
- Tampilkan 7 kategori seed EXPENSE saja: Belanja, Makanan, Transportasi, Tagihan, Kesehatan, Hiburan, Lainnya
- Kategori portfolio tidak ditampilkan
- Tambah opsi "Buat kategori custom..." sebagai item terakhir
  - Klik -> muncul input teks -> kategori sekali pakai
  - Disimpan sebagai string, tidak di-persist ke tabel categories
  - Penyimpanan: di field `note` transaksi dengan prefix "[Kategori: {nama}]"
- Cara filter: filter by name list (Approach 3) untuk Fase 1, hindari migration

### 2026-07-20 -- Sesi 1f: Diskusi F5 (Akun Team / Kolaborasi)

**Latar belakang:**
- Target: Keluarga/teman, 1-5 orang
- Prioritas: shared viewer dulu, edit nanti
- Preferensi user: tanpa server, via Google Drive
- Tidak perlu real-time, yang penting data bisa diakses member

**Keputusan Arsitektur:**
- **No backend server** -- murni Drive-based sharing via Google Drive appDataFolder
- **Sync Owner (auto)**: Setiap perubahan data akun team -> debounced WorkManager (30s) -> upload blob ke Drive
- **Sync Member (manual)**: Pull-to-refresh, tombol sync, check on app open
- **Background polling**: WorkManager periodik 30 menit sebagai fallback
- **Notifikasi**: Android Notification saat polling deteksi perubahan
- **Invite code**: KRON-TM-{base64url(fileId)}-{base64url(wrappedKey)}-{checksum}
- **Key**: AES-256-GCM, dibungkus di invite code, disimpan di Android Keystore
- **Read-only enforcement**: FAB hidden, buttons disabled, BudgetDetail readOnly=true

### 2026-07-20 -- Sesi 1g: Diskusi F6 (Layout BudgetDetailDialog)

**Masalah** (Dialiogs.kt:340-350):
- Badge dan nama kategori dalam satu Row -> nama kepotong kalau panjang
- Info Rencana/Booking/Terpakai/Sisa bercampur dalam satu baris teks -> susah dibaca
- Tidak readable untuk nominal besar

**Keputusan Desain F6:**
- Badge Cash/eBudget di baris terpisah (atas)
- Nama kategori full width, tidak terpotong (maxLines=2)
- Info dalam format label-value alignment (Rencana, Terpakai, Sisa)
- Divider tipis sebelum baris Sisa
- Sisa prominent + tombol Koreksi di samping kanan
- Nominal pakai compactIdr jika >= 1jt

### 2026-07-20 -- Sesi 1h: Diskusi F7 (Isolasi Data per Akun)

**Masalah**: Setelah diskusi F1-F6, user menyadari bahwa data akun tidak terisolasi. Portfolio/budget, riwayat transaksi, vault, dan laporan bersifat global -- tercampur antar akun.

**Analisis** (diverifikasi via kode):
- `ActivityEventEntity` tidak punya `accountId` → semua transaksi global
- `PortfolioEntity` tidak punya `accountId` → budget global
- `BudgetJournalLineEntity` (bucket VAULT/UNALLOCATED/ROLLOVER) tidak punya `accountId` → vault global
- Hanya `CashJournalLineEntity` dan `RecurringRuleEntity` yang punya `accountId` → saldo akun sudah benar

**Keputusan Desain F7:**
- Migration 7->8 untuk tambah `accountId` di 3 entity (realisasi: 8->9, 9->10, 10->11)
- Backfill deterministik: ActivityEvent via JOIN cash_journal_lines, Portfolio assign ke akun aktif, BudgetJournalLine bucket via allocation→period→portfolio
- Update DAO queries + ViewModel untuk filter by `activeAccountId`
- Data existing TIDAK rusak -- semua bisa dilacak secara deterministik
- Risiko: portfolio global diassign ke satu akun, akun lain mulai tanpa portfolio

---

## Rencana Implementasi

### Strategi Rilis

Fitur akan dirilis dalam beberapa fase untuk menjaga stabilitas dan memudahkan testing:

| Fase | Versi | Fitur | Kebutuhan Migration | Status |
|:----:|:-----:|-------|:-------------------:|:------:|
| 1 | 1.2.0-1.2.2 | F1 + F4 + F6 | Tidak ada | RILIS |
| 2 | 1.3.7-1.3.13 | F3 (kamera intent) + F7 (isolasi akun) | Room 8->9, 9->10, 10->11 | RILIS |
| 2b | 1.3.14 | F3 CameraX (ganti intent-based) | Izin CAMERA | RILIS |
| 2c | 1.3.15 | Foto bukti hanya INCOME/EXPENSE | Tidak ada | RILIS |
| 2d | 1.3.16 | Theme UI: container, icon, light mode muted | Tidak ada | RILIS |
| 2e | 1.3.17-1.3.18 | Riwayat budget + grafik batang | Tidak ada | RILIS |
| 2f | 1.3.19 | Perbaikan theme container + icon sistem | Tidak ada | RILIS |
| F5 | Ditunda | F5 - Akun Team | (tidak dijadwalkan) | DITUNDA |

### Fase 1 (v1.2.0-1.2.2) -- F1 + F4 + F6 ✅ SELESAI

Semua fitur F1, F4, F6 sudah diimplementasi dan dirilis.

### Fase 2 (v1.3.7-1.3.19) -- F3 + F7 + Perbaikan UI ✅ SELESAI

**F3 -- Kamera/Galeri foto bukti**: Sudah berfungsi penuh.
- Awal: ActivityResultContracts.TakePicture + ACTION_IMAGE_CAPTURE (1.3.7-1.3.13)
- Sekarang: **CameraX** (1.3.14+) -- preview + capture langsung, tidak force close
- Izin CAMERA diminta runtime (1.3.14+)
- Tombol foto bukti hanya muncul untuk INCOME/EXPENSE (1.3.15)
- Kompresi + enkripsi receipt sudah berfungsi

**F7 -- Isolasi Data per Akun**: Sudah berfungsi penuh.
- Migration 8->9, 9->10, 10->11 untuk accountId
- DAO queries pakai `IN (0, :accountId)`, repository filter `it.accountId == 0L`
- Data v1.1.5 netral (accountId=0) tampil di semua akun

**Catatan**: Fitur kompresi gambar (resize 1920px + JPEG 80) sudah ada di ImageCompressor.kt. EXIF extraction dan geotag belum diimplementasikan (ditunda).

### Fase 2b -- Perbaikan UI/UX (1.3.16-1.3.19)

- **Theme container**: LazyRow sekarang `fillMaxWidth` rata kiri/kanan (1.3.19)
- **Theme icons**: LightMode (matahari), DarkMode (bulan), Settings (gear) muncul di semua chip (1.3.16)
- **Light mode colors**: primaryContainer, surface, background dimuted (1.3.16)
- **Riwayat budget**: BudgetHistoryDialog dengan daftar semua periode (1.3.17)
- **Grafik batang**: Planned vs spent per periode (1.3.18)

### Fase 3 -- Sync Drive Privat (.krondrive) -- BERMASALAH

**Google Drive Sync (privat)**: TIDAK BERFUNGSI. Koneksi gagal di tahap Credential Manager — selalu timeout 30 detik ("Google tidak merespon"). Penyebab belum pasti. Tidak ada target perbaikan saat ini.

**Detail masalah**:
- Credential Manager `getCredential()` timeout setelah 30 detik
- Web client ID sudah benar: `677791134689-rb53arqc2k6ror17oku0pvm3nmfrcnkc.apps.googleusercontent.com`
- Android client ID: `677791134689-onu28oduh8h4ql632h8aphh4nad1qjbr.apps.googleusercontent.com`
- Scope `drive.appdata` sudah ditambahkan di GCP OAuth consent screen
- Test user (email pemilik) sudah terdaftar
- Internet aktif, Google Play Services versi 26.26.34
- Kemungkinan: Credential Manager tidak kompatibel penuh dengan perangkat

**Rencana**: Jika Credential Manager tidak bisa dipakai, bypass langsung ke Play Services AuthorizationClient (tanpa ID token). TIDAK DIPRIORITASKAN.

### Fase 3b -- Backup/Restore (.kronbackup) -- PERLU PERBAIKAN

Proses restore bisa gagal di tengah, rollback belum solid. Tidak ada target perbaikan saat ini.

### Fase 4 -- F5 Akun Team / Kolaborasi (.kronshare) -- DITUNDA

**Konsep**: Team workspace via Google Drive, berbasis file `.kronshare` dengan scope `drive.file`. Owner single-writer, member read-only viewer.

**Perubahan desain** (berdasarkan diskusi 2026-07-22):
- Ekstensi file: `.kronshare` untuk team, `.krondrive` untuk sync private
- Scope `drive.file` untuk team (bisa di-share via ACL Google Drive), bukan `appDataFolder`
- Mekanisme invite via permission Drive (email-based), bukan invite code base64
- GCP OAuth consent screen sudah punya kedua scope (`drive.appdata` + `drive.file`)

**Blocking issues**:
1. Fondasi Drive (DriveSyncRuntime, Credential Manager) belum stabil
2. Sync private `.krondrive` harus berfungsi dulu sebelum team
3. Perlu implementasi bypass Credential Manager

**Tidak akan dimulai** sampai fondasi Drive privat sudah berfungsi dan stabil.

Desain lama F5 (di bawah "Analisis Teknis") tetap dipertahankan sebagai referensi arsitektur.

---

## Analisis Teknis dan Rekomendasi Tambahan

> **Tanggal analisis**: 2026-07-20  
> **Status**: DOKUMEN HISTORIS -- analisis ini ditulis sebelum implementasi. Sebagian besar rekomendasi sudah terealisasi (F1, F3, F4, F6, F7). F5 ditunda. Bagian ini dipertahankan sebagai referensi arsitektur dan pertimbangan desain.
> **Kesimpulan umum**: Rencana F1, F3, F4, dan F6 layak dilanjutkan setelah beberapa koreksi. F5 belum aman untuk langsung diimplementasikan karena desain penyimpanan Drive saat ini memiliki hambatan teknis yang bersifat blocking.

### Ringkasan Penilaian

| Area | Penilaian | Keputusan yang disarankan |
|------|-----------|---------------------------|
| F1 - Indikator laporan | Layak, risiko rendah–sedang | Lanjutkan, tetapi pindahkan agregasi dari composable ke data/domain layer dan perjelas semantik warna |
| F4 - Kategori tak terduga | Layak sebagai solusi sementara | Lanjutkan dengan penandaan technical debt; jangan jadikan parsing `note` sebagai model permanen |
| F6 - Layout budget | Layak, risiko rendah | Lanjutkan pada v1.2.0 |
| F3 - Foto bukti | Layak, risiko sedang | Lanjutkan setelah memperbaiki alur permission, EXIF, orientasi, kompresi, dan privasi lokasi |
| F5 - Akun team | **Belum layak dengan rancangan saat ini** | Redesign penyimpanan Drive, pertukaran kunci, cache read-only, recovery, dan revocation sebelum coding |

### Temuan Blocking - F5 Tidak Dapat Menggunakan `appDataFolder` untuk Berbagi

Google Drive `appDataFolder` bersifat khusus untuk aplikasi dan akun Google yang membuatnya. File atau folder di dalamnya **tidak dapat dibagikan**. Karena itu, anggota dengan akun Google berbeda tidak akan bisa mengunduh blob hanya menggunakan `fileId` dan invite code.

**Dampak terhadap rencana saat ini:**

- Flow owner upload ke `appDataFolder` lalu membagikan `fileId` tidak akan berfungsi lintas akun.
- Invite code tidak dapat menggantikan permission Drive.
- `DriveAppDataClient` masih dapat digunakan untuk backup privat pemilik, tetapi tidak untuk workspace kolaboratif.

**Arsitektur pengganti yang direkomendasikan:**

1. Simpan snapshot akun team sebagai file biasa di space `drive`, bukan `appDataFolder`.
2. Buat folder aplikasi khusus, misalnya `KRON Team`, dengan `appProperties` agar mudah ditemukan kembali oleh aplikasi.
3. Gunakan permission Drive bertipe `user` dengan role `reader` untuk viewer dan `writer` hanya jika fase editor benar-benar siap.
4. Gunakan scope sesempit mungkin, idealnya `drive.file`, bukan akses seluruh Drive.
5. Jangan gunakan permission `anyoneWithLink` karena tidak sesuai dengan tujuan privasi aplikasi.
6. Invite harus memuat identitas workspace dan material kriptografi yang diperlukan, sedangkan akses file tetap dibatasi oleh ACL Google Drive.

### Rekomendasi F1 - Indikator Perubahan Laporan

#### 1. Jangan Melakukan Agregasi Besar di Composable

Memfilter seluruh `state.activities` di `ReportsScreen.kt` akan semakin mahal ketika jurnal bertambah selama bertahun-tahun dan dapat terulang saat recomposition. Agregasi sebaiknya ditempatkan di salah satu dari:

- DAO Room dengan query `SUM` berdasarkan rentang waktu dan tipe transaksi;
- repository/use-case yang menghasilkan immutable report state;
- ViewModel dengan `combine`/`mapLatest`, bukan kalkulasi langsung di UI.

Buat model domain yang eksplisit, misalnya:

```kotlin
data class MetricComparison(
    val current: Long,
    val previous: Long?,
    val delta: Long?,
    val percentage: BigDecimal?,
    val state: ComparisonState
)

enum class ComparisonState {
    UNAVAILABLE,
    UNCHANGED,
    NEW_VALUE,
    INCREASED,
    DECREASED
}
```

Dengan model ini, UI tidak perlu mengulang aturan bisnis untuk ringkasan dan tabel.

#### 2. Pisahkan Arah Perubahan dari Dampak Baik/Buruk

Ada dua konsep berbeda:

- **Arah**: naik, turun, atau tetap;
- **Dampak**: baik, buruk, atau netral.

Contoh:

- Masuk naik → arah naik, dampak baik;
- Keluar naik → arah naik, dampak buruk;
- Net dari `-Rp10.000` menjadi `-Rp5.000` → delta positif dan kondisi membaik, walaupun net masih negatif.

Direkomendasikan menggunakan enum seperti:

```kotlin
enum class MetricPolarity { HIGHER_IS_BETTER, LOWER_IS_BETTER }
enum class ChangeImpact { POSITIVE, NEGATIVE, NEUTRAL }
```

Warna indikator harus ditentukan dari **delta dan polarity**, bukan hanya tanda nilai current.

#### 3. Koreksi Edge Case `previous == 0`

Label `Baru` tidak selalu hijau:

- Masuk: 0 → positif = hijau;
- Keluar: 0 → positif = merah;
- Net: 0 → positif = hijau, 0 → negatif = merah.

Jika current juga 0, indikator tetap disembunyikan.

#### 4. Gunakan Rentang Tanggal yang Konsisten

Gunakan `LocalDate`, zona waktu aplikasi, dan batas rentang half-open:

```text
[startInclusive, endExclusive)
```

Cara ini menghindari transaksi pada akhir hari terlewat dan mengurangi kesalahan `23:59:59.999`. Untuk durasi:

```kotlin
val days = ChronoUnit.DAYS.between(startDate, endDateInclusive) + 1
```

Untuk bucket kalender seperti bulan/tahun, lebih baik membandingkan dengan bulan/tahun kalender sebelumnya daripada sekadar menggeser jumlah hari apabila UI memang berlabel per bulan atau per tahun.

#### 5. Baris Pertama pada Mode Tabel

Jika semua baris tabel harus memiliki pembanding, query perlu mengambil **satu bucket tambahan sebelum rentang yang terlihat**. Bucket tambahan tidak ditampilkan, hanya digunakan sebagai pembanding baris pertama.

#### 6. Akurasi dan Format

- Gunakan `Long` untuk nominal rupiah dan `BigDecimal` untuk persentase; hindari `Float`.
- Terapkan `compactIdr()` berdasarkan nilai absolut agar nilai negatif tetap memiliki threshold yang benar.
- Pisahkan formatter domain dari composable.
- Simbol `+^` dan `-v` sebaiknya tidak menjadi satu-satunya penanda. Tambahkan teks aksesibilitas seperti “naik 22,5 persen”.
- Saat nilai disembunyikan, pertimbangkan menyembunyikan persentase juga karena persentase dapat membocorkan pola keuangan walaupun nominal dimask.

#### 7. Skenario Tes Tambahan F1

- Previous negatif dan current negatif tetapi membaik;
- Previous positif dan current negatif;
- Nilai tepat pada batas Rp1 juta, Rp1 miliar, dan Rp1 triliun;
- Rentang satu hari;
- Tahun kabisat dan pergantian tahun;
- Transaksi tepat di awal/akhir hari;
- First bucket dengan data sebelum rentang;
- `valuesVisible=false` untuk nominal dan persentase;
- Urutan data yang tidak kronologis;
- Dataset besar untuk memastikan recomposition tidak memicu agregasi berulang.

### Rekomendasi F4 - Kategori Pengeluaran Tak Terduga

#### 1. Filter Berdasarkan Nama Hanya Cocok sebagai Solusi Transisi

Nama kategori bukan identifier yang stabil karena dapat berubah akibat rename, lokalisasi, typo seed, atau migrasi lama. Untuk v1.2.0, filter nama masih dapat diterima agar tidak menambah migration, tetapi harus dicatat sebagai technical debt.

Pada migration berikutnya, tambahkan identifier stabil seperti:

```kotlin
systemKey: String? // EXPENSE_SHOPPING, EXPENSE_FOOD, dst.
isBuiltIn: Boolean
```

UI menampilkan nama terlokalisasi, sedangkan logic menggunakan `systemKey`.

#### 2. Jangan Menjadikan `note` sebagai Model Data Permanen

Format `[Kategori: {nama}]` memiliki beberapa masalah:

- bercampur dengan catatan bebas pengguna;
- sulit di-query dan diindeks;
- rawan format rusak saat user mengedit note;
- nama yang mengandung `]`, newline, atau prefix serupa dapat merusak parser;
- laporan kategori tidak bisa mengandalkannya secara aman.

**Solusi sementara tanpa migration:**

- gunakan category `Lainnya` sebagai categoryId aktual;
- tambahkan marker terstruktur pada note hanya sebagai display metadata;
- batasi panjang nama, trim whitespace, larang kontrol karakter, dan escape delimiter;
- jangan gunakan marker tersebut sebagai satu-satunya sumber perhitungan laporan.

**Solusi permanen pada migration berikutnya:**

Tambahkan salah satu field berikut pada transaksi/journal metadata:

```kotlin
categoryLabelSnapshot: String?
adHocCategoryName: String?
```

`categoryId` tetap menunjuk `Lainnya`, sedangkan label ad-hoc menjadi metadata eksplisit.

#### 3. Validasi Custom Category

- `trim()` sebelum penyimpanan;
- panjang disarankan 1–40 karakter;
- tolak string hanya whitespace;
- cegah duplikasi visual dengan kategori built-in;
- normalisasi perbandingan menggunakan lowercase locale-independent;
- tentukan perilaku saat nama custom sama dengan kategori portfolio.

### Rekomendasi F6 - BudgetDetailDialog

Rancangan baru sudah tepat. Tambahan yang disarankan:

1. Jangan terlalu agresif menggunakan compact nominal pada layar koreksi keuangan. Nilai penuh lebih penting daripada menghemat beberapa karakter.
2. Gunakan compact hanya jika ruang benar-benar sempit; nilai penuh dapat ditampilkan pada tap, expanded state, atau content description.
3. Gunakan angka tabular agar digit antarbaris sejajar.
4. Untuk tombol `Koreksi`, pastikan target sentuh minimal tetap memadai walaupun visual tombol kecil.
5. `Sisa == 0` sebaiknya memakai warna netral, bukan hijau.
6. Tambahkan semantics “defisit” saat nilai negatif agar informasi tidak hanya disampaikan melalui warna.
7. Uji font scale besar, mode landscape, bahasa dengan label lebih panjang, dan nominal negatif besar.

### Rekomendasi F3 - Foto Bukti

#### 1. `CAMERA` Permission Tidak Wajib untuk Kamera Eksternal

Jika KRON menggunakan `ActivityResultContracts.TakePicture`/`ACTION_IMAGE_CAPTURE` untuk mendelegasikan pengambilan gambar ke aplikasi kamera sistem, Android merekomendasikan **tidak mendeklarasikan permission `CAMERA`**. Permission diperlukan apabila KRON mengakses kamera langsung, misalnya menggunakan CameraX.

Rencana harus memilih salah satu jalur dengan jelas:

- **TakePicture + aplikasi kamera eksternal**: lebih sederhana, tidak perlu permission `CAMERA`;
- **CameraX internal**: kontrol lebih besar, tetapi perlu permission `CAMERA`, lifecycle kamera, preview, dan pengujian tambahan.

Untuk fitur bukti transaksi, jalur pertama lebih sesuai untuk MVP.

#### 2. Baca EXIF Sebelum Kompresi

Kompresi ke JPEG baru dapat menghilangkan EXIF. Alur yang aman:

```text
URI sumber
→ baca EXIF dan orientasi
→ decode terukur/downsample
→ rotasi/mirror sesuai EXIF
→ resize
→ compress
→ encrypt
→ hapus file temporary
```

Tanpa koreksi orientasi, foto dari sebagian kamera dapat tampil miring walaupun terlihat benar di galeri.

#### 3. Target 200–500 KB Tidak Dapat Dijamin oleh JPEG Quality 80

Ukuran keluaran tergantung detail gambar. Gunakan target sebagai sasaran, bukan kontrak mutlak:

1. resize longest side maksimal 1920 px;
2. mulai quality 85 atau 80;
3. jika melewati batas maksimum, turunkan quality secara bertahap;
4. tetapkan quality minimum agar teks kuitansi tidak menjadi kabur;
5. gagal secara jelas jika input rusak atau tidak dapat didecode.

Untuk kuitansi, keterbacaan teks lebih penting daripada memaksa file masuk ke 500 KB.

#### 4. Metadata yang Disarankan

Selain `capturedAt`, `latitude`, dan `longitude`, pertimbangkan:

```kotlin
importedAt: Long
metadataSource: MetadataSource // CAMERA, EXIF, IMPORT_TIME
mimeType: String
width: Int
height: Int
originalSizeBytes: Long?
storedSizeBytes: Long
contentHash: String? // deduplikasi/verifikasi tambahan
```

`capturedAt` dari EXIF bukan bukti forensik yang kuat karena metadata dapat diedit. UI sebaiknya membedakan “waktu dari metadata foto” dan “waktu ditambahkan ke KRON”.

#### 5. Geotag Harus Default-Off dan Per-Receipt

Lokasi adalah data sangat sensitif untuk aplikasi keuangan privat. Rekomendasi:

- jangan meminta permission lokasi saat pertama kali memakai kamera;
- tampilkan opsi eksplisit “Sertakan lokasi”;
- default mati;
- gunakan lokasi terakhir yang masih fresh dan tampilkan akurasi;
- jangan meminta background location;
- sediakan aksi hapus lokasi dari receipt;
- jelaskan bahwa lokasi ikut masuk ke backup/sync;
- pertimbangkan pembulatan koordinat jika presisi penuh tidak diperlukan.

Karena database memakai SQLCipher, metadata tetap terenkripsi saat tersimpan. Namun metadata tersebut tetap perlu diperlakukan sebagai bagian dari threat model backup, restore, dan ekspor.

#### 6. Preview Internal Lebih Privat daripada Viewer Eksternal

`FileProvider` diperlukan untuk memberikan URI aman ke aplikasi lain, tetapi tidak wajib untuk preview internal Compose. Untuk default yang lebih privat:

- dekripsi ke stream/memori atau temporary cache privat;
- tampilkan dengan viewer internal;
- gunakan `FileProvider` hanya untuk aksi “Buka dengan aplikasi lain” atau “Bagikan” yang eksplisit;
- hapus temporary decrypted file setelah selesai;
- gunakan URI permission sementara dan revoke setelah pemakaian.

#### 7. Ketahanan Memori dan Crash

- Jangan membaca foto 10–20 MB seluruhnya ke `ByteArray` jika dapat diproses streaming.
- Decode bounds lebih dahulu dan gunakan downsampling.
- Jalankan decode/compress/encrypt di dispatcher I/O/default yang tepat, bukan main thread.
- Hapus cache sementara pada success, cancel, exception, dan startup cleanup.
- Simpan attachment hanya setelah jurnal/transaksi tersedia atau gunakan mekanisme cleanup orphan.
- Pertimbangkan batas jumlah dan ukuran attachment per transaksi.

#### 8. Tes Tambahan F3

- gambar dengan orientasi 90/180/270 dan mirror;
- PNG screenshot, HEIC/HEIF, WebP, JPEG sangat besar;
- URI cloud yang lambat atau stream hanya dapat dibuka sekali;
- EXIF rusak;
- ruang penyimpanan habis;
- proses aplikasi mati di tengah kompresi/enkripsi;
- user membatalkan kamera;
- file temporary tertinggal;
- receipt lama tanpa field metadata;
- restore backup berisi attachment dan metadata;
- thumbnail tidak membocorkan file decrypted ke storage publik.

### Rekomendasi F7 - Isolasi Data per Akun

> **Catatan**: F7 sudah diimplementasi dan dirilis (v1.3.7-1.3.13). Bagian ini adalah dokumentasi analisis pra-implementasi. Realisasi implementasi berbeda dalam beberapa detail -- lihat ringkasan di bawah.

**Temuan**: `ActivityEventEntity`, `PortfolioEntity`, dan `BudgetJournalLineEntity` (bucket VAULT/UNALLOCATED/ROLLOVER) tidak memiliki `accountId`. Hanya `CashJournalLineEntity` dan `RecurringRuleEntity` yang memiliki `accountId`. Akibatnya, budget, riwayat, vault, dan laporan mencampur data dari semua akun.

#### 1. Realisasi Implementasi vs Rencana

| Aspek | Rencana Awal | Realisasi |
|-------|-------------|-----------|
| Migration | 7->8 (1 migration) | 8->9 (add accountId) + 9->10 (fix quirk) + 10->11 (reset accountId=0 untuk data netral) |
| Filter DAO | `WHERE accountId = :activeAccountId` | `WHERE accountId IN (0, :accountId)` -- data netral (migrasi) tampil di semua akun |
| Repository | Filter ketat per akun | `it.accountId == 0L` untuk data lintas akun |

**Detail implementasi final** (v1.3.13):
- `ActivityEventEntity`, `PortfolioEntity`, `BudgetJournalLineEntity` punya `accountId`
- DAO queries: `accountId IN (0, :accountId)` untuk vault/unallocated/rollover/cashflow
- Repository: filter include `it.accountId == 0L` -- data migrasi dari v1.1.5 (netral) tampil di akun mana pun
- File terkait: `data/Entities.kt`, `data/KronDatabase.kt` (MIGRATION_8_9, MIGRATION_10_11), `data/KronDao.kt`, `data/KronRepository.kt`, `ui/MainViewModel.kt`

#### 2. Dampak Terhadap Data Existing

| Entity | Data per akun? | Risiko jika tidak diperbaiki |
|--------|:--------------:|------------------------------|
| CashJournalLineEntity | Sudah (accountId) | Aman |
| ActivityEventEntity | **Belum** | Riwayat transaksi akun A tercampur akun B |
| PortfolioEntity | **Belum** | Budget akun A dan B jadi satu |
| BudgetJournalLineEntity (allocation) | **Belum langsung** | Bisa dilacak via allocation → period → portfolio tapi tanpa accountId |
| BudgetJournalLineEntity (bucket) | **Belum** | VAULT/UNALLOCATED global, vault akun A dan B jadi satu |
| RecurringRuleEntity | Sudah (accountId) | Aman |

#### 3. Strategi Backfill (realisasi)

Semua data existing dapat dilacak ke account masing-masing tanpa kehilangan:

1. **ActivityEventEntity**: JOIN dengan `cash_journal_lines` pada `eventId` -- setiap event pasti memiliki minimal 1 cash journal line yang punya `accountId`. Deterministik, zero loss.

2. **PortfolioEntity**: Tidak ada jejak langsung ke account. Solusi: assign ke akun yang aktif dipilih user saat migration pertama kali dijalankan. Jika hanya ada 1 akun, assign ke akun tersebut. Jika ada >1 akun aktif, assign ke akun yang terakhir dipilih (stored di DataStore/preferences) atau akun pertama.

3. **BudgetJournalLineEntity (allocation)**: Lacak via `allocationId` → `AllocationEntity.periodId` → `PeriodEntity.portfolioId` → `PortfolioEntity.accountId`. Deterministik untuk allocation yang memiliki portfolio setelah backfill.

4. **BudgetJournalLineEntity (bucket)**: Untuk entries dengan `allocationId = NULL` (VAULT, UNALLOCATED, ROLLOVER, surplus bucket), assign ke akun yang sama dengan portfolio yang dimiliki bucket tersebut. Jika bucket tidak memiliki portfolio (sangat jarang, hanya data corrupt), assign ke akun aktif.

**Verifikasi backfill**: Migration test harus membandingkan total debit/credit per account sebelum dan sesudah migration untuk memastikan tidak ada data yang hilang atau bergeser.

#### 4. Perubahan DAO (realisasi)

Semua query melibatkan 3 entity di atas menggunakan filter `accountId IN (0, :activeAccountId)`:

- `KronDao.kt`: `getActivities()`, `getCashFlowEvents()`, `getPortfolios()`, `getAllocations()`, `getBudgetSummary()`, `getBudgetJournalLines()`, dll.
- Data dengan `accountId = 0` (hasil migrasi dari v1.1.5) tampil di semua akun sebagai data netral.

#### 5. Perubahan ViewModel / Repository (realisasi)

`KronRepository.kt` dan `MainViewModel.kt`:

- `KronUiState` menyertakan `activeAccountId: Long`
- `observeCashBalance(accountId)` sudah ada pola yang benar
- `observeActivities()` parameternya `accountId`
- `observePortfolios()` parameternya `accountId`
- Repository: filter tambahan `it.accountId == 0L` untuk vault/unallocated/rollover/cashflow menampilkan data netral lintas akun

#### 6. Prioritas vs F5

F7 adalah **prasyarat konseptual** untuk F5. Tanpa isolasi data per akun:

- Akun team akan mencampur data dengan akun privat
- Tidak bisa membedakan "data team" vs "data pribadi"
- Backup/restore per akun tidak mungkin

#### 7. Risiko dan Mitigasi (realisasi)

| Risiko | Dampak | Mitigasi |
|--------|--------|----------|
| Portfolio global diassign ke akun yang salah | Akun lain kehilangan budget history | Tampilkan dialog konfirmasi saat migration dengan info akun yang akan dipilih |
| Query lama tanpa filter accountId | Semua data tetap tampil (fallback aman) | Default parameter `accountId = null` pada query lama, bertahap migrasi ke query baru |
| ActivityEvent tanpa cash_journal_lines (data corrupt) | Backfill gagal, accountId = 0 | Skip event tersebut atau assign ke akun aktif, log warning |
| BudgetJournalLine bucket orphan | accountId = 0 | Assign ke akun aktif, log error untuk audit |

#### 8. Skenario Tes F7

- Migration 8->9, 9->10, 10->11 dengan berbagai kombinasi data multi-akun
- Backfill ActivityEvent yang punya 1 vs banyak cash_journal_lines
- Portfolio dengan 0, 1, atau >1 allocation periods
- Bucket dengan dan tanpa allocationId
- Data hanya 1 akun (seharusnya tidak berubah perilaku)
- Data 2+ akun aktif (verifikasi isolasi setelah migration)
- Query lama tanpa filter accountId tetap mengembalikan semua data
- Query baru dengan filter hanya mengembalikan data akun yang dimaksud
- Financial invariants per account setelah migration
- Rollback migration jika terjadi error

### Redesign F5 - Akun Team yang Direkomendasikan

#### 1. Pisahkan Konsep Workspace dari Account

Menambahkan semua field team langsung ke `AccountEntity` akan membuat model akun privat dan workspace bersama bercampur. Model yang lebih bersih:

```kotlin
TeamWorkspaceEntity(
    workspaceId: String,          // UUID stabil
    name: String,
    driveFileId: String,
    driveFolderId: String?,
    ownerAccountEmailHash: String?,
    role: TeamRole,
    keyAlias: String,
    lastSyncedGeneration: Long,
    lastSyncedAt: Long?,
    status: TeamSyncStatus
)
```

Tambahkan cache terpisah seperti `TeamSnapshotEntity` atau tabel read-only yang semuanya memiliki `workspaceId`. Hindari mencampurkan snapshot remote ke tabel jurnal privat authoritative tanpa batas sumber yang jelas.

#### 2. Jangan Menyimpan Kunci Team sebagai String di `AccountEntity`

`teamEncryptedKey: String?` terlalu ambigu dan mudah disalahgunakan. Yang disimpan di database sebaiknya hanya:

- alias Android Keystore;
- encrypted/wrapped key blob jika memang diperlukan;
- versi key;
- metadata recovery.

Material kunci plaintext tidak boleh ditulis ke log, analytics, crash report, clipboard permanen, atau database sebagai Base64 biasa. Android Keystore sebaiknya digunakan agar key material sulit diekstrak dari perangkat.

Catatan: `EncryptedSharedPreferences` telah deprecated. Jangan membangun fitur baru yang bergantung padanya; gunakan Android Keystore secara langsung dan DataStore/Room hanya untuk blob yang sudah dibungkus.

#### 3. Invite Code Saat Ini Belum Memiliki Model Keamanan yang Cukup

Membungkus key dengan passphrase sementara lalu menaruh passphrase/material terkait di invite code yang sama tidak memberi perlindungan berarti. Base64 juga bukan enkripsi.

Untuk keluarga kecil 1–5 orang, skema tanpa backend yang lebih kuat adalah **join dua langkah**:

1. Device member menghasilkan pasangan kunci publik–privat untuk pertukaran kunci.
2. Member menampilkan `Join Request QR` berisi workspace request ID dan public key.
3. Owner memindai request, menambahkan email member ke permission Drive, lalu mengenkripsi team key khusus untuk public key member.
4. Owner menghasilkan `Approval QR/code` pendek atau menulis envelope member ke file manifest Drive.
5. Member mengunduh envelope, membuka team key dengan private key lokal, lalu menyimpannya melalui Keystore.

Keuntungan:

- team key tidak perlu muncul mentah di satu invite code;
- setiap member memiliki envelope sendiri;
- removal dan rotasi key lebih mudah dikelola;
- invite yang bocor tidak otomatis memberi akses ke semua orang.

Untuk MVP yang lebih sederhana, secret dalam QR masih dapat digunakan, tetapi harus dinyatakan sebagai kompromi keamanan dan tetap digabungkan dengan permission Drive per-email.

#### 4. Tambahkan Recovery dan Revocation Sejak Awal

Tanpa recovery, kehilangan perangkat owner dapat membuat data tidak dapat didekripsi. Tambahkan:

- recovery code/key yang dibuat saat workspace dibuat;
- peringatan bahwa recovery code harus disimpan offline;
- prosedur ganti perangkat owner;
- prosedur re-invite member setelah restore;
- key version dan key rotation.

Saat member dikeluarkan:

1. hapus permission Drive member;
2. generate team key versi baru;
3. enkripsi ulang snapshot;
4. buat envelope baru untuk member yang masih aktif.

Revocation tidak dapat menghapus salinan data lama yang sudah pernah diunduh member. Batasan ini harus dijelaskan secara jujur di UI.

#### 5. Snapshot Harus Memiliki Manifest dan Versioning

Format blob minimal direkomendasikan:

```text
magic
formatVersion
workspaceId
schemaVersion
generation
createdAt
previousGenerationHash
keyVersion
compression
nonce
ciphertext + authentication tag
```

Metadata penting harus diikat sebagai AES-GCM AAD agar tidak dapat ditukar antar-workspace. Tambahkan hash konten/ciphertext untuk cache check dan diagnosis korupsi.

Untuk read-only MVP dengan owner sebagai satu-satunya penulis, pertimbangkan signature owner pada manifest agar member dapat membedakan snapshot resmi owner dari blob yang dibuat pihak lain yang memperoleh team key.

#### 6. Data Team Harus Menjaga Sifat Double-Entry dan Audit

Snapshot jangan hanya berisi saldo akhir. Sertakan jurnal/event authoritative yang dibutuhkan untuk membangun ulang:

- journal entry ID UUID yang stabil;
- debit/credit legs;
- account/category/portfolio references;
- timestamps dan logical sequence;
- audit metadata;
- tombstone/void event bila ada koreksi;
- schema version.

Setelah download, jalankan validator:

- total debit == total credit;
- tidak ada duplicate journal ID;
- semua foreign reference valid;
- generation tidak mundur;
- workspaceId cocok;
- blob tidak melampaui batas ukuran yang ditentukan.

Jika validasi gagal, jangan mengganti cache terakhir yang masih sehat.

#### 7. Read-Only Harus Dipaksa di Domain Layer

Menyembunyikan FAB dan menonaktifkan tombol tidak cukup. Semua mutasi harus ditolak di repository/use-case jika sumber data adalah team workspace dengan role VIEWER.

Direkomendasikan:

- repository team read-only terpisah; atau
- policy guard terpusat sebelum setiap command;
- unit test yang memanggil repository secara langsung tanpa UI;
- deep link dan state restoration tetap tidak dapat membuka aksi edit.

#### 8. Strategi Sync Owner

Debounce 30 detik menggunakan one-time WorkManager masuk akal, tetapi gunakan:

- unique work per `workspaceId`;
- network constraint;
- exponential backoff;
- dirty generation/outbox yang ditulis dalam transaksi database yang sama dengan perubahan ledger;
- upload hanya setelah transaksi lokal commit;
- status `DIRTY`, `SYNCING`, `SYNCED`, `FAILED`;
- retry aman dan idempotent;
- `ETag`/version check sebelum update file.

Jangan hanya memanggil scheduler dari beberapa fungsi repository secara manual karena mudah ada jalur mutasi yang terlupakan. Lebih aman gunakan satu outbox/sync-dirty mechanism pada boundary transaksi.

#### 9. Strategi Sync Member

WorkManager periodik 30 menit valid secara interval, tetapi eksekusinya **tidak presisi** dan dapat ditunda sistem. UI tidak boleh menjanjikan update tepat setiap 30 menit.

Prioritas trigger:

1. manual refresh;
2. refresh saat app dibuka jika cache stale;
3. periodik sebagai fallback;
4. retry saat jaringan kembali tersedia.

Gunakan generation/hash untuk menghindari download penuh jika tidak berubah. Notifikasi sebaiknya generik secara default, misalnya “Data akun tim diperbarui”, tanpa nominal atau nama transaksi pada lock screen.

#### 10. Jangan Gunakan Last-Write-Wins untuk Editor Keuangan

Last-write-wins dapat menghilangkan transaksi sah tanpa jejak. Untuk fase editor, gunakan pendekatan event-based:

- setiap device menghasilkan event append-only dengan UUID dan device ID;
- event idempotent;
- merge berupa union event, bukan overwrite snapshot;
- koreksi menggunakan reversal/void event;
- logical clock atau server/Drive generation digunakan untuk ordering;
- conflict pada entitas mutable seperti nama budget ditampilkan eksplisit atau dimediasi owner;
- setiap event dapat ditandatangani device/member bila threat model memerlukannya.

Sebelum model merge selesai, pertahankan **single writer: owner**.

### Rekomendasi Roadmap Revisi

> **Catatan**: Roadmap di bawah adalah usulan pra-implementasi. Realisasi rilis berbeda (lihat tabel Strategi Rilis di bagian Rencana Implementasi).

| Fase | Versi yang disarankan | Isi | Catatan |
|:----:|:---------------------:|-----|---------|
| 0 | sebelum 1.2.0 | Tambah test clock/date range, formatter, dan report domain model | Mengurangi bug F1 dan regresi finansial |
| 1 | 1.2.0 | F1 + F6 + F4 transisi | F4 marker note harus diberi technical-debt ticket |
| 2A | 1.3.0 | F3 tanpa geotag default dan tanpa CAMERA permission bila memakai kamera eksternal | Migration 6->7 untuk field metadata receipt |
| 2B | 1.3.0 | F7 - Isolasi Data per Akun | Migration 7->8 untuk accountId di 3 entity; backfill deterministik; update DAO/ViewModel |
| F5 | Ditunda | F5 - Akun Team (fondasi, hardening, editor) | Implementasi ditunda sampai diputuskan siap. Desain dan analisis sudah ada di dokumen ini. |

Estimasi F5 sebesar 10–14 hari dinilai terlalu optimistis jika mencakup Drive ACL, auth multi-user, E2EE key exchange, recovery, revocation, cache, migration, notifikasi, dan pengujian. Lebih aman memecahnya menjadi fondasi read-only dan hardening, lalu menetapkan estimasi setelah spike/prototype Drive sharing berhasil.

### Rekomendasi Release Gate dan Testing Lintas Fitur

Sebelum setiap rilis, tambahkan gate berikut:

#### Financial Invariants

- total debit sama dengan total credit;
- transfer tidak mengubah total kekayaan kecuali fee eksplisit;
- tidak ada hard-delete jurnal;
- koreksi menghasilkan event audit/reversal yang dapat dilacak;
- snapshot/import tidak membuat duplicate journal;
- saldo hasil rebuild sama dengan saldo materialized/cache.

#### Database dan Backup

- migration dari setiap schema yang masih didukung, bukan hanya versi tepat sebelumnya;
- migration chain, misalnya 6→7→8;
- backup lama dapat direstore ke versi baru;
- backup round-trip dengan attachment;
- restore gagal tidak boleh menimpa database sehat;
- simulated process death saat migration/restore;
- schema export masuk version control.

#### Security dan Privacy

- tidak ada key, invite secret, nominal, path receipt, atau koordinat di log;
- screenshot/recents policy diputuskan untuk layar sensitif;
- clipboard secret memiliki warning dan auto-clear bila memungkinkan;
- notification privacy diuji pada lock screen;
- temporary decrypted file dibersihkan;
- threat model didokumentasikan: pencurian perangkat, Drive compromise, invite bocor, member berbahaya, root, dan backup bocor.

#### Sync dan Resilience

- offline-first tetap dapat dipakai;
- timeout, retry, backoff, rate limit, sign-out, dan akun Google berganti;
- upload terputus tidak merusak file terakhir;
- cache lama tetap tersedia saat download baru gagal;
- generation lama atau replay ditolak;
- dua worker bersamaan tidak menyebabkan upload ganda atau status salah.

#### UI dan Accessibility

- font scale besar;
- TalkBack/content description;
- informasi tidak hanya melalui warna;
- `valuesHidden` menutup semua turunan data yang sensitif;
- layout pada layar kecil dan landscape;
- loading, empty, error, retry, dan stale state terlihat jelas.

### Definition of Done yang Disarankan

Sebuah fitur dianggap selesai hanya jika:

1. aturan bisnis berada di domain/data layer, bukan hanya UI;
2. seluruh edge case utama memiliki test otomatis;
3. migration dan backup compatibility lulus;
4. financial invariants lulus setelah operasi dan restore;
5. tidak ada data sensitif di log atau cache publik;
6. error tidak menghapus data sehat terakhir;
7. dokumentasi format data dan keputusan keamanan diperbarui;
8. release build, lint, unit test, instrumentation/migration test, dan smoke test lulus;
9. rollback/feature flag tersedia untuk fitur berisiko seperti Team sync;
10. user-facing limitation ditulis dengan jujur, terutama keterlambatan sync dan keterbatasan revocation.

### Referensi Teknis Resmi yang Digunakan untuk Verifikasi

- [Google Drive - Store application-specific data](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Google Drive - Share files, folders, and drives](https://developers.google.com/workspace/drive/api/guides/manage-sharing)
- [Google Drive - Choose Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Android - Minimize permission requests](https://developer.android.com/privacy-and-security/minimize-permission-requests)
- [Android - Define WorkManager requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Android - Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- [Android - EncryptedSharedPreferences API](https://developer.android.com/reference/kotlin/androidx/security/crypto/EncryptedSharedPreferences)
- [Android - ExifInterface API](https://developer.android.com/reference/kotlin/androidx/exifinterface/media/ExifInterface)
- [Android - Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)

---

## Kredit Analisis

**Analisis teknis, review arsitektur, dan rekomendasi tambahan:**  
**ChatGPT - GPT-5.6 Thinking, OpenAI**

**Tanggal:** 20 Juli 2026

> Kredit ini menjelaskan pihak yang menyusun bagian “Analisis Teknis dan Rekomendasi Tambahan”. Keputusan implementasi akhir tetap berada pada pemilik dan pengembang aplikasi KRON.

---

### 2026-07-21 - Sesi 2: Implementasi F3 + F7 + Perbaikan UI

**Ringkasan sesi:**
- Implementasi F3 kamera (gagal dengan intent-based, sukses dengan CameraX)
- Implementasi F7 isolasi akun (migration 8->9->10->11, DAO/Repository filter)
- Perbaikan unexpected expense (VAULT bucket instead of UNEXPECTED)
- Flow resilience (retry Long.MAX_VALUE di uiState)
- Perbaikan UI tema (container full width, icon benar, light mode muted)
- Riwayat budget per portfolio + grafik batang planned vs spent
- Sync Drive dinonaktifkan karena OAuth bermasalah
- Backup/restore diketahui bermasalah, perlu perbaikan terpisah
- **Total 13 rilis**: 1.3.7 s.d. 1.3.19

### 2026-07-21 - Sesi 3: Perbaikan OnboardingScreen

- **Masalah**: OnboardingScreen ("Setiap rupiah punya jejak") selalu tampil sesaat setiap buka aplikasi, padahal seharusnya hanya untuk pengguna baru
- **Akar masalah**: `stateIn(initialValue = KronUiState())` emit `onboardingComplete=false` secara sinkron sebelum combine flow dari DataStore selesai
- **Fix**: Di `KronApp.kt:172`, hanya tampilkan OnboardingScreen jika `state.accounts.isNotEmpty()` (tanda state sudah termuat, bukan default). Jika akun masih kosong, state masih initial default, tidak usah render apa pun
- **Skenario**:
  - Pengguna baru: setelah seed, accounts non-empty, onboardingComplete=false -> tampil OnboardingScreen
  - Pengguna lama: saat state termuat, accounts non-empty, onboardingComplete=true -> langsung ke konten utama
- **Release**: 1.3.20 (versionCode 51) -- APK, SHA-256, R8 mapping, schema-11.json diarsipkan di releases/1.3.20/

### 2026-07-22 - Sesi 4: Fix validasi seal jurnal untuk income

- **Tiket**: "Total split tidak sesuai pengeluaran" error setiap tambah pemasukan
- **Akar masalah**: `LedgerPostingEngine.validateSealable()` bandingkan total split dengan `cashOutflow` saja (filter negatif). Income hanya punya cash inflow (positif), jadi `cashMagnitude=0` tapi split sum > 0.
- **Fix**: Ganti `filter { it.amount < 0 }.sumOf { -it.amount }` jadi `sumOf { abs(it.amount) }` -- mencakup inflow dan outflow.
- **Release**: 1.5.1 (versionCode 61) -- diinstall via ADB, APK diarsipkan di releases/1.5.1/

### 2026-07-22 - Sesi 5: Diagnosa Drive Sync + Update Roadmap

- **Masalah**: Drive Sync gagal di Credential Manager -- timeout 30 detik "Google tidak merespon"
- **Pemeriksaan**:
  - Web client ID: `677791134689-rb53arqc2k6ror17oku0pvm3nmfrcnkc.apps.googleusercontent.com` cocok dengan file di `Secret/`
  - Android client: `677791134689-onu28oduh8h4ql632h8aphh4nad1qjbr.apps.googleusercontent.com`
  - Scope `drive.appdata` + `drive.file` sudah ditambahkan di GCP OAuth consent screen
  - Test user sudah terdaftar, internet aktif, Google Play Services versi 26.26.34
  - Belum ketemu penyebab pasti -- kemungkinan Credential Manager tidak kompatibel penuh dengan perangkat
- **Rencana**:
  - Ekstensi file: `.krondrive` untuk sync private, `.kronshare` untuk team/share
  - Scope `drive.appdata` untuk private, `drive.file` untuk team
  - Bypass Credential Manager mungkin diperlukan (langsung ke AuthorizationClient)
  - F5 team ditunda sampai fondasi Drive privat stabil
