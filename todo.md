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

- [x] After every successful GitHub Actions release build, attach the versioned Markdown quality report and upload the APK candidate to GoFile with commit, run URL, architecture, SHA-256, and known limitations.
- [ ] Keep the GoFile upload as a test candidate until real-device validation confirms the overlay frame, local OCR, TSX controls, and Russian TTS.

## White-screen regression in hybrid APK

- [ ] Reproduce the blank white WebView on the device using candidate `6a574db`.
- [x] Root cause: the packaged `tsx/index.html` (Vite output) used `<script type="module" crossorigin>`, and ES modules are CORS-blocked from `file://` in the WebView, so the app shell never mounts. Tag/release builds additionally never built the TSX assets at all because `app/src/main/assets/tsx/` is gitignored. The Gradle `preBuild` task now fails when the shell is missing or still uses ES modules.
- [x] Verify that the packaged `tsx/index.html`, JavaScript bundle, CSS, and WebView hash route are present and load from Android assets.
- [x] Fix: `tools/postbuild.mjs` rewrites the build to a classic `<script defer>` (no modules, no `crossorigin`) so the shell loads from `file:///android_asset/tsx/index.html`, and injects a watchdog that shows a visible error if React never mounts.
- [x] Add a visible native fallback/error state when the TSX page fails to load instead of leaving a blank screen (`tsxFallback` view with retry in `MainActivity`).
- [x] Add a startup regression check to the remote GitHub Actions build (classic script, no modules/crossorigin, assets present, watchdog injected) and upload a new APK candidate only after it passes.

## Floating voice control

- [x] Keep the requested overlay actions visible: choose/change frame, scan, copy, hide/stop, and status feedback.
- [x] Remove `Голос` from the result-card action row.
- [x] Add a separate floating voice button above the OCR result card; it must speak the current result, remain visible while text exists, and be disabled when there is no result.
- [x] User reverted to the earlier native floating overlay: `OverlayService` now shows the draggable SAO-style trigger menu (`VerticalMenuView`) with `Рамка страницы`, `Скан рамки`, `Озвучить`, `Выбор голоса`, `Копировать`, `История`, `Стоп`. The TSX `#overlay` panel and the TSX/WebView bridge are no longer used by the overlay service.
- [ ] Verify on a real device that the floating trigger menu does not cover the selected frame or OCR text on portrait screens.

## Floating voice picker

- [x] Remove voice selection controls from the OCR result card and place them in a separate floating control group above it.
- [x] Add a floating `Голос` action and a separate `Выбрать голос` action; the picker must show available Russian system voices and the current selection.
- [x] Expose voice-list and voice-selection commands through the TSX/Android bridge, with a clear fallback when no Russian voice is installed.
- [x] With the native floating menu restored, voice selection is the native `VoiceDialog` (lists Russian system voices, preview, apply), with a fallback dialog when no Russian voice is installed.
- [ ] Verify the native floating controls remain usable without covering the selected OCR frame or result text.

## Edge TTS + voice roles

- [x] Add `EdgeTts` client (OkHttp WebSocket + SSML): voice-list fetch/cache and MP3 synthesis, same protocol as `edge-tts`, no API key. `INTERNET` permission added.
- [x] Add `VoiceRoles` model: narrator / male / female / child roles, each bound to a voice (`sys:<name>` system TTS or `edge:<ShortName>` Edge voice) or auto (resolved by marker or `VoiceAssistant` gender); persisted in `SharedPreferences`.
- [x] Add `VoiceRoleDialog` (native overlay): per-role voice assignment via unified catalog (auto + system Russian voices + Edge voices), gender cycling, reset, background Edge voice-list download.
- [x] `OverlayService` menu item `Роли & голоса 🎭`; `speakNow` routes role voice → Edge synthesis (background thread + MediaPlayer) or system TTS with role pitch/rate; automatic Edge fallback when no Russian system voice is installed.
- [ ] Real-device check: prefer system TTS by default (privacy), Edge only when a role explicitly binds an Edge voice or no Russian system voice exists; verify MP3 plays with foreground service running.

## Release-only delivery gate

- [x] Change CI from debug packaging to the signed `assembleRelease` variant and upload only `app-release.apk`.
- [x] Add a CI guard that fails if the selected artifact is `app-debug.apk`, an unsigned APK, or any non-release variant.
- [x] Record the release signing/build variant, commit, run URL, size, SHA-256, and known limitations in the Markdown report.
- [ ] Replace the GoFile link only after the signed release APK passes the GitHub Actions gate; do not present the previous debug APK as a release again.
