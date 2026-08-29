# Production close-out — operator checklist

Do this on the VPS as `PROD_USER`. Do **not** paste private keys, R2
secrets, JWT, MSG91 keys, or backup passphrases into chat or git.

Do **not** run `docker compose down -v`. Do **not** drop Postgres or Redis
volumes. Recreate **backend only** after env changes.

## 1. Repair GitHub Actions SSH (blocks deploy + backup-alert)

GitHub secret **`PROD_SSH_PRIVATE_KEY`** is the private half of the deploy
key. The matching **public** key must be a single line in:

| If `PROD_USER` is | File |
|---|---|
| `root` | `/root/.ssh/authorized_keys` |
| any other user | `/home/<PROD_USER>/.ssh/authorized_keys` |

Permissions:

```bash
install -d -m 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
# owner must be PROD_USER (not a copied root-owned file)
```

The public key is printed (safe) by:

1. Actions → **SSH access check** → Run workflow, or
2. The **Identify deploy public key** step on **Deploy Production** / **Backup alert**

Copy that `public_key=` line onto the VPS. Never copy the private key.

```bash
# After appending the public line:
stat -c '%a %U %n' ~/.ssh ~/.ssh/authorized_keys
ssh-keygen -lf ~/.ssh/authorized_keys
# Compare the SHA256 fingerprint to the Actions log. Do not cat the private key.
sshd -T | grep -E '^(passwordauthentication|pubkeyauthentication|permitrootlogin) '
```

Expected: `passwordauthentication no`, `pubkeyauthentication yes`.
If `PROD_USER` is root, `permitrootlogin` should be `prohibit-password` or
`without-password`, not `yes` with password.

Re-run **SSH access check**. Success prints `SSH_OK` and does not deploy.
Then re-run **Deploy Production** on `main`.

## 2. Put R2 variables into the file Compose actually reads

| Variable | Where | Required | May be blank | Secret |
|---|---|---|---|---|
| `R2_ACCOUNT_ID` | `/opt/gp-store/backend/.env` | Yes (or set `R2_ENDPOINT`) | No | Low |
| `R2_ENDPOINT` | same | Yes if account id omitted. HTTPS `https://<account>.r2.cloudflarestorage.com` | No if used | Low |
| `R2_ACCESS_KEY_ID` | same | Yes | No | **Yes** |
| `R2_SECRET_ACCESS_KEY` | same | Yes | No | **Yes** |
| `R2_BUCKET_NAME` | same | Yes | No | Low |
| `R2_PUBLIC_BASE_URL` | same | No | **Yes — leave empty** | No |

`/opt/gpstore/env-production` is **not** read by Docker. Adding
`env_file:` for it while Compose still has `R2_*: ${R2_*:-}` leaves R2
empty.

Safe merge (prints `copied`/`kept`/`blank` only):

```bash
cd /opt/gp-store
chmod +x deploy/production/merge-r2-env.sh
./deploy/production/merge-r2-env.sh /opt/gpstore/env-production /opt/gp-store/backend/.env
stat -c '%a %n' /opt/gp-store/backend/.env   # expect 600
# Confirm names only — no values:
grep -E '^R2_' /opt/gp-store/backend/.env | sed 's/=.*$/=/'
```

Then recreate **backend only**:

```bash
cd /opt/gp-store/backend
docker compose up -d --no-deps --no-build backend
```

Prefer a full `deploy/production/deploy.sh <origin/main SHA>` after SSH
works so the running image is `29959e4` or later (includes #124 + #125).

## 3. Verify SHA, health, Traefik, R2 (after deploy)

```bash
curl -fsS https://api.gpstore.co.in/v1/api/version
# gitCommit must equal: git -C /opt/gp-store rev-parse origin/main
curl -fsS https://api.gpstore.co.in/v1/api/health
curl -fsS https://api.gpstore.co.in/v1/actuator/health
docker inspect gpstore-backend-1 --format '{{.Config.Image}}'
# Expect gp-store-backend:<40-char sha>
```

Logs without credentials:

```bash
docker logs gpstore-backend-1 2>&1 | grep -E 'R2 object storage ready|R2 is not configured' | tail -n 5
```

Admin connection test (ADMIN JWT; do not paste the token into git):

```bash
curl -sS -X POST https://api.gpstore.co.in/v1/api/uploads/r2-connection-test \
  -H "Authorization: Bearer <ADMIN_JWT>"
# Expect uploaded=true verified=true deleted=true ok=true
```

Unauthenticated must stay 401:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  -X POST https://api.gpstore.co.in/v1/api/uploads/r2-connection-test
```

Anonymous object GET (replace with a real object URL only if you have one)
must not return 200. ListBucket is not required; the connection test uses
Head only.

## 4. MSG91 (OTP will fail closed until this is set)

Dashboard: Auth Key, OTP template ID, DLT sender `GPSTOR` (or your
approved header), 6-digit OTP. See `docs/MSG91_OTP_SETUP.md`.

Put values in `/opt/gp-store/backend/.env`, then recreate backend only
(same command as §2). Do not put Auth Key in Flutter.

## 5. Backups / DR

On-box sidecar writes `/backups/gpstore-*.dump` + `.sha256` + `status.txt`.
Off-box is GitHub workflow **Off-box backup** (needs SSH +
`BACKUP_GPG_PASSPHRASE`). Isolated restore already runs in that workflow
against throwaway DB `gpstore_offbox_probe` — that is **PASS** when the
workflow is green. A restore onto live `gpstore` is **UNVERIFIED** and
must not be done as a drill.

After SSH works, confirm a green **Off-box backup** run and a green
**Backup alert** run. Do not treat a skipped/failed deploy-triggered
off-box job as proof.

## 6. What this agent will not do

- Rotate or replace `PROD_SSH_PRIVATE_KEY`
- Ask you to paste a private key or R2 secret
- Recreate the whole stack
- Restore over the production database
