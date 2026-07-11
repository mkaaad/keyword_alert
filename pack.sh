#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
bash setup.sh

flutter pub get
flutter analyze
flutter test
# arm64 only — smaller package
flutter build apk --release --target-platform android-arm64

APK=build/app/outputs/flutter-apk/app-release.apk
cp -f "$APK" keyword_alert-user.apk
echo "User APK: $(du -h keyword_alert-user.apk | cut -f1)  (sideload, no Root)"

bash scripts/package_magisk.sh "$APK"
echo "Done: keyword_alert-user.apk + keyword_alert_magisk.zip"
