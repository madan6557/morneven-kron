# KRON Google OAuth Setup

Konfigurasi ini hanya diperlukan untuk fitur sinkronisasi Google Drive. KRON tetap dapat dipakai penuh secara lokal tanpa OAuth.

## Persiapan

- Gunakan Google Account yang akan menjadi pemilik konfigurasi aplikasi KRON.
- Jangan hubungkan billing account. KRON hanya memakai kuota gratis Drive API.
- Siapkan homepage dan privacy policy publik pada domain yang sama.
- Pastikan akun pemilik project juga menjadi pemilik domain di Google Search Console.

## Google Cloud

1. Buat project production bernama `KRON Production` di Google Cloud Console.
2. Buka API Library dan aktifkan `Google Drive API`.
3. Pada Google Auth Platform, isi branding:
   - App name: `KRON`
   - User support email: email pemilik
   - Homepage: URL publik KRON
   - Privacy policy: URL privacy policy KRON
   - Developer contact: email pemilik
4. Pilih audience `External`.
   - Untuk pemakaian pribadi, gunakan status `Testing` dan tambahkan alamat Google Anda sebagai test user.
   - Gunakan `In production` hanya setelah homepage, privacy policy, branding, dan verifikasi yang diminta Google siap.
5. Tambahkan hanya scope `https://www.googleapis.com/auth/drive.appdata`.
6. Buat OAuth client bertipe Android:
   - Package name: `com.morneven.kron`
   - SHA-1 release KRON: `54:6C:B4:FC:CA:18:BC:D6:00:22:53:FD:BB:A9:36:EA:A7:FF:ED:9F`
7. Buat OAuth client bertipe Web application untuk Credential Manager. Redirect URI dan client secret tidak digunakan oleh APK.

## Konfigurasi lokal build

Buat file berikut di luar repository:

`C:\Users\mikyl\.android\kron-google.properties`

Isi:

```properties
webClientId=CLIENT_ID_WEB.apps.googleusercontent.com
privacyPolicyUrl=https://domain-anda.example/kron/privacy
```

OAuth client ID bukan rahasia. Client secret tidak boleh dimasukkan ke file ini, source code, APK, atau repository.

## Verifikasi

- Build release harus memakai signing key KRON yang sama dengan versi sebelumnya.
- Pilih `Hubungkan Google Drive` di Pengaturan.
- Pilih akun Google tujuan.
- Pastikan consent hanya meminta data aplikasi KRON.
- Uji upload, download, konflik dua perangkat, putuskan akun, dan login ulang.
- Jika Google meminta billing, jangan aktifkan billing. KRON harus kembali ke backup manual.

## Persetujuan yang dilakukan pengguna

Ada dua persetujuan yang berbeda:

1. Pemilik aplikasi menyiapkan Google Auth Platform satu kali di Google Cloud project miliknya.
2. Pemilik data menekan `Hubungkan Google Drive`, memilih akun Drive, lalu menyetujui scope `drive.appdata` pada perangkat Android.

Jangan membagikan password Google, client secret, access token, refresh token, atau kode 2FA. Client ID boleh dimasukkan ke konfigurasi build karena bukan rahasia.
