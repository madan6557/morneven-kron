# Analisis Logika Perhitungan dan Resolusi Konflik KRON

Tanggal review: 2026-08-03  
Mode review: static review dan implementation follow-up  
Ruang lingkup: seluruh jalur yang menghasilkan angka keuangan atau keputusan konflik, termasuk UI, repository, DAO, laporan, backup, Drive Sync, Team, dan test yang relevan.

## Batasan dan metode

Review awal bersifat static code review. Alur ditelusuri dari input/UI ke repository, DAO, laporan, dan sinkronisasi. Setelah temuan dipetakan, perubahan minimum diterapkan pada jalur bersama dan diverifikasi dengan test unit serta pemeriksaan build yang relevan. Perubahan tetap mempertahankan schema Room, format key, dan staging atomik yang sudah dikirim.

Severity yang dipakai:

- P1: dapat mengubah saldo, periode, atau keputusan sinkronisasi secara material.
- P2: dapat menghasilkan angka atau metadata yang tidak konsisten pada kondisi tertentu.
- P3: terutama false positive, keterbatasan audit, atau masalah yang memerlukan kondisi khusus.

## Ringkasan eksekutif

Temuan paling penting:

1. RESTORE_REVERSAL membuat angka budget, saldo, cash flow, dan laporan tidak mengikuti definisi yang sama.
2. Persentase Cash/eBudget disimpan sebagai integer terpotong, sehingga komposisi dapat berubah ketika period berikutnya dibuat atau allocation dikoreksi.
3. Batas portfolio menggunakan batas kalender yang dinormalisasi, bukan selalu startDate dan endDate yang dipilih pengguna.
4. Jalur account switch Drive mengambil snapshot ACTIVE terbaru tanpa validasi DAG atau dataset yang sama.
5. Safe merge Drive tersedia sebagai model dan preview, tetapi executor produksi masih dikunci.
6. Validasi Team saat refresh/merge lebih lemah daripada importer, sementara legacy event fingerprint tidak cukup untuk membuktikan kesamaan detail journal.

## Peta cakupan fitur perhitungan

| Area | Jalur yang ditelusuri | Hasil review |
| --- | --- | --- |
| Input uang | Components.kt, KronRepository.kt | Parsing digit dan overflow ditolak; nominal positif divalidasi di repository. Tidak ada temuan utama. |
| Posting ledger | LedgerPostingEngine.kt, KronRepository.kt | Event baru divalidasi debit/kredit, budget, dan split. Invariant aggregate tersedia. |
| Saldo account | KronDao.kt | Saldo berasal dari jumlah seluruh cash line, sehingga reversal tetap memengaruhi saldo fisik. |
| Budget available/booked/spent | KronDao.kt | Formula utama konsisten untuk event normal, tetapi tidak konsisten untuk RESTORE_REVERSAL. |
| Cash flow dan dashboard | KronDao.kt, ReportsScreen.kt, MainViewModel.kt | Filter event berbeda dari beberapa export dan evidence path. |
| Recurring schedule | KronRepository.kt dan property test | Clamp akhir bulan dan leap year tercakup test; tidak ada temuan utama. |
| Portfolio, rollover, dan reconcile | KronRepository.kt | Periode dinormalisasi ke batas kalender; exact date dapat diabaikan. |
| Cash/eBudget mix | Dialogs.kt, KronRepository.kt | Persentase integer menyebabkan drift dan komposisi salah. |
| Budget resolution | KronRepository.kt, Dialogs.kt | Guard UI ada; sebagian status period/channel tidak ditegakkan kembali di repository. |
| Notification/progress | BudgetNotifier.kt, Components.kt | BigInteger dipakai pada rasio notifier; progress float hanya presentasi. |
| CSV dan evidence | CsvExporter.kt, EvidencePackageManager.kt | Definisi summary tidak seragam, terutama automation, reversal, dan jumlah lampiran. |
| Drive conflict | DriveSyncCoordinator.kt, ConflictCenter.kt | DAG guard kuat pada sync normal; merge belum dieksekusi dan account switch melewati guard. |
| Team conflict | TeamGraphRefresher.kt, TeamGraphImporter.kt | Importer kuat, refresh/merge lebih lemah; beberapa graph dan legacy proof tidak lengkap. |

## Temuan perhitungan

### CALC-01: RESTORE_REVERSAL tidak ikut formula spend dan cash flow

Severity: P1  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/data/KronRepository.kt:860-894
- app/src/main/java/com/morneven/kron/data/KronDao.kt:187-203, 286-305, 417-435
- app/src/main/java/com/morneven/kron/report/CsvExporter.kt:101-114
- app/src/main/java/com/morneven/kron/ui/screens/ReportsScreen.kt:148-169, 559-561

restoreReversedEvent membuat event baru bertipe RESTORE_REVERSAL dan menyalin cash line serta budget line dari event asal. Event asal tetap memiliki reversal. Formula DAO untuk spentAmount hanya mengenali EXPENSE dan AUTOMATION, serta mengabaikan event asal yang mempunyai reversal. RESTORE_REVERSAL tidak masuk daftar tersebut.

Akibatnya, contoh event expense -100 yang dibalik menjadi +100, lalu dipulihkan:

- availableAmount dapat turun -100 karena budget line restore ikut dijumlahkan.
- spentAmount tetap 0 karena event restore bukan EXPENSE atau AUTOMATION.
- bookedAmount dapat menjadi -100.
- Cash total dan saldo account berubah karena cash line restore ikut ada.
- Cash flow pada DAO, Reports, dan CSV tetap tidak memasukkan restore event karena filter tipe event tidak mengenal RESTORE_REVERSAL.
- Budget notifier dapat melihat available negatif, sementara laporan spend menampilkan nol.

Ini bukan sekadar perbedaan label. Satu tindakan restore mengubah saldo fisik tetapi tidak mengembalikan angka cash flow dan spend menurut formula laporan.

Cakupan test saat ini menguji reversal/correction biasa di KronRepositoryTest.kt:74-104, tetapi tidak ditemukan test untuk restoreReversedEvent atau RESTORE_REVERSAL.

### CALC-02: Pembulatan persentase Cash/eBudget menyebabkan drift antar period

Severity: P1  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/ui/dialogs/Dialogs.kt:478-486
- app/src/main/java/com/morneven/kron/data/KronRepository.kt:552-560, 753-763, 1241-1246

UI menghitung nominal Cash dengan total * percent / 100, lalu eBudget menjadi sisa. Repository menyimpan hanya cashPercentage sebagai integer:

cashPercentage = cashTotal * 100 / categoryTotal

Nilai ini dipotong, bukan dibulatkan atau disimpan sebagai nominal sumber. Ketika template dipakai untuk period berikutnya atau allocation dikoreksi, nominal Cash dihitung ulang dari persentase yang sudah kehilangan pecahan.

Contoh:

- Total 3, Cash 1 menghasilkan persentase tersimpan 33.
- Period berikutnya menghitung Cash 3 * 33 / 100 = 0, sehingga satu unit berpindah atau hilang dari channel Cash.
- Total 1 dengan slider 50% menghasilkan Cash 0 dan eBudget 1, walaupun UI menampilkan komposisi 50/50.
- Correction pada total lama 3, Cash aktual 1, delta Cash +1 merekonstruksi Cash lama sebagai 0; hasil akhirnya dapat disimpan sebagai 25%, padahal komposisi aktual 2/4 = 50%.

Dampaknya adalah budget period baru dan hasil correction tidak lagi mempertahankan nominal atau komposisi yang dimasukkan pengguna. Tidak ditemukan test khusus untuk nilai kecil, persentase pecahan, pembuatan period berikutnya, dan correction.

### CALC-03: Batas portfolio memakai bulan/tahun kalender, bukan selalu tanggal konfigurasi

Severity: P1 jika tanggal konfigurasi dimaksudkan literal; jika kalender memang kontrak produk, ini perlu dokumentasi dan test.  
Confidence: tinggi untuk perilaku implementasi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/data/KronRepository.kt:538-576
- app/src/main/java/com/morneven/kron/data/KronRepository.kt:1213-1239, 1322-1329

periodBounds menormalkan period monthly ke hari pertama sampai hari terakhir bulan dan period yearly ke 1 Januari sampai 31 Desember. withinPeriod kemudian memakai batas normalisasi itu untuk menentukan apakah portfolio aktif dan dapat didanai. endDate tetap disimpan sebagai portfolio.endValue, tetapi BudgetPeriodEntity.endEpochDay yang dipakai untuk keputusan period berasal dari batas kalender.

Contoh perilaku:

- Portfolio dimulai 15 Agustus. Pada 3 Agustus, batas normalisasi menjadi 1-31 Agustus sehingga portfolio dapat dianggap berada dalam period aktif sebelum tanggal mulai yang dipilih.
- Portfolio berakhir 15 Maret. Pada 20 Maret, batas period masih dapat berakhir 31 Maret sehingga aktivitas dan funding dapat terus dianggap berada dalam period.

reconcilePortfolios memang menghentikan period yang mulai setelah portfolio.endValue, tetapi tidak memotong period yang sudah dimulai agar berakhir tepat pada endDate.

### CALC-04: Summary CSV tidak memakai definisi cash flow yang sama

Severity: P2 untuk automation; P3 untuk lampiran.  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/report/CsvExporter.kt:101-114
- app/src/main/java/com/morneven/kron/data/KronDao.kt:286-305
- app/src/main/java/com/morneven/kron/ui/screens/ReportsScreen.kt:148-169
- app/src/main/java/com/morneven/kron/evidence/EvidencePackageManager.kt:178-183

DAO cash flow, Reports, dan Evidence memasukkan AUTOMATION positif sebagai income. CSV hanya menghitung INCOME dan OPENING_BALANCE untuk total_pemasukan, sehingga automation income positif tidak terlihat di summary CSV.

Selain itu, total_lampiran menggunakan receiptsByEvent.size, yaitu jumlah event yang memiliki receipt, bukan jumlah baris receipt. Satu event dengan beberapa lampiran akan dihitung sebagai satu lampiran pada summary.

### CALC-05: Invariant belum memeriksa semua bentuk dan distribusi line

Severity: P2 sebagai coverage gap.  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/data/KronRepository.kt:1338-1373
- app/src/main/java/com/morneven/kron/audit/LedgerPostingEngine.kt:335-356

assertInvariant memeriksa event ledger yang tidak seimbang, satu active account, kesamaan aggregate cash/budget, dan jumlah budget per event. LedgerPostingEngine.validateEvent lebih kuat untuk event baru atau yang belum sealed.

Namun, invariant akhir tidak secara eksplisit memeriksa ulang seluruh data yang sudah sealed untuk hal berikut:

- amount harus positif;
- side hanya DEBIT atau CREDIT;
- distribusi budget per account dan channel;
- jumlah split harus sama dengan total event dan category/allocation tetap koheren;
- validitas seal chain.

Ini belum membuktikan ada data rusak, tetapi membatasi kemampuan invariant untuk mendeteksi kerusakan atau snapshot malformed yang sudah lolos ke database.

### CALC-06: Sebagian budget resolution bergantung pada guard UI

Severity: P2/P3, tergantung caller.  
Confidence: sedang

Lokasi terkait:

- app/src/main/java/com/morneven/kron/data/KronRepository.kt:626-731
- app/src/main/java/com/morneven/kron/ui/dialogs/Dialogs.kt:755-780

UI memfilter target resolution berdasarkan period dan status aktif. Repository memeriksa account, saldo sumber, dan beberapa batas nominal, tetapi tidak menegakkan kembali seluruh status period/channel di semua operasi allocateUnallocated, resolveFromVault, resolveFromRollover, dan transferBookedChannel.

Jika ada caller lain seperti import, automation, atau future UI yang langsung memanggil repository, operasi dapat mempercayai target yang tidak lagi eligible. Ini adalah trust-boundary gap, bukan bukti jalur UI saat ini selalu gagal.

## Observasi definisi laporan dan evidence

### OBS-CALC-01: Evidence PDF adalah raw/global view, bukan metrik Reports yang sama

EvidencePackageManager.kt:163-185 mengambil seluruh event dan cash line pada rentang hari tanpa filter account. Income dan expense dihitung dari event mentah, termasuk event reversal, sedangkan Reports dan DAO tertentu mengecualikan event asal yang sudah direversal. PDF juga secara eksplisit melabeli perubahan kas bersih sebagai termasuk reversal.

Perbedaan ini dapat valid untuk tujuan audit evidence global, tetapi saat ini mudah dibaca sebagai angka laporan yang sama. Dokumentasi produk atau label output perlu memperjelas bahwa definisinya berbeda.

### OBS-CALC-02: Jalur yang tidak menunjukkan temuan utama

- ScheduleCalculator menangani anchor monthly di akhir bulan dan tanggal leap year dengan clamp; perilaku ini didukung FinanceInvariantPropertyTest dan test repository.
- BudgetNotifier menggunakan BigInteger untuk rasio persentase, sehingga tidak memakai aritmetika floating point pada perhitungan threshold.
- Progress bar memakai float hanya untuk visualisasi, bukan sumber angka ledger.
- Parsing nominal menolak input overflow dan repository tetap memvalidasi nominal positif.
- Invariant aggregate yang ada sudah memberi perlindungan untuk saldo account, total cash/budget, dan event budget pada jalur normal.

## Temuan resolusi konflik

### CONF-01: Safe merge Drive tersedia di model/preview, tetapi executor produksi belum aktif

Severity: P1/P2  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/SyncModels.kt:180-185
- app/src/main/java/com/morneven/kron/sync/ConflictCenter.kt:78-175
- app/src/main/java/com/morneven/kron/sync/DriveSyncCoordinator.kt:389-441
- app/src/main/java/com/morneven/kron/ui/KronApp.kt:3077-3106
- app/src/main/java/com/morneven/kron/backup/BackupManager.kt:304-347

ConflictResolution.MERGE, MergePlan, dan conflict preview sudah memodelkan merge berbasis item. Namun saat resolve production dipilih MERGE, coordinator hanya mencatat bahwa safe merge masih dikunci sampai executor staging lulus verifikasi. UI juga menonaktifkan tombol Gabungkan aman.

Dengan demikian, dua perangkat yang masing-masing memiliki perubahan independen belum dapat disatukan secara aman. Pilihan operasional yang tersedia adalah memakai snapshot perangkat ini, memakai Drive, atau menyimpan local recovery lalu memakai remote. Pilihan tersebut bukan per-item merge dan dapat menghilangkan perubahan aktif dari salah satu sisi.

### CONF-02: Account switch memilih snapshot ACTIVE terbaru tanpa validasi DAG/dataset

Severity: P1  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/ui/KronApp.kt:541-549
- app/src/main/java/com/morneven/kron/sync/DriveSyncRuntime.kt:296-302
- app/src/main/java/com/morneven/kron/sync/DriveSyncCoordinator.kt:476-489
- app/src/test/java/com/morneven/kron/sync/DriveSyncDecisionEngineTest.kt:40-57

Saat isAccountSwitching, UI memanggil downloadAndApplyLatest alih-alih alur syncNow. Coordinator kemudian memilih file ACTIVE dengan generation/createdAt terbesar. Jalur ini tidak menjalankan pemeriksaan SnapshotDag, tidak memastikan dataset yang sama, dan tidak memeriksa fork atau multiple heads sebelum apply.

Sebaliknya, decision engine normal memang menolak multiple heads dan dataset mismatch. Karena account switch melewati decision engine tersebut, satu snapshot asing atau salah satu cabang fork dapat dipilih sebagai snapshot terbaru dan langsung diterapkan.

### CONF-03: Validasi Team refresh/merge lebih lemah daripada validasi importer

Severity: P1/P2  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/TeamGraphRefresher.kt:66, 122, 360-363
- app/src/main/java/com/morneven/kron/backup/TeamGraphImporter.kt:528-580

TeamGraphImporter memeriksa debit/kredit, amount positif dan side valid, budget event, budget per account/channel, serta cash versus budgetAvailable per account/channel. TeamGraphRefresher pada jalur refresh dan merge hanya memeriksa event ledger seimbang dengan asumsi semua side selain DEBIT menjadi negatif, serta budget event berjumlah nol.

Akibatnya, snapshot Team malformed yang akan ditolak oleh importer dapat lolos pada jalur refresh/merge karena side invalid, amount non-positive, atau distribusi account/channel tidak diuji dengan kekuatan yang sama.

### CONF-04: Team mutable graph tidak seluruhnya dibandingkan atau di-upsert

Severity: P2  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/TeamGraphRefresher.kt:182-198, 258-296
- app/src/main/java/com/morneven/kron/sync/TeamGraphRefresher.kt:112
- app/src/main/java/com/morneven/kron/backup/BackupManager.kt:1671-1678

validateNoMutableFork membandingkan categories, portfolios, periods, allocations, dan recurring rules, tetapi tidak memasukkan accounts, portfolio_allocation_templates, serta field pausedByArchive. upsertMutableGraph pada overwriteExisting=false hanya menambah row yang belum ada; row yang sudah ada tidak diperbarui.

Jika jalur mergeFork tercapai, perbedaan account/template/rule yang sudah ada dapat diam-diam mempertahankan nilai local. Selain itu, canonical hash recurring rule pada conflict preview tidak membedakan pausedByArchive, sehingga perubahan field tersebut tidak terlihat sebagai perbedaan.

UI saat ini cenderung memblokir sebagian mutable difference melalui requiresChoices, tetapi executor tetap tidak memiliki defense-in-depth yang lengkap.

### CONF-05: Legacy event fingerprint lossy dan line-level shared event tidak diverifikasi

Severity: P1/P2  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/TeamSnapshotPruner.kt:127-149
- app/src/main/java/com/morneven/kron/backup/BackupManager.kt:1618-1638
- app/src/main/java/com/morneven/kron/sync/TeamGraphRefresher.kt:150-180, 299-345

Snapshot Team lama tetap diizinkan walaupun event belum memiliki portable Team proof. Fallback fingerprint di BackupManager hanya menggabungkan cursor event, metadata ringkas, jumlah receipt, dan total cash/budget. Fingerprint tersebut tidak memasukkan distribusi cash line per account/channel, budget bucket/allocation, split, ledger line, audit detail, dan identitas receipt.

Pada saat append, child hanya memasukkan cash/budget/split/ledger row bila belum ada row untuk event ID tersebut. Jika legacy event UUID yang sama memiliki detail line berbeda tetapi total agregat sama, event dapat dianggap sama dan detail local dipertahankan tanpa perbandingan line-level.

Dampaknya adalah kemungkinan silent mismatch atau hilangnya detail kategori, channel, allocation, atau ledger pada conflict yang melibatkan snapshot legacy/unproved.

### CONF-06: DAG inspection mengabaikan parent yang dangling

Severity: P2  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/DriveSyncCoordinator.kt:23-66
- app/src/main/java/com/morneven/kron/sync/SyncModels.kt:63-92

SnapshotDag.inspect hanya menghitung parent yang ID-nya ada di himpunan snapshot yang sedang diperiksa. Parent ID yang hilang tidak dijadikan error. Child dengan parent dangling dapat tetap dianggap single head yang valid, walaupun lineage-nya rusak.

Akibatnya, keputusan berikutnya dapat dibuat dari graph yang tidak memiliki rantai ancestry lengkap. Manifest hanya memvalidasi format/count parent, bukan keberadaan parent.

### CONF-07: Production conflict preview tidak mengirim base snapshot

Severity: P3  
Confidence: tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/sync/ConflictCenter.kt:110-143
- app/src/main/java/com/morneven/kron/backup/BackupManager.kt:304-347
- app/src/test/java/com/morneven/kron/sync/ConflictCenterTest.kt:15-33

ConflictPreviewBuilder mendukung base-aware choice: jika local sama dengan base, remote dapat dipilih otomatis, dan sebaliknya. Namun production preview pada BackupManager memanggil builder tanpa base.

Perubahan satu sisi yang sebenarnya dapat diambil otomatis akan tampil sebagai DIFFERENT dengan automaticChoice=null dan meminta pilihan manual. Ini tidak langsung menyebabkan data loss, tetapi mengurangi resolusi konflik aman dan membuat false conflict.

### CONF-08: Team policy hanya mengenali direct parent, bukan seluruh descendant yang valid

Severity: P3  
Confidence: sedang-tinggi

Lokasi terkait:

- app/src/main/java/com/morneven/kron/team/TeamSnapshotCoordinator.kt:117-125
- app/src/main/java/com/morneven/kron/team/TeamSyncPolicy.kt:544-547
- app/src/test/java/com/morneven/kron/team/TeamSnapshotHeadPolicyTest.kt:11-50

Coordinator hanya mengisi baseGeneration ketika local head muncul sebagai parent langsung dari remote head. Jika remote sudah maju beberapa generation sementara local tidak berubah, remote tetap merupakan descendant yang valid, tetapi tidak dianggap base yang dapat dipull. Policy kemudian dapat menghasilkan CONFLICT alih-alih safe pull.

Ini adalah conservative false conflict dan mengurangi availability, bukan temuan data loss langsung.

## Kontrol yang sudah terlihat baik

- Drive decision engine memblokir fork/multiple heads dan cycle pada test DriveSyncDecisionEngineTest.kt:40-57.
- Preview dan resolve normal melakukan re-list serta re-check expected remote head sebelum apply.
- Team head policy mensyaratkan satu expected valid DAG head.
- Team importer memiliki validasi keuangan yang lebih lengkap daripada refresh path.
- Team merge memakai staging, expected head check, dan compare-and-set style flow.
- Test TeamGraphImporterTest.kt:112-142 menunjukkan independent append-only events diarahkan ke MERGE_PENDING.
- Snapshot retention mempertahankan active head dan parent yang diperlukan untuk lineage.

Kontrol tersebut tidak menutup temuan di atas karena beberapa jalur khusus, terutama account switch, merge executor, refresh Team, dan legacy event path, tidak melewati guard yang sama.

## Status implementation follow-up

### Bug ditemukan

- CALC-01 sampai CALC-06, CONF-02 sampai CONF-07, serta validasi graph Team yang disebut pada CONF-03 sampai CONF-05 memiliki jalur implementasi yang sebelumnya longgar.
- Test lama menganggap snapshot dengan parent yang tidak tersedia valid, sehingga regresi DAG tidak terlihat.

### Fix yang dilakukan

- RESTORE_REVERSAL sekarang ikut formula booked, spent, cash flow, Reports, CSV, dan evidence.
- Pembagian Cash/eBudget memakai pembulatan half-up berbasis `BigInteger`, mempertahankan channel pada nominal kecil.
- Batas period memakai tanggal anchor literal dan dipotong pada `endDate` portfolio.
- Repository menegakkan status period tertutup serta kelayakan channel pada seluruh operasi resolution yang relevan.
- Invariant ledger memeriksa semua event, amount, side, split, budget per account/channel, seal chain, dan Team proof chain.
- CSV menghitung automation/reversal dan jumlah baris receipt yang sebenarnya.
- Account switch memakai validasi dataset, DAG, dangling parent, dan single head sebelum apply.
- Preview konflik Drive memakai snapshot dasar jika tersedia untuk pilihan satu sisi otomatis.
- Fingerprint legacy event mencakup seluruh row graph; refresh Team membandingkan line-level, mutable graph, paused archive, account, template, dan proof metadata.
- Test policy Drive diperbarui agar fixture lineage menyertakan parent yang dirujuk.

### Hasil perbaikan

- Status: done untuk CALC-01 sampai CALC-06, CONF-02 sampai CONF-07, dan hardening refresh/import Team.
- Status: need attention untuk CONF-01. Executor merge aman Drive masih dikunci karena belum ada executor staging yang dapat mengunion journal seal chain tanpa risiko overwrite. Guard ini dipertahankan agar tidak ada merge palsu atau kehilangan data.
- Status: need attention untuk CONF-08. Team Drive saat ini menyimpan satu live snapshot file, sehingga ancestry antar generation tidak tersedia penuh untuk membuktikan descendant multi-hop. Policy tetap fail-closed menjadi konflik.
- Bukti sementara: targeted unit test `FinanceInvariantPropertyTest`, `DriveSyncDecisionEngineTest`, dan `DriveSyncPolicyTest.downloadedSnapshotIsReportedAsAppliedAfterStaging` lulus setelah fixture lineage diperbaiki. Full test, lint, build, dan instrumentation menjadi verifikasi berikutnya.

### Bug switch akun: SQLITE_CONSTRAINT_TRIGGER

#### Bug ditemukan

- Lokasi: `BackupManager.stagePortablePackage`, setelah `migrateAndValidateCandidate` dan sebelum staging receipt/sync metadata.
- Alasan/akar masalah: pembukaan kandidat melalui Room membuat ulang trigger append-only produksi. Tahap staging kemudian memperbarui `localPath` serta metadata receipt hasil salin attachment, sehingga trigger append-only menolak perubahan dengan `SQLITE_CONSTRAINT_TRIGGER`.
- Dampak: switch ke akun Google lain dapat berhenti sebelum kandidat diaktifkan.

#### Fix yang dilakukan

- Trigger kandidat dilepas setelah validasi Room selesai dan sebelum seluruh mutasi staging.
- Kandidat tetap divalidasi dengan SQLite, lalu trigger produksi dibuat ulang otomatis saat database kandidat dibuka sebagai database aktif.
- Database aktif lama tidak disentuh bila staging gagal.

#### Hasil perbaikan

- Status: done pada jalur staging; full unit test lulus. Smoke test beberapa akun Google masih perlu dijalankan pada device.

### Immutable hybrid Drive

#### Bug ditemukan

- Retention sebelumnya dapat menghapus ancestor aktif yang masih dirujuk head, sehingga lineage menjadi dangling. Menyimpan semua snapshot tanpa batas juga membesarkan penggunaan ruang.

#### Fix yang dilakukan

- Retention sekarang selalu mempertahankan seluruh ancestor aktif yang masih reachable dan hanya memangkas recovery/unreachable snapshot.
- Setelah lebih dari delapan active snapshot dengan satu head valid, KRON membuat satu checkpoint immutable penuh tanpa parent, memverifikasi head tunggal, lalu menghapus rantai lama secara terkontrol.
- Fork atau upload bersamaan membatalkan compaction dan mempertahankan lineage lengkap. Checkpoint tidak dibuat bila graph tidak valid.

#### Hasil perbaikan

- Status: done untuk kebijakan retention dan compaction hybrid.
- Batas ruang aktif: satu checkpoint plus maksimal delapan snapshot aktif sebelum compaction berikutnya, dengan puncak sementara satu salinan saat checkpoint dibuat.

### Bug loop "perlu otorisasi ulang"

#### Bug ditemukan

- Lokasi: `KronApp.handleSyncResult`, `AuthorizationClientDriveSession`, dan `TeamSyncRuntime`.
- Alasan/akar masalah: hasil sinkronisasi `AuthorizationRequired` langsung memanggil re-otorisasi lalu mengulang sinkronisasi tanpa batas bila token baru tetap ditolak. Selain itu, token cache worker Team/private tidak dibuang setelah Drive mengembalikan 401/403, sehingga worker dapat memakai token yang sudah ditolak. HTTP 404 Team juga diperlakukan sebagai masalah otorisasi walaupun file/workspace sudah tidak ada.
- Dampak: UI dapat terus meminta otorisasi ulang dan workspace yang hilang menampilkan tindakan yang salah.

#### Fix yang dilakukan

- Re-otorisasi otomatis dibatasi satu percobaan per aksi; percobaan kedua berhenti pada status yang dapat ditindaklanjuti tanpa loop.
- Grant cache yang ditolak diinvalidasi dan token dicabut best-effort sebelum permintaan berikutnya.
- 404 Team dipetakan ke `REVOKED`, sedangkan 401/403 tetap meminta otorisasi.
- Jalur ganti akun tetap memakai passphrase akun tujuan, karena setiap akun Google memiliki passphrase Drive sendiri.

#### Hasil perbaikan

- Status: done pada guard loop dan invalidasi token; smoke test akun Google tambahan masih perlu dibuktikan pada device.
- Bukti uji: unit test 79 lulus, lint debug/release lulus, debug/release build lulus, signature v2 lulus, install-over kedua varian berhasil, dan startup kedua package tanpa crash signature.

### Bug "Drive memiliki 9 snapshot aktif"

#### Bug ditemukan

- Lokasi: `SnapshotDag.inspect` dan guard keputusan sinkronisasi di `DriveSyncCoordinator`.
- Alasan/akar masalah: snapshot lama yang ancestor-nya sudah terhapus oleh retention ditandai sebagai graph rusak, lalu seluruh file aktif dikembalikan sebagai kandidat head. Satu rantai linear akhirnya tampil sebagai sembilan head dan pusat konflik terkunci.
- Dampak: sync, preview konflik, dan account switch tidak dapat memakai snapshot terbaru.

#### Fix yang dilakukan

- Parent dangling tunggal pada graph acyclic dengan satu head diperlakukan sebagai lineage lama yang dapat diperbaiki karena payload setiap snapshot bersifat penuh.
- Fork bercabang, duplicate ID, dan cycle tetap fail-closed.
- Upload berikutnya boleh membuat checkpoint immutable untuk memutus lineage lama; validasi retention dan compaction memakai status graph yang sama.
- Fixture unit test dangling parent diubah untuk memastikan kasus satu head tidak lagi menjadi false conflict.

#### Hasil perbaikan

- Status: done untuk false conflict satu rantai dengan parent lama yang hilang.
- Bukti uji: `DriveSyncDecisionEngineTest.singleDanglingParentIsRepairableFromFullSnapshot` lulus.
- Catatan risiko: beberapa head nyata tetap harus diselesaikan melalui Pusat Konflik dan tidak dipilih otomatis.

### Bug switch akun: `SQLITE_CONSTRAINT_TRIGGER`

#### Bug ditemukan

- Lokasi: `BackupManager.withValidatedPortableCandidate` dan `stageTeamDatabaseForRestart`.
- Alasan/akar masalah: snapshot atau salinan database membawa trigger append-only dari database sumber. Room dapat menjalankan migrasi atau transform Team sebelum trigger staging dilepas, sehingga mutasi disposable dianggap sebagai perubahan audit produksi.
- Dampak: switch akun, refresh/join/leave Team, atau import recovery dapat berhenti dengan kode SQLite 1811 tanpa menyentuh database aktif.

#### Fix yang dilakukan

- Trigger staging dilepas sebelum dan sesudah validasi/migrasi kandidat.
- Salinan plaintext untuk transform Team dibersihkan dari trigger sebelum importer/refresher melakukan mutasi.
- Database aktif tetap memakai trigger saat dibuka kembali; hanya kandidat disposable yang tanpa guard selama transform.

#### Hasil perbaikan

- Status: done pada jalur staging bersama.
- Bukti uji: build dan lint debug/release lulus; unit test regresi Drive tetap lulus.
- Catatan risiko: install-over dan smoke test akun Google tambahan tetap diperlukan untuk membuktikan snapshot produksi yang berbeda.

### Bug rollback switch akun: checksum database lama berubah

#### Bug ditemukan

- Lokasi: `BackupManager.rollbackIncompleteRestore`.
- Alasan/akar masalah: rollback hanya mempercayai checksum metadata yang dibuat sebelum swap. Marker swap yang tertinggal setelah checkpoint/WAL atau percobaan sebelumnya dapat membuat metadata stale walaupun artefak `.restore-old-v2` masih merupakan salinan yang akan dipulihkan.
- Dampak: data lama sebenarnya tersedia, tetapi rollback berhenti dengan pesan checksum berubah.

#### Fix yang dilakukan

- Hash artefak database, WAL, SHM, dan direktori receipt yang benar-benar dipindahkan menjadi checksum verifikasi rollback.
- Metadata lama tetap dipakai sebagai fallback bila artefak rollback tidak tersedia.
- Hasil file setelah restore tetap diverifikasi sebelum marker staging dibersihkan.

#### Hasil perbaikan

- Status: done pada rollback marker stale; database aktif lama tidak diganti kandidat yang gagal.
- Test regresi cold restore ditambahkan untuk metadata checksum stale.

## Prioritas verifikasi lanjutan

Bagian ini adalah daftar area yang perlu dibuktikan oleh test atau product decision; tidak ada implementasi yang dilakukan dalam review ini.

1. Tambahkan skenario angka konkret untuk restore reversal: reversal, restore, budget allocation, cash flow, CSV, report, dan notifier harus dibandingkan pada event yang sama.
2. Uji total kecil dan persentase pecahan untuk pembuatan period baru serta correction Cash/eBudget.
3. Putuskan apakah start/end portfolio bersifat exact date atau calendar period, lalu buktikan perilaku sebelum start dan sesudah end.
4. Uji account switch dengan foreign dataset, fork, multiple head, dangling parent, dan candidate generation yang sama.
5. Uji Team refresh/merge dengan amount non-positive, invalid side, distribusi budget berbeda tetapi total sama, mutable metadata berbeda, dan legacy event yang line composition-nya berbeda.
6. Uji one-sided change dengan base snapshot dan remote descendant lebih dari satu generation.

## Kesimpulan

Logika dasar ledger dan aggregate invariant untuk jalur normal sudah diperkuat. Jalur merge Drive dan ancestry Team multi-hop tetap sengaja dikunci sampai tersedia bukti staging dan metadata history yang lengkap. Tidak ada perubahan schema Room, dependency, atau key mode pada follow-up ini.
