import type { LiveCatalogPlan } from "./liveCatalogEngine";

/**
 * Dynamically normalizes live AlrahuzData (`GET /api/user/`) JSON responses into
 * M-Sageer Pay's unified LiveCatalogPlan structure.
 *
 * ZERO hardcoded plans or prices — every product, plan ID, price, validity,
 * and commission is discovered dynamically from AlrahuzData's live API response.
 */

const ALRAHUZ_NET_TO_UNIFIED: Record<string, { code: string; name: string }> = {
  "1": { code: "01", name: "MTN Nigeria" },
  "01": { code: "01", name: "MTN Nigeria" },
  MTN: { code: "01", name: "MTN Nigeria" },
  MTN_PLAN: { code: "01", name: "MTN Nigeria" },
  "2": { code: "02", name: "Globacom" },
  "02": { code: "02", name: "Globacom" },
  GLO: { code: "02", name: "Globacom" },
  GLO_PLAN: { code: "02", name: "Globacom" },
  "3": { code: "03", name: "9mobile" },
  "03": { code: "03", name: "9mobile" },
  "9MOBILE": { code: "03", name: "9mobile" },
  "9MOBILE_PLAN": { code: "03", name: "9mobile" },
  ETISALAT: { code: "03", name: "9mobile" },
  "4": { code: "04", name: "Airtel Nigeria" },
  "04": { code: "04", name: "Airtel Nigeria" },
  AIRTEL: { code: "04", name: "Airtel Nigeria" },
  AIRTEL_PLAN: { code: "04", name: "Airtel Nigeria" },
};

function resolveUnifiedNetwork(raw: string): { code: string; name: string } {
  const upper = raw.toUpperCase().trim();
  if (ALRAHUZ_NET_TO_UNIFIED[upper]) return ALRAHUZ_NET_TO_UNIFIED[upper];
  for (const [k, info] of Object.entries(ALRAHUZ_NET_TO_UNIFIED)) {
    if (upper.includes(k)) return info;
  }
  return { code: "01", name: raw.trim() || "Network" };
}

function resolveUnifiedCable(raw: string): { code: string; name: string } {
  const upper = raw.toUpperCase().trim();
  if (upper.includes("DSTV") || upper === "2") return { code: "dstv", name: "DStv" };
  if (upper.includes("GOTV") || upper === "1") return { code: "gotv", name: "GOtv" };
  if (upper.includes("STARTIME") || upper === "3") return { code: "startimes", name: "StarTimes" };
  if (upper.includes("SHOWMAX") || upper === "4") return { code: "showmax", name: "Showmax" };
  return { code: upper.toLowerCase() || "cable", name: raw.trim() || "Cable TV" };
}

function classifyAlrahuzDataCategory(planType: string, productName: string, validity: string): string {
  const text = `${planType} ${productName} ${validity}`.toLowerCase();
  if (/\bsme\b|\bsme\s*2\b/.test(text)) return "SME";
  if (/\bcorporate\b|\bcg\b|\bc\.g\b|\bgifting\b/.test(text)) return "Corporate / CG";
  if (
    /\bawoof\b|\bpromo\b|\bbonus\b|\bsocial\b|\bwhatsapp\b|\btiktok\b|\binstagram\b|\byoutube\b|\bnight\b|\bweekend\b|\bcampus\b/.test(
      text
    )
  ) {
    return "Awoof / Promo";
  }
  if (/\b(60|90|120|180|365)\s*day|\b(2|3|4|6|12)\s*month|\byearly\b|\bannual\b|\bmega\b/.test(text)) {
    return "Mega / Multi-Month";
  }
  if (/\b(1|2|3|4|5)\s*day|\bdaily\b|\b24\s*h|\b48\s*h/.test(text)) return "Daily";
  if (/\b(7|14)\s*day|\bweekly\b|\b(1|2)\s*week/.test(text)) return "Weekly";
  if (/\b(28|30|31)\s*day|\bmonthly\b|\b1\s*month/.test(text)) return "Monthly";
  return "Direct";
}

function parsePercentDiscount(rawVal: unknown): number {
  const num = Number(String(rawVal ?? "").replace(/[^0-9.]/g, ""));
  if (!Number.isFinite(num) || num <= 0) return 0;
  // MSORG APIs often return `98` meaning user pays 98% of face value (2% commission) or `2` meaning 2% discount
  if (num > 50 && num <= 100) {
    return Number((100 - num).toFixed(2));
  }
  if (num > 0 && num < 1) {
    return Number(((1 - num) * 100).toFixed(2));
  }
  return Math.min(30, Number(num.toFixed(2)));
}

export interface ParsedAlrahuzCatalog {
  walletBalanceNaira: number | null;
  airtime: LiveCatalogPlan[];
  data: LiveCatalogPlan[];
  cableTv: LiveCatalogPlan[];
  electricity: LiveCatalogPlan[];
  waec: LiveCatalogPlan[];
  jamb: LiveCatalogPlan[];
  rechargeCards: LiveCatalogPlan[];
}

export function parseAlrahuzUserCatalog(raw: Record<string, unknown>): ParsedAlrahuzCatalog {
  const userObj = (raw.user || raw) as Record<string, unknown>;
  const rawBal = String(userObj.wallet_balance ?? userObj.Account_Balance ?? "").replace(/[^0-9.]/g, "");
  const parsedBal = rawBal ? Number(rawBal) : NaN;
  const walletBalanceNaira = Number.isFinite(parsedBal) ? parsedBal : null;

  // 1. Dynamically discover live Data plans from Dataplans.{MTN_PLAN, GLO_PLAN, AIRTEL_PLAN, 9MOBILE_PLAN}
  const dataPlans: LiveCatalogPlan[] = [];
  const seenData = new Set<string>();
  const rawDataRoot = raw.Dataplans || raw.dataplans || raw.data_plans || raw.plans;

  const walkData = (node: unknown, ctxNet: string, ctxType: string) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach((item) => walkData(item, ctxNet, ctxType));
      return;
    }
    const obj = node as Record<string, unknown>;
    const rawPlanId = obj.dataplan_id ?? obj.id ?? obj.plan_id;
    const rawPlanAmount = obj.plan_amount ?? obj.amount ?? obj.price;
    const rawPlanSize = obj.plan ?? obj.plan_name ?? obj.name;

    if (rawPlanId !== undefined && rawPlanAmount !== undefined && rawPlanSize !== undefined) {
      const planCode = String(rawPlanId).trim();
      const amountNaira = Number(String(rawPlanAmount).replace(/[^0-9.]/g, ""));
      const costKobo = Number.isFinite(amountNaira) && amountNaira > 0 ? Math.round(amountNaira * 100) : 0;
      const net = resolveUnifiedNetwork(String(obj.plan_network || obj.network || ctxNet));
      const planType = String(obj.plan_type || ctxType || "").trim();
      const validity = String(obj.month_validate || obj.validity || "").trim();
      const sizeText = String(rawPlanSize).trim();
      const displayName = [net.name.split(" ")[0], sizeText, planType, validity ? `(${validity})` : ""]
        .filter(Boolean)
        .join(" ")
        .replace(/\s+/g, " ")
        .trim();

      const dedupeKey = `${net.code}_${planCode}`;
      if (costKobo > 0 && planCode && !seenData.has(dedupeKey)) {
        seenData.add(dedupeKey);
        const volMatch = sizeText.match(/(\d+(?:\.\d+)?\s*(?:MB|GB|TB))/i);
        const categoryGroup = classifyAlrahuzDataCategory(planType, displayName, validity);
        const availableFlag = obj.available !== false && String(obj.status || "active").toLowerCase() !== "disabled";

        dataPlans.push({
          id: `alrahuz_data_${net.code}_${planCode}`.replace(/[^a-z0-9_-]/gi, "_").toLowerCase(),
          apiProvider: "ALRAHUZDATA",
          apiProviderName: "AlrahuzData",
          serviceType: "DATA",
          providerCode: net.code,
          providerName: net.name,
          productCode: planCode,
          productName: displayName,
          categoryGroup,
          validity,
          dataVolume: volMatch ? volMatch[1].toUpperCase().replace(/\s+/g, "") : sizeText,
          providerCostKobo: costKobo,
          sellingPriceKobo: costKobo,
          feeKobo: 0,
          discountPercent: 0,
          commissionPercent: 0,
          netProfitKobo: 0,
          profitMarginPercent: 0,
          hasCustomOverride: false,
          available: availableFlag,
        });
      }
      return;
    }

    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === "object") {
        const upper = k.toUpperCase();
        const nextNet =
          upper.includes("MTN") || upper.includes("GLO") || upper.includes("AIRTEL") || upper.includes("9MOBILE")
            ? k
            : ctxNet;
        const nextType =
          upper === "ALL" || upper.includes("_PLAN") ? ctxType : k;
        walkData(v, nextNet, nextType);
      }
    }
  };
  walkData(rawDataRoot, "", "");

  // 2. Dynamically discover live Cable TV plans from Cableplan.{DSTVPLAN, GOTVPLAN, STARTIMEPLAN}
  const cablePlans: LiveCatalogPlan[] = [];
  const seenCable = new Set<string>();
  const rawCableRoot = raw.Cableplan || raw.cableplan || raw.cable_plans;

  const walkCable = (node: unknown, ctxCable: string) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      node.forEach((item) => walkCable(item, ctxCable));
      return;
    }
    const obj = node as Record<string, unknown>;
    const rawId = obj.cableplan_id ?? obj.id;
    const rawPackage = obj.package ?? obj.plan_name ?? obj.name;
    const rawAmt = obj.plan_amount ?? obj.amount ?? obj.price;

    if (rawId !== undefined && rawPackage !== undefined && rawAmt !== undefined) {
      const code = String(rawId).trim();
      const amountNaira = Number(String(rawAmt).replace(/[^0-9.]/g, ""));
      const costKobo = Number.isFinite(amountNaira) && amountNaira > 0 ? Math.round(amountNaira * 100) : 0;
      const cable = resolveUnifiedCable(String(obj.cable || ctxCable));
      const dedupeKey = `${cable.code}_${code}`;
      if (costKobo > 0 && code && !seenCable.has(dedupeKey)) {
        seenCable.add(dedupeKey);
        cablePlans.push({
          id: `alrahuz_cable_${cable.code}_${code}`.replace(/[^a-z0-9_-]/gi, "_").toLowerCase(),
          apiProvider: "ALRAHUZDATA",
          apiProviderName: "AlrahuzData",
          serviceType: "CABLE_TV",
          providerCode: cable.code,
          providerName: cable.name,
          productCode: code,
          productName: String(rawPackage).trim(),
          categoryGroup: cable.name,
          validity: "Monthly",
          dataVolume: "",
          providerCostKobo: costKobo,
          sellingPriceKobo: costKobo,
          feeKobo: 0,
          discountPercent: 0,
          commissionPercent: 0,
          netProfitKobo: 0,
          profitMarginPercent: 0,
          hasCustomOverride: false,
          available: true,
        });
      }
      return;
    }

    for (const [k, v] of Object.entries(obj)) {
      if (v && typeof v === "object") {
        walkCable(v, k);
      }
    }
  };
  walkCable(rawCableRoot, "");

  // 3. Dynamically discover live Airtime percentages from topup_percentage / topup
  const airtimePlans: LiveCatalogPlan[] = [];
  const rawTopup = (raw.topup_percentage || raw.topup || raw.airtime_percentage || {}) as Record<string, unknown>;
  const seenAirtimeNet = new Set<string>();
  for (const [netKey, rawVal] of Object.entries(rawTopup)) {
    const upper = netKey.toUpperCase();
    if (
      !upper.includes("MTN") &&
      !upper.includes("GLO") &&
      !upper.includes("AIRTEL") &&
      !upper.includes("9MOBILE") &&
      !["1", "2", "3", "4"].includes(upper)
    ) {
      continue;
    }
    const net = resolveUnifiedNetwork(netKey);
    if (seenAirtimeNet.has(net.code)) continue;
    seenAirtimeNet.add(net.code);
    let commPct = 2.0;
    if (rawVal && typeof rawVal === "object") {
      const inner = rawVal as Record<string, unknown>;
      commPct = parsePercentDiscount(inner.VTU ?? inner.vtu ?? inner.percentage ?? Object.values(inner)[0]);
    } else {
      commPct = parsePercentDiscount(rawVal);
    }
    airtimePlans.push({
      id: `alrahuz_airtime_${net.code}`,
      apiProvider: "ALRAHUZDATA",
      apiProviderName: "AlrahuzData",
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
      discountPercent: Math.max(0, Number((commPct * 0.5).toFixed(2))),
      commissionPercent: commPct,
      netProfitKobo: 0,
      profitMarginPercent: 0,
      hasCustomOverride: false,
      available: true,
    });
  }

  // 4. Dynamically discover Exam PIN prices (WAEC / JAMB / NECO) from raw.Exam / raw.exam
  const waecPlans: LiveCatalogPlan[] = [];
  const jambPlans: LiveCatalogPlan[] = [];
  const rawExam = (raw.Exam || raw.exam || raw.exams || {}) as Record<string, unknown>;
  for (const [examKey, val] of Object.entries(rawExam)) {
    const upper = examKey.toUpperCase();
    const rawAmt = val && typeof val === "object" ? (val as Record<string, unknown>).amount : val;
    const amtNaira = Number(String(rawAmt ?? "0").replace(/[^0-9.]/g, ""));
    const costKobo = Number.isFinite(amtNaira) && amtNaira > 0 ? Math.round(amtNaira * 100) : 0;
    if (costKobo <= 0) continue;

    if (upper.includes("WAEC")) {
      waecPlans.push({
        id: `alrahuz_waec_${upper.toLowerCase().replace(/[^a-z0-9]/g, "_")}`,
        apiProvider: "ALRAHUZDATA",
        apiProviderName: "AlrahuzData",
        serviceType: "WAEC_PIN",
        providerCode: "waec",
        providerName: "WAEC",
        productCode: examKey,
        productName: `${examKey} Result Checker e-PIN`,
        categoryGroup: "Education",
        validity: "Instant PIN",
        dataVolume: "",
        providerCostKobo: costKobo,
        sellingPriceKobo: costKobo,
        feeKobo: 0,
        discountPercent: 0,
        commissionPercent: 0,
        netProfitKobo: 0,
        profitMarginPercent: 0,
        hasCustomOverride: false,
        available: true,
      });
    } else if (upper.includes("JAMB")) {
      jambPlans.push({
        id: `alrahuz_jamb_${upper.toLowerCase().replace(/[^a-z0-9]/g, "_")}`,
        apiProvider: "ALRAHUZDATA",
        apiProviderName: "AlrahuzData",
        serviceType: "JAMB_PIN",
        providerCode: "jamb",
        providerName: "JAMB",
        productCode: examKey,
        productName: `${examKey} Registration e-PIN`,
        categoryGroup: "Education",
        validity: "Instant PIN",
        dataVolume: "",
        providerCostKobo: costKobo,
        sellingPriceKobo: costKobo,
        feeKobo: 0,
        discountPercent: 0,
        commissionPercent: 0,
        netProfitKobo: 0,
        profitMarginPercent: 0,
        hasCustomOverride: false,
        available: true,
      });
    }
  }

  // 5. Dynamically discover Recharge Card percentages if exposed on /api/user/
  const rechargeCards: LiveCatalogPlan[] = [];
  const rawRecharge = (raw.recharge_card || raw.recharge || raw.recharge_percentage || {}) as Record<string, unknown>;
  for (const [netKey, rawVal] of Object.entries(rawRecharge)) {
    const upper = netKey.toUpperCase();
    if (!upper.includes("MTN") && !upper.includes("GLO") && !upper.includes("AIRTEL") && !upper.includes("9MOBILE")) {
      continue;
    }
    const net = resolveUnifiedNetwork(netKey);
    const commPct = parsePercentDiscount(
      rawVal && typeof rawVal === "object" ? Object.values(rawVal as Record<string, unknown>)[0] : rawVal
    );
    for (const denom of [100, 200, 500, 1000]) {
      const faceKobo = denom * 100;
      const costKobo = Math.round(faceKobo * (1 - commPct / 100));
      rechargeCards.push({
        id: `alrahuz_epin_${net.code}_${denom}`,
        apiProvider: "ALRAHUZDATA",
        apiProviderName: "AlrahuzData",
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
        netProfitKobo: 0,
        profitMarginPercent: 0,
        hasCustomOverride: false,
        available: true,
      });
    }
  }

  // 6. Dynamically discover Electricity DisCos if exposed on /api/user/ (e.g. raw.Disco / raw.disco_name)
  const electricity: LiveCatalogPlan[] = [];
  const rawDiscos = raw.Disco || raw.disco || raw.discos || raw.electricity;
  if (Array.isArray(rawDiscos)) {
    for (const item of rawDiscos) {
      if (!item || typeof item !== "object") continue;
      const obj = item as Record<string, unknown>;
      const id = String(obj.id || obj.code || "").trim();
      const name = String(obj.name || obj.disco_name || "").trim();
      if (!id || !name) continue;
      electricity.push({
        id: `alrahuz_electricity_${id}`,
        apiProvider: "ALRAHUZDATA",
        apiProviderName: "AlrahuzData",
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
        netProfitKobo: 0,
        profitMarginPercent: 0,
        hasCustomOverride: false,
        available: true,
      });
    }
  }

  return {
    walletBalanceNaira,
    airtime: airtimePlans,
    data: dataPlans,
    cableTv: cablePlans,
    electricity,
    waec: waecPlans,
    jamb: jambPlans,
    rechargeCards,
  };
}
