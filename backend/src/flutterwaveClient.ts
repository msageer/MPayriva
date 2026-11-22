import { CONFIG, logProviderEvent, timingSafeEqualStr } from "./config";

/**
 * Official Flutterwave v3 API Client.
 * Documentation: https://developer.flutterwave.com/
 * Secret key and webhook hash remain strictly server-side.
 */
const FLW_BASE = "https://api.flutterwave.com/v3";
const REQUEST_TIMEOUT_MS = 25_000;

export class FlutterwaveClient {
  get isConfigured(): boolean {
    return Boolean(CONFIG.flutterwaveSecretKey);
  }

  private assertConfigured() {
    if (!this.isConfigured) {
      throw new Error(
        "Flutterwave secret key (FLUTTERWAVE_SECRET_KEY) is not configured on the backend."
      );
    }
  }

  verifyWebhookSignature(headerHash: string | undefined): boolean {
    if (!headerHash || !CONFIG.flutterwaveWebhookHash) return false;
    return timingSafeEqualStr(headerHash, CONFIG.flutterwaveWebhookHash);
  }

  private async requestJson(
    path: string,
    method: "GET" | "POST",
    actionLabel: string,
    reference: string,
    body?: Record<string, unknown>
  ): Promise<{ ok: boolean; status: number; json: Record<string, unknown> }> {
    this.assertConfigured();
    const res = await fetch(`${FLW_BASE}${path}`, {
      method,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      headers: {
        Authorization: `Bearer ${CONFIG.flutterwaveSecretKey}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
        Accept: "application/json",
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });

    const text = await res.text();
    let json: Record<string, unknown> = {};
    try {
      json = JSON.parse(text) as Record<string, unknown>;
    } catch {
      json = { status: "error", message: text.slice(0, 240) || res.statusText };
    }

    await logProviderEvent({
      provider: "FLUTTERWAVE",
      action: actionLabel,
      reference,
      statusCode: String(res.status),
      outcome: res.ok && json.status === "success" ? "SUCCESS" : "ERROR",
      message: String(json.message || res.statusText).slice(0, 300),
    });

    return { ok: res.ok, status: res.status, json };
  }

  async getNgnBalance(): Promise<{ availableNaira: number; ledgerNaira: number; raw: Record<string, unknown> }> {
    const { ok, json } = await this.requestJson(
      "/balances/NGN",
      "GET",
      "GET /v3/balances/NGN",
      "BALANCE_CHECK"
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Unable to fetch Flutterwave NGN balance"));
    }
    const data = (json.data || {}) as Record<string, unknown>;
    return {
      availableNaira: Number(data.available_balance ?? 0),
      ledgerNaira: Number(data.ledger_balance ?? 0),
      raw: json,
    };
  }

  async listSuccessfulTransactions(params: {
    customerEmail?: string;
    txRef?: string;
    from?: string;
    page?: number;
  } = {}): Promise<Array<Record<string, unknown>>> {
    const qs = new URLSearchParams();
    qs.set("status", "successful");
    qs.set("currency", "NGN");
    if (params.customerEmail) qs.set("customer_email", params.customerEmail);
    if (params.txRef) qs.set("tx_ref", params.txRef);
    if (params.from) qs.set("from", params.from);
    if (params.page) qs.set("page", String(params.page));

    const { ok, json } = await this.requestJson(
      `/transactions?${qs.toString()}`,
      "GET",
      "GET /v3/transactions",
      params.customerEmail || params.txRef || "SYNC_ALL"
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Unable to fetch Flutterwave transactions"));
    }
    return Array.isArray(json.data) ? (json.data as Array<Record<string, unknown>>) : [];
  }

  async createDedicatedVirtualAccount(params: {
    email: string;
    txRef: string;
    phoneNumber: string;
    firstName: string;
    lastName: string;
    bvn?: string;
    narration: string;
  }): Promise<Record<string, unknown>> {
    const body: Record<string, unknown> = {
      email: params.email,
      is_permanent: Boolean(params.bvn),
      tx_ref: params.txRef,
      phonenumber: params.phoneNumber,
      firstname: params.firstName,
      lastname: params.lastName,
      narration: params.narration,
    };
    if (params.bvn) body.bvn = params.bvn;

    const { ok, json } = await this.requestJson(
      "/virtual-account-numbers",
      "POST",
      "POST /v3/virtual-account-numbers",
      params.txRef,
      body
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Flutterwave virtual account creation failed"));
    }
    return json;
  }

  async initiateStandardPayment(params: {
    txRef: string;
    amountNaira: number;
    email: string;
    phoneNumber: string;
    customerName: string;
  }): Promise<Record<string, unknown>> {
    const { ok, json } = await this.requestJson(
      "/payments",
      "POST",
      "POST /v3/payments",
      params.txRef,
      {
        tx_ref: params.txRef,
        amount: params.amountNaira,
        currency: "NGN",
        redirect_url: CONFIG.flutterwaveRedirectUrl || `${CONFIG.callbackBaseUrl}/payment-complete`,
        customer: {
          email: params.email,
          phonenumber: params.phoneNumber,
          name: params.customerName,
        },
        customizations: {
          title: "M-Sageer Pay Wallet Funding",
          description: "Naira Wallet Top-Up — M-SAGEER DIGITAL TECHNOLOGIES LTD",
        },
      }
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Unable to initiate Flutterwave checkout"));
    }
    return json;
  }

  async verifyTransactionById(transactionId: string): Promise<Record<string, unknown>> {
    const { json } = await this.requestJson(
      `/transactions/${encodeURIComponent(transactionId)}/verify`,
      "GET",
      "GET /v3/transactions/:id/verify",
      transactionId
    );
    return json;
  }

  async verifyTransactionByReference(txRef: string): Promise<Record<string, unknown>> {
    const { json } = await this.requestJson(
      `/transactions/verify_by_reference?tx_ref=${encodeURIComponent(txRef)}`,
      "GET",
      "GET /v3/transactions/verify_by_reference",
      txRef
    );
    return json;
  }

  private cachedNgBanks: { expiresAt: number; banks: Array<{ code: string; name: string }> } | null = null;

  async listNigerianBanks(): Promise<Array<{ code: string; name: string }>> {
    const now = Date.now();
    if (this.cachedNgBanks && this.cachedNgBanks.expiresAt > now) {
      return this.cachedNgBanks.banks;
    }
    const { ok, json } = await this.requestJson(
      "/banks/NG",
      "GET",
      "GET /v3/banks/NG",
      "BANKS_NG"
    );
    if (!ok || json.status !== "success" || !Array.isArray(json.data)) {
      throw new Error(String(json.message || "Unable to fetch Nigerian banks from Flutterwave"));
    }
    const banks = (json.data as Array<Record<string, unknown>>)
      .map((item) => ({
        code: String(item.code || "").trim(),
        name: String(item.name || "").trim(),
      }))
      .filter((b) => b.code.length > 0 && b.name.length > 0)
      .sort((a, b) => a.name.localeCompare(b.name));

    this.cachedNgBanks = {
      expiresAt: now + 24 * 60 * 60 * 1000,
      banks,
    };
    return banks;
  }

  async resolveBankAccount(params: {
    accountNumber: string;
    accountBank: string;
  }): Promise<{ accountNumber: string; accountName: string }> {
    const { ok, json } = await this.requestJson(
      "/accounts/resolve",
      "POST",
      "POST /v3/accounts/resolve",
      `${params.accountBank}_${params.accountNumber.slice(-4)}`,
      {
        account_number: params.accountNumber,
        account_bank: params.accountBank,
      }
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Unable to verify Nigerian bank account details"));
    }
    const data = (json.data || {}) as Record<string, unknown>;
    const accountName = String(data.account_name || "").trim();
    if (!accountName) {
      throw new Error("Bank account resolved without an account holder name.");
    }
    return {
      accountNumber: String(data.account_number || params.accountNumber).trim(),
      accountName,
    };
  }

  async initiateBankTransfer(params: {
    accountBank: string;
    accountNumber: string;
    amountNaira: number;
    narration: string;
    reference: string;
    beneficiaryName?: string;
  }): Promise<{
    id: number;
    status: string;
    reference: string;
    completeMessage: string;
    feeNaira: number;
    amountNaira: number;
    bankName: string;
    fullName: string;
    raw: Record<string, unknown>;
  }> {
    const body: Record<string, unknown> = {
      account_bank: params.accountBank,
      account_number: params.accountNumber,
      amount: params.amountNaira,
      currency: "NGN",
      debit_currency: "NGN",
      narration: params.narration,
      reference: params.reference,
    };
    if (params.beneficiaryName) {
      body.beneficiary_name = params.beneficiaryName;
    }

    const { ok, json } = await this.requestJson(
      "/transfers",
      "POST",
      "POST /v3/transfers",
      params.reference,
      body
    );
    if (!ok || json.status !== "success") {
      throw new Error(
        String(
          json.message ||
            "Flutterwave live transfer could not be initiated. Ensure Transfers are enabled in your Flutterwave Dashboard Settings."
        )
      );
    }
    const data = (json.data || {}) as Record<string, unknown>;
    return {
      id: Number(data.id ?? 0),
      status: String(data.status || "NEW").toUpperCase(),
      reference: String(data.reference || params.reference),
      completeMessage: String(data.complete_message || json.message || "Transfer queued with Flutterwave"),
      feeNaira: Number(data.fee ?? 0),
      amountNaira: Number(data.amount ?? params.amountNaira),
      bankName: String(data.bank_name || ""),
      fullName: String(data.full_name || params.beneficiaryName || ""),
      raw: json,
    };
  }

  async getTransferById(transferId: string): Promise<{
    id: number;
    status: string;
    reference: string;
    completeMessage: string;
    feeNaira: number;
    amountNaira: number;
    bankName: string;
    fullName: string;
    raw: Record<string, unknown>;
  }> {
    const { ok, json } = await this.requestJson(
      `/transfers/${encodeURIComponent(transferId)}`,
      "GET",
      "GET /v3/transfers/:id",
      transferId
    );
    if (!ok || json.status !== "success") {
      throw new Error(String(json.message || "Unable to fetch Flutterwave transfer status"));
    }
    const data = (json.data || {}) as Record<string, unknown>;
    return {
      id: Number(data.id ?? transferId),
      status: String(data.status || "PENDING").toUpperCase(),
      reference: String(data.reference || ""),
      completeMessage: String(data.complete_message || json.message || ""),
      feeNaira: Number(data.fee ?? 0),
      amountNaira: Number(data.amount ?? 0),
      bankName: String(data.bank_name || ""),
      fullName: String(data.full_name || ""),
      raw: json,
    };
  }
}
