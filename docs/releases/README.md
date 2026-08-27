# APK quality reports

Every Android APK candidate from this repository is accompanied by a Markdown report. It states the source revision, remote workflow, locally processed data boundary, implemented changes, known limitations, automated checks, and device-validation status. A successful workflow is build evidence, not a claim that OCR is accepted on real pages.

The CI workflow uploads `APK-QUALITY-REPORT-<commit>.md` together with each debug APK/AAB artifact. Signed release candidates use the same report source.
