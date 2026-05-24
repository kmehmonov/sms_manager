# GitHub'ga yuklash va APK yaratish yo'riqnomasi

## 1. TALABLAR

Quyidagi dasturlar o'rnatilgan bo'lishi kerak:
- **Git** — https://git-scm.com/download/win
- **Android Studio** — https://developer.android.com/studio
- **Java JDK 17** — Android Studio bilan birga keladi

---

## 2. GRADLE WRAPPER JAR YARATISH

**Muhim:** `gradle-wrapper.jar` fayli git'da saqlanmaydi, uni Android Studio orqali yaratish kerak.

### Android Studio orqali:
1. Android Studio'ni oching
2. **File → Open** → `SMS_MANAGER` papkasini tanlang
3. Studio avtomatik ravishda gradle wrapper'ni yuklab oladi
4. Pastda **"Gradle sync"** tugagani kutiladi (birinchi marta 3-10 daqiqa)

### Yoki buyruq qatori orqali (agar Gradle o'rnatilgan bo'lsa):
```bash
cd D:\PYTHON\SMS_MANAGER
gradle wrapper --gradle-version 8.7
```

---

## 3. GITHUB REPOGA YUKLASH

### Birinchi marta (yangi repo):

```bash
# 1. Papkaga o'ting
cd D:\PYTHON\SMS_MANAGER

# 2. Git'ni ishga tushirish
git init

# 3. Barcha fayllarni qo'shish
git add .

# 4. Birinchi commit
git commit -m "feat: SMS Manager Android ilovasi yaratildi"

# 5. Remote repo'ni ulash
git remote add origin https://github.com/kmehmonov/sms_manager.git

# 6. GitHub'ga yuklash
git push -u origin main
```

### Keyingi o'zgarishlar uchun:
```bash
git add .
git commit -m "o'zgarish tavsifi"
git push
```

---

## 4. GITHUB ACTIONS — AVTOMATIK BUILD

Yuklashdan so'ng GitHub avtomatik ravishda APK yaratadi!

1. Repoga o'ting: `https://github.com/kmehmonov/sms_manager`
2. **Actions** bo'limini oching
3. **"Android CI — APK Build"** workflow'ini kuting (5-10 daqiqa)
4. Build tugagach → **Artifacts** → **"sms-manager-debug-apk"** ni yuklab oling

---

## 5. ANDROID STUDIO'DA OCHISH VA BUILD QILISH

### Qadamlar:
1. **Android Studio** → **File → Open**
2. `D:\PYTHON\SMS_MANAGER` papkasini tanlang
3. **Gradle Sync** tugashini kuting
4. Yuqori menyu: **Build → Build Bundle(s)/APK(s) → Build APK(s)**
5. APK manzili: `app\build\outputs\apk\debug\app-debug.apk`

### Agar xatolar bo'lsa:
- **"Gradle sync failed"** → File → Invalidate Caches → Restart
- **"SDK not found"** → SDK Manager'dan Android 35 ni yuklab oling
- **"JDK not found"** → File → Project Structure → SDK Location → JDK 17

---

## 6. TELEFONNI TAYYORLASH (UNKNOWN SOURCES)

### Android 8.0+ (Oreo va undan yangi):

APK o'rnatish imkoniyati ilovalar bo'yicha boshqariladi:

1. **Sozlamalar (Settings)** ni oching
2. **Ilova va bildirishnomalar (Apps & Notifications)** → **Maxsus kirish (Special app access)**
3. **Noma'lum ilovalar o'rnatish (Install unknown apps)**
4. **Chrome** yoki **Fayl menejeri** ni tanlang
5. **Bu manbadan ruxsat bering** ni yoqing

### Samsung (One UI):
1. **Sozlamalar → Bexavfsizlik (Biometrics and security)**
2. **Noma'lum manbalar** yoqing

### Xiaomi/MIUI:
1. **Sozlamalar → Maxsus ruxsatlar (Special Permissions)**
2. **Noma'lum ilovalar o'rnatish**

---

## 7. APK O'RNATISH

### Usul 1: USB orqali (tavsiya etiladi):
```bash
# adb o'rnatilgan bo'lishi kerak (Android Studio bilan keladi)
adb install app\build\outputs\apk\debug\app-debug.apk
```

### Usul 2: Fayl orqali:
1. APK faylini telefoningizga ko'chiring (USB yoki Telegram/WhatsApp orqali)
2. Telefonda fayl menejeri orqali APK'ni toping
3. Ustiga bosing → O'rnatish

### Usul 3: USB Debug (avtomatik o'rnatish):
1. Telefonni USB orqali ulang
2. Android Studio'da ▶️ (Run) tugmasini bosing
3. Telefonni tanlang → OK

---

## 8. ILOVA ISHLATISH YO'RIQNOMASI

### CSV fayl tayyorlash:
```csv
telefon,xabar
+998901234567,Salom Ahmadjon! Bu maxsus taklif sizga.
+998771112233,Hurmatli mijoz, buyurtmangiz tayyor.
+998931234567,"Salom, bu vergul bilan xabar"
```

**Muhim qoidalar:**
- Fayl `.csv` kengaymali bo'lishi kerak
- UTF-8 kodlash (Notepad++ yoki Google Sheets'da saqlang)
- Raqam `+998` bilan boshlansa yaxshi, lekin `998` yoki `0` bilan ham ishlaydi
- Vergul ichida xabar bo'lsa, qo'shtirnoq ichiga oling

### Ilovada:
1. **"CSV faylni tanlash"** → Faylni toping va tanlang
2. **"Birinchi qatorni o'tkazib yubor"** ✓ (agar sarlavha bo'lsa)
3. **"Yuborishni boshlash"** → Tasdiqlang
4. Log'da har bir SMS holati ko'rinadi
5. Kerak bo'lsa **"To'xtatish"** tugmasi bilan to'xtatiladi

---

## 9. XATO YECHIMLAR

| Xato | Yechim |
|------|--------|
| SMS ruxsati berilmadi | Sozlamalar → Ilovalar → SMS Manager → Ruxsatlar → SMS |
| "Noto'g'ri raqam" | Raqamni `+998XXXXXXXXX` formatida yozing |
| CSV o'qilmaydi | Faylni UTF-8 bilan saqlang (Excel'da: Saqlash → CSV UTF-8) |
| SMS yetib bormadi | Tarmoq aloqasini tekshiring, sim karta balansi |
| Build xatosi | Android Studio'da File → Invalidate Caches → Restart |
