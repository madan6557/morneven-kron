# Drive Auth Hierarchy — Design & Implementation Plan

## Problem

KRON has 3 Drive features, each with its own `AuthorizationClientDriveSession` instance and its own `PreferencesSelectedGoogleAccountStore`:

| Feature | Scope | Preferences file | Created in |
|---|---|---|---|
| Drive Sync | `drive.appdata` | `kron_google_account` | `DriveSyncRuntimeFactory` |
| Team Account | `drive.file` | `kron_team_google_account` | `TeamDriveScopeProbeFactory` |
| One-Time View | *(none yet)* | *(none yet)* | — |

There is **zero cross-enforcement**. A user can:
- Back up data with Account A (Drive Sync)
- Become Team Owner with Account B (Team Account scope probe)
- Create One-Time offers as "owner" identified only by `activeAccount.id`

Since Team Account operates on the **same database** (same accounts, transactions, budgets) by just flipping `sharingMode = TEAM`, and One-Time View exports a projection of that same data, having separate Google identities creates an ownership ambiguity.

## Target Hierarchy

```
Drive Sync Account (root identity — pemilik data tertinggi)
├── Team Account Owner   === Drive Sync Account  (WAJIB)
├── Team Member          !== Drive Sync Account  (BOLEH beda — dia editor/viewer)
└── One-Time View Owner  === Drive Sync Account  (WAJIB)
```

- **Drive Sync Account** adalah satu-satunya Google account yang diakui sebagai *owner* data KRON.
- **Team Owner** harus menggunakan Google account yang sama dengan Drive Sync. Dia mengelola workspace, invite/remove member, dan manage permissions.
- **Team Member** (editor/viewer) adalah akun Google berbeda — mereka tidak punya hak kelola, hanya akses data sesuai role.
- **One-Time View Owner** (pembuat offer) harus terautentikasi sebagai Drive Sync Account yang sama.

## Where enforcement happens

Every path that creates an owner-level binding must cross-check against the stored Drive Sync account.

### Point 1 — Team Conversion (`convertPrivateToTeam` in `MainViewModel.kt:593`)

Before creating the Drive workspace and writing `ownerSubjectHash`, validate that the Google account being used as Team Owner matches the Drive Sync account.

**Current flow:**
1. User clicks "Buat Tim" → `convertPrivateToTeam(accessToken, account, accountId)`
2. `account` berasal dari `teamDriveScopeProbe.getAccessToken()` — bisa akun mana saja, tidak dicek.

**Target flow:**
1. Tambah validasi: `require(account.subjectId == driveSyncAccount.subjectId) { "Owner Team harus menggunakan akun Google yang sama dengan Drive Sync" }`

### Point 2 — One-Time Offer creation (`createOffer` in `KronApp.kt:1503`)

Before creating a capsule, validate that the current viewer's Google account (or at minimum, that the offer is being created by the Drive Sync account holder).

**Current flow:**
1. Siapa pun bisa create offer — `ownerKeyId` cuma pakai `"owner-${activeAccount?.id}"`, tidak ada Google identity.
2. Tidak ada cross-check ke Drive Sync account.

**Target flow:**
1. Jika Drive Sync terhubung, `ownerKeyId` harus mengandung Drive Sync `subjectId`.
2. `OneTimeOfferManager.createOffer()` menerima parameter `ownerGoogleSubjectId` (dari ViewModel).
3. Validasi: kalau Drive Sync terhubung, `ownerGoogleSubjectId` wajib cocok.

### Point 3 — Team scope probe (`TeamDriveScopeProbe.createOwnerProbe`)

The probe itself doesn't need cross-check because it's only used during the probe flow (testing workspace). The enforcement is at conversion time (Point 1).

## File-by-file changes

### 1. `MainViewModel.kt` — Inject Drive Sync account store

```kotlin
// Add injection
@Inject lateinit var driveSyncAccountStore: SelectedGoogleAccountStore

// In convertPrivateToTeam()
fun convertPrivateToTeam(accessToken: String, account: GoogleAccountIdentity, accountId: Long) {
    runAction("Akun berhasil dikonversi ke Team") {
        val driveSyncAccount = runBlocking { driveSyncAccountStore.read() }
            ?: error("Hubungkan Drive Sync terlebih dahulu")
        require(driveSyncAccount.subjectId == account.subjectId) {
            "Owner Team harus menggunakan akun Google yang sama dengan Drive Sync"
        }
        // ... existing workspace creation ...
    }
}
```

### 2. `OneTimeOfferManager.kt` — Accept owner identity

```kotlin
fun createOffer(state: KronUiState, scope: String, ownerGoogleSubjectId: String?): OfferResult? {
    // ... existing logic ...
    val capsule = ViewCapsuleCodec.createCapsule(
        projection = projection,
        teamId = teamId,
        ownerKeyId = ownerGoogleSubjectId?.let { "drive-sync:$it" }
            ?: "local-${state.activeAccount?.id ?: 0}",
        // ...
    )
    // ...
}
```

### 3. `KronApp.kt` — Wire Drive Sync account into offers

```kotlin
// Near createOffer call (~line 1503)
val driveSyncAccount = remember { driveSyncRuntime?.let { runBlocking { it.currentAccount() } } }
// ...
val result = oneTimeOfferManager.createOffer(state, oneTimeSelectedScope, driveSyncAccount?.subjectId)
```

No changes needed to One-Time View **consumer** side (viewer opens capsule) — that flow doesn't need Drive auth at all.

### 4. `SettingsScreen.kt` — UX guard: disable Team/One-Time jika Drive Sync belum connect

- Tombol "Buat Tim" disabled kalau `driveSyncRuntime == null || currentAccount() == null`, dengan tooltip "Hubungkan Drive Sync terlebih dahulu".
- Tombol "Sekali Buka" disabled dengan tooltip sama.

Purely optional UX polish; enforcement sudah cukup di logic layer.

## Backward compatibility (existing users)

**Kasus 1 — Team Account sudah aktif dengan owner ≠ Drive Sync account**
- Enforce pada titik konversi, bukan runtime. User yang sudah terkonversi sebelumnya tetap bisa pakai.
- Tapi pada operasi Team Owner tertentu (invite, remove member, change role), validasi tambahan: `currentTeamOwnerGoogleAccount == driveSyncAccount`.
- Untuk member (non-owner), tidak ada perubahan — mereka tetap pakai akun sendiri.

**Kasus 2 — Drive Sync belum pernah dihubungkan**
- Fitur Team dan One-Time View ditolak di *enforcement point* dengan pesan jelas: "Hubungkan Drive Sync terlebih dahulu untuk menjadi Owner Team / membuat tautan Sekali Buka."
- Ini konsisten dengan hirarki — Drive Sync adalah root identity.

**Kasus 3 — Migrasi akun Drive Sync**
- Jika user switch account Drive Sync, Team Owner hash jadi mismatch.
- Solusi: di `DriveSyncRuntime.switchAccount()`, deteksi Team workspace yang `ownerSubjectHash` tidak cocok dengan Drive Sync baru. Tawarkan untuk:
  a. Transfer kepemilikan Team ke akun baru (owner hash di-update).
  b. Atau downgrade Team ke Private.

## Implementation order

| # | File | Change | Risk |
|---|---|---|---|
| 1 | `MainViewModel.kt` | Inject `driveSyncAccountStore`, add require check in `convertPrivateToTeam` | Low |
| 2 | `OneTimeOfferManager.kt` | Add `ownerGoogleSubjectId` parameter to `createOffer()` | Low |
| 3 | `KronApp.kt` | Pass `driveSyncAccount` to `createOffer` call | Low |
| 4 | `SettingsScreen.kt` | UX guard (disable buttons) | Low |
| 5 | `DriveSyncRuntime.kt` | Future: detect Team owner mismatch on switchAccount | Medium |
| 6 | `TeamConversionManager.kt` | Future: handle owner re-assignment | Medium |
