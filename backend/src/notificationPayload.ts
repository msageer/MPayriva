export type NotificationEventType =
  | "WALLET_FUNDING"
  | "PURCHASE_SUCCESS"
  | "PURCHASE_FAILED"
  | "TRANSACTION_PENDING"
  | "REFUND"
  | "ANNOUNCEMENT"
  | "SUPPORT_REPLY"
  | "SECURITY_ALERT"
  | "ACCOUNT_STATUS";

export type NotificationPlatform = "android" | "ios" | "web";

export type NotificationChannelId =
  | "msageer_transactions"
  | "msageer_security"
  | "msageer_support"
  | "msageer_announcements";

export type NotificationDeepLinkRoute =
  | "transaction_receipt"
  | "wallet"
  | "support"
  | "notifications";

export interface NotificationRoutingMeta {
  channelId: NotificationChannelId;
  route: NotificationDeepLinkRoute;
  interruptionLevel: "time-sensitive" | "active";
  priority: "high" | "normal";
}

export function resolveNotificationRouting(
  type: NotificationEventType,
  reference?: string
): NotificationRoutingMeta {
  switch (type) {
    case "WALLET_FUNDING":
      return {
        channelId: "msageer_transactions",
        route: reference ? "transaction_receipt" : "wallet",
        interruptionLevel: "time-sensitive",
        priority: "high",
      };
    case "PURCHASE_SUCCESS":
    case "PURCHASE_FAILED":
    case "TRANSACTION_PENDING":
    case "REFUND":
      return {
        channelId: "msageer_transactions",
        route: reference ? "transaction_receipt" : "notifications",
        interruptionLevel: "time-sensitive",
        priority: "high",
      };
    case "SECURITY_ALERT":
    case "ACCOUNT_STATUS":
      return {
        channelId: "msageer_security",
        route: "notifications",
        interruptionLevel: "time-sensitive",
        priority: "high",
      };
    case "SUPPORT_REPLY":
      return {
        channelId: "msageer_support",
        route: "support",
        interruptionLevel: "active",
        priority: "high",
      };
    case "ANNOUNCEMENT":
    default:
      return {
        channelId: "msageer_announcements",
        route: "notifications",
        interruptionLevel: "active",
        priority: "normal",
      };
  }
}

export function normalizePlatform(raw?: unknown): NotificationPlatform {
  const value = String(raw || "android").trim().toLowerCase();
  if (value === "ios" || value === "apple" || value === "iphone" || value === "ipad") return "ios";
  if (value === "web" || value === "pwa" || value === "browser") return "web";
  return "android";
}

export function isPrunableFcmErrorCode(code?: string): boolean {
  if (!code) return false;
  const normalized = code.trim().toLowerCase();
  return (
    normalized === "messaging/registration-token-not-registered" ||
    normalized === "messaging/invalid-registration-token" ||
    normalized === "messaging/mismatched-credential" ||
    normalized === "registration-token-not-registered" ||
    normalized === "invalid-registration-token"
  );
}

export function sanitizeDeviceId(raw?: unknown): string {
  const cleaned = String(raw || "")
    .trim()
    .replace(/[^a-zA-Z0-9_-]/g, "_")
    .slice(0, 96);
  return cleaned.length >= 4 ? cleaned : "";
}

export interface CrossPlatformMulticastPayloadInput {
  tokens: string[];
  notificationId: string;
  title: string;
  body: string;
  type: NotificationEventType;
  reference?: string;
  unreadBadgeCount?: number;
  webLink?: string;
  timestampIso?: string;
}

/**
 * Builds an industry-standard FCM v1 cross-platform MulticastMessage payload
 * targeting Android (Notification Channels + high priority + tag),
 * Apple APNs (iOS/iPadOS/macOS alert + sound + badge + interruption-level + mutable-content),
 * and Web Push (RFC 8030 Urgency + TTL + Notification API fields).
 */
export function buildCrossPlatformMulticastPayload(input: CrossPlatformMulticastPayloadInput) {
  const routing = resolveNotificationRouting(input.type, input.reference);
  const safeTitle = input.title.trim().slice(0, 120) || "M-Sageer Pay";
  const safeBody = input.body.trim().slice(0, 600);
  const badge = Math.max(1, Math.min(99, Math.round(input.unreadBadgeCount || 1)));
  const ttlSeconds = 24 * 60 * 60; // 24 hours
  const apnsExpiration = String(Math.floor(Date.now() / 1000) + ttlSeconds);
  const timestamp = input.timestampIso || new Date().toISOString();

  const data: Record<string, string> = {
    notificationId: input.notificationId,
    type: input.type,
    channelId: routing.channelId,
    route: routing.route,
    reference: input.reference ? input.reference.trim().slice(0, 100) : "",
    title: safeTitle,
    body: safeBody,
    timestamp,
  };

  return {
    tokens: input.tokens,
    notification: {
      title: safeTitle,
      body: safeBody,
    },
    data,
    android: {
      priority: routing.priority,
      ttl: ttlSeconds * 1000,
      collapseKey: input.reference ? `tx_${input.reference.slice(0, 48)}` : routing.channelId,
      notification: {
        channelId: routing.channelId,
        sound: "default",
        defaultVibrateTimings: true,
        visibility: "private" as const,
        tag: input.notificationId,
        clickAction: "MSAGEER_NOTIFICATION_CLICK",
      },
    },
    apns: {
      headers: {
        "apns-priority": routing.priority === "high" ? "10" : "5",
        "apns-push-type": "alert",
        "apns-expiration": apnsExpiration,
      },
      payload: {
        aps: {
          alert: {
            title: safeTitle,
            body: safeBody,
          },
          sound: "default",
          badge,
          "mutable-content": 1,
          "thread-id": routing.channelId,
          "interruption-level": routing.interruptionLevel,
        },
      },
    },
    webpush: {
      headers: {
        Urgency: routing.priority === "high" ? "high" : "normal",
        TTL: String(ttlSeconds),
      },
      notification: {
        title: safeTitle,
        body: safeBody,
        tag: input.notificationId,
        renotify: false,
      },
      ...(input.webLink ? { fcmOptions: { link: input.webLink } } : {}),
    },
  };
}
