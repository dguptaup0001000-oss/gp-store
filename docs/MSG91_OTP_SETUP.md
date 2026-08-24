# MSG91 OTP setup (GP-STORE)

This is the operator checklist for **production SMS OTP**. Do not put Auth Keys,
template secrets, or real OTP codes in git, Flutter, or chat logs.

GP-STORE is India-only. The backend normalises numbers to `91` + 10 digits
before calling MSG91. Flutter must never call MSG91.

## What the backend already does

- Password login is unchanged.
- Existing Flutter OTP paths still work: `POST /v1/api/auth/otp/send` and
  `POST /v1/api/auth/otp/verify` (LOGIN purpose, same JWT as password login).
- New purpose-separated APIs:
  - `POST /v1/api/auth/otp/login/request` `{ "phone": "9876543210" }`
  - `POST /v1/api/auth/otp/login/verify` `{ "phone": "...", "otp": "123456" }`
  - `POST /v1/api/auth/password-reset/request`
  - `POST /v1/api/auth/password-reset/verify` → `{ "reset_token", "expires_in_seconds" }`
    (this token is **not** a login JWT)
  - `POST /v1/api/auth/password-reset/complete` `{ "reset_token", "new_password" }`
- LOGIN OTPs cannot reset a password. PASSWORD_RESET OTPs cannot log in.
- OTPs are never stored in plaintext. Production verification uses MSG91.
- Local/CI uses an in-memory mock (`MSG91_ENABLED=false`). The mock cannot
  start when `APP_PRODUCTION=true`.

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
7. In **Render → Environment**, set (no quotes, no git commit):

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
   are already on Render; `MSG91_ENABLED` and `MSG91_OTP_TEMPLATE_ID` alias them.
8. Redeploy. If MSG91 is required in production but the Auth Key or template ID
   is missing, the app **refuses to start** (fail closed). It will not silently
   use the mock or a universal code such as `123456`.
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
