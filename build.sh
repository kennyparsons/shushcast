#!/usr/bin/env bash
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="${ANDROID_BUILD_TOOLS_DIR:-$SDK/build-tools/34.0.0}"
if [ ! -d "$BT" ]; then
  BT="$(ls -1d "$SDK/build-tools/"* 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$BT" ] || [ ! -d "$BT" ]; then
  echo "Error: Android build-tools not found under $SDK/build-tools" >&2
  exit 1
fi

PLAT="${ANDROID_PLATFORM_JAR:-$SDK/platforms/android-34/android.jar}"
KEYSTORE_PATH="${KEYSTORE_PATH:-$SDK/tv-helper.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android123}"
KEY_ALIAS="${KEY_ALIAS:-tvhelper}"
KEY_PASS="${KEY_PASS:-android123}"

rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/gen" "$PROJ/build/obj" "$PROJ/build/dex" "$PROJ/build/res"

"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res/compiled.zip"

AAPT_LINK_ARGS=(
  -I "$PLAT"
  --manifest "$PROJ/AndroidManifest.xml"
  --java "$PROJ/build/gen"
  -o "$PROJ/build/unsigned.apk"
)
if [ -n "${VERSION_CODE:-}" ] || [ -n "${VERSION_NAME:-}" ]; then
  AAPT_LINK_ARGS+=(--replace-version)
  if [ -n "${VERSION_CODE:-}" ]; then
    AAPT_LINK_ARGS+=(--version-code "$VERSION_CODE")
  fi
  if [ -n "${VERSION_NAME:-}" ]; then
    AAPT_LINK_ARGS+=(--version-name "$VERSION_NAME")
  fi
fi

"$BT/aapt2" link "${AAPT_LINK_ARGS[@]}" "$PROJ/build/res/compiled.zip"

find "$PROJ/src" "$PROJ/build/gen" -name '*.java' > "$PROJ/build/sources.txt"
javac -encoding UTF-8 --release 11 -classpath "$PLAT" -d "$PROJ/build/obj" @"$PROJ/build/sources.txt"

"$BT/d8" --min-api 29 --lib "$PLAT" --output "$PROJ/build/dex" $(find "$PROJ/build/obj" -name '*.class')

cp "$PROJ/build/unsigned.apk" "$PROJ/build/unaligned.apk"
(cd "$PROJ/build/dex" && zip -qj "$PROJ/build/unaligned.apk" classes*.dex)

"$BT/zipalign" -f 4 "$PROJ/build/unaligned.apk" "$PROJ/build/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KEYSTORE_PATH" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEYSTORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$PROJ/build/shushcast.apk" \
  "$PROJ/build/aligned.apk"

ls -lh "$PROJ/build/shushcast.apk"
"$BT/apksigner" verify --print-certs "$PROJ/build/shushcast.apk"
