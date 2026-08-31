#!/usr/bin/env bash
# Read-only SSH timeout diagnostics. Safe to pipe over ssh: bash -s.
# Does not change sshd, fail2ban, ufw, or any other service.
set +e

echo "===== remote_utc ====="
date -u +%Y-%m-%dT%H:%M:%SZ
echo "===== whoami / host ====="
id
hostname

echo "===== fail2ban-client status sshd ====="
if command -v fail2ban-client >/dev/null; then
  fail2ban-client status sshd
else
  echo "fail2ban-client missing"
fi

echo "===== fail2ban sshd tunables ====="
if command -v fail2ban-client >/dev/null; then
  fail2ban-client get sshd bantime
  fail2ban-client get sshd findtime
  fail2ban-client get sshd maxretry
  fail2ban-client get sshd actions
  fail2ban-client get sshd banip
fi

echo "===== jail file ====="
if [ -f /etc/fail2ban/jail.d/gpstore-sshd.conf ]; then
  sed -n '1,80p' /etc/fail2ban/jail.d/gpstore-sshd.conf
else
  echo "no gpstore-sshd.conf"
fi

echo "===== fail2ban Ban/Unban last 100 ====="
if ls /var/log/fail2ban.log* >/dev/null 2>&1; then
  zgrep -h -E "Ban |Unban " /var/log/fail2ban.log* 2>/dev/null | sort | tail -n 100
else
  echo "no /var/log/fail2ban.log*"
fi

echo "===== fail2ban.log 2026-08-31 06-07 UTC ====="
if [ -f /var/log/fail2ban.log ]; then
  grep -E "2026-08-31 0[67]:" /var/log/fail2ban.log || echo "no matches in current fail2ban.log"
else
  echo "no current fail2ban.log"
fi
zgrep -h -E "2026-08-31 0[67]:" /var/log/fail2ban.log* 2>/dev/null | sort | tail -n 200

echo "===== journalctl fail2ban 06:30-08:00 UTC ====="
journalctl -u fail2ban --since "2026-08-31 06:30" --until "2026-08-31 08:00" --utc --no-pager

echo "===== ufw status verbose ====="
ufw status verbose 2>/dev/null || echo "ufw not present or not permitted"

echo "===== iptables -S ====="
iptables -S 2>/dev/null | head -n 120

echo "===== iptables -L -n -v (first 120) ====="
iptables -L -n -v 2>/dev/null | head -n 120

echo "===== nft list ruleset (first 80) ====="
nft list ruleset 2>/dev/null | head -n 80 || echo "nft not present"

echo "===== sshd -T selected ====="
sshd -T 2>/dev/null | grep -Ei "maxstartups|maxsessions|logingracetime|maxauthtries|passwordauthentication|permitrootlogin|port "

echo "===== uptime / reboot ====="
uptime
who -b 2>/dev/null
last -x reboot 2>/dev/null | head -n 8

echo "===== journalctl warning 06:30-08:00 UTC ====="
journalctl --since "2026-08-31 06:30" --until "2026-08-31 08:00" --utc -p warning --no-pager | tail -n 80

echo "===== dmesg oom/network ====="
dmesg -T 2>/dev/null | grep -Ei "oom|killed process|network|link is down" | tail -n 40

echo "===== systemctl ssh ====="
systemctl status ssh sshd --no-pager -l 2>/dev/null | head -n 60

echo "===== journalctl ssh 06:30-08:00 UTC ====="
journalctl -u ssh -u sshd --since "2026-08-31 06:30" --until "2026-08-31 08:00" --utc --no-pager | tail -n 120

echo "===== df ====="
df -h
df -i

echo "===== auth.log failed 06:00-08:00 UTC (counts, not passwords) ====="
for f in /var/log/auth.log /var/log/auth.log.1; do
  [ -f "$f" ] || continue
  echo "-- $f --"
  grep -E "2026-08-31T0[67]:" "$f" 2>/dev/null | grep -E "Failed|Invalid user|Disconnected|Ban|MaxStartups|refused" | awk '{print $1}' | cut -c1-16 | uniq -c | tail -n 40
done

echo "===== sshd_config.d/99-gpstore.conf ====="
if [ -f /etc/ssh/sshd_config.d/99-gpstore.conf ]; then
  sed -n '1,40p' /etc/ssh/sshd_config.d/99-gpstore.conf
else
  echo "no drop-in"
fi

echo "===== listening ssh ====="
ss -tlnp 2>/dev/null | grep -E 'ssh|:22|:2[0-9]{3}' || netstat -tlnp 2>/dev/null | grep -E 'ssh|:22'

echo "===== DIAG_DONE ====="
date -u +%Y-%m-%dT%H:%M:%SZ
