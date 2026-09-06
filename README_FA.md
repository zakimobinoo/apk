# Boos 2.0 — Magnetic Scanner Phone

نسخه کامل و آماده برای بیلد.

## مشخصات فنی
- Android Gradle Plugin: 8.7.3
- Gradle: 8.9
- Java: 17
- compileSdk / targetSdk: 35
- minSdk: 23

## ساخت APK با GitHub Actions (پیشنهادی)

1. این پوشه را روی GitHub آپلود کنید (یا Push کنید).
2. به تب **Actions** بروید.
3. روی workflow به نام **Build APK** کلیک کنید.
4. دکمه **Run workflow** را بزنید.
5. بعد از اتمام بیلد، فایل APK را از بخش Artifacts دانلود کنید.

## ساخت دستی (اگر Android Studio یا SDK دارید)
```bash
./gradlew assembleDebug
```
فایل APK در مسیر زیر ساخته می‌شود:
`app/build/outputs/apk/debug/app-debug.apk`

## نکات
- اپ برای اتصال بلوتوث به دستگاه اسکنر مغناطیسی طراحی شده است.
- بعد از اسکن کامل ۱۲۰ نقطه، می‌توانید فایل V3D ذخیره کنید.
