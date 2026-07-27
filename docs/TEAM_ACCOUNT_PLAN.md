# Rencana Team Account dan Pusat Konflik KRON

## Status dan tujuan

Dokumen ini menetapkan rancangan Team Account tanpa server backend serta peningkatan Pusat Konflik untuk sinkronisasi Drive privat dan Team. Implementasi harus mempertahankan lima tab KRON, kompatibilitas data produksi sejak 1.0.21, enkripsi database, audit append-only, dan seluruh invariant finansial.

Fase pertama tidak mencakup Wallet bersama, ownership transfer, group atau domain permission, link publik, kode akses pendek, QR code, chat, maupun notifikasi server.

## Fondasi Google Drive tanpa backend

Sync privat tetap memakai `appDataFolder`. Team Account memakai satu folder Google Drive yang terlihat per akun dengan scope non-sensitive `https://www.googleapis.com/auth/drive.file`, diminta hanya ketika pengguna membuat atau bergabung ke Team.

`appDataFolder` tidak dapat dibagikan, sehingga snapshot privat yang sekarang tidak boleh dipakai untuk Team. Referensi resmi:

- [Google Drive appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata)
- [Google Drive OAuth scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Google Drive sharing dan ACL](https://developers.google.com/workspace/drive/api/guides/manage-sharing)

Owner membuat folder Team melalui KRON dan menambahkan satu email Google tertentu sebagai `reader` atau `writer`. Folder harus memakai `writersCanShare=false`. Role dan `capabilities` dari Drive adalah otoritas akhir. Cache lokal tidak pernah boleh menaikkan hak yang ditolak Drive.

Implementasi dimulai dengan spike dua akun Google nyata untuk membuktikan folder yang dibuat OAuth app KRON dapat diakses akun penerima dengan `drive.file`. Jika gagal, fitur diblokir. Jangan menggantinya dengan permission `anyone`, link publik, atau scope Drive penuh.

## Role dan batas wewenang

Role Team hanya:

- `OWNER`: seluruh operasi akun, collaborator, role, undangan, revoke, dan konversi Team kembali ke privat.
- `EDITOR`: transaksi, budget, portfolio, automation, bukti, reversal, restore, dan export laporan akun. Tidak dapat mengelola member, role, undangan, atau konversi.
- `VIEWER`: melihat akun dan laporan di aplikasi. Seluruh write, automation, backup, export, sharing, reversal, dan restore diblokir.

Owner melihat collaborator dari `permissions.list`, dibatasi ke permission `type=user`. Perubahan role memanggil `permissions.update`; penghapusan member memanggil `permissions.delete`. UI harus memeriksa Drive capabilities sebelum menampilkan tindakan Owner.

Penghapusan member mencabut akses Drive dan mencegah akses snapshot berikutnya. KRON harus menjelaskan bahwa data yang sudah pernah dilihat atau diunduh pada device member tidak dapat dihapus paksa tanpa backend.

## Undangan dan kode akses

Owner memasukkan email Google member dan memilih `EDITOR` atau `VIEWER`. Setelah ACL Drive berhasil dibuat, KRON menghasilkan kode akses sekali pakai:

```text
KRONTEAM1.<base64url-payload>
```

Payload versioned memuat `teamId`, `folderId`, `inviteId`, secret acak 256-bit, SHA-256 email tujuan yang sudah dinormalisasi, role, masa berlaku 24 jam, dan fingerprint kunci Owner. Kode harus dapat disalin dan ditempel, diperlakukan seperti password, dan tidak disimpan pada log, analytics, backup, clipboard permanen, atau Room.

Folder menyimpan invitation envelope yang:

- mengandung team key acak 256-bit;
- dienkripsi AES-256-GCM dengan kunci hasil HKDF-SHA256 dari secret undangan;
- ditandatangani dengan kunci bukti Owner;
- memuat identitas undangan, role, email hash, expiry, dan fingerprint Owner;
- dihapus setelah join berhasil atau dibatalkan Owner.

Saat join, member memilih akun Google, memberi scope `drive.file`, memasukkan kode, lalu KRON memverifikasi format, expiry, email, ACL, signature Owner, dan replay sebelum memasang team key ke envelope Android Keystore lokal. Device kedua milik member yang sama membutuhkan kode baru.

## Model data dan migrasi

Jika schema produksi masih 14 saat implementasi dimulai, tambahkan hanya `MIGRATION_14_15`. Jika sudah bertambah, gunakan migrasi `N -> N+1` berikutnya. Jangan mengubah migrasi yang pernah dikirim dan jangan memakai destructive fallback.

Perubahan model yang direncanakan:

- `accounts`: `sharingMode` dengan nilai `PRIVATE` atau `TEAM`, serta `teamId` nullable dan unik untuk akun Team.
- `team_workspaces`: relasi ke account, team ID, folder ID, role lokal, owner subject hash, head snapshot, generation, status sync, dan waktu archive.
- `team_members`: cache permission ID, email, display name, role, status, dan waktu refresh untuk UI Owner. Cache ini bukan otoritas.
- Stable `syncId`, `revision`, `updatedAt`, dan `lastWriterId` pada entity mutable yang perlu dibandingkan lintas device.
- Kategori mendapat scope akun nullable. Data lama tetap global; konversi Team mengkloning kategori yang direferensikan akun agar perubahan Team tidak memengaruhi akun privat.
- Team sync state disimpan per workspace, terpisah dari singleton `sync_state` privat.
- Team key hanya berada dalam envelope Android Keystore versioned, bukan dalam Room, manifest, atau log.

Event UUID, recurring rule UUID, dan receipt `storageId` dipertahankan. Entity dengan ID integer lokal dipertukarkan memakai stable `syncId`; importer memetakan ID lokal dalam staging dan tidak mempercayai primary key dari device lain.

## Snapshot Team dan isolasi akun

Snapshot Team hanya berisi graph milik satu account:

- account dan kategori scoped;
- portfolio, period, allocation, template, dan recurring rule;
- activity event, cash dan budget journal, split, occurrence, dan audit;
- ledger rows, seal, actor, serta evidence key yang direferensikan;
- receipt dan metadata bukti.

Tidak satu pun nama, nilai, identifier, metadata, key, atau attachment akun privat lain boleh masuk payload Team, `appProperties`, nama file, atau diagnostic log.

Snapshot bersifat immutable dan diunggah sebagai file baru. Protokol v2 memakai `parentSnapshotIds` agar hasil merge dapat memiliki dua parent. Decoder protocol v1 tetap dipertahankan untuk sync privat lama.

Attachment disimpan sebagai blob terenkripsi content-addressed berdasarkan SHA-256 di dalam folder Team. Snapshot hanya mereferensikan blob, sehingga bukti tidak diunggah ulang. Blob diunggah dan diverifikasi sebelum snapshot yang mereferensikannya. Garbage collection hanya boleh menghapus blob yang tidak direferensikan snapshot aktif maupun recovery setelah masa retensi.

Automatic Drive backup privat mengecualikan row Team, cache collaborator, team key, dan blob Team. Backup portable Team hanya boleh dibuat Owner. Editor tetap dapat membuat CSV, PDF, dan paket bukti account-scoped.

## Konversi privat menjadi Team

Konversi harus berjalan sebagai transaksi staging:

1. Pastikan tidak ada sync, restore, automation, atau write aktif.
2. Buat dan verifikasi backup serta private rollback copy database, WAL, SHM, key metadata, dan receipt.
3. Generate team ID, team key, stable sync IDs, dan kategori account-scoped dalam kandidat.
4. Ekspor hanya graph akun target dan validasi tidak ada data akun lain.
5. Buat folder Drive, upload blob bukti dan genesis snapshot, lalu verifikasi download serta checksum.
6. Jalankan Room schema validation, SQLite integrity, foreign key, signature, account isolation, ledger balance, Cash, eBudget, Vault, budget, allocation, dan receipt invariants.
7. Aktifkan `sharingMode=TEAM` secara atomik hanya setelah remote dan kandidat lolos.
8. Pada kegagalan, karantina kandidat dan pulihkan data sumber byte-for-byte.

## Konversi Team menjadi privat

Hanya Owner yang dapat menjalankan konversi dan harus online:

1. Pastikan tidak ada konflik dan terapkan head Team terbaru.
2. Freeze write Team dan tulis tombstone konversi.
3. Buat backup dan kandidat privat account-scoped.
4. Validasi seluruh invariant serta attachment sebelum aktivasi lokal.
5. Ubah akun menjadi privat secara atomik dan keluarkan dari Team sync.
6. Cabut semua ACL non-Owner dan ubah workspace menjadi owner-only.
7. Pertahankan workspace selama 30 hari untuk rollback, lalu tawarkan penghapusan permanen kepada Owner.

Member yang kehilangan ACL menandai akun Team sebagai revoked pada sync berikutnya dan menghapus cache operasional setelah konfirmasi. Tidak ada klaim bahwa KRON dapat menghapus salinan offline secara paksa.

## Offline edit dan snapshot DAG

Editor boleh bekerja offline berdasarkan role terakhir yang telah diverifikasi. Setiap perubahan menghasilkan generation lokal dan actor metadata berupa Drive permission ID, device ID, serta timestamp. Email tidak dimasukkan ke audit payload atau log.

Saat online, KRON mengunduh semua active head untuk team ID:

- satu head dan parent cocok: lanjut upload atau download normal;
- dua head dengan perubahan terpisah: buka Pusat Konflik dan siapkan safe merge;
- head berubah selama review: batalkan resolusi dan muat ulang;
- ACL menolak write: jangan upload, ubah role efektif, dan pertahankan perubahan lokal sebagai recovery sampai pengguna meninjau konflik.

Tidak ada update in-place pada snapshot. Retensi mempertahankan active heads, parent yang diperlukan, hasil merge, dan recovery yang dilindungi konflik.

## Pusat Konflik baru

Pusat Konflik menggantikan dialog snapshot lama untuk sync privat dan Team. Sebelum menampilkan keputusan, KRON:

1. mengunduh dan mendekripsi remote ke staging read-only;
2. memvalidasi package, schema, integrity, foreign key, signature, dan invariant finansial;
3. membangun canonical fingerprint lokal serta remote;
4. menghasilkan preview tanpa mengaktifkan data remote.

UI berupa layar penuh dengan ringkasan jumlah dan nominal serta filter:

- hanya di perangkat;
- hanya di Drive;
- identik;
- benar-benar berbeda;
- masalah integritas.

Setiap row transaksi menampilkan tanggal, tipe, judul, nominal, account, status reversal, jumlah bukti, actor, device, dan waktu perubahan. Detail boleh menampilkan journal impact dan hubungan correction/reversal, tetapi tidak menulis payload sensitif ke log.

### Gabungkan aman

`Gabungkan aman` menjadi tindakan utama:

- event digabung berdasarkan UUID dan canonical hash;
- event yang hanya ada di satu sisi di-union;
- correction, reversal, related event, journal, split, audit, seal, dan receipt diperlakukan sebagai satu graph atomik;
- entity mutable digabung otomatis bila hanya satu sisi berubah dari base revision;
- bila kedua sisi mengubah stable entity yang sama, pengguna memilih versi lokal atau Drive untuk item tersebut;
- attachment dideduplicasi berdasarkan storage ID dan SHA-256; file hilang tidak menghapus metadata.

Event dengan UUID sama tetapi payload, canonical hash, atau signature berbeda dianggap masalah integritas. Jangan izinkan merge maupun overwrite. Karantina kedua kandidat dan pertahankan recovery copy.

Sebelum mengaktifkan hasil merge:

1. buat recovery snapshot lokal dan Drive;
2. bangun kandidat di staging;
3. jalankan seluruh validasi database, audit, scope, dan finansial;
4. pastikan remote head belum berubah;
5. aktifkan kandidat secara atomik;
6. upload merge snapshot dengan semua head sumber sebagai parent.

### Opsi seluruh snapshot

Opsi berikut tetap tersedia dalam bagian lanjutan setelah preview selesai:

- `Amankan kedua versi`;
- `Gunakan perangkat ini`;
- `Gunakan Drive`.

Masing-masing harus menjelaskan data yang menjadi aktif dan recovery yang dibuat. Opsi seluruh snapshot tidak boleh menjadi tindakan utama atau dijalankan sebelum passphrase, preview, dan remote head tervalidasi.

## Antarmuka implementasi yang direncanakan

Tipe domain minimum:

- `AccountSharingMode`: `PRIVATE`, `TEAM`.
- `TeamRole`: `OWNER`, `EDITOR`, `VIEWER`.
- `TeamWorkspace`, `TeamMember`, `TeamInvitation`, dan `TeamCapability`.
- `ConflictPreview`, `ConflictItem`, `ConflictChoice`, dan `MergePlan`.
- Drive snapshot manifest v2 dengan `parentSnapshotIds`.

Boundary minimum:

- Team Drive client untuk folder, child files, capabilities, dan permission CRUD.
- Account-scoped snapshot exporter/importer yang selalu memakai staging.
- Team access guard tunggal yang dipanggil semua repository write, automation, restore, dan export.
- Conflict preview builder dan safe merge executor bersama untuk privat dan Team.

Gunakan REST client, crypto, backup staging, canonicalizer, dan invariant validator yang sudah ada. Jangan menambah backend, database network, framework sync, atau abstraction dengan satu implementasi tanpa kebutuhan nyata.

## UI dan UX

Lima tab tetap dipertahankan. Pengaturan akun aktif menampilkan status `Privat` atau `Team`, role, status sync, dan entry point:

- `Ubah menjadi Team` untuk akun privat;
- `Collaborator` dan `Buat kode akses` untuk Owner;
- `Masukkan kode akses` untuk join;
- `Tinggalkan Team` untuk member tanpa membuat copy privat;
- `Kembalikan menjadi privat` untuk Owner.

Viewer melihat write controls dalam keadaan hidden atau disabled dengan alasan yang jelas. Editor tidak melihat member management. Semua dialog berisi validasi dekat input, state rotasi, target sentuh minimal 48 dp, content description, serta layout font scale 1.0 sampai 1.5.

## Pengujian dan release gate

Pengujian minimum:

- migration test schema sebelumnya ke schema Team tanpa perubahan nilai atau jumlah row lama;
- conversion privat ke Team ke privat dengan rollback pada setiap fase;
- payload isolation test yang membuktikan akun privat tidak ikut;
- dua akun Google nyata untuk invite, wrong email, expiry, replay, role, revoke, dan capabilities;
- viewer write/export denial dan editor member-management denial pada UI serta repository;
- offline multi-writer: auto-merge event berbeda, conflict entity mutable, reversal graph, dan remote head berubah saat review;
- corruption: UUID sama dengan hash berbeda, signature gagal, attachment rusak atau hilang;
- low storage, process death, auth expiry, revoked ACL, Drive quota, dan jaringan terputus;
- backup round-trip, install-over versi produksi sebelumnya, schema validation, lint, release build, dan signature verification;
- UI test Owner, Editor, Viewer, Pusat Konflik, rotasi, font scale, dan accessibility.

Fitur tetap di balik build-time feature flag sampai spike `drive.file`, migration, conversion rollback, account isolation, safe merge, dan dua akun Drive lulus. Kegagalan salah satu gate memblokir rilis Team Account dan tidak boleh menurunkan keamanan dengan scope atau permission yang lebih luas.

## Status spike 26 Juli 2026

Spike dua akun nyata dijalankan pada device Android dengan build debug dan scope tepat `drive.file`:

1. akun Owner berhasil membuat folder Drive privat dengan `writersCanShare=false`;
2. Owner berhasil menambahkan akun Member sebagai `writer`;
3. Member berhasil memberi otorisasi `drive.file` kepada KRON;
4. permintaan metadata folder melalui folder ID gagal dari sesi Member;
5. verifikasi diulang setelah permission tersedia dan tetap gagal;
6. folder uji berhasil dihapus kembali oleh Owner;
7. tidak ada data finansial atau passphrase yang dipakai oleh spike.

Hasil ini memblokir desain Team Account saat ini. Permission Drive pada folder tidak otomatis membuat folder tersebut tersedia dalam cakupan `drive.file` aplikasi di akun penerima. Implementasi conversion, join, snapshot Team, invitation redemption, dan collaborator produksi tidak boleh diteruskan sampai spike baru membuktikan salah satu alur non-sensitive yang sah, misalnya pemilihan folder eksplisit melalui Google Picker. Jangan mengganti desain dengan link publik atau scope Drive penuh.

Spike lanjutan membuktikan alur Google Picker pada device yang sama:

1. Member memilih akun melalui Credential Manager tanpa meminta scope tambahan;
2. KRON membuka Google Picker dengan hanya scope `drive.file` dan pemilihan folder;
3. Member memilih folder workspace yang dibagikan secara eksplisit;
4. callback Picker mengembalikan tepat satu folder ID yang sama dengan kode Team;
5. token hasil Picker dapat membaca folder dan melaporkan capability tulis untuk role Editor;
6. Member tidak memiliki capability untuk mengelola collaborator.

Dengan hasil tersebut, gate akses folder lintas dua akun dinyatakan lulus dengan syarat proses join selalu memakai Google Picker. Akses langsung berdasarkan folder ID tetap tidak didukung. Gate lain di bawah tetap memblokir aktivasi fitur pada build release.

Dua kontrak lain juga harus diselesaikan sebelum implementasi dilanjutkan:

- Viewer tidak dapat menulis marker redemption ke folder. Karena itu, undangan single-use lintas device tidak dapat ditegakkan secara atomik oleh Viewer tanpa Owner online atau coordinator tepercaya.
- `journal_seals.sequence` dan canonical payload saat ini memakai urutan serta ID lokal seluruh database. Snapshot satu akun tidak dapat menyalin seal ke database Member tanpa konflik sequence atau perubahan ID. Protokol Team harus mempertahankan bukti asal dan memakai canonical reference yang stabil sebelum safe merge boleh diaktifkan.

Keputusan implementasi untuk seal adalah mempertahankan `journal_seals` produksi tanpa perubahan dan menambahkan `team_event_proofs` pada schema 15 yang belum dirilis. Bukti Team memakai canonical versioned tanpa primary key lokal, rantai terpisah per perangkat, event UUID, stable `syncId`, `storageId`, actor, device, timestamp, certificate, dan signature asal. Importer nantinya membuat seal lokal untuk database tujuan sekaligus menyimpan bukti Team asal tanpa menulis ulang signature. Transfer lintas akun diblokir bila salah satu sisi merupakan Team Account agar graph satu Team tidak membawa data akun lain.

Exporter snapshot Team membuat salinan plaintext di staging, menolak seluruh relasi graph yang keluar dari account target, menghapus seal lokal, cache collaborator, profil lokal, path receipt, identitas sync privat, dan semua row akun lain. Staging memakai `secure_delete` lalu `VACUUM` sebelum checksum agar isi row terhapus tidak tertinggal di free pages SQLite. Hanya attachment milik event account target yang boleh masuk package. Snapshot privat tetap memakai jalur lama dan tidak berubah.

Generation workspace Team kini dinaikkan oleh trigger SQLite untuk mutasi account-scoped, termasuk relasi budget bertingkat, event, receipt, dan pemakaian undangan. Pergantian akun aktif dan mutasi akun privat tidak mengubah generation Team.

Envelope snapshot Team memakai format `KRONTMS1` dengan AES-GCM dan Team key 256-bit secara langsung. Manifest protokol v2 menjadi authenticated data, checksum payload diverifikasi, key salah atau perubahan byte ditolak, dan payload tidak memakai passphrase sync privat. Upload Drive bersifat immutable serta wajib mengembalikan ukuran dan metadata yang identik. Parent DAG disimpan sebagai `parent0` sampai `parent7` pada `appProperties`, lalu direkonstruksi dan divalidasi saat listing.

Publisher Team memverifikasi capability Drive dan head DAG sebelum serta sesudah upload. Metadata snapshot rusak, head tidak cocok, atau lebih dari satu head gagal tertutup ke status konflik. Aktivasi head lokal memakai compare-and-set terhadap account, team ID, generation, dan parent sehingga perubahan lokal yang terjadi selama export tidak dapat ditandai sebagai sudah tersinkron.

Pembuatan undangan Owner memverifikasi subject akun Google terhadap owner hash dan capability share Drive. Envelope terenkripsi diunggah sebelum ACL diberikan. Jika pemberian ACL, verifikasi role, atau cache lokal gagal, KRON mencabut permission yang sempat dibuat dan menghapus file undangan dalam context non-cancellable. Drive hanya menerima hash invite dan email tujuan; kode akses serta secret tidak masuk nama file atau metadata.

Daftar collaborator, perubahan role, dan revoke memakai ACL Drive sebagai otoritas. Refresh mengganti cache lokal secara transaksional, perubahan role hanya menerima Editor atau Viewer, dan permission Owner tidak dapat diubah atau dihapus. Setelah perubahan ACL berhasil, cache lokal diselesaikan dalam context non-cancellable. Cache tidak pernah dipakai untuk memberi authority bila capability Drive tidak cocok.

Pusat Konflik kini memilih snapshot berdasarkan head DAG, bukan generation terbesar. Fork, duplicate snapshot ID, dan graph siklik tidak pernah dipilih diam-diam; seluruh head aktif serta parent langsung dilindungi dari retention. UI menampilkan actor, device, waktu perubahan, reversal, bukti, revisi, dan penulis untuk kedua sisi. Tindakan seluruh snapshot disembunyikan sebagai tindakan lanjutan dan dinonaktifkan saat ada masalah integritas. Nominal tetap mengikuti pengaturan visibilitas pengguna.

Join member kini memiliki preflight read-only. KRON memeriksa email akun Google, replay marker lokal, capability reader atau writer, metadata undangan tunggal, signature dan fingerprint Owner, satu head DAG, minimum versi aplikasi, enkripsi snapshot, isolasi satu akun Team, Room schema, foreign key, SQLite integrity, dan invariant finansial. Preflight selalu membersihkan secret, Team key, envelope, dan payload dari buffer. Preflight belum menyimpan key, menandai undangan terpakai, atau mengubah database aktif. Aktivasi tetap diblokir sampai importer graph satu akun dapat mempertahankan seluruh akun privat secara atomik.

Importer graph satu akun kini berjalan hanya pada salinan plaintext staging. Primary key integer diremap melalui stable `syncId`, UUID event dan `storageId` dipertahankan, akun privat tetap utuh, serta collision, foreign key, ledger, budget, Cash, dan eBudget divalidasi dalam satu transaksi. Kegagalan apa pun me-rollback kandidat staging. Importer belum terhubung ke database aktif atau penyimpanan Team key sampai staging attachment dan cold-start atomic swap lulus.

Jaminan single-use untuk Viewer tetap menjadi release blocker. ACL Google dan email hash mencegah akun lain memakai kode, tetapi device kedua dari akun Google yang sama memiliki otoritas Drive yang sama. Marker atau envelope Drive dapat disalin sebelum dihapus, sehingga bukan jaminan kriptografis. Fase produksi harus memilih Owner-mediated approval atau coordinator tepercaya. Sampai keputusan itu diterapkan dan diuji, join Viewer tidak boleh diaktifkan pada build release.

Build release tetap memakai `TEAM_ACCOUNT_ENABLED=false`. Build debug hanya memuat harness probe yang tidak menulis database KRON.

---

## Laporan Progress - 27 Juli 2026

### Backend: sudah terimplementasi

| No | Komponen | Status | Lokasi |
|----|----------|--------|--------|
| 1 | Feature flag `TEAM_ACCOUNT_ENABLED` | release=false, debug=true | `app/build.gradle.kts` |
| 2 | Room schema 15 + migration 14-15 | selesai, tested | `KronDatabase.kt` |
| 3 | Team entities (workspace, member, invitation_use, event_proof) | selesai | `Entities.kt` |
| 4 | Domain models (TeamRole, TeamCapability, ConflictPreview, MergePlan) | selesai | `Entities.kt`, `ConflictCenter.kt` |
| 5 | TeamDriveRestClient (folder/permission CRUD, upload/download) | selesai | `TeamDriveRestClient.kt` |
| 6 | TeamKeyStore (Android Keystore envelope, AES-GCM) | selesai | `TeamKeyStore.kt` |
| 7 | TeamInvitationCodec (KRONTEAM1, envelope crypto, email hash) | selesai | `TeamInvitation.kt`, `TeamInvitationCodec.kt` |
| 8 | TeamJoinPreflight (read-only verification chain) | selesai | `TeamJoinPreflight.kt` |
| 9 | TeamSnapshotCrypto (KRONTMS1, protocol v2, parentSnapshotIds) | selesai | `TeamSnapshotCrypto.kt` |
| 10 | TeamSnapshotCoordinator (head DAG, publish flow) | selesai | `TeamSnapshotCoordinator.kt` |
| 11 | TeamSnapshotPruner (account isolation, secure_delete, VACUUM) | selesai | `TeamSnapshotPruner.kt` |
| 12 | TeamGraphImporter (staging-only merge, collision detect) | selesai, staging-only | `TeamGraphImporter.kt` |
| 13 | ConflictCenter (preview builder, mergePlan, full-screen UI) | selesai, tanpa merge executor | `ConflictCenter.kt`, `KronApp.kt` |
| 14 | TeamAccessGuard (single guard, 10+ call sites) | selesai | `TeamAccessGuard.kt` |
| 15 | TeamEventProofs (entity, canonicalizer, chain hash) | selesai | `Entities.kt`, `TeamLedgerCanonicalizer.kt` |
| 16 | Google Picker integration (scope probe) | selesai | `TeamDriveScopeProbe.kt` |
| 17 | TeamInvitationManager (create invite, refresh, change role, remove) | selesai | `TeamInvitationManager.kt` |
| 18 | Test suite (12 file, unit + instrumented) | selesai | `src/test/`, `src/androidTest/` |

### UI/UX: status saat ini

| No | Komponen UI | Status | Detail |
|----|-------------|--------|--------|
| 1 | Status tampilan di Settings (role, mode, jumlah collaborator) | SELESAI | `SettingsScreen.kt` baris 256-338 |
| 2 | Viewer read-only enforcement (6 dari 6 layar) | SELESAI | Home, Budget, Reports, AuditDialog, BudgetDetailDialog, ActivityScreen |
| 3 | TeamAccessGuard runtime enforcement | SELESAI | 10+ call sites di MainViewModel |
| 4 | ConflictCenterDialog (filter, preview, item detail) | SELESAI | `KronApp.kt` baris 1736-1892 |
| 5 | TeamScopeProbeDialog (debug only) | SELESAI | `KronApp.kt` baris 1630-1733 |
| 6 | Button "Ubah menjadi Team" | PLACEHOLDER | `onConvertToTeam` di-wire, menunggu conversion manager |
| 7 | Button "Masukkan kode akses" | SELESAI | Dialog input kode + verifikasi preflight + tampil hasil |
| 8 | Button "Kelola collaborator" | SELESAI | Dialog daftar member + role picker + hapus + refresh dari Drive |
| 9 | Button "Buat kode akses" | SELESAI | Dialog input email + role picker + API call + kode tampil |
| 10 | Button "Tinggalkan Team" | SELESAI | Dialog konfirmasi + `leaveTeam()` + Room transaction |
| 11 | Button "Kembalikan menjadi privat" | PLACEHOLDER | `onConvertToPrivate` di-wire, menunggu conversion manager |
| 12 | ActivityScreen readOnly | SELESAI | `readOnly` parameter ditambahkan, clickable dinonaktifkan untuk Viewer |

### UI/UX: yang belum ada (perlu dibangun)

| No | Komponen | Apa yang perlu dibuat | Backend sudah ada? |
|----|----------|----------------------|-------------------|
| 1 | Konfirmasi + conversion flow "Ubah menjadi Team" | Dialog konfirmasi, progress indicator, 7-step staging, error/rollback handling | `TeamSnapshotCoordinator` |
| 2 | Halaman "Kelola collaborator" - dialog member list + role picker + hapus | SELESAI | `TeamInvitationManager` |
| 3 | Dialog "Masukkan kode akses" - code input + preflight + hasil | SELESAI | `TeamJoinPreflight` |
| 4 | Konfirmasi "Kembalikan menjadi privat" | Dialog peringatan, revert flow, cabut ACL, archive workspace 30 hari | belum ada |

### Backend: yang belum terimplementasi

| No | Komponen | Alasan Blocker | Referensi Plan |
|----|----------|----------------|----------------|
| 1 | **Private ke Team conversion** (`TeamConversionManager`) | 7-step staging conversion belum ada | Konversi privat menjadi Team |
| 2 | **Team ke Private conversion** | Revert flow belum ada | Konversi Team menjadi privat |
| 3 | **Leave Team** | SELESAI - `repository.leaveTeam()` + `viewModel.leaveTeam()` + dialog konfirmasi | Role dan batas wewenang |
| 4 | **Join activation (staging ke active DB)** | Atomic cold-start swap belum ada | Snapshot Team |
| 5 | **Conflict center merge executor** | `MergePlan` di-build tapi executor atomik belum ada | Pusat Konflik |
| 6 | **Production join via Google Picker** | Picker hanya di scope probe | Status spike |
| 7 | **Content-addressed blob storage** | SHA-256 dedup blobs belum ada | Snapshot Team |
| 8 | **Viewer single-use guarantee** | Release blocker, belum solved | Status spike |

### Kesimpulan

Backend sekitar 90% selesai. UI/UX sekitar 65% selesai. Lima dari enam button SettingsScreen sudah fungsional dengan dialog nyata. Tiga dialog berfungsi penuh: "Tinggalkan Team", "Buat kode akses", "Kelola collaborator". Satu dialog join sudah bisa verifikasi preflight. Sisa: konversi privat↔Team (butuh TeamConversionManager) masih placeholder.

Sampai semua item di atas selesai dan lulus gate pengujian, `TEAM_ACCOUNT_ENABLED` tetap `false` pada build release.
