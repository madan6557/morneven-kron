# KRON Changelog

## 1.0.13 - 2026-07-17

- Menambahkan throttling autentikasi, target budget pemasukan, dan mode pengeluaran tak terduga.
- Menghapus FAB pengeluaran global dan memperjelas alur form pengeluaran serta transfer.
- Menambahkan grafik cash flow interaktif, filter rentang, dan rincian aktual per budget.
- Menambahkan konfirmasi beralasan untuk menghentikan portfolio atau jadwal otomatis.
- Menampilkan nomor versi dinamis pada Pengaturan.

## 1.0.12 - 2026-07-17

- Menambahkan tombol Hentikan jadwal pada transaksi otomatis.
- Penghentian aturan otomatis tercatat sebagai event dan audit tanpa menghapus occurrence sebelumnya.

## 1.0.11 - 2026-07-17

- Menambahkan tombol Hentikan portfolio.
- Portfolio yang dihentikan tidak membuat periode baru, sementara seluruh periode dan jurnal lama tetap dipertahankan.
- Penghentian tercatat sebagai event sistem dan audit.

## 1.0.10 - 2026-07-17

- Setelah autentikasi app lock, visibilitas mengembalikan pilihan terakhir jika fitur pengingat aktif.
- Form koreksi budget dipindahkan ke dalam kartu kategori yang sedang dikoreksi.
- Jadwal otomatis diberi penjelasan bahwa revert occurrence tidak membatalkan aturan jadwal.

## 1.0.9 - 2026-07-17

- Memperbaiki penyimpanan preferensi "Ingat visibilitas" agar saat dinonaktifkan selalu kembali tersembunyi dan tidak memakai nilai lama.

## 1.0.8 - 2026-07-17

- Menambahkan tampilan detail setiap periode budget.
- Menambahkan koreksi nominal budget melalui jurnal koreksi dan audit.
- Koreksi tidak menghapus jurnal lama dan memeriksa saldo Main Vault.

## 1.0.7 - 2026-07-17

- Memasang aset logo launcher KRON pada layar kunci.
- Menghapus monogram teks sementara dari komponen logo layar kunci.

## 1.0.6 - 2026-07-17

- Mengganti background layar kunci menjadi warna solid sesuai tema.
- Memperbaiki warna judul layar kunci agar selalu memakai warna `onSurface`.
- Menjadikan versi 1.0.6 sebagai baseline rekaman rollback pertama.
