# KRON Drive Sync Change Control

Dokumen ini adalah kontrak kanonik untuk semua model dan engineer yang bekerja
di KRON. Baca `AGENTS.md` dan dokumen ini sebelum menyentuh kode produksi yang
berhubungan dengan Google Drive atau aktivasi database.

## Area terlindungi

Area berikut memerlukan persetujuan eksplisit pengguna sebelum kode produksi
diubah:

- Private Drive Sync dan `appDataFolder`.
- Team Drive, workspace, invitation, ACL, role, snapshot, conflict, auto-sync,
  dan recovery.
- Kapsul Drive dan secure viewer.
- Backup, restore, staging, rollback, atomic activation, dan `DatabaseRuntime`.
- Snapshot crypto, manifest, checksum, proof chain, key envelope, dan isolasi
  akun.
- Scheduler, WorkManager, sync worker, akun Google terpilih, serta UI orkestrasi
  sync.
- Entity, DAO, serializer, migration, dan test yang mengubah graph atau format
  data yang dikirim ke Drive.

Perubahan fitur finansial biasa juga termasuk area terlindungi bila menyentuh
serializer, snapshot graph, generation, atau invariant sinkronisasi.

Path produksi yang digate oleh CI mencakup `app/src/main/java/.../sync`,
`team`, `capsule`, `backup`, `security/DatabaseRuntime`, komponen bootstrap dan
enkripsi database, `KronDatabase`, `KronDao`, `Entities`, `MainActivity`,
`KronApplication`, modul DI, UI orkestrasi sync, serta konfigurasi build dan
dependency.

## Aturan untuk model dan engineer

Sebelum perubahan produksi pada area terlindungi:

1. Baca `AGENTS.md` dan dokumen ini.
2. Nyatakan bahwa keduanya sudah dibaca.
3. Jelaskan dampak terhadap Private Drive, Team, Kapsul, backup, scheduler, dan
   aktivasi database.
4. Minta persetujuan eksplisit pengguna dalam percakapan terpisah.
5. Tunggu persetujuan sebelum memakai `apply_patch`, mengubah migration,
   dependency, schema, scheduler, protokol, atau membuat release APK.

Laporan bug, permintaan analisis, atau permintaan rencana bukan persetujuan
implementasi. Inspeksi baca, static analysis, dokumentasi, dan test-only boleh
dikerjakan tanpa approval.

Commit yang mengubah area produksi terlindungi wajib memiliki trailer:

```text
Drive-Change-Approval: <approval-reference>
```

Trailer adalah bukti mekanis untuk CI, bukan pengganti verifikasi manusia bahwa
pengguna benar-benar memberi persetujuan.

## Invariant yang wajib dipertahankan

- Private Sync tetap memakai `appDataFolder`.
- Workspace Team dibuat dan dipakai secara idempotent pada namespace yang tepat.
- Kapsul memakai exact `fileId`, immutable, dan tidak masuk sync utama.
- Tidak ada listing global, permission `anyone`, atau scope Drive penuh.
- Migrasi Room biasa tidak mengubah key mode atau melakukan rekey.
- Tidak ada `fallbackToDestructiveMigration`.
- Database aktif tidak disentuh sebelum kandidat staging lolos validasi.
- Aktivasi wajib melewati checksum, schema validation, foreign-key check, SQLite
  integrity, invariant finansial, dan atomic swap.
- Process death, low storage, key salah, snapshot rusak, atau validasi gagal
  harus mempertahankan database dan sidecar lama.
- Account, Team, snapshot head, parent DAG, generation, audit, receipt metadata,
  dan proof chain tetap terisolasi.
- Viewer hanya menerima. Owner dan Editor mengikuti role serta ACL Drive.
- Auto-sync tidak memproses event non-transaksi yang tidak berdampak pada data
  finansial.
- Tombol sync dan resolusi dikunci selama operasi berlangsung.
- Worker tidak memunculkan dialog interaktif dan tidak retry tanpa batas.
- Log tidak memuat email, kode akses, token, passphrase, key, payload, file ID
  sensitif, atau nilai finansial.

## Checklist sebelum merge

Untuk perubahan terlindungi, jalankan sesuai cakupan:

- Unit, migration, instrumentation, lint, debug build, release build, dan
  signature verification.
- Install-over terhadap APK produksi sebelumnya.
- Private Drive: upload, pull, no-change, remote kosong, conflict, account
  switch, recovery copy, Wi-Fi-only, seluler, dan isolasi akun.
- Team: Owner, Editor, Viewer, multi-member, join, leave, rejoin, push/pull dua
  arah, auto-sync, conflict, fresh recovery, dan ACL.
- Kapsul: create, exact-file upload, biometrik/PIN, duplicate import, revoke,
  404, expiry, tombstone, delete, dan read-only isolation.
- Self-healing: snapshot rusak, key salah, staging terputus, process death,
  low storage, rollback, dan metadata restore rusak.
- Invariant ledger, Cash, eBudget, Vault, allocation, rollover, debt graph,
  audit append-only, receipt metadata, proof chain, dan active-account
  isolation.
- Pemeriksaan logcat untuk memastikan tidak ada data sensitif.

Catat hasil pada `docs/KRON_STABILIZATION_REPORT.md` dengan status `done` atau
`need attention`. Status `need attention` hanya boleh dipakai bila bukti
eksternal seperti akun, jaringan, izin perangkat, atau fixture produksi belum
tersedia.

## Checklist commit

```text
Protected area changed: yes
User approval reference: <reference>
Drive/Team/Capsule impact reviewed: yes
Migration or key-mode change: no/explicitly approved
Regression tests completed: <commands/results>
```

## CI gate

`tools/check_drive_change_approval.py` dan workflow
`.github/workflows/drive-change-control.yml` memeriksa file produksi yang
berubah terhadap branch dasar. Docs-only dan test-only dilewati. Jika area
terlindungi berubah, setiap commit yang menyentuh area tersebut harus memiliki
`Drive-Change-Approval`; tanpa trailer CI gagal dan meminta approval pengguna.

Gate ini tidak menghapus, mereset, meng-uninstall, meng-clear data, atau
mengakses Google Drive.

## Perubahan kebijakan

Perubahan pada kebijakan sync, daftar invariant, atau aturan approval harus
memperbarui dokumen ini terlebih dahulu atau dalam commit yang sama. Dokumen ini
tidak mengubah schema Room, protokol sync, dependency, UI, atau perilaku runtime
KRON.
