# GP-STORE on Hostinger KVM 2 (production)

Production architecture after this migration:

```
Flutter APK  --HTTPS-->  api.gpstore.co.in
                              |
                         Cloudflare (optional, already configured)
                              |
                         Nginx :443 on the VPS
                              |
                         Spring Boot 127.0.0.1:8081  (context-path /v1)
                              |
                    Redis 127.0.0.1:6379   +   existing Supabase PostgreSQL
```

Hostinger is the VPS. It is not an SDK. The APK must never contain Hostinger
code; it only calls the HTTPS API.

This repository does **not** contain VPS passwords, SSH keys, or production
secrets. If those are missing, follow this file on the machine; do not invent
credentials.

Java / Spring versions (do not change them for this host):

- Java 21 (`backend/pom.xml` `<java.version>21</java.version>`)
- Spring Boot 3.5.3 (`backend/pom.xml` parent)
- Build: Maven Wrapper `backend/mvnw` (not Gradle)

Supabase is **not** migrated. Keep `DB_URL` pointed at the existing project
with `sslmode=require`.

Coded Flutter production host (override with GitHub `vars.API_BASE_URL` if
DNS differs): `https://api.gpstore.co.in/v1`.

---

## 1. Creating the VPS

1. Hostinger → VPS → **KVM 2**, **India** location (not Malaysia).
2. OS: **Ubuntu 24.04 LTS** (22.04 LTS is also fine).
3. Turn **daily auto-backup off** if you are paying extra for it and already
   have another backup plan; Hostinger panel snapshots remain available.
4. Note the public IPv4 address. Do not expose Redis or port 8081 in the
   Hostinger firewall panel.

## 2. Connecting through SSH

From your laptop (replace the IP):

```bash
ssh -i ~/.ssh/your_key root@YOUR_VPS_IP
```

Prefer SSH keys. After the first login, disable password SSH if keys work:

```bash
# /etc/ssh/sshd_config.d/gpstore.conf  (then: systemctl reload ssh)
PasswordAuthentication no
PermitRootLogin prohibit-password
```

## 3. Updating Ubuntu

```bash
sudo apt-get update
sudo apt-get upgrade -y
```

Or run the packaged installer (as root):

```bash
# from a clone of this repo on the VPS
sudo bash deploy/hostinger/install.sh
```

## 4. Installing Java

GP-STORE needs a **JRE 21** to run the jar, and a **JDK 21** only on the
machine that compiles it. Compiling on the VPS:

```bash
sudo apt-get install -y openjdk-21-jdk-headless
java -version    # openjdk 21
```

Runtime-only (if you upload a prebuilt jar):

```bash
sudo apt-get install -y openjdk-21-jre-headless
```

## 5. Installing Redis

```bash
sudo apt-get install -y redis-server
```

Edit `/etc/redis/redis.conf` using `deploy/hostinger/redis.conf.snippet`:

- `bind 127.0.0.1 -::1`
- `protected-mode yes`
- `requirepass` — generate with `openssl rand -base64 32` and put the **same**
  value in `/opt/gpstore/env.production` as `REDIS_PASSWORD`
- `maxmemory 512mb`
- `maxmemory-policy allkeys-lru`

```bash
sudo systemctl enable --now redis-server
sudo systemctl restart redis-server
redis-cli -h 127.0.0.1 -a "$REDIS_PASSWORD" ping   # PONG
```

Confirm it is not public:

```bash
ss -lntp | grep 6379
# must show 127.0.0.1:6379, not 0.0.0.0:6379
```

## 6. Installing Nginx

```bash
sudo apt-get install -y nginx
sudo systemctl enable --now nginx
```

Do not enable the TLS `server` block until certificates exist (step 14).

## 7. Installing Docker (optional)

Primary production is **systemd + host Redis + host Nginx**. Docker Compose
is optional (`deploy/hostinger/docker-compose.yml`) and still binds Redis and
Spring Boot to **127.0.0.1** only. If you use it:

```bash
sudo apt-get install -y docker.io docker-compose-v2
```

Do not publish `8081` or `6379` on `0.0.0.0`.

## 8. Uploading / cloning the backend

```bash
sudo mkdir -p /opt/gpstore
sudo useradd --system --create-home --home-dir /opt/gpstore --shell /usr/sbin/nologin gpstore || true
sudo chown -R gpstore:gpstore /opt/gpstore

# as a user who can git clone, then copy:
git clone https://github.com/dguptaup0001000-oss/gp-store.git /tmp/gp-store
cd /tmp/gp-store
```

The Spring Boot project is the `backend/` directory (Maven wrapper `mvnw`,
`Dockerfile`).

## 9. Configuring environment variables

```bash
sudo cp deploy/hostinger/env.production.example /opt/gpstore/env.production
sudo chmod 600 /opt/gpstore/env.production
sudo chown gpstore:gpstore /opt/gpstore/env.production
sudo -u gpstore nano /opt/gpstore/env.production
```

Fill every empty required value. Never commit this file.

Required for a real shop (names only):

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (Supabase, `sslmode=require`)
- `JWT_SECRET` (long random; not the repo default)
- `REDIS_PASSWORD` (matches Redis `requirepass`)
- `APP_PRODUCTION=true`, `FLYWAY_ENABLED=true`, `DDL_AUTO=validate`
- `RATE_LIMIT_TRUST_FORWARDED_FOR=true` (Nginx sets `X-Forwarded-For`)
- `CORS_ALLOWED_ORIGINS` if you serve Flutter web
- `STORE_LATITUDE` / `STORE_LONGITUDE` if not Malhia defaults
- Cashfree, MSG91, Firebase, Cloudinary as used today

Do **not** raise `DB_POOL_MAX_SIZE` (default 10) or `TOMCAT_MAX_THREADS`
(default 40) because the VPS has more RAM. Those ceilings match 2 vCPU and
the Supabase connection budget.

## 10. Building Spring Boot

On the VPS (JDK 21 installed):

```bash
cd /tmp/gp-store/backend
./mvnw -B clean package -DskipTests
sudo cp target/backend-0.0.1-SNAPSHOT.jar /opt/gpstore/backend.jar
sudo chown gpstore:gpstore /opt/gpstore/backend.jar
```

Tests (run before you switch DNS, from a machine with Docker for Testcontainers
if needed):

```bash
cd backend
./mvnw -B test
```

## 11. Starting the backend (first smoke, localhost only)

```bash
sudo -u gpstore bash -c 'set -a; source /opt/gpstore/env.production; set +a;
  exec java -XX:MaxRAMPercentage=35 -XX:ActiveProcessorCount=2 -XX:+UseG1GC \
    -jar /opt/gpstore/backend.jar'
```

In another SSH session:

```bash
curl -fsS http://127.0.0.1:8081/v1/api/health
curl -fsS http://127.0.0.1:8081/v1/api/health/ready
```

Liveness (`/v1/api/health`) is a short string and does not take a pool
connection. Stop the foreground process (Ctrl+C) once that works, then use
systemd.

## 12. Configuring systemd

`ExecStart` in the unit file expands variables from `EnvironmentFile`. Copy:

```bash
sudo cp deploy/hostinger/run-backend.sh /opt/gpstore/run-backend.sh
sudo chmod 0755 /opt/gpstore/run-backend.sh
sudo chown gpstore:gpstore /opt/gpstore/run-backend.sh
sudo cp deploy/hostinger/gpstore-backend.service /etc/systemd/system/gpstore-backend.service
sudo systemctl daemon-reload
sudo systemctl enable --now gpstore-backend
sudo systemctl status gpstore-backend --no-pager
```

Redis and Nginx must also be enabled (they are, if you used `enable --now`).

Automatic restart: `Restart=always`. Automatic start after reboot: `enable`.

## 13. Configuring Nginx

HTTP-only first (certificate issuance needs port 80):

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name api.gpstore.co.in;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo mkdir -p /var/www/certbot
sudo cp deploy/hostinger/nginx-gpstore.conf /etc/nginx/sites-available/gpstore
# temporarily comment the listen 443 server until certs exist, or use the
# HTTP-only block above as /etc/nginx/sites-available/gpstore
sudo ln -sf /etc/nginx/sites-available/gpstore /etc/nginx/sites-enabled/gpstore
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

Point DNS **A** record `api.gpstore.co.in` → VPS IPv4 (and Cloudflare orange
cloud only after you understand SSL mode: full/strict with the origin cert).

## 14. Installing HTTPS

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.gpstore.co.in
```

Then use the full `deploy/hostinger/nginx-gpstore.conf` (TLS block + headers)
and `sudo nginx -t && sudo systemctl reload nginx`.

HTTP must redirect to HTTPS. Spring Boot stays on 8081/localhost.

## 15. Checking logs

```bash
sudo journalctl -u gpstore-backend -f
sudo journalctl -u nginx -f
sudo journalctl -u redis-server -f
```

## 16. Checking the health endpoint

```bash
sudo bash deploy/hostinger/check-health.sh
# or:
curl -fsS http://127.0.0.1:8081/v1/api/health
curl -fsS https://api.gpstore.co.in/v1/api/health
```

Expect: `GP-STORE Backend Running Successfully!`

Ready (optional, may hit Postgres): `GET /v1/api/health/ready` → `{"status":"ready"}`.

Actuator `GET /v1/actuator/health` is also public (no details unless
authorized). Prometheus/metrics remain **ADMIN** JWT only — do not open them
on the firewall.

## 17. Restarting services

```bash
sudo systemctl restart gpstore-backend
sudo systemctl restart redis-server
sudo systemctl reload nginx
```

Graceful JVM stop: systemd `TimeoutStopSec=40` and
`server.shutdown=graceful` (already in `application.properties`).

## 18. Updating the application

```bash
cd /tmp/gp-store
git fetch origin
git checkout main
git pull origin main
cd backend
./mvnw -B clean package -DskipTests
sudo cp /opt/gpstore/backend.jar /opt/gpstore/backend.jar.prev
sudo cp target/backend-0.0.1-SNAPSHOT.jar /opt/gpstore/backend.jar
sudo systemctl restart gpstore-backend
curl -fsS http://127.0.0.1:8081/v1/api/health
```

GitHub Actions still builds APKs; it does **not** SSH to Hostinger. App
deploys are this procedure (or your own script wrapping it).

## 19. Rollback procedure

1. **Jar only** (new build fails health):

   ```bash
   sudo cp /opt/gpstore/backend.jar.prev /opt/gpstore/backend.jar
   sudo systemctl restart gpstore-backend
   ```

2. **DNS** (VPS is down, old host still exists): point `api.gpstore.co.in`
   back to the previous origin. After Render is deleted there is no old
   origin — keep the previous jar.

3. **`DDL_AUTO=validate` boot failure:** validate never writes. Set
   `DDL_AUTO=update` in `/opt/gpstore/env.production` only as a temporary
   emergency, restart, then fix the Flyway/entity mismatch. Do not rewrite
   `flyway_schema_history`. Do not restore
   `backend/docs/production-schema-reference.sql` as a bootstrap script.

4. **Redis:** cache/rate-limit only. Restart Redis; orders remain in
   Supabase.

## 20. Backup procedure

- **Database:** Supabase dashboard backups / PITR on your plan. This VPS
  does not host Postgres.
- **Secrets:** copy of `/opt/gpstore/env.production` in an offline password
  manager, not in git.
- **Jar:** keep `backend.jar.prev` on disk.
- **VPS disk:** Hostinger snapshot before a risky upgrade.
- **Redis:** optional `redis-cli -a ... BGSAVE`; losing Redis is a cold
  cache, not lost orders.

---

## Firewall (required)

```bash
sudo bash deploy/hostinger/ufw-setup.sh
```

Allow only 22, 80, 443. Spring Boot (8081) and Redis (6379) stay on
loopback.

## Cashfree / notifications after DNS

Webhook path (unchanged in code):

`POST https://<API-HOST>/v1/api/payments/webhooks/cashfree`

Set `CASHFREE_NOTIFY_URL` to that HTTPS URL and the same URL in the Cashfree
dashboard. Signature verification is unchanged.

Firebase / MSG91 have no Render callback URL. Put
`FIREBASE_CREDENTIALS_BASE64` and MSG91 keys in `env.production`. Rebuild the
APK only after `vars.API_BASE_URL` matches the live host.

## Cutover (do not skip)

1. Build and start on the VPS.
2. `curl` localhost health, then Nginx HTTP, then HTTPS.
3. Login, catalog, search, cart, checkout, payment webhook (Cashfree test),
   a notification, an admin action.
4. Then switch DNS / GitHub `API_BASE_URL` and rebuild APKs.
5. Keep the previous jar for rollback.

## Resource budget (KVM 2: 2 vCPU, 8 GB RAM)

| Process | Rough RAM |
|---|---|
| OS + journald | ~0.5–1 GB |
| Nginx | ~50 MB |
| Redis `maxmemory` | 512 MB |
| JVM heap (~35%) + metaspace/codecache | ~3.2 GB |
| Headroom | remainder |

Do not set Tomcat to 200 threads or Hikari to 40 on this machine.

## Flutter

One place: `frontend/lib/core/config/app_environment.dart`. Override:

```bash
flutter build apk --release \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.gpstore.co.in/v1
```

GitHub Actions uses `vars.API_BASE_URL`, falling back to that same host if
the variable is empty. Never fall back to `onrender.com`.
