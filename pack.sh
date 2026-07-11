#!/bin/bash
set -e

cd "$(dirname "$0")"
bash setup.sh

flutter pub get
flutter build apk --release --split-per-abi

MODULE_DIR="magisk_module"
mkdir -p "$MODULE_DIR/system/priv-app/KeywordAlert"

# Find the arm64 APK
APK=$(find build/app/outputs/apk/release -name "*arm64*" -name "*.apk" | head -1)
if [ -z "$APK" ]; then
    APK=$(find build/app/outputs/apk/release -name "*.apk" | head -1)
fi
[ -z "$APK" ] && { echo "APK not found"; exit 1; }

cp "$APK" "$MODULE_DIR/system/priv-app/KeywordAlert/KeywordAlert.apk"
echo "APK size: $(du -h "$MODULE_DIR/system/priv-app/KeywordAlert/KeywordAlert.apk" | cut -f1)"

ZIP_NAME="keyword_alert_magisk.zip"
rm -f "$ZIP_NAME"
cd "$MODULE_DIR"
zip -r "../$ZIP_NAME" . -x "*.DS_Store"
cd ..
echo "Magisk zip: $(du -h "$ZIP_NAME" | cut -f1)"
