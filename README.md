# Overlay OCR Translator

Плавающий оверлей для Android: выделение области экрана, OCR (ML Kit), перевод (on-device ML Kit Translate) и озвучка (системный TTS) в live-режиме.

## Как пользоваться

1. Установите APK (debug) из GitHub Actions artifacts.
2. Откройте приложение → разрешите **оверлей** и **захват экрана**.
3. Выберите языки, включите Live + TTS.
4. **Запустить оверлей** → кнопка **Область** → нарисуйте прямоугольник на экране.
5. Текст распознаётся, переводится и произносится. Live повторяет скан ~раз в 1.2 с.

## Сборка локально

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26, target 34. Модели перевода качаются при первом использовании выбранной пары языков.

## Важно

Токен GitHub, отправленный в чат, считается скомпрометированным — отзовите его в Settings → Developer settings → Personal access tokens.
