import { CONFIG, logProviderEvent } from "./config";

/**
 * Official ClubKonnect (Nellobyte Systems) API Client.
 * Documentation: https://www.clubkonnect.com/apidocs.asp
 * Protocol: HTTPS GET returning JSON. All credentials remain strictly server-side.
 */
const BASE_URL = "https://www.nellobytesystems.com";
const REQUEST_TIMEOUT_MS = 25_000;

function normalizeNgPhone(raw: string): string {
  const digits = String(raw || "").replace(/[^0-9]/g, "");
  if (digits.startsWith("234") && digits.length === 13) {
    return `0${digits.slice(3)}`;
  }
  return digits || String(raw || "").trim();
}

function summarizeProviderResponse(parsed: Record<string, unknown>, fallbackStatusText: string): string {
  const parts: string[] = [];
  if (parsed.statuscode !== undefined && parsed.statuscode !== "") parts.push(`code=${parsed.statuscode}`);
  if (parsed.status) parts.push(`status=${parsed.status}`);
  if (parsed.orderstatus && parsed.orderstatus !== parsed.status) parts.push(`orderstatus=${parsed.orderstatus}`);
  if (parsed.orderid) parts.push(`orderid=${parsed.orderid}`);
  if (parsed.remark) parts.push(`remark=${parsed.remark}`);
  if (parsed.message && parsed.message !== parsed.remark) parts.push(`msg=${parsed.message}`);
  if (parsed.raw) parts.push(`raw=${String(parsed.raw).slice(0, 140)}`);
  return (parts.join(" | ") || fallbackStatusText).slice(0, 350);
}

export class ClubKonnectClient {
  get isConfigured(): boolean {
    return Boolean(CONFIG.clubKonnectUserId && CONFIG.clubKonnectApiKey);
  }

  private assertConfigured() {
    if (!this.isConfigured) {
      throw new Error(
        "ClubKonnect API credentials (CLUBKONNECT_USER_ID, CLUBKONNECT_API_KEY) are not configured on the backend."
      );
    }
  }

  private async getJson(
    endpoint: string,
    params: Record<string, string>,
    reference: string,
    requireAuth = true,
    silentFailure = false
  ): Promise<Record<string, unknown>> {
    if (requireAuth) this.assertConfigured();
    const url = new URL(`${BASE_URL}/${endpoint}`);
    if (requireAuth) {
      url.searchParams.set("UserID", CONFIG.clubKonnectUserId);
      url.searchParams.set("APIKey", CONFIG.clubKonnectApiKey);
    }
    for (const [k, v] of Object.entries(params)) {
      if (v) url.searchParams.set(k, v);
    }

    const res = await fetch(url.toString(), {
      method: "GET",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      headers: { Accept: "application/json" },
    });
    const text = await res.text();
    let parsed: Record<string, unknown> = {};
    try {
      parsed = JSON.parse(text) as Record<string, unknown>;
    } catch {
      parsed = { raw: text.slice(0, 400) };
    }

    if (!silentFailure) {
      await logProviderEvent({
        provider: "CLUBKONNECT",
        action: endpoint,
        reference,
        statusCode: String(parsed.statuscode || res.status),
        outcome: res.ok ? "SUCCESS" : "ERROR",
        message: summarizeProviderResponse(parsed, res.statusText),
      });
    }

    if (!res.ok) {
      throw new Error(`ClubKonnect HTTP ${res.status}`);
    }
    return parsed;
  }

  async getWalletBalance(): Promise<Record<string, unknown>> {
    return this.getJson("APIWalletBalanceV1.asp", {}, "HEALTH_CHECK");
  }

  // --- LIVE CATALOG & AVAILABILITY ENDPOINTS (ClubKonnect v2 JSON Feeds) ---

  async fetchAirtimeDiscounts(): Promise<Record<string, unknown>> {
    return this.getJson("APIAirtimeDiscountV2.asp", {}, "CATALOG_AIRTIME", true, true);
  }

  async fetchDataPlans(): Promise<Record<string, unknown>> {
    return this.getJson("APIDatabundlePlansV2.asp", {}, "CATALOG_DATA", true, true);
  }

  async fetchElectricityDiscos(): Promise<Record<string, unknown>> {
    return this.getJson("APIElectricityDiscosV2.asp", {}, "CATALOG_ELECTRICITY", true, true);
  }

  async fetchCablePackages(): Promise<Record<string, unknown>> {
    return this.getJson("APICableTVPackagesV2.asp", {}, "CATALOG_CABLE", true, true);
  }

  async fetchWaecPackages(): Promise<Record<string, unknown>> {
    return this.getJson("APIWAECPackagesV2.asp", {}, "CATALOG_WAEC", true, true);
  }

  async fetchJambPackages(): Promise<Record<string, unknown>> {
    return this.getJson("APIJAMBPackagesV2.asp", {}, "CATALOG_JAMB", true, true);
  }

  async fetchEpinDiscounts(): Promise<Record<string, unknown>> {
    return this.getJson("APIEPINDiscountV2.asp", {}, "CATALOG_EPIN", true, true);
  }

  async fetchDataCardPlans(): Promise<Record<string, unknown>> {
    return this.getJson("APIDataCardPlansV2.asp", {}, "CATALOG_DATACARD", true, true);
  }

  async fetchSmilePackages(): Promise<Record<string, unknown>> {
    return this.getJson("APISmilePackagesV2.asp", {}, "CATALOG_SMILE", true, true);
  }

  async fetchSpectranetPackages(): Promise<Record<string, unknown>> {
    return this.getJson("APISpectranetPackagesV2.asp", {}, "CATALOG_SPECTRANET", true, true);
  }

  async fetchBettingCompanies(): Promise<Record<string, unknown>> {
    return this.getJson("APIBettingCompaniesV2.asp", {}, "CATALOG_BETTING", true, true);
  }

  // --- UTILITY FULFILLMENT ENDPOINTS ---

  async buyAirtime(params: {
    mobileNetwork: string;
    amountNaira: number;
    mobileNumber: string;
    requestId: string;
  }) {
    return this.getJson(
      "APIAirtimeV1.asp",
      {
        MobileNetwork: params.mobileNetwork.trim(),
        Amount: String(Math.round(params.amountNaira)),
        MobileNumber: normalizeNgPhone(params.mobileNumber),
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buyData(params: {
    mobileNetwork: string;
    dataPlan: string;
    mobileNumber: string;
    requestId: string;
  }) {
    return this.getJson(
      "APIDatabundleV1.asp",
      {
        MobileNetwork: params.mobileNetwork.trim(),
        DataPlan: params.dataPlan.trim(),
        MobileNumber: normalizeNgPhone(params.mobileNumber),
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async verifyElectricityMeter(params: {
    electricCompany: string;
    meterNo: string;
    meterType: string;
  }) {
    const meterTypeCode = params.meterType.toLowerCase() === "postpaid" ? "02" : "01";
    return this.getJson(
      "APIVerifyElectricityV1.asp",
      {
        ElectricCompany: params.electricCompany,
        meterno: params.meterNo,
        MeterType: meterTypeCode,
      },
      `VERIFY_${params.meterNo}`
    );
  }

  async payElectricity(params: {
    electricCompany: string;
    meterNo: string;
    meterType: string;
    amountNaira: number;
    phoneNo: string;
    requestId: string;
  }) {
    const meterTypeCode = params.meterType.toLowerCase() === "postpaid" ? "02" : "01";
    return this.getJson(
      "APIElectricityV1.asp",
      {
        ElectricCompany: params.electricCompany,
        MeterType: meterTypeCode,
        MeterNo: params.meterNo,
        Amount: String(params.amountNaira),
        PhoneNo: params.phoneNo,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async verifyCableSmartCard(params: { cableTv: string; smartCardNo: string }) {
    return this.getJson(
      "APIVerifyCableTVV1.asp",
      {
        CableTV: params.cableTv,
        SmartCardNo: params.smartCardNo,
      },
      `VERIFY_${params.smartCardNo}`
    );
  }

  async payCableTv(params: {
    cableTv: string;
    packageCode: string;
    smartCardNo: string;
    phoneNo: string;
    requestId: string;
  }) {
    return this.getJson(
      "APICableTVV1.asp",
      {
        CableTV: params.cableTv,
        Package: params.packageCode,
        SmartCardNo: params.smartCardNo,
        PhoneNo: params.phoneNo,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buyWaecPin(params: { examType: string; phoneNo: string; requestId: string }) {
    return this.getJson(
      "APIWAECV1.asp",
      {
        ExamType: params.examType,
        PhoneNo: params.phoneNo,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buyJambPin(params: { examType: string; profileId: string; phoneNo: string; requestId: string }) {
    return this.getJson(
      "APIJAMBV1.asp",
      {
        ExamType: params.examType,
        ProfileID: params.profileId,
        PhoneNo: params.phoneNo,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buyRechargeEpin(params: {
    mobileNetwork: string;
    valueNaira: number;
    quantity: number;
    requestId: string;
  }) {
    return this.getJson(
      "APIEPINV1.asp",
      {
        MobileNetwork: params.mobileNetwork,
        Value: String(params.valueNaira),
        Quantity: String(params.quantity),
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buyDataCardEpin(params: {
    mobileNetwork: string;
    dataPlan: string;
    quantity: number;
    requestId: string;
  }) {
    return this.getJson(
      "APIDataCardV1.asp",
      {
        MobileNetwork: params.mobileNetwork,
        DataPlan: params.dataPlan,
        Quantity: String(params.quantity),
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async verifySmileAccount(params: { mobileNetwork: string; mobileNumber: string }) {
    return this.getJson(
      "APIVerifySmileV1.asp",
      {
        MobileNetwork: params.mobileNetwork || "smile-direct",
        MobileNumber: params.mobileNumber,
      },
      `VERIFY_SMILE_${params.mobileNumber}`
    );
  }

  async buySmileData(params: {
    mobileNetwork: string;
    dataPlan: string;
    mobileNumber: string;
    requestId: string;
  }) {
    return this.getJson(
      "APISmileV1.asp",
      {
        MobileNetwork: params.mobileNetwork || "smile-direct",
        DataPlan: params.dataPlan,
        MobileNumber: params.mobileNumber,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async buySpectranetPin(params: {
    mobileNetwork: string;
    dataPlan: string;
    mobileNumber: string;
    requestId: string;
  }) {
    return this.getJson(
      "APISpectranetV1.asp",
      {
        MobileNetwork: params.mobileNetwork || "spectranet",
        DataPlan: params.dataPlan,
        MobileNumber: params.mobileNumber,
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  async verifyBettingCustomer(params: { bettingCompany: string; customerId: string }) {
    return this.getJson(
      "APIVerifyBettingV1.asp",
      {
        BettingCompany: params.bettingCompany,
        CustomerID: params.customerId,
      },
      `VERIFY_BET_${params.customerId}`
    );
  }

  async fundBettingWallet(params: {
    bettingCompany: string;
    customerId: string;
    amountNaira: number;
    requestId: string;
  }) {
    return this.getJson(
      "APIBettingV1.asp",
      {
        BettingCompany: params.bettingCompany,
        CustomerID: params.customerId,
        Amount: String(params.amountNaira),
        RequestID: params.requestId,
        CallBackURL: CONFIG.callbackBaseUrl ? `${CONFIG.callbackBaseUrl}/webhooks/clubkonnect` : "",
      },
      params.requestId
    );
  }

  /**
   * Requery existing transaction status — prefers ClubKonnect OrderID when available,
   * falling back to RequestID. NEVER blindly resubmits a timed-out order.
   */
  async queryTransactionStatus(params: { orderId?: string | null; requestId?: string | null }) {
    const cleanOrderId = String(params.orderId || "").trim();
    const cleanRequestId = String(params.requestId || "").trim();
    const queryParams: Record<string, string> = cleanOrderId
      ? { OrderID: cleanOrderId }
      : { RequestID: cleanRequestId };
    return this.getJson("APIQueryV1.asp", queryParams, cleanRequestId || cleanOrderId || "REQUERY");
  }
}
