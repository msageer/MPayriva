# M-Sageer Pay — Integration, Testing & Deployment Guide

Operated by **M-SAGEER DIGITAL TECHNOLOGIES LTD**

## 1. Architecture Summary

- **Android Client (`/app`)**: Native Kotlin, Jetpack Compose, Material Design 3, MVVM + Repository pattern, Google Sign-In via Jetpack Credential Manager, Cloud Firestore real-time listeners, and Retrofit HTTPS calls to the trusted backend.
- **Database (`/firebase-blueprint.json`, `/firestore.rules`)**: Provisioned Firestore Enterprise database (`ai-studio-android-msageerp-1cabd974-084b-4311-b76c-3034d7a0e5a6`) with zero-trust security rules:
  - Clients **cannot** write to `walletBalanceKobo`, `/transactions`, `/ledgerEntries`, `/providerLogs`, or `/admins`.
  - All monetary values use exact integer **kobo** (`100 kobo = ₦1.00`).
- **Trusted Backend (`/backend`)**: Node.js TypeScript service deployable to **Google Cloud Run** or **Firebase Cloud Functions**, handling:
  - Server-side Firebase ID Token and 4-digit Transaction PIN verification.
  - Atomic double-entry financial ledger with idempotency key locking.
  - **ClubKonnect (Nellobyte Systems)** official HTTPS GET endpoints (`APIAirtimeV1.asp`, `APIDatabundleV1.asp`, `APIVerifyElectricityV1.asp`, `APIElectricityV1.asp`, `APIVerifyCableTVV1.asp`, `APICableTVV1.asp`, `APIWAECV1.asp`, `APIJAMBV1.asp`, `APIEPINV1.asp`, `APIQueryV1.asp`, `APIWalletBalanceV1.asp`).
  - **Flutterwave v3** official API (`POST /v3/virtual-account-numbers`, `POST /v3/payments`, `GET /v3/transactions/:id/verify`) with constant-time `verif-hash` webhook verification.
  - **Firebase Cloud Messaging (FCM)** backend event notifications.

---

## 2. Required External Credentials & Regulatory Notes

Configure these secrets **exclusively in Google Cloud Secret Manager / Cloud Run environment variables** (never in Android `BuildConfig` or APK):

| Variable | Provider | Purpose |
| --- | --- | --- |
| `CLUBKONNECT_USER_ID` | ClubKonnect | Merchant UserID from clubkonnect.com |
| `CLUBKONNECT_API_KEY` | ClubKonnect | Secret API key for utility vending & requery |
| `FLUTTERWAVE_SECRET_KEY` | Flutterwave v3 | Server-side secret key (use `FLWSECK_TEST-...` for sandbox) |
| `FLUTTERWAVE_WEBHOOK_HASH` | Flutterwave v3 | Secret hash matching `verif-hash` header in dashboard |
| `BACKEND_CALLBACK_BASE_URL` | Cloud Run / Functions | Public HTTPS URL of your deployed backend |

### Nigerian Regulatory & Stored-Value Wallet Notice
Payment collection via Flutterwave does **not** automatically confer a CBN Mobile Money Operator (MMO) or PSP stored-value wallet license. Before public launch in Nigeria, ensure dedicated virtual accounts and customer wallet float custody are operated in partnership with a CBN-licensed bank / MMO / settlement partner and comply with the **Nigeria Data Protection Act (NDPA)** and **CBN KYC/BVN tier limits**.

---

## 3. Deploying the Trusted Backend

### Option A: Render (Free Tier — Recommended Quick Start)

This repository includes a ready-to-deploy **`/render.yaml`** Blueprint at the root:

1. **Push your project to GitHub** (from AI Studio settings menu → Push to GitHub).
2. **Generate a Firebase Service Account Key** (for project `gen-lang-client-0034150788`):
   - Open [Firebase Console](https://console.firebase.google.com/) → **Project Settings → Service Accounts** → **Generate new private key** (downloads a `.json` file).
3. **Create the service on [Render](https://render.com)**:
   - Click **New → Blueprint** (or **New → Web Service** pointing `Root Directory` to `backend`, `Build Command` to `npm install --include=dev && npm run build`, and `Start Command` to `npm start`).
   - Select your GitHub repository. Render automatically detects `render.yaml` and uses the **Free** plan.
4. **Set Environment Variables in Render Dashboard**:
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — paste the entire contents of the Firebase service account `.json` file (raw JSON or base64 both work).
   - `CLUBKONNECT_USER_ID` — your ClubKonnect merchant UserID.
   - `CLUBKONNECT_API_KEY` — your ClubKonnect API key.
   - `FLUTTERWAVE_SECRET_KEY` — your Flutterwave secret key (`FLWSECK_TEST-...` for sandbox).
   - `FLUTTERWAVE_WEBHOOK_HASH` — your chosen Flutterwave webhook secret hash.
   - *(Optional)* `BACKEND_CALLBACK_BASE_URL` — Render automatically sets `RENDER_EXTERNAL_URL` (e.g. `https://msageer-pay-backend.onrender.com`), which the backend uses automatically if `BACKEND_CALLBACK_BASE_URL` is left blank.
5. **Connect Render to Your Android App & Flutterwave**:
   - Wait for Render to show **Live** and test `https://<your-service>.onrender.com/health`.
   - Sign in as admin (`msagirgroup@gmail.com`) in the Android app, open **API & Providers**, paste `https://<your-service>.onrender.com` into **Backend HTTPS Base URL**, and tap **Save API Configuration** (no APK rebuild needed).
   - Set your Flutterwave Dashboard Webhook URL to `https://<your-service>.onrender.com/webhooks/flutterwave` with the same `FLUTTERWAVE_WEBHOOK_HASH`.
   - Tap **Query Backend Provider Health** inside **Admin Operations → API & Providers** to verify end-to-end connectivity.

### Option B: Google Cloud Run (Optional Alternative)

```bash
cd backend
npm install
npm run build

gcloud run deploy msageer-pay-backend \
  --source . \
  --region europe-west2 \
  --allow-unauthenticated \
  --set-env-vars="APP_ENVIRONMENT=SANDBOX,FIRESTORE_DATABASE_ID=ai-studio-android-msageerp-1cabd974-084b-4311-b76c-3034d7a0e5a6"
```

After deployment (uses `/backend/Dockerfile` automatically via `--source .`):
1. Sign in as admin (`msagirgroup@gmail.com`) in the Android app, open **API & Providers**, enter your HTTPS URL, and tap **Save API Configuration**.
2. Configure Flutterwave Dashboard Webhook URL to `<BACKEND_URL>/webhooks/flutterwave` with your `FLUTTERWAVE_WEBHOOK_HASH`.
3. Tap **Query Backend Provider Health** and **Sync Live ClubKonnect Catalog Costs** inside **Admin Operations → API & Providers**.
4. For Firebase Cloud Messaging (FCM) push notifications or Email/Password/Phone OTP auth providers, enable them in the Firebase Console for project `gen-lang-client-0034150788`.

### Implemented REST API Endpoints (`/backend/src/index.ts`)

| Method & Path | Auth | Purpose |
| --- | --- | --- |
| `GET /health` | Public | Cloud Run readiness/liveness probe |
| `POST /api/v1/wallet/virtual-account` | Bearer (Firebase ID Token) | Provisions or fetches dedicated Flutterwave NUBAN |
| `POST /api/v1/wallet/fund-card` | Bearer | Initiates Flutterwave v3 hosted card/bank checkout |
| `POST /api/v1/wallet/verify-funding` | Bearer | Independently verifies Flutterwave reference & credits wallet idempotently |
| `POST /api/v1/verify/meter` | Bearer | ClubKonnect electricity meter name/address lookup |
| `POST /api/v1/verify/smartcard` | Bearer | ClubKonnect Cable TV smartcard/IUC customer lookup |
| `POST /api/v1/transactions/purchase` | Bearer + 4-digit PIN | Atomic double-entry debit + ClubKonnect utility fulfillment |
| `POST /api/v1/transactions/:reference/requery` | Bearer (Owner or Admin) | Reconciles pending order via ClubKonnect `APIQueryV1.asp` |
| `GET/POST /webhooks/clubkonnect` | Server Requery | Async callback trigger (verifies authoritatively via `APIQueryV1.asp`) |
| `POST /webhooks/flutterwave` | `verif-hash` + Verify API | Signature-checked deposit webhook with independent Flutterwave verification |
| `GET /api/v1/admin/provider-health` | Bearer + Admin | Queries ClubKonnect wallet balance & provider readiness |
| `POST /api/v1/admin/transactions/:reference/reconcile` | Bearer + Admin | Reconciles any customer's pending transaction |
| `POST /api/v1/admin/catalog/sync-clubkonnect` | Bearer + Admin | Syncs live ClubKonnect v2 data/cable/WAEC/JAMB provider costs |

---

## 4. Verification & Testing Suite

- **Backend Pricing & Provider Status Tests**:
  ```bash
  cd backend && npm test
  ```
- **Firestore Security Rules Tests** (Port 8085 single-database emulator):
  ```bash
  FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
  ```
- **Android Unit & Robolectric Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **Release APK & Google Play App Bundle (AAB)**:
  ```bash
  gradle :app:assembleRelease
  gradle :app:bundleRelease
  ```
