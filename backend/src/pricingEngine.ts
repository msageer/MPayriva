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

export interface ServicePricingRule {
  serviceType: SupportedServiceType;
  markupPercent: number;
  markupFlatKobo: number;
  customerDiscountPercent: number;
  feeKobo: number;
}

export type PlatformPricingRulesMap = Record<SupportedServiceType, ServicePricingRule>;

export const DEFAULT_SERVICE_PRICING_RULES: PlatformPricingRulesMap = {
  AIRTIME: { serviceType: "AIRTIME", markupPercent: 0, markupFlatKobo: 0, customerDiscountPercent: 1.0, feeKobo: 0 },
  DATA: { serviceType: "DATA", markupPercent: 3.0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  ELECTRICITY: { serviceType: "ELECTRICITY", markupPercent: 0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 10000 },
  CABLE_TV: { serviceType: "CABLE_TV", markupPercent: 1.5, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  RECHARGE_CARD: { serviceType: "RECHARGE_CARD", markupPercent: 0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  DATA_CARD: { serviceType: "DATA_CARD", markupPercent: 3.0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  WAEC_PIN: { serviceType: "WAEC_PIN", markupPercent: 3.0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  JAMB_PIN: { serviceType: "JAMB_PIN", markupPercent: 3.0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  SMILE: { serviceType: "SMILE", markupPercent: 1.5, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  SPECTRANET: { serviceType: "SPECTRANET", markupPercent: 1.5, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 0 },
  BETTING: { serviceType: "BETTING", markupPercent: 0, markupFlatKobo: 0, customerDiscountPercent: 0, feeKobo: 5000 },
};

export function sanitizeServicePricingRule(
  serviceType: SupportedServiceType,
  raw: Partial<ServicePricingRule> | undefined
): ServicePricingRule {
  const def = DEFAULT_SERVICE_PRICING_RULES[serviceType];
  const clamp = (val: unknown, min: number, max: number, fallback: number) => {
    const n = Number(val);
    if (!Number.isFinite(n)) return fallback;
    return Math.min(max, Math.max(min, n));
  };
  return {
    serviceType,
    markupPercent: Number(clamp(raw?.markupPercent, -15, 100, def.markupPercent).toFixed(2)),
    markupFlatKobo: Math.round(clamp(raw?.markupFlatKobo, -50000, 2000000, def.markupFlatKobo)),
    customerDiscountPercent: Number(clamp(raw?.customerDiscountPercent, 0, 25, def.customerDiscountPercent).toFixed(2)),
    feeKobo: Math.round(clamp(raw?.feeKobo, 0, 500000, def.feeKobo)),
  };
}

/**
 * Applies a service-level pricing rule to a single ClubKonnect plan and calculates exact
 * selling price, customer fee, net profit (kobo), and profit margin (%).
 */
export function computePlanPricingWithRule(params: {
  serviceType: string;
  productCode: string;
  providerCostKobo: number;
  commissionPercent: number;
  rule: ServicePricingRule;
}): {
  sellingPriceKobo: number;
  feeKobo: number;
  discountPercent: number;
  netProfitKobo: number;
  profitMarginPercent: number;
} {
  const svc = params.serviceType.toUpperCase();
  const costKobo = Math.max(0, Math.round(params.providerCostKobo));
  const commPct = Math.max(0, Math.min(50, Number(params.commissionPercent || 0)));
  const feeKobo = Math.max(0, Math.round(params.rule.feeKobo));
  const discPct = Math.max(0, Math.min(25, Number(params.rule.customerDiscountPercent || 0)));

  if (svc === "AIRTIME") {
    // Variable face value: evaluate net profit on a ₦1,000 (100,000 kobo) reference purchase
    const sampleFaceKobo = 100000;
    const sampleCostKobo = Math.round(sampleFaceKobo * (1 - commPct / 100));
    const sampleSellKobo = Math.round(sampleFaceKobo * (1 - discPct / 100));
    const netProfitKobo = sampleSellKobo + feeKobo - sampleCostKobo;
    const marginPct = Number(((netProfitKobo / sampleFaceKobo) * 100).toFixed(2));
    return {
      sellingPriceKobo: 0,
      feeKobo,
      discountPercent: discPct,
      netProfitKobo,
      profitMarginPercent: marginPct,
    };
  }

  if (svc === "ELECTRICITY" || svc === "BETTING") {
    // Variable face value: evaluate net profit on a ₦1,000 reference purchase
    const sampleFaceKobo = 100000;
    const sampleCostKobo = Math.round(sampleFaceKobo * (1 - commPct / 100));
    const sampleSellKobo = Math.round(sampleFaceKobo * (1 - discPct / 100));
    const netProfitKobo = sampleSellKobo + feeKobo - sampleCostKobo;
    const marginPct = Number(((netProfitKobo / sampleFaceKobo) * 100).toFixed(2));
    return {
      sellingPriceKobo: 0,
      feeKobo,
      discountPercent: discPct,
      netProfitKobo,
      profitMarginPercent: marginPct,
    };
  }

  if (svc === "RECHARGE_CARD") {
    const denomNaira = Number(params.productCode || 0);
    const faceKobo = denomNaira > 0 ? Math.round(denomNaira * 100) : costKobo;
    const discountedFace = faceKobo * (1 - discPct / 100);
    const withMarkup = discountedFace * (1 + params.rule.markupPercent / 100) + params.rule.markupFlatKobo;
    const sellingPriceKobo = Math.max(100, Math.round(withMarkup / 100) * 100);
    const netProfitKobo = sellingPriceKobo + feeKobo - costKobo;
    const marginPct = costKobo > 0 ? Number(((netProfitKobo / costKobo) * 100).toFixed(2)) : 0;
    return {
      sellingPriceKobo,
      feeKobo,
      discountPercent: discPct,
      netProfitKobo,
      profitMarginPercent: marginPct,
    };
  }

  // Fixed-price plans (DATA, DATA_CARD, CABLE_TV, WAEC_PIN, JAMB_PIN, SMILE, SPECTRANET)
  const rawSell = costKobo * (1 + params.rule.markupPercent / 100) + params.rule.markupFlatKobo;
  const sellingPriceKobo = costKobo > 0 ? Math.max(100, Math.ceil(rawSell / 100) * 100) : 0;
  const netProfitKobo = sellingPriceKobo + feeKobo - costKobo;
  const marginPct = costKobo > 0 ? Number(((netProfitKobo / costKobo) * 100).toFixed(2)) : 0;
  return {
    sellingPriceKobo,
    feeKobo,
    discountPercent: discPct,
    netProfitKobo,
    profitMarginPercent: marginPct,
  };
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
  directApiWholesale?: boolean;
}): ComputedPurchasePricing {
  const serviceType = String(params.serviceType || "").toUpperCase();
  const safeQty = Math.max(1, Math.min(50, Math.floor(Number(params.quantity || 1))));
  const requestedKobo = Math.round(Number(params.requestedAmountKobo || 0));
  const catalog = params.catalog ?? null;
  const isDirectWholesale = params.directApiWholesale === true;

  let productName = String(catalog?.productName || `${serviceType} (${params.providerCode})`);
  const feeKobo = isDirectWholesale ? 0 : Math.max(0, Math.round(Number(catalog?.feeKobo ?? 0)));

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
      providerCostKobo = Math.round(requestedKobo * (1 - commission / 100));
      sellingPriceKobo = isDirectWholesale
        ? providerCostKobo
        : Math.round(requestedKobo * (1 - discount / 100));
      break;
    }

    case "ELECTRICITY": {
      if (!Number.isFinite(requestedKobo) || requestedKobo < 50000 || requestedKobo > 50000000) {
        throw new Error("Electricity payment must be between ₦500 and ₦500,000.");
      }
      faceValueNaira = Math.round(requestedKobo / 100);
      const commission = Math.min(50, Math.max(0, Number(catalog?.commissionPercent ?? 0)));
      providerCostKobo = Math.round(requestedKobo * (1 - commission / 100));
      sellingPriceKobo = isDirectWholesale ? providerCostKobo : requestedKobo;
      break;
    }

    case "BETTING": {
      if (!Number.isFinite(requestedKobo) || requestedKobo < 10000 || requestedKobo > 50000000) {
        throw new Error("Betting wallet funding must be between ₦100 and ₦500,000.");
      }
      faceValueNaira = Math.round(requestedKobo / 100);
      const commission = Math.min(50, Math.max(0, Number(catalog?.commissionPercent ?? 0)));
      providerCostKobo = Math.round(requestedKobo * (1 - commission / 100));
      sellingPriceKobo = isDirectWholesale ? providerCostKobo : requestedKobo;
      break;
    }

    case "DATA":
    case "DATA_CARD":
    case "CABLE_TV":
    case "WAEC_PIN":
    case "JAMB_PIN":
    case "SMILE":
    case "SPECTRANET": {
      const catalogSell = Math.round(Number(catalog?.sellingPriceKobo ?? 0));
      const catalogCost = Math.round(Number(catalog?.providerCostKobo ?? catalogSell));
      const effectiveUnitCost = catalogCost > 0 ? catalogCost : catalogSell;
      if (!catalog || (isDirectWholesale ? effectiveUnitCost <= 0 : catalogSell <= 0)) {
        throw new Error(`No active pricing catalog entry found for ${serviceType} (${params.productCode}).`);
      }
      providerCostKobo = effectiveUnitCost * safeQty;
      sellingPriceKobo = isDirectWholesale ? providerCostKobo : catalogSell * safeQty;
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
      const catalogSell = Math.round(Number(catalog?.sellingPriceKobo ?? 0));
      const catalogCost = Math.round(Number(catalog?.providerCostKobo ?? catalogSell));
      if (isDirectWholesale && catalogCost > 0) {
        providerCostKobo = catalogCost * safeQty;
        sellingPriceKobo = providerCostKobo;
      } else if (catalog && catalogSell > 0) {
        sellingPriceKobo = catalogSell * safeQty;
        providerCostKobo = (catalogCost > 0 ? catalogCost : catalogSell) * safeQty;
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
  isDataCard = false,
  isRequery = false
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
  const rawRemark = String(providerRes.remark || providerRes.message || "").trim();
  const reason = [statusStr, rawRemark]
    .filter((v, idx, arr) => v && (idx === 0 || v.toUpperCase() !== arr[0].toUpperCase()))
    .join(" — ") || String(providerRes.raw || "Provider response").slice(0, 180);
  const orderId =
    providerRes.orderid || providerRes.ORDER_ID
      ? String(providerRes.orderid || providerRes.ORDER_ID).trim()
      : null;

  const vouchers = extractVouchersFromResponse(providerRes, isDataCard);

  if (vouchers.length > 0) {
    const summaryToken =
      vouchers.length === 1
        ? vouchers[0].pin
        : vouchers.map((v, idx) => `#${idx + 1}: ${v.pin}${v.serialNumber ? ` (S/N: ${v.serialNumber})` : ""}`).join("\n");
    return {
      resolution: "SUCCESSFUL",
      reason: rawRemark || "EPIN_GENERATED",
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

  if (
    statusStr.includes("ORDER_COMPLETED") ||
    statusStr.includes("ORDER_PROCESSED") ||
    statusCode === "200"
  ) {
    return {
      resolution: "SUCCESSFUL",
      reason: rawRemark || "ORDER_COMPLETED",
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
      reason: reason || "ORDER_RECEIVED",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers: [],
    };
  }

  // On requery (APIQueryV1.asp), NEVER falsely reverse an order just because a query lookup key or auth failed.
  // Only reverse on requery when the provider explicitly confirms ORDER_CANCELLED or ORDER_FAILED.
  if (isRequery) {
    if (
      statusStr.includes("ORDER_CANCELLED") ||
      statusStr.includes("ORDER_FAILED") ||
      statusStr === "FAILED" ||
      statusCode === "600"
    ) {
      return {
        resolution: "FAILED",
        reason: reason || "Order cancelled by provider",
        tokenOrPin: null,
        serialNumber: null,
        orderId,
        vouchers: [],
      };
    }
    return {
      resolution: "PENDING",
      reason: reason || "Awaiting provider confirmation",
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
      reason: reason || "Order declined by provider",
      tokenOrPin: null,
      serialNumber: null,
      orderId,
      vouchers: [],
    };
  }

  // Default safe fallback: treat unrecognized responses as PENDING until APIQueryV1 confirms.
  return {
    resolution: "PENDING",
    reason: reason || "Awaiting provider confirmation",
    tokenOrPin,
    serialNumber,
    orderId,
    vouchers: [],
  };
}

/**
 * Classifies AlrahuzData (MSORG Django REST) transaction responses into strict
 * financial state machine outcomes. Ambiguous/timeout/5xx states ALWAYS stay PENDING.
 */
export function classifyAlrahuzStatus(
  providerRes: Record<string, unknown>,
  isDataCard = false,
  isRequery = false
): {
  resolution: ProviderResolution;
  reason: string;
  tokenOrPin: string | null;
  serialNumber: string | null;
  orderId: string | null;
  vouchers: EpinVoucher[];
} {
  const httpStatus = Number(providerRes.__httpStatus ?? 200);
  const rawStatus = String(providerRes.Status || providerRes.status || "").trim();
  const upperStatus = rawStatus.toUpperCase();
  const apiMsg = String(
    providerRes.api_response ||
      providerRes.message ||
      providerRes.error ||
      providerRes.detail ||
      ""
  ).trim();
  const reason = [rawStatus, apiMsg].filter(Boolean).join(" — ") || "AlrahuzData response";
  const orderId =
    providerRes.id !== undefined && providerRes.id !== null
      ? String(providerRes.id).trim()
      : providerRes.ident
        ? String(providerRes.ident).trim()
        : null;

  // Extract printable recharge / exam PIN vouchers if returned
  const vouchers: EpinVoucher[] = [];
  const rawPins = providerRes.data_pin || providerRes.pins || providerRes.cards;
  if (Array.isArray(rawPins)) {
    for (const item of rawPins) {
      if (typeof item === "string" && item.trim()) {
        vouchers.push({
          pin: item.trim(),
          serialNumber: "",
          batchNo: orderId || "",
          network: String(providerRes.network_name || providerRes.plan_network || "NETWORK"),
          denomination: String(providerRes.amount || ""),
          loadCode: resolveRechargeLoadCode(String(providerRes.network_name || ""), isDataCard),
          transactionDate: new Date().toISOString(),
        });
      } else if (item && typeof item === "object") {
        const obj = item as Record<string, unknown>;
        const pin = String(obj.pin || obj.PIN || obj.fields?.toString() || "").trim();
        if (pin) {
          vouchers.push({
            pin,
            serialNumber: String(obj.serial || obj.serial_number || obj.sno || "").trim(),
            batchNo: String(obj.batch || orderId || "").trim(),
            network: String(obj.network || providerRes.network_name || "NETWORK"),
            denomination: String(obj.amount || providerRes.amount || ""),
            loadCode: String(obj.load_code || resolveRechargeLoadCode(String(obj.network || ""), isDataCard)),
            transactionDate: String(obj.create_date || new Date().toISOString()),
          });
        }
      }
    }
  }

  const rawToken =
    providerRes.token ||
    providerRes.Token ||
    providerRes.pin ||
    providerRes.pins_str ||
    null;
  const tokenOrPin =
    vouchers.length > 0
      ? vouchers.length === 1
        ? vouchers[0].pin
        : vouchers.map((v, idx) => `#${idx + 1}: ${v.pin}`).join("\n")
      : rawToken && typeof rawToken === "string"
        ? rawToken.trim()
        : null;
  const serialNumber = vouchers[0]?.serialNumber || (providerRes.serial ? String(providerRes.serial).trim() : null);

  if (
    upperStatus === "SUCCESSFUL" ||
    upperStatus === "SUCCESS" ||
    upperStatus === "DELIVERED" ||
    vouchers.length > 0
  ) {
    return {
      resolution: "SUCCESSFUL",
      reason: apiMsg || "SUCCESSFUL",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers,
    };
  }

  if (
    upperStatus === "PROCESSING" ||
    upperStatus === "PENDING" ||
    upperStatus === "QUEUED" ||
    httpStatus >= 500
  ) {
    return {
      resolution: "PENDING",
      reason: reason || "Processing at provider",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers: [],
    };
  }

  if (isRequery) {
    if (upperStatus === "FAILED" || upperStatus === "FAIL" || upperStatus === "CANCELLED" || upperStatus === "REFUNDED") {
      return {
        resolution: "FAILED",
        reason: reason || "Provider confirmed failed",
        tokenOrPin: null,
        serialNumber: null,
        orderId,
        vouchers: [],
      };
    }
    return {
      resolution: "PENDING",
      reason: reason || "Awaiting provider confirmation",
      tokenOrPin,
      serialNumber,
      orderId,
      vouchers: [],
    };
  }

  if (
    upperStatus === "FAILED" ||
    upperStatus === "FAIL" ||
    upperStatus === "ERROR" ||
    upperStatus === "CANCELLED" ||
    (httpStatus >= 400 && httpStatus < 500)
  ) {
    return {
      resolution: "FAILED",
      reason: reason || "Order declined by provider",
      tokenOrPin: null,
      serialNumber: null,
      orderId,
      vouchers: [],
    };
  }

  return {
    resolution: "PENDING",
    reason: reason || "Awaiting provider confirmation",
    tokenOrPin,
    serialNumber,
    orderId,
    vouchers: [],
  };
}

/**
 * Strict server-side pricing override protection guard.
 * Prevents corrupt, negative, NaN, or accidental below-cost pricing overrides.
 */
export function validateAdminPricingOverride(input: {
  serviceType: string;
  providerCostKobo: number;
  sellingPriceKobo: number;
  feeKobo: number;
  discountPercent: number;
  commissionPercent: number;
  allowBelowCost?: boolean;
}): {
  providerCostKobo: number;
  sellingPriceKobo: number;
  feeKobo: number;
  discountPercent: number;
  commissionPercent: number;
} {
  const svc = String(input.serviceType || "").toUpperCase().trim();
  const isVariable = svc === "AIRTIME" || svc === "ELECTRICITY" || svc === "BETTING";

  const costKobo = Math.round(Number(input.providerCostKobo));
  const sellKobo = Math.round(Number(input.sellingPriceKobo));
  const feeKobo = Math.round(Number(input.feeKobo));
  const discountPercent = Number(input.discountPercent);
  const commissionPercent = Number(input.commissionPercent);

  if (
    !Number.isFinite(costKobo) ||
    !Number.isFinite(sellKobo) ||
    !Number.isFinite(feeKobo) ||
    !Number.isFinite(discountPercent) ||
    !Number.isFinite(commissionPercent)
  ) {
    throw new Error("Invalid numeric pricing values supplied.");
  }

  if (costKobo < 0 || sellKobo < 0 || feeKobo < 0 || feeKobo > 500000) {
    throw new Error("Prices and fees must be non-negative and within safe bounds (max fee ₦5,000).");
  }

  if (discountPercent < 0 || discountPercent > 25) {
    throw new Error("Customer discount must be between 0% and 25%.");
  }

  if (commissionPercent < 0 || commissionPercent > 50) {
    throw new Error("Provider commission must be between 0% and 50%.");
  }

  if (!isVariable) {
    if (sellKobo < 100) {
      throw new Error("Fixed-price plans must have a selling price of at least ₦1.00.");
    }
    if (costKobo > 0 && sellKobo + feeKobo < costKobo && !input.allowBelowCost) {
      throw new Error(
        `Protected against loss-making price: selling price + fee (₦${((sellKobo + feeKobo) / 100).toFixed(2)}) is below provider cost (₦${(costKobo / 100).toFixed(2)}).`
      );
    }
    if (costKobo > 0 && sellKobo > costKobo * 5) {
      throw new Error("Selling price exceeds 5x provider cost safety ceiling. Please verify your entry.");
    }
  } else if (svc === "AIRTIME" && discountPercent > commissionPercent + 2 && !input.allowBelowCost) {
    throw new Error(
      `Protected against excessive airtime loss: customer discount (${discountPercent}%) exceeds provider commission (${commissionPercent}%) by more than 2%.`
    );
  }

  return {
    providerCostKobo: costKobo,
    sellingPriceKobo: sellKobo,
    feeKobo,
    discountPercent: Number(discountPercent.toFixed(2)),
    commissionPercent: Number(commissionPercent.toFixed(2)),
  };
}
