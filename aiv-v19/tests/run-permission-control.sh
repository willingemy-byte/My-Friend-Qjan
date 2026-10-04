#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
TEST_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEST_CLASSES"' EXIT
javac -encoding UTF-8 -d "$TEST_CLASSES" \
    aiv-v19/app/src/main/java/fr/erick/journallocal/PermissionControlRules.java \
    aiv-v19/tests/PermissionControlRulesTest.java
java -cp "$TEST_CLASSES" fr.erick.journallocal.PermissionControlRulesTest
javac -encoding UTF-8 -d "$TEST_CLASSES" \
    aiv-v19/tests/shell-stubs/android/content/pm/PackageManager.java \
    aiv-v19/tests/shell-stubs/rikka/shizuku/Shizuku.java \
    aiv-v19/app/src/main/java/fr/erick/journallocal/{AivConfig,AccessPolicy,ControlShell}.java \
    aiv-v19/tests/ControlShellTest.java
java -cp "$TEST_CLASSES" fr.erick.journallocal.ControlShellTest
