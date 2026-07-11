#!/bin/bash
# Package Magisk module: APK + pre-extracted arm64 native libs for priv-app.
# System/priv-app installs do NOT extract lib/*.so from the APK; without
# system/priv-app/KeywordAlert/lib/arm64/{libflutter,libapp,...}.so Flutter
# dies with "VM snapshot invalid" / SIGSEGV on launch.
set -euo pipefail

cd "$(dirname "$0")/.."

APK="${1:-}"
if [ -z "$APK" ]; then
  APK=build/app/outputs/flutter-apk/app-release.apk
fi
if [ ! -f "$APK" ]; then
  APK=$(find build -name "app-release.apk" -type f | head -1 || true)
fi
[ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "APK not found"; exit 1; }

MODULE_DIR="magisk_module"
APP_DIR="$MODULE_DIR/system/priv-app/KeywordAlert"
LIB_DIR="$APP_DIR/lib/arm64"

rm -rf "$APP_DIR/lib"
mkdir -p "$LIB_DIR"
cp -f "$APK" "$APP_DIR/KeywordAlert.apk"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -j "$APK" 'lib/arm64-v8a/*' -d "$TMP"
# Map APK ABI folder arm64-v8a -> system instruction-set dir arm64
cp -f "$TMP"/*.so "$LIB_DIR/"
chmod 644 "$LIB_DIR"/*.so

echo "APK:  $APK ($(du -h "$APP_DIR/KeywordAlert.apk" | cut -f1))"
echo "Libs: $(ls "$LIB_DIR" | wc -l) files in $LIB_DIR"
ls -lh "$LIB_DIR"

# Required Flutter entrypoints
for need in libflutter.so libapp.so; do
  [ -f "$LIB_DIR/$need" ] || { echo "Missing $need in APK arm64-v8a"; exit 1; }
done

ZIP_NAME="keyword_alert_magisk.zip"
rm -f "$ZIP_NAME"
(
  cd "$MODULE_DIR"
  zip -r "../$ZIP_NAME" . -x "*.DS_Store" -x "*/.gitkeep"
)
echo "Magisk zip: $(du -h "$ZIP_NAME" | cut -f1)"
