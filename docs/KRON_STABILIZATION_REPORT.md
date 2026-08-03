# Laporan Stabilisasi KRON

## Bug ditemukan

- Lokasi: `DriveSyncCoordinator.upload`, pemeriksaan `minimumAppVersionCode`.
- Alasan/akar masalah: build 1.6.6 menulis code aplikasi saat ini ke manifest snapshot, padahal payload, key derivation, dan schema tetap sama dengan 1.6.4. APK 1.6.4 lalu menolak snapshot tersebut.
- Dampak: data yang sudah tersinkron pada 1.6.6 tidak dapat dibuka dari APK 1.6.4.

## Fix yang dilakukan

- Perubahan minimum: source dikembalikan ke checkpoint `v1.6.4`; versionCode/versionName hanya dibump untuk install-over; manifest snapshot baru memakai compatibility floor code 89.
- Jalur yang ikut tercakup: upload snapshot aktif/recovery dan sinkronisasi berikutnya. Jika head lama memakai metadata 1.6.6, sinkronisasi tanpa perubahan membuat snapshot penuh baru dengan parent head lama dan metadata kompatibel.
- Tidak diubah: Room schema, key mode, database structure, Team graph, Kapsul, UI, scheduler, dan alur sinkronisasi 1.6.4.

## Hasil perbaikan

- Status: done untuk source audit, unit test, lint, build, signature, install-over, dan smoke startup.
- Bukti uji: `testDebugUnitTest`, `lintDebug`, `lintRelease`, `assembleDebug`, `assembleRelease`, signature APK release v2, serta kedua package hidup setelah install-over tanpa fatal/foreign-key error pada logcat.
- Catatan risiko: instrumentasi end-to-end Drive/Team belum dijalankan karena memerlukan akun/jaringan dan pemasangan APK test dapat meminta konfirmasi Android. Snapshot lama harus menjalankan satu sync pada build ini agar alias kompatibel terunggah.

## Pergantian akun Google

### Bug ditemukan

- Lokasi: `DriveSyncRuntime.connectWithAccountEmail`, trigger `accounts` di `KronDatabase`, dan `KronRepository.activateAccount`.
- Alasan/akar masalah: state akun Google, secret, worker, dan cursor lama dapat berubah sebelum otorisasi tujuan selesai. Pemilihan akun lokal juga menulis event audit ketika trigger sync sedang aktif, sehingga SQLite membatalkan transaksi dengan code 1811.
- Dampak: pergantian akun gagal, state lama tidak konsisten, atau akun lokal tidak dapat diaktifkan saat sync berjalan.

### Fix yang dilakukan

- Perubahan minimum: pergantian memakai mutex yang sama dengan sync, staged secret crash-safe, commit identitas dan dataset baru setelah otorisasi berhasil, serta rollback state lama bila tahap apa pun gagal. Update `isActive` tidak menaikkan generation dan tidak diblokir oleh guard sync; event audit tidak ditulis pada periode tersebut.
- Jalur yang ikut tercakup: picker akun manual, otorisasi interaktif, callback resolution, pembatalan, worker, sync manual, dan remote kosong yang meminta konfirmasi sebelum upload.

### Hasil perbaikan

- Status: done untuk unit/build/smoke.
- Bukti uji: 79 unit test lulus, lint debug/release lulus, dua APK install-over berhasil, dan startup tanpa `SQLITE_CONSTRAINT`, `FOREIGN KEY`, atau fatal exception pada logcat.
- Catatan risiko: Drive dua akun nyata tetap perlu diuji pada jaringan aktif untuk membuktikan ACL dan remote dataset.

## Namespace Drive

### Bug ditemukan

- Lokasi: `TeamDriveRestClient.createWorkspace` dan `CapsuleDriveClient.upload`.
- Alasan/akar masalah: folder Team dan file Kapsul baru dibuat langsung di root Drive sehingga artefak KRON berulang dan sulit dibedakan.
- Dampak: root Drive berantakan dan folder baru terus dibuat.

### Fix yang dilakukan

- Perubahan minimum: helper idempotent `KronDriveNamespace` membuat atau memakai `KRON/Team/team-<teamId>` dan `KRON/Capsule` dengan parent serta `appProperties` yang tepat. Private Sync tetap memakai `appDataFolder`; artefak lama tidak dipindah atau dihapus.
- Jalur yang ikut tercakup: pembuatan workspace Team dan upload Kapsul; akses file lama tetap memakai file ID exact.

### Hasil perbaikan

- Status: done untuk kompilasi, lint, dan build release.
- Bukti uji: endpoint baru terkompilasi dan APK terpasang; uji Drive nyata belum dijalankan pada sesi ini.

## Autentikasi Kapsul dan UI

### Bug ditemukan

- Lokasi: `SecureViewerActivity` dan `MainScaffold`.
- Alasan/akar masalah: viewer hanya menawarkan biometrik dan kode akses ditampilkan sebagai blok panjang yang tidak praktis.
- Dampak: pengguna tanpa biometrik tidak dapat membuka Kapsul dan alur salin kode kurang jelas.

### Fix yang dilakukan

- Perubahan minimum: viewer memakai `BIOMETRIC_STRONG` atau kredensial perangkat, dengan fallback `KeyguardManager` untuk API lama dan fail-closed saat tidak tersedia. Undangan Team dan Kapsul memakai satu field read-only dengan tombol salin trailing. Tab aktif tidak menjalankan transition dan pill indikator bergerak halus.
- Jalur yang ikut tercakup: pembukaan Kapsul, rotasi, pembuatan undangan, pembuatan Kapsul, dan navigasi lima tab.

### Hasil perbaikan

- Status: done untuk compile, lint, unit test, signature, install-over, dan smoke startup.
- Catatan risiko: dialog PIN/biometrik dan alur Drive/Kapsul end-to-end tetap memerlukan interaksi perangkat nyata.

## Deteksi format kunci database

### Bug ditemukan

- Lokasi: `DatabaseEncryptionManager.canOpenEncrypted` dan `canOpenPlaintext`.
- Alasan/akar masalah: pemeriksaan format kunci mencampur keberhasilan membuka database dengan `foreign_key_check`. Database yang dapat dibuka dengan kunci benar tetapi memiliki relasi rusak dilaporkan seolah-olah semua format kunci tidak cocok.
- Dampak: layar recovery menampilkan diagnosis kunci yang salah dan menghambat pemulihan yang aman.

### Fix yang dilakukan

- Perubahan minimum: deteksi kunci hanya memeriksa integritas cipher/schema; validasi foreign-key tetap dijalankan terpisah sebelum aktivasi database.
- Jalur yang ikut tercakup: bootstrap, migrasi, export/import, restore, dan aktivasi kandidat terenkripsi.

### Hasil perbaikan

- Status: done untuk unit test, lint, build, install-over, dan startup debug/release.
- Bukti uji: kedua APK terpasang dengan `adb install -r`; debug kembali membuka database lokal dan tidak menampilkan layar recovery pada cold start terakhir.
- Catatan risiko: bila probe passphrase dan raw key benar-benar sama-sama gagal, KRON tetap menolak membuka database dan tidak membuat kunci pengganti. Diperlukan recovery copy dari instalasi yang memiliki kunci asli.

## Snapshot akun Google baru

### Bug ditemukan

- Lokasi: `BackupManagerLocalSnapshotSource.describe`.
- Alasan/akar masalah: `seedIfNeeded()` membuat akun default pada instalasi baru, lalu penanda data lokal menghitung keberadaan akun tersebut sebagai data finansial. Database fresh akhirnya dianggap sudah berisi data dan diarahkan ke konflik saat remote memiliki snapshot.
- Dampak: fresh device tidak dapat langsung memulihkan satu snapshot remote yang valid.

### Fix yang dilakukan

- Perubahan minimum: `hasFinancialData` hanya true bila ada `activity_events`; akun seed dan kategori default tidak memicu konflik. Konfirmasi upload pertama tetap wajib, dan remote kosong tidak pernah diisi otomatis.
- Jalur yang ikut tercakup: fresh sync, pergantian akun Google, upload pertama, deteksi dataset asing, dan pemulihan snapshot privat.

### Hasil perbaikan

- Status: done untuk unit test, lint, build, dan install-over.
- Bukti uji: `testDebugUnitTest` lulus termasuk `freshSeededDatabaseDownloadsTheSingleRemoteDataset`; `assembleDebug` dan `assembleRelease` lulus; kedua APK terpasang dengan `adb install -r`.
- Catatan risiko: konfigurasi non-ledger yang dibuat pengguna tanpa event tidak dianggap sebagai data finansial pada preflight; alur initial-choice tetap menjadi pengaman sebelum upload atau penghapusan remote.

## Preflight akun Drive baru

### Bug ditemukan

- Lokasi: `DriveSyncRuntime.syncNowLocked` dan `DriveSyncCoordinator.syncPendingAccount`.
- Alasan/akar masalah: gerbang pergantian akun sebelumnya selalu menahan sinkronisasi dan menampilkan pilihan awal, termasuk saat remote sudah memiliki snapshot atau database lokal sudah berisi data. Jalur tersebut membuat akun lama/baru terlihat seperti akun baru dan dapat mengarahkan pengguna ke konflik sebelum sinkronisasi normal berjalan.
- Dampak: akun tujuan berisi data tidak langsung menarik atau mengunggah perubahan; konflik nyata tidak masuk Pusat Konflik secara konsisten.

### Fix yang dilakukan

- Perubahan minimum: saat pergantian akun, lakukan preflight read-only. Dialog `Gunakan data lokal`/`Mulai fresh` hanya dikembalikan bila tidak ada snapshot remote sama sekali. Bila remote memiliki satu DAG valid, snapshot Drive tujuan langsung dipakai; data lokal dari akun Google sebelumnya tidak dibandingkan sebagai konflik. Worker tetap menunggu pilihan bila remote kosong agar tidak membuka dialog dari latar belakang.
- Jalur yang ikut tercakup: connect pertama, picker pergantian akun, sync manual, callback otorisasi, dan worker Drive.

### Hasil perbaikan

- Status: done untuk unit test, lint, build, install-over, dan cold start.
- Bukti uji: build penuh debug/release lulus; kedua APK dipasang dengan `adb install -r`; cold start kedua package tidak menghasilkan fatal exception atau `SQLITE_CONSTRAINT_TRIGGER`/foreign-key pada logcat.
- Catatan risiko: uji end-to-end dengan akun Drive nyata tetap memerlukan jaringan dan snapshot yang sesuai; sinkronisasi konflik tidak ditebak atau di-overwrite otomatis.

### Temuan lanjutan

- Bug ditemukan: pilihan `Mulai fresh` tidak meng-commit passphrase yang masih staged pada koneksi pertama/pergantian akun.
- Fix yang dilakukan: jalur fresh memakai finalisasi staged secret yang sama dengan upload, tanpa memvalidasi snapshot yang sengaja dikosongkan.
- Hasil perbaikan: status done; compile, unit test, lint, dan install-over dijalankan ulang.

## Snapshot rusak pada akun tujuan

### Bug ditemukan

- Lokasi: `DriveSyncRuntime.connect` dan `DriveSyncCoordinator.syncPendingAccount`.
- Alasan/akar masalah: jalur Credential Manager belum menyiapkan reset dataset seperti picker manual. Selain itu, snapshot aktif yang rusak langsung diteruskan ke preview konflik; staging lalu mengembalikan `SQLITE_CONSTRAINT_TRIGGER` code 1811.
- Dampak: akun Google tujuan tertentu selalu masuk Pusat Konflik dan tidak dapat memilih data lokal atau memulai dataset baru.

### Fix yang dilakukan

- Perubahan minimum: seluruh jalur otorisasi kini memakai pending switch, mutex, staged secret, reset metadata remote, dan rollback yang sama. State yang masih menunjuk akun Google lama dinormalisasi sebelum keputusan sync. Snapshot pending divalidasi read-only di staging; kandidat rusak kembali ke pilihan awal tanpa menyentuh database aktif.
- Jalur yang ikut tercakup: Credential Manager, picker manual, callback otorisasi, akun remote kosong, snapshot rusak, dan resolver konflik.

### Hasil perbaikan

- Status: done untuk unit test dan build; need attention untuk uji device karena Android menolak install-over dengan `INSTALL_FAILED_USER_RESTRICTED`.
- Bukti uji: `testDebugUnitTest`, `assembleDebug`, dan `assembleRelease` lulus; test `malformedPendingSnapshotReturnsInitialChoiceInsteadOfConflict` lulus; signature release v2 valid.
- Catatan risiko: install APK dan reproduksi akun Google bermasalah harus diulang setelah izin pemasangan ADB diaktifkan. Tidak ada uninstall, clear data, atau penghapusan snapshot yang dilakukan.

## Switch akun tidak boleh menjadi konflik lokal

### Bug ditemukan

- Lokasi: `DriveSyncRuntime.syncNowLocked`, `DriveSyncCoordinator.syncPendingAccount`, dan `DriveSyncDecisionEngine.decide`.
- Alasan/akar masalah: setelah identitas Google diganti, dataset lokal masih memiliki riwayat akun sebelumnya. Jalur keputusan umum membandingkan riwayat tersebut dengan snapshot akun tujuan dan mengklasifikasikannya sebagai konflik, padahal switch adalah pemilihan dataset remote baru.
- Dampak: switch ke akun yang sudah memiliki snapshot dapat membuka Pusat Konflik dan tidak langsung memulihkan data akun tujuan.

### Fix yang dilakukan

- Perubahan minimum: tambahkan penanda `accountSwitchPending`. Saat penanda aktif, remote kosong tetap meminta pilihan awal, sedangkan satu DAG remote yang valid langsung dipakai sebagai sumber (`Download`). Hanya fork atau lebih dari satu dataset remote yang menghasilkan konflik; dataset lokal lama tidak ikut dibandingkan. Penanda dibersihkan setelah apply/upload/fresh atau rollback.
- Jalur yang ikut tercakup: Credential Manager, picker manual, callback otorisasi, switch otomatis, sync manual, dan pemulihan rollback.

### Hasil perbaikan

- Status: done untuk unit test dan compile.
- Bukti uji: `accountSwitchUsesSingleRemoteDatasetInsteadOfComparingWithOldLocalGraph` dan seluruh `testDebugUnitTest` lulus; compile Kotlin debug lulus.
- Catatan risiko: fork atau beberapa dataset pada akun tujuan tetap diblokir demi mencegah pemilihan remote yang ambigu; uji dua akun Drive nyata perlu dijalankan setelah perangkat kembali authorized.
