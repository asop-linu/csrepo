<p align="center">
  <a href="https://github.com/asop-linu/csrepo">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://cdn-icons-png.flaticon.com/512/2920/2920297.png">
      <source media="(prefers-color-scheme: light)" srcset="https://cdn-icons-png.flaticon.com/512/3136/3136684.png">
      <img src="https://cdn-icons-png.flaticon.com/512/3136/3136684.png" alt="Logo" width="80" height="80">
    </picture>
  </a>

  <h3 align="center">MiraCsX</h3>

  <p align="center">
    CloudStream 4 Extensions untuk Situs Streaming Indonesia
    <br />
    Nonton anime dan film favoritmu langsung dari CloudStream
  </p>

  <p align="center">
    <a href="https://github.com/asop-linu/csrepo/issues">Report Bug</a>
    ·
    <a href="https://github.com/asop-linu/csrepo/issues">Request Situs</a>
  </p>
</p>

---

## Tentang

**MiraCsX** adalah kumpulan plugin [CloudStream 4](https://github.com/recloudstream/cloudstream) untuk situs streaming populer di Indonesia. Plugin ini memungkinkan kamu menonton anime, film, dan drama langsung dari aplikasi CloudStream di Android.

### Fitur

- Streaming anime subtitle Indonesia
- Film dan drama Indonesia
- Support multiple server
- Download support
- Gratis dan open source

---

## Daftar Plugin

### Anime

| Plugin | Situs&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp;&emsp; | Status |
|---|---|---|
| Anichin | [anichin.moe](https://anichin.moe) | ✅ Stable |
| AnimeIndo | [anime-indo.lol](https://anime-indo.lol) | ✅ Stable |
| CA3D | [ca3d.site](https://ca3d.site) | ✅ Stable |
| Donghub | [donghive.vip](https://donghive.vip) | ✅ Stable |
| Kuramanime | [v20.kuramanime.ing](https://v20.kuramanime.ing) | ✅ Stable |
| Kuronime | [kuronime.sbs](https://kuronime.sbs) | ✅ Stable |
| Nimegami | [nimegami.id](https://nimegami.id) | ✅ Stable |
| Oploverz | [oploverz.mov](https://oploverz.mov) | ✅ Stable |
| Otakudesu | [otakudesu.blog](https://otakudesu.blog) | ✅ Stable |
| Sokuja | [x6.sokuja.uk](https://x6.sokuja.uk) | ✅ Stable |
| YunshanID | [yunshanid.site](https://yunshanid.site) | ✅ Stable |

### Film & Series

| Plugin | Situs | Status |
|---|---|---|
| ArrowTV | [arrowtv.net](https://arrowtv.net) | ✅ Stable |
| BlurayUFR | [blurayufr.site](https://blurayufr.site) | ✅ Stable |
| GfyMultimedx | [gfymultimedx.online](https://gfymultimedx.online) | ⚠️ Butuh login + Turnstile |
| Idlix | [z3.idlixku.com](https://z3.idlixku.com) | 🧪 Development |
| Indomax21 | [idmxl.ink](https://idmxl.ink) | ✅ Stable |
| KlikXXI | [klikxxi.shop](https://klikxxi.shop) | ✅ Stable |
| LayarAsia | [server-1.layar.asia](https://server-1.layar.asia) | ✅ Stable |
| LK21 | [lk21.de](https://lk21.de) | ✅ Stable |
| MovieBox | [themoviebox.org](https://themoviebox.org) | ✅ Stable |
| NGEFILM21 | [new39.ngefilm.site](https://new39.ngefilm.site) | ✅ Stable |
| Nomat | [nomat.world](https://nomat.world) | ⚠️ Butuh captcha |
| PusatFilm | [new1.pusatfilm21info.com](https://new1.pusatfilm21info.com) | ✅ Stable |
| Rebahin | [rebahin.cam](https://rebahin.cam) | ✅ Stable |
| Sarangfilm | [sarangfilm.co](https://sarangfilm.co) | ✅ Stable |
| Sflix | [sflix.film](https://sflix.film) | ✅ Stable |
| SontolFilm | [sontolfilm.com](https://sontolfilm.com) | ✅ Stable |
| SontolFilmDubIndo | [sontolfilm.com/quality/dubbing/](https://sontolfilm.com/quality/dubbing/) | ✅ Stable |
| WGFilm21 | [rotator.wgfilm21.net](https://rotator.wgfilm21.net) | ✅ Stable |

---

## Instalasi

### Prasyarat

- [CloudStream 4](https://github.com/recloudstream/cloudstream) terinstall di Android

### Cara Install

1. Buka CloudStream
2. Masuk ke **Settings** > **Repository**
3. Tambahkan URL repository berikut:

```
https://raw.githubusercontent.com/asop-linu/csrepo/builds/repo.json
```

4. Kembali ke halaman utama, plugin akan muncul di tab **Extensions**
5. Install plugin yang diinginkan

---

## Cara Build

### Prasyarat

- JDK 17+
- Android SDK

### Build

```bash
# Clone repository
git clone https://github.com/asop-linu/csrepo.git
cd csrepo

# Build semua plugin
./gradlew assembleRelease

# Build satu plugin
./gradlew :AnimeIndo:assembleRelease
```

File `.cs3` akan dihasilkan di folder `build/` masing-masing plugin.

---

## Kontribusi

Kontribusi sangat diterima. Berikut cara berkontribusi:

1. Fork repository ini
2. Buat branch baru (`git checkout -b feature/situs-baru`)
3. Commit perubahan (`git commit -m 'Tambah plugin SitusBaru'`)
4. Push ke branch (`git push origin feature/situs-baru`)
5. Buat Pull Request

### Menambah Plugin Baru

1. Buat folder baru di root repository
2. Buat `build.gradle.kts` dengan konfigurasi plugin
3. Buat class provider yang extends `MainAPI()`
4. Buat class plugin yang extends `Plugin()`
5. Tambahkan plugin ke `settings.gradle.kts`

Lihat contoh plugin yang sudah ada untuk referensi.

---

## Disclaimer

- Plugin ini hanya menyediakan interface untuk mengakses situs publik
- Konten disediakan oleh situs pihak ketiga, bukan oleh kami
- Gunakan dengan bijak dan sesuai hukum yang berlaku

---

## Lisensi

MIT License - lihat [LICENSE](LICENSE) untuk detail.

---

## Credit

- [CloudStream 4](https://github.com/recloudstream/cloudstream) - Platform
- [ExtremeBoyGG](https://github.com/ExtremeBoyGG/nonton-indo) - Repo asli / referensi extension

---

## Star History

<a href="https://star-history.com/#asop-linu/csrepo&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=asop-linu/csrepo&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=asop-linu/csrepo&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=asop-linu/csrepo&type=Date" />
  </picture>
</a>
