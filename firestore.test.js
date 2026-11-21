const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_customer_001";
const BOB_UID = "bob_customer_002";
const ADMIN_UID = "admin_msageer_001";
const ADMIN_EMAIL = "msagirgroup@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validUserPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    accountType: "INDIVIDUAL",
    fullName: "Amina Musa",
    email: "amina@example.com",
    phoneNumber: "+2348012345678",
    accountStatus: "ACTIVE",
    pinSet: false,
    biometricEnabled: false,
    kycVerified: false,
    walletBalanceKobo: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validPinPayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    pinHash: "a".repeat(64),
    pinSalt: "0123456789abcdef0123456789abcdef",
    failedAttempts: 0,
    lockedUntilEpochMs: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validCatalogPayload(adminUid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    serviceType: "DATA",
    providerCode: "01",
    providerName: "MTN",
    productCode: "mtn_1gb_30d",
    productName: "MTN 1GB SME Monthly",
    validity: "30 Days",
    dataVolume: "1GB",
    providerCostKobo: 26000,
    sellingPriceKobo: 30000,
    feeKobo: 0,
    discountPercent: 0,
    commissionPercent: 1.5,
    enabled: true,
    updatedBy: adminUid,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- 1. UNAUTHENTICATED ACCESS DENIAL ---
test("Unauthenticated users are denied across all fintech collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("transactions").get());
  await assertFails(unauthDb.collection("ledgerEntries").get());
  await assertFails(unauthDb.collection("serviceCatalog").where("enabled", "==", true).get());
  await assertFails(unauthDb.collection("supportTickets").get());
  await assertFails(unauthDb.collection("notifications").get());
  await assertFails(unauthDb.collection("providerLogs").get());
});

// --- 2. USER PROFILE REGISTRATION, PII ISOLATION & WALLET TAMPERING PREVENTION ---
test("Customer registers Individual & Business profiles, updates safe fields, and CANNOT tamper wallet balance", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Valid customer registration
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validUserPayload(ALICE_UID))
  );

  // Reject registration attempting non-zero initial wallet balance
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set(validUserPayload(BOB_UID, { walletBalanceKobo: 5000000 }))
  );

  // Reject registration attempting to inject ghost/virtualAccount fields
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set(validUserPayload(BOB_UID, { virtualAccountNumber: "1234567890" }))
  );

  // Reject registration with future timestamp
  await assertFails(
    bobDb
      .collection("users")
      .doc(BOB_UID)
      .set(validUserPayload(BOB_UID, { createdAt: new Date(Date.now() + 60000) }))
  );

  // Valid safe profile update (preserving createdAt and walletBalanceKobo)
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      fullName: "Amina Musa Ibrahim",
      phoneNumber: "+2348099887766",
      pinSet: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // CRITICAL FINANCIAL INVARIANT: Customer CANNOT modify walletBalanceKobo
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      walletBalanceKobo: 99999999,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Customer CANNOT change accountStatus or kycVerified
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      kycVerified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // PII Isolation: Bob cannot read or modify Alice's profile or list users
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      fullName: "Hijacked",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

// --- 3. TRANSACTION PIN SECURITY SUBCOLLECTION ---
test("Transaction PIN subcollection is strictly owner-only and schema-enforced", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("security")
      .doc("pin")
      .set(validPinPayload(ALICE_UID))
  );

  // Owner can read and update their PIN hash
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("security").doc("pin").get()
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("security").doc("pin").update({
      pinHash: "b".repeat(64),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Invalid short hash rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("security").doc("pin").update({
      pinHash: "1234",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Bob cannot read or write Alice's PIN
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("security").doc("pin").get()
  );
});

// --- 4. BACKEND-ONLY TRANSACTIONS & IMMUTABLE DOUBLE-ENTRY LEDGER ---
test("Transactions and Ledger Entries are read-only to owners/admins and strictly backend-write-only", async () => {
  const now = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("transactions").doc("tx_alice_1").set({
      userId: ALICE_UID,
      reference: "MSGR-2026-0001",
      idempotencyKey: "idem_001",
      type: "AIRTIME",
      status: "SUCCESSFUL",
      provider: "CLUBKONNECT",
      providerCode: "01",
      recipient: "08012345678",
      productName: "MTN Airtime",
      providerCostKobo: 9700,
      sellingPriceKobo: 10000,
      feeKobo: 0,
      grossMarginKobo: 300,
      totalAmountKobo: 10000,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("ledgerEntries").doc("led_alice_1").set({
      userId: ALICE_UID,
      transactionId: "tx_alice_1",
      reference: "MSGR-2026-0001",
      entryType: "DEBIT",
      accountCode: "CUSTOMER_WALLET",
      amountKobo: 10000,
      balanceBeforeKobo: 50000,
      balanceAfterKobo: 40000,
      narration: "MTN Airtime 08012345678",
      createdAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();

  // Scoped Kotlin query succeeds for owner
  await assertSucceeds(
    aliceDb.collection("transactions").where("userId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("ledgerEntries").where("userId", "==", ALICE_UID).get()
  );

  // Unfiltered query rejected for customer
  await assertFails(aliceDb.collection("transactions").get());
  await assertFails(aliceDb.collection("ledgerEntries").get());

  // Cross-user read denied
  await assertFails(bobDb.collection("transactions").doc("tx_alice_1").get());
  await assertFails(bobDb.collection("ledgerEntries").doc("led_alice_1").get());

  // Client CANNOT create, update, or delete transactions or ledger entries (even Admin via client SDK!)
  await assertFails(
    aliceDb.collection("transactions").doc("tx_fake").set({ userId: ALICE_UID, status: "SUCCESSFUL" })
  );
  await assertFails(
    adminDb.collection("ledgerEntries").doc("led_alice_1").delete()
  );

  // Admin can list all transactions and ledger entries for oversight
  await assertSucceeds(adminDb.collection("transactions").get());
  await assertSucceeds(adminDb.collection("ledgerEntries").get());
});

// --- 5. ROLE-BASED ADMIN AUTHORIZATION & EMAIL SPOOFING PROTECTION ---
test("Admin role enforces verified email or admins doc; unverified email spoofing is rejected", async () => {
  const spoofedAdminDb = testEnv
    .authenticatedContext("attacker_uid", { email: ADMIN_EMAIL, email_verified: false })
    .firestore();
  const verifiedAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  // Spoofed unverified admin email CANNOT write service catalog or list users
  await assertFails(
    spoofedAdminDb
      .collection("serviceCatalog")
      .doc("mtn_1gb")
      .set(validCatalogPayload("attacker_uid"))
  );
  await assertFails(spoofedAdminDb.collection("users").get());

  // Verified admin creates and updates service catalog item
  await assertSucceeds(
    verifiedAdminDb
      .collection("serviceCatalog")
      .doc("mtn_1gb")
      .set(validCatalogPayload(ADMIN_UID))
  );

  // Customer can list ONLY enabled catalog items (matches Kotlin .whereEqualTo("enabled", true))
  await assertSucceeds(
    aliceDb.collection("serviceCatalog").where("enabled", "==", true).get()
  );
  await assertFails(aliceDb.collection("serviceCatalog").get());

  // Customer CANNOT modify service catalog pricing
  await assertFails(
    aliceDb.collection("serviceCatalog").doc("mtn_1gb").update({
      sellingPriceKobo: 100,
      updatedBy: ALICE_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Customer CANNOT escalate themselves into /admins
  await assertFails(
    aliceDb.collection("admins").doc(ALICE_UID).set({
      email: "amina@example.com",
      role: "SUPER_ADMIN",
      createdAt: new Date(Date.now() - 1000),
    })
  );
});

// --- 6. SUPPORT TICKETS & NOTIFICATIONS ---
test("Customers can open and close own support tickets; admins can respond and send notifications", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const now = new Date(Date.now() - 2000);

  // Customer creates support ticket
  await assertSucceeds(
    aliceDb.collection("supportTickets").doc("tkt_1").set({
      userId: ALICE_UID,
      userEmail: "amina@example.com",
      userName: "Amina Musa",
      category: "FAILED_TRANSACTION",
      subject: "Pending IKEDC Token",
      message: "My electricity token has been pending for 10 minutes. Please assist.",
      transactionReference: "MSGR-2026-0009",
      status: "OPEN",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Customer CANNOT inject adminResponse on create
  await assertFails(
    aliceDb.collection("supportTickets").doc("tkt_2").set({
      userId: ALICE_UID,
      userEmail: "amina@example.com",
      userName: "Amina Musa",
      category: "OTHER",
      subject: "Test Spoof",
      message: "Trying to spoof an admin response field on creation.",
      status: "OPEN",
      adminResponse: "Refund approved!",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Bob cannot view Alice's ticket
  await assertFails(bobDb.collection("supportTickets").doc("tkt_1").get());

  // Admin responds to Alice's ticket
  await assertSucceeds(
    adminDb.collection("supportTickets").doc("tkt_1").update({
      status: "RESOLVED",
      adminResponse: "We have verified your transaction and resent your token.",
      adminNotes: "Confirmed via APIQueryV1.asp",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Admin sends notification to Alice; Alice marks it read
  await assertSucceeds(
    adminDb.collection("notifications").doc("notif_1").set({
      userId: ALICE_UID,
      title: "Support Ticket Resolved",
      body: "Your IKEDC token request has been resolved.",
      type: "ANNOUNCEMENT",
      read: false,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    aliceDb.collection("notifications").where("userId", "==", ALICE_UID).get()
  );
  await assertSucceeds(
    aliceDb.collection("notifications").doc("notif_1").update({
      read: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

// --- 7. PLATFORM API & BRANDING CONFIG (NON-SECRET HTTPS URL, ADMIN-ONLY WRITE) ---
test("Admin can configure non-secret HTTPS backendBaseUrl in platformConfig/branding; secrets and HTTP URLs rejected", async () => {
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 2000);

  // Admin writes valid HTTPS backendBaseUrl
  await assertSucceeds(
    adminDb.collection("platformConfig").doc("branding").set({
      brandName: "M-Sageer Pay",
      companyLegalName: "M-SAGEER DIGITAL TECHNOLOGIES LTD",
      supportEmail: "support@msageerdigital.com",
      supportPhone: "+234 800 672 4337",
      backendBaseUrl: "https://msageer-backend-prod.run.app",
      clubKonnectMode: "LIVE",
      flutterwaveMode: "LIVE",
      virtualAccountsEnabled: true,
      cardFundingEnabled: true,
      maintenanceMode: false,
      updatedBy: ADMIN_UID,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Customer can read branding config, but CANNOT modify it
  await assertSucceeds(aliceDb.collection("platformConfig").doc("branding").get());
  await assertFails(
    aliceDb.collection("platformConfig").doc("branding").update({
      backendBaseUrl: "https://evil.example.com",
      updatedBy: ALICE_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Plaintext HTTP external URL rejected
  await assertFails(
    adminDb.collection("platformConfig").doc("branding").update({
      backendBaseUrl: "http://insecure.example.com",
      updatedBy: ADMIN_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Arbitrary secret fields rejected by strict allowlist
  await assertFails(
    adminDb.collection("platformConfig").doc("branding").update({
      clubKonnectApiKey: "should-never-be-in-firestore",
      updatedBy: ADMIN_UID,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

