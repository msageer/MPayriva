import crypto from "crypto";
import { FieldValue } from "firebase-admin/firestore";
import { CONFIG, db, logProviderEvent, messagingAdmin } from "./config";
import {
  buildCrossPlatformMulticastPayload,
  isPrunableFcmErrorCode,
  normalizePlatform,
  NotificationEventType,
  NotificationPlatform,
  sanitizeDeviceId,
} from "./notificationPayload";

export interface RegisterDeviceTokenInput {
  userId: string;
  token: string;
  platform?: NotificationPlatform | string;
  deviceId?: string;
  appVersion?: string;
  deviceName?: string;
}

export interface SendPushResult {
  notificationId: string;
  attemptedTokens: number;
  deliveredCount: number;
  prunedCount: number;
}

const MAX_FCM_MULTICAST_BATCH = 500;

function computeStableDeviceId(token: string, rawDeviceId?: string): string {
  const sanitized = sanitizeDeviceId(rawDeviceId);
  if (sanitized) return sanitized;
  return `dev_${crypto.createHash("sha256").update(token, "utf8").digest("hex").slice(0, 20)}`;
}

/**
 * Registers or refreshes an FCM push token for a specific user device across Android, iOS, or Web.
 * Deduplicates identical tokens across other device slots and keeps legacy users/{uid}.fcmToken synced.
 */
export async function registerUserDeviceToken(input: RegisterDeviceTokenInput): Promise<{
  deviceId: string;
  platform: NotificationPlatform;
}> {
  const cleanToken = input.token.trim();
  if (cleanToken.length < 20 || cleanToken.length > 512) {
    throw new Error("Invalid push notification token length.");
  }

  const platform = normalizePlatform(input.platform);
  const deviceId = computeStableDeviceId(cleanToken, input.deviceId);
  const userRef = db.collection("users").doc(input.userId);
  const devicesCol = userRef.collection("devices");

  // Deduplicate if the same physical token was previously stored under another deviceId
  const existingSameToken = await devicesCol.where("token", "==", cleanToken).get();
  const batch = db.batch();

  for (const doc of existingSameToken.docs) {
    if (doc.id !== deviceId) {
      batch.delete(doc.ref);
    }
  }

  batch.set(
    devicesCol.doc(deviceId),
    {
      deviceId,
      userId: input.userId,
      token: cleanToken,
      platform,
      appVersion: String(input.appVersion || "").trim().slice(0, 40) || null,
      deviceName: String(input.deviceName || "").trim().slice(0, 80) || null,
      active: true,
      updatedAt: FieldValue.serverTimestamp(),
      createdAt: FieldValue.serverTimestamp(),
    },
    { merge: true }
  );

  // Keep legacy single-token field synced if user profile doc exists
  const userSnap = await userRef.get();
  if (userSnap.exists) {
    batch.update(userRef, {
      fcmToken: cleanToken.slice(0, 300),
      updatedAt: FieldValue.serverTimestamp(),
    });
  }

  await batch.commit();
  return { deviceId, platform };
}

/**
 * Unregisters a device's FCM token on sign-out so signed-out devices never receive pushes.
 */
export async function unregisterUserDeviceToken(params: {
  userId: string;
  token?: string;
  deviceId?: string;
}): Promise<{ removedCount: number }> {
  const userRef = db.collection("users").doc(params.userId);
  const devicesCol = userRef.collection("devices");
  const cleanToken = typeof params.token === "string" ? params.token.trim() : "";
  const cleanDeviceId = sanitizeDeviceId(params.deviceId);

  const refsToDelete = new Map<string, FirebaseFirestore.DocumentReference>();
  if (cleanDeviceId) {
    refsToDelete.set(cleanDeviceId, devicesCol.doc(cleanDeviceId));
  }
  if (cleanToken.length >= 20) {
    const tokenMatches = await devicesCol.where("token", "==", cleanToken).get();
    for (const doc of tokenMatches.docs) {
      refsToDelete.set(doc.id, doc.ref);
    }
  }

  if (refsToDelete.size === 0 && !cleanToken) {
    return { removedCount: 0 };
  }

  const batch = db.batch();
  for (const ref of refsToDelete.values()) {
    batch.delete(ref);
  }

  const userSnap = await userRef.get();
  if (userSnap.exists) {
    const legacyToken = String(userSnap.data()?.fcmToken || "").trim();
    if (cleanToken && legacyToken === cleanToken) {
      batch.update(userRef, {
        fcmToken: null,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  }

  await batch.commit();
  return { removedCount: refsToDelete.size };
}

async function collectActiveTokensForUser(userId: string): Promise<{
  tokens: string[];
  tokenToDeviceRefs: Map<string, FirebaseFirestore.DocumentReference[]>;
  legacyToken: string;
}> {
  const userRef = db.collection("users").doc(userId);
  const [userSnap, devicesSnap] = await Promise.all([
    userRef.get(),
    userRef.collection("devices").where("active", "==", true).get(),
  ]);

  const tokenSet = new Set<string>();
  const tokenToDeviceRefs = new Map<string, FirebaseFirestore.DocumentReference[]>();

  for (const doc of devicesSnap.docs) {
    const token = String(doc.data()?.token || "").trim();
    if (token.length >= 20) {
      tokenSet.add(token);
      const list = tokenToDeviceRefs.get(token) ?? [];
      list.push(doc.ref);
      tokenToDeviceRefs.set(token, list);
    }
  }

  const legacyToken = String(userSnap.data()?.fcmToken || "").trim();
  if (legacyToken.length >= 20) {
    tokenSet.add(legacyToken);
  }

  return {
    tokens: Array.from(tokenSet),
    tokenToDeviceRefs,
    legacyToken,
  };
}

/**
 * Persists an in-app notification record AND dispatches cross-platform FCM v1 multicast
 * pushes (Android, iOS/APNs, Web Push) to all registered devices of the user, automatically
 * pruning unregistered or invalid tokens.
 */
export async function sendCrossPlatformNotification(params: {
  userId: string;
  title: string;
  body: string;
  type: NotificationEventType;
  reference?: string;
}): Promise<SendPushResult> {
  const safeTitle = params.title.trim().slice(0, 120) || "M-Sageer Pay";
  const safeBody = params.body.trim().slice(0, 600);

  // Map extended push types onto Firestore schema-compatible notification type while keeping rich FCM data.type
  const firestoreType =
    params.type === "SUPPORT_REPLY" ||
    params.type === "SECURITY_ALERT" ||
    params.type === "ACCOUNT_STATUS"
      ? "ANNOUNCEMENT"
      : params.type;

  const notifDocRef = db.collection("notifications").doc();
  const payload: Record<string, unknown> = {
    userId: params.userId,
    title: safeTitle,
    body: safeBody,
    type: firestoreType,
    read: false,
    createdAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  };
  if (params.reference) payload.reference = params.reference.trim().slice(0, 100);
  await notifDocRef.set(payload);

  const { tokens, tokenToDeviceRefs, legacyToken } = await collectActiveTokensForUser(params.userId);
  if (tokens.length === 0) {
    return {
      notificationId: notifDocRef.id,
      attemptedTokens: 0,
      deliveredCount: 0,
      prunedCount: 0,
    };
  }

  // Count unread notifications for accurate iOS/APNs app icon badge
  let unreadCount = 1;
  try {
    const unreadSnap = await db
      .collection("notifications")
      .where("userId", "==", params.userId)
      .where("read", "==", false)
      .count()
      .get();
    unreadCount = Math.max(1, Number(unreadSnap.data().count || 1));
  } catch {
    unreadCount = 1;
  }

  let deliveredCount = 0;
  const staleTokens: string[] = [];

  for (let i = 0; i < tokens.length; i += MAX_FCM_MULTICAST_BATCH) {
    const batchTokens = tokens.slice(i, i + MAX_FCM_MULTICAST_BATCH);
    const multicastMessage = buildCrossPlatformMulticastPayload({
      tokens: batchTokens,
      notificationId: notifDocRef.id,
      title: safeTitle,
      body: safeBody,
      type: params.type,
      reference: params.reference,
      unreadBadgeCount: unreadCount,
      webLink: CONFIG.callbackBaseUrl || undefined,
    });

    try {
      const response = await messagingAdmin.sendEachForMulticast(multicastMessage);
      deliveredCount += response.successCount;

      response.responses.forEach((resp, idx) => {
        if (!resp.success) {
          const code = resp.error?.code;
          if (isPrunableFcmErrorCode(code)) {
            staleTokens.push(batchTokens[idx]);
          }
        }
      });
    } catch (fcmErr) {
      await logProviderEvent({
        provider: "SYSTEM",
        action: "FCM_MULTICAST_PUSH",
        reference: params.reference || params.userId,
        statusCode: "FCM_ERR",
        outcome: "ERROR",
        message: String((fcmErr as Error).message || "FCM multicast failed").slice(0, 280),
      });
    }
  }

  // Automatically prune dead/unregistered tokens from Firestore
  let prunedCount = 0;
  if (staleTokens.length > 0) {
    try {
      const pruneBatch = db.batch();
      let clearLegacy = false;
      for (const stale of staleTokens) {
        const refs = tokenToDeviceRefs.get(stale) ?? [];
        for (const ref of refs) {
          pruneBatch.delete(ref);
          prunedCount += 1;
        }
        if (legacyToken && stale === legacyToken) {
          clearLegacy = true;
        }
      }
      if (clearLegacy) {
        pruneBatch.update(db.collection("users").doc(params.userId), {
          fcmToken: null,
          updatedAt: FieldValue.serverTimestamp(),
        });
      }
      await pruneBatch.commit();
    } catch {
      // Non-fatal cleanup error
    }
  }

  return {
    notificationId: notifDocRef.id,
    attemptedTokens: tokens.length,
    deliveredCount,
    prunedCount,
  };
}

/**
 * Broadcasts an announcement to all active users (persisted to inbox + cross-platform FCM push).
 */
export async function broadcastCrossPlatformAnnouncement(params: {
  title: string;
  body: string;
  reference?: string;
}): Promise<{
  totalUsers: number;
  attemptedTokens: number;
  deliveredCount: number;
  prunedCount: number;
}> {
  const usersSnap = await db.collection("users").get();
  let totalUsers = 0;
  let attemptedTokens = 0;
  let deliveredCount = 0;
  let prunedCount = 0;

  const CONCURRENCY = 10;
  const userIds = usersSnap.docs
    .filter((doc) => String(doc.data()?.accountStatus || "ACTIVE").toUpperCase() !== "SUSPENDED")
    .map((doc) => doc.id);

  for (let i = 0; i < userIds.length; i += CONCURRENCY) {
    const slice = userIds.slice(i, i + CONCURRENCY);
    const results = await Promise.allSettled(
      slice.map((uid) =>
        sendCrossPlatformNotification({
          userId: uid,
          title: params.title,
          body: params.body,
          type: "ANNOUNCEMENT",
          reference: params.reference,
        })
      )
    );
    for (const r of results) {
      if (r.status === "fulfilled") {
        totalUsers += 1;
        attemptedTokens += r.value.attemptedTokens;
        deliveredCount += r.value.deliveredCount;
        prunedCount += r.value.prunedCount;
      }
    }
  }

  return { totalUsers, attemptedTokens, deliveredCount, prunedCount };
}
