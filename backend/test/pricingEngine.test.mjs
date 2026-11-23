import test from "node:test";
import assert from "node:assert/strict";
import {
  classifyClubKonnectStatus,
  computeAuthorizedPurchasePricing,
  computePlanPricingWithRule,
  sanitizeServicePricingRule,
} from "../src/pricingEngine.ts";

test("Airtime preserves exact faceValueNaira while charging discounted selling price", () => {
  const result = computeAuthorizedPurchasePricing({
    serviceType: "AIRTIME",
    providerCode: "01",
    productCode: "mtn_airtime",
    requestedAmountKobo: 100000, // ₦1,000.00 face value
    catalog: {
      productName: "MTN VTU Airtime",
      discountPercent: 1.0,
      commissionPercent: 2.5,
      feeKobo: 0,
    },
  });

  assert.equal(result.faceValueNaira, 1000);
  assert.equal(result.sellingPriceKobo, 99000);
  assert.equal(result.providerCostKobo, 97500);
  assert.equal(result.totalDebitKobo, 99000);
  assert.equal(result.grossMarginKobo, 1500);
});

test("Electricity preserves faceValueNaira and adds configured convenience fee", () => {
  const result = computeAuthorizedPurchasePricing({
    serviceType: "ELECTRICITY",
    providerCode: "02",
    productCode: "electricity_v1",
    requestedAmountKobo: 500000, // ₦5,000.00
    catalog: {
      productName: "IKEDC Prepaid",
      feeKobo: 10000, // ₦100 fee
      commissionPercent: 1.0,
    },
  });

  assert.equal(result.faceValueNaira, 5000);
  assert.equal(result.sellingPriceKobo, 500000);
  assert.equal(result.feeKobo, 10000);
  assert.equal(result.totalDebitKobo, 510000);
  assert.equal(result.providerCostKobo, 495000);
  assert.equal(result.grossMarginKobo, 15000);
});

test("Data & Cable ignore client amount and compute total strictly from admin catalog", () => {
  const result = computeAuthorizedPurchasePricing({
    serviceType: "DATA",
    providerCode: "01",
    productCode: "1000.0",
    requestedAmountKobo: 100, // Tampered low client amount MUST be ignored
    quantity: 1,
    catalog: {
      productName: "MTN 1GB SME Data",
      providerCostKobo: 26000,
      sellingPriceKobo: 29000,
      feeKobo: 0,
    },
  });

  assert.equal(result.sellingPriceKobo, 29000);
  assert.equal(result.totalDebitKobo, 29000);
  assert.equal(result.grossMarginKobo, 3000);
});

test("Recharge Card e-PIN multiplies catalog price by quantity and validates denominations", () => {
  const result = computeAuthorizedPurchasePricing({
    serviceType: "RECHARGE_CARD",
    providerCode: "01",
    productCode: "200",
    requestedAmountKobo: 100000,
    quantity: 5,
    catalog: {
      productName: "MTN ₦200 e-PIN",
      providerCostKobo: 19300,
      sellingPriceKobo: 19800,
      feeKobo: 0,
    },
  });

  assert.equal(result.faceValueNaira, 200);
  assert.equal(result.quantity, 5);
  assert.equal(result.totalDebitKobo, 99000);
  assert.equal(result.grossMarginKobo, 2500);

  assert.throws(() =>
    computeAuthorizedPurchasePricing({
      serviceType: "RECHARGE_CARD",
      providerCode: "01",
      productCode: "750",
      requestedAmountKobo: 75000,
      quantity: 1,
    })
  );
});

test("ClubKonnect status classifier keeps uncertain/pending responses PENDING and never auto-reverses", () => {
  const completed = classifyClubKonnectStatus({
    status: "ORDER_COMPLETED",
    orderid: "CK-1001",
    metertoken: "1234-5678-9012-3456",
  });
  assert.equal(completed.resolution, "SUCCESSFUL");
  assert.equal(completed.tokenOrPin, "1234-5678-9012-3456");

  const pending = classifyClubKonnectStatus({ status: "ORDER_RECEIVED", statuscode: "100" });
  assert.equal(pending.resolution, "PENDING");

  const unknown = classifyClubKonnectStatus({ raw: "<html>502 Bad Gateway</html>" });
  assert.equal(unknown.resolution, "PENDING");

  const cancelled = classifyClubKonnectStatus({ status: "ORDER_CANCELLED", remark: "Invalid number" });
  assert.equal(cancelled.resolution, "FAILED");
});

test("ClubKonnect TXN_EPIN array is parsed into printable vouchers and marked SUCCESSFUL", () => {
  const res = classifyClubKonnectStatus({
    ORDER_ID: "EPIN-9901",
    TXN_EPIN: [
      {
        transactionid: "TX1",
        transactiondate: "2026-11-21",
        mobilenetwork: "MTN",
        amount: "100",
        batchno: "B-100",
        sno: "SN-0001",
        pin: "1234567890123456",
      },
      {
        transactionid: "TX2",
        transactiondate: "2026-11-21",
        mobilenetwork: "MTN",
        amount: "100",
        batchno: "B-100",
        sno: "SN-0002",
        pin: "6543210987654321",
      },
    ],
  });

  assert.equal(res.resolution, "SUCCESSFUL");
  assert.equal(res.orderId, "EPIN-9901");
  assert.equal(res.vouchers.length, 2);
  assert.equal(res.vouchers[0].pin, "1234567890123456");
  assert.equal(res.vouchers[0].serialNumber, "SN-0001");
  assert.equal(res.vouchers[0].loadCode, "*311*PIN#");
  assert.equal(res.serialNumber, "SN-0001");
});

test("DATA_CARD and BETTING compute authorized pricing cleanly", () => {
  const dc = computeAuthorizedPurchasePricing({
    serviceType: "DATA_CARD",
    providerCode: "01",
    productCode: "1500",
    requestedAmountKobo: 100,
    quantity: 3,
    catalog: {
      productName: "MTN 1.5GB Data Card",
      providerCostKobo: 32000,
      sellingPriceKobo: 36000,
      feeKobo: 0,
    },
  });
  assert.equal(dc.quantity, 3);
  assert.equal(dc.totalDebitKobo, 108000);
  assert.equal(dc.grossMarginKobo, 12000);

  const bet = computeAuthorizedPurchasePricing({
    serviceType: "BETTING",
    providerCode: "Bet9ja",
    productCode: "Bet9ja",
    requestedAmountKobo: 200000,
    catalog: {
      productName: "Bet9ja Wallet",
      feeKobo: 5000,
      commissionPercent: 0.5,
    },
  });
  assert.equal(bet.faceValueNaira, 2000);
  assert.equal(bet.totalDebitKobo, 205000);
});

test("ClubKonnect requery mode keeps lookup errors PENDING instead of falsely reversing", () => {
  const badKey = classifyClubKonnectStatus({ status: "INVALID_REQUESTID" }, false, true);
  assert.equal(badKey.resolution, "PENDING");

  const cancelled = classifyClubKonnectStatus(
    { status: "ORDER_CANCELLED", remark: "Network unavailable" },
    false,
    true
  );
  assert.equal(cancelled.resolution, "FAILED");
  assert.match(cancelled.reason, /ORDER_CANCELLED/);
});

test("Service pricing rules accurately compute Data markup (% + flat) and exact net profit", () => {
  const rule = sanitizeServicePricingRule("DATA", {
    markupPercent: 5.0,
    markupFlatKobo: 1500, // +₦15
    customerDiscountPercent: 0,
    feeKobo: 0,
  });
  const res = computePlanPricingWithRule({
    serviceType: "DATA",
    productCode: "1000.0",
    providerCostKobo: 25000, // ₦250 ClubKonnect wholesale
    commissionPercent: 0,
    rule,
  });
  // 25000 * 1.05 = 26250 + 1500 = 27750 -> ceil to ₦1 = 27800 (₦278)
  assert.equal(res.sellingPriceKobo, 27800);
  assert.equal(res.netProfitKobo, 2800); // ₦28.00 net profit
  assert.equal(res.profitMarginPercent, 11.2);
});

test("Service pricing rules compute Airtime spread & Electricity convenience fee profit per ₦1,000", () => {
  const airtimeRule = sanitizeServicePricingRule("AIRTIME", {
    customerDiscountPercent: 1.0,
    feeKobo: 0,
  });
  const airtime = computePlanPricingWithRule({
    serviceType: "AIRTIME",
    productCode: "airtime",
    providerCostKobo: 0,
    commissionPercent: 3.0, // ClubKonnect gives 3% discount
    rule: airtimeRule,
  });
  // Customer pays ₦990, ClubKonnect costs ₦970 -> ₦20 net profit (2.0%)
  assert.equal(airtime.discountPercent, 1.0);
  assert.equal(airtime.netProfitKobo, 2000);
  assert.equal(airtime.profitMarginPercent, 2.0);

  const elecRule = sanitizeServicePricingRule("ELECTRICITY", {
    customerDiscountPercent: 0,
    feeKobo: 10000, // ₦100 fee
  });
  const elec = computePlanPricingWithRule({
    serviceType: "ELECTRICITY",
    productCode: "prepaid",
    providerCostKobo: 0,
    commissionPercent: 1.0, // ₦10 per ₦1,000
    rule: elecRule,
  });
  assert.equal(elec.feeKobo, 10000);
  assert.equal(elec.netProfitKobo, 11000); // ₦100 fee + ₦10 commission = ₦110 per ₦1,000
});

test("Super Admin directApiWholesale charges exact upstream API cost with zero fee and zero margin", () => {
  // Airtime: full 3% API commission passed directly to Super Admin (₦970 instead of ₦990)
  const airtime = computeAuthorizedPurchasePricing({
    serviceType: "AIRTIME",
    providerCode: "01",
    productCode: "airtime",
    requestedAmountKobo: 100000,
    catalog: {
      productName: "MTN Airtime",
      discountPercent: 1.0,
      commissionPercent: 3.0,
      feeKobo: 5000,
    },
    directApiWholesale: true,
  });
  assert.equal(airtime.faceValueNaira, 1000);
  assert.equal(airtime.providerCostKobo, 97000);
  assert.equal(airtime.sellingPriceKobo, 97000);
  assert.equal(airtime.feeKobo, 0);
  assert.equal(airtime.totalDebitKobo, 97000);
  assert.equal(airtime.grossMarginKobo, 0);

  // Data: exact wholesale cost (₦250 instead of retail ₦290)
  const data = computeAuthorizedPurchasePricing({
    serviceType: "DATA",
    providerCode: "01",
    productCode: "1000.0",
    requestedAmountKobo: 25000,
    catalog: {
      productName: "MTN 1GB SME",
      providerCostKobo: 25000,
      sellingPriceKobo: 29000,
      feeKobo: 2000,
    },
    directApiWholesale: true,
  });
  assert.equal(data.sellingPriceKobo, 25000);
  assert.equal(data.feeKobo, 0);
  assert.equal(data.totalDebitKobo, 25000);
  assert.equal(data.grossMarginKobo, 0);

  // Electricity: ₦0 convenience fee + full 1% DisCo commission discount
  const elec = computeAuthorizedPurchasePricing({
    serviceType: "ELECTRICITY",
    providerCode: "02",
    productCode: "prepaid",
    requestedAmountKobo: 500000,
    catalog: {
      productName: "IKEDC Prepaid",
      feeKobo: 10000,
      commissionPercent: 1.0,
    },
    directApiWholesale: true,
  });
  assert.equal(elec.faceValueNaira, 5000);
  assert.equal(elec.feeKobo, 0);
  assert.equal(elec.providerCostKobo, 495000);
  assert.equal(elec.sellingPriceKobo, 495000);
  assert.equal(elec.totalDebitKobo, 495000);
  assert.equal(elec.grossMarginKobo, 0);

  // Recharge Card e-PIN: exact wholesale cost × quantity
  const epin = computeAuthorizedPurchasePricing({
    serviceType: "RECHARGE_CARD",
    providerCode: "01",
    productCode: "200",
    requestedAmountKobo: 100000,
    quantity: 5,
    catalog: {
      productName: "MTN ₦200 e-PIN",
      providerCostKobo: 19300,
      sellingPriceKobo: 20000,
      feeKobo: 1000,
    },
    directApiWholesale: true,
  });
  assert.equal(epin.totalDebitKobo, 96500);
  assert.equal(epin.grossMarginKobo, 0);
});

test("AlrahuzData status classifier protects against 5xx timeouts and parses success/decline cleanly", async () => {
  const { classifyAlrahuzStatus } = await import("../src/pricingEngine.ts");

  const ok = classifyAlrahuzStatus({ Status: "successful", id: 8821, token: "4411-2233-5566-7788" });
  assert.equal(ok.resolution, "SUCCESSFUL");
  assert.equal(ok.orderId, "8821");
  assert.equal(ok.tokenOrPin, "4411-2233-5566-7788");

  const timeout502 = classifyAlrahuzStatus({ __httpStatus: 502, error: "Bad Gateway" });
  assert.equal(timeout502.resolution, "PENDING");

  const declined400 = classifyAlrahuzStatus({ __httpStatus: 400, error: "Insufficient balance" });
  assert.equal(declined400.resolution, "FAILED");

  const requeryUnknown = classifyAlrahuzStatus({ __httpStatus: 404, detail: "Not found" }, false, true);
  assert.equal(requeryUnknown.resolution, "PENDING");
});

test("Backend pricing override validator blocks negative, NaN, and accidental below-cost selling prices", async () => {
  const { validateAdminPricingOverride } = await import("../src/pricingEngine.ts");

  const valid = validateAdminPricingOverride({
    serviceType: "DATA",
    providerCostKobo: 25000,
    sellingPriceKobo: 28000,
    feeKobo: 0,
    discountPercent: 0,
    commissionPercent: 0,
  });
  assert.equal(valid.sellingPriceKobo, 28000);

  assert.throws(
    () =>
      validateAdminPricingOverride({
        serviceType: "DATA",
        providerCostKobo: 25000,
        sellingPriceKobo: 20000, // Below provider cost -> must reject!
        feeKobo: 0,
        discountPercent: 0,
        commissionPercent: 0,
      }),
    /below provider cost/i
  );

  assert.throws(
    () =>
      validateAdminPricingOverride({
        serviceType: "DATA",
        providerCostKobo: 25000,
        sellingPriceKobo: -500,
        feeKobo: 0,
        discountPercent: 0,
        commissionPercent: 0,
      }),
    /non-negative/i
  );
});

test("AlrahuzData dynamic catalog parser extracts Data, Cable, Airtime, and Exam PINs without hardcoding", async () => {
  const { parseAlrahuzUserCatalog } = await import("../src/alrahuzCatalogParser.ts");
  const samplePayload = {
    user: { wallet_balance: "14500.50" },
    Dataplans: {
      MTN_PLAN: {
        ALL: [
          {
            dataplan_id: 215,
            plan_network: "MTN",
            plan_type: "SME",
            plan: "1.0GB",
            plan_amount: "255",
            month_validate: "30 days",
          },
        ],
      },
    },
    Cableplan: {
      DSTVPLAN: [{ cableplan_id: 19, cable: "DSTV", package: "DStv Compact", plan_amount: "15700" }],
    },
    topup_percentage: {
      MTN: { VTU: 97 },
      AIRTEL: { VTU: 97.5 },
    },
    Exam: {
      WAEC: { amount: 3450 },
    },
  };

  const parsed = parseAlrahuzUserCatalog(samplePayload);
  assert.equal(parsed.walletBalanceNaira, 14500.5);
  assert.equal(parsed.data.length, 1);
  assert.equal(parsed.data[0].apiProvider, "ALRAHUZDATA");
  assert.equal(parsed.data[0].providerCode, "01");
  assert.equal(parsed.data[0].providerCostKobo, 25500);
  assert.equal(parsed.data[0].categoryGroup, "SME");
  assert.equal(parsed.cableTv.length, 1);
  assert.equal(parsed.cableTv[0].providerCode, "dstv");
  assert.equal(parsed.airtime.length, 2);
  assert.equal(parsed.airtime[0].commissionPercent, 3);
  assert.equal(parsed.waec.length, 1);
  assert.equal(parsed.waec[0].providerCostKobo, 345000);
});

test("Disabled API providers are strictly excluded from routing and transaction execution", async () => {
  const { sanitizeProviderControls, selectActiveProviderForService } = await import("../src/providerRouting.ts");

  const controls = sanitizeProviderControls({
    providers: {
      CLUBKONNECT: { enabled: false },
      ALRAHUZDATA: { enabled: true },
    },
    serviceRouting: {
      DATA: "CLUBKONNECT",
    },
  });

  // Even when routing asks for CLUBKONNECT, disabled provider must fail over to enabled ALRAHUZDATA
  const routed = selectActiveProviderForService({
    controls,
    serviceType: "DATA",
    configuredProviders: ["CLUBKONNECT", "ALRAHUZDATA"],
  });
  assert.equal(routed, "ALRAHUZDATA");

  // Explicitly requesting a disabled provider returns null (hard block)
  const blocked = selectActiveProviderForService({
    controls,
    serviceType: "DATA",
    configuredProviders: ["CLUBKONNECT", "ALRAHUZDATA"],
    requestedProvider: "CLUBKONNECT",
  });
  assert.equal(blocked, null);

  // AUTO_CHEAPEST picks cheapest enabled provider
  const bothOn = sanitizeProviderControls({
    providers: { CLUBKONNECT: { enabled: true }, ALRAHUZDATA: { enabled: true } },
    serviceRouting: { DATA: "AUTO_CHEAPEST" },
  });
  const cheapest = selectActiveProviderForService({
    controls: bothOn,
    serviceType: "DATA",
    configuredProviders: ["CLUBKONNECT", "ALRAHUZDATA"],
    costByProviderKobo: { CLUBKONNECT: 26500, ALRAHUZDATA: 24800 },
  });
  assert.equal(cheapest, "ALRAHUZDATA");
});

test("Cross-platform FCM v1 payload builder targets Android channels, Apple APNs, and Web Push cleanly", async () => {
  const {
    buildCrossPlatformMulticastPayload,
    isPrunableFcmErrorCode,
    normalizePlatform,
    resolveNotificationRouting,
  } = await import("../src/notificationPayload.ts");

  const payload = buildCrossPlatformMulticastPayload({
    tokens: ["tok_android_12345678901234567890", "tok_ios_12345678901234567890"],
    notificationId: "notif_abc_123",
    title: "Wallet Credited",
    body: "₦5,000.00 added to your wallet.",
    type: "WALLET_FUNDING",
    reference: "MSGR-FLW-999",
    unreadBadgeCount: 3,
    webLink: "https://msageer-pay-backend.onrender.com",
  });

  assert.equal(payload.tokens.length, 2);
  assert.equal(payload.android.priority, "high");
  assert.equal(payload.android.notification.channelId, "msageer_transactions");
  assert.equal(payload.android.notification.tag, "notif_abc_123");
  assert.equal(payload.apns.headers["apns-priority"], "10");
  assert.equal(payload.apns.payload.aps.badge, 3);
  assert.equal(payload.apns.payload.aps["interruption-level"], "time-sensitive");
  assert.equal(payload.webpush.headers.Urgency, "high");
  assert.equal(payload.data.route, "transaction_receipt");
  assert.equal(payload.data.reference, "MSGR-FLW-999");

  const supportRoute = resolveNotificationRouting("SUPPORT_REPLY");
  assert.equal(supportRoute.channelId, "msageer_support");
  assert.equal(supportRoute.route, "support");

  const secRoute = resolveNotificationRouting("ACCOUNT_STATUS");
  assert.equal(secRoute.channelId, "msageer_security");
  assert.equal(secRoute.interruptionLevel, "time-sensitive");

  assert.equal(normalizePlatform("iPhone"), "ios");
  assert.equal(normalizePlatform("PWA"), "web");
  assert.equal(normalizePlatform("android"), "android");

  assert.equal(isPrunableFcmErrorCode("messaging/registration-token-not-registered"), true);
  assert.equal(isPrunableFcmErrorCode("messaging/invalid-registration-token"), true);
  assert.equal(isPrunableFcmErrorCode("messaging/server-unavailable"), false);
});
