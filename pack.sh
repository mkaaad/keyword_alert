#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")"
bash setup.sh

flutter pub get
flutter analyze
flutter test
# arm64 only — device target is OnePlus etc.; smaller Magisk package
flutter build apk --release --target-platform android-arm64

bash scripts/package_magisk.sh build/app/outputs/flutter-apk/app-release.apk
