#!/usr/bin/env bash
set -euo pipefail
PROJ=/opt/projects/shushcast
SDK=/opt/android-sdk
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"

rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/gen" "$PROJ/build/obj" "$PROJ/build/dex" "$PROJ/build/res"

"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res/compiled.zip"
"$BT/aapt2" link \
  -I "$PLAT" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$PROJ/build/gen" \
  -o "$PROJ/build/unsigned.apk" \
  "$PROJ/build/res/compiled.zip"

find "$PROJ/src" "$PROJ/build/gen" -name '*.java' > "$PROJ/build/sources.txt"
javac -encoding UTF-8 --release 11 -classpath "$PLAT" -d "$PROJ/build/obj" @"$PROJ/build/sources.txt"

"$BT/d8" --min-api 29 --lib "$PLAT" --output "$PROJ/build/dex" $(find "$PROJ/build/obj" -name '*.class')

cp "$PROJ/build/unsigned.apk" "$PROJ/build/unaligned.apk"
(cd "$PROJ/build/dex" && zip -qj "$PROJ/build/unaligned.apk" classes*.dex)

"$BT/zipalign" -f 4 "$PROJ/build/unaligned.apk" "$PROJ/build/aligned.apk"
"$BT/apksigner" sign \
  --ks "$SDK/tv-helper.keystore" \
  --ks-pass pass:android123 \
  --key-pass pass:android123 \
  --out "$PROJ/build/shushcast.apk" \
  "$PROJ/build/aligned.apk"

ls -lh "$PROJ/build/shushcast.apk"
"$BT/apksigner" verify --print-certs "$PROJ/build/shushcast.apk"
