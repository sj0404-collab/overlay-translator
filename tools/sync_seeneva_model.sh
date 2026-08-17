#!/usr/bin/env bash
set -euo pipefail

# Reproducibly fetch the GPL-3.0-or-later Seeneva model from the audited upstream revision.
REVISION="003f01423bb174f08cd31c033d7171c9e89dd099"
REPOSITORY="https://github.com/Seeneva/seeneva-reader-android.git"
TARGET="app/src/main/assets/models/yolo_seeneva.tflite"
SHA256="b35e493280cc6c0edc9b1fe9f32027b86c34f340dcc116848622833c465bd173"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git -C "$TMP" init -q
git -C "$TMP" remote add origin "$REPOSITORY"
git -C "$TMP" fetch -q --depth 1 origin "$REVISION"
git -C "$TMP" checkout -q FETCH_HEAD
install -Dm644 "$TMP/logic/src/main/assets/yolo_seeneva.tflite" "$TARGET"
printf '%s  %s\n' "$SHA256" "$TARGET" | sha256sum --check --status
printf 'Updated %s from Seeneva revision %s\n' "$TARGET" "$REVISION"
