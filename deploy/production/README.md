# Production auto-deploy (GitHub `main` → Hostinger VPS)

After one-time setup, a push to `main` deploys the backend. Do not put
SSH keys, database passwords, MSG91 keys, Cashfree keys, or JWT secrets
in git.

## What GitHub Actions does

Workflow: `.github/workflows/deploy-production.yml`

1. Waits until workflow **CI** succeeds for that commit (backend tests + schema-migrate).
2. SSHs to the VPS using GitHub secrets.
3. Runs `deploy/production/deploy.sh <GITHUB_SHA>`.
4. That script:
   - `git fetch origin`
   - `git checkout main`
   - `git reset --hard origin/main`
   - verifies `git rev-parse HEAD` == `GITHUB_SHA`
   - builds `gp-store-backend:<sha>` while the old backend keeps running
   - recreates **only** the backend container
   - waits for `/v1/actuator/health` UP, `/v1/api/health`, `/v1/api/health/ready`
   - requires in-container and public `https://api.gpstore.co.in/v1/api/version` `gitCommit` == SHA
5. On failure after the new container is started, it rolls back to the
   previous SHA-tagged image.

It never runs `docker compose down -v`. Postgres and Redis volumes stay.
Traefik is not restarted.

## GitHub secrets (required)

| Secret | Meaning |
|---|---|
| `PROD_HOST` | VPS IPv4 (currently `187.127.173.192`, stored as a secret, not in app code) |
| `PROD_USER` | SSH user that can run Docker on the VPS |
| `PROD_SSH_PRIVATE_KEY` | Dedicated deploy key (full PEM). Not the GitHub account password. |
| `PROD_PORT` | Optional. Defaults to `22`. |
| `PROD_APP_DIR` | Optional. Defaults to `/opt/gp-store`. |

Create the key on a trusted machine (not in this repo):

```bash
ssh-keygen -t ed25519 -f gpstore-deploy -C "gp-store-github-deploy" -N ""
# private → GitHub secret PROD_SSH_PRIVATE_KEY
# public  → VPS ~/.ssh/authorized_keys for PROD_USER
```

## One-time VPS commands

If production already runs from another directory (for example `~/gp-store`),
either set `PROD_APP_DIR` / `DEPLOY_ROOT` to that path **or** move the
checkout to `/opt/gp-store` **without** recreating Docker volumes. The
Compose project name is `gpstore`; volume names must stay
`gpstore_gpstore_pg_data` and `gpstore_gpstore_redis_data`.

```bash
sudo bash deploy/production/prepare-vps.sh
# If the repo already existed elsewhere, copy backend/.env into
# $DEPLOY_ROOT/backend/.env (gitignored).
sudo -u "$USER" mkdir -p ~/.ssh
# append the deploy public key to ~/.ssh/authorized_keys
```

The deploy user must be able to run `docker compose` (docker group).

## Verify a deploy

```bash
curl -fsS https://api.gpstore.co.in/v1/api/version
# gitCommit must equal: git rev-parse origin/main
curl -fsS https://api.gpstore.co.in/v1/actuator/health
```

On the VPS:

```bash
docker image ls gp-store-backend
cat /var/lib/gp-store/deployment-state
```

## Emergency manual run

Same script GitHub uses. Does not skip health/version checks.

```bash
cd /opt/gp-store
sudo TARGET_SHA="$(git rev-parse origin/main)" ./deploy/production/deploy.sh "$TARGET_SHA"
```

Or Actions → **Deploy Production** → Run workflow.

## MSG91 / Cashfree / Firebase

Deploy succeeds if the process is healthy even when OTP, Cashfree webhook
secret, or FCM are unset. Those stay fail-closed in the app. Fill them in
`backend/.env` on the VPS when you have real values. Never commit them.
