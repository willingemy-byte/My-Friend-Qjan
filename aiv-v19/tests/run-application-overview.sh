#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
FIXTURE_ROOT="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_ROOT"' EXIT
JSON_JAR="${AIV_TEST_JSON_JAR:?Set AIV_TEST_JSON_JAR to the pinned org.json 20240303 jar}"
python3 - "$JSON_JAR" <<'PY'
import hashlib,sys
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
PY
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$FIXTURE_ROOT/classes" \
    aiv-v19/app/src/main/java/fr/erick/journallocal/ApplicationOverview.java aiv-v19/tests/ApplicationOverviewTest.java
java -cp "$FIXTURE_ROOT/classes:$JSON_JAR" fr.erick.journallocal.ApplicationOverviewTest
