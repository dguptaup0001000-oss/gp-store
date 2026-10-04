#!/usr/bin/env bash
# Build Super Admin with only its platform-console plugin set.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <command...>" >&2
  exit 2
fi

BACKUP="$(mktemp -d)"
cp pubspec.yaml "$BACKUP/pubspec.yaml"
if [[ -f pubspec.lock ]]; then cp pubspec.lock "$BACKUP/pubspec.lock"; fi

restore() {
  cp "$BACKUP/pubspec.yaml" "$ROOT/pubspec.yaml"
  if [[ -f "$BACKUP/pubspec.lock" ]]; then cp "$BACKUP/pubspec.lock" "$ROOT/pubspec.lock"; fi
  (cd "$ROOT" && flutter pub get >/dev/null)
  rm -rf "$BACKUP"
}
trap restore EXIT

cp pubspec.superadmin.yaml pubspec.yaml
flutter pub get

cmd=("$@")
if [[ "${cmd[0]:-}" == "flutter" ]]; then
  has_flavor=0
  for arg in "${cmd[@]}"; do
    if [[ "$arg" == "--flavor" ]]; then has_flavor=1; break; fi
  done
  if [[ "$has_flavor" -eq 0 ]]; then
    cmd+=(--flavor superadmin)
    echo "added --flavor superadmin"
  fi
fi
"${cmd[@]}"
