# Contributing

1. Create a branch from `main`.
2. Keep UI work compatible with Android 8 (API 26).
3. Add or update tests for non-UI logic.
4. Open a pull request. GitHub Actions must pass unit tests, Android lint, APK and AAB builds.
5. Never commit tokens, keystores, `local.properties`, APKs, or build directories.

Release builds are created only by `.github/workflows/release.yml` from a `v*` tag and require repository signing secrets.
