#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
javac -encoding UTF-8 -cp "$AIV_JSON_JAR" -d "$classes" app/src/main/java/fr/erick/journallocal/{TrackerMatcher,EndpointContextRules}.java tests/EndpointContextRulesTest.java
java -cp "$classes:$AIV_JSON_JAR" fr.erick.journallocal.EndpointContextRulesTest app/src/main/assets/catalogs
