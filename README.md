# PCLOS Baholash — Android va iOS mobil ilovasi

Svetoforli va svetoforsiz chorrahalarda piyodalar uchun xizmat darajasini baholash ilovasi: infratuzilma (PCLOS, 17 indikator) va harakat sharoiti (HCM 2010 piyoda kutish vaqti).
Metodika: Ahmed T. va boshq. (2021), IJERPH 18(16):8813, https://doi.org/10.3390/ijerph18168813
va `PCLOS_avtomatik_hisoblash_shabloni.xlsx` shabloni.

Bitta kod bazasi (HTML/JS) Capacitor orqali Android va iOS’da bir xil ishlaydi. Internet kerak emas: barcha kutubxonalar (Chart.js, SheetJS, Leaflet) ichiga joylangan (faqat xarita fonini ko‘rsatish va manzil nomini aniqlash uchun internet kerak), ma’lumotlar telefonning o‘zida saqlanadi.

## Tuzilma
- `src/app.html` — ilovaning asl kodi (shu faylni tahrirlang)
- `tools/build.py` — `src/app.html` dan oflayn `www/index.html` yasaydi
- `www/` — Android/iOS ichiga joylanadigan tayyor ilova (brauzerda ham ochiladi)
- `android/`, `ios/` — tayyor nativ loyihalar
- `.github/workflows/android-apk.yml` — GitHub’da APK’ni avtomatik yig‘ish

## 1-yo‘l: APK’ni GitHub’da avtomatik olish (kompyuterga hech narsa o‘rnatmasdan)
1. GitHub’da yangi repozitoriy oching va shu papkani yuklang.
2. **Actions** bo‘limida “Android APK” ishga tushadi (5–8 daqiqa).
3. Tugagach, ichidan `PCLOS-Baholash-debug-apk` arxivini yuklab oling, `app-debug.apk` ni telefonga o‘tkazib o‘rnating
   (telefonda “Noma’lum manbalardan o‘rnatish”ga ruxsat bering).

## 2-yo‘l: Android Studio orqali
```
npm install
npx cap sync
npx cap open android      # Android Studio ochiladi → Run ▶ yoki Build > Build APK
```

## iOS (iPhone)
iOS ilovani yig‘ish uchun macOS va Xcode kerak (Apple talabi).
```
npm install
npx cap sync ios
npx cap open ios          # Xcode → telefonni ulang → Run ▶
```
O‘z iPhone’ingizga bepul Apple ID bilan o‘rnatish mumkin (7 kun amal qiladi). App Store’ga chiqarish: Apple Developer, yiliga $99.
Google Play’ga chiqarish: bir martalik $25.

## Kodni o‘zgartirgandan keyin
```
python3 tools/build.py src/app.html node_modules www/index.html   # avval: npm i chart.js@4.4.1 xlsx@0.18.5 leaflet@1.9.4
npx cap sync
```

## Imkoniyatlar
- Chorraha: viloyat/shahar, boshqaruv turi (svetoforli, chaqiruv tugmali, svetoforsiz, regulyovchi), shakli (X, T/Y, ko‘p tarmoqli, aylanma)
- HCM 2010: svetoforli — d=(C−g)²/2C va piyoda LOS balli; svetoforsiz — kritik interval, oqim, haydovchilar yo‘l berishi bo‘yicha kutish vaqti
- «Talab qilinmaydi» (N/A): indikator ΣCi dan chiqariladi; P05, P08, P10 chorraha turiga qarab avtomatik belgilanadi
- 17 indikator (P01–P17), har biri 0 / 0,5 / 1 ball; standart matni har indikator ostida
- Tezlik, kenglik, qatorlar soni kiritilsa P01, P03, P04 avtomatik baholanadi
- PCLOS = ΣCi·Pi, PCLOS% = ΣCi·Pi / ΣCi × 100, toifa A–F, tavsif va tavsiyalar (ta’siri bo‘yicha saralangan)
- Ci koeffitsiyentlari tahrirlanadi (o‘z so‘rovnomangizdagi o‘rtacha Likert)
- GPS koordinata, obyektlar ro‘yxati, tahrirlash
- Grafiklar: PCLOS% obyektlar bo‘yicha, toifalar taqsimoti, indikator hissasi, guruhlar radari, Pi issiqlik xaritasi,
  Ci muhimligi, IPA (muhimlik–holat), yaxshilash salohiyati, indikator–PCLOS% korrelyatsiyasi,
  17×17 korrelyatsiya matritsasi, ikki o‘zgaruvchi regressiyasi (r, R², tenglama)
- GIS xarita: har obyekt toifa rangidagi ikki piyoda belgisi (A to‘q yashil → F to‘q qizil), shartli belgilar, shimol strelkasi va masshtab, OpenStreetMap fonida
- Toifa chegaralari sozlanadi: maqola (80/60/40/20) yoki 85/70/55/40/25
- GIS eksport: GeoJSON (QGIS/ArcGIS), KML (Google Earth, ranglari bilan), ZIP ichida QGIS uslubi (.qml) — GeoJSON va .qml bir papkada bo‘lsa QGIS ranglarni o‘zi qo‘llaydi
- Excel shablondan import, Excel/CSV eksport, JSON zaxira
- Til: o‘zbek, ingliz, rus; yorug‘/qorong‘i mavzu
