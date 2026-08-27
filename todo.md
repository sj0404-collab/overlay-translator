# Local screen-frame OCR overlay

- [x] Identify `sj0404-collab/overlay-translator` as the dedicated Android overlay source baseline.
- [x] Confirm the existing project already has Android overlay permission, `MediaProjection`, a foreground service, manual frame selection and Russian system TTS wiring.
- [x] Document the requirement that only a user-drawn screen frame is OCR input; no full-display fallback and no cloud OCR transfer.
- [x] Replace the native settings interface with a TSX user interface packaged in the APK shell.
- [x] Keep only the minimum native Android bridge required for draw-over-other-apps permission, MediaProjection capture, frame selection, local OCR invocation, and system Russian TTS.
- [x] Expose explicit TSX actions for Allow overlay, Allow screen capture, choose/change page frame, scan the frame, read aloud, copy, and stop the overlay.
- [x] Do not send captured screen pixels or OCR text to a network service; retain a local-only OCR route.
- [x] Repair the LiteRT build path with Kotlin 2.2.21 and LiteRT 2.1.0, verified by GitHub Actions run `33081224416`.
- [x] Create an APK-quality Markdown report for each candidate and build the APK only through GitHub Actions.
- [ ] Download the GitHub Actions candidate `6a574db` only on explicit user request and verify screen-frame OCR, TSX controls and Russian TTS on a real device.

## Permanent release delivery

- [ ] After every successful GitHub Actions release build, attach the versioned Markdown quality report and upload the APK candidate to GoFile with commit, run URL, architecture, SHA-256, and known limitations.
- [ ] Keep the GoFile upload as a test candidate until real-device validation confirms the overlay frame, local OCR, TSX controls, and Russian TTS.

## White-screen regression in hybrid APK

- [ ] Reproduce the blank white WebView on the device using candidate `6a574db`.
- [ ] Verify that the packaged `tsx/index.html`, JavaScript bundle, CSS, and WebView hash route are present and load from Android assets.
- [ ] Add a visible native fallback/error state when the TSX page fails to load instead of leaving a blank screen.
- [ ] Add a startup regression check to the remote GitHub Actions build and upload a new APK candidate only after it passes.
