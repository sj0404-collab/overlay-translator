# APK quality report — local screen-frame overlay candidate

## Build scope

This is a separate Android overlay APK. It uses Android screen capture only after the user grants it, and processes only the manually selected frame of the underlying page. The control menu hides before capture. No screen crop is sent to a network OCR or vision service.

## Positive qualities

The APK embeds a pinned Cyrillic PP-OCR detector plus PP-OCRv3 and PP-OCRv5 recognizers. The model assets are fetched and SHA-256 verified only while GitHub Actions builds the APK, then copied from the installed APK to app-private storage for local runtime use. Russian Android TTS remains available through the device's selected system voice. The TSX overlay places `Голос` as a floating action above the OCR result and exposes a separate floating `Выбрать голос` picker populated from installed Russian system voices; voice selection is persisted locally.

The user must select a page frame before Scan. The app no longer silently scans the full display, so Android status bars, navigation bars, reader chrome and floating controls outside the frame do not enter OCR input.

## Known limitations

OCR quality on handwritten comic fonts remains subject to device validation. The application intentionally refuses to invent an arbitrary Russian sentence from low-quality input; it can still return an empty result when local recognition has no usable Cyrillic text. The initial candidate does not translate Korean or Japanese artwork, and it relies on an installed Russian TTS voice for speech.

## Required validation

Check that manual framing excludes content outside the page, that Scan recognizes only the selected page crop, that the floating `Голос` action speaks the current result, that `Выбрать голос` lists and switches installed Russian voices, and that changing the frame before a second scan updates the result.
 Record any missed or falsely merged Russian words with screenshots for the next report.
