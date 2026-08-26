# Production auto-deploy (GitHub `main` → Hostinger VPS)

After one-time admin settings below, the routine flow is:

Cursor/agent PR → required CI green → automatic merge into `main` →
**Deploy Production** → VPS `reset --hard origin/main` → health + SHA checks.

Do not put SSH keys, database passwords, MSG91 keys, Cashfree keys, or JWT
secrets in git. Shop credentials stay in `backend/.env` on the VPS.

## Verified in this repository (2026-08-26)

| Check | Result |
|---|---|
| `push` to `main` starts **Deploy Production** | Yes. Run [32984341028](https://github.com/dguptaup0001000-oss/gp-store/actions/runs/32984341028) on `8627de1` (merge of #94), actor `cursor[bot]`. |
| That deploy succeeded | **No.** `Wait for backend CI` was skipped (job `if` referenced `inputs` on a push event). `Check deploy scripts` stayed queued. The run failed in 19s. No SSH step ran. |
| CI on merge SHA `8627de1` | **No CI run.** Deploy must accept successful PR CI on the merge commit's second parent. |
| Live `https://api.gpstore.co.in/v1/actuator/health` | `{"status":"UP"}` |
| Live `https://api.gpstore.co.in/v1/api/version` | **401** — production is not running the SHA-tagged backend from #94. |
| `allow_auto_merge` | **false** (this token cannot PATCH repository settings: HTTP 403) |
| Branch rulesets | **none** (`GET /rulesets` → `[]`) |
| Branch protection API | **403** Resource not accessible by integration |
| GitHub Actions secrets API | **403** — this token cannot create `PROD_*` secrets |
| SSH to `187.127.173.192` | **Permission denied** from this environment (no deploy key) |
| PR CI `startup_failure` | Yes, on this branch. `build-and-push-image` skipped immediately on non-main while `build-and-test` was still queued. Job `if` conditions now wait on `needs.*`. |

YAML files alone do not complete production. The remaining items are GitHub
settings and VPS secrets this token cannot write.

## GitHub settings an admin must enable once

This integration **cannot** change these (PATCH/PUT return 403). Do them in
the GitHub UI as the repo owner:

1. **Settings → Actions → General → Workflow permissions → Read and write permissions**  
   Without this, `GITHUB_TOKEN` cannot merge PRs (`contents: write` in YAML
   cannot escalate past a repository-level read-only default).
2. **Settings → Actions → General → Allow GitHub Actions to create and approve pull requests**  
   Required for Actions to operate on PRs in some orgs; this workflow still
   **does not approve** reviews.
3. **Settings → General → Pull Requests → Allow auto-merge**  
   Enables GitHub-native queued auto-merge. If this stays off, the
   **Auto-merge eligible PRs** workflow merges only after `build-and-test`
   and `schema-migrate` are green (it will not merge a failing PR).
4. Recommended: **Settings → Rules → Rulesets** (or classic branch
   protection) on `main`:
   - Require status checks: `build-and-test`, `schema-migrate`
   - Do **not** require the auto-merge job itself
   - Preserve required reviews if you want human review; the workflow
     will not merge when `reviewDecision` is `REVIEW_REQUIRED` or
     `CHANGES_REQUESTED`
   - Do **not** allow the Actions app to bypass the ruleset
5. **Settings → Secrets and variables → Actions** — create:

| Secret | Meaning |
|---|---|
| `PROD_HOST` | VPS IPv4 (`187.127.173.192`) |
| `PROD_USER` | SSH user that can run Docker on the VPS |
| `PROD_SSH_PRIVATE_KEY` | Dedicated deploy key (full PEM). Not the GitHub account password. |
| `PROD_PORT` | Optional. Defaults to `22`. |
| `PROD_APP_DIR` | Optional. Defaults to `/opt/gp-store`. |

Do **not** put database passwords, JWT secrets, MSG91, Cashfree, or
production `.env` values in GitHub. Those remain on the VPS.

Until (1) is on, PRs will not merge automatically (the Merge button stays).
Until (5) is on, **Deploy Production** will fail at "Require VPS SSH secrets"
and production will not update.

## What GitHub Actions does

### Auto-merge — `.github/workflows/enable-auto-merge.yml`

Runs on non-draft same-repo PRs into `main`, and when workflow **CI**
succeeds on a PR branch. It waits until `build-and-test` and
`schema-migrate` are green, refuses any failing check, then:

- `gh pr merge --auto --merge` when Allow auto-merge is on (GitHub performs
  the merge; that `push` starts Deploy Production), or
- `gh pr merge --merge` after CI is green when that setting is off, then
  `gh workflow run deploy-production.yml --ref main` as a backup because some
  GitHub App merges do not start every workflow on `main`.

### Deploy — `.github/workflows/deploy-production.yml`

1. Syntax-checks deploy scripts (never `docker compose down -v`).
2. Waits until workflow **CI** succeeds for the deploy SHA, or for the PR
   head parent of a merge commit.
3. SSHs to the VPS using GitHub secrets.
4. Runs `deploy/production/deploy.sh <GITHUB_SHA>`.
5. That script:
   - `git fetch origin`
   - `git checkout main`
   - `git reset --hard origin/main`
   - verifies `git rev-parse HEAD` == `GITHUB_SHA`
   - records the currently running backend image/SHA
   - builds `gp-store-backend:<sha>` while the old backend keeps running
   - recreates **only** the backend container (`docker compose up -d --no-deps --no-build backend`)
   - waits for `/v1/actuator/health` UP, `/v1/api/health`, `/v1/api/health/ready`
   - requires the running container healthy and in-container plus public
     `https://api.gpstore.co.in/v1/api/version` `gitCommit` == SHA
6. On failure after the new container is started, it rolls back to the
   previous SHA-tagged image, re-checks health, and **fails the job**.

A GitHub Actions deploy job is not success unless:

GitHub main SHA = VPS checkout SHA = running backend `gitCommit`

It never runs `docker compose down` / `docker compose down -v`. Postgres and
Redis volumes stay. Traefik is not restarted.

## One-time VPS commands

If production already runs from another directory (for example `~/gp-store`),
either set `PROD_APP_DIR` / `DEPLOY_ROOT` to that path **or** move the
checkout to `/opt/gp-store` **without** recreating Docker volumes. The
Compose project name is `gpstore`; volume names must stay
`gpstore_gpstore_pg_data` and `gpstore_gpstore_redis_data`.

Create the deploy key on a trusted machine (not in this repo):

```bash
ssh-keygen -t ed25519 -f gpstore-deploy -C "gp-store-github-deploy" -N ""
# private → GitHub secret PROD_SSH_PRIVATE_KEY
# public  → VPS ~/.ssh/authorized_keys for PROD_USER
```

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
curl -fsS https://api.gpstore.co.in/v1/api/health
curl -fsS https://api.gpstore.co.in/v1/api/health/ready
```

On the VPS:

```bash
git -C /opt/gp-store rev-parse HEAD
docker image ls gp-store-backend
cat /var/lib/gp-store/deployment-state
```

## Emergency manual run

Same script GitHub uses. Does not skip health/version checks.

```bash
cd /opt/gp-store
sudo TARGET_SHA="$(git rev-parse origin/main)" ./deploy/production/deploy.sh "$TARGET_SHA"
```

Or Actions → **Deploy Production** → Run workflow on `main` (only needed if
secrets were missing on the automatic run).

## MSG91 / Cashfree / Firebase

Deploy succeeds if the process is healthy even when OTP, Cashfree webhook
secret, or FCM are unset. Those stay fail-closed in the app. Fill them in
`backend/.env` on the VPS when you have real values. Never commit them.
