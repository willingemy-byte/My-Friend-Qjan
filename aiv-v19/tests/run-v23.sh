#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
bash aiv-v19/tests/run-v22.sh
node aiv-v19/tests/defense-v23.cjs
TEST_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEST_CLASSES"' EXIT
javac -d "$TEST_CLASSES" aiv-v19/app/src/main/java/fr/erick/journallocal/DefenseRules.java aiv-v19/tests/DefenseV23Test.java
java -cp "$TEST_CLASSES" DefenseV23Test
