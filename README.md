# FluidLauncher

Uygulamaları ekranda serbestçe taşınabilen ve boyutlandırılabilen pencerelerde açan bir Android launcher'ı. Her uygulama telefon boyutunda kendi sanal ekranında çalışır, pencere onun küçültülmüş görüntüsünü gösterir; yani uygulamalar pencereye sıkışmak yerine küçülerek sığar.

> Yalnızca realme RMX3842 (Android 16) üzerinde denendi. Başka cihazlarda farklı davranabilir.

## Özellikler

**Pencereler**
- Sanal ekranlarda çalışan, ölçekli uygulama pencereleri
- Sürükleme, serbest boyutlandırma, çift dokunarak tam ekran; tam ekran pencerede başlık çubuğu alta geçer
- Pencereler üst üste binmez ve başka masaüstüne kaçmaz: varsayılan olarak birbirini iterler, uygulama düğmesine üç kez dokununca döşeme (tiling) moduna geçilir
- Başlık çubuğundaki **Aa** butonuyla uygulama ölçeği (%50–%200), her uygulama için ayrı kaydedilir
- Gerçek tam ekrandaki uygulamaların üstünde de duran uygulama düğmesi ve pencereler

**Masaüstü**
- İki düzen: **Tray** (varsayılan) ve **Klasik**
- Tray düzeninde ilk masaüstü ana ekrandır: saat, tarih, takvimdeki sıradaki etkinlik, bildirim noktaları, arama kutulu uygulama listesi ve kaydırınca dalgalanan alfabe şeridi. Buradan açılan uygulamalar 2. masaüstünde açılır
- Altta, parmağı kaydırarak da kullanılabilen 5 masaüstlük seçici; arka plandaki uygulamalar kapanmaz
- Uygulamaya basılı tutunca: uygulama başına "Tam ekranda aç" anahtarı, uygulama bilgisi ve kaldırma
- Masaüstleriyle birlikte kayan 16:9 duvar kağıdı

**Görünüm**
- Hazır temalar, renk seçerek kendi temanı oluşturma ve duvar kağıdından otomatik tema
- Font içe aktarma ve seçme, ikonlara temaya uygun gölgelendirme, liste ya da ızgara görünümü

**Diğer**
- Ayarlanabilir pencere hareketleri, ekran yenileme hızı (120/90/60 Hz) ve dil (Türkçe / English)
- İlk açılışta etkileşimli kullanım rehberi
- Başka bir uygulamaya gerek yok: ekran servisi, telefonun kablosuz hata ayıklamasıyla launcher'ın içinden başlatılır ve yeniden başlatmadan sonra kendiliğinden açılır
- Ekran servisi kapalıyken sistemin kendi pencere moduna geri dönüş
- Uygulamanın içinden görülebilen açık kaynak lisansları

## Gereksinimler

- Android 13 veya üstü
- Kablosuz hata ayıklama (Android 11+, bir Wi‑Fi ağına bağlıyken) ya da bilgisayarda ADB; root gerekmez
- Derlemek için JDK 17 ve Android SDK 36

## İndirme

Hazır APK'yı [Releases](https://github.com/Jeoalist/fluidlauncher/releases) sayfasından indirip kurabilirsiniz:

```sh
adb install -r FluidLauncher-1.1.0.apk
```

Yeni sürümler eskisinin üzerine kurulur. Kendi derlediğiniz bir sürüm telefonda kuruluysa önce onu kaldırın; imzalar farklı olduğu için üzerine kurulamaz.

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
2. Ölçekli pencereleri çizen ekran servisini başlatın. Bunun iki yolu var:
   - **Telefondan (önerilen, bir kez yapılır):** Wi‑Fi'a bağlıyken masaüstünde boş bir yere basılı tutup Ayarlar'ı açın, **Ekran servisi → Kablosuz hata ayıklamayla eşleştir**'e dokunun. Açılan Geliştirici seçeneklerinde **Kablosuz hata ayıklama → Eşleştirme kodu ile cihaz eşle**'yi açın ve gösterilen altı haneli kodu FluidLauncher bildirimine yazın. Launcher eşleşir, servisi başlatır ve kendine "güvenli ayarları değiştirme" iznini verir. Bundan sonra telefon yeniden başlayınca Wi‑Fi'a bağlanır bağlanmaz kablosuz hata ayıklamayı kısa süreliğine açıp servisi kendisi başlatır, sonra geri kapatır.
   - **Bilgisayardan:** `adb shell sh -s < fluidlauncher-start.sh`. Bu yol her yeniden başlatmadan sonra tekrarlanmalıdır.

Ekran servisi kapalıyken kullanılan sistem pencereleri için şu ayarlar ve bir yeniden başlatma gerekir:

```sh
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
```

Kablosuz hata ayıklama gerçek bir Wi‑Fi ağı ister; telefonun kendi hotspot'u yetmez. Kendiliğinden başlatma için eşleştirirken o ağda "Bu ağda her zaman izin ver" seçeneğini işaretleyin.

## Kullanım

| Hareket | Sonuç |
|---|---|
| Yuvarlak düğmeye dokunma | Uygulama araması; tekrar dokununca kapanır |
| Yuvarlak düğmeye üç kez dokunma | İterek yerleştirme ↔ döşeme (tiling) |
| Yuvarlak düğmeye basılı tutma | Arka plandaki uygulamaları kapatır |
| Düğmeyi sürükleme | Düğmeyi taşır |
| Başlık çubuğunu sürükleme | Pencereyi taşır |
| Başlık çubuğuna çift dokunma | Tam ekran / eski boyut |
| Başlık çubuğuna üç kez dokunma | Uygulamayı yan çevirir |
| Başlık çubuğuna basılı tutma | Uygulamayı gerçek tam ekranda açar |
| Başlık çubuğunun sol köşesi | Dokunma: Geri, sürükleme: boyutlandırma |
| **Aa** | Uygulama ölçeği slider'ı |
| Alttaki masaüstü seçici | Dokunarak ya da parmağı kaydırarak masaüstü değiştirir |
| Uygulamaya basılı tutma | Tam ekranda aç anahtarı, uygulama bilgisi, kaldırma |
| Masaüstünde boş bir yere basılı tutma | Ayarlar |

Başlık çubuğu hareketleri Ayarlar → Pencere hareketleri'nden değiştirilebilir.

## Bilinen sınırlar

- Launcher yeniden başlayınca pencereler kapanır.
- Pencere içinde sistemin geri hareketi yoktur, uygulamanın kendi geri düğmeleri kullanılır.
- Ekran görüntüsünü engelleyen uygulamalar pencerede siyah görünebilir.
- Her pencere ayrı bellek ve işlemci harcar.

## Güvenlik notu

Eşleştirme, launcher'a telefonun kendi adb'si üzerinden shell yetkisi verir ve launcher'ın anahtarı adb'ye güvenilir olarak kaydedilir. Launcher bu yetkiyi yalnızca ekran servisini başlatmak ve kablosuz hata ayıklamayı açıp kapatmak için kullanır. Yetkiyi geri almak için Geliştirici seçeneklerinde **Kablosuz hata ayıklama → Eşlenen cihazlar**'dan FluidLauncher'ı kaldırın. Varsayılan olmayan sistem ayarları cihazın korumalarını gevşetir; bunları yalnızca ne yaptığını bildiğiniz cihazlarda açın.

## Lisans

[MIT](LICENSE).

Kullanılan kütüphaneler:

- [libadb-android](https://github.com/MuntashirAkon/libadb-android), Apache-2.0 seçeneğiyle
- [spake2-java](https://github.com/MuntashirAkon/spake2-java), LGPL-3.0 (libadb-android'in bağımlılığı; kaynağı açık, değiştirilip yeniden derlenebilir)
- [Conscrypt](https://github.com/google/conscrypt), Apache-2.0
- [Bouncy Castle](https://www.bouncycastle.org/), MIT benzeri Bouncy Castle lisansı
