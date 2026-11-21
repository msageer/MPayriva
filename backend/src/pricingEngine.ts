export type SupportedServiceType =
  | "AIRTIME"
  | "DATA"
  | "ELECTRICITY"
  | "CABLE_TV"
  | "RECHARGE_CARD"
  | "WAEC_PIN"
  | "JAMB_PIN";

export interface CatalogPricingDoc {
  productName?: string;
  providerCostKobo?: number;
  sellingPriceKobo?: number;
  feeKobo?: number;
  discountPercent?: number;
  commissionPercent?: number;
}

export interface ComputedPurchasePricing {
  productName: string;
  quantity: number;
  faceValueNaira: number;
  providerCostKobo: number;
  sellingPriceKobo: number;
  feeKobo: number;
  totalDebitKobo: number;
  grossMarginKobo: number;
}

const VALID_EPIN_DENOMINATIONS = new Set([100, 200, 500, 1000]);

/**
 * Pure, server-authoritative pricing and validation calculator.
 * Preserves faceValueNaira for variable-amount utilities (Airtime & Electricity)
 * so customers receive the exact face value ordered even when a discount applies.
 */
export function computeAuthorizedPurchasePricing(params: {
  serviceType: string;
  providerCode: string;
  productCode: string;
  requestedAmountKobo: number;
  quantity?: number;
  catalog?: CatalogPricingDoc | null;
}): ComputedPurchasePricing {
  const serviceType = String(params.serviceType || "").toUpperCase();
  const safeQty = Math.max(1, Math.min(50, Math.floor(Number(params.quantity || 1))));
  const requestedKobo = Math.round(Number(params.requestedAmountKobo || 0));
  const catalog = params.catalog ?? null;

  let productName = String(catalog?.productName || `${serviceType} (${params.providerCode})`);
  const feeKobo = Math.max(0, Math.round(Number(catalog?.feeKobo ?? 0)));

  let faceValueNaira = Math.round(requestedKobo / 100);
  let sellingPriceKobo = requestedKobo;
  let providerCostKobo = requestedKobo;

  switch (serviceType) {
    case "AIRTIME": {
      if (!Number.isFinite(requestedKobo) || requestedKobo < 5000 || requestedKobo > 5000000) {
        throw new Error("Airtime amount must be between ₦50 and ₦50,000.");
      }
      faceValueNaira = Math.round(requestedKobo / 100);
      const discount = Math.min(50, Math.max(0, Number(catalog?.discountPercent ?? 0)));
      const commission = Math.min(50, Math.max(0, Number(catalog?.commissionPercent ?? 0)));
      sellingPriceKobo = Math.round(requestedKobo * (1 - discount / 100));
      providerCostKobo = Math.round(requestedKobo * (1 - commission / 100));
      break;
    }

    case "ELECTRICITY": {
      if (!Number.isFinite(requestedKobo) || requestedKobo < 50000 || requestedKobo > 50000000) {
        throw new Error("Electricity payment must be between ₦500 and ₦500,000.");
      }
      faceValueNaira = Math.round(requestedKobo / 100);
      const commission = Math.min(50, Math.max(0, Number(catalog?.commissionPercent ?? 0)));
      sellingPriceKobo = requestedKobo;
      providerCostKobo = Math.round(requestedKobo * (1 - commission / 100));
      break;
    }

    case "DATA":
    case "CABLE_TV":
    case "WAEC_PIN":
    case "JAMB_PIN": {
      if (!catalog || Number(catalog.sellingPriceKobo ?? 0) <= 0) {
        throw new Error(`No active pricing catalog entry found for ${serviceType} (${params.productCode}).`);
      }
      sellingPriceKobo = Math.round(Number(catalog.sellingPriceKobo)) * safeQty;
      providerCostKobo = Math.round(Number(catalog.providerCostKobo ?? catalog.sellingPriceKobo)) * safeQty;
      faceValueNaira = Math.round(sellingPriceKobo / 100);
      break;
    }

    case "RECHARGE_CARD": {
      const denomNaira = Math.round(Number(params.productCode || 0));
      if (!VALID_EPIN_DENOMINATIONS.has(denomNaira)) {
        throw new Error("Recharge card denomination must be ₦100, ₦200, ₦500, or ₦1,000.");
      }
      faceValueNaira = denomNaira;
      const unitFaceKobo = denomNaira * 100;
      if (catalog && Number(catalog.sellingPriceKobo ?? 0) > 0) {
        sellingPriceKobo = Math.round(Number(catalog.sellingPriceKobo)) * safeQty;
        providerCostKobo = Math.round(Number(catalog.providerCostKobo ?? catalog.sellingPriceKobo)) * safeQty;
      } else {
        sellingPriceKobo = unitFaceKobo * safeQty;
        providerCostKobo = unitFaceKobo * safeQty;
      }
      break;
    }

    default:
      throw new Error(`Unsupported service type: ${serviceType}`);
  }

  const totalDebitKobo = sellingPriceKobo + feeKobo;
  const grossMarginKobo = totalDebitKobo - providerCostKobo;

  if (totalDebitKobo <= 0) {
    throw new Error("Calculated transaction total must be positive.");
  }

  return {
    productName,
    quantity: safeQty,
    faceValueNaira,
    providerCostKobo,
    sellingPriceKobo,
    feeKobo,
    totalDebitKobo,
    grossMarginKobo,
  };
}

export type ProviderResolution = "SUCCESSFUL" | "PENDING" | "FAILED";

/**
 * Classifies raw ClubKonnect (Nellobyte Systems) API status fields into strict
 * financial state machine outcomes. Unknown or ambiguous states ALWAYS stay PENDING.
 */
export function classifyClubKonnectStatus(providerRes: Record<string, unknown>): {
  resolution: ProviderResolution;
  reason: string;
  tokenOrPin: string | null;
  orderId: string | null;
} {
  const statusStr = String(providerRes.status || providerRes.orderstatus || "").toUpperCase().trim();
  const statusCode = String(providerRes.statuscode || "").trim();
  const remark = String(providerRes.remark || providerRes.message || statusStr || "Provider response").trim();
  const orderId = providerRes.orderid ? String(providerRes.orderid) : null;
  const rawToken =
    providerRes.metertoken ||
    providerRes.carddetails ||
    providerRes.pin ||
    providerRes.token ||
    null;
  const tokenOrPin = rawToken ? String(rawToken).trim() : null;

  if (statusStr.includes("ORDER_COMPLETED") || statusCode === "200") {
    return { resolution: "SUCCESSFUL", reason: remark || "ORDER_COMPLETED", tokenOrPin, orderId };
  }

  if (
    statusStr.includes("ORDER_RECEIVED") ||
    statusStr.includes("ORDER_ONHOLD") ||
    statusStr.includes("PENDING") ||
    statusStr.includes("PROCESSING") ||
    statusCode === "100" ||
    statusCode === "300"
  ) {
    return { resolution: "PENDING", reason: remark || "ORDER_RECEIVED", tokenOrPin, orderId };
  }

  if (
    statusStr.includes("ORDER_CANCELLED") ||
    statusStr.includes("FAILED") ||
    statusStr.includes("INVALID") ||
    statusStr.includes("INSUFFICIENT") ||
    statusStr.includes("MISSING") ||
    statusStr.includes("UNAVAILABLE") ||
    statusCode.startsWith("4") ||
    statusCode.startsWith("5") ||
    statusCode === "600"
  ) {
    return { resolution: "FAILED", reason: remark || "Order declined by provider", tokenOrPin: null, orderId };
  }

  // Default safe fallback: treat unrecognized responses as PENDING until APIQueryV1 confirms.
  return {
    resolution: "PENDING",
    reason: remark || "Awaiting provider confirmation",
    tokenOrPin,
    orderId,
  };
}
