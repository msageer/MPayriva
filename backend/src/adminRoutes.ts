import { Express, NextFunction, Request, Response } from "express";
import crypto from "crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { db, logProviderEvent } from "./config";
import { ClubKonnectClient } from "./clubKonnectClient";
import { AlrahuzDataClient } from "./alrahuzDataClient";
import { FlutterwaveClient } from "./flutterwaveClient";
import {
  getMultiProviderCatalogBundle,
  getPlatformPricingRules,
  invalidateLiveCatalogCache,
  savePlatformPricingRules,
} from "./liveCatalogEngine";
import {
  ServicePricingRule,
  SupportedServiceType,
  validateAdminPricingOverride,
} from "./pricingEngine";
import {
  getProviderControls,
  isProviderEnabled,
  saveProviderControls,
} from "./providerControls";
import { NotificationEventType } from "./notificationPayload";
import {
  broadcastCrossPlatformAnnouncement,
  sendCrossPlatformNotification,
} from "./notificationService";

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
  alrahuzData: AlrahuzDataClient;
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
  const { requireAuth, requireAdmin, clubKonnect, alrahuzData, flutterwave, reconcileTransactionWithClubKonnect } = deps;

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
  // 4B. API PROVIDER CONTROLS (ON/OFF SWITCHES + SERVICE ROUTING)
  // =========================================================================
  app.get("/api/v1/admin/providers/controls", requireAuth, requireAdmin, async (_req: AdminAuthedRequest, res: Response) => {
    try {
      const [controls, ckBalRes, alrBalRes, bundle] = await Promise.allSettled([
        getProviderControls(),
        clubKonnect.isConfigured ? clubKonnect.getWalletBalance() : Promise.resolve(null),
        alrahuzData.isConfigured ? alrahuzData.getWalletBalance() : Promise.resolve(null),
        getMultiProviderCatalogBundle(clubKonnect, alrahuzData, false),
      ]);

      const resolvedControls = controls.status === "fulfilled" ? controls.value : await getProviderControls();
      let ckBalanceNaira: string | null = null;
      let ckReachable = false;
      if (ckBalRes.status === "fulfilled" && ckBalRes.value) {
        const raw = String(ckBalRes.value.balance ?? ckBalRes.value.Balance ?? "").replace(/[^0-9.]/g, "");
        if (raw && Number.isFinite(Number(raw))) {
          ckBalanceNaira = Number(raw).toFixed(2);
          ckReachable = true;
        }
      }

      let alrBalanceNaira: string | null = null;
      let alrReachable = false;
      if (alrBalRes.status === "fulfilled" && alrBalRes.value) {
        alrBalanceNaira = alrBalRes.value.balanceNaira.toFixed(2);
        alrReachable = true;
      }

      const summaryFromBundle = bundle.status === "fulfilled" ? bundle.value.providersSummary : [];
      const ckCount = summaryFromBundle.find((p) => p.id === "CLUBKONNECT")?.livePlanCount ?? 0;
      const alrCount = summaryFromBundle.find((p) => p.id === "ALRAHUZDATA")?.livePlanCount ?? 0;

      res.json({
        status: "success",
        providers: [
          {
            id: "CLUBKONNECT",
            name: "ClubKonnect",
            configured: clubKonnect.isConfigured,
            enabled: isProviderEnabled(resolvedControls, "CLUBKONNECT"),
            reachable: ckReachable,
            livePlanCount: ckCount,
            balanceNaira: ckBalanceNaira,
          },
          {
            id: "ALRAHUZDATA",
            name: "AlrahuzData",
            configured: alrahuzData.isConfigured,
            enabled: isProviderEnabled(resolvedControls, "ALRAHUZDATA"),
            reachable: alrReachable,
            livePlanCount: alrCount,
            balanceNaira: alrBalanceNaira,
          },
        ],
        serviceRouting: resolvedControls.serviceRouting,
        updatedBy: resolvedControls.updatedBy || null,
        updatedAtIso: resolvedControls.updatedAtIso || null,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/providers/controls", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const body = (req.body || {}) as Record<string, unknown>;
      const patchProviders: Partial<Record<"CLUBKONNECT" | "ALRAHUZDATA", { enabled?: boolean }>> = {};

      if (body.providers && typeof body.providers === "object") {
        const provObj = body.providers as Record<string, Record<string, unknown>>;
        if (typeof provObj.CLUBKONNECT?.enabled === "boolean") {
          patchProviders.CLUBKONNECT = { enabled: provObj.CLUBKONNECT.enabled as boolean };
        }
        if (typeof provObj.ALRAHUZDATA?.enabled === "boolean") {
          patchProviders.ALRAHUZDATA = { enabled: provObj.ALRAHUZDATA.enabled as boolean };
        }
      }
      if (typeof body.clubKonnectEnabled === "boolean") {
        patchProviders.CLUBKONNECT = { enabled: body.clubKonnectEnabled };
      }
      if (typeof body.alrahuzDataEnabled === "boolean") {
        patchProviders.ALRAHUZDATA = { enabled: body.alrahuzDataEnabled };
      }

      const patchRouting =
        body.serviceRouting && typeof body.serviceRouting === "object"
          ? (body.serviceRouting as Partial<Record<string, string>>)
          : undefined;

      const updated = await saveProviderControls(
        { providers: patchProviders, serviceRouting: patchRouting },
        req.uid!
      );
      invalidateLiveCatalogCache();

      await logProviderEvent({
        provider: "SYSTEM",
        action: "ADMIN_UPDATE_PROVIDER_CONTROLS",
        reference: `PROVCTL-${Date.now()}`,
        statusCode: "200",
        outcome: "SUCCESS",
        message: `Admin ${req.email || req.uid} updated provider switches: ClubKonnect=${updated.providers.CLUBKONNECT.enabled ? "ON" : "OFF"}, AlrahuzData=${updated.providers.ALRAHUZDATA.enabled ? "ON" : "OFF"}.`,
      });

      const bundle = await getMultiProviderCatalogBundle(clubKonnect, alrahuzData, true);

      res.json({
        status: "success",
        providers: bundle.providersSummary,
        serviceRouting: updated.serviceRouting,
        updatedBy: updated.updatedBy || null,
        updatedAtIso: updated.updatedAtIso || null,
        message: `Provider controls updated: ClubKonnect is ${updated.providers.CLUBKONNECT.enabled ? "ON" : "OFF"}, AlrahuzData is ${updated.providers.ALRAHUZDATA.enabled ? "ON" : "OFF"}.`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  // =========================================================================
  // 5. LIVE MULTI-PROVIDER PRICING MATRIX, COMPARISON, RULES & OVERRIDES
  // =========================================================================
  app.get("/api/v1/admin/pricing/matrix", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const forceRefresh = req.query.refresh === "true";
      const [rulesMap, bundle] = await Promise.all([
        getPlatformPricingRules(),
        getMultiProviderCatalogBundle(clubKonnect, alrahuzData, forceRefresh),
      ]);

      const rules = ORDERED_SERVICE_TYPES.map((svc) => rulesMap[svc]);

      res.json({
        status: "success",
        fetchedAt: bundle.allProvidersCatalog.fetchedAt,
        clubKonnectConnected: bundle.allProvidersCatalog.clubKonnectConnected,
        alrahuzDataConnected: bundle.allProvidersCatalog.alrahuzDataConnected === true,
        totalPlans: bundle.allProvidersCatalog.totalPlans,
        rules,
        catalog: bundle.allProvidersCatalog,
        providerComparison: bundle.providerComparison,
        providersSummary: bundle.providersSummary,
        serviceRouting: bundle.providerControls.serviceRouting,
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
      const bundle = await getMultiProviderCatalogBundle(clubKonnect, alrahuzData, true);
      const rules = ORDERED_SERVICE_TYPES.map((svc) => savedMap[svc]);

      await logProviderEvent({
        provider: "SYSTEM",
        action: "ADMIN_UPDATE_PRICING_RULES",
        reference: `RULES-${Date.now()}`,
        statusCode: "200",
        outcome: "SUCCESS",
        message: `Admin ${req.email || req.uid} updated category pricing rules for ${Object.keys(incoming).join(", ")}.`,
      });

      res.json({
        status: "success",
        fetchedAt: bundle.allProvidersCatalog.fetchedAt,
        clubKonnectConnected: bundle.allProvidersCatalog.clubKonnectConnected,
        alrahuzDataConnected: bundle.allProvidersCatalog.alrahuzDataConnected === true,
        totalPlans: bundle.allProvidersCatalog.totalPlans,
        rules,
        catalog: bundle.allProvidersCatalog,
        providerComparison: bundle.providerComparison,
        providersSummary: bundle.providersSummary,
        serviceRouting: bundle.providerControls.serviceRouting,
        message: `Updated pricing rule(s) for ${Object.keys(incoming).join(", ")} and recalculated ${bundle.allProvidersCatalog.totalPlans} live plans.`,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  app.post("/api/v1/admin/pricing/override", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const serviceType = String(req.body?.serviceType || "").toUpperCase().trim();
      const apiProviderRaw = String(req.body?.apiProvider || "").toUpperCase().trim();
      const apiProvider = apiProviderRaw === "ALRAHUZDATA" || apiProviderRaw === "CLUBKONNECT" ? apiProviderRaw : "";
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

      const idPrefix = apiProvider ? `${apiProvider}_${serviceType}` : serviceType;
      const docId = sanitizeId(
        `${idPrefix}_${providerCode}_${productCode}`.toLowerCase(),
        `override_${Date.now()}`
      );
      const docRef = db.collection("serviceCatalog").doc(docId);

      // Also check if an existing /serviceCatalog doc with different ID matches this tuple
      const matchingSnap = await db
        .collection("serviceCatalog")
        .where("serviceType", "==", serviceType)
        .where("providerCode", "==", providerCode)
        .where("productCode", "==", productCode)
        .limit(10)
        .get();

      const matchingDocs = matchingSnap.docs.filter((d) => {
        const dApi = String(d.data()?.apiProvider || "").toUpperCase().trim();
        return !apiProvider || !dApi || dApi === apiProvider;
      });

      if (clearOverride) {
        const batch = db.batch();
        batch.delete(docRef);
        matchingDocs.forEach((d) => batch.delete(d.ref));
        await batch.commit();
        invalidateLiveCatalogCache();
        res.json({
          status: "success",
          message: `Removed custom override for ${productName}. Now governed by ${serviceType} global pricing rule.`,
        });
        return;
      }

      // Strict backend validation against invalid, corrupt, or accidental loss-making prices
      const validated = validateAdminPricingOverride({
        serviceType,
        providerCostKobo: Number(req.body?.providerCostKobo ?? 0),
        sellingPriceKobo: Number(req.body?.sellingPriceKobo ?? 0),
        feeKobo: Number(req.body?.feeKobo ?? 0),
        discountPercent: Number(req.body?.discountPercent ?? 0),
        commissionPercent: Number(req.body?.commissionPercent ?? 0),
        allowBelowCost: Boolean(req.body?.allowBelowCost),
      });

      const targetRef = matchingDocs.length > 0 ? matchingDocs[0].ref : docRef;
      const existingSnap = await targetRef.get();
      const enabled = req.body?.enabled !== false;

      const payload: Record<string, unknown> = {
        serviceType,
        ...(apiProvider ? { apiProvider } : {}),
        providerCode,
        providerName,
        productCode,
        productName,
        validity: String(req.body?.validity || ""),
        dataVolume: String(req.body?.dataVolume || ""),
        providerCostKobo: validated.providerCostKobo,
        sellingPriceKobo: validated.sellingPriceKobo,
        feeKobo: validated.feeKobo,
        discountPercent: validated.discountPercent,
        commissionPercent: validated.commissionPercent,
        enabled,
        updatedBy: req.uid,
        updatedAt: FieldValue.serverTimestamp(),
      };
      if (!existingSnap.exists) {
        payload.createdAt = FieldValue.serverTimestamp();
      }

      await targetRef.set(payload, { merge: true });
      invalidateLiveCatalogCache();

      await logProviderEvent({
        provider: "SYSTEM",
        action: "ADMIN_PLAN_PRICE_OVERRIDE",
        reference: docId,
        statusCode: "200",
        outcome: "SUCCESS",
        message: `Admin ${req.email || req.uid} set override on ${productName}: sell=₦${(validated.sellingPriceKobo / 100).toFixed(2)}, fee=₦${(validated.feeKobo / 100).toFixed(2)}.`,
      });

      res.json({
        status: "success",
        message: `Saved validated pricing override for ${productName}.`,
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
  // 7. EXECUTIVE PROFIT DASHBOARD METRICS (Daily / Weekly / Monthly / All Time)
  // =========================================================================
  app.get("/api/v1/admin/overview-metrics", requireAuth, requireAdmin, async (_req: AdminAuthedRequest, res: Response) => {
    try {
      const [usersSnap, txSnap, ticketsSnap, ckBalRes, alrBalRes, flwBalRes, controlsRes] = await Promise.allSettled([
        db.collection("users").get(),
        db.collection("transactions").get(),
        db.collection("supportTickets").where("status", "in", ["OPEN", "IN_PROGRESS"]).get(),
        clubKonnect.isConfigured ? clubKonnect.getWalletBalance() : Promise.resolve(null),
        alrahuzData.isConfigured ? alrahuzData.getWalletBalance() : Promise.resolve(null),
        flutterwave.isConfigured ? flutterwave.getNgnBalance() : Promise.resolve(null),
        getProviderControls(),
      ]);

      const usersDocs = usersSnap.status === "fulfilled" ? usersSnap.value.docs : [];
      const txDocs = txSnap.status === "fulfilled" ? txSnap.value.docs : [];
      const openTicketsCount = ticketsSnap.status === "fulfilled" ? ticketsSnap.value.size : 0;
      const controls = controlsRes.status === "fulfilled" ? controlsRes.value : await getProviderControls();

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
        individualUsers += 1;
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
      const last30dMs = nowMs - 30 * 24 * 60 * 60 * 1000;

      interface ServiceBucketAcc {
        serviceType: string;
        label: string;
        txCount: number;
        volumeKobo: number;
        providerCostKobo: number;
        profitKobo: number;
        feesKobo: number;
      }

      interface PeriodAcc {
        totalSalesKobo: number;
        providerCostsKobo: number;
        grossProfitKobo: number;
        paymentFeesKobo: number;
        refundsKobo: number;
        refundCount: number;
        txCount: number;
        byService: Map<string, ServiceBucketAcc>;
      }

      const newPeriodAcc = (): PeriodAcc => ({
        totalSalesKobo: 0,
        providerCostsKobo: 0,
        grossProfitKobo: 0,
        paymentFeesKobo: 0,
        refundsKobo: 0,
        refundCount: 0,
        txCount: 0,
        byService: new Map<string, ServiceBucketAcc>(),
      });

      const periods = {
        daily: newPeriodAcc(),
        weekly: newPeriodAcc(),
        monthly: newPeriodAcc(),
        allTime: newPeriodAcc(),
      };

      const recordInPeriod = (
        acc: PeriodAcc,
        type: string,
        status: string,
        totalKobo: number,
        providerCostKobo: number,
        marginKobo: number,
        feeKobo: number
      ) => {
        if (status === "REVERSED") {
          acc.refundsKobo += totalKobo;
          acc.refundCount += 1;
          return;
        }
        if (status !== "SUCCESSFUL") return;

        acc.txCount += 1;
        acc.paymentFeesKobo += feeKobo;

        if (type !== "WALLET_FUNDING") {
          acc.totalSalesKobo += totalKobo;
          acc.providerCostsKobo += providerCostKobo;
          acc.grossProfitKobo += marginKobo;
        }

        const bucket = acc.byService.get(type) ?? {
          serviceType: type,
          label: SERVICE_LABELS[type] || type,
          txCount: 0,
          volumeKobo: 0,
          providerCostKobo: 0,
          profitKobo: 0,
          feesKobo: 0,
        };
        bucket.txCount += 1;
        bucket.volumeKobo += totalKobo;
        bucket.providerCostKobo += type === "WALLET_FUNDING" ? 0 : providerCostKobo;
        bucket.profitKobo += marginKobo;
        bucket.feesKobo += feeKobo;
        acc.byService.set(type, bucket);
      };

      let allTimeTxCount = 0;
      let successfulCount = 0;
      let pendingCount = 0;
      let failedCount = 0;
      let reversedCount = 0;

      for (const doc of txDocs) {
        const tx = doc.data();
        const type = String(tx.type || "OTHER").toUpperCase();
        const status = String(tx.status || "PENDING").toUpperCase();
        const totalKobo = Math.max(0, Math.round(Number(tx.totalAmountKobo || 0)));
        const costKobo = Math.max(0, Math.round(Number(tx.providerCostKobo || 0)));
        const marginKobo = Math.round(Number(tx.grossMarginKobo || 0));
        const feeKobo = Math.max(0, Math.round(Number(tx.feeKobo || 0)));
        const createdMs = toEpochMs(tx.createdAt);

        allTimeTxCount += 1;
        if (status === "SUCCESSFUL") successfulCount += 1;
        else if (status === "PENDING" || status === "PROCESSING") pendingCount += 1;
        else if (status === "REVERSED") reversedCount += 1;
        else failedCount += 1;

        recordInPeriod(periods.allTime, type, status, totalKobo, costKobo, marginKobo, feeKobo);
        if (createdMs >= last30dMs) {
          recordInPeriod(periods.monthly, type, status, totalKobo, costKobo, marginKobo, feeKobo);
        }
        if (createdMs >= last7dMs) {
          recordInPeriod(periods.weekly, type, status, totalKobo, costKobo, marginKobo, feeKobo);
        }
        if (createdMs >= todayMs) {
          recordInPeriod(periods.daily, type, status, totalKobo, costKobo, marginKobo, feeKobo);
        }
      }

      const serializePeriod = (acc: PeriodAcc) => ({
        totalSalesKobo: acc.totalSalesKobo,
        providerCostsKobo: acc.providerCostsKobo,
        grossProfitKobo: acc.grossProfitKobo,
        paymentFeesKobo: acc.paymentFeesKobo,
        refundsKobo: acc.refundsKobo,
        refundCount: acc.refundCount,
        txCount: acc.txCount,
        serviceBreakdown: Array.from(acc.byService.values()).sort((a, b) => b.volumeKobo - a.volumeKobo),
      });

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

      let alrahuzDataBalanceKobo = 0;
      let alrahuzDataReachable = false;
      if (alrBalRes.status === "fulfilled" && alrBalRes.value) {
        alrahuzDataBalanceKobo = Math.round(alrBalRes.value.balanceNaira * 100);
        alrahuzDataReachable = true;
      }

      let flutterwaveAvailableKobo = 0;
      let flutterwaveLedgerKobo = 0;
      let flutterwaveReachable = false;
      if (flwBalRes.status === "fulfilled" && flwBalRes.value) {
        flutterwaveAvailableKobo = Math.round(flwBalRes.value.availableNaira * 100);
        flutterwaveLedgerKobo = Math.round(flwBalRes.value.ledgerNaira * 100);
        flutterwaveReachable = true;
      }

      const totalProviderFloatKobo = clubKonnectBalanceKobo + alrahuzDataBalanceKobo + flutterwaveAvailableKobo;
      const floatCoveragePercent =
        totalWalletLiabilitiesKobo > 0
          ? Number(((totalProviderFloatKobo / totalWalletLiabilitiesKobo) * 100).toFixed(1))
          : 100.0;
      const completedTerminalCount = successfulCount + failedCount + reversedCount;
      const successRatePercent =
        completedTerminalCount > 0
          ? Number(((successfulCount / completedTerminalCount) * 100).toFixed(1))
          : 100.0;

      const serializedDaily = serializePeriod(periods.daily);
      const serializedWeekly = serializePeriod(periods.weekly);
      const serializedMonthly = serializePeriod(periods.monthly);
      const serializedAllTime = serializePeriod(periods.allTime);

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
        clubKonnectEnabled: isProviderEnabled(controls, "CLUBKONNECT"),
        alrahuzDataBalanceKobo,
        alrahuzDataReachable,
        alrahuzDataEnabled: isProviderEnabled(controls, "ALRAHUZDATA"),
        flutterwaveAvailableKobo,
        flutterwaveLedgerKobo,
        flutterwaveReachable,
        totalProviderFloatKobo,
        floatCoveragePercent,
        lowClubKonnectFloatWarning: clubKonnectReachable && clubKonnectBalanceKobo < 1_000_000,
        solvencyAlert:
          (clubKonnectReachable || alrahuzDataReachable || flutterwaveReachable) &&
          totalWalletLiabilitiesKobo > 0 &&
          totalProviderFloatKobo < totalWalletLiabilitiesKobo,
        allTimeVolumeKobo: serializedAllTime.totalSalesKobo,
        allTimeProfitKobo: serializedAllTime.grossProfitKobo,
        allTimeTxCount,
        todayVolumeKobo: serializedDaily.totalSalesKobo,
        todayProfitKobo: serializedDaily.grossProfitKobo,
        todayTxCount: serializedDaily.txCount,
        last7dVolumeKobo: serializedWeekly.totalSalesKobo,
        last7dProfitKobo: serializedWeekly.grossProfitKobo,
        last7dTxCount: serializedWeekly.txCount,
        last30dVolumeKobo: serializedMonthly.totalSalesKobo,
        last30dProfitKobo: serializedMonthly.grossProfitKobo,
        last30dTxCount: serializedMonthly.txCount,
        successfulCount,
        pendingCount,
        failedCount,
        reversedCount,
        successRatePercent,
        openTicketsCount,
        serviceBreakdown: serializedAllTime.serviceBreakdown,
        dailyMetrics: serializedDaily,
        weeklyMetrics: serializedWeekly,
        monthlyMetrics: serializedMonthly,
        allTimeMetrics: serializedAllTime,
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });

  // =========================================================================
  // 8. ADMIN CROSS-PLATFORM PUSH NOTIFICATIONS (TARGETED OR BROADCAST ALL)
  // =========================================================================
  const ALLOWED_ADMIN_PUSH_TYPES = new Set<NotificationEventType>([
    "ANNOUNCEMENT",
    "SUPPORT_REPLY",
    "SECURITY_ALERT",
    "ACCOUNT_STATUS",
    "WALLET_FUNDING",
    "PURCHASE_SUCCESS",
    "PURCHASE_FAILED",
    "TRANSACTION_PENDING",
    "REFUND",
  ]);

  app.post("/api/v1/admin/notifications/send", requireAuth, requireAdmin, async (req: AdminAuthedRequest, res: Response) => {
    try {
      const title = typeof req.body?.title === "string" ? req.body.title.trim() : "";
      const body = typeof req.body?.body === "string" ? req.body.body.trim() : "";
      const targetUserId = typeof req.body?.targetUserId === "string" ? req.body.targetUserId.trim() : "";
      const broadcastAll = req.body?.broadcastAll === true;
      const reference = typeof req.body?.reference === "string" ? req.body.reference.trim() : undefined;
      const rawType = String(req.body?.type || "ANNOUNCEMENT").trim().toUpperCase() as NotificationEventType;
      const type: NotificationEventType = ALLOWED_ADMIN_PUSH_TYPES.has(rawType) ? rawType : "ANNOUNCEMENT";

      if (title.length < 2 || title.length > 120) {
        res.status(400).json({ status: "error", message: "Title must be between 2 and 120 characters." });
        return;
      }
      if (body.length < 2 || body.length > 600) {
        res.status(400).json({ status: "error", message: "Message must be between 2 and 600 characters." });
        return;
      }
      if (!broadcastAll && !targetUserId) {
        res.status(400).json({ status: "error", message: "Specify targetUserId or set broadcastAll: true." });
        return;
      }

      if (broadcastAll) {
        const result = await broadcastCrossPlatformAnnouncement({ title, body, reference });
        await logProviderEvent({
          provider: "SYSTEM",
          action: "ADMIN_PUSH_BROADCAST",
          reference: reference || `broadcast_${Date.now()}`,
          statusCode: "200",
          outcome: "SUCCESS",
          message: `Broadcasted announcement to ${result.totalUsers} users (${result.deliveredCount}/${result.attemptedTokens} devices delivered, ${result.prunedCount} stale pruned).`,
        });
        res.json({
          status: "success",
          totalUsers: result.totalUsers,
          attemptedTokens: result.attemptedTokens,
          deliveredCount: result.deliveredCount,
          prunedCount: result.prunedCount,
          message: `Announcement sent to ${result.totalUsers} user(s) (${result.deliveredCount} push device${result.deliveredCount === 1 ? "" : "s"} delivered).`,
        });
        return;
      }

      const result = await sendCrossPlatformNotification({
        userId: targetUserId,
        title,
        body,
        type,
        reference,
      });

      res.json({
        status: "success",
        notificationId: result.notificationId,
        attemptedTokens: result.attemptedTokens,
        deliveredCount: result.deliveredCount,
        prunedCount: result.prunedCount,
        message:
          result.deliveredCount > 0
            ? `Notification delivered to ${result.deliveredCount} device(s) and saved to user inbox.`
            : "Notification saved to user inbox (no active push token registered yet).",
      });
    } catch (err) {
      res.status(400).json({ status: "error", message: (err as Error).message });
    }
  });
}
