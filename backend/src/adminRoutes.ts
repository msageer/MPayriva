import { Express, NextFunction, Request, Response } from "express";
import crypto from "crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { db, logProviderEvent } from "./config";
import { ClubKonnectClient } from "./clubKonnectClient";
import { FlutterwaveClient } from "./flutterwaveClient";
import {
  getLiveStorefrontCatalog,
  getPlatformPricingRules,
  invalidateLiveCatalogCache,
  savePlatformPricingRules,
} from "./liveCatalogEngine";
import { ServicePricingRule, SupportedServiceType } from "./pricingEngine";

export interface AdminAuthedRequest extends Request {
  uid?: string;
  email?: string;
  emailVerified?: boolean;
  isAdmin?: boolean;
}

export interface AdminRoutesDeps {
  requireAuth: (req: AdminAuthedRequest, res: Response, next: NextFunction) => Promise<void>;
  requireAdmin: (req: AdminAuthedRequest, res: Response, next: NextFunction) => void;
  clubKonnect: ClubKonnectClient;
  flutterwave: FlutterwaveClient;
  reconcileTransactionWithClubKonnect: (reference: string) => Promise<{
    notFound: boolean;
    status?: string;
    message?: string;
    tokenOrPin?: string | null;
  }>;
}

const ORDERED_SERVICE_TYPES: SupportedServiceType[] = [
  "AIRTIME",
  "DATA",
  "ELECTRICITY",
  "CABLE_TV",
  "RECHARGE_CARD",
  "DATA_CARD",
  "WAEC_PIN",
  "JAMB_PIN",
  "SMILE",
  "SPECTRANET",
  "BETTING",
];

const SERVICE_LABELS: Record<string, string> = {
  AIRTIME: "Airtime Top-Up",
  DATA: "Mobile Data",
  ELECTRICITY: "Electricity",
  CABLE_TV: "Cable TV",
  RECHARGE_CARD: "Recharge e-PINs",
  DATA_CARD: "Data Card e-PINs",
  WAEC_PIN: "WAEC e-PIN",
  JAMB_PIN: "JAMB e-PIN",
  SMILE: "Smile Broadband",
  SPECTRANET: "Spectranet",
  BETTING: "Betting Funding",
  WALLET_FUNDING: "Wallet Funding",
};

function sanitizeId(raw: string, fallback: string): string {
  const cleaned = raw.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 110);
  return cleaned.length > 0 ? cleaned : fallback;
}

function toEpochMs(ts: unknown): number {
  if (!ts) return 0;
  if (ts instanceof Timestamp) return ts.toMillis();
  if (typeof ts === "object" && ts !== null && "toMillis" in ts && typeof (ts as { toMillis: () => number }).toMillis === "function") {
    return (ts as { toMillis: () => number }).toMillis();
  }
  if (typeof ts === "number") return ts;
  if (typeof ts === "string") {
    const parsed = Date.parse(ts);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function serializeTransferDoc(id: string, data: Record<string, unknown>) {
  return {
    id,
    reference: String(data.reference || id),
    direction: String(data.direction || "FLUTTERWAVE_TO_CLUBKONNECT"),
    flutterwaveTransferId: Number(data.flutterwaveTransferId || 0),
    status: String(data.status || "PENDING"),
    amountKobo: Number(data.amountKobo || 0),
    feeKobo: Number(data.feeKobo || 0),
    bankCode: String(data.bankCode || ""),
    bankName: String(data.bankName || ""),
    accountNumber: String(data.accountNumber || ""),
    accountName: String(data.accountName || ""),
    narration: String(data.narration || ""),
    completeMessage: String(data.completeMessage || ""),
    createdAtIso: toEpochMs(data.createdAt) ? new Date(toEpochMs(data.createdAt)).toISOString() : "",
  };
}

export function registerAdminTreasuryAndPricingRoutes(app: Express, deps: AdminRoutesDeps) {
  const { requireAuth, requireAdmin, clubKonnect, flutterwave, reconcileTransactionWithClubKonnect } = deps;

  // =========================================================================
  // 1. LIVE NIGERIAN BANKS & ACCOUNT RESOLUTION (Flutterwave v3)
  // =========================================================================
  app.get("/api/v1/admin/banks", requireAuth, requireAdmin, async (_req: AdminAuthedRequest, res: Response) => {
    try {
      const banks = await flutterwave.listNigerianBanks();
      res.json({
        status: "success",
        count: banks.length,
        banks,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/transfers/resolve-account", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const accountBank = String(req.body?.bankCode || req.body?.accountBank || "").trim();
      const accountNumber = String(req.body?.accountNumber || "").replace(/\D/g, "");

      if (!accountBank) {
        res.status(400).json({ status: "error", message: "Please select a Nigerian bank first." });
        return;
      }
      if (accountNumber.length !== 10) {
        res.status(400).json({ status: "error", message: "Enter a valid 10-digit NUBAN bank account number." });
        return;
      }

      const resolved = await flutterwave.resolveBankAccount({ accountBank, accountNumber });
      res.json({
        status: "success",
        accountNumber: resolved.accountNumber,
        accountName: resolved.accountName,
        message: `Verified account: ${resolved.accountName}`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  // =========================================================================
  // 2. TREASURY CONFIG (Saved ClubKonnect Funding Bank Account) + HISTORY
  // =========================================================================
  app.get("/api/v1/admin/treasury/config", requireAuth, requireAdmin, async (_req: AdminAuthedRequest, res: Response) => {
    try {
      const [cfgSnap, transfersSnap] = await Promise.all([
        db.collection("platformConfig").doc("treasuryConfig").get(),
        db.collection("treasuryTransfers").orderBy("createdAt", "desc").limit(25).get(),
      ]);

      const cfgData = cfgSnap.exists ? (cfgSnap.data() as Record<string, unknown>) : null;
      const fundingAcct = cfgData?.clubKonnectFundingAccount as Record<string, unknown> | undefined;

      res.json({
        status: "success",
        clubKonnectFundingAccount: fundingAcct
          ? {
              bankCode: String(fundingAcct.bankCode || ""),
              bankName: String(fundingAcct.bankName || ""),
              accountNumber: String(fundingAcct.accountNumber || ""),
              accountName: String(fundingAcct.accountName || ""),
            }
          : null,
        recentTransfers: transfersSnap.docs.map((d) =>
          serializeTransferDoc(d.id, d.data() as Record<string, unknown>)
        ),
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/treasury/config", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const bankCode = String(req.body?.bankCode || "").trim();
      const bankName = String(req.body?.bankName || "").trim();
      const accountNumber = String(req.body?.accountNumber || "").replace(/\D/g, "");
      const accountName = String(req.body?.accountName || "").trim();

      if (!bankCode || !bankName || accountNumber.length !== 10 || accountName.length < 2) {
        res.status(400).json({
          status: "error",
          message: "Provide valid bank, 10-digit ClubKonnect funding account number, and verified account name.",
        });
        return;
      }

      const clubKonnectFundingAccount = {
        bankCode,
        bankName,
        accountNumber,
        accountName,
      };

      await db.collection("platformConfig").doc("treasuryConfig").set(
        {
          clubKonnectFundingAccount,
          updatedBy: req.uid,
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      res.json({
        status: "success",
        clubKonnectFundingAccount,
        message: `Saved ClubKonnect funding account (${bankName} • ${accountNumber}).`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  // =========================================================================
  // 3. REAL LIVE BANK TRANSFER: FLUTTERWAVE MAIN NGN → CLUBKONNECT FUNDING ACCOUNT
  // =========================================================================
  app.post(
    "/api/v1/admin/transfers/flutterwave-to-clubkonnect",
    requireAuth,
    requireAdmin,
    async (req: AdminAuthedRequest, res: Response) => {
      try {
        const uid = req.uid!;
        const amountKobo = Math.round(Number(req.body?.amountKobo || 0));
        const amountNaira = Math.round(amountKobo / 100);
        const bankCode = String(req.body?.bankCode || "").trim();
        const bankName = String(req.body?.bankName || "").trim();
        const accountNumber = String(req.body?.accountNumber || "").replace(/\D/g, "");
        const accountName = String(req.body?.accountName || "").trim();
        const narration =
          String(req.body?.narration || "").trim().slice(0, 90) ||
          "M-Sageer Pay Treasury Top-Up to ClubKonnect";
        const rawIdem = String(req.body?.idempotencyKey || "").trim();

        if (!Number.isInteger(amountKobo) || amountKobo < 10000 || amountKobo > 1000000000) {
          res.status(400).json({
            status: "error",
            message: "Transfer amount must be between ₦100.00 and ₦10,000,000.00.",
          });
          return;
        }
        if (!bankCode || accountNumber.length !== 10) {
          res.status(400).json({
            status: "error",
            message: "Select a valid bank and 10-digit ClubKonnect funding account number.",
          });
          return;
        }

        const lockKey = sanitizeId(
          rawIdem || `flw_ck_${Date.now()}_${crypto.randomBytes(4).toString("hex")}`,
          `flw_ck_${Date.now()}`
        );
        const lockRef = db.collection("idempotencyLocks").doc(lockKey);
        const existingLock = await lockRef.get();
        if (existingLock.exists) {
          const prevRef = String(existingLock.data()?.reference || "");
          const prevDoc = prevRef ? await db.collection("treasuryTransfers").doc(prevRef).get() : null;
          res.json({
            status: "success",
            transfer: prevDoc?.exists
              ? serializeTransferDoc(prevDoc.id, prevDoc.data() as Record<string, unknown>)
              : null,
            message: "This live transfer request was already submitted.",
          });
          return;
        }

        // Step 1: Verify live Flutterwave Available NGN Balance covers the transfer
        const flwBal = await flutterwave.getNgnBalance();
        if (flwBal.availableNaira < amountNaira) {
          res.status(400).json({
            status: "error",
            message: `Insufficient Flutterwave Main NGN available balance (₦${flwBal.availableNaira.toFixed(2)}) for ₦${amountNaira.toLocaleString()} live transfer.`,
          });
          return;
        }

        // Step 2: Acquire idempotency lock before invoking outbound money movement
        const reference = `MSGR-FLW2CK-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
        await lockRef.create({
          type: "FLUTTERWAVE_TO_CLUBKONNECT",
          reference,
          initiatedBy: uid,
          amountKobo,
          createdAt: FieldValue.serverTimestamp(),
        });

        // Step 3: Execute REAL LIVE Flutterwave v3 Bank Payout (POST /v3/transfers)
        let flwTransfer;
        try {
          flwTransfer = await flutterwave.initiateBankTransfer({
            accountBank: bankCode,
            accountNumber,
            amountNaira,
            narration,
            reference,
            beneficiaryName: accountName || undefined,
          });
        } catch (payoutErr) {
          // Release lock if Flutterwave rejected the call before queueing a payout
          await lockRef.delete().catch(() => {});
          throw payoutErr;
        }

        const transferDocData = {
          reference: flwTransfer.reference || reference,
          direction: "FLUTTERWAVE_TO_CLUBKONNECT",
          flutterwaveTransferId: flwTransfer.id,
          status: flwTransfer.status,
          amountKobo: Math.round(flwTransfer.amountNaira * 100),
          feeKobo: Math.round(flwTransfer.feeNaira * 100),
          bankCode,
          bankName: flwTransfer.bankName || bankName,
          accountNumber,
          accountName: flwTransfer.fullName || accountName,
          narration,
          completeMessage: flwTransfer.completeMessage,
          initiatedBy: uid,
          createdAt: FieldValue.serverTimestamp(),
          updatedAt: FieldValue.serverTimestamp(),
        };

        await db.collection("treasuryTransfers").doc(reference).set(transferDocData);

        await logProviderEvent({
          provider: "FLUTTERWAVE",
          action: "LIVE_TRANSFER_FLW_TO_CLUBKONNECT",
          reference,
          statusCode: "200",
          outcome:
            flwTransfer.status === "SUCCESSFUL"
              ? "SUCCESS"
              : flwTransfer.status === "FAILED"
                ? "ERROR"
                : "PENDING",
          message: `Initiated live ₦${amountNaira.toLocaleString()} transfer to ${transferDocData.bankName} ${accountNumber} (${transferDocData.accountName}). FLW ID: ${flwTransfer.id}`,
        });

        res.json({
          status: "success",
          transfer: serializeTransferDoc(reference, {
            ...transferDocData,
            createdAt: new Date().toISOString(),
          }),
          message: `Live transfer of ₦${amountNaira.toLocaleString()} sent via Flutterwave (${flwTransfer.status}). ClubKonnect credits automatically once bank settlement completes.`,
        });
      } catch (err) {
        res.status(400).json({ status: "error", message: (err as Error).message });
      }
    }
  );

  // =========================================================================
  // 4. CHECK LIVE TRANSFER STATUS (Flutterwave GET /v3/transfers/:id)
  // =========================================================================
  app.get(
    "/api/v1/admin/transfers/:transferId/status",
    requireAuth,
    requireAdmin,
    async (req: AdminAuthedRequest, res: Response) => {
      try {
        const transferId = String(req.params.transferId || "").trim();
        if (!/^\d+$/.test(transferId)) {
          res.status(400).json({ status: "error", message: "Invalid Flutterwave transfer ID." });
          return;
        }

        const live = await flutterwave.getTransferById(transferId);

        // Update local Firestore record if present
        const snap = await db
          .collection("treasuryTransfers")
          .where("flutterwaveTransferId", "==", Number(transferId))
          .limit(1)
          .get();

        let serialized;
        if (!snap.empty) {
          const docRef = snap.docs[0].ref;
          const prev = snap.docs[0].data() as Record<string, unknown>;
          await docRef.update({
            status: live.status,
            completeMessage: live.completeMessage,
            feeKobo: Math.round(live.feeNaira * 100),
            updatedAt: FieldValue.serverTimestamp(),
          });
          serialized = serializeTransferDoc(docRef.id, {
            ...prev,
            status: live.status,
            completeMessage: live.completeMessage,
            feeKobo: Math.round(live.feeNaira * 100),
          });
        } else {
          serialized = {
            id: live.reference || transferId,
            reference: live.reference || transferId,
            direction: "FLUTTERWAVE_TO_CLUBKONNECT",
            flutterwaveTransferId: live.id,
            status: live.status,
            amountKobo: Math.round(live.amountNaira * 100),
            feeKobo: Math.round(live.feeNaira * 100),
            bankCode: "",
            bankName: live.bankName,
            accountNumber: "",
            accountName: live.fullName,
            narration: "",
            completeMessage: live.completeMessage,
            createdAtIso: new Date().toISOString(),
          };
        }

        res.json({
          status: "success",
          transfer: serialized,
          message: `Transfer #${live.id} status: ${live.status}${live.completeMessage ? ` — ${live.completeMessage}` : ""}`,
        });
      } catch (err) {
        res.status(400).json({ status: "error", message: (err as Error).message });
      }
    }
  );

  // =========================================================================
  // 5. LIVE PRICING MATRIX, SERVICE MARKUP/DISCOUNT RULES & PER-PLAN OVERRIDES
  // =========================================================================
  app.get("/api/v1/admin/pricing/matrix", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const forceRefresh = req.query.refresh === "true";
      const [rulesMap, catalog] = await Promise.all([
        getPlatformPricingRules(),
        getLiveStorefrontCatalog(clubKonnect, forceRefresh),
      ]);

      const rules = ORDERED_SERVICE_TYPES.map((svc) => rulesMap[svc]);

      res.json({
        status: "success",
        fetchedAt: catalog.fetchedAt,
        clubKonnectConnected: catalog.clubKonnectConnected,
        totalPlans: catalog.totalPlans,
        rules,
        catalog,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/pricing/rules", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const incoming: Record<string, Partial<ServicePricingRule>> = {};
      if (Array.isArray(req.body?.rules)) {
        for (const item of req.body.rules) {
          const svc = String(item?.serviceType || "").toUpperCase();
          if (svc) incoming[svc] = item;
        }
      } else if (req.body?.rule && typeof req.body.rule === "object") {
        const svc = String(req.body.rule.serviceType || "").toUpperCase();
        if (svc) incoming[svc] = req.body.rule;
      } else if (req.body?.serviceType) {
        const svc = String(req.body.serviceType).toUpperCase();
        incoming[svc] = req.body;
      }

      if (Object.keys(incoming).length === 0) {
        res.status(400).json({ status: "error", message: "No pricing rule provided." });
        return;
      }

      const savedMap = await savePlatformPricingRules(incoming, req.uid!);
      const catalog = await getLiveStorefrontCatalog(clubKonnect, true);
      const rules = ORDERED_SERVICE_TYPES.map((svc) => savedMap[svc]);

      res.json({
        status: "success",
        fetchedAt: catalog.fetchedAt,
        clubKonnectConnected: catalog.clubKonnectConnected,
        totalPlans: catalog.totalPlans,
        rules,
        catalog,
        message: `Updated pricing rule(s) for ${Object.keys(incoming).join(", ")} and recalculated ${catalog.totalPlans} live plans.`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/pricing/override", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const serviceType = String(req.body?.serviceType || "").toUpperCase().trim();
      const providerCode = String(req.body?.providerCode || "").trim();
      const providerName = String(req.body?.providerName || providerCode).trim();
      const productCode = String(req.body?.productCode || "").trim();
      const productName = String(req.body?.productName || productCode).trim();
      const clearOverride = Boolean(req.body?.clearOverride);

      if (!serviceType || !providerCode || !productCode) {
        res.status(400).json({
          status: "error",
          message: "serviceType, providerCode, and productCode are required.",
        });
        return;
      }

      const docId = sanitizeId(
        `${serviceType}_${providerCode}_${productCode}`.toLowerCase(),
        `override_${Date.now()}`
      );
      const docRef = db.collection("serviceCatalog").doc(docId);

      // Also check if an existing /serviceCatalog doc with different ID matches this exact triple
      const matchingSnap = await db
        .collection("serviceCatalog")
        .where("serviceType", "==", serviceType)
        .where("providerCode", "==", providerCode)
        .where("productCode", "==", productCode)
        .limit(5)
        .get();

      if (clearOverride) {
        const batch = db.batch();
        batch.delete(docRef);
        matchingSnap.docs.forEach((d) => batch.delete(d.ref));
        await batch.commit();
        invalidateLiveCatalogCache();
        res.json({
          status: "success",
          message: `Removed custom override for ${productName}. Now governed by ${serviceType} global pricing rule.`,
        });
        return;
      }

      const targetRef = !matchingSnap.empty ? matchingSnap.docs[0].ref : docRef;
      const existingSnap = await targetRef.get();
      const providerCostKobo = Math.max(0, Math.round(Number(req.body?.providerCostKobo ?? 0)));
      const sellingPriceKobo = Math.max(0, Math.round(Number(req.body?.sellingPriceKobo ?? 0)));
      const feeKobo = Math.max(0, Math.round(Number(req.body?.feeKobo ?? 0)));
      const discountPercent = Math.min(50, Math.max(0, Number(req.body?.discountPercent ?? 0)));
      const commissionPercent = Math.min(50, Math.max(0, Number(req.body?.commissionPercent ?? 0)));
      const enabled = req.body?.enabled !== false;

      const payload: Record<string, unknown> = {
        serviceType,
        providerCode,
        providerName,
        productCode,
        productName,
        validity: String(req.body?.validity || ""),
        dataVolume: String(req.body?.dataVolume || ""),
        providerCostKobo,
        sellingPriceKobo,
        feeKobo,
        discountPercent,
        commissionPercent,
        enabled,
        updatedBy: req.uid,
        updatedAt: FieldValue.serverTimestamp(),
      };
      if (!existingSnap.exists) {
        payload.createdAt = FieldValue.serverTimestamp();
      }

      await targetRef.set(payload, { merge: true });
      invalidateLiveCatalogCache();

      res.json({
        status: "success",
        message: `Saved live pricing override for ${productName}.`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  // =========================================================================
  // 6. ONE-CLICK BATCH RECONCILE ALL PENDING TRANSACTIONS
  // =========================================================================
  app.post(
    "/api/v1/admin/transactions/reconcile-all-pending",
    requireAuth,
    requireAdmin,
    async (_req: AdminAuthedRequest, res: Response) => {
      try {
        const snap = await db
          .collection("transactions")
          .where("status", "in", ["PENDING", "PROCESSING"])
          .limit(25)
          .get();

        let processedCount = 0;
        let settledCount = 0;
        let reversedCount = 0;
        let stillPendingCount = 0;
        let errorCount = 0;

        for (const doc of snap.docs) {
          const data = doc.data();
          if (String(data.type || "") === "WALLET_FUNDING") continue;
          const ref = String(data.reference || doc.id);
          processedCount += 1;
          try {
            const outcome = await reconcileTransactionWithClubKonnect(ref);
            if (outcome.status === "SUCCESSFUL") settledCount += 1;
            else if (outcome.status === "REVERSED" || outcome.status === "FAILED") reversedCount += 1;
            else stillPendingCount += 1;
          } catch {
            errorCount += 1;
          }
        }

        res.json({
          status: "success",
          processedCount,
          settledCount,
          reversedCount,
          stillPendingCount,
          errorCount,
          message:
            processedCount === 0
              ? "No pending utility transactions require reconciliation."
              : `Checked ${processedCount} pending order(s): ${settledCount} settled, ${reversedCount} refunded/failed, ${stillPendingCount} still pending${errorCount > 0 ? `, ${errorCount} errors` : ""}.`,
        });
      } catch (err) {
        res.status(400).json({ status: "error", message: (err as Error).message });
      }
    }
  );

  // =========================================================================
  // 7. EXECUTIVE OVERVIEW METRICS (Profit, Volume, Solvency, Service Breakdown)
  // =========================================================================
  app.get("/api/v1/admin/overview-metrics", requireAuth, requireAdmin, async (_req: AdminAuthedRequest, res: Response) => {
    try {
      const [usersSnap, txSnap, ticketsSnap, ckBalRes, flwBalRes] = await Promise.allSettled([
        db.collection("users").get(),
        db.collection("transactions").get(),
        db.collection("supportTickets").where("status", "in", ["OPEN", "IN_PROGRESS"]).get(),
        clubKonnect.isConfigured ? clubKonnect.getWalletBalance() : Promise.resolve(null),
        flutterwave.isConfigured ? flutterwave.getNgnBalance() : Promise.resolve(null),
      ]);

      const usersDocs = usersSnap.status === "fulfilled" ? usersSnap.value.docs : [];
      const txDocs = txSnap.status === "fulfilled" ? txSnap.value.docs : [];
      const openTicketsCount = ticketsSnap.status === "fulfilled" ? ticketsSnap.value.size : 0;

      let totalUsers = 0;
      let individualUsers = 0;
      let businessUsers = 0;
      let activeUsers = 0;
      let restrictedUsers = 0;
      let virtualAccountUsers = 0;
      let totalWalletLiabilitiesKobo = 0;

      for (const doc of usersDocs) {
        const u = doc.data();
        totalUsers += 1;
        if (String(u.accountType || "INDIVIDUAL").toUpperCase() === "BUSINESS") businessUsers += 1;
        else individualUsers += 1;
        if (String(u.accountStatus || "ACTIVE").toUpperCase() === "ACTIVE") activeUsers += 1;
        else restrictedUsers += 1;
        if (u.virtualAccountNumber) virtualAccountUsers += 1;
        totalWalletLiabilitiesKobo += Math.max(0, Math.round(Number(u.walletBalanceKobo || 0)));
      }

      const nowMs = Date.now();
      const startOfToday = new Date();
      startOfToday.setUTCHours(0, 0, 0, 0);
      const todayMs = startOfToday.getTime();
      const last7dMs = nowMs - 7 * 24 * 60 * 60 * 1000;

      let allTimeVolumeKobo = 0;
      let allTimeProfitKobo = 0;
      let allTimeTxCount = 0;
      let todayVolumeKobo = 0;
      let todayProfitKobo = 0;
      let todayTxCount = 0;
      let last7dVolumeKobo = 0;
      let last7dProfitKobo = 0;
      let last7dTxCount = 0;

      let successfulCount = 0;
      let pendingCount = 0;
      let failedCount = 0;
      let reversedCount = 0;

      const byService = new Map<string, { serviceType: string; label: string; txCount: number; volumeKobo: number; profitKobo: number }>();

      for (const doc of txDocs) {
        const tx = doc.data();
        const type = String(tx.type || "OTHER").toUpperCase();
        const status = String(tx.status || "PENDING").toUpperCase();
        const totalKobo = Math.max(0, Math.round(Number(tx.totalAmountKobo || 0)));
        const marginKobo = Math.round(Number(tx.grossMarginKobo || 0));
        const createdMs = toEpochMs(tx.createdAt);

        allTimeTxCount += 1;
        if (status === "SUCCESSFUL") successfulCount += 1;
        else if (status === "PENDING" || status === "PROCESSING") pendingCount += 1;
        else if (status === "REVERSED") reversedCount += 1;
        else failedCount += 1;

        if (status === "SUCCESSFUL") {
          allTimeVolumeKobo += totalKobo;
          allTimeProfitKobo += marginKobo;
          if (createdMs >= todayMs) {
            todayVolumeKobo += totalKobo;
            todayProfitKobo += marginKobo;
            todayTxCount += 1;
          }
          if (createdMs >= last7dMs) {
            last7dVolumeKobo += totalKobo;
            last7dProfitKobo += marginKobo;
            last7dTxCount += 1;
          }

          const bucket = byService.get(type) ?? {
            serviceType: type,
            label: SERVICE_LABELS[type] || type,
            txCount: 0,
            volumeKobo: 0,
            profitKobo: 0,
          };
          bucket.txCount += 1;
          bucket.volumeKobo += totalKobo;
          bucket.profitKobo += marginKobo;
          byService.set(type, bucket);
        }
      }

      let clubKonnectBalanceKobo = 0;
      let clubKonnectReachable = false;
      if (ckBalRes.status === "fulfilled" && ckBalRes.value) {
        const raw = String(ckBalRes.value.balance ?? ckBalRes.value.Balance ?? ckBalRes.value.walletbalance ?? "0").replace(/[^0-9.]/g, "");
        const naira = Number(raw);
        if (Number.isFinite(naira)) {
          clubKonnectBalanceKobo = Math.round(naira * 100);
          clubKonnectReachable = true;
        }
      }

      let flutterwaveAvailableKobo = 0;
      let flutterwaveLedgerKobo = 0;
      let flutterwaveReachable = false;
      if (flwBalRes.status === "fulfilled" && flwBalRes.value) {
        flutterwaveAvailableKobo = Math.round(flwBalRes.value.availableNaira * 100);
        flutterwaveLedgerKobo = Math.round(flwBalRes.value.ledgerNaira * 100);
        flutterwaveReachable = true;
      }

      const totalProviderFloatKobo = clubKonnectBalanceKobo + flutterwaveAvailableKobo;
      const floatCoveragePercent =
        totalWalletLiabilitiesKobo > 0
          ? Number(((totalProviderFloatKobo / totalWalletLiabilitiesKobo) * 100).toFixed(1))
          : 100.0;
      const completedTerminalCount = successfulCount + failedCount + reversedCount;
      const successRatePercent =
        completedTerminalCount > 0
          ? Number(((successfulCount / completedTerminalCount) * 100).toFixed(1))
          : 100.0;

      const serviceBreakdown = Array.from(byService.values()).sort((a, b) => b.volumeKobo - a.volumeKobo);

      res.json({
        status: "success",
        generatedAt: new Date().toISOString(),
        totalUsers,
        individualUsers,
        businessUsers,
        activeUsers,
        restrictedUsers,
        virtualAccountUsers,
        totalWalletLiabilitiesKobo,
        clubKonnectBalanceKobo,
        clubKonnectReachable,
        flutterwaveAvailableKobo,
        flutterwaveLedgerKobo,
        flutterwaveReachable,
        totalProviderFloatKobo,
        floatCoveragePercent,
        lowClubKonnectFloatWarning: clubKonnectReachable && clubKonnectBalanceKobo < 1_000_000,
        solvencyAlert:
          (clubKonnectReachable || flutterwaveReachable) &&
          totalWalletLiabilitiesKobo > 0 &&
          totalProviderFloatKobo < totalWalletLiabilitiesKobo,
        allTimeVolumeKobo,
        allTimeProfitKobo,
        allTimeTxCount,
        todayVolumeKobo,
        todayProfitKobo,
        todayTxCount,
        last7dVolumeKobo,
        last7dProfitKobo,
        last7dTxCount,
        successfulCount,
        pendingCount,
        failedCount,
        reversedCount,
        successRatePercent,
        openTicketsCount,
        serviceBreakdown,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });
}
