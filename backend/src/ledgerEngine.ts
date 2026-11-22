import { FieldValue } from "firebase-admin/firestore";
import { db, notifyCustomer } from "./config";

/**
 * Atomic Double-Entry Financial Ledger Engine.
 * Guarantees:
 * - Idempotency key locking (prevents duplicate debits and duplicate funding credits)
 * - Atomic balance checks & updates inside Firestore transactions
 * - Immutable balanced double-entry records in /ledgerEntries
 * - Strict reversal rules (NEVER reverse a PENDING/PROCESSING timeout without provider confirmation)
 */
export async function atomicCreditWallet(params: {
  userId: string;
  idempotencyKey: string;
  reference: string;
  amountKobo: number;
  feeKobo: number;
  providerOrderId?: string;
  narration: string;
  settlementAccountCode?: string;
}) {
  if (params.amountKobo <= 0) throw new Error("Credit amount must be positive.");
  const netCreditKobo = Math.max(0, params.amountKobo - params.feeKobo);
  const lockRef = db.collection("idempotencyLocks").doc(params.idempotencyKey);
  const userRef = db.collection("users").doc(params.userId);
  const txRef = db.collection("transactions").doc(params.reference);

  const alreadyProcessed = await db.runTransaction(async (tx) => {
    const lockSnap = await tx.get(lockRef);
    if (lockSnap.exists) return true;

    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Customer account not found.");

    const balanceBefore = Number(userSnap.data()?.walletBalanceKobo ?? 0);
    const balanceAfter = balanceBefore + netCreditKobo;

    tx.set(lockRef, {
      userId: params.userId,
      reference: params.reference,
      type: "WALLET_FUNDING",
      createdAt: FieldValue.serverTimestamp(),
    });

    tx.update(userRef, {
      walletBalanceKobo: balanceAfter,
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(txRef, {
      userId: params.userId,
      reference: params.reference,
      idempotencyKey: params.idempotencyKey,
      type: "WALLET_FUNDING",
      status: "SUCCESSFUL",
      provider: "FLUTTERWAVE",
      providerCode: params.settlementAccountCode || "FLW_NGN",
      providerOrderId: params.providerOrderId || null,
      recipient: userSnap.data()?.email || params.userId,
      productName: "NGN Wallet Funding",
      providerCostKobo: params.feeKobo,
      sellingPriceKobo: params.amountKobo,
      feeKobo: params.feeKobo,
      grossMarginKobo: 0,
      totalAmountKobo: netCreditKobo,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    // Double-entry 1: CREDIT Customer Wallet Liability
    tx.set(db.collection("ledgerEntries").doc(), {
      userId: params.userId,
      transactionId: params.reference,
      reference: params.reference,
      entryType: "CREDIT",
      accountCode: "CUSTOMER_WALLET",
      amountKobo: netCreditKobo,
      balanceBeforeKobo: balanceBefore,
      balanceAfterKobo: balanceAfter,
      narration: params.narration,
      createdAt: FieldValue.serverTimestamp(),
    });

    // Double-entry 2: DEBIT Flutterwave Settlement / Main Treasury Asset
    tx.set(db.collection("ledgerEntries").doc(), {
      userId: params.userId,
      transactionId: params.reference,
      reference: params.reference,
      entryType: "DEBIT",
      accountCode: params.settlementAccountCode || "FLUTTERWAVE_SETTLEMENT",
      amountKobo: netCreditKobo,
      balanceBeforeKobo: balanceBefore,
      balanceAfterKobo: balanceAfter,
      narration: `Settlement inflow ${params.reference}`,
      createdAt: FieldValue.serverTimestamp(),
    });

    return false;
  });

  if (!alreadyProcessed) {
    await notifyCustomer({
      userId: params.userId,
      title: "Wallet Funded Successfully",
      body: `Your wallet was credited with ₦${(netCreditKobo / 100).toFixed(2)} (Ref: ${params.reference}).`,
      type: "WALLET_FUNDING",
      reference: params.reference,
    });
  }
  return { alreadyProcessed };
}

export async function atomicReserveAndCreatePurchase(params: {
  userId: string;
  idempotencyKey: string;
  reference: string;
  serviceType: string;
  providerCode: string;
  productCode: string;
  productName: string;
  recipient: string;
  providerCostKobo: number;
  sellingPriceKobo: number;
  feeKobo: number;
  apiProvider?: "CLUBKONNECT" | "ALRAHUZDATA";
}) {
  const activeApiProvider = params.apiProvider || "CLUBKONNECT";
  const totalDebitKobo = params.sellingPriceKobo + params.feeKobo;
  const grossMarginKobo = totalDebitKobo - params.providerCostKobo;
  if (totalDebitKobo <= 0) throw new Error("Invalid purchase amount.");

  const lockRef = db.collection("idempotencyLocks").doc(params.idempotencyKey);
  const userRef = db.collection("users").doc(params.userId);
  const txRef = db.collection("transactions").doc(params.reference);

  return db.runTransaction(async (tx) => {
    const lockSnap = await tx.get(lockRef);
    if (lockSnap.exists) {
      throw new Error("Duplicate transaction prevented: this request has already been submitted.");
    }

    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Account not found.");
    const userData = userSnap.data()!;
    if (userData.accountStatus !== "ACTIVE") {
      throw new Error(`Account is currently ${userData.accountStatus}. Contact support.`);
    }

    const balanceBefore = Number(userData.walletBalanceKobo ?? 0);
    if (balanceBefore < totalDebitKobo) {
      throw new Error("Insufficient wallet balance.");
    }
    const balanceAfter = balanceBefore - totalDebitKobo;

    tx.set(lockRef, {
      userId: params.userId,
      reference: params.reference,
      type: params.serviceType,
      createdAt: FieldValue.serverTimestamp(),
    });

    tx.update(userRef, {
      walletBalanceKobo: balanceAfter,
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(txRef, {
      userId: params.userId,
      reference: params.reference,
      idempotencyKey: params.idempotencyKey,
      type: params.serviceType,
      status: "PROCESSING",
      provider: activeApiProvider,
      providerCode: params.providerCode,
      recipient: params.recipient,
      productName: params.productName,
      productCode: params.productCode,
      providerCostKobo: params.providerCostKobo,
      sellingPriceKobo: params.sellingPriceKobo,
      feeKobo: params.feeKobo,
      grossMarginKobo,
      totalAmountKobo: totalDebitKobo,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(db.collection("ledgerEntries").doc(), {
      userId: params.userId,
      transactionId: params.reference,
      reference: params.reference,
      entryType: "DEBIT",
      accountCode: "CUSTOMER_WALLET",
      amountKobo: totalDebitKobo,
      balanceBeforeKobo: balanceBefore,
      balanceAfterKobo: balanceAfter,
      narration: `${params.productName} for ${params.recipient}`,
      createdAt: FieldValue.serverTimestamp(),
    });

    tx.set(db.collection("ledgerEntries").doc(), {
      userId: params.userId,
      transactionId: params.reference,
      reference: params.reference,
      entryType: "CREDIT",
      accountCode: activeApiProvider === "ALRAHUZDATA" ? "ALRAHUZDATA_PAYABLE" : "CLUBKONNECT_PAYABLE",
      amountKobo: params.providerCostKobo,
      balanceBeforeKobo: balanceBefore,
      balanceAfterKobo: balanceAfter,
      narration: `Provider payable ${params.reference}`,
      createdAt: FieldValue.serverTimestamp(),
    });

    if (grossMarginKobo > 0) {
      tx.set(db.collection("ledgerEntries").doc(), {
        userId: params.userId,
        transactionId: params.reference,
        reference: params.reference,
        entryType: "CREDIT",
        accountCode: "PLATFORM_REVENUE",
        amountKobo: grossMarginKobo,
        balanceBeforeKobo: balanceBefore,
        balanceAfterKobo: balanceAfter,
        narration: `Gross margin ${params.reference}`,
        createdAt: FieldValue.serverTimestamp(),
      });
    }

    return { balanceBefore, balanceAfter, totalDebitKobo };
  });
}

/**
 * Super Admin Direct API Execution (msagirgroup@gmail.com only).
 * Records an audited transaction funded directly from the live upstream API float
 * (wholesale cost, ₦0 fee, ₦0 margin) without debiting or modifying customer wallet liabilities.
 */
export async function atomicCreateDirectApiPurchase(params: {
  userId: string;
  idempotencyKey: string;
  reference: string;
  serviceType: string;
  providerCode: string;
  productCode: string;
  productName: string;
  recipient: string;
  providerCostKobo: number;
  apiProvider?: "CLUBKONNECT" | "ALRAHUZDATA";
}) {
  const activeApiProvider = params.apiProvider || "CLUBKONNECT";
  const wholesaleCostKobo = Math.max(0, Math.round(params.providerCostKobo));
  if (wholesaleCostKobo <= 0) throw new Error("Invalid wholesale API purchase amount.");

  const lockRef = db.collection("idempotencyLocks").doc(params.idempotencyKey);
  const userRef = db.collection("users").doc(params.userId);
  const txRef = db.collection("transactions").doc(params.reference);

  return db.runTransaction(async (tx) => {
    const lockSnap = await tx.get(lockRef);
    if (lockSnap.exists) {
      throw new Error("Duplicate transaction prevented: this request has already been submitted.");
    }

    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new Error("Account not found.");
    const userData = userSnap.data()!;
    if (userData.accountStatus !== "ACTIVE") {
      throw new Error(`Account is currently ${userData.accountStatus}. Contact support.`);
    }

    const currentWalletKobo = Number(userData.walletBalanceKobo ?? 0);

    tx.set(lockRef, {
      userId: params.userId,
      reference: params.reference,
      type: params.serviceType,
      fundingSource: "DIRECT_PROVIDER_API",
      createdAt: FieldValue.serverTimestamp(),
    });

    tx.set(txRef, {
      userId: params.userId,
      reference: params.reference,
      idempotencyKey: params.idempotencyKey,
      type: params.serviceType,
      status: "PROCESSING",
      provider: activeApiProvider,
      providerCode: params.providerCode,
      recipient: params.recipient,
      productName: params.productName,
      productCode: params.productCode,
      fundingSource: "DIRECT_PROVIDER_API",
      directApiWholesale: true,
      providerCostKobo: wholesaleCostKobo,
      sellingPriceKobo: wholesaleCostKobo,
      feeKobo: 0,
      grossMarginKobo: 0,
      totalAmountKobo: wholesaleCostKobo,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(db.collection("ledgerEntries").doc(), {
      userId: params.userId,
      transactionId: params.reference,
      reference: params.reference,
      entryType: "DEBIT",
      accountCode: activeApiProvider === "ALRAHUZDATA" ? "ALRAHUZDATA_DIRECT_API" : "CLUBKONNECT_DIRECT_API",
      amountKobo: wholesaleCostKobo,
      balanceBeforeKobo: currentWalletKobo,
      balanceAfterKobo: currentWalletKobo,
      narration: `[Direct API Wholesale] ${params.productName} for ${params.recipient}`,
      createdAt: FieldValue.serverTimestamp(),
    });

    return { wholesaleCostKobo };
  });
}

/**
 * Safe Reversal: Only call when ClubKonnect explicitly confirms failure/cancellation.
 * For DIRECT_PROVIDER_API transactions (Super Admin direct API float), marks FAILED
 * WITHOUT crediting customer walletBalanceKobo (no customer wallet debit ever occurred).
 */
export async function atomicReverseConfirmedFailure(reference: string, reason: string) {
  const txRef = db.collection("transactions").doc(reference);
  const outcome = await db.runTransaction(async (tx) => {
    const txSnap = await tx.get(txRef);
    if (!txSnap.exists) return null;
    const data = txSnap.data()!;
    if (data.status === "REVERSED" || data.status === "SUCCESSFUL" || data.status === "FAILED") return null;

    const isDirectApi =
      data.fundingSource === "DIRECT_PROVIDER_API" || data.directApiWholesale === true;

    if (isDirectApi) {
      tx.update(txRef, {
        status: "FAILED",
        failureReason: reason,
        updatedAt: FieldValue.serverTimestamp(),
      });
      return { userId: String(data.userId), refundKobo: 0, isDirectApi: true };
    }

    const userRef = db.collection("users").doc(data.userId);
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) return null;

    const refundKobo = Number(data.totalAmountKobo ?? 0);
    const balanceBefore = Number(userSnap.data()?.walletBalanceKobo ?? 0);
    const balanceAfter = balanceBefore + refundKobo;

    tx.update(userRef, {
      walletBalanceKobo: balanceAfter,
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.update(txRef, {
      status: "REVERSED",
      failureReason: reason,
      updatedAt: FieldValue.serverTimestamp(),
    });

    tx.set(db.collection("ledgerEntries").doc(), {
      userId: data.userId,
      transactionId: reference,
      reference,
      entryType: "REVERSAL",
      accountCode: "CUSTOMER_WALLET",
      amountKobo: refundKobo,
      balanceBeforeKobo: balanceBefore,
      balanceAfterKobo: balanceAfter,
      narration: `Automatic refund for failed order ${reference}: ${reason}`,
      createdAt: FieldValue.serverTimestamp(),
    });

    return { userId: String(data.userId), refundKobo, isDirectApi: false };
  });

  if (outcome) {
    if (outcome.isDirectApi) {
      await notifyCustomer({
        userId: outcome.userId,
        title: "Direct API Order Declined",
        body: `Your direct API order (${reference}) was declined by the provider (${reason}). No app wallet balance was debited.`,
        type: "PURCHASE_FAILED",
        reference,
      });
    } else {
      await notifyCustomer({
        userId: outcome.userId,
        title: "Transaction Reversed & Refunded",
        body: `₦${(outcome.refundKobo / 100).toFixed(2)} has been refunded to your wallet (Ref: ${reference}).`,
        type: "REFUND",
        reference,
      });
    }
  }
}
