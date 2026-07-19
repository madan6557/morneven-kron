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

## Security and repository hygiene

- Never commit signing credentials, OAuth secrets, access tokens, financial data, backup passwords, or encryption keys.
- OAuth client IDs may be supplied through external build properties. Client secrets must never be embedded in the APK.
- Do not log tokens, passphrases, account details, notes, receipt contents, or monetary values.
- Keep the codebase organized. Do not leave temporary files, dead code, dead files, or unnecessary directories.
- Use a measure twice, cut once policy for migrations, journal behavior, backup formats, and destructive-looking actions.
- Do not use an em dash in source text or documentation.
