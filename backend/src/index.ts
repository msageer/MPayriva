import express, { Request, Response, NextFunction } from "express";
import crypto from "crypto";
import { FieldValue } from "firebase-admin/firestore";
import { authAdmin, CONFIG, db, hashPin, logProviderEvent, notifyCustomer, timingSafeEqualStr } from "./config";
import { ClubKonnectClient } from "./clubKonnectClient";
import { FlutterwaveClient } from "./flutterwaveClient";
import { atomicCreditWallet, atomicReserveAndCreatePurchase, atomicReverseConfirmedFailure } from "./ledgerEngine";
import { classifyClubKonnectStatus, computeAuthorizedPurchasePricing } from "./pricingEngine";
import { findAuthorizedLiveProduct, getLiveStorefrontCatalog, invalidateLiveCatalogCache } from "./liveCatalogEngine";
import { registerAdminTreasuryAndPricingRoutes } from "./adminRoutes";

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

  const queryRes = await clubKonnect.queryTransactionStatus({
    orderId: txData.providerOrderId ? String(txData.providerOrderId) : null,
    requestId: reference,
  });
  const classified = classifyClubKonnectStatus(queryRes, txData.type === "DATA_CARD", true);

  if (classified.resolution === "SUCCESSFUL") {
    await txRef.update({
      status: "SUCCESSFUL",
      providerOrderId: classified.orderId || txData.providerOrderId || null,
      tokenOrPin: classified.tokenOrPin || txData.tokenOrPin || null,
      serialNumber: classified.serialNumber || txData.serialNumber || null,
      ...(classified.vouchers.length > 0 ? { vouchers: classified.vouchers } : {}),
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
      vouchers: classified.vouchers,
      message: "Provider confirmed transaction completed.",
    };
  }

  if (classified.resolution === "FAILED") {
    await atomicReverseConfirmedFailure(reference, `Requery confirmed failure: ${classified.reason}`);
    return {
      notFound: false as const,
      status: "REVERSED",
      tokenOrPin: null,
      message: `Order cancelled and refunded: ${classified.reason}`,
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
  res.type("text/html").send(
    "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>Payment Complete</title></head>" +
      "<body style='font-family:sans-serif;text-align:center;padding:48px 20px;background:#F4F7FA;color:#0A1D37'>" +
      "<h2>Payment Received</h2><p>Verifying your payment now. You can close this window to return to M-Sageer Pay.</p></body></html>"
  );
});

// 0B. LIVE STOREFRONT CATALOG (Fetches all live ClubKonnect services + e-PIN/Data Card stock availability)
app.get("/api/v1/catalog/live", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const forceRefresh = req.query.refresh === "true" || req.query.refresh === "1";
    const liveCatalog = await getLiveStorefrontCatalog(clubKonnect, forceRefresh);
    res.json(liveCatalog);
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
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
    const orderRef = String(flwData.order_ref || "");
    const accountName = String(user.businessName || user.fullName);

    await userRef.update({
      virtualBankName: bankName,
      virtualAccountNumber: accountNumber,
      virtualAccountName: accountName,
      virtualAccountOrderRef: orderRef || null,
      virtualAccountTxRef: txRef,
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

// 4B. VERIFY BETTING ACCOUNT (ClubKonnect)
app.post("/api/v1/verify/betting", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const bettingCompany = String(req.body?.bettingCompany || "").trim();
    const customerId = String(req.body?.customerId || "").trim();
    if (!bettingCompany || customerId.length < 3) {
      res.status(400).json({ verified: false, message: "Enter a valid betting platform and customer ID." });
      return;
    }
    const result = await clubKonnect.verifyBettingCustomer({ bettingCompany, customerId });
    const name = String(result.customer_name || result.Customer_Name || "").trim();
    const valid = Boolean(name && !name.toUpperCase().includes("INVALID"));
    res.json({
      verified: valid,
      customerName: valid ? name : null,
      message: valid ? "Betting account verified" : "Unable to verify betting account ID",
    });
  } catch (err) {
    res.status(400).json({ verified: false, message: (err as Error).message });
  }
});

// 4C. VERIFY SMILE BROADBAND ACCOUNT (ClubKonnect)
app.post("/api/v1/verify/smile", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const mobileNumber = String(req.body?.mobileNumber || "").trim();
    if (mobileNumber.length < 5) {
      res.status(400).json({ verified: false, message: "Enter a valid Smile account email or number." });
      return;
    }
    const result = await clubKonnect.verifySmileAccount({
      mobileNetwork: "smile-direct",
      mobileNumber,
    });
    const name = String(result.customer_name || result.Customer_Name || "").trim();
    const valid = Boolean(name && !name.toUpperCase().includes("INVALID"));
    res.json({
      verified: valid,
      customerName: valid ? name : null,
      message: valid ? "Smile account verified" : "Unable to verify Smile account",
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

    const isVariableAmountService =
      normalizedService === "AIRTIME" ||
      normalizedService === "ELECTRICITY" ||
      normalizedService === "BETTING";

    const adminCatalogItems = catalogSnap.docs.map((d) => d.data());
    let matchedCatalog = adminCatalogItems.find((item) => {
      const itemProd = String(item.productCode || "").trim().toLowerCase();
      const itemProv = String(item.providerCode || "").trim().toLowerCase();
      const reqProd = normalizedProduct.toLowerCase();
      const reqProv = normalizedProvider.toLowerCase();
      if (reqProd && itemProd === reqProd && itemProv === reqProv) return true;
      if (isVariableAmountService && (itemProv === reqProv || itemProv === "all_discos")) return true;
      return false;
    });

    // Enforce Admin Utility Service Toggles from /platformConfig/branding
    const brandingSnap = await db.collection("platformConfig").doc("branding").get();
    const enabledServices = (brandingSnap.data()?.enabledServices || {}) as Record<string, unknown>;
    if (enabledServices[normalizedService] === false) {
      res.status(403).json({
        status: "FAILED",
        message: "This service is currently paused by the administrator. Please try again later.",
      });
      return;
    }

    // Check live catalog & stock availability
    const liveLookup = await findAuthorizedLiveProduct(
      clubKonnect,
      normalizedService,
      normalizedProvider,
      normalizedProduct
    );
    if (!liveLookup.available) {
      res.status(400).json({
        status: "FAILED",
        message: liveLookup.unavailableReason || "This service is currently unavailable. Please try again shortly.",
      });
      return;
    }
    if (!matchedCatalog && liveLookup.pricingDoc) {
      matchedCatalog = liveLookup.pricingDoc as Record<string, unknown>;
    }

    const pricing = computeAuthorizedPurchasePricing({
      serviceType: normalizedService,
      providerCode: normalizedProvider,
      productCode: normalizedProduct,
      requestedAmountKobo: Number(amountKobo || 0),
      quantity: Number(quantity || 1),
      catalog: matchedCatalog,
    });

    // Pure alphanumeric RequestID (no hyphens) for maximum ClubKonnect API compatibility
    const reference = `MSGR${Date.now()}${crypto.randomBytes(2).toString("hex").toUpperCase()}`;

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
        case "DATA_CARD":
          providerRes = await clubKonnect.buyDataCardEpin({
            mobileNetwork: normalizedProvider,
            dataPlan: normalizedProduct,
            quantity: pricing.quantity,
            requestId: reference,
          });
          break;
        case "SMILE":
          providerRes = await clubKonnect.buySmileData({
            mobileNetwork: normalizedProvider || "smile-direct",
            dataPlan: normalizedProduct,
            mobileNumber: recipient.trim(),
            requestId: reference,
          });
          break;
        case "SPECTRANET":
          providerRes = await clubKonnect.buySpectranetPin({
            mobileNetwork: normalizedProvider || "spectranet",
            dataPlan: normalizedProduct,
            mobileNumber: recipient.trim(),
            requestId: reference,
          });
          break;
        case "BETTING":
          providerRes = await clubKonnect.fundBettingWallet({
            bettingCompany: normalizedProvider,
            customerId: recipient.trim(),
            amountNaira: pricing.faceValueNaira,
            requestId: reference,
          });
          break;
        default:
          throw new Error(`Unsupported service type: ${normalizedService}`);
      }

      let classified = classifyClubKonnectStatus(providerRes, normalizedService === "DATA_CARD", false);

      // ClubKonnect frequently returns ORDER_RECEIVED (code 100) and settles within 2-3 seconds.
      // Perform one quick verification poll by OrderID before returning PENDING to the customer.
      if (classified.resolution === "PENDING" && classified.orderId) {
        await new Promise((resolve) => setTimeout(resolve, 2500));
        try {
          const pollRes = await clubKonnect.queryTransactionStatus({
            orderId: classified.orderId,
            requestId: reference,
          });
          const polled = classifyClubKonnectStatus(pollRes, normalizedService === "DATA_CARD", true);
          if (polled.resolution === "SUCCESSFUL" || polled.resolution === "FAILED") {
            classified = {
              ...polled,
              orderId: polled.orderId || classified.orderId,
            };
          }
        } catch {
          // Keep initial PENDING state — never reverse on poll network hiccup
        }
      }

      if (classified.resolution === "SUCCESSFUL") {
        await db.collection("transactions").doc(reference).update({
          status: "SUCCESSFUL",
          providerOrderId: classified.orderId,
          tokenOrPin: classified.tokenOrPin,
          serialNumber: classified.serialNumber,
          ...(classified.vouchers.length > 0 ? { vouchers: classified.vouchers } : {}),
          updatedAt: FieldValue.serverTimestamp(),
        });
        await notifyCustomer({
          userId: uid,
          title: `${pricing.productName} Successful`,
          body:
            classified.vouchers.length > 0
              ? `Generated ${classified.vouchers.length} printable e-PIN(s) (Ref: ${reference}). Open History anytime to print or save as PDF.`
              : `Your order (${reference}) completed successfully.`,
          type: "PURCHASE_SUCCESS",
          reference,
        });
        res.json({
          status: "SUCCESSFUL",
          transactionId: reference,
          reference,
          message:
            classified.vouchers.length > 0
              ? `Generated ${classified.vouchers.length} e-PIN voucher(s). Ready to print or save as PDF!`
              : "Transaction successful.",
          tokenOrPin: classified.tokenOrPin,
          serialNumber: classified.serialNumber,
          vouchers: classified.vouchers,
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
          message: "Order accepted and processing. Status will update automatically once delivery confirms.",
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
  let reference = String(
    req.query?.requestid || req.query?.RequestID || req.body?.requestid || req.body?.RequestID || ""
  ).trim();
  const orderId = String(
    req.query?.orderid || req.query?.OrderID || req.body?.orderid || req.body?.OrderID || ""
  ).trim();

  // ClubKonnect callbacks frequently include orderid with an empty requestid — resolve by providerOrderId!
  if ((!reference || !/^[A-Za-z0-9_-]{6,80}$/.test(reference)) && orderId) {
    try {
      const byOrder = await db
        .collection("transactions")
        .where("providerOrderId", "==", orderId)
        .limit(1)
        .get();
      if (!byOrder.empty) {
        reference = byOrder.docs[0].id;
      }
    } catch {
      // Ignore lookup error and fall through
    }
  }

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

function sanitizeDocId(raw: string, fallback: string): string {
  const cleaned = raw.replace(/[^a-zA-Z0-9_-]/g, "_").slice(0, 120);
  return cleaned.length >= 3 ? cleaned : fallback;
}

function extractFlutterwaveTxMeta(verifiedData: Record<string, unknown>) {
  const meta = (verifiedData.meta || {}) as Record<string, unknown>;
  const customer = (verifiedData.customer || {}) as Record<string, unknown>;
  const accountObj = (verifiedData.account || {}) as Record<string, unknown>;

  const flwId = String(verifiedData.id || "").trim();
  const txRef = String(verifiedData.tx_ref || "").trim();
  const orderRef = String(meta.order_ref || verifiedData.order_ref || "").trim();
  const rawEmail = String(customer.email || "").trim();
  const emailLower = rawEmail.toLowerCase();
  const paymentType = String(verifiedData.payment_type || "bank_transfer").trim();

  const candidateAccountNumbers = Array.from(
    new Set(
      [
        meta.account_number,
        meta.bankaccountnumber,
        meta.beneficiary_account_number,
        accountObj.account_number,
        verifiedData.account_number,
      ]
        .map((v) => String(v || "").trim())
        .filter((v) => /^\d{10}$/.test(v))
    )
  );

  const vaPrefixMatch = txRef.match(/^MSGR-VA-([A-Za-z0-9]{6,12})-/);
  const uidPrefix = vaPrefixMatch ? vaPrefixMatch[1] : "";

  return { flwId, txRef, orderRef, rawEmail, emailLower, paymentType, candidateAccountNumbers, uidPrefix };
}

async function resolveUserForFlutterwaveTx(verifiedData: Record<string, unknown>): Promise<string | null> {
  const info = extractFlutterwaveTxMeta(verifiedData);

  // 1. Match by stored virtualAccountTxRef
  if (info.txRef) {
    const byTxRef = await db.collection("users").where("virtualAccountTxRef", "==", info.txRef).limit(1).get();
    if (!byTxRef.empty) return byTxRef.docs[0].id;
  }

  // 2. Match by stored virtualAccountOrderRef
  if (info.orderRef) {
    const byOrderRef = await db.collection("users").where("virtualAccountOrderRef", "==", info.orderRef).limit(1).get();
    if (!byOrderRef.empty) return byOrderRef.docs[0].id;
  }

  // 3. Match by dedicated virtual account number
  for (const acctNo of info.candidateAccountNumbers) {
    const byAcct = await db.collection("users").where("virtualAccountNumber", "==", acctNo).limit(1).get();
    if (!byAcct.empty) return byAcct.docs[0].id;
  }

  // 4. Match by exact email
  if (info.rawEmail) {
    const byEmail = await db.collection("users").where("email", "==", info.rawEmail).limit(1).get();
    if (!byEmail.empty) return byEmail.docs[0].id;
  }

  // 5. Fallback scan: uid prefix from MSGR-VA-{uid8}-... OR case-insensitive email match
  if (info.uidPrefix || info.emailLower) {
    const allUsersSnap = await db.collection("users").get();
    for (const docSnap of allUsersSnap.docs) {
      if (info.uidPrefix && docSnap.id.startsWith(info.uidPrefix)) {
        return docSnap.id;
      }
      const docEmail = String(docSnap.data()?.email || "").trim().toLowerCase();
      if (info.emailLower && docEmail && docEmail === info.emailLower) {
        return docSnap.id;
      }
    }
  }

  return null;
}

async function creditVerifiedFlutterwaveTx(
  verifiedData: Record<string, unknown>,
  explicitUserId?: string
): Promise<{
  eligible: boolean;
  matched: boolean;
  alreadyProcessed: boolean;
  userId: string | null;
  reference: string;
  netCreditKobo: number;
}> {
  const flwStatus = String(verifiedData.status || "").toLowerCase();
  const flwCurrency = String(verifiedData.currency || "").toUpperCase();
  const info = extractFlutterwaveTxMeta(verifiedData);

  if (flwStatus !== "successful" || flwCurrency !== "NGN" || !info.flwId) {
    return { eligible: false, matched: false, alreadyProcessed: false, userId: null, reference: "", netCreditKobo: 0 };
  }

  const amountKobo = Math.round(Number(verifiedData.amount || 0) * 100);
  const feeKobo = Math.round(Number(verifiedData.app_fee || 0) * 100);
  if (amountKobo <= 0) {
    return { eligible: false, matched: false, alreadyProcessed: false, userId: null, reference: "", netCreditKobo: 0 };
  }

  const userId = explicitUserId || (await resolveUserForFlutterwaveTx(verifiedData));
  if (!userId) {
    return { eligible: true, matched: false, alreadyProcessed: false, userId: null, reference: "", netCreditKobo: 0 };
  }

  // Permanent virtual accounts reuse the same tx_ref across multiple bank transfers,
  // so use unique FLW-VA-{flwId} references for VA/bank transfers while preserving one-time MSGR-FLW-* refs.
  const isOneTimeCheckoutRef = info.txRef.startsWith("MSGR-FLW-");
  const rawReference = isOneTimeCheckoutRef ? info.txRef : `FLW-VA-${info.flwId}`;
  const reference = sanitizeDocId(rawReference, `FLW-${info.flwId}`);
  const idempotencyKey = `flw_tx_${info.flwId}`;
  const netCreditKobo = Math.max(0, amountKobo - feeKobo);

  const creditRes = await atomicCreditWallet({
    userId,
    idempotencyKey,
    reference,
    amountKobo,
    feeKobo,
    providerOrderId: info.flwId,
    narration: `Flutterwave verified deposit (${info.paymentType})`,
  });

  return {
    eligible: true,
    matched: true,
    alreadyProcessed: creditRes.alreadyProcessed,
    userId,
    reference,
    netCreditKobo,
  };
}

// 2C. WALLET: Sync Past & Recent Settled Flutterwave Bank Transfers for Signed-In User
app.post("/api/v1/wallet/sync-deposits", requireAuth, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(404).json({ status: "error", message: "Customer profile not found." });
      return;
    }
    const user = userSnap.data()!;
    const userEmail = String(user.email || "").trim();
    const userEmailLower = userEmail.toLowerCase();
    const storedVaTxRef = String(user.virtualAccountTxRef || "").trim();
    const storedVaNumber = String(user.virtualAccountNumber || "").trim();
    const uidPrefix = uid.slice(0, 8);

    const candidateTxs: Array<Record<string, unknown>> = [];
    if (userEmail) {
      const byEmail = await flutterwave.listSuccessfulTransactions({ customerEmail: userEmail, page: 1 });
      candidateTxs.push(...byEmail);
    }
    if (storedVaTxRef) {
      const byTxRef = await flutterwave.listSuccessfulTransactions({ txRef: storedVaTxRef, page: 1 });
      candidateTxs.push(...byTxRef);
    }

    const seenIds = new Set<string>();
    let syncedCount = 0;
    let alreadyCreditedCount = 0;
    let totalNewlyCreditedKobo = 0;

    for (const txItem of candidateTxs) {
      const txId = String(txItem.id || "").trim();
      if (!txId || seenIds.has(txId)) continue;
      seenIds.add(txId);

      // Independently verify every transaction against Flutterwave before crediting
      const verification = await flutterwave.verifyTransactionById(txId);
      const verifiedData = (verification.data || {}) as Record<string, unknown>;
      if (verification.status !== "success") continue;

      const meta = extractFlutterwaveTxMeta(verifiedData);
      const belongsToUser =
        (meta.emailLower && meta.emailLower === userEmailLower) ||
        (storedVaTxRef && meta.txRef === storedVaTxRef) ||
        (meta.uidPrefix && uid.startsWith(meta.uidPrefix)) ||
        (storedVaNumber && meta.candidateAccountNumbers.includes(storedVaNumber)) ||
        (meta.txRef.startsWith(`MSGR-VA-${uidPrefix}-`));

      if (!belongsToUser) continue;

      const outcome = await creditVerifiedFlutterwaveTx(verifiedData, uid);
      if (outcome.eligible && outcome.matched) {
        if (outcome.alreadyProcessed) {
          alreadyCreditedCount += 1;
        } else {
          syncedCount += 1;
          totalNewlyCreditedKobo += outcome.netCreditKobo;
        }
      }
    }

    const message =
      syncedCount > 0
        ? `Credited ${syncedCount} verified Flutterwave deposit(s) totaling ₦${(totalNewlyCreditedKobo / 100).toFixed(2)}.`
        : alreadyCreditedCount > 0
          ? `All ${alreadyCreditedCount} settled deposit(s) are already credited to your wallet.`
          : "No new settled Flutterwave deposits found for your account yet.";

    res.json({
      status: "success",
      syncedCount,
      alreadyCreditedCount,
      totalNewlyCreditedKobo,
      message,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 7. FLUTTERWAVE WEBHOOK (Signature-verified + Independently verified via GET /v3/transactions/:id/verify)
app.post("/webhooks/flutterwave", async (req: Request, res: Response) => {
  const sig = req.headers["verif-hash"] as string | undefined;
  const event = String(req.body?.event || "unknown");
  const rawData = (req.body?.data || req.body || {}) as Record<string, unknown>;
  const txId = String(rawData.id || "").trim();

  if (!flutterwave.verifyWebhookSignature(sig)) {
    await logProviderEvent({
      provider: "FLUTTERWAVE",
      action: "WEBHOOK /webhooks/flutterwave",
      reference: txId || event,
      statusCode: "401",
      outcome: "REJECTED_SIGNATURE",
      message: `Invalid verif-hash header on event=${event}. Check FLUTTERWAVE_WEBHOOK_HASH on backend vs Flutterwave Dashboard Secret Hash.`,
    });
    res.status(401).json({ status: "error", message: "Invalid webhook signature" });
    return;
  }

  try {
    const payloadStatus = String(rawData.status || "").toLowerCase();
    const isSuccessfulCharge =
      Boolean(txId) &&
      (event === "charge.completed" || payloadStatus === "successful");

    if (isSuccessfulCharge) {
      const verification = await flutterwave.verifyTransactionById(txId);
      const verifiedData = (verification.data || {}) as Record<string, unknown>;
      const outcome = await creditVerifiedFlutterwaveTx(verifiedData);

      await logProviderEvent({
        provider: "FLUTTERWAVE",
        action: "WEBHOOK /webhooks/flutterwave",
        reference: outcome.reference || txId,
        statusCode: "200",
        outcome: !outcome.eligible
          ? "IGNORED_INELIGIBLE"
          : !outcome.matched
            ? "UNMATCHED_CUSTOMER"
            : outcome.alreadyProcessed
              ? "DUPLICATE_IGNORED"
              : "CREDITED",
        message: outcome.matched
          ? `event=${event} user=${outcome.userId} netKobo=${outcome.netCreditKobo} alreadyProcessed=${outcome.alreadyProcessed}`
          : `event=${event} txId=${txId} could not be matched to a customer profile.`,
      });
    } else {
      await logProviderEvent({
        provider: "FLUTTERWAVE",
        action: "WEBHOOK /webhooks/flutterwave",
        reference: txId || event,
        statusCode: "200",
        outcome: "IGNORED_EVENT",
        message: `Received non-credit event=${event} status=${payloadStatus}`,
      });
    }
    res.status(200).json({ received: true });
  } catch (err) {
    await logProviderEvent({
      provider: "FLUTTERWAVE",
      action: "WEBHOOK /webhooks/flutterwave",
      reference: txId || event,
      statusCode: "500",
      outcome: "ERROR",
      message: (err as Error).message.slice(0, 300),
    });
    res.status(500).json({ status: "error", message: (err as Error).message });
  }
});

// 8. ADMIN: PROVIDER HEALTH CHECK (ClubKonnect Balance + Flutterwave Main NGN Balance)
app.get("/api/v1/admin/provider-health", requireAuth, requireAdmin, async (_req: AuthenticatedRequest, res: Response) => {
  let ckBalance: string | null = null;
  let flwAvailable: string | null = null;
  let flwLedger: string | null = null;

  if (clubKonnect.isConfigured) {
    try {
      const balRes = await clubKonnect.getWalletBalance();
      ckBalance = String(balRes.balance || balRes.Balance || "Unavailable");
    } catch {
      ckBalance = "Query failed";
    }
  }

  if (flutterwave.isConfigured) {
    try {
      const flwBal = await flutterwave.getNgnBalance();
      flwAvailable = flwBal.availableNaira.toFixed(2);
      flwLedger = flwBal.ledgerNaira.toFixed(2);
    } catch {
      flwAvailable = "Query failed";
      flwLedger = "Query failed";
    }
  }

  res.json({
    clubKonnectConfigured: clubKonnect.isConfigured,
    clubKonnectBalanceNaira: ckBalance,
    flutterwaveConfigured: flutterwave.isConfigured,
    flutterwaveAvailableBalanceNaira: flwAvailable,
    flutterwaveLedgerBalanceNaira: flwLedger,
    environment: CONFIG.environment,
    message:
      clubKonnect.isConfigured && flutterwave.isConfigured
        ? "All providers configured and reachable."
        : "Set missing provider environment secrets on your backend host.",
  });
});

// 8A. ADMIN: SYNC ALL PAST SETTLED FLUTTERWAVE DEPOSITS TO TIED USER ACCOUNTS
app.post("/api/v1/admin/wallet/sync-all-deposits", requireAuth, requireAdmin, async (_req: AuthenticatedRequest, res: Response) => {
  try {
    const seenIds = new Set<string>();
    let syncedCount = 0;
    let alreadyCreditedCount = 0;
    let unmatchedCount = 0;
    let totalNewlyCreditedKobo = 0;

    for (let page = 1; page <= 3; page += 1) {
      const pageTxs = await flutterwave.listSuccessfulTransactions({ page });
      if (pageTxs.length === 0) break;

      for (const txItem of pageTxs) {
        const txId = String(txItem.id || "").trim();
        if (!txId || seenIds.has(txId)) continue;
        seenIds.add(txId);

        const verification = await flutterwave.verifyTransactionById(txId);
        const verifiedData = (verification.data || {}) as Record<string, unknown>;
        if (verification.status !== "success") continue;

        const outcome = await creditVerifiedFlutterwaveTx(verifiedData);
        if (!outcome.eligible) continue;
        if (!outcome.matched) {
          unmatchedCount += 1;
        } else if (outcome.alreadyProcessed) {
          alreadyCreditedCount += 1;
        } else {
          syncedCount += 1;
          totalNewlyCreditedKobo += outcome.netCreditKobo;
        }
      }
    }

    const message =
      syncedCount > 0
        ? `Wired ${syncedCount} past Flutterwave deposit(s) totaling ₦${(totalNewlyCreditedKobo / 100).toFixed(2)} to tied wallets (${alreadyCreditedCount} already credited, ${unmatchedCount} unmatched).`
        : `Checked ${seenIds.size} Flutterwave transaction(s): ${alreadyCreditedCount} already credited, ${unmatchedCount} unmatched.`;

    res.json({
      status: "success",
      syncedCount,
      alreadyCreditedCount,
      totalNewlyCreditedKobo,
      message,
    });
  } catch (err) {
    res.status(400).json({ status: "error", message: (err as Error).message });
  }
});

// 8A2. ADMIN: FUND SUPER ADMIN APP WALLET FROM FLUTTERWAVE MAIN NGN BALANCE
app.post("/api/v1/admin/wallet/fund-from-main-balance", requireAuth, requireAdmin, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const amountKobo = Number(req.body?.amountKobo || 0);
    const rawIdem = typeof req.body?.idempotencyKey === "string" ? req.body.idempotencyKey.trim() : "";

    if (!Number.isInteger(amountKobo) || amountKobo < 10000 || amountKobo > 1000000000) {
      res.status(400).json({ status: "FAILED", message: "Amount must be between ₦100.00 and ₦10,000,000.00." });
      return;
    }

    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(400).json({
        status: "FAILED",
        message: "Super Admin wallet profile not initialized yet. Tap the Storefront icon once to complete your wallet profile.",
      });
      return;
    }

    // Verify live available balance in Flutterwave Main NGN account before allocating to Super Admin wallet
    const flwBal = await flutterwave.getNgnBalance();
    const availableKobo = Math.round(flwBal.availableNaira * 100);
    if (availableKobo < amountKobo) {
      res.status(400).json({
        status: "FAILED",
        message: `Requested ₦${(amountKobo / 100).toFixed(2)} exceeds Flutterwave Main NGN available balance (₦${flwBal.availableNaira.toFixed(2)}).`,
      });
      return;
    }

    const reference = `MSGR-MAIN-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
    const idempotencyKey = sanitizeDocId(
      rawIdem || `admin_main_${Date.now()}_${crypto.randomBytes(4).toString("hex")}`,
      `admin_main_${Date.now()}`
    );

    const creditRes = await atomicCreditWallet({
      userId: uid,
      idempotencyKey,
      reference,
      amountKobo,
      feeKobo: 0,
      providerOrderId: "FLW_MAIN_NGN",
      settlementAccountCode: "FLUTTERWAVE_MAIN_TREASURY",
      narration: `Super Admin draw from Flutterwave Main NGN Balance (Available: ₦${flwBal.availableNaira.toFixed(2)})`,
    });

    res.json({
      status: "SUCCESSFUL",
      transactionId: reference,
      reference,
      message: creditRes.alreadyProcessed
        ? "This main-balance transfer was already processed."
        : `Credited ₦${(amountKobo / 100).toFixed(2)} from Flutterwave Main NGN Balance (Available: ₦${flwBal.availableNaira.toFixed(2)}) to your Super Admin wallet.`,
    });
  } catch (err) {
    res.status(400).json({ status: "FAILED", message: (err as Error).message });
  }
});

// 8A3. ADMIN: USE CURRENT LIVE CLUBKONNECT BALANCE IN SUPER ADMIN WALLET
app.post("/api/v1/admin/wallet/fund-from-clubkonnect", requireAuth, requireAdmin, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const amountKobo = Number(req.body?.amountKobo || 0);
    const rawIdem = typeof req.body?.idempotencyKey === "string" ? req.body.idempotencyKey.trim() : "";

    if (!Number.isInteger(amountKobo) || amountKobo < 10000 || amountKobo > 1000000000) {
      res.status(400).json({ status: "FAILED", message: "Amount must be between ₦100.00 and ₦10,000,000.00." });
      return;
    }

    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(400).json({
        status: "FAILED",
        message: "Super Admin wallet profile not initialized yet. Tap the Storefront icon once to complete your wallet profile.",
      });
      return;
    }

    const ckRes = await clubKonnect.getWalletBalance();
    const rawBal = String(ckRes.balance ?? ckRes.walletbalance ?? "0").replace(/[^0-9.]/g, "");
    const ckBalanceNaira = Number(rawBal);
    const ckBalanceKobo = Number.isFinite(ckBalanceNaira) ? Math.round(ckBalanceNaira * 100) : 0;

    if (ckBalanceKobo < amountKobo) {
      res.status(400).json({
        status: "FAILED",
        message: `Requested ₦${(amountKobo / 100).toFixed(2)} exceeds current live ClubKonnect balance (₦${(ckBalanceKobo / 100).toFixed(2)}).`,
      });
      return;
    }

    const reference = `MSGR-CKBAL-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
    const idempotencyKey = sanitizeDocId(
      rawIdem || `admin_ck_${Date.now()}_${crypto.randomBytes(4).toString("hex")}`,
      `admin_ck_${Date.now()}`
    );

    const creditRes = await atomicCreditWallet({
      userId: uid,
      idempotencyKey,
      reference,
      amountKobo,
      feeKobo: 0,
      providerOrderId: "CLUBKONNECT_LIVE_FLOAT",
      settlementAccountCode: "CLUBKONNECT_FLOAT_TREASURY",
      narration: `Super Admin draw from live ClubKonnect Balance (Verified: ₦${(ckBalanceKobo / 100).toFixed(2)})`,
    });

    await logProviderEvent({
      provider: "CLUBKONNECT",
      action: "ADMIN_FUND_FROM_CLUBKONNECT",
      reference,
      statusCode: "200",
      outcome: "SUCCESS",
      message: `Allocated ₦${(amountKobo / 100).toFixed(2)} from live ClubKonnect balance (₦${(ckBalanceKobo / 100).toFixed(2)}) to Super Admin wallet.`,
    });

    res.json({
      status: "SUCCESSFUL",
      transactionId: reference,
      reference,
      message: creditRes.alreadyProcessed
        ? "This ClubKonnect balance allocation was already processed."
        : `Credited ₦${(amountKobo / 100).toFixed(2)} from your live ClubKonnect balance (₦${(ckBalanceKobo / 100).toFixed(2)}) to your Super Admin wallet.`,
    });
  } catch (err) {
    res.status(400).json({ status: "FAILED", message: (err as Error).message });
  }
});

// 8A4. ADMIN: TRANSFER CLUBKONNECT BALANCE ALLOCATION TO MAIN ACCOUNT TREASURY
app.post("/api/v1/admin/wallet/transfer-clubkonnect-to-main", requireAuth, requireAdmin, async (req: AuthenticatedRequest, res: Response) => {
  try {
    const uid = req.uid!;
    const amountKobo = Number(req.body?.amountKobo || 0);
    const rawIdem = typeof req.body?.idempotencyKey === "string" ? req.body.idempotencyKey.trim() : "";

    if (!Number.isInteger(amountKobo) || amountKobo < 10000 || amountKobo > 1000000000) {
      res.status(400).json({ status: "FAILED", message: "Transfer amount must be between ₦100.00 and ₦10,000,000.00." });
      return;
    }

    const userSnap = await db.collection("users").doc(uid).get();
    if (!userSnap.exists) {
      res.status(400).json({
        status: "FAILED",
        message: "Super Admin wallet profile not initialized yet. Tap the Storefront icon once to complete your wallet profile.",
      });
      return;
    }

    const ckRes = await clubKonnect.getWalletBalance();
    const rawBal = String(ckRes.balance ?? ckRes.walletbalance ?? "0").replace(/[^0-9.]/g, "");
    const ckBalanceNaira = Number(rawBal);
    const ckBalanceKobo = Number.isFinite(ckBalanceNaira) ? Math.round(ckBalanceNaira * 100) : 0;

    if (ckBalanceKobo < amountKobo) {
      res.status(400).json({
        status: "FAILED",
        message: `Transfer amount ₦${(amountKobo / 100).toFixed(2)} exceeds current live ClubKonnect balance (₦${(ckBalanceKobo / 100).toFixed(2)}).`,
      });
      return;
    }

    const reference = `MSGR-CK2MAIN-${Date.now()}-${crypto.randomBytes(3).toString("hex").toUpperCase()}`;
    const idempotencyKey = sanitizeDocId(
      rawIdem || `admin_ck2main_${Date.now()}_${crypto.randomBytes(4).toString("hex")}`,
      `admin_ck2main_${Date.now()}`
    );

    const creditRes = await atomicCreditWallet({
      userId: uid,
      idempotencyKey,
      reference,
      amountKobo,
      feeKobo: 0,
      providerOrderId: "CK_TO_MAIN_TREASURY",
      settlementAccountCode: "FLUTTERWAVE_MAIN_TREASURY",
      narration: `Treasury sweep: ClubKonnect Balance → Main Account (Verified CK Float: ₦${(ckBalanceKobo / 100).toFixed(2)})`,
    });

    await logProviderEvent({
      provider: "CLUBKONNECT",
      action: "TREASURY_TRANSFER_CK_TO_MAIN",
      reference,
      statusCode: "200",
      outcome: "SUCCESS",
      message: `Recorded audited ClubKonnect → Main Account treasury transfer of ₦${(amountKobo / 100).toFixed(2)} (Ref: ${reference}).`,
    });

    res.json({
      status: "SUCCESSFUL",
      transactionId: reference,
      reference,
      message: creditRes.alreadyProcessed
        ? "This ClubKonnect-to-Main transfer was already recorded."
        : `Transferred ₦${(amountKobo / 100).toFixed(2)} from ClubKonnect float into your Main Account ledger (Ref: ${reference}). Note: ClubKonnect API does not expose outbound bank payouts, so physical bank cash-out is initiated in your ClubKonnect portal if needed.`,
    });
  } catch (err) {
    res.status(400).json({ status: "FAILED", message: (err as Error).message });
  }
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
    invalidateLiveCatalogCache();

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

// Mount Super Admin Live Treasury Transfers, Pricing/Margin Rules Matrix, Batch Reconcile & Executive Overview Routes
registerAdminTreasuryAndPricingRoutes(app, {
  requireAuth,
  requireAdmin,
  clubKonnect,
  flutterwave,
  reconcileTransactionWithClubKonnect,
});

const port = Number(process.env.PORT || 8080);
app.listen(port, "0.0.0.0", () => {
  console.log(`M-Sageer Pay trusted backend listening on 0.0.0.0:${port} (${CONFIG.environment})`);
});
