import crypto from "crypto";
import admin from "firebase-admin";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

/**
 * Parses a Firebase Service Account from either raw JSON or base64-encoded JSON.
 * Supports non-GCP hosts like Render, Railway, Fly.io, and VPS deployments.
 */
export function parseServiceAccountEnv(raw?: string, base64?: string): admin.ServiceAccount | null {
  let jsonText = raw?.trim() || "";
  if (!jsonText && base64?.trim()) {
    jsonText = Buffer.from(base64.trim(), "base64").toString("utf8").trim();
  }
  if (!jsonText) return null;

  // Allow users to paste either raw JSON or base64 into FIREBASE_SERVICE_ACCOUNT_JSON
  if (!jsonText.startsWith("{")) {
    try {
      const decoded = Buffer.from(jsonText, "base64").toString("utf8").trim();
      if (decoded.startsWith("{")) {
        jsonText = decoded;
      }
    } catch {
      // Fall through to JSON.parse validation below
    }
  }

  const parsed = JSON.parse(jsonText) as Record<string, unknown>;
  const projectId = String(parsed.project_id || parsed.projectId || "").trim();
  const clientEmail = String(parsed.client_email || parsed.clientEmail || "").trim();
  const privateKeyRaw = String(parsed.private_key || parsed.privateKey || "").trim();

  if (!projectId || !clientEmail || !privateKeyRaw) {
    throw new Error(
      "FIREBASE_SERVICE_ACCOUNT_JSON is missing required fields (project_id, client_email, private_key)."
    );
  }

  return {
    projectId,
    clientEmail,
    privateKey: privateKeyRaw.replace(/\\n/g, "\n"),
  };
}

if (!admin.apps.length) {
  const serviceAccount = parseServiceAccountEnv(
    process.env.FIREBASE_SERVICE_ACCOUNT_JSON,
    process.env.FIREBASE_SERVICE_ACCOUNT_BASE64
  );
  if (serviceAccount) {
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount),
      projectId: serviceAccount.projectId,
    });
  } else {
    // Fallback to GOOGLE_APPLICATION_CREDENTIALS or GCP Application Default Credentials
    admin.initializeApp();
  }
}

export const FIRESTORE_DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-msageerp-1cabd974-084b-4311-b76c-3034d7a0e5a6";

export const db = getFirestore(admin.app(), FIRESTORE_DATABASE_ID);
export const authAdmin = admin.auth();
export const messagingAdmin = admin.messaging();

export const CONFIG = {
  environment: process.env.APP_ENVIRONMENT || "SANDBOX",
  callbackBaseUrl: (process.env.BACKEND_CALLBACK_BASE_URL || process.env.RENDER_EXTERNAL_URL || "").replace(/\/+$/, ""),
  clubKonnectUserId: process.env.CLUBKONNECT_USER_ID || "",
  clubKonnectApiKey: process.env.CLUBKONNECT_API_KEY || "",
  flutterwaveSecretKey: process.env.FLUTTERWAVE_SECRET_KEY || "",
  flutterwaveWebhookHash: process.env.FLUTTERWAVE_WEBHOOK_HASH || "",
  flutterwaveRedirectUrl: process.env.FLUTTERWAVE_REDIRECT_URL || "",
  bootstrappedAdminEmail: "msagirgroup@gmail.com",
};

export function hashPin(pin: string, saltHex: string): string {
  return crypto
    .createHash("sha256")
    .update(`MSAGEER_PIN_V1:${saltHex}:${pin}`, "utf8")
    .digest("hex");
}

export function timingSafeEqualStr(a: string, b: string): boolean {
  const bufA = Buffer.from(a, "utf8");
  const bufB = Buffer.from(b, "utf8");
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

export async function logProviderEvent(params: {
  provider: "CLUBKONNECT" | "FLUTTERWAVE" | "SYSTEM";
  action: string;
  reference: string;
  statusCode: string;
  outcome: "SUCCESS" | "PENDING" | "ERROR";
  message: string;
}) {
  await db.collection("providerLogs").add({
    ...params,
    createdAt: FieldValue.serverTimestamp(),
  });
}

export async function notifyCustomer(params: {
  userId: string;
  title: string;
  body: string;
  type: "WALLET_FUNDING" | "PURCHASE_SUCCESS" | "PURCHASE_FAILED" | "TRANSACTION_PENDING" | "REFUND" | "ANNOUNCEMENT";
  reference?: string;
}) {
  const payload: Record<string, unknown> = {
    userId: params.userId,
    title: params.title,
    body: params.body,
    type: params.type,
    read: false,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  };
  if (params.reference) payload.reference = params.reference;
  await db.collection("notifications").add(payload);

  // Dispatch FCM push notification if the customer registered an fcmToken
  const userSnap = await db.collection("users").doc(params.userId).get();
  const fcmToken = userSnap.data()?.fcmToken;
  if (typeof fcmToken === "string" && fcmToken.length > 20) {
    try {
      await messagingAdmin.send({
        token: fcmToken,
        notification: { title: params.title, body: params.body },
        data: {
          type: params.type,
          reference: params.reference || "",
          title: params.title,
          body: params.body,
        },
        android: {
          priority: "high",
          notification: {
            channelId: "msageer_transactions",
            sound: "default",
          },
        },
      });
    } catch (fcmErr) {
      await logProviderEvent({
        provider: "SYSTEM",
        action: "FCM_PUSH",
        reference: params.reference || params.userId,
        statusCode: "FCM_ERR",
        outcome: "ERROR",
        message: String((fcmErr as Error).message || "FCM push failed").slice(0, 280),
      });
    }
  }
}
