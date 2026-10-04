#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
FIXTURE_ROOT="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_ROOT"' EXIT
JSON_JAR="${AIV_TEST_JSON_JAR:-$FIXTURE_ROOT/json.jar}"
if [[ ! -f "$JSON_JAR" ]]; then
    curl -fsSL 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -o "$JSON_JAR"
fi
python3 - "$JSON_JAR" <<'PY'
import hashlib,sys
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
PY
python3 aiv-v19/tests/permission-integration-stubs.py "$FIXTURE_ROOT/src"
mapfile -t FIXTURE_SOURCES < <(rg --files "$FIXTURE_ROOT/src" -g '*.java')
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$FIXTURE_ROOT/classes" "${FIXTURE_SOURCES[@]}" \
    aiv-v19/app/src/main/java/fr/erick/journallocal/{PermissionControl,PermissionControlRules,PermissionReviewRules,ControlCoordinator}.java \
    aiv-v19/tests/PermissionControlIntegrationTest.java
mkdir "$FIXTURE_ROOT/files"
java -Xmx512m -cp "$FIXTURE_ROOT/classes:$JSON_JAR" fr.erick.journallocal.PermissionControlIntegrationTest \
    "$FIXTURE_ROOT/files" aiv-v19/app/src/main/assets
