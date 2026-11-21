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
}
