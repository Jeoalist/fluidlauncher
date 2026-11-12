# FluidLauncher Yatay Mod (Landscape) Test Raporu ve Hata Listesi

> **Bu belge başka bir agent için hazırlanmıştır.** Kod üzerinde hiçbir değişiklik yapılmamıştır.
> Test ortamı: Fiziksel cihaz `37ca206c` (realme `RMX3842`, Android 16, ekran `1080x2412` / yatayda `2412x1080`, `480dpi`, `density = 3.0`, sol tarafta kamera deliği / `displayCutout` = `112px`).
> Başlangıçtaki cihaz tercihleri: `desktop_layout = TRAY`, `tiling = true`, `window_size = SMALL`, `apps_button_drawer = false`, `refresh_rate = 120`.

---

## Özet (En Kritik Bulgular)

1. **Tiling modunda sonsuz layout + `VirtualDisplay` yeniden boyutlandırma döngüsü** (saniyede 60–120+ kez `Resize VirtualDisplay`, 4. pencere siyah kalıyor).
2. **Yatay modda Tiling ve Fill pencereleri sanal ekranı kullanılamaz derecede basıklaştırıyor** (`520x160` px = `260x80dp`!), portre zorunlu uygulamalarda siyah yan boşluklar oluşuyor ve `Fill` modunda `DesktopPicker` altta pencerenin üstünü kapatıyor.
3. **Serbest (Untiled) modda açılan pencereler aşırı dar doğuyor (`146px` = `48dp`), `✕` kapatma düğmesi kayboluyor ve altları `DesktopPicker` / gesture bar altına taşıyor.**
4. **Yatay ↔ Dikey döndürmelerde pencereler ve `FloatingWindows` ekran dışına taşıyor** (kapatma/tutma alanları ulaşılamaz hale geliyor).
5. **Ana ekran (`TrayDesktop` - Masaüstü 1) yatayda kullanılamaz**: saat alanı dikeyde yarım ekranı yediği için uygulama listesinde yalnızca **1–1.5 satır** görünüyor; sağdaki **A–Z harf şeridi (`AlphabetIndex`)** önce ekran dışına kesiliyor, ardından IME/layout sonrası tek bir beyaz leke halinde üst üste biniyor.
6. **Masaüstü 2–5'te boş alana basılı tutarak Ayarlar açılamıyor** (Tray düzeninde).
7. **Display Cutout (çentik/kamera deliği) ve yan sistem çubuğu inset'leri** pencere yerleşimi, `SearchActivity` ve `TutorialOverlay`'de dikkate alınmıyor.

---

## Doğrudan Cihazda Gözlemlenen Hatalar

### 1. [KRİTİK] Tiling Modunda Sonsuz Layout ve `VirtualDisplay` Yeniden Boyutlandırma Döngüsü
* **Gözlem:** Cihazda tiling açıkken (2 pencerede her ~16ms'de bir, 4 pencerede 63ms içinde 8+ kez) `logcat`'te aralıksız `VirtualDisplayAdapter: Resize VirtualDisplay pencere-...` spam'i oluşuyor:
  ```text
  VirtualDisplayAdapter: Resize VirtualDisplay pencere-com.finalwire.aida64 to 520 160
  VirtualDisplayAdapter: Resize VirtualDisplay pencere-com.android.chrome to 520 160
  VirtualDisplayAdapter: Resize VirtualDisplay pencere-com.google.android.apps.nbu.files to 520 160
  VirtualDisplayAdapter: Resize VirtualDisplay pencere-com.coloros.calculator to 1584 160
  ```
  4 pencereli yatay düzende `com.coloros.calculator` (`ws 4`) bu sürekli resize yüzünden hiç kare çizemedi ve **tamamen siyah** kaldı.
* **Kök Neden:**
  * [`MainActivity.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/MainActivity.kt#L104-L109): `root.addOnLayoutChangeListener` her layout geçişinde `keepWindowsInArea()` çağırıyor.
  * [`AppWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L289-L293): `keepInArea()` tiling açıkken `retileAll()` çağırıyor.
  * [`AppWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L200-L219) & [`AppWindow.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L66-L78): `retile()` her pencere için `setFrame()` çağırıyor; `setFrame()` de boyut değişmemiş olsa bile koşulsuz olarak `applySize()` (`layoutParams = LayoutParams(...)` ataması `root`'a kadar `requestLayout()` tetikler) ve `resizeDisplay()` -> `service.resizeDisplay(...)` çağırıyor. Bu da her karede yeni bir layout geçişi tetikleyerek sonsuz döngü yaratıyor.
* **Öneri:** `setFrame()` ve `applySize()` içinde mevcut `x/y/windowWidth/contentHeight` değerleri değişmediyse `layoutParams` ataması yapılmamalı; `resizeDisplay()` yalnızca `(displayWidth, displayHeight, spec.dpi)` gerçekten değiştiğinde IPC çağrısı yapmalı.

---

### 2. [KRİTİK] Yatay Modda Tiling ve Fill Düzeni Sanal Ekranları Çok Basık Hale Getiriyor
* **Gözlem:**
  * **1 pencere (`AIDA64`):** Tam genişliğe geriliyor (`1584x404` @ `320dpi` = `792dp x 202dp`, `smll land`). Ekrana yalnızca 3 liste satırı sığıyor.
  * **2 pencere (`AIDA64` + `Akbank` / `Chrome`):** Her sanal ekran `786x404` (`393dp x 202dp`, `sw202dp`). Portre zorunlu uygulamalar (`Akbank`) bu yatay sanal ekranda `104dp x 202dp` şeride sıkışıp sağında/solunda devasa siyah barlar (`pillarbox`) oluşturuyor.
  * **4 pencere (`AIDA64`, `Chrome`, `Files`, `Calculator`):** Üst satırdaki 3 pencerenin sanal ekranları **`520x160` px @ `320dpi` (`260dp x 80dp`)** oluyor; sadece uygulama üst çubukları görünüyor, içerik tamamen yok. Alt satırdaki 4. pencere (`1584x160` = `792dp x 80dp`) ise tamamen siyah kaldı.
  * **Fill (çift dokunuşla tam ekran):** `Calculator` (`1608x648` sanal ekran = `804dp x 324dp`) butonları 1px yüksekliğinde çizgilere sıkıştı. Ayrıca alttaki `DesktopPicker` (`一 二 三 四 五`) dolu pencerenin alt kısmının ve butonlarının üzerine biniyor.
* **Kök Neden:**
  * [`AppWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L204-L218): `retile()` yatayda `columns = minOf(count, 3)` kullanıyor ve [`AppWindow.setFrame()`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L68-L77) `displayWidth = (windowWidth * 2 / 3)` ile sanal ekranın en-boy oranını tile kutusuna zorla eşitlediği için yatay tile'lar kısa ve geniş (en fazla ~202dp, 4 pencerede ~80dp yükseklikte) sanal ekranlar üretiyor.
  * [`AppWindow.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L253-L286): `availableContentHeight()` ve `fillArea()`, `areaHeight()` (`1080px`) kullanıyor; `bottomLimit` (Tray masaüstündeki `DesktopPicker` üst sınırı) hesaba katılmıyor.

---

### 3. [KRİTİK] Serbest (Untiled) Modda Pencere Yerleşimi: Mikroskobik Genişlik, Kaybolan `✕` Düğmesi ve `DesktopPicker` Çakışması
* **Gözlem:**
  * `tiling = false` iken yeni pencere açıldığında (`cascade = 4` iken `Files` penceresi) ekranın altında **`146px` (`~48dp`) genişliğinde** minik bir şerit pencere doğdu. Bu genişlik `gripView` (`44dp = 132px`) + `closeView` (`44dp = 132px`) toplamından küçük olduğu için `ws 3` etiketi ve **`✕` kapatma düğmesi tamamen görünmez/erişilemez** oldu; ayrıca `gripView` tüm başlık çubuğunu kapladığı için pencere başlık çubuğundan sürüklenemedi (yalnızca boyutlandırılabildi).
  * `cascade = 0/1` iken açılan `AIDA64` penceresi de `~302px` (`100dp`) genişliğinde doğdu ve **`✕` düğmesi sağdan yarı kesik** (`>`) göründü.
  * Açılan serbest pencerelerin alt kenarları `y ~ 1040..1080` seviyesine kadar inerek alttaki `DesktopPicker` (`一 二 三 四 五`) ve sistem gesture çizgisinin arkasına/üzerine bindi.
* **Kök Neden:**
  * [`AppWindows.kt` (`Placement.next`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L421-L436):
    * `step = bounds.width() * CASCADE_STEP` (`2412 * 0.05 = 120.6px`) yatay ekran genişliğinden türetilip `top = bounds.height() / 8f + cascade * step` dikey konumuna ekleniyor! `cascade = 4` iken `top = 617px`, `maxContentHeight = 1080 - 617 - 135 = 328px`, buradan `width = 328 * 720 / 1608 = 146px` çıkıyor.
    * `Placement.next()`, `bottomLimit` (Tray `pickerTop`) değerini hesaba katmıyor (`bounds.height() - bounds.height() / 8` kullanıyor).
  * [`AppWindow.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L140): Yapıcıda `windowWidth = initialWidth` için `minWidth` (`140dp = 420px`) alt sınırı uygulanmıyor (yalnızca `setFrame` ve `resizeFromTopLeft`'te uygulanıyor).
  * [`AppWindows.kt` (`freeSpot`, `pushOut`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L155-L193): Yeni pencere boyu `areaBottom - topLimit` yüksekliğinden büyük olduğunda `freeSpot()` daima `null` dönüyor ve `resolve()` -> `pushOut(..., force = true)` pencereyi `areaBottom` sınırının altına (`DesktopPicker` üstüne / ekran dışına) itiyor.
  * [`AppWindow.kt` (`areaHeight`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L109): `areaHeight()` parent yüksekliğini (`1080px`) döndürüyor, `bottomLimit` değerini bilmiyor; bu yüzden `moveTo()` ve `resizeFromTopLeft()` de pencerelerin `DesktopPicker` üzerine inmesine izin veriyor.

---

### 4. [YÜKSEK] Ekran Döndürmede (`Yatay ↔ Dikey`) Pencerelerin Ekran Dışına Taşması
* **Gözlem:**
  * Yatayda açılmış veya tile edilmiş pencereler varken cihaz dikeye döndürüldüğünde:
    * `1180px` genişliğindeki `ws 1`, `1080px` dikey ekranda küçülmedi ve sağ kenardan taştı (`✕` düğmesi ekran dışında kaldı).
    * Sağ taraftaki `ws 2` penceresinin yarısı ekranın sağından dışarı çıktı (etiket ve `✕` ulaşılamaz oldu).
  * Tekrar yataya dönüldüğünde pencereler birbirinin üstüne itilmiş / bozulmuş boyutlarla kaldı.
* **Kök Neden:**
  * [`AppWindow.kt` (`scaleForRotation`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L210-L230): Yatayda açılan pencereler için `rotationShrink = 1f` olduğundan dikeye dönerken `scale = 1f / rotationShrink = 1f` oluyor, pencere genişliği hiç küçülmüyor. Ayrıca `x` koordinatı ve `windowWidth` yeni `areaWidth()` sınırına kıstırılmıyor (yalnızca `y` için `coerceAtMost` yapılmış).
  * [`AppWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L342): `rotated` kontrolü sadece en/boy oranı değişimini (`(oldWidth > oldHeight) != (width > height)`) kontrol ediyor; `ROTATION_90` ↔ `ROTATION_270` gibi 180° yön değişimlerinde cutout/inset değişikliği yeniden uygulanmıyor.

---

### 5. [KRİTİK] Tray Ana Ekranı (Masaüstü 1): Uygulama Listesi Kullanılamaz & `AlphabetIndex` Bozuluyor
* **Gözlem:**
  * Yatayda Masaüstü 1'de `clockBlock` (`21:55` + tarih) üst ~540px'i, `DesktopPicker` alt ~200px'i kaplıyor; `AppListPanel` toplam `~340px` yüksekliğe sıkışıyor ve arama kutusundan sonra **`GridView` için ~190px kalıyor (ekranda sadece 1–1.5 satır uygulama görünüyor)**, sağ taraftaki geniş alan ise boş kalıyor.
  * Sağdaki **A–Z harf şeridi (`AlphabetIndex`)** ilk açılışta yalnızca `☆ A B C D E` harflerini gösterip `F..#` kısmını ekran altından taşırıyor.
  * Arama kutusuna dokunulup klavye açıldığında (veya sonraki bir layout geçişinde) ise tüm 27 harf (`☆..#`) `~300px` yüksekliğe `42px` (`14sp`) fontla üst üste çizilip **okunamaz tek bir beyaz lekeye** dönüşüyor.
  * Arama kutusu odaklı kaldığı için Masaüstü 1'e geri dönüldüğünde veya ayarlar diyalogu açıldığında yüzen klavye tekrar otomatik olarak açıldı.
* **Kök Neden:**
  * [`TrayDesktop.kt` (`place`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L238-L261): Yatay mod için saat + liste yan yana (iki sütunlu) bir yerleşim yok; liste daima saatin altına yerleştiriliyor.
  * [`TrayDesktop.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L91) & [`place()`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L255-L260): `index` (`AlphabetIndex`), `onMeasure` override etmeyen bir `View` olarak `WRAP_CONTENT` ile ekleniyor (ilk ölçümde `1080px` alıyor). `place()` içinde `index.layoutParams.height = indexHeight` in-place değiştiriliyor ama **`index.requestLayout()` çağrılmıyor**! Bu yüzden `index.height` `1080px` kalıp `index.y = listTop` (`~500px`) ile aşağı itiliyor (`F..#` ekran dışına taşıyor). Başka bir layout geçişi tetiklendiğinde ise `index.height` `~340px` oluyor ve [`itemHeight`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L661) `340 / 27 ≈ 12.5px` değerine düşerek `RAIL_LETTER_SP` (`14sp`) metinleri birbirinin üstüne bindiriyor.

---

### 6. [ORTA] Tray Düzeninde Masaüstü 2–5'te Boş Alana Basılı Tutarak Ayarlar Açılamıyor
* **Gözlem:** Masaüstü 3'te boş duvar kağıdına `1800ms` basılı tutulduğunda `"Open the settings?"` diyaloğu açılmıyor (Masaüstü 1'de açılıyor).
* **Kök Neden:**
  * [`MainActivity.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/MainActivity.kt#L499): Tray modunda `desktop.visibility = View.GONE` yapıldığı için `setUpDesktopHold()` devre dışı kalıyor.
  * [`TrayDesktop.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L215-L236) & [`followDesktops`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L267-L276): Boş alan long-press dinleyicisi `tray.backdrop` üzerinde ve `followDesktops(page)` bunu `-page * backdrop.width` kadar sola kaydırıyor. Dolayısıyla Masaüstü 2–5'te ekranda uzun basmayı yakalayan hiçbir view kalmıyor.

---

### 7. [ORTA] Display Cutout (Çentik/Kamera Deliği) ve Yatay Kenar Inset'leri Göz Ardı Ediliyor
* **Gözlem & İlgili Kodlar:**
  * [`MainActivity.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/MainActivity.kt#L87-L103) & [`AppWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindows.kt#L289-L293): `MainActivity` yalnızca `systemBars.top` değerini `windows.keepInArea(...)` ile iletiyor; `WindowInsets.Type.displayCutout()` ve `systemBars.left / right` değerleri `AppWindows`'a hiç verilmiyor. Yatayda `112px` sol cutout varken `retile()` pencereleri `x = gap (18px)` konumundan başlatıyor (sol üstteki sistem/FPS/cutout bölgesinin altına giriyor).
  * [`SearchActivity.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/SearchActivity.kt#L65-L68) & [`placePanel`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/SearchActivity.kt#L100-L111): `bars` hesabı `displayCutout()` içermiyor; ayrıca `panel.x` clamp edilirken `bars.left` ve `bars.right` yerine yalnızca `margin` kullanılıyor (`ROTATION_270` veya yan nav bar durumunda panel çentik/bar altına girebilir).
  * [`TutorialOverlay.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TutorialOverlay.kt#L97-L114): `bars.left` ve `bars.right` hiç saklanmıyor; `card` `[48, 160][2364, 638]` konumuna yerleştirilerek `0..112px` sol cutout alanının içine giriyor.

---

### 8. [YÜKSEK] `TutorialOverlay`: Yatayda 5.5:1 Basık Örnek Pencere ve Döndürmede Ekran Dışına Düşme
* **Gözlem:**
  * Yatay modda ilk açılış rehberindeki `"Sample app"` (`DemoWindow`) **`1326 x 238` px** (`5.5:1` basık bir şerit) boyutunda çizildi (`[543, 716][1869, 954]`, içerik yüksekliği sadece `164px`).
  * Dikeyde açık olan rehber yataya döndürüldüğünde `DemoWindow` dikey `y` koordinatında (`~1000px`) kalarak **%90 oranında ekranın altından taştı** (sadece başlık çubuğu göründü).
* **Kök Neden:**
  * [`TutorialOverlay.kt` (`DemoWindow.reset`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TutorialOverlay.kt#L378-L391): `windowWidth = (width * 0.55f)` (`1326px`) hesaplanıyor ve `windowHeight` en fazla `area * 0.8f` ile sınırlandığı için pencere genişlikten dolayı orantısız basıklaşıyor.
  * [`TutorialOverlay.kt` (`grip`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TutorialOverlay.kt#L578-L579): Minimum yükseklik `titleHeight + minWidth / 2` (`282px`) başlangıç yüksekliğinden (`238px`) büyük olduğu için 2. adımda yeniden boyutlandırmaya başlandığında pencere küçülmek yerine büyüyüp zıplayabilir.
  * [`TutorialOverlay.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TutorialOverlay.kt#L104-L108): Layout değişiminde `demo.placed` zaten `true` olduğu için döndürme sonrası `DemoWindow` yeniden konumlandırılmıyor ve alt sınır (`areaBottom()`) kontrol edilmiyor.

---

### 9. [YÜKSEK] `FloatingWindows`: Tam Ekran Uygulama Üstündeki Yüzen Pencereler Döndürmeyi Takip Etmiyor
* **Gözlem:**
  * Tam ekran `Chrome` açıkken yatayda `FloatingAppsButton` üzerinden açılan `FloatingWindow` (`AIDA64`, `x = 1134`) cihaz dikeye döndürüldüğünde `x = 1134` konumunda kaldı ve **`1080px` genişlikteki dikey ekranın tamamen sağ dışına çıktı** (yalnızca sol kenarından ~1mm göründü, `✕` düğmesine ulaşılamadı).
  * Yatayda açıldığında da alt kenar `y = 1080`'e (sistem gesture çizgisi arkasına) kadar uzandı.
* **Kök Neden:**
  * [`FloatingWindows.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/FloatingWindows.kt#L13-L92): Bir singleton `object` olup hiçbir `Configuration` / display değişimini dinlemiyor, pencereler için `scaleForRotation()` veya ekran sınırlarına clamp işlemi yapmıyor, alt navigation/gesture inset'ini de hesaba katmıyor.

---

## Kod İncelemesinden Tespit Edilen Ek Riskler (Test Durdurulduğu İçin Canlı Doğrulanmadı)

* **`DrawerActivity` yatay düzeni** ([`DrawerActivity.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/DrawerActivity.kt#L32-L36), [`activity_drawer.xml`](file:///home/jeo/development/phonelauncher/app/src/main/res/layout/activity_drawer.xml#L22-L30)): `GridView` sabit `android:numColumns="4"` kullanıyor (yatayda 6–8 sütun yerine çok geniş boşluklar bırakır) ve `displayCutout()` inset'ini eklemiyor.
* **Yatayda Masaüstü Kaydırma Bölgesi Çakışması** ([`MainActivity.kt` (`canStartPan`)](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/MainActivity.kt#L323-L332)): `PAN_ZONE_HEIGHT_DP = 200` (`600px`, yani `1080px` yüksekliğin %55'i!) ve `PAN_ZONE_WIDTH_RATIO = 0.6f`. `canStartPan()` yalnızca `!windows.isOnTitleBar(...)` kontrol ediyor, `!windows.isOnContent(...)` kontrol etmiyor. Yatayda pencere içindeki yatay kaydırmalar (carousel, video seekbar vb.) yanlışlıkla masaüstü değiştirmeyi tetikleyebilir.
* **Dokunmatik/Dock Klavye Açıkken `TrayDesktop` Liste Çökmesi** ([`TrayDesktop.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/TrayDesktop.kt#L250-L260)): Testte Gboard yüzen (floating) moddaydı (`keyboardHeight == 0`); normal tam genişlikli (docked) klavye açıldığında `listBottom = height - keyboardHeight - dp(8)` değeri `listTop` (~`500px`) değerinin altına düşer ve `AppListPanel` `PANEL_HEADER_DP` (`52dp`) yüksekliğine sıkışıp uygulama listesini tamamen gizler.
* **`SettingsActivity` & `LicensesActivity` Döndürmede Yeniden Başlama** ([`AndroidManifest.xml`](file:///home/jeo/development/phonelauncher/app/src/main/AndroidManifest.xml#L75-L94)): `configChanges` tanımlı değil; yatay/dikey geçişte `Activity` yeniden oluşturulur ve açık seçim diyalogları kapanır.
* **Yatayda `ScrollingWallpaper` Oranı** ([`ScrollingWallpaper.kt`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/ScrollingWallpaper.kt#L94-L101)): `scale = max(width / image.width, height / image.height)` yatayda (`width = 2412`) 16:9 bir görseli dikeyde aşırı yakınlaştırır (`2412 / image.width`), masaüstleri arası kaydırma payını (`extra`) sıfıra indirir.
* **Pencere İçi Döndürme (`toggleLandscape`) ve Üçlü Dokunuş:** Test durdurulduğu için başlık çubuğu üçlü dokunuşla `WindowAction.ROTATE` (`toggleLandscape()`) davranışı canlı doğrulanamadı; [`AppWindow.toggleLandscape()`](file:///home/jeo/development/phonelauncher/app/src/main/kotlin/dev/jeo/pencere/AppWindow.kt#L236-L251) da `bottomLimit` yerine `areaHeight()` kullandığı için masaüstü seçicinin altına taşma riski taşır.

---

## Cihazın Test Sonu Durumu (Bilgi Notu)
Test kullanıcı isteğiyle durdurulduğu için cihaz ayarlarına tekrar müdahale edilmedi. Cihazda test sırasında değiştirilen ayarlar:
* `settings put system accelerometer_rotation 0` / `user_rotation 1` (sonrasında sistem `Settings` ekranı `user_rotation`'ı `0` yapmıştı; cihaz şu an `MainActivity` üzerinde Tutorial Step 1 ekranında olabilir).
* Launcher tercihlerinden `desktop_layout = CLASSIC` ve `tiling = false` test için değiştirilmişti.
