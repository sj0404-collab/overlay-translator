# Local Screen-Frame OCR Overlay

## Product boundary

This APK is a standalone Android overlay. It does not modify, read data from, or automate the underlying reader. The user explicitly starts it over another app, grants the Android system permissions for drawing above apps and one screen-capture session, then draws one visible frame around the page area to scan.

> Only pixels inside the selected frame are passed to OCR. Status bars, navigation areas, the floating control menu, result window, and all content outside the frame are excluded from the OCR input.

## Local processing route

| Stage | Implementation | Network use after setup |
|---|---|---|
| Screen capture | Android `MediaProjection` foreground service | None |
| Frame selection | Touch-driven `RegionView` overlay | None |
| OCR | Bundled Cyrillic PP-OCR detector plus PP-OCRv3/v5 recognizers through LiteRT | None |
| Text cleanup | Conservative Cyrillic spacing and line-wrap handling; no free-form spell substitution | None |
| Speech | Device-provided Russian Android TTS engine | None |

The OCR model files are bundled into the APK by a reproducible Gradle task that runs only in GitHub Actions release/CI builds. This prevents a first-use model download and ensures the application can perform OCR locally after installation.

## Interaction flow

The app initially presents two deliberate actions: **Allow overlay** and **Allow screen capture**. It does not request either permission in the background. After pressing **Start overlay**, the app moves to the background and exposes a compact floating control. The user chooses **Frame**, drags a rectangle over the actual page content, and then presses **Scan**. The control hides before capture, the selected crop is OCR-processed, and an editable result card appears with **Speak**, **Copy**, **Scan again**, and **Change frame**.

The default selection is the device content area with top/bottom system insets removed. Manual framing remains the default OCR mode because it is the only reliable way to exclude reader chrome and neighbouring panels.

## Quality and safety rules

The application must report a blank or low-quality result rather than invent a Russian sentence. A visual line-break hyphen is joined only when the source crop has a true adjacent OCR line boundary. Candidate recognition compares PP-OCRv3 and PP-OCRv5, and it preserves whole-line PP-OCRv5 candidates when their spaces can be reconstructed only from a restricted, verified word list.

Every APK candidate has a Markdown release-quality report with its source commit, remote build link, known improvements, known failure modes, automated tests, and device-validation status. A successful build never by itself means that OCR quality is accepted.

## Build compatibility note

LiteRT 2.1.0 is compiled with Kotlin 2.2 metadata, so this project uses Kotlin 2.2.21 rather than the old Kotlin 1.9 baseline. The incompatibility and the need for a newer Kotlin compiler are documented by the LiteRT maintainers in [GitHub issue 4887][1].

## References

[1]: https://github.com/google-ai-edge/LiteRT/issues/4887 "LiteRT Kotlin-version compatibility discussion"
