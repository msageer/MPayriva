import express, { Request, Response, NextFunction } from "express";
import crypto from "crypto";
import { FieldValue } from "firebase-admin/firestore";
import { authAdmin, CONFIG, db, hashPin, notifyCustomer, timingSafeEqualStr } from "./config";
import { ClubKonnectClient } from "./clubKonnectClient";
import { FlutterwaveClient } from "./flutterwaveClient";
import { atomicCreditWallet, atomicReserveAndCreatePurchase, atomicReverseConfirmedFailure } from "./ledgerEngine";
import { classifyClubKonnectStatus, computeAuthorizedPurchasePricing } from "./pricingEngine";

const app = express();
app.set("trust proxy", 1);
app.disable("x-powered-by");
app.use(express.json({ limit: "256kb" }));

// Security response headers across all endpoints
app.use((_req: Request, res: Response, next: NextFunction) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Cache-Control", "no-store");
  next();
});

const clubKonnect = new ClubKonnectClient();
const flutterwave = new FlutterwaveClient();

interface AuthenticatedRequest extends Request {
  uid?: string;
  email?: string;
  emailVerified?: boolean;
  isAdmin?: boolean;
}

async function resolveAdminStatus(uid: string, email?: string, emailVerified?: boolean): Promise<boolean> {
  const isBootstrapped =
    email?.toLowerCase() === CONFIG.bootstrappedAdminEmail && emailVerified === true;
  if (isBootstrapped) return true;
  const adminDoc = await db.collection("admins").doc(uid).get();
  return adminDoc.exists;
}

async function requireAuth(req: AuthenticatedRequest, res: Response, next: NextFunction) {
  const header = req.headers.authorization;
  if (!header?.startsWith("Bearer ")) {
    res.status(401).json({ status: "error", message: "Missing Bearer token" });
    return;
  }
  try {
    const decoded = await authAdmin.verifyIdToken(header.slice(7));
    req.uid = decoded.uid;
    req.email = decoded.email;
    req.emailVerified = decoded.email_verified;
    req.isAdmin = await resolveAdminStatus(decoded.uid, decoded.email, decoded.email_verified);
    next();
  } catch {
    res.status(401).json({ status: "error", message: "Invalid or expired session token" });
  }
}

async function requireAdmin(req: AuthenticatedRequest, res: Response, next: NextFunction) {
  if (!req.uid) {
    res.status(401).json({ status: "error", message: "Unauthorized" });
    return;
  }
  if (!req.isAdmin) {
    res.status(403).json({ status: "error", message: "Admin authorization required" });
    return;
  }
  next();
}

async function verifyServerTransactionPin(uid: string, pin: string): Promise<void> {
  if (!/^\d{4}$/.test(pin)) throw new Error("Transaction PIN must be 4 digits.");
  const pinRef = db.collection("users").doc(uid).collection("security").doc("pin");
  const snap = await pinRef.get();
  if (!snap.exists) throw new Error("Transaction PIN is not set.");
  const data = snap.data()!;
  const nowMs = Date.now();
  if (Number(data.lockedUntilEpochMs || 0) > nowMs) {
    throw new Error("Transaction PIN is temporarily locked due to failed attempts.");
  }
  const computed = hashPin(pin, String(data.pinSalt));
  if (!timingSafeEqualStr(computed, String(data.pinHash))) {
    const nextAttempts = Math.min(5, Number(data.failedAttempts || 0) + 1);
    const lockUntil = nextAttempts >= 5 ? nowMs + 15 * 60 * 1000 : 0;
    await pinRef.update({
      failedAttempts: nextAttempts,
      lockedUntilEpochMs: lockUntil,
      updatedAt: FieldValue.serverTimestamp(),
    });
    throw new Error("Incorrect Transaction PIN.");
  }
  if (Number(data.failedAttempts || 0) > 0) {
    await pinRef.update({
      failedAttempts: 0,
      lockedUntilEpochMs: 0,
      updatedAt: FieldValue.serverTimestamp(),
    });
  }
}

async function reconcileTransactionWithClubKonnect(reference: string) {
  const txRef = db.collection("transactions").doc(reference);
  const txSnap = await txRef.get();
  if (!txSnap.exists) {
    return { notFound: true as const };
  }
  const txData = txSnap.data()!;
  if (txData.status === "SUCCESSFUL" || txData.status === "REVERSED") {
    return {
      notFound: false as const,
      status: String(txData.status),
      tokenOrPin: txData.tokenOrPin ? String(txData.tokenOrPin) : null,
      message: `Transaction is already finalized (${txData.status}).`,
    };
  }

  const queryRes = await clubKonnect.queryTransactionStatus(reference);
  const classified = classifyClubKonnectStatus(queryRes);

  if (classified.resolution === "SUCCESSFUL") {
    await txRef.update({
      status: "SUCCESSFUL",
      providerOrderId: classified.orderId || txData.providerOrderId || null,
      tokenOrPin: classified.tokenOrPin || txData.tokenOrPin || null,
      updatedAt: FieldValue.serverTimestamp(),
    });
    await notifyCustomer({
      userId: String(txData.userId),
      title: `${txData.productName || "Utility Order"} Completed`,
      body: `Your order (${reference}) was confirmed successful.`,
      type: "PURCHASE_SUCCESS",
      reference,
    });
    return {
      notFound: false as const,
      status: "SUCCESSFUL",
      tokenOrPin: classified.tokenOrPin || txData.tokenOrPin || null,
      message: "Provider confirmed transaction completed.",
    };
  }

  if (classified.resolution === "FAILED") {
    await atomicReverseConfirmedFailure(reference, `Requery confirmed failure: ${classified.reason}`);
    return {
      notFound: false as const,
      status: "REVERSED",
      tokenOrPin: null,
      message: "Provider confirmed cancellation. Wallet automatically refunded.",
    };
  }

  return {
    notFound: false as const,
    status: "PENDING",
    tokenOrPin: null,
    message: `Provider status: ${classified.reason}`,
  };
}

// 0. PUBLIC HEALTH & PAYMENT REDIRECT PAGES
app.get("/health", (_req: Request, res: Response) => {
  res.json({
    status: "ok",
    service: "m-sageer-pay-backend",
    environment: CONFIG.environment,
    timestamp: new Date().toISOString(),
  });
});

app.get("/payment-complete", (_req: Request, res: Response) => {
  res.type("text/plain").send(
    "Payment submitted. Return to the M-Sageer Pay app and tap 'Verify Payment Status' or wait for automatic webhook settlement."
  );
});

// 1. WALLET: Dedicated Flutterwave Virtual Account
app.post("/api/v1/wallet/virtual-account", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const userRef = db.collection("users").doc(uid);
    const userSnap = await userRef.get();
    if (!userSnap.exists) {
      res.status(404).json({ status: "error", message: "Customer profile not found." });
      return;
    }
    const user = userSnap.data()!;
    if (user.virtualAccountNumber) {
      res.json({
        status: "success",
        message: "Dedicated virtual account retrieved.",
        bankName: user.virtualBankName,
        accountNumber: user.virtualAccountNumber,
        accountName: user.virtualAccountName,
      });
      return;
    }

    const rawBvn = typeof req.body?.bvn === "string" ? req.body.bvn.trim() : "";
    if (rawBvn && !/^\d{11}$/.test(rawBvn)) {
      res.status(400).json({ status: "error", message: "BVN must be exactly 11 digits." });
      return;
    }

    const nameParts = String(user.fullName || "Customer").trim().split(/\s+/);
    const txRef = `MSGR-VA-${uid.slice(0, 8)}-${Date.now()}`;
    const flw = await flutterwave.createDedicatedVirtualAccount({
      email: String(user.email),
      txRef,
      phoneNumber: String(user.phoneNumber),
      firstName: nameParts[0] || "Customer",
      lastName: nameParts.slice(1).join(" ") || "MSageer",
      bvn: rawBvn || undefined,
      narration: `M-SAGEER ${user.businessName || user.fullName}`,
    });

    const flwData = (flw.data || {}) as Record<string, unknown>;
    const bankName = String(flwData.bank_name || "Wema Bank");
    const accountNumber = String(flwData.account_number || "");
    const accountName = String(user.businessName || user.fullName);

    await userRef.update({
      virtualBankName: bankName,
      virtualAccountNumber: accountNumber,
      virtualAccountName: accountName,
      updatedAt: FieldValue.serverTimestamp(),
    });

    res.json({
      status: "success",
      message: "Dedicated virtual bank account provisioned.",
      bankName,
      accountNumber,
      accountName,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 2. WALLET: Initiate Flutterwave Card Checkout
app.post("/api/v1/wallet/fund-card", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const amountKobo = Number(req.body?.amountKobo || 0);
    if (!Number.isInteger(amountKobo) || amountKobo < 10000 || amountKobo > 100000000) {
      res.status(400).json({ status: "error", message: "Card funding must be between ₦100.00 and ₦1,000,000.00." });
      return;
    }
    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(404).json({ status: "error", message: "Customer profile not found." });
      return;
    }
    const user = userSnap.data()!;
    const reference = `MSGR-FLW-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
    const flw = await flutterwave.initiateStandardPayment({
      txRef: reference,
      amountNaira: amountKobo / 100,
      email: String(user.email),
      phoneNumber: String(user.phoneNumber),
      customerName: String(user.businessName || user.fullName),
    });

    const link = String(((flw.data || {}) as Record<string, unknown>).link || "");
    res.json({
      status: "success",
      message: "Checkout session ready. Wallet credits after verified payment confirmation.",
      reference,
      checkoutUrl: link,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 2B. WALLET: Verify Flutterwave Card Funding & Idempotently Credit Wallet
app.post("/api/v1/wallet/verify-funding", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const reference = typeof req.body?.reference === "string" ? req.body.reference.trim() : "";
    const flwTxId =
      typeof req.body?.flutterwaveTransactionId === "string" ? req.body.flutterwaveTransactionId.trim() : "";

    if (!reference && !flwTxId) {
      res.status(400).json({ status: "FAILED", message: "Provide reference or flutterwaveTransactionId." });
      return;
    }

    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(404).json({ status: "FAILED", message: "Customer profile not found." });
      return;
    }
    const userEmail = String(userSnap.data()?.email || "").toLowerCase();

    const verification = flwTxId
      ? await flutterwave.verifyTransactionById(flwTxId)
      : await flutterwave.verifyTransactionByReference(reference);

    const verifiedData = (verification.data || {}) as Record<string, unknown>;
    const flwStatus = String(verifiedData.status || "").toLowerCase();
    const flwCurrency = String(verifiedData.currency || "").toUpperCase();
    const flwEmail = String(((verifiedData.customer || {}) as Record<string, unknown>).email || "").toLowerCase();
    const flwId = String(verifiedData.id || "");
    const txRef = String(verifiedData.tx_ref || reference);

    if (verification.status !== "success" || flwStatus !== "successful" || flwCurrency !== "NGN") {
      res.status(200).json({
        status: "PENDING",
        reference: txRef,
        message: `Payment not yet settled by Flutterwave (status: ${flwStatus || "pending"}).`,
      });
      return;
    }

    if (!req.isAdmin && flwEmail && userEmail && flwEmail !== userEmail) {
      res.status(403).json({ status: "FAILED", message: "Payment customer email does not match signed-in account." });
      return;
    }

    const amountKobo = Math.round(Number(verifiedData.amount || 0) * 100);
    const feeKobo = Math.round(Number(verifiedData.app_fee || 0) * 100);

    const creditRes = await atomicCreditWallet({
      userId: uid,
      idempotencyKey: `flw_tx_${flwId || txRef}`,
      reference: txRef,
      amountKobo,
      feeKobo,
      providerOrderId: flwId || undefined,
      narration: `Flutterwave verified deposit (${verifiedData.payment_type || "card"})`,
    });

    res.json({
      status: "SUCCESSFUL",
      transactionId: txRef,
      reference: txRef,
      message: creditRes.alreadyProcessed
        ? "Payment was already verified and credited to your wallet."
        : "Payment verified and credited to your NGN wallet.",
    });
  } catch (err) {
    res.status(400).json({ status: "FAILED", message: (err as Error).message });
  }
});

// 3. VERIFY ELECTRICITY METER (ClubKonnect)
app.post("/api/v1/verify/meter", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const electricCompany = String(req.body?.electricCompany || "").trim();
    const meterNo = String(req.body?.meterNo || "").trim();
    const meterType = String(req.body?.meterType || "prepaid").trim();
    if (!electricCompany || meterNo.length < 6) {
      res.status(400).json({ verified: false, message: "Enter a valid DisCo and meter number." });
      return;
    }

    const result = await clubKonnect.verifyElectricityMeter({
      electricCompany,
      meterNo,
      meterType,
    });
    const name = String(result.customer_name || result.Customer_Name || "").trim();
    const valid = Boolean(name && !name.toUpperCase().includes("INVALID"));
    res.json({
      verified: valid,
      customerName: valid ? name : null,
      customerAddress: result.address ? String(result.address) : null,
      message: valid ? "Meter verified" : "Unable to verify meter number",
    });
  } catch (err) {
    res.status(400).json({ verified: false, message: (err as Error).message });
  }
});

// 4. VERIFY CABLE SMARTCARD (ClubKonnect)
app.post("/api/v1/verify/smartcard", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const cableTv = String(req.body?.cableTv || "").trim();
    const smartCardNo = String(req.body?.smartCardNo || "").trim();
    if (!cableTv || smartCardNo.length < 6) {
      res.status(400).json({ verified: false, message: "Enter a valid Cable provider and smartcard/IUC number." });
      return;
    }

    const result = await clubKonnect.verifyCableSmartCard({
      cableTv,
      smartCardNo,
    });
    const name = String(result.customer_name || result.Customer_Name || "").trim();
    const valid = Boolean(name && !name.toUpperCase().includes("INVALID"));
    res.json({
      verified: valid,
      customerName: valid ? name : null,
      message: valid ? "Smartcard verified" : "Unable to verify smartcard",
    });
  } catch (err) {
    res.status(400).json({ verified: false, message: (err as Error).message });
  }
});

// 5. CENTRAL TRANSACTION ENGINE (13-Step Server-Authoritative Purchase)
app.post("/api/v1/transactions/purchase", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const {
      idempotencyKey,
      serviceType,
      providerCode,
      productCode,
      recipient,
      amountKobo,
      meterType,
      quantity = 1,
      transactionPin,
    } = req.body || {};

    if (
      typeof idempotencyKey !== "string" ||
      typeof serviceType !== "string" ||
      typeof providerCode !== "string" ||
      typeof recipient !== "string" ||
      typeof transactionPin !== "string" ||
      !idempotencyKey.trim() ||
      !recipient.trim()
    ) {
      res.status(400).json({ status: "FAILED", message: "Missing or invalid purchase fields." });
      return;
    }

    // Step 6: Server-side Transaction PIN Verification
    await verifyServerTransactionPin(uid, transactionPin.trim());

    // Step 3/4: Authorize pricing from Firestore /serviceCatalog (never trust client margin)
    const normalizedService = serviceType.trim().toUpperCase();
    const normalizedProvider = providerCode.trim();
    const normalizedProduct = String(productCode || "").trim();

    const catalogSnap = await db
      .collection("serviceCatalog")
      .where("serviceType", "==", normalizedService)
      .where("enabled", "==", true)
      .get();

    const matchedCatalog = catalogSnap.docs
      .map((d) => d.data())
      .find(
        (item) =>
          (normalizedProduct && item.productCode === normalizedProduct && item.providerCode === normalizedProvider) ||
          (normalizedProduct && item.productCode === normalizedProduct) ||
          item.providerCode === normalizedProvider ||
          item.providerCode === "ALL_DISCOS"
      );

    const pricing = computeAuthorizedPurchasePricing({
      serviceType: normalizedService,
      providerCode: normalizedProvider,
      productCode: normalizedProduct,
      requestedAmountKobo: Number(amountKobo || 0),
      quantity: Number(quantity || 1),
      catalog: matchedCatalog,
    });

    const reference = `MSGR-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;

    // Steps 7–9: Atomic balance validation, idempotency lock, and double-entry debit
    await atomicReserveAndCreatePurchase({
      userId: uid,
      idempotencyKey: idempotencyKey.trim(),
      reference,
      serviceType: normalizedService,
      providerCode: normalizedProvider,
      productCode: normalizedProduct,
      productName: pricing.productName,
      recipient: recipient.trim(),
      providerCostKobo: pricing.providerCostKobo,
      sellingPriceKobo: pricing.sellingPriceKobo,
      feeKobo: pricing.feeKobo,
    });

    // Step 10–11: Submit to ClubKonnect & inspect response (NEVER refund on timeout!)
    try {
      let providerRes: Record<string, unknown>;
      switch (normalizedService) {
        case "AIRTIME":
          providerRes = await clubKonnect.buyAirtime({
            mobileNetwork: normalizedProvider,
            amountNaira: pricing.faceValueNaira,
            mobileNumber: recipient.trim(),
            requestId: reference,
          });
          break;
        case "DATA":
          providerRes = await clubKonnect.buyData({
            mobileNetwork: normalizedProvider,
            dataPlan: normalizedProduct,
            mobileNumber: recipient.trim(),
            requestId: reference,
          });
          break;
        case "ELECTRICITY":
          providerRes = await clubKonnect.payElectricity({
            electricCompany: normalizedProvider,
            meterNo: recipient.trim(),
            meterType: String(meterType || "prepaid"),
            amountNaira: pricing.faceValueNaira,
            phoneNo: recipient.trim(),
            requestId: reference,
          });
          break;
        case "CABLE_TV":
          providerRes = await clubKonnect.payCableTv({
            cableTv: normalizedProvider,
            packageCode: normalizedProduct,
            smartCardNo: recipient.trim(),
            phoneNo: recipient.trim(),
            requestId: reference,
          });
          break;
        case "WAEC_PIN":
          providerRes = await clubKonnect.buyWaecPin({
            examType: normalizedProduct || "waecdirect",
            phoneNo: recipient.trim(),
            requestId: reference,
          });
          break;
        case "JAMB_PIN":
          providerRes = await clubKonnect.buyJambPin({
            examType: normalizedProduct || "utme-mock",
            profileId: recipient.trim(),
            phoneNo: recipient.trim(),
            requestId: reference,
          });
          break;
        case "RECHARGE_CARD":
          providerRes = await clubKonnect.buyRechargeEpin({
            mobileNetwork: normalizedProvider,
            valueNaira: pricing.faceValueNaira,
            quantity: pricing.quantity,
            requestId: reference,
          });
          break;
        default:
          throw new Error(`Unsupported service type: ${normalizedService}`);
      }

      const classified = classifyClubKonnectStatus(providerRes);

      if (classified.resolution === "SUCCESSFUL") {
        await db.collection("transactions").doc(reference).update({
          status: "SUCCESSFUL",
          providerOrderId: classified.orderId,
          tokenOrPin: classified.tokenOrPin,
          updatedAt: FieldValue.serverTimestamp(),
        });
        await notifyCustomer({
          userId: uid,
          title: `${pricing.productName} Successful`,
          body: `Your order (${reference}) completed successfully.`,
          type: "PURCHASE_SUCCESS",
          reference,
        });
        res.json({
          status: "SUCCESSFUL",
          transactionId: reference,
          reference,
          message: "Transaction successful.",
          tokenOrPin: classified.tokenOrPin,
        });
      } else if (classified.resolution === "PENDING") {
        await db.collection("transactions").doc(reference).update({
          status: "PENDING",
          providerOrderId: classified.orderId,
          updatedAt: FieldValue.serverTimestamp(),
        });
        res.status(202).json({
          status: "PENDING",
          transactionId: reference,
          reference,
          message: "Order received by provider. Status will update once confirmed.",
        });
      } else {
        await atomicReverseConfirmedFailure(reference, classified.reason);
        res.status(400).json({
          status: "REVERSED",
          transactionId: reference,
          reference,
          message: `Order failed and refunded: ${classified.reason}`,
        });
      }
    } catch (providerErr) {
      // CRITICAL FINANCIAL RULE: On uncertain network timeout, mark PENDING (NOT refunded) until APIQueryV1 confirms!
      await db.collection("transactions").doc(reference).update({
        status: "PENDING",
        failureReason: `Awaiting provider confirmation: ${(providerErr as Error).message}`,
        updatedAt: FieldValue.serverTimestamp(),
      });
      res.status(202).json({
        status: "PENDING",
        transactionId: reference,
        reference,
        message: "Order submitted; awaiting provider verification. Use 'Verify Status' to reconcile.",
      });
    }
  } catch (err) {
    res.status(400).json({ status: "FAILED", message: (err as Error).message });
  }
});

// 6. TRANSACTION REQUERY & RECONCILIATION (Owner or Admin -> ClubKonnect APIQueryV1.asp)
app.post("/api/v1/transactions/:reference/requery", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const reference = String(req.params.reference || "").trim();
    const txSnap = await db.collection("transactions").doc(reference).get();
    if (!txSnap.exists) {
      res.status(404).json({ status: "error", message: "Transaction not found" });
      return;
    }
    const txData = txSnap.data()!;
    if (txData.userId !== req.uid && !req.isAdmin) {
      res.status(403).json({ status: "error", message: "Forbidden" });
      return;
    }

    const result = await reconcileTransactionWithClubKonnect(reference);
    if (result.notFound) {
      res.status(404).json({ status: "error", message: "Transaction not found" });
      return;
    }
    res.json({
      status: result.status,
      transactionId: reference,
      reference,
      message: result.message,
      tokenOrPin: result.tokenOrPin,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 6B. CLUBKONNECT ASYNC CALLBACK (Never blindly trust callback payload — independently requery APIQueryV1.asp)
async function handleClubKonnectCallback(req: Request, res: Response) {
  const reference = String(
    req.query?.requestid || req.query?.RequestID || req.body?.requestid || req.body?.RequestID || ""
  ).trim();
  if (!reference || !/^[A-Za-z0-9_-]{6,80}$/.test(reference)) {
    res.status(200).json({ received: true, ignored: true });
    return;
  }
  try {
    await reconcileTransactionWithClubKonnect(reference);
    res.status(200).json({ received: true, reconciled: reference });
  } catch {
    res.status(200).json({ received: true, deferred: reference });
  }
}
app.get("/webhooks/clubkonnect", handleClubKonnectCallback);
app.post("/webhooks/clubkonnect", handleClubKonnectCallback);

// 7. FLUTTERWAVE WEBHOOK (Signature-verified + Independently verified via GET /v3/transactions/:id/verify)
app.post("/webhooks/flutterwave", async (req: Request, res: Response) => {
  const sig = req.headers["verif-hash"] as string | undefined;
  if (!flutterwave.verifyWebhookSignature(sig)) {
    res.status(401).json({ status: "error", message: "Invalid webhook signature" });
    return;
  }

  try {
    const event = req.body?.event;
    const data = req.body?.data || {};
    if (event === "charge.completed" && data.status === "successful" && data.id) {
      const verification = await flutterwave.verifyTransactionById(String(data.id));
      const verifiedData = (verification.data || {}) as Record<string, unknown>;
      if (verifiedData.status === "successful" && verifiedData.currency === "NGN") {
        const email = String(((verifiedData.customer || {}) as Record<string, unknown>).email || "");
        const usersSnap = await db.collection("users").where("email", "==", email).limit(1).get();
        if (!usersSnap.empty) {
          const userId = usersSnap.docs[0].id;
          const amountKobo = Math.round(Number(verifiedData.amount || 0) * 100);
          const feeKobo = Math.round(Number(verifiedData.app_fee || 0) * 100);
          const reference = String(verifiedData.tx_ref || `FLW-${verifiedData.id}`);
          await atomicCreditWallet({
            userId,
            idempotencyKey: `flw_tx_${verifiedData.id}`,
            reference,
            amountKobo,
            feeKobo,
            providerOrderId: String(verifiedData.id),
            narration: `Flutterwave verified deposit (${verifiedData.payment_type || "transfer"})`,
          });
        }
      }
    }
    res.status(200).json({ received: true });
  } catch (err) {
    res.status(500).json({ status: "error", message: (err as Error).message });
  }
});

// 8. ADMIN: PROVIDER HEALTH CHECK
app.get("/api/v1/admin/provider-health", requireAuth, requireAdmin, async (_req: AuthenticatedRequest, res: Response) => {
  let balance: string | null = null;
  if (clubKonnect.isConfigured) {
    try {
      const balRes = await clubKonnect.getWalletBalance();
      balance = String(balRes.balance || balRes.Balance || "Unavailable");
    } catch {
      balance = "Query failed";
    }
  }
  res.json({
    clubKonnectConfigured: clubKonnect.isConfigured,
    clubKonnectBalanceNaira: balance,
    flutterwaveConfigured: flutterwave.isConfigured,
    environment: CONFIG.environment,
    message:
      clubKonnect.isConfigured && flutterwave.isConfigured
        ? "All providers configured and reachable."
        : "Set missing provider environment secrets on Cloud Run / Cloud Functions.",
  });
});

// 8B. ADMIN: RECONCILE ANY PENDING TRANSACTION
app.post("/api/v1/admin/transactions/:reference/reconcile", requireAuth, requireAdmin, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const reference = String(req.params.reference || "").trim();
    const result = await reconcileTransactionWithClubKonnect(reference);
    if (result.notFound) {
      res.status(404).json({ status: "error", message: "Transaction not found." });
      return;
    }
    res.json({
      status: result.status,
      transactionId: reference,
      reference,
      message: result.message,
      tokenOrPin: result.tokenOrPin,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 8C. ADMIN: SYNC LIVE CLUBKONNECT PROVIDER COSTS INTO /serviceCatalog
app.post("/api/v1/admin/catalog/sync-clubkonnect", requireAuth, requireAdmin, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const [dataPlans, cablePlans, waecPlans, jambPlans] = await Promise.all([
      clubKonnect.fetchDataPlans(),
      clubKonnect.fetchCablePackages(),
      clubKonnect.fetchWaecPackages(),
      clubKonnect.fetchJambPackages(),
    ]);

    // Extract productCode -> provider Naira cost lookup from ClubKonnect v2 JSON payload
    const liveCostsNaira = new Map<string, number>();
    const extractRecursively = (node: unknown) => {
      if (!node || typeof node !== "object") return;
      if (Array.isArray(node)) {
        node.forEach(extractRecursively);
        return;
      }
      const obj = node as Record<string, unknown>;
      const code = obj.PRODUCT_ID || obj.PRODUCT_CODE || obj.ID;
      const amount = obj.PRODUCT_AMOUNT || obj.AMOUNT;
      if (code && amount && !Number.isNaN(Number(amount))) {
        liveCostsNaira.set(String(code).trim(), Number(amount));
      }
      Object.values(obj).forEach(extractRecursively);
    };

    extractRecursively(dataPlans);
    extractRecursively(cablePlans);
    extractRecursively(waecPlans);
    extractRecursively(jambPlans);

    const catalogSnap = await db.collection("serviceCatalog").get();
    let updatedCount = 0;
    const batch = db.batch();

    for (const docSnap of catalogSnap.docs) {
      const item = docSnap.data();
      const prodCode = String(item.productCode || "").trim();
      const liveNaira = liveCostsNaira.get(prodCode);
      if (liveNaira && liveNaira > 0) {
        const newCostKobo = Math.round(liveNaira * 100);
        const currentSellKobo = Number(item.sellingPriceKobo || 0);
        const safeSellKobo = currentSellKobo < newCostKobo ? Math.round(newCostKobo * 1.05) : currentSellKobo;
        batch.update(docSnap.ref, {
          providerCostKobo: newCostKobo,
          sellingPriceKobo: safeSellKobo,
          updatedBy: req.uid,
          updatedAt: FieldValue.serverTimestamp(),
        });
        updatedCount += 1;
      }
    }

    if (updatedCount > 0) {
      await batch.commit();
    }

    res.json({
      status: "success",
      discoveredProducts: liveCostsNaira.size,
      updatedCatalogItems: updatedCount,
      message: `Synced ClubKonnect feed (${liveCostsNaira.size} live products discovered, ${updatedCount} catalog items updated).`,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

const port = Number(process.env.PORT || 8080);
app.listen(port, "0.0.0.0", () => {
  console.log(`M-Sageer Pay trusted backend listening on 0.0.0.0:${port} (${CONFIG.environment})`);
});
