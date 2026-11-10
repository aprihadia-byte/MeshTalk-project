# Cara mendapatkan APK (tanpa Android Studio)

1. Buat akun di github.com, lalu buat repository baru (New repository).
2. Upload SEMUA isi folder ini (termasuk folder tersembunyi .github) ke repository itu.
   Cara mudah: di halaman repo klik "uploading an existing file", seret semua file/folder.
3. Buka tab "Actions" -> tunggu workflow "Build APK" selesai (sekitar 5-10 menit, tanda centang hijau).
4. Klik run tersebut -> bagian "Artifacts" -> unduh "MeshTalk-APK" (zip berisi app-debug.apk).
5. Ekstrak zip, kirim app-debug.apk ke HP, buka, izinkan "Instal dari sumber tidak dikenal".

Catatan: aplikasi butuh server signaling (server.js) yang berjalan, lalu isi "Alamat server"
di aplikasi dengan alamat server itu (contoh http://192.168.1.10:3000 untuk jaringan WiFi yang sama).
