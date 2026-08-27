# Local screen-frame OCR overlay

- [x] Identify `sj0404-collab/overlay-translator` as the dedicated Android overlay source baseline.
- [x] Confirm the existing project already has Android overlay permission, `MediaProjection`, a foreground service, manual frame selection and Russian system TTS wiring.
- [x] Document the requirement that only a user-drawn screen frame is OCR input; no full-display fallback and no cloud OCR transfer.
- [ ] Replace the native settings interface with a TSX user interface packaged in the APK shell.
- [ ] Keep only the minimum native Android bridge required for draw-over-other-apps permission, MediaProjection capture, frame selection, local OCR invocation, and system Russian TTS.
- [ ] Expose explicit TSX actions for Allow overlay, Allow screen capture, choose/change page frame, scan the frame, read aloud, copy, and hide the result.
- [ ] Do not send captured screen pixels or OCR text to a network service; retain a local-only OCR route.
- [ ] Repair the failed LiteRT build path or use a Kotlin-compatible local inference dependency before publishing any hybrid APK candidate.
- [ ] Create an APK-quality Markdown report for each candidate and build the APK only through GitHub Actions.
