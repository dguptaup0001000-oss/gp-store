#!/usr/bin/env bash
# Swap in pubspec.worker.yaml for one command, then restore the customer
# pubspec + lockfile. Used by CI when building the delivery-worker APK/AAB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <command...>" >&2
  exit 2
fi

BACKUP="$(mktemp -d)"
GRADLE_PROPS="$ROOT/android/gradle.properties"
cp pubspec.yaml "$BACKUP/pubspec.yaml"
if [[ -f pubspec.lock ]]; then
  cp pubspec.lock "$BACKUP/pubspec.lock"
fi
cp "$GRADLE_PROPS" "$BACKUP/gradle.properties"

restore() {
  cp "$BACKUP/pubspec.yaml" "$ROOT/pubspec.yaml"
  if [[ -f "$BACKUP/pubspec.lock" ]]; then
    cp "$BACKUP/pubspec.lock" "$ROOT/pubspec.lock"
  fi
  cp "$BACKUP/gradle.properties" "$GRADLE_PROPS"
  (cd "$ROOT" && flutter pub get >/dev/null)
  rm -rf "$BACKUP"
}
trap restore EXIT

cp pubspec.worker.yaml pubspec.yaml
# Same plugin, Play-Services ML Kit instead of bundling libbarhopper + tflite.
# Barcode scanning still goes through mobile_scanner; the model is not packed
# into the APK. Required on devices with Google Play Services (Play installs).
printf '\ndev.steenbakker.mobile_scanner.useUnbundled=true\n' >> "$GRADLE_PROPS"

export GPSTORE_WORKER_SLIM=1
flutter pub get
"$@"
