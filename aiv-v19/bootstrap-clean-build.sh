#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLROOT="${AIV_TOOLROOT:-$ROOT/.toolchain}"
DL="$TOOLROOT/downloads"
SDK="$TOOLROOT/sdk"
SIGNING_DIR="${AIV_SIGNING_DIR:-$ROOT/.signing-private}"
BUILD_MODE="${AIV_BUILD_MODE:-signed}"
mkdir -p "$DL" "$SDK/platforms" "$SDK/build-tools" "$SDK/ndk" "$SIGNING_DIR"

need(){ command -v "$1" >/dev/null 2>&1 || { echo "Missing required host tool: $1" >&2; exit 2; }; }
for x in python3 java keytool unzip curl sha256sum stat; do need "$x"; done

fetch(){
  local name="$1" url="$2" sha="$3" bytes="$4"\n  local dst="$DL/$name"
  if [[ -f "$dst" ]]; then
    local got
    got="$(sha256sum "$dst" | awk '{print $1}')"
    if [[ "$got" == "$sha" ]]; then echo "[cache] $name"; return; fi
    rm -f "$dst"
  fi
  echo "[download] $name"
  curl -fL --retry 5 --retry-delay 2 --connect-timeout 30 -o "$dst.part" "$url"
  mv "$dst.part" "$dst"
  [[ "$(stat -c %s "$dst")" == "$bytes" ]] || { echo "Size mismatch: $name" >&2; exit 3; }
  echo "$sha  $dst" | sha256sum -c -
}

fetch platform-35_r02.zip \
  https://dl.google.com/android/repository/platform-35_r02.zip \
  0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0 64273788
fetch build-tools_r35_linux.zip \
  https://dl.google.com/android/repository/build-tools_r35_linux.zip \
  bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88 61958799
fetch android-ndk-r27d-linux.zip \
  https://dl.google.com/android/repository/android-ndk-r27d-linux.zip \
  601246087a682d1944e1e16dd85bc6e49560fe8b6d61255be2829178c8ed15d9 663956036
fetch ecj-3.40.0.jar \
  https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.40.0/ecj-3.40.0.jar \
  05cc22a24e7982970f63a405fc6c820bc80b806f27f3c5a6236fc475f8f7152b 3312549

if [[ ! -f "$SDK/platforms/android-35/android.jar" ]]; then
  rm -rf "$TOOLROOT/platform-unpack"
  mkdir -p "$TOOLROOT/platform-unpack"
  unzip -q "$DL/platform-35_r02.zip" -d "$TOOLROOT/platform-unpack"
  rm -rf "$SDK/platforms/android-35"
  mv "$TOOLROOT/platform-unpack/android-35" "$SDK/platforms/android-35"
fi

if [[ ! -x "$SDK/build-tools/35.0.0/aapt2" ]]; then
  rm -rf "$TOOLROOT/build-tools-unpack"
  mkdir -p "$TOOLROOT/build-tools-unpack"
  unzip -q "$DL/build-tools_r35_linux.zip" -d "$TOOLROOT/build-tools-unpack"
  rm -rf "$SDK/build-tools/35.0.0"
  # Google's archive uses android-15 as the top-level folder.
  mv "$TOOLROOT/build-tools-unpack/android-15" "$SDK/build-tools/35.0.0"
  chmod +x "$SDK/build-tools/35.0.0/aapt2" "$SDK/build-tools/35.0.0/zipalign" || true
fi

if [[ ! -x "$SDK/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
  rm -rf "$TOOLROOT/ndk-unpack"
  mkdir -p "$TOOLROOT/ndk-unpack"
  unzip -q "$DL/android-ndk-r27d-linux.zip" -d "$TOOLROOT/ndk-unpack"
  rm -rf "$SDK/ndk/27.3.13750724"
  mv "$TOOLROOT/ndk-unpack/android-ndk-r27d" "$SDK/ndk/27.3.13750724"
fi

if [[ "$BUILD_MODE" == unsigned ]]; then
  SIGN_ARGS=(--unsigned)
else
  [[ "$BUILD_MODE" == signed ]] || { echo "AIV_BUILD_MODE must be signed or unsigned" >&2; exit 4; }
  [[ -f "$SIGNING_DIR/journal-local.p12" ]] || { echo "Missing historical signing key" >&2; exit 4; }
  [[ -f "$SIGNING_DIR/password.txt" ]] || { echo "Missing signing password file" >&2; exit 4; }
  SIGN_ARGS=(--signing-dir "$SIGNING_DIR")
fi

echo "[preflight] source marker"
VERSION="$(python3 -c 'import sys,xml.etree.ElementTree as E; print(E.parse(sys.argv[1]).getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"])' "$ROOT/app/src/main/AndroidManifest.xml")"

echo "[tests] source-level"
if command -v node >/dev/null 2>&1; then node "$ROOT/tests/audit-rules.cjs"; fi
python3 "$ROOT/tests/test_verdicts.py"

echo "[build] full native + Java + package + sign"
python3 "$ROOT/build.py" \
  --android-jar "$SDK/platforms/android-35/android.jar" \
  --build-tools "$SDK/build-tools/35.0.0" \
  --ecj "$DL/ecj-3.40.0.jar" \
  --ndk "$SDK/ndk/27.3.13750724" \
  "${SIGN_ARGS[@]}"

if [[ "$BUILD_MODE" == unsigned ]]; then
  FINAL="$ROOT/build/AIV-$VERSION-unsigned.apk"
  cp "$ROOT/build/journal-local-aligned.apk" "$FINAL"
else
  FINAL="$ROOT/build/AIV-$VERSION.apk"
  cp "$ROOT/build/journal-local.apk" "$FINAL"
  java -jar "$SDK/build-tools/35.0.0/lib/apksigner.jar" verify --verbose --print-certs "$FINAL"
fi

echo "[verify] package/signature/content"
"$SDK/build-tools/35.0.0/aapt2" dump badging "$FINAL" 2>/dev/null | head -n 8 || true
python3 - "$FINAL" "$VERSION" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    html=z.read('assets/journal.html').decode()
assert 'AIV '+sys.argv[2] in html and 'PÉNALITÉS CONFIGURABLES' in html
assert 'id="ja-scan"' in html and 'id="ja-export"' in html
PY
sha256sum "$FINAL" | tee "$FINAL.sha256"
echo "FINAL_APK=$FINAL"