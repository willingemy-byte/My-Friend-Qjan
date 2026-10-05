#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${AIV_JSON_JAR:?org.json jar required}"
: "${AIV_SQLITE_JAR:?sqlite-jdbc jar required}"
: "${AIV_SLF4J_JAR:?slf4j jar required}"
python3 aiv-v19/tests/permission-usage.py
python3 aiv-v19/tests/convergence-host.py
python3 aiv-v19/tests/accessibility-snapshots.py
bash aiv-v19/tests/run-endpoint-context.sh
python3 aiv-v19/tests/network-report.py
python3 aiv-v19/tests/journal-sqlite.py
python3 aiv-v19/tests/event-store-timestamps.py
python3 aiv-v19/tests/network-observation.py
python3 aiv-v19/tests/native-observation.py
bash aiv-v19/tests/run-permission-control.sh
bash aiv-v19/tests/run-permission-integration.sh
python3 aiv-v19/tests/test_access_policy.py
python3 aiv-v19/tests/test_config.py
python3 aiv-v19/tests/test_public_config.py
