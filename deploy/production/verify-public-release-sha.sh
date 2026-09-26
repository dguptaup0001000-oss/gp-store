#!/usr/bin/env bash
# Does the LIVE public deployment report exactly the commit we are about to act
# on? Answered from outside, with the answer separated from the asking.
#
# WHY THIS EXISTS. The Controlled marketplace test data workflow guarded its
# one-shot database operation with a single bare request:
#
#   curl --fail --silent --show-error --max-time 20 .../api/version
#
# and a GitHub runner answered `curl: (6) Could not resolve host:
# api.gpstore.co.in` half a second later. The job failed in twelve seconds,
# twice, having learned nothing about production at all - while the uptime
# probe kept reaching the same hostname every ten minutes and the deploy's own
# smoke test passed 141 checks against it. One resolver hiccup on one
# ephemeral runner is weather, not a fact about the release, and a guard with
# no retry cannot tell the two apart.
#
# THE OTHER HALF OF THE LESSON, WHICH IS THE IMPORTANT HALF. The wrong fix is
# to make the failure softer - `|| true`, a warning, a skip - because this
# check is the gate in front of a script that writes 100 shops and 6000
# listings into the live database. A gate that opens when it cannot see is
# worse than no gate: it looks like verification. So THREE outcomes, never
# two:
#
#   exit 0  the live deployment answered, and reports exactly this release.
#   exit 1  the live deployment answered, and it is NOT this release - or we
#           were asked to verify something that cannot be verified. A verdict.
#   exit 2  the live deployment never answered. NOT a verdict, NOT a pass;
#           the caller must treat it as a failure and nothing may proceed.
#
# Both 1 and 2 are non-zero, so a workflow that simply checks the exit status
# can never mistake "unreachable" for "verified".
#
# HOW IT RETRIES, AND WHY THAT WAY. `probe-public-health.sh` established the
# pattern here: bounded consecutive attempts with a gap, because a single
# failed request is a blip and an unbounded wait is an outage nobody is told
# about. This adds backoff that doubles to a cap, so a resolver that is
# briefly confused gets several widely-spaced chances inside a couple of
# minutes without the job idling for its whole budget. The default eight
# attempts spend about 135 seconds of waiting, chosen because the retrying
# uptime probe once gave up on this same hostname after only 40.
#
# AND WHY IT DOES NOT ONLY RETRY. Retrying a broken resolver just asks the
# same broken resolver again. After a DNS-class failure the next attempts
# bypass the runner's resolver entirely: first DNS-over-HTTPS to an IP-literal
# resolver, then, failing that, an address looked up over DoH and pinned with
# `--resolve`. Pinning the address changes nothing about trust - TLS still
# verifies the certificate for the real hostname, so a wrong or hostile
# address fails the handshake instead of being believed.
#
# A CONNECT FAILURE ESCALATES TOO, and the uptime probe's log is why. During
# the same incident it recorded exit 6 once and then `Failed to connect ...
# after 272 ms` and `after 10 ms` - a TCP connect that fails in ten
# milliseconds did not time out, it had nowhere to go, which is what an
# address of an unroutable family looks like from a runner. Production is
# fronted by A records and every runner that reaches it does so over IPv4, so
# after a connect-class failure the remaining attempts ask for IPv4 only. That
# removes a failure mode; it cannot invent a passing answer.
#
# IT WEAKENS NOTHING. No secret, no token, no header: /api/version is public.
# It cannot make VersionGuard more permissive, it does not run on the VPS, and
# it is read-only over HTTP. Its only power is to refuse.
#
# Usage:
#   EXPECT_SHA=<40 hex> verify-public-release-sha.sh
#   verify-public-release-sha.sh <40 hex>
#   VERIFY_RELEASE_SHA_SELFTEST=1 verify-public-release-sha.sh
#
# Environment:
#   PUBLIC_VERSION_URL                 default https://api.gpstore.co.in/v1/api/version
#   EXPECT_SHA                         the 40-character commit that must be live
#   EXPECT_ENVIRONMENT                 default production
#   VERIFY_ATTEMPTS                    total attempts, default 8
#   VERIFY_CONNECT_TIMEOUT_SECONDS     default 10
#   VERIFY_TIMEOUT_SECONDS             default 25
#   VERIFY_BACKOFF_SECONDS             first gap, doubling, default 3
#   VERIFY_BACKOFF_CAP_SECONDS         gap ceiling, default 30
#   VERIFY_DOH_URL                     IP-literal DoH resolver, default https://1.1.1.1/dns-query
#   VERIFY_BODY_OUTPUT                 copy the verified body here
set -Eeuo pipefail

PUBLIC_VERSION_URL="${PUBLIC_VERSION_URL:-https://api.gpstore.co.in/v1/api/version}"
EXPECT_SHA="${EXPECT_SHA:-${1:-}}"
EXPECT_ENVIRONMENT="${EXPECT_ENVIRONMENT:-production}"
VERIFY_ATTEMPTS="${VERIFY_ATTEMPTS:-8}"
VERIFY_CONNECT_TIMEOUT_SECONDS="${VERIFY_CONNECT_TIMEOUT_SECONDS:-10}"
VERIFY_TIMEOUT_SECONDS="${VERIFY_TIMEOUT_SECONDS:-25}"
VERIFY_BACKOFF_SECONDS="${VERIFY_BACKOFF_SECONDS:-3}"
VERIFY_BACKOFF_CAP_SECONDS="${VERIFY_BACKOFF_CAP_SECONDS:-30}"
VERIFY_DOH_URL="${VERIFY_DOH_URL:-https://1.1.1.1/dns-query}"
VERIFY_BODY_OUTPUT="${VERIFY_BODY_OUTPUT:-}"

# The three outcomes, named so the code reads as the contract above.
readonly VERIFIED=0
readonly WRONG_RELEASE=1
readonly NEVER_ANSWERED=2

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

# curl's exit codes, grouped by who has to fix them.
#
# 6 and 5 are the ones this script was written for: no address for the host.
# They are the only failures worth changing tactics over, because they are the
# only ones where the runner's resolver is the suspect.
is_dns_failure() { [[ "$1" == "6" || "$1" == "5" ]]; }

# 2, 3 and 4 mean curl refused the command itself - a malformed URL, an
# unknown option, a protocol this build cannot speak. Retrying a typo several
# times just hides it, so these stop at once and are reported as OUR bug.
is_usage_failure() { [[ "$1" == "2" || "$1" == "3" || "$1" == "4" ]]; }

# 7 is "could not connect": an address was known and led nowhere.
is_connect_failure() { [[ "$1" == "7" ]]; }

host_of() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit
parts = urlsplit(sys.argv[1])
print(parts.hostname or "")
PY
}

port_of() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit
parts = urlsplit(sys.argv[1])
print(parts.port or (443 if parts.scheme == "https" else 80))
PY
}

# An address for the host from a resolver that is NOT this machine's.
#
# Talks to the DoH endpoint by IP literal, so it needs no working DNS to ask
# where DNS lives - which is the whole trick. Prints nothing and fails quietly
# when it cannot help; the caller carries on retrying normally.
resolve_over_doh() {
  local host="$1" answer_file rc=0 address
  answer_file="$(mktemp -t gpstore_doh.XXXXXX)"
  curl --silent --show-error \
    --connect-timeout "$VERIFY_CONNECT_TIMEOUT_SECONDS" \
    --max-time "$VERIFY_TIMEOUT_SECONDS" \
    --header 'accept: application/dns-json' \
    --output "$answer_file" \
    "${VERIFY_DOH_URL}?name=${host}&type=A" >/dev/null 2>&1 || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    rm -f "$answer_file"
    return 1
  fi
  # The answer goes in a FILE and the program in a heredoc, because they
  # cannot both be stdin - the first version piped the JSON into a heredoc
  # program, which silently read the exhausted heredoc instead and made this
  # fallback a no-op that still logged as if it had tried.
  address="$(python3 - "$answer_file" <<'PY'
import json, sys
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        answers = json.load(handle).get("Answer") or []
except Exception:
    sys.exit(1)
# Type 1 is an A record. CNAME rows arrive in the same list and are not
# addresses, so they are skipped rather than pinned.
for row in answers:
    if row.get("type") == 1 and row.get("data"):
        print(row["data"])
        sys.exit(0)
sys.exit(1)
PY
  )" || rc=$?
  rm -f "$answer_file"
  [[ "$rc" -eq 0 && -n "$address" ]] || return 1
  printf '%s' "$address"
}

# IS THIS EVEN PRODUCTION'S ADDRESS?
#
# Written after the failure that taught it. On 2026-09-26 at 10:00 UTC the
# registry record for gpstore.co.in was changed to
# ns1/ns2.verification-hold.suspended-domain.com - a registrar hold - and
# those nameservers answer A 127.0.0.1 with a 30-second TTL for every name in
# the zone. From a runner that produces two symptoms with one cause: resolvers
# that refuse the private-NS delegation give `curl: (6) Could not resolve
# host`, and resolvers that hand back 127.0.0.1 make curl dial the runner's
# OWN loopback and fail in ten milliseconds. Both look exactly like the flaky
# network they are not.
#
# So when a request fails, say whether the name still points anywhere real.
# The check only ever adds a diagnosis to a failure; it cannot pass anything.
is_non_public_ipv4() {
  case "$1" in
    127.*|0.0.0.0|0.*|10.*|192.168.*|169.254.*|\
    172.1[6-9].*|172.2[0-9].*|172.3[01].*) return 0 ;;
    *) return 1 ;;
  esac
}

# The addresses this machine would actually use, from the system resolver
# first and DoH only if that said nothing.
addresses_for() {
  local host="$1" addrs=""
  addrs="$(getent ahostsv4 "$host" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')"
  if [[ -z "${addrs// /}" ]]; then
    addrs="$(resolve_over_doh "$host" 2>/dev/null || true)"
  fi
  printf '%s' "$addrs"
}
#
# smoke_api.sh paid for these two details already: truncate the body file
# first, so a failed attempt cannot show the PREVIOUS attempt's body as its
# evidence, and take curl's exit code separately from the status it printed,
# because "no response at all" and "answered 500" are different facts.
fetch_once() {
  local body_file="$1"; shift
  : > "$body_file"
  local status rc=0
  status="$(curl --silent --show-error \
              --connect-timeout "$VERIFY_CONNECT_TIMEOUT_SECONDS" \
              --max-time "$VERIFY_TIMEOUT_SECONDS" \
              --output "$body_file" \
              --write-out '%{http_code}' \
              "$@" "$PUBLIC_VERSION_URL" 2>/dev/null)" || rc=$?
  printf '%s %s' "$rc" "${status:-000}"
}

# The verdict, once production has actually spoken.
#
# All three identity facts are required together: the environment (so a
# staging box answering on this name cannot satisfy a production guard), the
# source commit, and the commit baked into the JAR that is running. gitCommit
# alone would pass an environment variable that says one thing while the
# binary on disk is another - which is exactly what VersionGuard exists to
# refuse, and this is the same refusal made from outside.
assert_release_identity() {
  local body_file="$1"
  python3 - "$EXPECT_SHA" "$EXPECT_ENVIRONMENT" "$body_file" <<'PY'
import json, sys

expected, expected_env, path = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(path, encoding="utf-8") as handle:
        body = json.load(handle)
except Exception as exc:
    print(f"the version endpoint did not return JSON: {exc}")
    sys.exit(1)

problems = []
if body.get("environment") != expected_env:
    problems.append(f"environment is {body.get('environment')!r}, wanted {expected_env!r}")
if body.get("gitCommit") != expected:
    problems.append(f"gitCommit is {body.get('gitCommit')!r}, wanted {expected!r}")
if body.get("binaryGitCommit") != expected:
    problems.append(f"binaryGitCommit is {body.get('binaryGitCommit')!r}, wanted {expected!r}")

if problems:
    for problem in problems:
        print(f"  - {problem}")
    print(f"  full body: {json.dumps(body, sort_keys=True)[:600]}")
    sys.exit(1)

print(f"  environment:     {body.get('environment')}")
print(f"  gitCommit:       {body.get('gitCommit')}")
print(f"  binaryGitCommit: {body.get('binaryGitCommit')}")
print(f"  schemaVersion:   {body.get('schemaVersion')}")
sys.exit(0)
PY
}

verify_public_release_sha() {
  # A guard asked to check nothing would pass everything. Refuse first.
  if [[ ! "$EXPECT_SHA" =~ ^[0-9a-f]{40}$ ]]; then
    log "REFUSING: EXPECT_SHA must be a full 40-character lowercase commit (got '${EXPECT_SHA}')"
    return "$WRONG_RELEASE"
  fi

  local host port body_file
  host="$(host_of "$PUBLIC_VERSION_URL")"
  port="$(port_of "$PUBLIC_VERSION_URL")"
  if [[ -z "$host" ]]; then
    log "REFUSING: PUBLIC_VERSION_URL has no host: '$PUBLIC_VERSION_URL'"
    return "$WRONG_RELEASE"
  fi
  body_file="$(mktemp -t gpstore_version.XXXXXX)"
  # shellcheck disable=SC2064  # expand the path now, while it is known.
  trap "rm -f '$body_file'" RETURN

  log "verifying $PUBLIC_VERSION_URL reports $EXPECT_SHA (up to $VERIFY_ATTEMPTS attempts)"

  local use_doh="${VERIFY_FORCE_DOH:-0}" ipv4_only="${VERIFY_FORCE_IPV4:-0}"
  local pinned_ip="" gap="$VERIFY_BACKOFF_SECONDS"
  local attempt result rc status extra=() answered_at_all=0 last=""
  local diagnosed=0 parked_at=""

  for ((attempt = 1; attempt <= VERIFY_ATTEMPTS; attempt++)); do
    extra=()
    [[ "$use_doh" == "1" ]] && extra+=(--doh-url "$VERIFY_DOH_URL")
    [[ "$ipv4_only" == "1" ]] && extra+=(--ipv4)
    [[ -n "$pinned_ip" ]] && extra+=(--resolve "$host:$port:$pinned_ip")

    result="$(fetch_once "$body_file" "${extra[@]+"${extra[@]}"}")"
    rc="${result%% *}"
    status="${result##* }"

    if [[ "$rc" == "0" && "$status" == "200" ]]; then
      log "attempt $attempt/$VERIFY_ATTEMPTS: HTTP 200 from $host"
      if assert_release_identity "$body_file"; then
        log "VERIFIED: the live $EXPECT_ENVIRONMENT deployment is $EXPECT_SHA (source and JAR)"
        if [[ -n "$VERIFY_BODY_OUTPUT" ]]; then
          cp "$body_file" "$VERIFY_BODY_OUTPUT"
        fi
        return "$VERIFIED"
      fi
      # Production answered clearly and it is the wrong build. More attempts
      # cannot change that, and retrying would only delay the refusal.
      log "WRONG RELEASE: $host is live, but it is not $EXPECT_SHA."
      log "Nothing may be seeded against a deployment that is not the release being validated."
      return "$WRONG_RELEASE"
    fi

    if is_usage_failure "$rc"; then
      log "REFUSING: curl rejected the request itself (exit $rc). This is a bug in the check, not an outage."
      head -c 300 "$body_file" 2>/dev/null || true
      return "$WRONG_RELEASE"
    fi

    # Once, on the first network failure: does the name still point at
    # anything public? A parked or held domain is not a flaky network and
    # re-running will not cure it.
    if [[ "$rc" != "0" && "$diagnosed" == "0" ]]; then
      diagnosed=1
      local resolved addr
      resolved="$(addresses_for "$host")"
      for addr in $resolved; do
        if is_non_public_ipv4 "$addr"; then
          parked_at="$addr"
          log "  !! DNS answers $addr for $host, which is not a public address."
          log "     The hostname is not pointing at production. A registrar hold or a"
          log "     parked zone does exactly this, and no amount of retrying will fix it."
          break
        fi
      done
    fi

    if [[ "$rc" == "0" ]]; then
      answered_at_all=1
      last="HTTP $status"
      log "attempt $attempt/$VERIFY_ATTEMPTS: $host answered $last (wanted 200)"
    elif is_dns_failure "$rc"; then
      last="DNS: no address for $host (curl exit $rc)"
      log "attempt $attempt/$VERIFY_ATTEMPTS: $last"
      # Change tactics rather than asking the same resolver again.
      if [[ "$use_doh" != "1" ]]; then
        use_doh=1
        log "  -> next attempt resolves over DNS-over-HTTPS at $VERIFY_DOH_URL"
      elif [[ -z "$pinned_ip" ]]; then
        if pinned_ip="$(resolve_over_doh "$host")" && [[ -n "$pinned_ip" ]] \
           && ! is_non_public_ipv4 "$pinned_ip"; then
          log "  -> next attempt pins $host to $pinned_ip (TLS still verifies the certificate)"
        else
          pinned_ip=""
          log "  -> DoH could not supply an address either; retrying as before"
        fi
      fi
    else
      last="no HTTP response (curl exit $rc)"
      log "attempt $attempt/$VERIFY_ATTEMPTS: $last"
      if is_connect_failure "$rc" && [[ "$ipv4_only" != "1" ]]; then
        ipv4_only=1
        log "  -> next attempt asks for IPv4 only; an address that refuses in milliseconds"
        log "     is usually one this runner has no route to"
      fi
    fi

    # No sleep after the final attempt; it would only delay the failure.
    if [[ "$attempt" -lt "$VERIFY_ATTEMPTS" ]]; then
      log "  waiting ${gap}s before the next attempt"
      sleep "$gap"
      gap=$((gap * 2))
      [[ "$gap" -gt "$VERIFY_BACKOFF_CAP_SECONDS" ]] && gap="$VERIFY_BACKOFF_CAP_SECONDS"
    fi
  done

  log "NOT VERIFIED: $PUBLIC_VERSION_URL did not confirm $EXPECT_SHA in $VERIFY_ATTEMPTS attempts."
  log "Last result: $last"
  if [[ -n "$parked_at" ]]; then
    log "THE NAME IS THE PROBLEM, NOT THE NETWORK: $host resolves to $parked_at."
    log "Production cannot be reached at this hostname by anybody - including customers -"
    log "until its DNS is restored. Check the domain's registry status and nameservers."
  elif [[ "$answered_at_all" == "1" ]]; then
    log "The host answered but never with 200, so the deployed commit is still unknown."
  else
    log "No HTTP response arrived at all, so this is NOT a verdict on the deployed code -"
    log "and it is NOT a pass. Either production is unreachable, or THIS RUNNER cannot"
    log "reach it while others can. Re-run to tell the two apart; nothing may be seeded"
    log "until the live deployment has positively confirmed the release."
  fi
  return "$NEVER_ANSWERED"
}

if [[ "${VERIFY_RELEASE_SHA_SELFTEST:-0}" == "1" ]]; then
  # PROVED WITHOUT A NETWORK, AND ONCE WITH THE REAL curl.
  #
  # Most checks below drive a stand-in curl whose answers are scripted by a
  # counter file - the only way to prove a retry loop and a mismatch verdict
  # deterministically. But a fake curl accepts any flag, so a self-test made
  # only of fakes would happily pass while the real curl rejected an option
  # and production went unverified. The last checks therefore run the real
  # curl, with the real flag set, at a port nothing listens on.
  SELFTEST_DIR="$(mktemp -d)"
  trap 'rm -rf "$SELFTEST_DIR"' EXIT
  failures=0
  note() {
    if [[ "$1" == "ok" ]]; then
      echo "ok   $2"
    else
      echo "FAIL $2"
      failures=$((failures + 1))
    fi
  }

  GOOD_SHA="1111111111111111111111111111111111111111"
  OTHER_SHA="2222222222222222222222222222222222222222"

  cat > "$SELFTEST_DIR/curl" <<'FAKECURL'
#!/usr/bin/env bash
# A scripted stand-in for curl. Writes the body named for this attempt to the
# -o/--output path, prints the status, and exits with the scripted code.
#
out=""
prev=""
for arg in "$@"; do
  case "$prev" in
    -o|--output) out="$arg" ;;
  esac
  prev="$arg"
done
# A DoH lookup is answered separately and is NOT counted as an attempt: it is
# the script asking where the host lives, not asking the host anything.
if [[ "$*" == *"dns-query?name="* ]]; then
  if [[ -n "$out" ]]; then cat "$FAKE_CURL_DOH_FILE" > "$out"; else cat "$FAKE_CURL_DOH_FILE"; fi
  exit 0
fi
n=$(( $(cat "$FAKE_CURL_COUNT" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$FAKE_CURL_COUNT"
printf '%s\n' "$*" >> "$FAKE_CURL_ARGS"
if [[ "$n" -le "${FAKE_CURL_FAIL_TIMES:-0}" ]]; then
  [[ -n "$out" ]] && printf 'half a response\n' > "$out"
  printf '%s' "${FAKE_CURL_FAIL_STATUS:-000}"
  exit "${FAKE_CURL_FAIL_CODE:-6}"
fi
[[ -n "$out" ]] && cat "$FAKE_CURL_BODY" > "$out"
printf '%s' "${FAKE_CURL_STATUS:-200}"
exit 0
FAKECURL
  chmod +x "$SELFTEST_DIR/curl"

  write_body() {
    cat > "$SELFTEST_DIR/body.json" <<JSON
{"environment":"$1","gitCommit":"$2","binaryGitCommit":"$3","schemaVersion":"42"}
JSON
  }

  printf '{"Answer":[{"type":5,"data":"cname.example.invalid"},{"type":1,"data":"203.0.113.10"}]}\n' \
    > "$SELFTEST_DIR/doh.json"

  export FAKE_CURL_COUNT="$SELFTEST_DIR/count"
  export FAKE_CURL_ARGS="$SELFTEST_DIR/args"
  export FAKE_CURL_BODY="$SELFTEST_DIR/body.json"
  export FAKE_CURL_DOH_FILE="$SELFTEST_DIR/doh.json"
  attempts_used() { cat "$SELFTEST_DIR/count" 2>/dev/null || echo 0; }
  reset() { rm -f "$SELFTEST_DIR/count" "$SELFTEST_DIR/args"; }

  # Fast, quiet, and pointed at the fake curl. SELFTEST is turned OFF for the
  # child, or the script would recurse into its own self-test forever.
  fake_verify() {
    reset
    (
      PATH="$SELFTEST_DIR:$PATH"
      PUBLIC_VERSION_URL="https://api.example.invalid/v1/api/version"
      VERIFY_RELEASE_SHA_SELFTEST=0
      VERIFY_BACKOFF_SECONDS=0
      VERIFY_BACKOFF_CAP_SECONDS=0
      VERIFY_CONNECT_TIMEOUT_SECONDS=1
      VERIFY_TIMEOUT_SECONDS=2
      export PATH PUBLIC_VERSION_URL VERIFY_RELEASE_SHA_SELFTEST \
             VERIFY_BACKOFF_SECONDS VERIFY_BACKOFF_CAP_SECONDS \
             VERIFY_CONNECT_TIMEOUT_SECONDS VERIFY_TIMEOUT_SECONDS
      "$@" 2>&1
    )
  }

  # 1. The happy path: production answers with the exact release.
  write_body production "$GOOD_SHA" "$GOOD_SHA"
  out=""; code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=3 \
           bash "$0")" || code=$?
  if [[ "$code" == "0" ]] && grep -q "VERIFIED" <<<"$out"; then
    note ok "an exact source+JAR match on the live deployment verifies"
  else
    note FAIL "an exact source+JAR match on the live deployment verifies (code=$code)"
  fi

  # 2. THE BUG THIS SCRIPT EXISTS FOR. A resolver hiccup that clears must not
  # fail the guard - and must not need a human to press re-run.
  write_body production "$GOOD_SHA" "$GOOD_SHA"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=4 \
           FAKE_CURL_FAIL_TIMES=2 FAKE_CURL_FAIL_CODE=6 bash "$0")" || code=$?
  if [[ "$code" == "0" ]] && [[ "$(attempts_used)" -ge "3" ]]; then
    note ok "a DNS failure that clears is retried and then verifies"
  else
    note FAIL "a DNS failure that clears is retried and then verifies (code=$code attempts=$(attempts_used))"
  fi

  # 3. THE OTHER HALF. A resolver that never recovers is a FAILURE, with its
  # own exit code, and the output must not read like a pass.
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=3 \
           FAKE_CURL_FAIL_TIMES=99 FAKE_CURL_FAIL_CODE=6 bash "$0")" || code=$?
  if [[ "$code" == "2" ]] && [[ "$(attempts_used)" == "3" ]] \
     && grep -q "NOT VERIFIED" <<<"$out" && ! grep -q "VERIFIED: the live" <<<"$out"; then
    note ok "an unresolvable host fails with the unreachable code after exactly the attempts allowed"
  else
    note FAIL "an unresolvable host fails with the unreachable code after exactly the attempts allowed (code=$code attempts=$(attempts_used))"
  fi

  # 4. A resolver that keeps failing must be worked around, not merely asked
  # again: DoH first, then a pinned address.
  if grep -q -- '--doh-url' "$SELFTEST_DIR/args" \
     && grep -q -- '--resolve api.example.invalid:443:' "$SELFTEST_DIR/args"; then
    note ok "repeated DNS failures escalate to DoH and then to a pinned address"
  else
    note FAIL "repeated DNS failures escalate to DoH and then to a pinned address"
  fi

  # 5. A connect failure that fails in milliseconds is an address with nowhere
  # to go, which the uptime probe saw during the same incident. The remaining
  # attempts must ask for IPv4 only rather than repeating it.
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=3 \
           FAKE_CURL_FAIL_TIMES=99 FAKE_CURL_FAIL_CODE=7 bash "$0")" || code=$?
  if [[ "$code" == "2" ]] && grep -q -- '--ipv4' "$SELFTEST_DIR/args"; then
    note ok "a connect failure escalates to IPv4-only on the next attempt"
  else
    note FAIL "a connect failure escalates to IPv4-only on the next attempt (code=$code)"
  fi

  # 6. THE FAILURE OF 2026-09-26. The registrar pointed the zone at
  # verification-hold nameservers answering 127.0.0.1, so the hostname stopped
  # meaning production. That must be NAMED, not reported as a flaky network,
  # because re-running cures a blip and cannot cure a held domain.
  cat > "$SELFTEST_DIR/getent" <<'FAKEGETENT'
#!/usr/bin/env bash
printf '%s  %s\n' "${FAKE_GETENT_ADDRESS:-203.0.113.10}" "${2:-host}"
FAKEGETENT
  chmod +x "$SELFTEST_DIR/getent"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 \
           FAKE_GETENT_ADDRESS=127.0.0.1 \
           FAKE_CURL_FAIL_TIMES=99 FAKE_CURL_FAIL_CODE=7 bash "$0")" || code=$?
  if [[ "$code" == "2" ]] && grep -q "THE NAME IS THE PROBLEM" <<<"$out" \
     && grep -q "not a public address" <<<"$out"; then
    note ok "a hostname parked on 127.0.0.1 is named as a DNS problem, not a flaky network"
  else
    note FAIL "a hostname parked on 127.0.0.1 is named as a DNS problem, not a flaky network (code=$code)"
  fi

  # A real address must NOT trigger that diagnosis, or it would cry wolf on
  # every ordinary outage.
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 \
           FAKE_GETENT_ADDRESS=203.0.113.10 \
           FAKE_CURL_FAIL_TIMES=99 FAKE_CURL_FAIL_CODE=7 bash "$0")" || code=$?
  if [[ "$code" == "2" ]] && ! grep -q "THE NAME IS THE PROBLEM" <<<"$out"; then
    note ok "an ordinary unreachable production is not blamed on DNS"
  else
    note FAIL "an ordinary unreachable production is not blamed on DNS (code=$code)"
  fi

  # The predicate itself, since everything above rests on it.
  bad_addresses=0
  for addr in 127.0.0.1 127.1.2.3 0.0.0.0 10.1.2.3 192.168.1.1 169.254.1.1 \
              172.16.0.1 172.31.255.255; do
    is_non_public_ipv4 "$addr" || bad_addresses=$((bad_addresses + 1))
  done
  for addr in 203.0.113.10 104.21.5.6 172.15.0.1 172.32.0.1 8.8.8.8 11.0.0.1; do
    is_non_public_ipv4 "$addr" && bad_addresses=$((bad_addresses + 1))
  done
  if [[ "$bad_addresses" == "0" ]]; then
    note ok "loopback and private ranges are recognised and public ones are not"
  else
    note FAIL "loopback and private ranges are recognised and public ones are not ($bad_addresses wrong)"
  fi

  # 7. The wrong build is a verdict, delivered at once. Retrying it would only
  # delay the refusal, and a seed must never run against another release.
  write_body production "$OTHER_SHA" "$OTHER_SHA"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=4 bash "$0")" || code=$?
  if [[ "$code" == "1" ]] && [[ "$(attempts_used)" == "1" ]] \
     && grep -q "WRONG RELEASE" <<<"$out"; then
    note ok "a live deployment on another commit fails immediately as the wrong release"
  else
    note FAIL "a live deployment on another commit fails immediately as the wrong release (code=$code attempts=$(attempts_used))"
  fi

  # 8. gitCommit alone is not identity. A JAR that disagrees with its own
  # environment variable is the exact thing VersionGuard refuses on the box.
  write_body production "$GOOD_SHA" "$OTHER_SHA"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 bash "$0")" || code=$?
  if [[ "$code" == "1" ]] && grep -q "binaryGitCommit" <<<"$out"; then
    note ok "a running JAR whose embedded commit differs is refused"
  else
    note FAIL "a running JAR whose embedded commit differs is refused (code=$code)"
  fi

  # 9. A staging box answering on this name cannot satisfy a production guard.
  write_body staging "$GOOD_SHA" "$GOOD_SHA"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 bash "$0")" || code=$?
  if [[ "$code" == "1" ]] && grep -q "environment is" <<<"$out"; then
    note ok "a non-production environment is refused"
  else
    note FAIL "a non-production environment is refused (code=$code)"
  fi

  # 10. A body that is not JSON at all is not a pass either.
  printf '<html>504 Gateway Time-out</html>\n' > "$SELFTEST_DIR/body.json"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 bash "$0")" || code=$?
  if [[ "$code" == "1" ]] && grep -q "did not return JSON" <<<"$out"; then
    note ok "an HTML error page served with HTTP 200 is refused"
  else
    note FAIL "an HTML error page served with HTTP 200 is refused (code=$code)"
  fi

  # 11. A non-200 is retried, then reported as an unknown commit - never as a
  # verified one.
  write_body production "$GOOD_SHA" "$GOOD_SHA"
  code=0
  out="$(fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 \
           FAKE_CURL_STATUS=503 bash "$0")" || code=$?
  if [[ "$code" == "2" ]] && [[ "$(attempts_used)" == "2" ]] \
     && grep -q "never with 200" <<<"$out"; then
    note ok "HTTP 503 is retried and then reported as an unknown deployed commit"
  else
    note FAIL "HTTP 503 is retried and then reported as an unknown deployed commit (code=$code attempts=$(attempts_used))"
  fi

  # 12. A guard asked to verify nothing must refuse, not pass.
  for bad in "" "f17767d" "F17767D498253706FB65E1E681F598B8DFE54530" "not-a-sha"; do
    code=0
    out="$(fake_verify env EXPECT_SHA="$bad" VERIFY_ATTEMPTS=2 bash "$0")" || code=$?
    if [[ "$code" == "1" ]] && [[ "$(attempts_used)" == "0" ]]; then
      note ok "a SHA that is not 40 lowercase hex characters is refused unasked ('${bad}')"
    else
      note FAIL "a SHA that is not 40 lowercase hex characters is refused unasked ('${bad}', code=$code)"
    fi
  done

  # 13. The verified body can be handed on, and is only written on success.
  write_body production "$GOOD_SHA" "$GOOD_SHA"
  rm -f "$SELFTEST_DIR/kept.json"
  fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 \
    VERIFY_BODY_OUTPUT="$SELFTEST_DIR/kept.json" bash "$0" >/dev/null 2>&1 || true
  if grep -q "$GOOD_SHA" "$SELFTEST_DIR/kept.json" 2>/dev/null; then
    note ok "the verified body is kept for the job log"
  else
    note FAIL "the verified body is kept for the job log"
  fi
  write_body production "$OTHER_SHA" "$OTHER_SHA"
  rm -f "$SELFTEST_DIR/never.json"
  fake_verify env EXPECT_SHA="$GOOD_SHA" VERIFY_ATTEMPTS=2 \
    VERIFY_BODY_OUTPUT="$SELFTEST_DIR/never.json" bash "$0" >/dev/null 2>&1 || true
  if [[ ! -e "$SELFTEST_DIR/never.json" ]]; then
    note ok "nothing is written when the release was not verified"
  else
    note FAIL "nothing is written when the release was not verified"
  fi

  # 14. THE REAL curl, WITH THE REAL FLAGS, at a port nothing listens on.
  # Proves this runner's curl accepts every option used above - including
  # --doh-url, which is the DNS workaround and would otherwise be a usage
  # error discovered only in production. Expect the unreachable verdict (2),
  # never the "curl rejected the request itself" one.
  for forced in "0 0" "1 0" "0 1" "1 1"; do
    read -r forced_doh forced_ipv4 <<<"$forced"
    code=0
    out="$(EXPECT_SHA="$GOOD_SHA" \
           PUBLIC_VERSION_URL="https://127.0.0.1:1/v1/api/version" \
           VERIFY_RELEASE_SHA_SELFTEST=0 \
           VERIFY_ATTEMPTS=1 VERIFY_CONNECT_TIMEOUT_SECONDS=2 \
           VERIFY_TIMEOUT_SECONDS=3 VERIFY_FORCE_DOH="$forced_doh" \
           VERIFY_FORCE_IPV4="$forced_ipv4" \
           bash "$0" 2>&1)" || code=$?
    if [[ "$code" == "2" ]] && ! grep -q "curl rejected the request itself" <<<"$out"; then
      note ok "the real curl accepts the flag set (DoH=$forced_doh IPv4=$forced_ipv4) and reports unreachable"
    else
      note FAIL "the real curl accepts the flag set (DoH=$forced_doh IPv4=$forced_ipv4) and reports unreachable (code=$code): $out"
    fi
  done

  if [[ "$failures" -ne 0 ]]; then
    echo "public release SHA verifier self-test: $failures failure(s)"
    exit 1
  fi
  echo "public release SHA verifier self-test: all checks passed"
  exit 0
fi

verify_public_release_sha
