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




