# Third-party notices

## Seeneva speech-balloon detector

Overlay Translator includes `app/src/main/assets/models/yolo_seeneva.tflite` from
[Seeneva/seeneva-reader-android](https://github.com/Seeneva/seeneva-reader-android),
upstream commit `003f01423bb174f08cd31c033d7171c9e89dd099`.
Model SHA-256: `b35e493280cc6c0edc9b1fe9f32027b86c34f340dcc116848622833c465bd173`.

Seeneva and its model are copyright © Sergei Solodovnikov and contributors and are distributed
under **GNU GPL version 3 or later**. The full license is available in [LICENSE](LICENSE).

The Android integration in `SeenevaDetector.kt` is a new implementation which follows Seeneva's
published preprocessing and output format: aspect-preserving resize, top-left padding, wide-page
slicing, class filtering, coordinate restoration and non-maximum suppression.

## Other libraries

Library dependencies retain their respective licenses. Gradle coordinates are listed in
`app/build.gradle.kts`.
