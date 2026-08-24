# Investigasi Auto Sync Mengganggu Input

Tanggal: 2026-08-24
Status: analisis selesai, belum ada perubahan produksi
Pelapor: laporan pengguna — auto sync berjalan saat menginput sehingga transaksi gagal tersimpan
Kontrak yang dilaporkan: auto sync hanya 15-30 detik setelah aksi atau saat aplikasi awal dibuka

Dokumen ini adalah laporan analisis read-only. Tidak ada perubahan kode produksi pada area terlindungi. Perubahan produksi memerlukan approval eksplisit sesuai `docs/DRIVE_SYNC_CHANGE_CONTROL.md`.

## Kontrak vs implementasi saat ini

### Private Drive Sync
- Debounce dijadwalkan di `app/src/main/java/com/morneven/kron/sync/DriveSyncRuntime.kt:744-762`
  - `observeSyncState()` → `distinctUntilChanged()` pada `localGeneration/lastSyncedGeneration` → jika `changed && localGeneration > lastSyncedGeneration && isReady()` → `DriveSyncScheduler.scheduleAfterChange(context, wifiOnly)` 
  - `DriveSyncScheduler.scheduleAfterChange` di `app/src/main/java/com/morneven/kron/sync/DriveSyncWorker.kt:110-121` memakai `setInitialDelay(15, SECONDS)` dengan `ExistingWorkPolicy.REPLACE`. Ini sesuai kontrak 15-30 detik.
- Saat aplikasi awal dibuka: `DriveSyncRuntimeFactory.start` → `installBackgroundIfReady(syncImmediately=true)` di `app/src/main/java/com/morneven/kron/sync/DriveSyncRuntime.kt:905-921` melakukan `schedulePeriodic(12h)` + `syncNow()` immediate. Sesuai kontrak "saat aplikasi awal dibuka".
- Trigger generasi: `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:1320-1352` membuat `sync_generation_*` AFTER INSERT/UPDATE/DELETE pada `accounts, categories, portfolios, budget_periods, allocations, portfolio_allocation_templates, activity_events, recurring_rules, receipts, debts, debt_entries` yang mengincrement `sync_state.localGeneration`. Scope dibatasi ke akun PRIVATE via `privateScopes` map.

### Team Sync
- `app/src/main/java/com/morneven/kron/team/TeamSyncRuntime.kt:95-104` `scheduleAfterChange` memakai `setInitialDelay(7, SECONDS)` dengan `ExistingWorkPolicy.REPLACE`.
- Observer di `app/src/main/java/com/morneven/kron/team/TeamSyncRuntime.kt:158-168` memakai `observeAutoSyncTeamWorkspaces()` → `distinctUntilChanged` pada `generation` map → `scheduleAfterChange` 7 detik.
- `CHANGELOG.md:88` mencatat 1.5.22 menurunkan debounce 30 detik menjadi 7 detik agar data lebih cepat muncul. Nilai 7 detik di luar kontrak 15-30 detik yang dilaporkan pengguna.
- Trigger Team: `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:1400-1493` `team_generation_*` pada `accounts, categories, portfolios, budget_periods, allocations, portfolio_allocation_templates, recurring_rules, debts, activity_events, receipts, debt_entries, team_invitation_uses`. Setiap transaksi finansial yang menambah `activity_events` juga menaikkan `team_workspaces.generation` jika akun aktif adalah TEAM, sehingga satu input memicu dua jalur auto sync (Private 15s + Team 7s) secara paralel.

### Periodic
- Private periodic `12h, flex 1h` di `app/src/main/java/com/morneven/kron/sync/DriveSyncWorker.kt:84-95`
- Team periodic `12h, flex 1h` di `app/src/main/java/com/morneven/kron/team/TeamSyncRuntime.kt:73-82`
- Periodic tidak melanggar kontrak 15-30s karena ini jadwal latar belakang terpisah.

## Akar masalah transaksi gagal

### Guard SQLite yang memblokir tulis
`app/src/main/java/com/morneven/kron/data/KronDatabase.kt:1377-1397` membuat guard:
```sql
CREATE TRIGGER sync_write_guard_{table}_{insert|update|delete}
BEFORE INSERT/UPDATE/DELETE ON {guardedTables}
WHEN EXISTS(SELECT 1 FROM sync_state WHERE id=1 AND status IN ('SYNCING','RESTART_REQUIRED','DOWNLOADING'))
BEGIN SELECT RAISE(ABORT, 'KRON sedang menyinkronkan atau menunggu restart'); END
```
`guardedTables` = `generationTables` + `cash_journal_lines, budget_journal_lines, transaction_splits, recurring_occurrences, audit_snapshots, ledger_accounts, ledger_lines, journal_seals, evidence_keys` di `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:1355-1365`.

`DriveSyncCoordinator.kt:428-433` set `status=SYNCING` sebelum `describe()` dan `listSnapshots()` dan tahan sampai upload/download selesai. Timeout `SYNC_TIMEOUT_MILLIS=180_000` di `app/src/main/java/com/morneven/kron/sync/DriveSyncCoordinator.kt:224`. Selama 180 detik ini semua tulis finansial dibatalkan.

`KronRepository.kt:108` memiliki `snapshotOperationLock` dengan mutex statik di `app/src/main/java/com/morneven/kron/security/SnapshotOperationLock.kt:19`, tetapi hanya `activateAccount:254` yang memakai `withLock`. Metode `addIncome:309`, `addExpense:378`, `transfer:713`, `createDebt:482` dll hanya memakai `database.withTransaction` tanpa lock, sehingga tidak menunggu sync — langsung kena ABORT.

### Mutex terpisah
- `SnapshotOperationLock.mutex` (statik) dipakai `BackupManager` dan `BackupManagerLocalSnapshotSource:26,59` untuk export snapshot.
- `DriveSyncRuntimeFactory.processSyncMutex:708` dan `DriveSyncCoordinator.syncMutex:221` dipakai jalur sync.
- Karena dua mutex berbeda, transaksi dan sync tidak serial via Mutex. Guard dipilih sebagai jalan pintas untuk mencegah divergensi, dengan efek samping memblokir UX.

### Timeline tabrakan tipikal
1. T0: transaksi T1 commit → `sync_generation_*` → `localGeneration 5` → `scheduleAfterChange 15s` (REPLACE)
2. T15: `DriveSyncWorker.doWork:49` → `coordinator.syncNow()` → `status=SYNCING`
3. T20: pengguna simpan T2 → `BEFORE INSERT ON activity_events` → guard cek `SYNCING` → `ABORT` → repository lempar `SQLiteConstraintException` → UI tampil gagal simpan

Jika akun TEAM, langkah 1 juga menjadwalkan Team debounced 7s, sehingga jendela SYNCING bisa dimulai lebih awal (7s) dan tumpang tindih dengan Private 15s, memperpanjang total waktu block.

## Dampak
- Input yang berbarengan dengan jendela sync gagal total, bukan ditunda.
- User tidak mendapat retry otomatis, harus input ulang.
- `activateAccount` sudah dikecualikan dari guard via `OF name,isArchived,...` di `KronDatabase.kt:1328` dan tidak menulis audit `KronRepository.kt:259`, sehingga ganti akun tidak kena block, tetapi transaksi finansial tetap kena.

## Rencana perbaikan minimal (tidak diimplementasi di dokumen ini)

Perubahan produksi pada area terlindungi memerlukan approval eksplisit dan trailer `Drive-Change-Approval:` sesuai `docs/DRIVE_SYNC_CHANGE_CONTROL.md`. Rencana di bawah adalah usulan, bukan eksekusi.

### Opsi lazy (ponytail)
1. Hapus pembuatan `sync_write_guard_*` di `KronDatabase.kt:1377-1397` atau ubah menjadi no-op. Alasan: guard adalah constraint DB yang menggantikan serialisasi aplikasi. Penghapusan adalah delesi, bukan penambahan.
2. Bungkus semua transaksi tulis di `KronRepository` dengan `snapshotOperationLock.withLock` yang sudah ada (reuse mutex statik). Contoh: `suspend fun addIncome(...) = snapshotOperationLock.withLock { database.withTransaction { ... } }`. Sync sudah memakai lock saat `describe:26` dan `createPortableSnapshotPayload:124` di `BackupManager.kt`, sehingga transaksi akan `await` bukan `ABORT`. Export snapshot <1 detik, bukan block 180 detik.
3. Samakan debounce Team menjadi 15 detik di `TeamSyncRuntime.kt:98` `setInitialDelay(15, SECONDS)` agar kembali ke kontrak 15-30 detik. Hapus penjadwalan ganda `installBackgroundIfReady` + observer generasi pertama yang langsung schedule debounced saat `localGeneration > lastSyncedGeneration`.

```
// ponytail: sync waits via Mutex, DB guard removed — re-add guard if invariant proves race without it
// ponytail: 15s debounce unified, per-account lock if throughput matters
```

Skipped: custom retry queue, exponential backoff baru, config tambahan. Add when throughput atau kontensi Mutex terukur.

### Checklist sebelum merge produksi
- Unit, migration, lint, debug/release build, signature verification
- Install-over terhadap APK produksi sebelumnya (minimal 1.0.21 dan rilis sebelumnya)
- Private Drive: upload, pull, no-change, remote kosong, conflict, account switch, recovery copy, WiFi-only, seluler, isolasi akun
- Team: Owner/Editor/Viewer, multi-member, join/leave/rejoin, push/pull dua arah, auto-sync 15s, conflict, fresh recovery, ACL
- Invariant ledger, Cash, eBudget, Vault, allocation, rollover, debt graph, audit append-only, proof chain, active-account isolation
- Logcat tidak mengandung data sensitif

## Verifikasi analisis
- Inspeksi file: `DriveSyncWorker.kt`, `DriveSyncRuntime.kt`, `TeamSyncRuntime.kt`, `DriveSyncCoordinator.kt`, `KronDatabase.kt`, `KronRepository.kt`, `SnapshotOperationLock.kt`, `BackupManagerLocalSnapshotSource.kt`, `CHANGELOG.md`, `KRON_STABILIZATION_REPORT.md`
- Tidak ada eksekusi perubahan produksi, hanya pembacaan dan penulisan docs
- Temuan didasarkan pada kode yang ada, bukan spekulasi

## Tindak lanjut yang diminta
- Konfirmasi apakah kontrak 15-30 detik harus berlaku untuk Team juga (saat ini 7 detik) atau Team memang dikecualikan
- Approval untuk implementasi produksi jika usulan di atas disetujui, dengan referensi approval untuk trailer commit
