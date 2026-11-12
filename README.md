# FluidLauncher

Uygulamaları ekranda serbestçe taşınabilen ve boyutlandırılabilen pencerelerde açan bir Android launcher'ı. Her uygulama telefon boyutunda kendi sanal ekranında çalışır, pencere onun küçültülmüş görüntüsünü gösterir; yani uygulamalar pencereye sıkışmak yerine küçülerek sığar.

> Yalnızca realme RMX3842 (Android 16) üzerinde denendi. Başka cihazlarda farklı davranabilir.

## Özellikler

- Sanal ekranlarda çalışan, ölçekli uygulama pencereleri
- Sürükleme, serbest boyutlandırma ve çift dokunarak tam ekran
- Sol alttan çekerek geçilen sanal masaüstleri; arka plandaki uygulamalar kapanmaz
- Ekranda taşınabilen düğme ve Türkçe karakter gerektirmeyen uygulama araması
- Gerçek tam ekrandaki uygulamaların üstünde de duran uygulama düğmesi ve pencereler
- Ayarlanabilir pencere hareketleri, ekran yenileme hızı (120/90/60 Hz) ve dil (Türkçe / English)
- İlk açılışta etkileşimli kullanım rehberi
- Shizuku kapalıyken sistemin kendi pencere moduna geri dönüş

## Gereksinimler

- Android 13 veya üstü
- ADB veya [Shizuku](https://github.com/RikkaApps/Shizuku) (root gerekmez)
- Derlemek için JDK 17 ve Android SDK 36

## İndirme

Hazır APK'yı [Releases](https://github.com/Jeoalist/fluidlauncher/releases) sayfasından indirip kurabilirsiniz:

```sh
adb install FluidLauncher-1.0.0.apk
```

Kendi derlediğiniz bir sürüm telefonda kuruluysa önce onu kaldırın; imzalar farklı olduğu için üzerine kurulamaz.

## Derleme

Proje köküne, SDK yolunu gösteren bir `local.properties` ekleyin:

```
sdk.dir=/android/sdk/yolu
```

Ardından derleyip kurun:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Kurulum

1. FluidLauncher'ı sistem ayarlarından varsayılan ana ekran yapın.
2. Sanal ekran servisini iki yoldan biriyle başlatın:
   - **Betikle (Shizuku gerekmez):** `adb shell sh -s < fluidlauncher-start.sh`
   - **Shizuku ile:** Shizuku'yu başlatıp masaüstündeki "Küçültme için izin ver" düğmesine dokunun; servis otomatik başlar. İsterseniz betiği Shizuku'nun `rish` aracıyla da çalıştırabilirsiniz.

realme, OPPO ve OnePlus cihazlarda Shizuku "ADB'nin izni sınırlıdır" uyarısı verirse Geliştirici seçeneklerinden **İzin izlemeyi devre dışı bırak** ayarını açıp Shizuku'yu yeniden başlatın.

Shizuku kapalıyken kullanılan sistem pencereleri için şu ayarlar ve bir yeniden başlatma gerekir:

```sh
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
```

Servis telefon her yeniden başladığında tekrar başlatılmalıdır; uygulama güncellenince de betiği yeniden çalıştırın. Shizuku kullanıyorsanız onu da tekrar başlatın. Rootsuz başlatmak için bilgisayar bağlantısı veya bir Wi‑Fi ağına bağlı olmak gerekir; telefonun kendi hotspot'u yetmez.

## Kullanım

| Hareket | Sonuç |
|---|---|
| Yuvarlak düğmeye dokunma | Uygulama araması |
| Düğmeyi sürükleme | Düğmeyi taşır |
| Üst barı sürükleme | Pencereyi taşır |
| Üst barın sol köşesi `◤` | Pencereyi boyutlandırır |
| Üst barın ortasına çift dokunma | Tam ekran / eski boyut |
| Ekranın sol alt bölgesinden yana çekme | Masaüstü değiştirir |
| Masaüstüne basılı tutma | Pencere boyutu, duvar kağıdı, ayarlar |

Masaüstü değiştirmek için hareketi ekranın en alt çizgisinin biraz üstünden başlatın; en alt çizgi sistemin kendi hareketlerine ayrılmıştır.

## Bilinen sınırlar

- Pencereler masaüstüne aittir; tam ekran bir uygulama açıkken arkada kalır.
- Launcher yeniden başlayınca pencereler kapanır.
- Pencere içinde sistemin geri hareketi yoktur, uygulamanın kendi geri düğmeleri kullanılır.
- Ekran görüntüsünü engelleyen uygulamalar pencerede siyah görünebilir.
- Her pencere ayrı bellek ve işlemci harcar.

## Güvenlik notu

Shizuku, izin verdiğiniz uygulamalara adb düzeyinde yetki sağlar. "İzin izlemeyi devre dışı bırak" ayarı ve varsayılan olmayan sistem ayarları cihazın korumalarını gevşetir; bunları yalnızca ne yaptığını bildiğiniz cihazlarda açın.

## Lisans

[MIT](LICENSE). [Shizuku API](https://github.com/RikkaApps/Shizuku-API) kullanılmaktadır (MIT).
