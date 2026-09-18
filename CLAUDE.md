# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

KRON is a local-first Android personal finance app (Kotlin, Jetpack Compose, Room + SQLCipher, Hilt, WorkManager). No backend, no Firebase, no analytics. All UI strings and most in-code messages are Indonesian.

## Read before changing production code

Two documents are binding contracts, not background reading:

- [AGENTS.md](AGENTS.md) - LTS compatibility, encryption key continuity, release gate, repo hygiene.
- [docs/DRIVE_SYNC_CHANGE_CONTROL.md](docs/DRIVE_SYNC_CHANGE_CONTROL.md) - approval process and invariants for Drive, Team, Capsule, backup, and database activation code.

Practical consequences:

- **Protected paths require an approval trailer.** `.github/workflows/drive-change-control.yml` runs `tools/check_drive_change_approval.py`, which fails CI when a commit touches a protected path without a `Drive-Change-Approval: <reference>` trailer in the commit message. Protected prefixes: `sync/`, `team/`, `capsule/`, `backup/`, `security/`, `data/`, `di/` under `app/src/main/java/com/morneven/kron/`. Protected files: `MainActivity.kt`, `KronApplication.kt`, `ui/KronApp.kt`, `ui/MainViewModel.kt`, `ui/components/Components.kt`, `ui/dialogs/Dialogs.kt`, `ui/screens/SettingsScreen.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`. In practice almost every real feature change touches one of these, so ask the user for an approval reference before implementing, and put the trailer on every commit that touches a protected path.
- Docs-only, test-only, analysis, and read-only inspection need no approval.
- Never edit or delete a shipped Room migration. Never use `fallbackToDestructiveMigration`. Never hard-delete journals, audits, reversals, or historical budget periods.
- Never change the SQLCipher key encoding, Keystore alias `kron.database.wrap.v1`, or envelope format in place. Those are immutable compatibility contracts.
- Do not use em dashes in source or documentation (house rule from AGENTS.md).

## Commands

Windows PowerShell, JDK 17, Android SDK 37.

Full verification pass (what a release must survive):

```bash
./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleRelease
```

Unit tests only (plain JVM JUnit4, no Robolectric, no device):

```bash
./gradlew.bat :app:testDebugUnitTest
```

One unit test class or method:

```bash
./gradlew.bat :app:testDebugUnitTest --tests "com.morneven.kron.data.DebtCalculatorTest"
```

Instrumented tests (need a device or emulator; migration, backup, encryption, and Compose UI tests live here):

```bash
./gradlew.bat :app:connectedDebugAndroidTest
```

One instrumented test class:

```bash
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.morneven.kron.data.KronMigrationTest
```

Release build. `assembleRelease` signs automatically from `%USERPROFILE%/.android/kron-signing.properties`, and the build **fails fast** if that file is missing. Output is `app/build/outputs/apk/release/KRON-<versionName>.apk`. Optional Drive OAuth config comes from `%USERPROFILE%/.android/kron-google.properties` (`webClientId`, `privacyPolicyUrl`); when absent, `BuildConfig.DRIVE_SYNC_CONFIGURED` is false and Drive features are inert. See [OAUTH_SETUP.md](OAUTH_SETUP.md).

## Architecture

### Layering

`MainActivity` (FragmentActivity, Hilt entry) -> `MainViewModel` -> `KronRepository` -> `KronDao` / `KronDatabase`. Compose UI is state-down / callbacks-up: `KronApp` collects a single `KronUiState` from the ViewModel and passes plain data plus lambdas into the five screens (`home`, `budget`, `activity`, `reports`, `settings` in `ui/screens/`). Screens hold no repository reference. `ui/KronApp.kt` is large because it owns navigation, dialogs, lock screen, and all sync orchestration UI.

### The dual-journal money model

This is the core invariant and the thing most likely to be broken by a careless change.

- An account has two funding channels, `CASH` and `EBUDGET` (`FundingChannel`). Exactly one account is active at a time; the repository's flows are all keyed off `activeAccountFlow`.
- Every financial operation writes an append-only `ActivityEventEntity` plus **two** parallel journals:
  - `cash_journal_lines` - where the money physically is (account + channel).
  - `budget_journal_lines` - what the money is earmarked for (`BudgetBucket.VAULT` / `UNEXPECTED` / `ROLLOVER` / `EXTERNAL`, or an `allocationId`).
  Both must sum to a consistent picture. Income, for example, writes one cash line `+amount` and two budget lines: `VAULT +amount`, `EXTERNAL -amount`.
- A third layer, the double-entry general ledger (`ledger_accounts` / `ledger_lines`), is derived and sealed by `audit/LedgerPostingEngine`, which also hash-chains events (`journal_seals`) and signs team events (ECDSA P-256, `EvidenceSigningKeyManager`).
- `KronRepository.assertInvariant()` runs inside the transaction of every mutating operation. It finalizes unsealed events, then asserts: ledger events balance, exactly one active account, `cashTotal == budgetAvailableTotal` globally, per channel, per account, and per account+channel, and every event's budget lines sum to zero. A violation throws `LedgerInvariantException` and rolls the transaction back. **New money operations must post both journals and go through `assertInvariant()`.**
- Corrections and reversals never mutate history. They append new events (`REVERSAL`, `CORRECTION`, `RESTORE_REVERSAL`) linked by `relatedEventId`.
- Mutable rows carry `revision` and `updatedAt`, bumped via the private `bumpRevision()` extensions. Sync conflict resolution depends on these, so any write to accounts, portfolios, periods, allocations, rules, debts, or categories must bump.

### Budget lifecycle

`PortfolioEntity` (a recurring budget plan, monthly or yearly) generates `BudgetPeriodEntity` rows, each holding `AllocationEntity` per category and channel. `reconcilePortfolios()` opens and closes periods. `PeriodStatus` moves `DRAFT` -> `UNDERFUNDED` / `ACTIVE` -> `RESOLUTION_REQUIRED` -> `CLOSED`. Overspending drives a period to `RESOLUTION_REQUIRED`, resolved by moving funds from another allocation, the Main Vault, or rollover (`resolveFrom*` / `allocateUnallocated`). A category with both Cash and eBudget allocations is one logical "split" category in the UI.

### Database, encryption, and activation

`data/KronDatabase.kt` is schema **version 19**, exported to `app/schemas/`, opened through SQLCipher. `security/` owns a deliberately paranoid startup path:

- `DatabaseKeyManager` / `DatabaseEncryptionManager` detect the historical key representation (raw-hex is read-only legacy; passphrase bytes is the write contract since 1.4.7), stage any conversion, validate it, and only then swap atomically. Failure keeps the original files byte-for-byte.
- `DatabaseBootstrapManager` gates upgrades on a verified external backup and two successful cold launches.
- `DatabaseAccessGate` is a process-wide ready flag. Workers, sync, and DI must not open the database before bootstrap marks the process ready.
- `DatabaseRuntime` owns the live Room instance and exposes an `epoch` StateFlow. When a validated Drive or Team snapshot is activated, the database is closed and swapped and the epoch increments; `KronRepository.observe()` re-subscribes every flow on epoch change. That is why repository flows go through `databaseEpoch.flatMapLatest { ... database.kronDao() }` rather than capturing a DAO once.
- `SnapshotOperationLock` serializes account activation, sync, and swap so a snapshot never lands mid-transaction.

### Backup, sync, Team, Capsule

- `backup/BackupManager` writes `.kronbackup` (AES-256-GCM, PBKDF2-HMAC-SHA256 600k iterations, ZIP with `database.sqlite`, `manifest.json`, `checksums.tsv`, encrypted attachments). It still reads v1 and v2 magic headers; that importer compatibility is a hard requirement. Restore stages, validates (checksum, `PRAGMA user_version` vs `KronDatabase.SCHEMA_VERSION`, foreign keys, integrity, financial invariants) and activates atomically on next process start via `KronApplication.attachBaseContext`.
- `sync/` is optional private Google Drive sync into `appDataFolder`, encrypted client-side. `DriveSyncRuntime` is the Activity-facing surface (interactive auth); `DriveSyncCoordinator` is the Activity-free path used by `DriveSyncWorker`. `ConflictCenter` resolves divergence.
- `team/` is shared workspaces on regular Drive folders under `KRON/Team/team-<id>` (`KronDriveNamespace`), with roles `OWNER` / `EDITOR` / `VIEWER` enforced by `data/TeamAccessGuard` (`teamAccessGuard.require(accountId, TeamCapability.WRITE)` at the top of every mutating repository call).
- `capsule/` is immutable shared read-only snapshots opened by `sharing/viewer/SecureViewerActivity`.
- Never list Drive globally, grant `anyone` permissions, or request full Drive scope.

### Automation, evidence, widget

`automation/AutomationWorker` is a daily periodic worker that runs due recurring rules and reconciles portfolios. `security/ReceiptManager` + `EncryptedAttachmentStore` store receipt photos encrypted in app-private storage with SHA-256 integrity; `evidence/EvidencePackageManager` exports `.kronevidence` packages and verifies hash chains and signatures. `widget/` is a home screen AppWidget with multi-currency display including the synthetic KRM rate.

## Release process

Every release: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a `CHANGELOG.md` section in Indonesian ending with the version bump line, and archive the APK, SHA-256, Room schemas, R8 mapping, commit, and tag (`releases/` is gitignored; binaries go to GitHub Release assets). A release is blocked until migration tests, backup round trips, invariants, lint, release build, signature verification, and install-over-previous smoke tests pass. Commit subjects follow Conventional Commits (`feat(budget):`, `fix(ui):`, `chore(release):`).
