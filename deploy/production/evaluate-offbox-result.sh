#!/usr/bin/env bash
# Evaluate an off-box backup/restore result file. Exit 0 only on SUCCESS.
# Used so upload/decrypt/restore failures produce the same ALERT= line as
# the on-VPS backup alert workflow.
#
#   ./evaluate-offbox-result.sh /path/to/result.txt
set -euo pipefail

RESULT_FILE="${1:?result file required}"

if [[ ! -s "$RESULT_FILE" ]]; then
  echo "ALERT=MISSING reason=offbox result.txt missing or empty"
  exit 1
fi

status="$(grep '^status=' "$RESULT_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
stage="$(grep '^stage=' "$RESULT_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
detail="$(grep '^detail=' "$RESULT_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"

case "$status" in
  SUCCESS)
    echo "ALERT=HEALTHY stage=${stage:-unknown} detail=${detail:-ok}"
    exit 0
    ;;
  UPLOAD_FAILED)
    echo "ALERT=UPLOAD_FAILED stage=$stage detail=$detail"
    exit 1
    ;;
  RESTORE_FAILED)
    echo "ALERT=RESTORE_FAILED stage=$stage detail=$detail"
    exit 1
    ;;
  FAILED|FAILURE|MISSING|"")
    echo "ALERT=${status:-MISSING} stage=${stage:-unknown} detail=$detail"
    exit 1
    ;;
  *)
    echo "ALERT=FAILED status=$status stage=$stage detail=$detail"
    exit 1
    ;;
esac
