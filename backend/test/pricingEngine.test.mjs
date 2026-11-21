import test from "node:test";
import assert from "node:assert/strict";
import {
  classifyClubKonnectStatus,
  computeAuthorizedPurchasePricing,
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

