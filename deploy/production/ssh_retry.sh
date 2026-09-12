#!/usr/bin/env bash
# Run an ssh command, retrying only the failures that belong to the network.
#
# WHY THIS EXISTS, AND WHAT IT COST TO LEARN. Three scheduled workflows SSH
# to the VPS: the money alert, the backup alert and the access probe. None of
# them retried. A GitHub runner whose egress address cannot reach the box -
# which happens, and is invisible from here - turned every one of them red,
# and the money alert then announced "GP-STORE has money that has not
# reached a customer" about a query that never ran. The deploy has survived
# this all along because it already retries on a second runner; these did
# not.
#
# 255 IS SSH'S OWN VOICE AND NOTHING ELSE'S. ssh exits 255 when IT failed -
# connection timed out, refused, host unreachable, authentication rejected.
# Any other non-zero code is the REMOTE COMMAND's verdict and is passed
# straight back without a retry: a psql that failed, a container that is
# down, an evaluator that found a stuck refund. Retrying those would turn a
# real finding into three real findings and then report the last one.
#
# An auth rejection is also 255, so it is retried too, and that is a
# deliberate trade: a broken deploy key costs three attempts and then fails
# with the same message, while a blocked runner IP recovers. Failing closed
# on the first 255 is what produced the false money alert.
#
# IT OWNS THE OUTPUT FILE, which is the part a naive retry gets wrong.
# `ssh ... > out` truncates once, in the calling shell - so an attempt that
# writes half a result before the connection dies, followed by a successful
# retry, leaves the two CONCATENATED. The money evaluator would read that as
# unparseable and alert about money again, for a second wrong reason. Each
# attempt here writes to its own temporary file and only replaces the real
# one after ssh says it succeeded.
#
# STDIN IS A FILE, NOT A PIPE, for the same reason: a pipe is consumed by the
# first attempt and the second would send an empty query.
#
# Usage:
#   ssh_retry.sh -- ssh -i key user@host 'command'
#   ssh_retry.sh --output /tmp/out.txt -- ssh -i key user@host 'command'
#   ssh_retry.sh --output /tmp/out.txt --stdin query.sql -- ssh ... 'psql -f -'
#   SSH_RETRY_SELFTEST=1 ssh_retry.sh
#
# Environment:
#   SSH_RETRY_ATTEMPTS        total attempts, default 3
#   SSH_RETRY_DELAY_SECONDS   wait between attempts, default 20
set -euo pipefail

SSH_RETRY_ATTEMPTS="${SSH_RETRY_ATTEMPTS:-3}"
SSH_RETRY_DELAY_SECONDS="${SSH_RETRY_DELAY_SECONDS:-20}"

# ssh's own exit code for "I could not make this connection work".
readonly SSH_OWN_FAILURE=255

run_with_retry() {
  local output="" stdin_file=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --output) output="$2"; shift 2 ;;
      --stdin) stdin_file="$2"; shift 2 ;;
      --) shift; break ;;
      *) echo "ssh_retry: unknown option $1" >&2; return 2 ;;
    esac
  done
  if [[ $# -eq 0 ]]; then
    echo "ssh_retry: nothing to run" >&2
    return 2
  fi
  if [[ -n "$stdin_file" && ! -f "$stdin_file" ]]; then
    echo "ssh_retry: stdin file not found: $stdin_file" >&2
    return 2
  fi

  local attempt=1 code scratch
  while :; do
    code=0
    if [[ -n "$output" ]]; then
      # A PER-ATTEMPT FILE, promoted only on success. See the header.
      scratch="$(mktemp)"
      if [[ -n "$stdin_file" ]]; then
        "$@" < "$stdin_file" > "$scratch" || code=$?
      else
        "$@" > "$scratch" || code=$?
      fi
      if [[ "$code" -eq 0 ]]; then
        mv "$scratch" "$output"
        return 0
      fi
      rm -f "$scratch"
    else
      if [[ -n "$stdin_file" ]]; then
        "$@" < "$stdin_file" || code=$?
      else
        "$@" || code=$?
      fi
      [[ "$code" -eq 0 ]] && return 0
    fi

    # The remote command's own verdict. Not ours to second-guess.
    if [[ "$code" -ne "$SSH_OWN_FAILURE" ]]; then
      return "$code"
    fi
    if [[ "$attempt" -ge "$SSH_RETRY_ATTEMPTS" ]]; then
      echo "ssh_retry: could not connect after $attempt attempt(s)" >&2
      return "$code"
    fi
    echo "ssh_retry: ssh could not connect (attempt $attempt of" \
         "$SSH_RETRY_ATTEMPTS); retrying in ${SSH_RETRY_DELAY_SECONDS}s" >&2
    sleep "$SSH_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
}

if [[ "${SSH_RETRY_SELFTEST:-0}" == "1" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  failures=0
  export SSH_RETRY_DELAY_SECONDS=0

  note() {
    if [[ "$1" == "ok" ]]; then
      echo "ok   $2"
    else
      echo "FAIL $2"
      failures=$((failures + 1))
    fi
  }

  # A stand-in for ssh whose behaviour is scripted by a counter file: it fails
  # with the code named for the first N calls, then succeeds. That is the only
  # way to prove a retry loop without a network.
  cat > "$tmp/fake" <<'FAKE'
#!/usr/bin/env bash
count_file="$FAKE_COUNT_FILE"
n=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$count_file"
if [[ "$n" -le "${FAKE_FAIL_TIMES:-0}" ]]; then
  printf 'partial-%s\n' "$n"
  exit "${FAKE_FAIL_CODE:-255}"
fi
printf 'refunds_in_flight=0\n'
exit 0
FAKE
  chmod +x "$tmp/fake"
  export FAKE_COUNT_FILE="$tmp/count"

  attempts_used() { cat "$tmp/count"; }
  reset() { rm -f "$tmp/count"; }

  # 1. A connection failure that clears is not a failure.
  reset; FAKE_FAIL_TIMES=1 FAKE_FAIL_CODE=255 \
    run_with_retry -- "$tmp/fake" >/dev/null 2>&1 \
    && [[ "$(attempts_used)" == "2" ]] \
    && note ok "a timeout that clears on the second attempt succeeds" \
    || note FAIL "a timeout that clears on the second attempt succeeds"

  # 2. THE BUG THIS SCRIPT EXISTS FOR: a half-written result must never be
  # left joined to a good one.
  reset
  FAKE_FAIL_TIMES=1 FAKE_FAIL_CODE=255 \
    run_with_retry --output "$tmp/out" -- "$tmp/fake" >/dev/null 2>&1 || true
  if [[ "$(cat "$tmp/out")" == "refunds_in_flight=0" ]]; then
    note ok "the failed attempt's partial output is discarded, not concatenated"
  else
    note FAIL "the failed attempt's partial output is discarded, not concatenated (got: $(cat "$tmp/out"))"
  fi

  # 3. The remote command's own verdict is returned at once. Retrying a stuck
  # refund three times would report the same real finding as if it were new.
  reset; code=0
  FAKE_FAIL_TIMES=9 FAKE_FAIL_CODE=1 \
    run_with_retry -- "$tmp/fake" >/dev/null 2>&1 || code=$?
  if [[ "$code" == "1" ]] && [[ "$(attempts_used)" == "1" ]]; then
    note ok "a non-255 exit is the remote command's answer and is not retried"
  else
    note FAIL "a non-255 exit is the remote command's answer and is not retried (code=$code attempts=$(attempts_used))"
  fi

  # 4. It gives up, and says so, rather than looping for the job's timeout.
  reset; code=0
  SSH_RETRY_ATTEMPTS=3 FAKE_FAIL_TIMES=9 FAKE_FAIL_CODE=255 \
    run_with_retry -- "$tmp/fake" >/dev/null 2>&1 || code=$?
  if [[ "$code" == "255" ]] && [[ "$(attempts_used)" == "3" ]]; then
    note ok "an unreachable host fails after exactly the attempts allowed"
  else
    note FAIL "an unreachable host fails after exactly the attempts allowed (code=$code attempts=$(attempts_used))"
  fi

  # 5. No output file is written when every attempt failed, so a stale file
  # from a previous run cannot be mistaken for today's answer.
  reset; rm -f "$tmp/never"
  SSH_RETRY_ATTEMPTS=2 FAKE_FAIL_TIMES=9 FAKE_FAIL_CODE=255 \
    run_with_retry --output "$tmp/never" -- "$tmp/fake" >/dev/null 2>&1 || true
  [[ ! -e "$tmp/never" ]] \
    && note ok "nothing is written when the host never answered" \
    || note FAIL "nothing is written when the host never answered"

  # 6. Stdin is re-read on every attempt. A pipe would be empty the second
  # time and the query would silently become no query at all.
  reset
  printf 'SELECT 1;\n' > "$tmp/query.sql"
  cat > "$tmp/echo_stdin" <<'ECHOIN'
#!/usr/bin/env bash
count_file="$FAKE_COUNT_FILE"
n=$(( $(cat "$count_file" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$count_file"
body="$(cat)"
if [[ "$n" -le "${FAKE_FAIL_TIMES:-0}" ]]; then exit 255; fi
printf 'saw:%s\n' "$body"
ECHOIN
  chmod +x "$tmp/echo_stdin"
  FAKE_FAIL_TIMES=1 \
    run_with_retry --output "$tmp/stdin_out" --stdin "$tmp/query.sql" \
      -- "$tmp/echo_stdin" >/dev/null 2>&1 || true
  [[ "$(cat "$tmp/stdin_out")" == "saw:SELECT 1;" ]] \
    && note ok "the query is re-sent on a retry, not consumed by the first try" \
    || note FAIL "the query is re-sent on a retry, not consumed by the first try (got: $(cat "$tmp/stdin_out" 2>/dev/null))"

  # 7. A missing stdin file is refused rather than sending an empty query.
  code=0
  run_with_retry --stdin "$tmp/absent.sql" -- "$tmp/fake" >/dev/null 2>&1 || code=$?
  [[ "$code" == "2" ]] \
    && note ok "a missing query file is refused, not sent as nothing" \
    || note FAIL "a missing query file is refused, not sent as nothing (code=$code)"

  if [[ "$failures" -ne 0 ]]; then
    echo "ssh retry self-test: $failures failure(s)"
    exit 1
  fi
  echo "ssh retry self-test: all checks passed"
  exit 0
fi

run_with_retry "$@"
