#!/usr/bin/env bash
# After a successful deploy: drop extra gp-store-backend tags and dangling
# images. Never remove the running tag or the recorded rollback tag.
#
# Env:
#   IMAGE_NAME     default gp-store-backend
#   KEEP           last N tags to keep, including protected (default 3)
#   CURRENT_TAG    required
#   ROLLBACK_TAG   optional
#   DOCKER_BIN     default docker
#   DRY_RUN        1 = print rmi only
#   IMAGE_TAGS_FIXTURE  test file, newest tag first
set -Eeuo pipefail

IMAGE_NAME="${IMAGE_NAME:-gp-store-backend}"
KEEP="${KEEP:-3}"
CURRENT_TAG="${CURRENT_TAG:-}"
ROLLBACK_TAG="${ROLLBACK_TAG:-}"
DOCKER_BIN="${DOCKER_BIN:-docker}"

if [ -z "$CURRENT_TAG" ]; then
  echo "prune-backend-images: CURRENT_TAG is required" >&2
  exit 1
fi

list_tags() {
  if [ -n "${IMAGE_TAGS_FIXTURE:-}" ]; then
    cat "$IMAGE_TAGS_FIXTURE"
    return
  fi
  "$DOCKER_BIN" images --format '{{.Tag}}' "$IMAGE_NAME"
}

is_protected() {
  local tag="$1"
  [ "$tag" = "$CURRENT_TAG" ] && return 0
  [ -n "$ROLLBACK_TAG" ] && [ "$tag" = "$ROLLBACK_TAG" ] && return 0
  return 1
}

mapfile -t tags < <(list_tags | awk 'NF && $1 != "<none>"')
protected_count=0
for tag in "${tags[@]}"; do
  if is_protected "$tag"; then
    protected_count=$((protected_count + 1))
  fi
done
extra_keep=$((KEEP - protected_count))
if [ "$extra_keep" -lt 0 ]; then
  extra_keep=0
fi

kept_extra=0
removed=0
for tag in "${tags[@]}"; do
  if is_protected "$tag"; then
    echo "keep $IMAGE_NAME:$tag (running or rollback)"
    continue
  fi
  if [ "$kept_extra" -lt "$extra_keep" ]; then
    echo "keep $IMAGE_NAME:$tag"
    kept_extra=$((kept_extra + 1))
    continue
  fi
  echo "rmi $IMAGE_NAME:$tag"
  if [ "${DRY_RUN:-0}" != "1" ]; then
    "$DOCKER_BIN" rmi "$IMAGE_NAME:$tag" >/dev/null || true
  fi
  removed=$((removed + 1))
done

if [ "${SKIP_DANGLING_PRUNE:-0}" != "1" ] && [ "${DRY_RUN:-0}" != "1" ]; then
  "$DOCKER_BIN" image prune -f >/dev/null || true
fi

echo "prune-backend-images: removed=$removed"
