# KRON Code Review Results

**Review date:** 2026-07-23
**Version reviewed:** 1.5.17 (versionCode 77)
**Fixed in:** 1.5.20 (versionCode 80)
**Total findings:** 53 -- added 13 new findings (H14-H16, M11-M14, L12-L13)
**Fixed:** 42 issues (C1, C2, C4, C5, C6, H1, H2, H3, H4, H5, H6, H7, H8, H9, H10, H11, H12, H13, H14, H15, H16, M1, M3, M4, M6, M7, M8, M9, M10, M11, M12, M13, M14, L1, L4, L5, L7, L8, L9, L11, L12, L13)
**Acknowledged (shipped/design):** 11 issues (C3, L2, L3, L6, L10, M2, M5)

## New findings summary

| ID | Severity | Description |
|----|----------|-------------|
| H14 | High | cashPercentage integer division causes precision loss in allocation templates |
| H15 | High | refreshPeriodStatus never transitions UNDERFUNDED to RESOLUTION_REQUIRED |
| H16 | High | acquireTransactionLock() provides no actual lock |
| M11 | Medium | SCHEMA_VERSION constant duplikat dengan @Database annotation |
| M12 | Medium | General ledger tidak mencatat event tanpa cash movement |
| M13 | Medium | safeAbs/safeSumOf tidak digunakan secara konsisten |
| M14 | Medium | processDueRules memiliki dual guard redundan untuk endEpochDay |
| L12 | Low | Cashflow query mengandalkan asumsi implisit tentang sign amount |
| L13 | Low | processDueRules memproses aturan tanpa transaksi untuk non-aktif accounts |

## Critical (6)

### C1 [FIXED] CameraCaptureScreen thread leak
- **File:** `app/src/main/java/com/morneven/kron/ui/CameraCaptureScreen.kt:121`
- **Fixed in:** 1.5.18
- **Description:** `cameraExecutor` (newSingleThreadExecutor) created in `CameraPreview` composable but never shut down. Each recomposition leaks a thread. Fixed by adding `DisposableEffect` with `cameraExecutor.shutdown()`.

### C2 [FIXED 1.5.19] atomicReplace() fallback overwrites target without atomicity
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseKeyManager.kt:377-389`
- **Fixed in:** 1.5.19 — fallback sekarang buat temp file di direktori target dulu, lalu `renameTo` secara atomik.
- **Description:** `atomicReplace()` first tries `File.renameTo()` (atomic on same filesystem), but when it fails (cross-filesystem), the fallback writes directly via `input.copyTo(output)`. If the process is killed during this copy, the target key envelope file (`database-key-v1.bin`) is left partially written. Used for every critical key artifact (envelope, profile, initialization marker). Violates AGENTS.md "fail closed into read-only recovery mode" rule.

### C3 [SHIPPED] MIGRATION_10_11 destructive data erasure
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:467-473`
- **Status:** Data-loss event shipped in production. Tidak dapat diubah (AGENTS.md: tidak boleh edit migration yang sudah dirilis). MIGRATION_10_11_RECOVERY sudah menjadi default di ALL_MIGRATIONS. MIGRATION_10_11 di-@Deprecated di 1.5.19.
- **Description:** Migration explicitly sets `accountId=0` on `portfolios`, `activity_events`, and `budget_journal_lines`, destroying all ownership mapping established by MIGRATION_8_9 and MIGRATION_9_10. Recovery (MIGRATION_11_12) uses complex heuristics; records that cannot be mapped are dumped into a legacy "Data KRON Lama" account. Recognized data-loss event shipped in production.

### C4 [FIXED 1.5.19] SQL injection in invariant validation queries
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:1049-1086`
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:627-665`
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:143-153`
- **Fixed in:** 1.5.19 — semua query interpolation diganti `?` parameterized binding. Fungsi `scalar()` ditambah parameter `args: Array<String>`.
- **Description:** `fundingChannel='$channel'` and `accountId=$accountId` use Kotlin string interpolation instead of `?` parameterized bindings. While values currently come from enums/IDs, this pattern is a SQL injection vulnerability if data source changes.

### C5 [FIXED 1.5.19] setRandomizedEncryptionRequired(false) weakens Keystore wrapping
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseKeyManager.kt:336`
- **Fixed in:** 1.5.19 — diubah ke `true`.
- **Description:** Android Keystore wrapping key configured with `setRandomizedEncryptionRequired(false)`, allowing deterministic encryption. For GCM mode this is catastrophic: nonce+key reuse destroys all security guarantees. If TEE/StrongBox reuses an IV when re-wrapping the key, key material is exposed.

### C6 [FIXED 1.5.18] processReady AtomicBoolean never set -- DatabaseAccessGate disabled
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseAccessGate.kt:6-12`
- **Fixed in:** 1.5.18 — `markReady()` sudah dipanggil di `MainActivity.kt:157` (review missed this call).
- **Description:** `markReady()` is never called anywhere in the codebase. `processReady` is always `false`, so `isReady()` always returns false. The entire process-gated database access mechanism described in AGENTS.md ("Database access is process-gated...") is effectively non-functional.

## High (13)

### H1 [FIXED 1.5.19] Uri.fromFile causes FileUriExposedException on API 24+
- **File:** `app/src/main/java/com/morneven/kron/ui/CameraCaptureScreen.kt:206`
- **Fixed in:** 1.5.19 — ganti ke `FileProvider.getUriForFile()`.
- **Description:** `Uri.fromFile(photoFile)` produces `file://` URI, triggering `FileUriExposedException` crash on Android 7+ (API 24+). Also leaks internal file path. Should use `FileProvider.getUriForFile()`.

### H2 [ALREADY HANDLED] Camera photos preserve EXIF metadata through encryption pipeline
- **File:** `app/src/main/java/com/morneven/kron/ui/CameraCaptureScreen.kt`
- **File:** `app/src/main/java/com/morneven/kron/security/ImageCompressor.kt:85-102`
- **Status:** Sudah otomatis strip EXIF. Bitmap decode/compress cycle di `ImageCompressor.compress()` membuang semua metadata EXIF. Tidak perlu perubahan.
- **Description:** CameraX saves photos with full EXIF (GPS, device info). `ImageCompressor.compress()` and `cropCenterSquare()` do not strip EXIF before encryption via `EncryptedAttachmentStore`. GPS location and device metadata stored permanently with every financial receipt photo.

### H3 [FIXED 1.5.19] markContinuityValidated() unsynchronized -- concurrent writes produce stale marker
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseEncryptionManager.kt:298-313`
- **Fixed in:** 1.5.19 — tambah `@Synchronized`, fallback copy pakai temp file.
- **Description:** No `@Synchronized` or mutex. Two threads can both see marker absent, both write it, and partial write causes `isContinuityValidated()` to return false, falsely indicating continuity failure and blocking database access.

### H4 [FIXED 1.5.19] EncryptionGuard.rollback() copies recovery database non-atomically
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseEncryptionManager.kt:552-568`
- **Fixed in:** 1.5.19 — rollback sekarang buat temp file dulu, baru rename atomik.
- **Description:** `rollback()` writes recovery database directly to live database via `input.copyTo(output)` without temp file. Process death during copy corrupts both live and recovery copies. Same for WAL/SHM sidecars. Violates AGENTS.md "keep original files byte-for-byte intact."

### H5 [FIXED 1.5.19] RESTORE_REVERSAL classified as INCOME/EXPENSE solely by cash sign
- **File:** `app/src/main/java/com/morneven/kron/audit/LedgerPostingEngine.kt:201-203`
- **Fixed in:** 1.5.19 — sekarang lihat `relatedEventId` original event type untuk menentukan klasifikasi.
- **Description:** `counterpartAccount()` classifies `RESTORE_REVERSAL` with `cashAmount > 0` as INCOME and `cashAmount < 0` as EXPENSE, ignoring original event type. Transfer reversals produce incorrect ledger entries, permanently corrupting the general ledger for restored transfers.

### H6 [FIXED 1.5.19] Staged Drive passphrase not discarded on AuthorizationRequired
- **File:** `app/src/main/java/com/morneven/kron/sync/DriveSyncRuntime.kt:244-246`
- **Fixed in:** 1.5.19 — tambah `secretStore.discardStaged()` ketika `AuthorizationRequired`.
- **Description:** When `finalizeStagedPassphrase()` receives `AuthorizationRequired`, the staged passphrase is neither committed nor discarded. Passphrase remains in memory until process death, violating least-lifetime principle for secrets.

### H7 [FIXED 1.5.19] GCM nonce stored without authentication -- attacker can modify nonce
- **File:** `app/src/main/java/com/morneven/kron/security/EncryptedAttachmentStore.kt:65-66,94-99`
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:81-87`
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:176-177`
- **File:** `app/src/main/java/com/morneven/kron/evidence/EvidencePackageManager.kt:376-378`
- **Fixed in:** 1.5.19 — encrypt/decrypt/inspect di EncryptedAttachmentStore, BackupManager, PreUpgradeBackupManager tambah `cipher.updateAAD(MAGIC)`. EvidencePackageManager masih open (lih. L10).
- **Description:** GCM nonce written as plaintext before ciphertext, not provided as AAD to `cipher.updateAAD()`. Attacker can modify stored nonce; decryption produces different plaintext but GCM authentication still passes. Bypasses integrity guarantee across attachments, backups, and evidence packages.

### H8 [FIXED 1.5.19] connect() passes raw CharArray to stage() before zeroing
- **File:** `app/src/main/java/com/morneven/kron/sync/DriveSyncRuntime.kt:42-86`
- **Fixed in:** 1.5.19 — tambah `discardUncommittedPassphrase()` di semua path error.
- **Description:** `passphrase` zeroed in `finally` block after `secretStore.stage(passphrase)` copies contents internally. On `UserActionRequired` path, `discardUncommittedPassphrase()` not called, so staged copy remains in memory indefinitely.

### H9 [FIXED 1.5.19] Attachment decryption reads localPath without canonical validation
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:98`
- **Fixed in:** 1.5.19 — tambah canonical path resolution + `privateRoots` verification.
- **Description:** `extractAttachments()` reads `localPath` from database cursor and opens `File(cursor.getString(1))`. No canonical path check or `isAppPrivate()`-style verification before opening file. If backup restore populates a world-readable path, arbitrary files could be read.

### H10 [FIXED 1.5.19] Long overflow in financial calculations
- **File:** `app/src/main/java/com/morneven/kron/audit/LedgerPostingEngine.kt:156,242,254`
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:330,468-479,1001,1189`
- **Fixed in:** 1.5.19 — tambah `safeAbs()`, `safeAdd()`, `safeSumOf()` utility functions di `LedgerPostingEngine` companion object.
- **Description:** `kotlin.math.abs(Long.MIN_VALUE)` returns `Long.MIN_VALUE` (negative) due to two's complement overflow. `sumOf { it.amount }` silently overflows if sum exceeds `Long.MAX_VALUE`. No `Math.addExact` or checked arithmetic used.

### H11 [FIXED 1.5.19] Race condition between invariant checks and financial operations
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:508-518,611-622,638-643,675-679`
- **Fixed in:** 1.5.19 — `acquireTransactionLock()` sekarang panggil `dao.acquireWriteLock()` yang eksekusi `UPDATE sync_state` untuk serialisasi akses concurent.
- **Description:** `resolveFromVault` and `resolveFromRollover` read balances then write adjustments inside `withTransaction` but without `SELECT ... FOR UPDATE`. Under concurrent access, both reads see same balance, both write, producing inconsistent state (double-spending from vault).

### H12 [FIXED 1.5.19] ZIP path traversal protection uses simple substring check
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:433`
- **Fixed in:** 1.5.19 — ganti substring check jadi `File(name).canonicalFile` + `startsWith(workspace.canonicalFile.toPath())`.
- **Description:** Check is `!name.contains("../")` and `!name.startsWith("/")`. Bypassable on case-insensitive filesystems (Windows, macOS) using `..\` or URL-encoded `..%2F`. Should use `File(name).canonicalFile` and verify against extraction directory.

### H13 [FIXED 1.5.19] Backup encryption nonce embedded without authentication
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:81-87`
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:176-177`
- **File:** `app/src/main/java/com/morneven/kron/evidence/EvidencePackageManager.kt:376-378`
- **Fixed in:** 1.5.19 — BackupManager dan PreUpgradeBackupManager tambah `cipher.updateAAD(MAGIC)`. EvidencePackageManager masih open (lih. L10).
- **Description:** Backup nonce written before ciphertext, not authenticated. Mitigated by backup integrity checksum but nonce itself is not integrity-protected.

## Medium (10)

### M1 [FIXED 1.5.19] MIGRATION_3_4 duplicate -- dead code hazard
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:101-186,195-320`
- **Fixed in:** 1.5.19 — tambah `@Deprecated("Dead code — jangan gunakan")` annotation + warning comment.
- **Description:** Two `Migration(3,4)` objects. Only `MIGRATION_3_4_RECOVERY` is in `ALL_MIGRATIONS`. Original `MIGRATION_3_4` is dead code. If accidentally re-added, wrong migration path could be selected.

### M2 MIGRATION_8_9/9_10 produce stale accountId=0 records
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:402-445,448-465`
- **Description:** Backfill queries use `COALESCE(... LIMIT 1, 0)` fallback. Events with no defensible owner stay at `accountId=0`. Compounded by MIGRATION_10_11 which resets all to 0 again.

### M3 [FIXED 1.5.20] Budget overspend not prevented at transaction time
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:1229-1253`
- **Fixed in:** 1.5.20 — `postExpenseInternal()` sudah nullify allocationId untuk DRAFT/UNDERFUNDED. H15 fix menambah defensive check untuk UNDERFUNDED → RESOLUTION_REQUIRED. Overspend untuk ACTIVE/RESOLUTION_REQUIRED tetap diizinkan (by design) dengan refreshPeriodStatus post-expense.
- **Description:** `allocationAvailable() < 0` checked only post-commit in `refreshPeriodStatus()` and `assertInvariant()`. No pre-transaction check rejects overspend. Budget enforcement is entirely post-hoc.

### M4 [FIXED 1.5.20] Key material exposed in heap during deriveSubkey
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseKeyManager.kt:191-199`
- **Fixed in:** 1.5.20 — zeroing diperketat: panggil `root.fill(0)` sebelum try block selesai dan sekali lagi di finally block.
- **Description:** Raw 32-byte key held in `ByteArray` on heap while HMAC-SHA256 derivation runs. `root.fill(0)` attempts zeroing but JVM can move/copy array during GC. Called for every attachment encrypt/decrypt.

### M5 GCM nonce generated with SecureRandom -- no uniqueness guarantee
- **File:** `app/src/main/java/com/morneven/kron/security/EncryptedAttachmentStore.kt:56`
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseKeyManager.kt:343`
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:81`
- **File:** `app/src/main/java/com/morneven/kron/evidence/EvidencePackageManager.kt:368`
- **Description:** All GCM nonces generated with `SecureRandom()` with no counter, persistent state, or dedup check. Collision probability 2^-96 but nonce reuse leaks auth key and allows plaintext recovery.

### M6 [FIXED 1.5.19] Main-thread blocking during attachBaseContext
- **File:** `app/src/main/java/com/morneven/kron/KronApplication.kt:30-33`
- **Fixed in:** 1.5.19 — pindahkan `BackupManager.applyPendingRestore()` dan `SqlCipherLibrary.ensureLoaded()` ke coroutine `applicationScope.launch`.
- **Description:** `BackupManager.applyPendingRestore(this)` runs on main thread during `attachBaseContext()`. File I/O can take hundreds of milliseconds, risking ANR before app finishes starting.

### M7 [FIXED 1.5.19] LegacyReceiptEncryption.migrate() nested transaction management
- **File:** `app/src/main/java/com/morneven/kron/security/LegacyReceiptEncryption.kt:62-91`
- **Fixed in:** 1.5.19 — hapus `beginTransaction()`/`endTransaction()`, biarkan Room handle transaksi.
- **Description:** Uses `database.beginTransaction()` / `endTransaction()` on raw `SupportSQLiteDatabase` inside Room's own migration process, bypassing Room's coroutine context and creating nested/savepoint transactions.

### M8 [FIXED 1.5.19] No comprehensive end-to-end migration test (1->13)
- **File:** `app/src/androidTest/java/com/morneven/kron/data/KronMigrationTest.kt`
- **Fixed in:** 1.5.19 — tambah `endToEndMigrationFromVersionOneToThirteenPreservesAllData()` dan `endToEndMigrationValidatesDataIntegrityAfterAllMigrations()`.
- **Description:** Each migration tested in isolation. No test applies ALL migrations sequentially (1->2->3->...->13) and validates data preservation at the end. Some migrations (7->8, 8->9) have no `runMigrationsAndValidate` call at all.

### M9 [FIXED 1.5.20] Derivation subkey label collision risk
- **File:** `app/src/main/java/com/morneven/kron/security/DatabaseKeyManager.kt:192`
- **File:** `app/src/main/java/com/morneven/kron/security/EncryptedAttachmentStore.kt:175`
- **Fixed in:** 1.5.20 — tambah `usedSubkeyLabels: MutableSet<String>` + `@Synchronized` di `deriveSubkey()`. Setiap label hanya bisa dipakai sekali.
- **Description:** Subkey label "KRON attachment encryption v1" is single purpose. No mechanism enforces label uniqueness if future code uses `deriveSubkey()` with same label. HMAC-SHA256 domain separation requires unique labels per purpose.

### M10 [FIXED 1.5.20] MIGRATION_5_6 inserts sync_state without legacy data validation
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:353-383`
- **Fixed in:** 1.5.20 — tambah pre-migration check: `SELECT COUNT(*) FROM receipts WHERE eventId IS NOT NULL AND mimeType IS NOT NULL` harus sama dengan total count.
- **Description:** `byteSize` and `sha256` hard-coded to `0` and `''` for legacy receipts. No validation that legacy `receipts` table has valid data before migration.

## Low (11)

### L1 [FIXED 1.5.20] MIGRATION_5_6: No verification of legacy receipts table validity
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:374`
- **Fixed in:** 1.5.20 — tambah pre-migration check (bersama M10).
- **Description:** `INSERT INTO receipts_new` subquery trusts legacy data without validation.

### L2 flatMapLatest race with active account changes
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:89-100`
- **Description:** UI briefly sees old account data while displaying new account during account switch. Benign transient inconsistency.

### L3 SnapshotOperationLock single Mutex throughput bottleneck
- **File:** `app/src/main/java/com/morneven/kron/security/SnapshotOperationLock.kt:8-18`
- **Description:** All backup/restore/snapshot operations serialized by single coroutine `Mutex`. Drive sync must wait during backup export.

### L4 [FIXED 1.5.19] PreUpgradeBackupManager workspace not cleaned on JVM crash
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:36-75`
- **Fixed in:** 1.5.19 — tambah guard variable `workspaceReady` untuk cleanup workspace hanya jika `mkdirs()` sukses.
- **Description:** If `extractAttachments()` throws and JVM crashes before `finally` block runs, temp files accumulate.

### L5 [FIXED 1.5.19] PreUpgradeBackupManager destination URI verification reads twice
- **File:** `app/src/main/java/com/morneven/kron/backup/PreUpgradeBackupManager.kt:56-67`
- **Fixed in:** 1.5.19 — SHA-256 staged file dihitung sebelum ditulis ke URI, verifikasi pakai nilai yang sudah di-cache.
- **Description:** URI re-opened for checksum verification. If content provider deletes/modifies on read (streaming pipe), second read may fail.

### L6 Backup symlink validation defense-in-depth
- **File:** `app/src/main/java/com/morneven/kron/backup/BackupManager.kt:689-692`
- **Description:** `isAppPrivate()` resolves canonical paths correctly. If symlink inside private dir points outside, canonical resolution follows it. Defended but worth noting.

### L7 [FIXED 1.5.19] MIGRATION_3_4 dead code without removal comment
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:101-186`
- **Fixed in:** 1.5.19 — tambah `@Deprecated` annotation + warning comment via M1.
- **Description:** `MIGRATION_3_4` is dead code but has no comment explaining why it is retained or warning not to use.

### L8 [FIXED 1.5.19] CameraCaptureScreen Log.e uses exception as parameter
- **File:** `app/src/main/java/com/morneven/kron/ui/CameraCaptureScreen.kt:158`
- **Fixed in:** 1.5.19 — tambah exception parameter ke `Log.e()`.
- **Description:** `Log.e(TAG, "Camera tidak dapat disiapkan")` catches `e: Exception` but never logs the exception. Should use `Log.e(TAG, "message", e)`.

### L9 [ALREADY HANDLED] ImageCompressor GPS metadata read but not stripped
- **File:** `app/src/main/java/com/morneven/kron/security/ImageCompressor.kt:85-102`
- **Status:** Sudah otomatis strip EXIF via bitmap decode/compress cycle. Metadata diekstrak sebelum kompresi untuk logging, tidak ikut di output.
- **Description:** `extractMetadata()` reads GPS coordinates but does not strip EXIF from output JPEG, leaving GPS in encrypted attachment.

### L10 EvidencePackageManager nonce deterministic source
- **File:** `app/src/main/java/com/morneven/kron/evidence/EvidencePackageManager.kt:368`
- **Description:** Nonce generated with `SecureRandom` with no counter. Low risk but no defense against RNG seeding issues.

### L11 [FIXED 1.5.19] Application.attachBaseContext loads SQLCipher native libs synchronously
- **File:** `app/src/main/java/com/morneven/kron/KronApplication.kt:32`
- **Fixed in:** 1.5.19 — pindahkan ke coroutine `applicationScope.launch`.
- **Description:** `SqlCipherLibrary.ensureLoaded()` is synchronous on main thread during app startup.

## High (New)

### H14 [FIXED 1.5.20] cashPercentage integer division causes precision loss in allocation templates
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:470-471`
- **Fixed in:** 1.5.20 — gunakan midpoint rounding `(cashTotal * 100 + categoryTotal / 2) / categoryTotal`.
- **Description:** `cashPercentage = ((cashTotal * 100) / categoryTotal).toInt()` uses integer division. Example: CASH=333, EBUDGET=667, Total=1000 produces `cashPercentage=33`. When templates are regenerated via `periodBounds()` -> `createPortfolio()` -> template reconstruction, `cash = 1000 * 33 / 100 = 330`, `eBudget = 1000 - 330 = 670`. **3 currency units lost per category per period.** Compounds over time. Use `Math.round()` or store exact amounts instead of percentages.

### H15 [FIXED 1.5.20] refreshPeriodStatus never transitions UNDERFUNDED to RESOLUTION_REQUIRED
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:1213-1217`
- **Fixed in:** 1.5.20 — tambah defensive check untuk UNDERFUNDED: jika ada alokasi minus, transisi ke RESOLUTION_REQUIRED.
- **Description:** `refreshPeriodStatus()` returns early when `period.status == PeriodStatus.UNDERFUNDED`. If an UNDERFUNDED period somehow accumulates a deficit (e.g., via direct allocation manipulation or race condition), the status can never reach `RESOLUTION_REQUIRED`, and the deficit remains invisible in the UI. Currently mitigated by `postExpenseInternal()` nullifying `allocationId` for non-ACTIVE/RESOLUTION_REQUIRED periods, but no defensive check enforces this invariant.

### H16 [FIXED 1.5.20] acquireTransactionLock() provides no actual lock
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:1257-1259`
- **File:** `app/src/main/java/com/morneven/kron/data/KronDao.kt:283`
- **Fixed in:** 1.5.20 — ganti dengan `withTransactionLock` yang pakai coroutine `Mutex` + `dao.acquireWriteLock()` untuk serialisasi di tingkat JVM dan SQLite.
- **Description:** `acquireTransactionLock()` calls `dao.acquireWriteLock()` which executes `UPDATE sync_state SET updatedAt = updatedAt WHERE id = 1`. This is a no-op write that does NOT acquire any database-level lock. Room's `withTransaction` provides serialization within a single coroutine context, but concurrent JVM threads can still interleave. Called by `resolveFromVault()` and `resolveFromRollover()` which perform read-check-write patterns vulnerable to race conditions (see H11). Either remove the misleading call or implement a proper `SELECT ... FOR UPDATE` or `INSERT ... ON CONFLICT`-based advisory lock.

## Medium (New)

### M11 [FIXED 1.5.20] SCHEMA_VERSION constant duplikat dengan @Database annotation
- **File:** `app/src/main/java/com/morneven/kron/data/KronDatabase.kt:38,1112`
- **Fixed in:** 1.5.20 — `SCHEMA_VERSION` sekarang `lazy` property yang baca `version` dari `@Database` annotation via reflection.
- **Description:** `@Database(version = 13)` dan `const val SCHEMA_VERSION = 13` harus dijaga sinkron secara manual. Jika salah satu diubah tanpa yang lain, tidak ada peringatan kompilasi atau runtime. `SCHEMA_VERSION` digunakan oleh `BackupManager.migrateAndValidateCandidate()` (line 551) untuk memverifikasi hasil migrasi backup. Jika konstanta tidak update, restore backup dari versi database baru akan gagal. Solusi: baca `user_version` dari database atau gunakan reflection untuk membaca versi dari annotation.

### M12 [FIXED 1.5.20] General ledger tidak mencatat event tanpa cash movement
- **File:** `app/src/main/java/com/morneven/kron/audit/LedgerPostingEngine.kt:122-125`
- **Fixed in:** 1.5.20 — `ensureLedgerLines()` sekarang buat ledger lines dari budget journal lines jika cash kosong, menggunakan akun `clearing:budget` yang baru.
- **Description:** `ensureLedgerLines()` return early jika `cash.isEmpty()`. Event seperti `REALLOCATION`, `ROLLOVER`, `RELEASE`, `PORTFOLIO_BOOKING`, `OVERBUDGET_COVERAGE` hanya memiliki budget_journal_lines tanpa cash_journal_lines. Akibatnya, general ledger tidak memiliki catatan untuk alokasi/pemindahan dana antar kategori. Audit trail hanya bisa dilacak melalui subledger budget. Meskipun balanced oleh EXTERNAL bucket, ini menciptakan gap di general ledger yang seharusnya mencatat seluruh pergerakan ekonomi.

### M13 [FIXED 1.5.20] safeAbs/safeSumOf tidak digunakan secara konsisten
- **File:** `app/src/main/java/com/morneven/kron/audit/LedgerPostingEngine.kt:156,242,254,306`
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:330,468-479,1001,1189`
- **Fixed in:** 1.5.20 — ganti semua `.sumOf { it.amount }` dengan `safeSumOf()`, ganti `kotlin.math.abs()` dengan `safeAbs()`. Jadikan `safeAbs`/`safeAdd`/`safeSumOf` sebagai `internal` (sebelumnya `private`).
- **Description:** Companion object mendefinisikan `safeAbs()`, `safeAdd()`, `safeSumOf()` dengan overflow checking, tetapi kode produksi masih memakai `kotlin.math.abs()` (yang return `Long.MIN_VALUE` untuk input `Long.MIN_VALUE`) dan `.sumOf { it.amount }` (silent overflow). Beberapa lokasi:
  - `LedgerPostingEngine.ordinaryCashLines()` line 159: `matchingSplits.sumOf { it.amount }`
  - `LedgerPostingEngine.validateEvent()` line 306: `kotlin.math.abs(it.amount)` + `.sumOf()`
  - `KronRepository.createPortfolio()` line 468: `.sumOf { it.plannedAmount }`
  H11/H10 sudah mencatat overflow secara umum; temuan ini spesifik tentang inkonsistensi dengan safe functions yang sudah ada.

### M14 [FIXED 1.5.20] processDueRules memiliki dual guard redundan untuk endEpochDay
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:1071-1083`
- **Fixed in:** 1.5.20 — hapus inner guard, pindahkan precondition ke `database.withTransaction` pertama, dan ganti `dao.allRules()` dengan `dao.dueRules()`. Juga fix L13 (non-active account rules).
- **Description:** Rules dengan `endEpochDay != null && nextEpochDay > endEpochDay` dijeda di dua tempat: precondition filter loop (line 1074) dan sekali lagi di dalam transaksi loop (line 1080-1083). Precondition sudah menjeda rule sebelum loop dimulai, sehingga guard di dalam transaksi tidak akan pernah terpicu (rule sudah isPaused=true, tidak muncul di `dueRules()`). Redundan tapi tidak berbahaya. Namun, precondition menjeda rule TANPA transaksi, jadi ada window dimana state tidak konsisten.

## Low (New)

### L12 [FIXED 1.5.20] Cashflow query mengandalkan asumsi implisit tentang sign amount
- **File:** `app/src/main/java/com/morneven/kron/data/KronDao.kt:189-195,197-206`
- **Fixed in:** 1.5.20 — ganti `BETWEEN` dengan `>= :startDay AND < :endDay + 1` (half-open), klasifikasi AUTOMATION dengan OR eksplisit.
- **Description:** Query cashflow mengklasifikasikan INCOME/OPENING_BALANCE/AUTOMATION sebagai income hanya jika `c.amount > 0`, dan EXPENSE/UNEXPECTED_EXPENSE/AUTOMATION sebagai expense hanya jika `c.amount < 0`. Jika karena bug atau data korup sebuah event AUTOMATION expense memiliki `c.amount > 0`, transaksi tersebut tidak masuk kategori income maupun expense. Query menggunakan `BETWEEN :startDay AND :endDay` yang bersifat inklusif di kedua ujung, bisa double-count events di tanggal batas jika dipanggil dengan range overlapping.

### L13 [FIXED 1.5.20] processDueRules memproses aturan tanpa transaksi untuk non-aktif accounts
- **File:** `app/src/main/java/com/morneven/kron/data/KronRepository.kt:1071-1099`
- **Fixed in:** 1.5.20 — ganti `dao.allRules()` dengan `dao.dueRules()` yang join dengan accounts (filter isActive=1). Fix di M14.
- **Description:** `processDueRules()` memanggil `dao.allRules()` yang mengembalikan rules dari SEMUA account (setelah it goes through `dueRules()` which does join with accounts filtering by isActive). Namun precondition filter (line 1073-1075) menggunakan `dao.allRules()` langsung tanpa filter account. Jika ada rule untuk account non-aktif yang kebetulan memiliki `nextEpochDay > endEpochDay`, aturan tersebut akan dijeda meskipun accountnya tidak aktif. Dampak minimal karena account tidak aktif tidak akan memproses due rules, tapi ini masih inkonsisten.


