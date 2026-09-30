#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
node aiv-v19/tests/exposure-v22.cjs
node aiv-v19/tests/automatic-v18.cjs
node aiv-v19/tests/snapshots-v19.cjs
node aiv-v19/tests/audit-rules.cjs
python aiv-v19/tests/segments-v19.py
python -m unittest discover -s reference-engine/tests -v
TEST_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEST_CLASSES"' EXIT
javac -d "$TEST_CLASSES" aiv-v19/app/src/main/java/fr/erick/journallocal/{TrackerMatcher,DexClasses}.java aiv-v19/tests/TrackerV22Test.java
APK=aiv-v19/build/AIV-0.6.22-unsigned.apk
if [[ -f "$APK" ]]; then
  java -cp "$TEST_CLASSES" TrackerV22Test "$APK"
else
  java -cp "$TEST_CLASSES" TrackerV22Test
fi
