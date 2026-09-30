#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${AIV_JSON_JAR:?Specify org.json:json:20240303 jar}"
: "${AIV_ANDROID_JAR:?Specify Android platform 35 android.jar}"
test -d aiv-v19/build/classes
TEST_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEST_CLASSES"' EXIT
TEST_CP="$AIV_JSON_JAR:$AIV_ANDROID_JAR:aiv-v19/build/classes"
javac -cp "$TEST_CP" -d "$TEST_CLASSES" aiv-v19/app/src/main/java/fr/erick/journallocal/DefenseStore.java aiv-v19/tests/DefenseSnapshotV23Test.java
java -cp "$TEST_CLASSES:$TEST_CP" fr.erick.journallocal.DefenseSnapshotV23Test
