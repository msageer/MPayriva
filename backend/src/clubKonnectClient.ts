import { CONFIG, logProviderEvent } from "./config";

/**
 * Official ClubKonnect (Nellobyte Systems) API Client.
 * Documentation: https://www.clubkonnect.com/apidocs.asp
 * Protocol: HTTPS GET returning JSON. All credentials remain strictly server-side.
 */
const BASE_URL = "https://www.nellobytesystems.com";
const REQUEST_TIMEOUT_MS = 25_000;

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
    requireAuth = true
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

    await logProviderEvent({
      provider: "CLUBKONNECT",
      action: endpoint,
      reference,
      statusCode: String(res.status),
      outcome: res.ok ? "SUCCESS" : "ERROR",
      message: String(parsed.status || parsed.remark || parsed.message || res.statusText).slice(0, 300),
    });

    if (!res.ok) {
      throw new Error(`ClubKonnect HTTP ${res.status}`);
    }
    return parsed;
  }

  async getWalletBalance(): Promise<Record<string, unknown>> {
    return this.getJson("APIWalletBalanceV1.asp", {}, "HEALTH_CHECK");
  }

  // --- LIVE CATALOG ENDPOINTS (ClubKonnect v2 JSON Feeds) ---

  async fetchDataPlans(): Promise<Record<string, unknown>> {
    return this.getJson("APIDatabundlePlansV2.asp", {}, "CATALOG_SYNC_DATA", true);
  }

  async fetchCablePackages(): Promise<Record<string, unknown>> {
    return this.getJson("APICableTVPackagesV2.asp", {}, "CATALOG_SYNC_CABLE", true);
  }

  async fetchWaecPackages(): Promise<Record<string, unknown>> {
    return this.getJson("APIWAECPackagesV2.asp", {}, "CATALOG_SYNC_WAEC", true);
  }

  async fetchJambPackages(): Promise<Record<string, unknown>> {
    return this.getJson("APIJAMBPackagesV2.asp", {}, "CATALOG_SYNC_JAMB", true);
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
        MobileNetwork: params.mobileNetwork,
        Amount: String(params.amountNaira),
        MobileNumber: params.mobileNumber,
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
        MobileNetwork: params.mobileNetwork,
        DataPlan: params.dataPlan,
        MobileNumber: params.mobileNumber,
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

  /**
   * Requery existing transaction status — NEVER blindly resubmit a timed-out order.
   */
  async queryTransactionStatus(requestId: string) {
    return this.getJson("APIQueryV1.asp", { RequestID: requestId }, requestId);
  }
}
