# Overlay Translator 5

Android-оверлей для цепочки **область экрана → OCR → перевод → озвучка**. Проект ориентирован на комиксы, мангу, визуальные новеллы, игры и любой текст, который нельзя удобно выделить.

> **Приватность:** локальные OCR и перевод не отправляют изображение в сеть. При выборе Zen Vision/OpenRouter выбранный фрагмент экрана передаётся соответствующему облачному сервису. Не публикуйте API-ключи в issues или коммитах.

## Возможности

- Захват экрана через официальный Android `MediaProjection`, без root.
- Выбор произвольной области, широкой полосы или почти всего экрана.
- Разовый и live-режимы; неизменившиеся кадры пропускаются perceptual hash.
- OCR: ML Kit, Tesseract EN/RU, локальный fallback, экспериментальный ONNX detector, Zen Vision и OpenRouter Vision.
- Перевод EN → RU: офлайн ML Kit NMT, локальный словарь, Zen, OpenRouter и сетевые fallback-движки.
- Озвучка системными русскими TTS-голосами (Google TTS, RHVoice и совместимые движки).
- Копирование результата и повторное открытие скрытого окна.
- Android 8+ (`minSdk 26`), ARM64.

## Что улучшено в 5.0

- Live-режим теперь выполняет полный OCR **и перевод**, а не только распознавание.
- Исправлен выбор `Local NMT`: раньше пункт интерфейса фактически включал Zen.
- Tesseract больше не переинициализируется перед каждым кадром.
- Добавлен difference hash вместо нестабильного сравнения переполненных `Int`-хешей.
- Убрано накопление bitmap-кропов и параллельные дублирующиеся переводы.
- Ошибки OCR/перевода больше не проглатываются молча и отображаются пользователю.
- Удалены ненужные разрешения микрофона/data sync, запрещены cleartext HTTP и backup данных приложения.
- Release больше не подписывается публичным debug-ключом.
- CI, lint, тесты, APK/AAB и подписанные релизы полностью выполняются GitHub Actions.

## Быстрый старт

1. Установите APK из **Actions → Android CI → Artifacts** или из GitHub Releases.
2. Выдайте разрешение «Поверх других приложений».
3. Разрешите захват экрана.
4. Выберите OCR, перевод и голос.
5. Запустите оверлей, нажмите **Область**, затем **Скан** или включите **Live**.

Для полностью локальной работы выберите:

- OCR: `Local stack`, `ML Kit` или `Tesseract`;
- перевод: `Local NMT` (языковая модель загрузится при первом использовании);
- голос: установленный системный TTS.

## Сборка — только GitHub Actions

Локальная сборка не требуется. Workflow `.github/workflows/ci.yml` на каждом push/PR выполняет:

1. проверку Gradle Wrapper;
2. JVM unit tests;
3. Android Lint;
4. сборку debug APK и AAB;
5. загрузку пакетов и отчётов в Artifacts.

Откройте вкладку **Actions**, выберите успешный запуск **Android CI** и скачайте `overlay-translator-debug-*`.

## Подписанный релиз

В `Settings → Secrets and variables → Actions` добавьте:

- `SIGNING_KEYSTORE_BASE64` — keystore, закодированный Base64;
- `SIGNING_STORE_PASSWORD`;
- `SIGNING_KEY_ALIAS`;
- `SIGNING_KEY_PASSWORD`.

После push тега вида `v5.0.0` workflow `.github/workflows/release.yml` прогонит тесты/lint, соберёт и проверит подпись APK, создаст AAB и опубликует GitHub Release. Релизы на обычный push не создаются.

## Движки и ограничения

| Задача | Локально | Облако |
|---|---|---|
| OCR | ML Kit, Tesseract, local stack, ONNX | Zen Vision, OpenRouter Vision |
| Перевод | ML Kit NMT, словарь | Zen, OpenRouter, Google fallback |
| Голос | Android TTS / RHVoice | — |

- Текущая качественно поддерживаемая языковая пара перевода — EN → RU.
- Наличие и названия бесплатных облачных моделей могут меняться у провайдера.
- Системные голоса зависят от TTS-движка, установленного на устройстве.
- ONNX detector экспериментальный; для обычного текста сначала попробуйте ML Kit/local stack.

## Архитектура

- `OverlayService` — foreground service, захват, live-loop и overlay UI.
- `OcrRouter` — маршрутизация OCR и fallback-цепочки.
- `Translator` / `LocalNmt` / `LlmClient` — перевод.
- `VoiceHelper` — выбор и настройка TTS.
- `ImagePrep` / `PerceptualHash` — подготовка изображения и дедупликация кадров.
- `EnginePrefs` — пользовательские настройки движков.

## Безопасность и вклад в проект

См. [SECURITY.md](SECURITY.md) и [CONTRIBUTING.md](CONTRIBUTING.md). Никогда не добавляйте токены, keystore и пароли в репозиторий.
