# CLAUDE.md: Apex Fit project guide

Read this first. It tells you what exists, what was actually tested, and what is still open.
The owner is a beginner and non-developer who speaks Egyptian Arabic. When you talk to him, use simple Egyptian Arabic,
one small step at a time, and ask for screenshots when he is stuck. Never ask him to paste passwords or secret keys in chat.

## What this is
**Apex Fit** is an Arabic (RTL, Egyptian dialect) fitness web app by the company **Apex** (built by Mahmoud Roshdy).
Free: body-fat estimate, BMI, daily calories and macros, a general weekly workout table, a rep counter/timer, ads.
PRO: detailed meal plan, 3 training styles (calisthenics, home, gym) x 3 levels x 4 body shapes, camera form coach,
30-day challenge, weekly shopping list, workout reminder (.ics), progress tracker, AI assistant, no ads.

- Repo: `APEX-RECRUITMENT-AGENCY/APEX-FIT` (GitHub Pages, branch `main`, root)
- Live: https://apex-recruitment-agency.github.io/APEX-FIT/
- Worker URL: https://apex-assistant.rooshdy15.workers.dev (Cloudflare, no trailing slash in code)
- Firebase project: `apex-fit-8d951` (Auth: Email/Password + Google)

## Layout
```
index.html            the whole site (single file: HTML + CSS + inline JS, no build step)
apex-fit-logo.svg     logo (peak-shaped A with a dumbbell bar)
README.md             public GitHub README (EN/AR)
worker/worker.js      Cloudflare Worker: AI assistant, Paymob checkout, status, webhook
worker/wrangler.toml  optional deploy config with placeholders (see warning inside)
android/              Kotlin files + manifest for the app-blocker app (NOT a full Android Studio project)
tools/check_js.py     syntax check for index.html inline script and worker.js (needs Node)
```
Run `python3 tools/check_js.py` after every edit. There is no other test suite.

## index.html: key facts
- Constants near the top of the PRO script: `FIREBASE_CONFIG` (public web config, fine to keep in the repo), `AI_URL` (Worker base URL),
  `PAY_URL` (Paymob payment link, currently a TEST link), `CODE` (manual PRO code `PRO-2026`), `ADSENSE_CLIENT`/`ADSENSE_SLOT` (empty), `TRIAL_DAYS=7`.
- Access model: `plan()` returns `pro` / `trial` / `free`. Pro = `localStorage.fc_pro==='1'` (manual code) OR server subscription (`proUntil` from Worker `/status`).
  Trial = `localStorage.fc_trial` start time + 7 days (PRO features visible, ads still shown). Ads are hidden only for `pro`.
- localStorage keys: `fc_pro`, `fc_trial`, `fc_pref`, `fc_log`, `fc_ch`.
- Camera coach: MediaPipe Pose Landmarker (`@mediapipe/tasks-vision@0.10.14` via jsDelivr ESM `import()`; model file from storage.googleapis.com).
  Squat/push-up/plank logic from joint angles with thresholds in the camera IIFE. Runs on-device.
- Lock mode: `?lock=1&ex=squat|pushup|plank&n=10` moves the camera card to the top, hides the rest, and calls `window.Android.workoutDone()/grace()/skipToday()` if present.
- AI assistant: inside claude.ai it uses the `claude.use('sample')` capability; elsewhere it POSTs to `AI_URL` with the Firebase ID token.
- Typography rule used in the UI strings: Arabic text, Egyptian dialect, numbers shown in Arabic-Indic digits via `n()`/`ar()`.
- The page is also published as a claude.ai artifact preview (`https://claude.ai/artifact/RXmCBSLQQo7WbfKCBH2orb`). That copy cannot use Firebase scripts, the camera, or the Worker. The real deployment is GitHub Pages.

## Worker (worker/worker.js)
Routes (POST unless noted): `/` AI chat (login required), `/checkout` (creates a Paymob intention for the logged-in user, returns the checkout URL),
`/status` (subscription end date), `/webhook` (Paymob callback, HMAC-SHA512 verified, grants time and stores it in KV).
Plans and prices live ONLY in the Worker: `m1` 99 EGP/30d, `m3` 249 EGP/90d, `y1` 899 EGP/365d (the page shows the same numbers; keep them in sync).
Needs: bindings `AI` (Workers AI) and `SUBS` (KV); variables `FIREBASE_API_KEY`, `ALLOWED_ORIGIN`, `SITE_URL`, `PAYMOB_PUBLIC_KEY`, `PAYMOB_INTEGRATION_ID`;
secrets `PAYMOB_SECRET_KEY`, `PAYMOB_HMAC`. Never commit secret values.
AI models: Cloudflare retired the Llama 3.1 models on 2026-05-30. The Worker tries a fallback list (`glm-4.7-flash`, `gemma-4-26b-a4b-it`, `llama-3.2-3b-instruct`).
Model names change over time; check the Workers AI catalog when you see errors (the site now shows the Worker error text in the chat bubble).

## Status: what was tested vs not
Tested by the owner on a real phone/desktop: GitHub Pages deploy, Firebase login, Paymob test checkout with the plain payment link, the AI assistant reaching the Worker (it failed on a deprecated model, since replaced).
**Not tested at all** (written from docs, only syntax-checked): Worker `/checkout` + `/webhook` + `/status` end to end; camera coach accuracy and thresholds; lock mode; Android app (never compiled).

## Open items and risks (suggested order)
1. Verify the new Worker AI fallback models answer in Arabic (after redeploy of `worker.js`).
2. Paymob end to end in TEST mode: create the KV namespace + binding, set secrets, pay with the test card, check the Worker logs.
   Unverified guess: how `special_reference` comes back in the callback (code tries `order.merchant_order_id`, `payment_key_claims.extra`, then an `ord:` KV mapping). Read a real callback and fix the lookup.
   `billing_data.phone_number` is a placeholder; ask the user for a phone number if Paymob rejects it.
3. Go live with Paymob (Live keys + live card integration id; test and live must match).
4. PRO gating is client-side: content is in the same HTML and `fc_pro`/the code can be bypassed with dev tools. Decide whether that is acceptable or move PRO content behind the Worker.
5. The manual code `PRO-2026` is visible in the page source. Change or remove it before real sales.
6. Camera coach: tune angle thresholds with real videos (squat knee <=110, push-up elbow <=100, plank body >=160).
7. Android app: create the Android Studio project (see `android/README_ANDROID.md`), fix compile errors, test on Xiaomi/Samsung (battery killers), then Play requirements.
8. AdSense: needs approval, privacy policy / about / contact pages, `ads.txt` at the domain root. A custom domain is strongly recommended (github.io project sites do not own the root).
9. Trial is per browser (localStorage). A per-account trial needs Firestore + security rules.
10. Add privacy policy page (camera processed on-device, localStorage, Firebase Auth, ads).

## Safety rules (keep these)
- Health numbers are estimates; the page says it is not medical advice. Keep that notice.
- Calorie floors 1200 (female) / 1500 (male). The AI system prompt forbids diagnosis and tells users to see a doctor for symptoms.
- App blocker: calls, emergency and settings must never be blockable; always keep the emergency pause and the skip button; never block uninstalling; no Accessibility Service (Google Play restricts it).
- Camera video never leaves the device. Do not add any upload of frames.

## Suggested first prompt for the new developer
"Read CLAUDE.md. Run tools/check_js.py. Then help me finish item 2 (Paymob test flow) step by step, asking me before you touch any secret."
