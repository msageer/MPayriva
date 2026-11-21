export type SupportedServiceType =
  | "AIRTIME"
  | "DATA"
  | "ELECTRICITY"
  | "CABLE_TV"
  | "RECHARGE_CARD"
  | "DATA_CARD"
  | "WAEC_PIN"
  | "JAMB_PIN"
  | "SMILE"
  | "SPECTRANET"
  | "BETTING";

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

export interface EpinVoucher {
  pin: string;
  serialNumber: string;
  batchNo: string;
  network: string;
  denomination: string;
  loadCode: string;
  transactionDate: string;
}

const VALID_EPIN_DENOMINATIONS = new Set([100, 200, 500, 1000]);

/**
 * Pure, server-authoritative pricing and validation calculator.
 * Preserves faceValueNaira for variable-amount utilities (Airtime, Electricity, Betting)
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

    case "BETTING": {
      if (!Number.isFinite(requestedKobo) || requestedKobo < 10000 || requestedKobo > 50000000) {
        throw new Error("Betting wallet funding must be between ₦100 and ₦500,000.");
      }
      faceValueNaira = Math.round(requestedKobo / 100);
      sellingPriceKobo = requestedKobo;
      providerCostKobo = requestedKobo;
      break;
    }

    case "DATA":
    case "DATA_CARD":
    case "CABLE_TV":
    case "WAEC_PIN":
    case "JAMB_PIN":
    case "SMILE":
    case "SPECTRANET": {
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

function resolveRechargeLoadCode(networkName: string, isDataCard: boolean): string {
  const net = networkName.toUpperCase();
  if (isDataCard) {
    if (net.includes("MTN")) return "*460*6*1# or *311*PIN#";
    if (net.includes("AIRTEL")) return "*311*PIN#";
    if (net.includes("GLO")) return "*311*PIN#";
    if (net.includes("9MOBILE") || net.includes("ETISALAT")) return "*311*PIN#";
    return "*311*PIN#";
  }
  // NCC Harmonized Nigerian Recharge Code (*311*PIN#) works across MTN, Airtel, Glo & 9mobile
  return "*311*PIN#";
}

function extractVouchersFromResponse(
  providerRes: Record<string, unknown>,
  isDataCard = false
): EpinVoucher[] {
  const rawList = providerRes.TXN_EPIN || providerRes.txn_epin || providerRes.pins || providerRes.cards;
  if (!Array.isArray(rawList)) return [];

  const vouchers: EpinVoucher[] = [];
  for (const item of rawList) {
    if (!item || typeof item !== "object") continue;
    const obj = item as Record<string, unknown>;
    const pin = String(obj.pin || obj.PIN || obj.card_pin || "").trim();
    if (!pin) continue;
    const network = String(obj.mobilenetwork || obj.network || obj.MOBILE_NETWORK || "NETWORK").trim();
    const amount = String(obj.amount || obj.value || obj.plan || "").trim();
    vouchers.push({
      pin,
      serialNumber: String(obj.sno || obj.serial || obj.serial_no || obj.SNO || "").trim(),
      batchNo: String(obj.batchno || obj.batch_no || obj.BATCHNO || "").trim(),
      network,
      denomination: amount ? (amount.startsWith("₦") || /[A-Za-z]/.test(amount) ? amount : `₦${amount}`) : "",
      loadCode: String(obj.load_code || resolveRechargeLoadCode(network, isDataCard)).trim(),
      transactionDate: String(obj.transactiondate || obj.date || new Date().toISOString()).trim(),
    });
  }
  return vouchers;
}

/**
 * Classifies raw ClubKonnect (Nellobyte Systems) API status fields into strict
 * financial state machine outcomes. Handles both standard V1 responses and
 * TXN_EPIN arrays returned by APIEPINV1.asp / Data Card endpoints.
 * Unknown or ambiguous states ALWAYS stay PENDING.
 */
export function classifyClubKonnectStatus(
  providerRes: Record<string, unknown>,
  isDataCard = false
): {
  resolution: ProviderResolution;
  reason: string;
  tokenOrPin: string | null;
  serialNumber: string | null;
  orderId: string | null;
  vouchers: EpinVoucher[];
} {
  const statusStr = String(providerRes.status || providerRes.orderstatus || "").toUpperCase().trim();
  const statusCode = String(providerRes.statuscode || "").trim();
  const remark = String(providerRes.remark || providerRes.message || statusStr || "Provider response").trim();
  const orderId =
    providerRes.orderid || providerRes.ORDER_ID
      ? String(providerRes.orderid || providerRes.ORDER_ID)
      : null;

  const vouchers = extractVouchersFromResponse(providerRes, isDataCard);

  if (vouchers.length > 0) {
    const summaryToken =
      vouchers.length === 1
        ? vouchers[0].pin
        : vouchers.map((v, idx) => `#${idx + 1}: ${v.pin}${v.serialNumber ? ` (S/N: ${v.serialNumber})` : ""}`).join("\n");
    return {
      resolution: "SUCCESSFUL",
      reason: remark || "EPIN_GENERATED",
      tokenOrPin: summaryToken,
      serialNumber: vouchers[0].serialNumber || null,
      orderId,
      vouchers,
    };
  }

  const rawToken =
    providerRes.metertoken ||
    providerRes.carddetails ||
    providerRes.pin ||
    providerRes.token ||
    null;
  const tokenOrPin = rawToken ? String(rawToken).trim() : null;
  const serialNumber = providerRes.sno || providerRes.serial ? String(providerRes.sno || providerRes.serial).trim() : null;

  if (statusStr.includes("ORDER_COMPLETED") || statusCode === "200") {
    return {
      resolution: "SUCCESSFUL",
      reason: remark || "ORDER_COMPLETED",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers: [],
    };
  }

  if (
    statusStr.includes("ORDER_RECEIVED") ||
    statusStr.includes("ORDER_ONHOLD") ||
    statusStr.includes("PENDING") ||
    statusStr.includes("PROCESSING") ||
    statusCode === "100" ||
    statusCode === "300"
  ) {
    return {
      resolution: "PENDING",
      reason: remark || "ORDER_RECEIVED",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers: [],
    };
  }

  if (
    statusStr.includes("ORDER_CANCELLED") ||
    statusStr.includes("FAILED") ||
    statusStr.includes("INVALID") ||
    statusStr.includes("INSUFFICIENT") ||
    statusStr.includes("MISSING") ||
    statusStr.includes("UNAVAILABLE") ||
    statusStr.includes("NOT_AVAILABLE") ||
    statusStr.includes("OUT_OF_STOCK") ||
    statusCode.startsWith("4") ||
    statusCode.startsWith("5") ||
    statusCode === "600"
  ) {
    return {
      resolution: "FAILED",
      reason: remark || "Order declined by provider",
      tokenOrPin: null,
      serialNumber: null,
      orderId,
      vouchers: [],
    };
  }

  // Default safe fallback: treat unrecognized responses as PENDING until APIQueryV1 confirms.
  return {
    resolution: "PENDING",
    reason: remark || "Awaiting provider confirmation",
    tokenOrPin,
    serialNumber,
    orderId,
    vouchers: [],
  };
}
