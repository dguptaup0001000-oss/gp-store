#!/usr/bin/env bash
# Print the public address this runner dials out from, immediately before an
# SSH attempt on the production VPS.
#
# WHY THIS EXISTS. Deploy and backup runs intermittently die on
# "ssh: connect to host *** port ***: Connection timed out". The VPS side is
# clean - sshd up, no fail2ban, nothing logged - so the packets are being
# dropped before they arrive, and the only variable that changes between a
# run that works and a run that does not is the runner. Off-box backup #120
# failed and #121 succeeded four minutes apart, which killed the theory that
# the runner's Azure region predicts it: both were westus.
#
# That leaves the source address, and until now no run recorded it. A failed
# run could not be checked against a firewall or blocklist because nobody
# knew which address to look up. Now every SSH attempt is preceded by one
# line naming the address it is about to dial from.
#
# THIS MUST NEVER FAIL A DEPLOY. It is a diagnostic sitting in front of the
# step that does the real work, so every lookup is best-effort and the script
# exits 0 even when all of them fail - a deploy that cannot reach an IP echo
# service is not a deploy that should stop.
#
# Nothing here is secret: it prints the runner's own public IP, never the
# host, port, user or key.
#
# Usage:
#   ./log_runner_egress.sh
#   LOG_RUNNER_EGRESS_SELFTEST=1 ./log_runner_egress.sh
set -uo pipefail

# Any of these answering is enough; they are tried in order and the first
# plausible IPv4 wins. Three providers because one being down must not cost
# us the diagnostic on the very run we most want it for.
lookup() {
  curl -fsS --max-time 10 https://api.ipify.org 2>/dev/null && return 0
  curl -fsS --max-time 10 https://icanhazip.com 2>/dev/null && return 0
  dig +short +time=5 +tries=1 myip.opendns.com @resolver1.opendns.com 2>/dev/null && return 0
  return 1
}

main() {
  local ip
  ip="$(lookup | tr -d '[:space:]' || true)"

  if [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
    echo "runner_egress_ip=$ip"
    echo "If the SSH step below times out, this is the address to check"
    echo "against the VPS firewall / blocklist. Compare it with the address"
    echo "printed by a run that succeeded."
  else
    # Not an error. Say so plainly rather than printing a blank assignment
    # that would read as "the IP is empty".
    echo "runner_egress_ip=unknown (no IP echo service answered)"
  fi
  return 0
}

if [[ "${LOG_RUNNER_EGRESS_SELFTEST:-0}" = "1" ]]; then
  # Prove the failure path prints the unknown marker and still exits 0,
  # because that is the behaviour a deploy depends on.
  lookup() { return 1; }
  out="$(main)"
  rc=$?
  [[ $rc -eq 0 ]] || { echo "SELFTEST FAIL: exit $rc with no lookup"; exit 1; }
  case "$out" in
    *"runner_egress_ip=unknown"*) ;;
    *) echo "SELFTEST FAIL: expected unknown marker, got: $out"; exit 1 ;;
  esac
  echo "SELFTEST OK: no lookup -> '$out', exit 0"
  exit 0
fi

main
