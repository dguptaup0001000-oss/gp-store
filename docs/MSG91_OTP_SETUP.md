# MSG91 OTP setup (GP-STORE)

This is the operator checklist for **production SMS OTP**. Do not put Auth Keys,
template secrets, or real OTP codes in git, Flutter, or chat logs.

GP-STORE is India-only. The backend normalises numbers to `91` + 10 digits
before calling MSG91. Flutter must never call MSG91.

## What the backend already does

- Password login is unchanged.
- Existing Flutter OTP paths still work for older clients: `POST /v1/api/auth/otp/send` and
  `POST /v1/api/auth/otp/verify` (LOGIN purpose, same JWT as password login).
- Current Flutter customer app uses the purpose-separated APIs:
  - `POST /v1/api/auth/otp/login/request` `{ "phone": "9876543210" }`
  - `POST /v1/api/auth/otp/login/verify` `{ "phone": "...", "otp": "123456" }`
  - `POST /v1/api/auth/password-reset/request`
  - `POST /v1/api/auth/password-reset/verify` → `{ "reset_token", "expires_in_seconds" }`
    (this token is **not** a login JWT and is held only in memory until complete)
  - `POST /v1/api/auth/password-reset/complete` `{ "reset_token", "new_password" }`
- LOGIN OTPs cannot reset a password. PASSWORD_RESET OTPs cannot log in.
- OTPs are never stored in plaintext. Production verification uses MSG91 when it is configured.
- Local/CI uses an in-memory mock (`MSG91_ENABLED=false`). The mock cannot
  start when `APP_PRODUCTION=true`; production without MSG91 credentials uses a
  fail-closed unconfigured provider so the shop can boot without fake SMS.

## Manual MSG91 / DLT steps

1. Create an MSG91 account at https://msg91.com
2. Complete business / KYC verification MSG91 requires for Indian SMS.
3. Complete applicable **DLT** registration (TRAI) for your entity, header
   (sender ID), and OTP template. Without DLT, Indian OTP SMS will not
   deliver reliably.
4. In MSG91, create an **OTP template** whose variables match what Send OTP
   expects (typically an `otp` placeholder). Keep the template to transactional
   OTP copy only.
5. Copy the **template ID**.
6. Copy the MSG91 **Auth Key** from the dashboard. Treat it like `JWT_SECRET`.
7. On the **VPS**, set these in **`/opt/gp-store/backend/.env`** (the file
   Docker Compose interpolates). `/opt/gpstore/env-production` is **not**
   read by the running backend. No quotes, no git commit:

   ```
   APP_PRODUCTION=true
   MSG91_ENABLED=true
   OTP_SMS_SENDING_ENABLED=true
   MSG91_AUTH_KEY=<auth key from MSG91>
   MSG91_OTP_TEMPLATE_ID=<template id>
   MSG91_TEMPLATE_ID=<same template id, legacy alias>
   MSG91_SENDER_ID=<DLT-approved header, e.g. GPSTOR>
   MSG91_BASE_URL=https://control.msg91.com
   ```

   You can keep using `OTP_SMS_SENDING_ENABLED` / `MSG91_TEMPLATE_ID` if those
   are already in that env file; `MSG91_ENABLED` and `MSG91_OTP_TEMPLATE_ID` alias them.

## MSG91 dashboard fields (do not paste secrets here)

| Dashboard field | What it must be |
|---|---|
| Auth Key | Copied into `MSG91_AUTH_KEY` on the VPS only. Never Flutter, never git. |
| OTP template / template ID | Transactional OTP template with an `otp` variable. Same ID in `MSG91_OTP_TEMPLATE_ID` and `MSG91_TEMPLATE_ID`. |
| Sender / header | DLT-approved 6-character header. Same value as `MSG91_SENDER_ID` (example in repo: `GPSTOR`). |
| OTP length | **6** digits (backend verify requires `^\d{6}$`). |
| OTP expiry | Backend sends `otp_expiry` **5** minutes (`OTP_EXPIRY_MINUTES`, capped 1–5). Dashboard default may be longer; the API query wins. |
| API used | Send OTP v5: `POST /api/v5/otp`, verify `GET /api/v5/otp/verify`. Not the OTP widget, not Flow. |
| IP allowlist (optional) | If you enable it, allow the VPS egress IP. Do not lock to GitHub Actions IPs. |
| Inbound webhook | Not used. Verification is server-to-MSG91. No callback URL to configure. |
| KYC / DLT | Required for Indian transactional OTP. Without DLT, send may return success and still not arrive. |
8. Redeploy. Production **starts** without MSG91 credentials; LOGIN and
   password-reset OTP then fail closed (generic send error, no mock, no
   universal code such as `123456`). Set the Auth Key and template ID when
   you have a real MSG91 account, then redeploy to enable SMS OTP.
9. Test real Indian delivery: request a LOGIN OTP to a handset you control.
10. Confirm verification: the same number + code logs in and returns the usual
    `token` / `refreshToken` body.
11. Test password reset: request → verify → complete with a new password of at
    least 8 characters. Confirm old refresh sessions stop working.
12. Test OTP login again after reset. Password login with the new password must
    still work.

## Safety notes

- Never put `MSG91_AUTH_KEY` in Flutter `--dart-define`, APKs, or client env.
- Never log OTP, Auth Key, passwords, or `reset_token`.
- Phone numbers in logs are masked as `******3210`.
- Customer-facing errors stay generic (`Unable to send OTP right now. Please try again.`).
- Official API references: [MSG91 OTP](https://docs.msg91.com/otp)
  (`POST /api/v5/otp`, `GET /api/v5/otp/verify`).
