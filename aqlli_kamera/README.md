# Chorraha Ko‘zi — piyoda va transport oqimini avtomatik sanash

Dissertatsiya tajribasi uchun "aqlli kamera": chorrahada **bir vaqtning o'zida**
- piyodalarni **soni** va **yosh guruhlari** (bola / kattalar / keksa), qo'shimcha ravishda **jinsi** bo'yicha,
- transportni **soni** va **turi** (yengil, avtobus, yuk, mototsikl, velosiped) bo'yicha sanaydi,
- natijani **har 15 daqiqada** jadvalga yozadi (keltirilgan birlik va soatlik jadallik bilan).

Ikki qism bor:

| Qism | Qayerda ishlaydi | Nima uchun |
|---|---|---|
| `android/` — **Chorraha Ko‘zi** ilovasi | Telefonda (internetsiz) | Dalada jonli sanash |
| `kamera/` — Python dastur | Kompyuterda | Yozib olingan videoni qayta tahlil qilish, aniqroq (kattaroq) modellar bilan |

Ikkalasi ham bir xil mantiqda ishlaydi: YOLO11 (COCO) obyektlarni topadi → kuzatuvchi har bir obyektga raqam beradi →
obyektning "oyog'i" (qutining pastki o'rtasi) chiziqni kesib o'tganda bir marta sanaladi.

## 1. Telefon ilovasi (Android)

**Tavsiya etilgan telefon:** Samsung Galaxy A17 (6 GB RAM) yoki shunga o'xshash. Kutilayotgan tezlik: 8–15 kadr/s.

### O'rnatish
APK GitHub Actions orqali avtomatik yig'iladi: repozitoriyning **Releases → "Chorraha Ko‘zi (oxirgi versiya)"**
sahifasidan `Chorraha-Kozi.apk` ni telefonda yuklab oling va o'rnating ("Noma'lum manbalar"ga ruxsat bering).

### Dalada ishlatish
1. Telefonni **shtativga** o'rnating, kamera chorrahaga **yuqoriroqdan va yonroqdan** qarasin (piyodalar bir-birini to'smasin).
2. Ilovada chorraha nomini yozing va **sanash rejimini** tanlang: piyoda va transport birga, faqat piyodalar yoki faqat transport. **Kamerani ochish**.
3. **1-chiziq** ni bosing va barmoq bilan **piyodalar o'tish joyini ko'ndalangiga kesuvchi** chiziq torting
   (piyoda yo'lni kesib o'tayotganda shu chiziqdan o'tishi kerak).
4. **2-chiziq** bilan **transport yo'lini ko'ndalangiga kesuvchi** chiziq torting.
   Chiziqlar shu chorraha nomi uchun eslab qolinadi.
5. **Boshlash**. Uzoq kuzatuvda **Ekranni xiralashtirish** ni bosing (ikki marta bosib qaytariladi).
   Quvvat tugmasini bosmang: ekran o'chsa kamera to'xtaydi.
6. **To'xtatish** → bosh sahifada kuzatuv paydo bo'ladi: **Ulashish** (Telegram va h.k.) yoki **Yuklamalarga** (Download/PCLOS_Kamera).

### Natija fayllari (ZIP ichida)
- `intervallar.csv` — har 15 daqiqa va har chiziq bo'yicha:
  `piyoda_bola, piyoda_kattalar, piyoda_keksa, piyoda_noaniq, piyoda_jami, erkak, ayol, jinsi_noaniq,
  bola_erkak, bola_ayol, kattalar_erkak, kattalar_ayol, keksa_erkak, keksa_ayol, bola_% …,
  yengil, avtobus, yuk, mototsikl, velosiped, transport_jami, keltirilgan_birlik, soatlik_jadallik_avt, soatlik_jadallik_kb, …_%`
- `hodisalar.csv` — har bir sanalgan obyekt: vaqt, trek raqami, toifa, jins, ishonch, chiziq, yo'nalish (A→B / B→A).
- `malumot.txt` — chorraha, boshlanish/tugash vaqti, sozlamalar, chiziq koordinatalari, telefon modeli.
- Video (ixtiyoriy): `Movies/PCLOS_Kamera/`, har soat alohida fayl (720p da ~2 GB/soat).

Fayllar Excel'da to'g'ridan-to'g'ri ochiladi (UTF-8).

### Batareya va qizish
Kamera va neyron tarmoq birga ishlaganda batareya taxminan 3–5 soatga yetadi. 10 soatlik kuzatuv uchun **powerbank ulang**.
Ekranda batareya foizi va harorat ko'rinadi; 45 °C dan oshsa ogohlantiradi. Telefonni soyaga qo'ying,
kerak bo'lsa **5 kadr/s (tejamkor)** rejimini tanlang.

### Yosh guruhi va jins qanday aniqlanadi
Har bir piyoda uchun bir necha kadrdan (5 tagacha) kesma olinib, **PaddleClas PULC person_attribute** modeliga beriladi.
Bu model **PA-100K** ma'lumotlar to'plamida (100 000 piyoda tasviri) o'qitilgan va `AgeLess18 / Age18-60 / AgeOver60`,
`Female` atributlarini beradi. Ehtimolliklar o'rtachalanadi; ishonch `hodisalar.csv` da saqlanadi.
Piyoda kesmasi **60 pikseldan past** bo'lsa (kamera juda uzoqda) yosh aniqlanmaydi va `noaniq` deb yoziladi.

**Muhim (maqola uchun):** yosh bo'yicha aniqlik kamera balandligi, masofa va yorug'likka bog'liq.
Shuning uchun 2–3 chorrahada 15–30 daqiqalik videoni **qo'lda sanab**, ilova natijasi bilan solishtiring
(aniqlik, F1, MAPE). Bu validatsiya maqolaning metodika bo'limiga kiradi.

Velosiped/mototsikl haydovchisi piyoda sifatida sanalmaydi (odam qutisi ikki g'ildirakli transport ustida bo'lsa).

### Kodni yig'ish
```
cd aqlli_kamera/android
./gradlew testDebugUnitTest assembleDebug
```
Yosh modeli (`app/src/main/assets/pa100k_attr.onnx`) GitHub Actions ichida yuklab olinib ONNX ga o'tkaziladi
(`.github/workflows/kamera-apk.yml`). Model bo'lmasa ilova ishlayveradi, faqat yosh guruhi `noaniq` bo'ladi.

## 2. Kompyuterda video tahlili (Python)

```
cd aqlli_kamera
pip install -r requirements.txt
cp sozlama.example.yaml sozlama.yaml      # chiziqlar va video yo'lini o'zgartiring
python -m kamera.run --config sozlama.yaml --source video.mp4 --start-time "2026-12-01 08:00:00"
```
Natija: `natijalar/` ichida Excel (`hodisalar`, `piyodalar`, `transport`, `umumiy` varaqlari), CSV va qutilar chizilgan video.
Bu yerda yosh guruhi CLIP (zero-shot) yoki kattaroq YOLO modeli (`yolo11s.pt`, `yolo11m.pt`) bilan aniqroq tahlil qilish mumkin.

## Keltirish koeffitsientlari
Standart: yengil 1.0, mototsikl 0.5, velosiped 0.3, avtobus 2.5, yuk 2.0. O'zbekiston me'yoriga (ShNQ) qarab tekshiring
va kerak bo'lsa `SessionLog.DEFAULT_PCU` (ilova) yoki `pcu:` (Python sozlamasi) ni o'zgartiring.
