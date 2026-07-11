#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
bash setup.sh

flutter pub get
flutter analyze
flutter test
flutter build apk --release

MODULE_DIR="magisk_module"
mkdir -p "$MODULE_DIR/system/priv-app/KeywordAlert"

APK=$(find build -name "app-release.apk" -path "*/release/*" | head -1)
if [ -z "$APK" ]; then
    APK=$(find build -name "*.apk" -path "*/release/*" | head -1)
fi
if [ -z "$APK" ]; then
    APK=$(find build -name "*.apk" | head -1)
fi
[ -n "$APK" ] || { echo "APK not found"; find build -name "*.apk" -type f || true; exit 1; }

cp "$APK" "$MODULE_DIR/system/priv-app/KeywordAlert/KeywordAlert.apk"
echo "APK: $APK ($(du -h "$MODULE_DIR/system/priv-app/KeywordAlert/KeywordAlert.apk" | cut -f1))"

ZIP_NAME="keyword_alert_magisk.zip"
rm -f "$ZIP_NAME"
(
  cd "$MODULE_DIR"
  zip -r "../$ZIP_NAME" . -x "*.DS_Store" -x "*/.gitkeep"
)
echo "Magisk zip: $(du -h "$ZIP_NAME" | cut -f1)"
