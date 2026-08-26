# Play Store Readiness Checklist

Canonical, current checklist: **[frontend/PLAY_STORE_CHECKLIST.md](frontend/PLAY_STORE_CHECKLIST.md)**.

This root file used to claim debug-signing fallback, a placeholder icon, and
account deletion “via support”. Those statements are stale. Do not fill Play
Console from this page.

## Still operator / Play Console work (not claimed done in git)

1. Google Play developer account.
2. Release keystore on the operator’s machine (`android/key.properties`, never committed).
3. Paste privacy URL: `https://dguptaup0001000-oss.github.io/gp-store/privacy-policy.html`
4. Store listing assets (512 icon, feature graphic, screenshots).
5. Content rating + Data Safety form (use the table in `frontend/PLAY_STORE_CHECKLIST.md`).
6. Production `google-services.json` via `GOOGLE_SERVICES_JSON_BASE64` for Play CI.
7. Real `STORE_SUPPORT_*` on the VPS (production refuses placeholders).
