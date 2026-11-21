import { db } from "./config";
import { ClubKonnectClient } from "./clubKonnectClient";
import { CatalogPricingDoc } from "./pricingEngine";

export interface LiveCatalogPlan {
  id: string;
  serviceType: string;
  providerCode: string;
  providerName: string;
  productCode: string;
  productName: string;
  categoryGroup: string;
  validity: string;
  dataVolume: string;
  providerCostKobo: number;
  sellingPriceKobo: number;
  feeKobo: number;
  discountPercent: number;
  commissionPercent: number;
  available: boolean;
}

export interface LiveServiceAvailabilityGroup {
  available: boolean;
  statusMessage: string;
  items: LiveCatalogPlan[];
}

export interface LiveStorefrontCatalogResponse {
  status: string;
  fetchedAt: string;
  clubKonnectConnected: boolean;
  totalPlans: number;
  airtime: LiveCatalogPlan[];
  data: LiveCatalogPlan[];
  electricity: LiveCatalogPlan[];
  cableTv: LiveCatalogPlan[];
  waec: LiveCatalogPlan[];
  jamb: LiveCatalogPlan[];
  rechargeCards: LiveServiceAvailabilityGroup;
  dataCards: LiveServiceAvailabilityGroup;
  broadband: LiveCatalogPlan[];
  betting: LiveCatalogPlan[];
}

const CACHE_TTL_MS = 5 * 60 * 1000;
let cachedCatalog: { expiresAt: number; data: LiveStorefrontCatalogResponse } | null = null;

const NETWORK_NAME_TO_CODE: Record<string, { code: string; name: string }> = {
  MTN: { code: "01", name: "MTN Nigeria" },
  "01": { code: "01", name: "MTN Nigeria" },
  GLO: { code: "02", name: "Globacom" },
  "02": { code: "02", name: "Globacom" },
  "9MOBILE": { code: "03", name: "9mobile" },
  ETISALAT: { code: "03", name: "9mobile" },
  "03": { code: "03", name: "9mobile" },
  AIRTEL: { code: "04", name: "Airtel Nigeria" },
  "04": { code: "04", name: "Airtel Nigeria" },
};

function resolveNetworkInfo(rawKey: string): { code: string; name: string } {
  const upper = rawKey.toUpperCase().trim();
  for (const [k, info] of Object.entries(NETWORK_NAME_TO_CODE)) {
    if (upper === k || upper.includes(k)) return info;
  }
  return { code: rawKey.trim() || "01", name: rawKey.trim() || "Network" };
}

function extractVolumeAndValidity(productName: string): { volume: string; validity: string } {
  const volMatch = productName.match(/(\d+(?:\.\d+)?\s*(?:MB|GB|TB))/i);
  const valMatch = productName.match(/(\d+\s*(?:day|days|week|weeks|month|months|hr|hrs|hour|hours|yr|year|years))/i);
  return {
    volume: volMatch ? volMatch[1].toUpperCase().replace(/\s+/g, "") : "",
    validity: valMatch ? valMatch[1] : "",
  };
}

function classifyDataCategory(productName: string, validity: string): string {
  const text = `${productName} ${validity}`.toLowerCase();
  if (/\bsme\b|\bsme\s*2\b/.test(text)) return "SME";
  if (/\bcorporate\b|\bcg\b|\bc\.g\b|\bgifting\b/.test(text)) return "Corporate / CG";
  if (
    /\bawoof\b|\bpromo\b|\bbonus\b|\bsocial\b|\bwhatsapp\b|\btiktok\b|\binstagram\b|\byoutube\b|\bnight\b|\bweekend\b|\bopera\b|\bstream\b|\bcampus\b|\bxtra\b/.test(
      text
    )
  ) {
    return "Awoof / Promo";
  }
  if (
    /\b(60|90|120|180|365)\s*day|\b(2|3|4|6|12)\s*month|\b1\s*year|\byearly\b|\bannual\b|\bmega\b|\brouter\b|\bmifi\b|\b5g\b|\bbroadband\b|\bhome\b/.test(
      text
    )
  ) {
    return "Mega / Multi-Month";
  }
  if (/\b(1|2|3|4|5)\s*day|\bdaily\b|\b24\s*h|\b48\s*h/.test(text)) return "Daily";
  if (/\b(7|14)\s*day|\bweekly\b|\b(1|2)\s*week/.test(text)) return "Weekly";
  if (/\b(28|30|31)\s*day|\bmonthly\b|\b1\s*month/.test(text)) return "Monthly";
  return "Direct";
}

function classifyBroadbandCategory(productName: string, validity: string): string {
  const text = `${productName} ${validity}`.toLowerCase();
  if (/\bvoice\b|\btalk\b|\bcall\b|\bairtime\b|\bmin(ute)?s?\b/.test(text)) return "Voice & Airtime";
  if (/\bunlimited\b|\balways\s*on\b|\bfreedom\b|\bwild\b|\bsuper\b/.test(text)) return "Unlimited";
  if (
    /\b(60|90|120|180|365)\s*day|\b(2|3|4|6|12)\s*month|\b1\s*year|\bannual\b|\byearly\b|\bjumbo\b/.test(text)
  ) {
    return "Multi-Month & Annual";
  }
  if (/\b(1|2|3|4|5)\s*day|\bdaily\b|\bflexi\b|\blite\b|\bmini\b|\bweekend\b|\bnight\b|\bmidnite\b|\bmidnight\b/.test(text)) {
    return "Daily & Flexi";
  }
  if (/\b(7|14)\s*day|\bweekly\b|\b(1|2)\s*week/.test(text)) return "Weekly";
  if (/\b(28|30|31)\s*day|\bmonthly\b|\b1\s*month|\banytime\b|\bbigga\b|\bbuma\b|\bvalue\b|\bclassic\b/.test(text)) {
    return "Monthly";
  }
  return "Standard";
}

const DATA_CATEGORY_ORDER = [
  "SME",
  "Corporate / CG",
  "Daily",
  "Weekly",
  "Monthly",
  "Awoof / Promo",
  "Mega / Multi-Month",
  "Direct",
];

const BROADBAND_CATEGORY_ORDER = [
  "Daily & Flexi",
  "Weekly",
  "Monthly",
  "Unlimited",
  "Multi-Month & Annual",
  "Voice & Airtime",
  "Standard",
];

function categoryRank(order: string[], cat: string): number {
  const idx = order.indexOf(cat);
  return idx >= 0 ? idx : order.length;
}

function parsePercentage(raw: unknown): number {
  const num = Number(String(raw ?? "").replace(/%/g, "").trim());
  if (!Number.isFinite(num) || num < 0) return 0;
  // ClubKonnect sometimes expresses discount as 0.97 (multiplier) or 3.0 (percent)
  if (num > 0 && num < 1) {
    return Number(((1 - num) * 100).toFixed(2));
  }
  return Math.min(50, Number(num.toFixed(2)));
}

function defaultSellingFromCostKobo(costKobo: number, serviceType: string): number {
  if (costKobo <= 0) return 0;
  // Safe default platform markup when admin hasn't overridden this plan in /serviceCatalog
  const markupPct =
    serviceType === "DATA" || serviceType === "DATA_CARD"
      ? 0.03
      : serviceType === "CABLE_TV" || serviceType === "SMILE" || serviceType === "SPECTRANET"
        ? 0.015
        : serviceType === "WAEC_PIN" || serviceType === "JAMB_PIN"
          ? 0.03
          : 0.02;
  // Round up to nearest ₦1 (100 kobo)
  return Math.ceil((costKobo * (1 + markupPct)) / 100) * 100;
}

function parseNestedProductArray(
  root: unknown,
  serviceType: string,
  defaultProviderCode = "",
  defaultProviderName = "",
  categoryGroup = ""
): LiveCatalogPlan[] {
  const results: LiveCatalogPlan[] = [];
  const seen = new Set<string>();

  const walk = (node: unknown, ctxProviderCode: string, ctxProviderName: string, ctxGroup: string) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      for (const item of node) {
        walk(item, ctxProviderCode, ctxProviderName, ctxGroup);
      }
      return;
    }

    const obj = node as Record<string, unknown>;
    let nextCode = ctxProviderCode;
    let nextName = ctxProviderName;
    let nextGroup = ctxGroup;

    // Detect network / disco / cable parent wrapper objects like { ID: "01", PRODUCT: [...] }
    const containerId = obj.ID ?? obj.id ?? obj.CODE ?? obj.code;
    const containerName = obj.NAME ?? obj.name ?? obj.COMPANY ?? obj.NETWORK;
    if (
      (obj.PRODUCT || obj.PRODUCT_LIST || obj.PLANS || obj.PACKAGES) &&
      (containerId || containerName)
    ) {
      if (serviceType === "DATA" || serviceType === "DATA_CARD" || serviceType === "RECHARGE_CARD" || serviceType === "AIRTIME") {
        const net = resolveNetworkInfo(String(containerId || containerName || ctxProviderCode));
        nextCode = net.code;
        nextName = net.name;
      } else {
        nextCode = String(containerId || ctxProviderCode).trim();
        nextName = String(containerName || ctxProviderName || nextCode).trim();
      }
    }

    // Detect leaf product object
    const prodCode = obj.PRODUCT_ID ?? obj.PRODUCT_CODE ?? obj.PACKAGE_ID ?? obj.EXAM_TYPE;
    const prodName = obj.PRODUCT_NAME ?? obj.PACKAGE_NAME ?? obj.NAME ?? obj.PRODUCT_DESCRIPTION;
    const rawAmount = obj.PRODUCT_AMOUNT ?? obj.PACKAGE_AMOUNT ?? obj.AMOUNT ?? obj.PRICE;

    if (prodCode && prodName) {
      const cleanCode = String(prodCode).trim();
      const cleanName = String(prodName).trim();
      const amountNaira = Number(String(rawAmount ?? "0").replace(/[^0-9.]/g, ""));
      const costKobo = Number.isFinite(amountNaira) && amountNaira > 0 ? Math.round(amountNaira * 100) : 0;
      const key = `${serviceType}_${nextCode}_${cleanCode}`.toLowerCase();

      if (!seen.has(key) && (costKobo > 0 || serviceType === "ELECTRICITY" || serviceType === "BETTING")) {
        seen.add(key);
        const { volume, validity } = extractVolumeAndValidity(cleanName);
        const availabilityFlag =
          String(obj.STATUS ?? obj.AVAILABLE ?? "AVAILABLE").toUpperCase();
        const isAvailable =
          !availabilityFlag.includes("UNAVAILABLE") &&
          !availabilityFlag.includes("OUT") &&
          !availabilityFlag.includes("DISABLED") &&
          availabilityFlag !== "FALSE" &&
          availabilityFlag !== "0";

        const resolvedValidity = String(obj.VALIDITY || validity || "");
        const resolvedCategory =
          serviceType === "DATA" || serviceType === "DATA_CARD"
            ? classifyDataCategory(cleanName, resolvedValidity)
            : serviceType === "SMILE" || serviceType === "SPECTRANET"
              ? classifyBroadbandCategory(cleanName, resolvedValidity)
              : nextGroup || categoryGroup || nextName;

        results.push({
          id: key.replace(/[^a-z0-9_-]/g, "_"),
          serviceType,
          providerCode: nextCode || defaultProviderCode,
          providerName: nextName || defaultProviderName || nextCode,
          productCode: cleanCode,
          productName: cleanName,
          categoryGroup: resolvedCategory,
          validity: resolvedValidity,
          dataVolume: String(obj.DATA_VOLUME || volume || ""),
          providerCostKobo: costKobo,
          sellingPriceKobo: defaultSellingFromCostKobo(costKobo, serviceType),
          feeKobo: serviceType === "ELECTRICITY" ? 10000 : 0,
          discountPercent: 0,
          commissionPercent: 0,
          available: isAvailable,
        });
      }
      return;
    }

    // Walk nested keys — key names often identify provider (e.g., "MTN", "DStv", "GOtv", "StarTimes", "EKEDC")
    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === "object") {
        let childCode = nextCode;
        let childName = nextName;
        let childGroup = nextGroup;

        const upperKey = k.toUpperCase();
        if (
          upperKey !== "MOBILE_NETWORK" &&
          upperKey !== "TV_ID" &&
          upperKey !== "ELECTRIC_COMPANY" &&
          upperKey !== "BETTING_COMPANY" &&
          upperKey !== "PRODUCT" &&
          upperKey !== "PRODUCT_LIST" &&
          upperKey !== "PACKAGES" &&
          upperKey !== "PLANS"
        ) {
          if (serviceType === "DATA" || serviceType === "DATA_CARD" || serviceType === "RECHARGE_CARD" || serviceType === "AIRTIME") {
            if (NETWORK_NAME_TO_CODE[upperKey] || upperKey.includes("MTN") || upperKey.includes("GLO") || upperKey.includes("AIRTEL") || upperKey.includes("9MOBILE") || upperKey.includes("ETISALAT")) {
              const net = resolveNetworkInfo(upperKey);
              childCode = net.code;
              childName = net.name;
            }
          } else if (serviceType === "CABLE_TV") {
            if (upperKey.includes("DSTV")) {
              childCode = "dstv";
              childName = "DStv";
            } else if (upperKey.includes("GOTV")) {
              childCode = "gotv";
              childName = "GOtv";
            } else if (upperKey.includes("STARTIMES")) {
              childCode = "startimes";
              childName = "StarTimes";
            } else if (upperKey.includes("SHOWMAX")) {
              childCode = "showmax";
              childName = "Showmax";
            }
          } else if (!childCode) {
            childCode = k.toLowerCase();
            childName = k;
          }
          childGroup = k;
        }
        walk(v, childCode, childName, childGroup);
      }
    }
  };

  walk(root, defaultProviderCode, defaultProviderName, categoryGroup);
  return results;
}

function parseAirtimeDiscounts(raw: Record<string, unknown>): LiveCatalogPlan[] {
  const defaultNetworks = [
    { code: "01", name: "MTN Nigeria" },
    { code: "02", name: "Globacom" },
    { code: "03", name: "9mobile" },
    { code: "04", name: "Airtel Nigeria" },
  ];
  const discountMap = new Map<string, number>();

  const walk = (node: unknown, parentKey = "") => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach((item) => walk(item, parentKey));
      return;
    }
    const obj = node as Record<string, unknown>;
    const idVal = String(obj.ID || obj.id || parentKey || "").trim();
    const discVal = obj.PRODUCT_DISCOUNT ?? obj.DISCOUNT ?? obj.PRODUCT_DISCOUNT_AMOUNT;
    if (idVal && discVal !== undefined) {
      const net = resolveNetworkInfo(idVal);
      const pct = parsePercentage(discVal);
      if (pct >= 0) discountMap.set(net.code, pct);
    }
    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === "object") {
        walk(v, k);
      } else if (NETWORK_NAME_TO_CODE[k.toUpperCase()]) {
        const net = resolveNetworkInfo(k);
        discountMap.set(net.code, parsePercentage(v));
      }
    }
  };

  walk(raw);

  return defaultNetworks.map((net) => {
    const commission = discountMap.get(net.code) ?? 2.0;
    const customerDiscount = Math.max(0, Number((commission * 0.5).toFixed(2)));
    return {
      id: `airtime_${net.code}`,
      serviceType: "AIRTIME",
      providerCode: net.code,
      providerName: net.name,
      productCode: "airtime",
      productName: `${net.name} Airtime`,
      categoryGroup: "Airtime",
      validity: "Instant",
      dataVolume: "",
      providerCostKobo: 0,
      sellingPriceKobo: 0,
      feeKobo: 0,
      discountPercent: customerDiscount,
      commissionPercent: commission,
      available: true,
    };
  });
}

function parseElectricityDiscos(raw: Record<string, unknown>): LiveCatalogPlan[] {
  const discovered: LiveCatalogPlan[] = [];
  const seen = new Set<string>();

  const walk = (node: unknown) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    const obj = node as Record<string, unknown>;
    const id = String(obj.ID || obj.id || obj.CODE || "").trim();
    const name = String(obj.NAME || obj.name || obj.PRODUCT_NAME || "").trim();
    if (id && name && /^\d{2}$/.test(id) && !seen.has(id)) {
      seen.add(id);
      discovered.push({
        id: `electricity_${id}`,
        serviceType: "ELECTRICITY",
        providerCode: id,
        providerName: name,
        productCode: "prepaid",
        productName: `${name} (Prepaid / Postpaid)`,
        categoryGroup: "Electricity",
        validity: "Instant Token",
        dataVolume: "",
        providerCostKobo: 0,
        sellingPriceKobo: 0,
        feeKobo: 10000,
        discountPercent: 0,
        commissionPercent: 1.0,
        available: true,
      });
    }
    Object.values(obj).forEach(walk);
  };

  walk(raw);

  if (discovered.length > 0) return discovered;

  // Fallback list of official ClubKonnect DisCo codes (01..12)
  const fallbackDiscos = [
    ["01", "Eko Electric (EKEDC)"],
    ["02", "Ikeja Electric (IKEDC)"],
    ["03", "Abuja Electric (AEDC)"],
    ["04", "Kano Electric (KEDCO)"],
    ["05", "Port Harcourt Electric (PHED)"],
    ["06", "Jos Electric (JED)"],
    ["07", "Ibadan Electric (IBEDC)"],
    ["08", "Kaduna Electric (KAEDCO)"],
    ["09", "Enugu Electric (EEDC)"],
    ["10", "Benin Electric (BEDC)"],
    ["11", "Yola Electric (YEDC)"],
    ["12", "Aba Electric (ABA)"],
  ];
  return fallbackDiscos.map(([code, name]) => ({
    id: `electricity_${code}`,
    serviceType: "ELECTRICITY",
    providerCode: code,
    providerName: name,
    productCode: "prepaid",
    productName: `${name} (Prepaid / Postpaid)`,
    categoryGroup: "Electricity",
    validity: "Instant Token",
    dataVolume: "",
    providerCostKobo: 0,
    sellingPriceKobo: 0,
    feeKobo: 10000,
    discountPercent: 0,
    commissionPercent: 1.0,
    available: true,
  }));
}

function parseBettingCompanies(raw: Record<string, unknown>): LiveCatalogPlan[] {
  const discovered: LiveCatalogPlan[] = [];
  const seen = new Set<string>();

  const walk = (node: unknown) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    const obj = node as Record<string, unknown>;
    const code = String(obj.PRODUCT_CODE || obj.PRODUCT_ID || obj.ID || "").trim();
    const name = String(obj.PRODUCT_NAME || obj.NAME || code).trim();
    if (code && name && !seen.has(code.toLowerCase()) && code.length <= 40) {
      seen.add(code.toLowerCase());
      discovered.push({
        id: `betting_${code.toLowerCase().replace(/[^a-z0-9_-]/g, "_")}`,
        serviceType: "BETTING",
        providerCode: code,
        providerName: name,
        productCode: code,
        productName: `${name} Wallet Funding`,
        categoryGroup: "Betting",
        validity: "Instant",
        dataVolume: "",
        providerCostKobo: 0,
        sellingPriceKobo: 0,
        feeKobo: 5000,
        discountPercent: 0,
        commissionPercent: 0,
        available: true,
      });
    }
    Object.values(obj).forEach(walk);
  };

  walk(raw);
  return discovered;
}

function parseRechargeCardAvailability(
  raw: Record<string, unknown> | null,
  fetchFailed: boolean
): LiveServiceAvailabilityGroup {
  if (fetchFailed || !raw || raw.raw) {
    return {
      available: false,
      statusMessage: "Recharge Card e-PIN printing is currently unavailable. Please check back shortly.",
      items: [],
    };
  }

  const rawText = JSON.stringify(raw).toUpperCase();
  if (rawText.includes("UNAVAILABLE") || rawText.includes("OUT_OF_STOCK") || rawText.includes("NO_RECORD")) {
    return {
      available: false,
      statusMessage: "Recharge Card e-PINs are currently out of stock.",
      items: [],
    };
  }

  const items: LiveCatalogPlan[] = [];
  const seen = new Set<string>();
  const networks = [
    { code: "01", name: "MTN Nigeria" },
    { code: "02", name: "Globacom" },
    { code: "03", name: "9mobile" },
    { code: "04", name: "Airtel Nigeria" },
  ];

  // Try parsing explicit per-denomination entries from APIEPINDiscountV2.asp first
  const parsedExplicit = parseNestedProductArray(raw, "RECHARGE_CARD");
  if (parsedExplicit.length > 0) {
    for (const p of parsedExplicit) {
      const denomMatch = p.productName.match(/\b(100|200|500|1000)\b/) || p.productCode.match(/\b(100|200|500|1000)\b/);
      const denom = denomMatch ? Number(denomMatch[1]) : Math.round(p.providerCostKobo / 100);
      if (![100, 200, 500, 1000].includes(denom)) continue;
      const key = `${p.providerCode}_${denom}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const faceKobo = denom * 100;
      const costKobo = p.providerCostKobo > 0 ? p.providerCostKobo : Math.round(faceKobo * 0.98);
      items.push({
        ...p,
        id: `epin_${p.providerCode}_${denom}`,
        productCode: String(denom),
        productName: `${p.providerName} ₦${denom} Recharge e-PIN`,
        providerCostKobo: costKobo,
        sellingPriceKobo: Math.max(costKobo, faceKobo),
        validity: "Printable Voucher",
      });
    }
  }

  // Also parse network-level discount map if APIEPINDiscountV2 returns discounts per network
  if (items.length === 0) {
    const discountByNet = new Map<string, number>();
    const walk = (node: unknown, parentKey = "") => {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node)) {
        node.forEach((n) => walk(n, parentKey));
        return;
      }
      const obj = node as Record<string, unknown>;
      const id = String(obj.ID || obj.id || parentKey || "").trim();
      const disc = obj.PRODUCT_DISCOUNT ?? obj.DISCOUNT ?? obj.PRODUCT_AMOUNT;
      if (id && disc !== undefined && (NETWORK_NAME_TO_CODE[id.toUpperCase()] || ["01", "02", "03", "04"].includes(id))) {
        const net = resolveNetworkInfo(id);
        discountByNet.set(net.code, parsePercentage(disc));
      }
      for (const [k, v] of Object.entries(obj)) {
        if (v && typeof v === "object") walk(v, k);
      }
    };
    walk(raw);

    if (discountByNet.size > 0) {
      for (const net of networks) {
        if (!discountByNet.has(net.code)) continue;
        const commPct = discountByNet.get(net.code) || 1.5;
        for (const denom of [100, 200, 500, 1000]) {
          const faceKobo = denom * 100;
          const costKobo = Math.round(faceKobo * (1 - commPct / 100));
          items.push({
            id: `epin_${net.code}_${denom}`,
            serviceType: "RECHARGE_CARD",
            providerCode: net.code,
            providerName: net.name,
            productCode: String(denom),
            productName: `${net.name} ₦${denom} Recharge e-PIN`,
            categoryGroup: "Recharge Card e-PIN",
            validity: "Printable PDF Voucher",
            dataVolume: "",
            providerCostKobo: costKobo,
            sellingPriceKobo: faceKobo,
            feeKobo: 0,
            discountPercent: 0,
            commissionPercent: commPct,
            available: true,
          });
        }
      }
    }
  }

  const anyAvailable = items.some((i) => i.available);
  return {
    available: anyAvailable,
    statusMessage: anyAvailable
      ? `Recharge Card e-PIN printing is available (${items.filter((i) => i.available).length} denominations ready).`
      : "Recharge Card e-PIN stock is currently unavailable.",
    items,
  };
}

function parseDataCardAvailability(
  raw: Record<string, unknown> | null,
  fetchFailed: boolean
): LiveServiceAvailabilityGroup {
  if (fetchFailed || !raw || raw.raw) {
    return {
      available: false,
      statusMessage: "Data Card e-PIN printing is currently unavailable.",
      items: [],
    };
  }
  const rawText = JSON.stringify(raw).toUpperCase();
  if (
    rawText.includes("UNAVAILABLE") ||
    rawText.includes("OUT_OF_STOCK") ||
    rawText.includes("INVALID") ||
    rawText.includes("NOT_FOUND")
  ) {
    return {
      available: false,
      statusMessage: "Data Card e-PIN stock is currently unavailable.",
      items: [],
    };
  }

  const parsed = parseNestedProductArray(raw, "DATA_CARD");
  const availableItems = parsed.filter((i) => i.available && i.providerCostKobo > 0);
  return {
    available: availableItems.length > 0,
    statusMessage:
      availableItems.length > 0
        ? `Data Card e-PIN printing is available (${availableItems.length} printable data card plans ready).`
        : "Data Card e-PIN plans are currently unavailable.",
    items: availableItems,
  };
}

function applyAdminCatalogOverrides(
  livePlans: LiveCatalogPlan[],
  adminDocs: Array<Record<string, unknown>>
): LiveCatalogPlan[] {
  const byExact = new Map<string, Record<string, unknown>>();
  const byProvider = new Map<string, Record<string, unknown>>();

  for (const doc of adminDocs) {
    const svc = String(doc.serviceType || "").toUpperCase();
    const prov = String(doc.providerCode || "").trim().toLowerCase();
    const prod = String(doc.productCode || "").trim().toLowerCase();
    byExact.set(`${svc}|${prov}|${prod}`, doc);
    byProvider.set(`${svc}|${prov}`, doc);
  }

  return livePlans
    .map((plan) => {
      const exactKey = `${plan.serviceType}|${plan.providerCode.toLowerCase()}|${plan.productCode.toLowerCase()}`;
      const provKey = `${plan.serviceType}|${plan.providerCode.toLowerCase()}`;
      const override =
        byExact.get(exactKey) ||
        (plan.serviceType === "AIRTIME" || plan.serviceType === "ELECTRICITY"
          ? byProvider.get(provKey) || byProvider.get(`${plan.serviceType}|all_discos`)
          : undefined);

      if (!override) return plan;

      const enabled = override.enabled !== false;
      const overrideSell = Number(override.sellingPriceKobo ?? 0);
      const overrideCost = Number(override.providerCostKobo ?? 0);
      const overrideFee = Number(override.feeKobo ?? plan.feeKobo);
      const overrideDisc = Number(override.discountPercent ?? plan.discountPercent);
      const overrideComm = Number(override.commissionPercent ?? plan.commissionPercent);

      const finalCost = plan.providerCostKobo > 0 ? plan.providerCostKobo : overrideCost;
      const finalSell =
        overrideSell > 0
          ? Math.max(overrideSell, finalCost)
          : plan.sellingPriceKobo;

      return {
        ...plan,
        providerCostKobo: finalCost,
        sellingPriceKobo: finalSell,
        feeKobo: Number.isFinite(overrideFee) ? overrideFee : plan.feeKobo,
        discountPercent: Number.isFinite(overrideDisc) ? overrideDisc : plan.discountPercent,
        commissionPercent: Number.isFinite(overrideComm) ? overrideComm : plan.commissionPercent,
        available: plan.available && enabled,
      };
    })
    .filter((p) => p.available || p.serviceType === "RECHARGE_CARD" || p.serviceType === "DATA_CARD");
}

export function invalidateLiveCatalogCache() {
  cachedCatalog = null;
}

export async function getLiveStorefrontCatalog(
  clubKonnect: ClubKonnectClient,
  forceRefresh = false
): Promise<LiveStorefrontCatalogResponse> {
  const now = Date.now();
  if (!forceRefresh && cachedCatalog && cachedCatalog.expiresAt > now) {
    return cachedCatalog.data;
  }

  const adminCatalogSnap = await db.collection("serviceCatalog").get();
  const adminDocs = adminCatalogSnap.docs.map((d) => ({ id: d.id, ...d.data() })) as Array<Record<string, unknown>>;

  if (!clubKonnect.isConfigured) {
    const emptyRes: LiveStorefrontCatalogResponse = {
      status: "unconfigured",
      fetchedAt: new Date().toISOString(),
      clubKonnectConnected: false,
      totalPlans: 0,
      airtime: [],
      data: [],
      electricity: [],
      cableTv: [],
      waec: [],
      jamb: [],
      rechargeCards: { available: false, statusMessage: "Recharge Card service is currently unavailable.", items: [] },
      dataCards: { available: false, statusMessage: "Data Card service is currently unavailable.", items: [] },
      broadband: [],
      betting: [],
    };
    return emptyRes;
  }

  const [
    airtimeRes,
    dataRes,
    discoRes,
    cableRes,
    waecRes,
    jambRes,
    epinRes,
    dataCardRes,
    smileRes,
    spectranetRes,
    bettingRes,
  ] = await Promise.allSettled([
    clubKonnect.fetchAirtimeDiscounts(),
    clubKonnect.fetchDataPlans(),
    clubKonnect.fetchElectricityDiscos(),
    clubKonnect.fetchCablePackages(),
    clubKonnect.fetchWaecPackages(),
    clubKonnect.fetchJambPackages(),
    clubKonnect.fetchEpinDiscounts(),
    clubKonnect.fetchDataCardPlans(),
    clubKonnect.fetchSmilePackages(),
    clubKonnect.fetchSpectranetPackages(),
    clubKonnect.fetchBettingCompanies(),
  ]);

  const airtime = applyAdminCatalogOverrides(
    parseAirtimeDiscounts(airtimeRes.status === "fulfilled" ? airtimeRes.value : {}),
    adminDocs
  );
  const data = applyAdminCatalogOverrides(
    dataRes.status === "fulfilled" ? parseNestedProductArray(dataRes.value, "DATA") : [],
    adminDocs
  ).sort(
    (a, b) =>
      a.providerCode.localeCompare(b.providerCode) ||
      categoryRank(DATA_CATEGORY_ORDER, a.categoryGroup) - categoryRank(DATA_CATEGORY_ORDER, b.categoryGroup) ||
      a.sellingPriceKobo - b.sellingPriceKobo
  );

  const electricity = applyAdminCatalogOverrides(
    parseElectricityDiscos(discoRes.status === "fulfilled" ? discoRes.value : {}),
    adminDocs
  );

  const cableTv = applyAdminCatalogOverrides(
    cableRes.status === "fulfilled" ? parseNestedProductArray(cableRes.value, "CABLE_TV") : [],
    adminDocs
  ).sort((a, b) => a.providerCode.localeCompare(b.providerCode) || a.sellingPriceKobo - b.sellingPriceKobo);

  const waec = applyAdminCatalogOverrides(
    waecRes.status === "fulfilled" ? parseNestedProductArray(waecRes.value, "WAEC_PIN", "waec", "WAEC") : [],
    adminDocs
  );

  const jamb = applyAdminCatalogOverrides(
    jambRes.status === "fulfilled" ? parseNestedProductArray(jambRes.value, "JAMB_PIN", "jamb", "JAMB") : [],
    adminDocs
  );

  const rawRecharge = parseRechargeCardAvailability(
    epinRes.status === "fulfilled" ? epinRes.value : null,
    epinRes.status === "rejected"
  );
  const rechargeItems = applyAdminCatalogOverrides(rawRecharge.items, adminDocs);
  const rechargeCards: LiveServiceAvailabilityGroup = {
    available: rechargeItems.some((i) => i.available),
    statusMessage: rawRecharge.statusMessage,
    items: rechargeItems,
  };

  const rawDataCards = parseDataCardAvailability(
    dataCardRes.status === "fulfilled" ? dataCardRes.value : null,
    dataCardRes.status === "rejected"
  );
  const dataCardItems = applyAdminCatalogOverrides(rawDataCards.items, adminDocs);
  const dataCards: LiveServiceAvailabilityGroup = {
    available: dataCardItems.some((i) => i.available),
    statusMessage: rawDataCards.statusMessage,
    items: dataCardItems,
  };

  const smilePlans =
    smileRes.status === "fulfilled"
      ? parseNestedProductArray(smileRes.value, "SMILE", "smile-direct", "Smile Broadband")
      : [];
  const spectranetPlans =
    spectranetRes.status === "fulfilled"
      ? parseNestedProductArray(spectranetRes.value, "SPECTRANET", "spectranet", "Spectranet")
      : [];
  const broadband = applyAdminCatalogOverrides([...smilePlans, ...spectranetPlans], adminDocs).sort(
    (a, b) =>
      a.serviceType.localeCompare(b.serviceType) ||
      categoryRank(BROADBAND_CATEGORY_ORDER, a.categoryGroup) - categoryRank(BROADBAND_CATEGORY_ORDER, b.categoryGroup) ||
      a.sellingPriceKobo - b.sellingPriceKobo
  );

  const betting = applyAdminCatalogOverrides(
    bettingRes.status === "fulfilled" ? parseBettingCompanies(bettingRes.value) : [],
    adminDocs
  );

  const totalPlans =
    airtime.length +
    data.length +
    electricity.length +
    cableTv.length +
    waec.length +
    jamb.length +
    rechargeCards.items.length +
    dataCards.items.length +
    broadband.length +
    betting.length;

  const response: LiveStorefrontCatalogResponse = {
    status: "success",
    fetchedAt: new Date().toISOString(),
    clubKonnectConnected: true,
    totalPlans,
    airtime,
    data,
    electricity,
    cableTv,
    waec,
    jamb,
    rechargeCards,
    dataCards,
    broadband,
    betting,
  };

  cachedCatalog = { expiresAt: now + CACHE_TTL_MS, data: response };
  return response;
}

export async function findAuthorizedLiveProduct(
  clubKonnect: ClubKonnectClient,
  serviceType: string,
  providerCode: string,
  productCode: string
): Promise<{ pricingDoc: CatalogPricingDoc | null; available: boolean; unavailableReason?: string }> {
  const catalog = await getLiveStorefrontCatalog(clubKonnect, false);
  const svc = serviceType.toUpperCase();
  const prov = providerCode.trim().toLowerCase();
  const prod = productCode.trim().toLowerCase();

  if (svc === "RECHARGE_CARD") {
    if (!catalog.rechargeCards.available) {
      return {
        pricingDoc: null,
        available: false,
        unavailableReason: catalog.rechargeCards.statusMessage,
      };
    }
    const found = catalog.rechargeCards.items.find(
      (i) => i.providerCode.toLowerCase() === prov && i.productCode.toLowerCase() === prod
    );
    if (!found || !found.available) {
      return {
        pricingDoc: null,
        available: false,
        unavailableReason: "Selected recharge card denomination is currently out of stock.",
      };
    }
    return { pricingDoc: found, available: true };
  }

  if (svc === "DATA_CARD") {
    if (!catalog.dataCards.available) {
      return {
        pricingDoc: null,
        available: false,
        unavailableReason: catalog.dataCards.statusMessage,
      };
    }
    const found = catalog.dataCards.items.find(
      (i) => i.providerCode.toLowerCase() === prov && i.productCode.toLowerCase() === prod
    );
    if (!found || !found.available) {
      return {
        pricingDoc: null,
        available: false,
        unavailableReason: "Selected data card plan is currently out of stock.",
      };
    }
    return { pricingDoc: found, available: true };
  }

  const pool: LiveCatalogPlan[] =
    svc === "AIRTIME"
      ? catalog.airtime
      : svc === "DATA"
        ? catalog.data
        : svc === "ELECTRICITY"
          ? catalog.electricity
          : svc === "CABLE_TV"
            ? catalog.cableTv
            : svc === "WAEC_PIN"
              ? catalog.waec
              : svc === "JAMB_PIN"
                ? catalog.jamb
                : svc === "SMILE" || svc === "SPECTRANET"
                  ? catalog.broadband
                  : svc === "BETTING"
                    ? catalog.betting
                    : [];

  const isVariableSvc = svc === "AIRTIME" || svc === "ELECTRICITY" || svc === "BETTING";
  const matched =
    pool.find((i) => i.providerCode.toLowerCase() === prov && i.productCode.toLowerCase() === prod) ||
    (prod ? pool.find((i) => i.productCode.toLowerCase() === prod) : undefined) ||
    (isVariableSvc ? pool.find((i) => i.providerCode.toLowerCase() === prov) : undefined);

  return { pricingDoc: matched || null, available: true };
}
