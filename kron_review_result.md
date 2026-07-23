# Hasil Pemeriksaan Kode KRON

## CRITICAL -- Error Kompilasi / Crash Pasti

### 1. `DEFAULT_BUFFER_SIZE` tidak pernah didefinisikan
Digunakan di **15 lokasi** di 5 file, tapi tidak ada definisi `val DEFAULT_BUFFER_SIZE` di mana pun. **Kompilasi pasti gagal.**

**File:** `BackupManager.kt:711,724,766,984,1003`, `PreUpgradeBackupManager.kt:60,349,362`, `EncryptedAttachmentStore.kt:68,125,159`, `EvidencePackageManager.kt:412,541`, `DriveAppDataClient.kt:272,273`

### 2. NPE saat `localPath` NULL di query receipts
`PreUpgradeBackupManager.kt:91` query `SELECT localPath FROM receipts` tanpa `WHERE localPath IS NOT NULL`. `cursor.getString(1)` return `null`, lalu `File(null)` -> **NullPointerException crash**.

### 3. Multiple `setContent()` di MainActivity
`MainActivity.kt:108,142,166,185` memanggil `setContent()` berulang kali. Compose tidak menjamin cleanup layout sebelumnya, menyebabkan **window hierarchy leak / crash**.

---

## HIGH -- Data Corruption / Logic Salah

### 4. SQL injection via string interpolation di invariant check
`KronDatabase.kt:1048-1058`, `BackupManager.kt:630-667` -- puluhan query menggunakan `"$channel"` dan `"$accountId"` tanpa parameterized query.

### 5. `EncryptionGuard.rollback()` hapus database sebelum recovery copy selesai
`DatabaseEncryptionManager.kt:552-567` -- live database di-delete **sebelum** recovery copy di-copy balik. Jika copy gagal di tengah, **database hilang total**.

### 6. `cameraProviderFuture.get()` blocking main thread
`CameraCaptureScreen.kt:148` -- blocking call di main thread via `AndroidView.factory`. Menyebabkan **ANR** jika CameraProvider lambat.

### 7. `Uri.fromFile()` rawan `FileUriExposedException`
`CameraCaptureScreen.kt:206` -- menggunakan `Uri.fromFile(photoFile)` yang di Android 7+ bisa throw `FileUriExposedException`. Harusnya `FileProvider.getUriForFile()`.

### 8. AutomationWorker retry forever untuk permanent error
`AutomationWorker.kt:27` -- semua exception (termasuk `IllegalStateException`) menyebabkan `Result.retry()`. WorkManager akan retry terus tanpa batas.

### 9. `DatabaseBootstrapManager` bypass Dagger/Hilt singleton
`DatabaseBootstrapManager.kt:25-26` membuat instance `DatabaseKeyManager` dan `DatabaseEncryptionManager` manual via `new()`, mengabaikan `@Singleton` Dagger. Dua instance berbeda bisa eksis dengan state inkonsisten.

### 10. Fallback `Files.move` tanpa `REPLACE_EXISTING`
`BackupManager.kt:1117-1120` -- fallback non-atomic move kehilangan `StandardCopyOption.REPLACE_EXISTING`, menyebabkan `FileAlreadyExistsException` dalam race condition.

---

## MEDIUM

| # | File | Baris | Masalah |
|---|------|-------|---------|
| 11 | `MainViewModel.kt` | 459-464 | Receipt dari `receiptUri`/`cameraFile` di-drop silent saat `recordNow=false` |
| 12 | `KronApp.kt` | 1041-1071 | `MainScope()` dibuat tanpa cleanup tiap dialog camera dibuka -> memory leak |
| 13 | `KronApp.kt` | 196-237 | Lockout biometric bisa di-bypass via recomposition di antara panggilan `onAuthenticationFailed` |
| 14 | `PrivacyPreferences.kt` | 48-51 | `recordAuthFailure` pakai `System.currentTimeMillis()` sebagai default parameter -- dua failure < 1ms dapat lock duration sama |
| 15 | `PreUpgradeBackupManager.kt` | 98 | `require(source.isFile)` crash tanpa pesan jelas jika file hilang |
| 16 | `BackupManager.kt` | 922-928 | Stale old artifact silent abort restore -- user tidak tahu restore dibatalkan |
| 17 | `BackupManager.kt` | 586-587 | File staging tidak dibersihkan jika `encryptTo` gagal (sampah menumpuk) |
| 18 | `EncryptedSyncSecretStore.kt` | 192-199 | Passphrase tidak di-zero jika `CharsetEncoder.encode()` return direct buffer |

---

## LOW -- Code Quality

- `DatabaseAccessGate.isReady()` tidak pernah diperiksa di production path (dead code)
- `Os.fsync()` hidden API bisa diblokir Android versi baru
- Duplikasi `toHex()` dengan implementasi berbeda di 2 file
- `EncryptedAttachmentStore.kt:164` off-by-one: `require(total <= limit)` setelah buffer melebihi limit
- `KronApp.kt:420` `authenticateCriticalAction` tidak handle perangkat tanpa biometric
- `LedgerPostingEngine.kt:297` fallback `"local-device"` tidak unique secara global
- CSV injection protection di `CsvExporter.kt:67-71` hanya cek karakter pertama
- `SnapshotOperationLock.kt:11-18` implementasi manual `withLock` rawan human error
- `KronTheme.kt:77-81` hardcode `"DARK"` untuk screen recovery tidak ikut preference user
- `KronDatabase.kt:1042-1047` invariant cross-check non-deterministik (false positive crash)
