# Overlay Translator EN → RU

Оверлей для **английских комиксов / манги / манхвы / маньхуа** и **русских** изданий.

## Режимы

- **English → русский** — OCR латиницы, перевод (локальный словарь ~400 фраз + MyMemory), озвучка русским голосом.
- **Русский** — OCR кириллицы, без перевода, озвучка.

## Голоса

Женский / мужской / подросток / другие — из **установленных** TTS (Google TTS русский, RHVoice и т.д.). Само приложение не тащит нейро-голоса (это сотни мегабайт).

## Модели в APK

- `vision_enhance.onnx` — самодельная свёртка (очистка кропа).
- `ocr_crnn.onnx` — самодельная CRNN EN+RU (вспомогательная).
- Tesseract `eng` + `rus` (tessdata_fast) — основной локальный OCR.
- Словарь `en_ru_dict.tsv` + онлайн MyMemory.

Целевой размер APK: **50–150 МБ** (ONNX Runtime + tess + модели).

## Сборка

GitHub Actions: `.github/workflows/build.yml` → artifact `overlay-translator-apk`.
