# KRON Engineering Rules

## Release and data compatibility

- KRON 1.0.21 is the permanent production compatibility baseline.
- Every stable release must upgrade data from every supported earlier stable release without data loss.
- Never edit or delete a Room migration that has shipped. Add a new `N -> N+1` migration for every schema change.
- Every Room schema change requires an exported schema, a previous-version fixture, a migration test, and invariant checks.
- Never use `fallbackToDestructiveMigration` in a production database builder.
- Never hard-delete financial journals, audit records, reversals, or historical budget periods.
- The newest backup importer must continue to read every production `.kronbackup` format released since KRON 1.0.21.
- Restore and database encryption upgrades must use staging, validation, and atomic replacement. The current database must remain untouched when validation fails.
- Preserve `com.morneven.kron`, the release signing key, and its certificate fingerprint across all updates.
- Direct APK downgrade is not supported. Rollback uses the archived APK together with a backup created before upgrading.

## Release gate

- Increase `versionCode`, `versionName`, and the version shown inside the app for every release.
- Update `CHANGELOG.md` and archive the APK, SHA-256 checksum, Room schemas, R8 mapping, commit, and Git tag.
- Do not mark a release complete until migration tests, backup round trips, financial invariants, lint, release build, signature verification, and install-over-previous smoke tests pass.
- A release that cannot prove compatibility with the previous production APK is blocked.

## Encryption key continuity and data-loss prevention

- Starting with KRON 1.4.6, the production key contract is fixed: Android Keystore alias `kron.database.wrap.v1`, envelope `security/database-key-v1.bin`, profile `security/database-key-profile-v1.bin`, raw-hex key encoding, and SQLCipher 4 compatibility. Treat these identifiers and semantics as shipped migration history.
- Treat the production database, its `-wal` and `-shm` files, the wrapped data-key envelope, Android Keystore alias, and encryption metadata as one inseparable data set.
- Never generate or install a replacement database key when any existing database or encrypted recovery artifact is present. A missing, unreadable, or mismatched key must fail closed into read-only recovery mode.
- The shipped Android Keystore alias, envelope format, SQLCipher key encoding, cipher parameters, and key derivation behavior are immutable compatibility contracts. Any change requires a new versioned format and an explicit tested migration. Never reinterpret existing key bytes with a new encoding.
- Key creation is allowed only for a genuinely new installation with no database and no recovery artifacts, or while converting a database that has already been positively validated as plaintext.
- Before any encryption, rekey, schema, restore, or ownership migration, create a private pre-upgrade recovery copy. Preserve the original database, sidecars, and key envelope until the replacement database opens successfully and passes SQLCipher integrity, SQLite integrity, foreign-key, Room schema, journal, account, Vault, Cash, eBudget, and allocation invariant checks.
- Migration staging and recovery files must be written and synchronized completely before atomic activation. On any failure, keep the original files byte-for-byte intact and quarantine unreadable candidates instead of deleting or overwriting them.
- All database format and key detection must be read-only. Test every historically shipped key representation separately, validate it with a real database read and integrity check, and never use a failed probe as permission to re-encrypt or replace data.
- Never present uninstall, clear data, destructive reset, or downgrade as the default response to an upgrade failure. Recovery must be delivered as a forward-only APK with a higher `versionCode` and the same application ID and signing certificate.
- A reset action must be isolated from migration recovery, explicitly initiated by the user, explain permanent data loss, and require a verified backup plus strong confirmation. Migration code must never invoke reset behavior.
- Encryption and key-management changes require install-over tests using data produced by the exact signed production APKs, beginning with KRON 1.0.21 and including the immediately previous release. Synthetic databases alone are insufficient.
- The install-over test must cover plaintext databases, every shipped SQLCipher key representation, valid and missing envelopes, unavailable Keystore aliases, WAL mode, interrupted migration, process death, low storage, and rollback after validation failure.
- Every release must prove that an existing user can open their ledger after upgrade without re-entering or regenerating a device key. If this cannot be demonstrated, the release is blocked.
- Before a risky encryption or key-format upgrade, create and verify an automatic pre-upgrade backup that can be restored by the new version. Do not start the migration if that backup cannot be verified.
- Recovery diagnostics must not expose keys, passphrases, financial values, account details, file contents, or other sensitive data in the UI, logs, crash reports, or exported diagnostics.
- If Android Keystore material is genuinely unavailable and no valid recovery copy exists, stop all writes and preserve every artifact for forensic recovery. Never hide this condition by creating a new key.
- Starting with KRON 1.4.7, SQLCipher passphrase bytes as used by KRON 1.3.20 are the default write mode for new databases and upgrades from 1.3.x. Raw-hex remains a supported historical read mode and must never be reinterpreted as a passphrase.
- Profile v2 `security/database-key-profile-v2.bin` records the validated key fingerprint, SQLCipher compatibility, and database key mode. A profile is metadata, not authority. A database that independently opens and passes every integrity and financial invariant check is the source of truth.
- Database access is process-gated. WorkManager, automation, Drive Sync, dependency injection, and operational UI must not open or write the database before bootstrap validation marks the current process ready.
- Ordinary Room schema upgrades must not rekey or convert a valid SQLCipher database. A key-mode conversion requires its own versioned migration, verified external recovery backup, private rollback copy, and signed install-over test.
- A release must test a fresh database through create, close, process death, and reopen. It must also install over the exact signed KRON 1.3.20 APK and the immediately previous production APK before release.

## Security and repository hygiene

- Never commit signing credentials, OAuth secrets, access tokens, financial data, backup passwords, or encryption keys.
- OAuth client IDs may be supplied through external build properties. Client secrets must never be embedded in the APK.
- Do not log tokens, passphrases, account details, notes, receipt contents, or monetary values.
- Keep the codebase organized. Do not leave temporary files, dead code, dead files, or unnecessary directories.
- Use a measure twice, cut once policy for migrations, journal behavior, backup formats, and destructive-looking actions.
- Do not use an em dash in source text or documentation.
