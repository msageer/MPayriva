import { CONFIG, logProviderEvent } from "./config";

/**
 * Trusted server-side AlrahuzData API client
 * Official documentation: https://alrahuzdata.com.ng/documentation/
 *
 * Authentication: Authorization: Token <ALRAHUZDATA_API_TOKEN>
 * Never hardcodes products or prices — dynamically fetches live user profile,
 * wallet balance, and full available service catalog from GET /api/user/.
 */

const ALRAHUZ_NETWORK_ID_MAP: Record<string, number> = {
  "01": 1,
  "1": 1,
  MTN: 1,
  "02": 2,
  "2": 2,
  GLO: 2,
  GLOBACOM: 2,
  "03": 3,
  "3": 3,
  "9MOBILE": 3,
  ETISALAT: 3,
  "04": 4,
  "4": 4,
  AIRTEL: 4,
};

const ALRAHUZ_CABLE_ID_MAP: Record<string, number> = {
  GOTV: 1,
  "1": 1,
  DSTV: 2,
  "2": 2,
  STARTIMES: 3,
  "3": 3,
  SHOWMAX: 4,
  "4": 4,
};

// Standard MSORG / AlrahuzData Nigerian DisCo numeric IDs mapped from ClubKonnect 2-digit codes & names
const ALRAHUZ_DISCO_ID_MAP: Record<string, number> = {
  "01": 2, // Eko Electric (EKEDC)
  EKEDC: 2,
  "02": 1, // Ikeja Electric (IKEDC)
  IKEDC: 1,
  "03": 3, // Abuja Electric (AEDC)
  AEDC: 3,
  "04": 4, // Kano Electric (KEDCO)
  KEDCO: 4,
  "05": 6, // Port Harcourt Electric (PHED)
  PHED: 6,
  "06": 9, // Jos Electric (JED)
  JED: 9,
  "07": 7, // Ibadan Electric (IBEDC)
  IBEDC: 7,
  "08": 8, // Kaduna Electric (KAEDCO)
  KAEDCO: 8,
  "09": 5, // Enugu Electric (EEDC)
  EEDC: 5,
  "10": 10, // Benin Electric (BEDC)
  BEDC: 10,
  "11": 11, // Yola Electric (YEDC)
  YEDC: 11,
};

export function mapToAlrahuzNetworkId(rawCodeOrName: string): number {
  const upper = rawCodeOrName.trim().toUpperCase();
  if (ALRAHUZ_NETWORK_ID_MAP[upper]) return ALRAHUZ_NETWORK_ID_MAP[upper];
  for (const [k, id] of Object.entries(ALRAHUZ_NETWORK_ID_MAP)) {
    if (upper.includes(k)) return id;
  }
  const num = Number(upper);
  return Number.isInteger(num) && num >= 1 && num <= 10 ? num : 1;
}

export function mapToAlrahuzCableId(rawCodeOrName: string): number {
  const upper = rawCodeOrName.trim().toUpperCase();
  if (ALRAHUZ_CABLE_ID_MAP[upper]) return ALRAHUZ_CABLE_ID_MAP[upper];
  for (const [k, id] of Object.entries(ALRAHUZ_CABLE_ID_MAP)) {
    if (upper.includes(k)) return id;
  }
  const num = Number(upper);
  return Number.isInteger(num) && num >= 1 && num <= 10 ? num : 2;
}

export function mapToAlrahuzDiscoId(rawCodeOrName: string): number {
  const upper = rawCodeOrName.trim().toUpperCase();
  if (ALRAHUZ_DISCO_ID_MAP[upper]) return ALRAHUZ_DISCO_ID_MAP[upper];
  for (const [k, id] of Object.entries(ALRAHUZ_DISCO_ID_MAP)) {
    if (upper.includes(k)) return id;
  }
  const num = Number(upper);
  return Number.isInteger(num) && num >= 1 && num <= 20 ? num : 1;
}

export class AlrahuzDataClient {
  get isConfigured(): boolean {
    return Boolean(CONFIG.alrahuzDataToken.trim());
  }

  private get baseUrl(): string {
    return CONFIG.alrahuzDataBaseUrl || "https://alrahuzdata.com.ng/api";
  }

  private ensureConfigured(): void {
    if (!this.isConfigured) {
      throw new Error("AlrahuzData API token is not configured (set ALRAHUZDATA_API_TOKEN on backend).");
    }
  }

  private async request(
    method: "GET" | "POST",
    path: string,
    body: Record<string, unknown> | undefined,
    action: string,
    reference: string,
    silentLog = false
  ): Promise<Record<string, unknown>> {
    this.ensureConfigured();
    const url = `${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);

    try {
      const response = await fetch(url, {
        method,
        headers: {
          Authorization: `Token ${CONFIG.alrahuzDataToken.trim()}`,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: method === "POST" ? JSON.stringify(body || {}) : undefined,
        signal: controller.signal,
      });

      const rawText = await response.text();
      let parsed: Record<string, unknown>;
      try {
        const json = JSON.parse(rawText);
        parsed = json && typeof json === "object" && !Array.isArray(json)
          ? (json as Record<string, unknown>)
          : { data: json };
      } catch {
        parsed = { raw: rawText.slice(0, 400), status: response.ok ? "ok" : "error" };
      }

      if (!silentLog) {
        const statusSummary = String(
          parsed.Status || parsed.status || parsed.error || parsed.detail || (response.ok ? "OK" : "ERROR")
        );
        const upperSummary = statusSummary.toUpperCase();
        const mappedOutcome: "SUCCESS" | "PENDING" | "ERROR" = !response.ok
          ? "ERROR"
          : upperSummary.includes("SUCCESS")
            ? "SUCCESS"
            : upperSummary.includes("FAIL") || upperSummary.includes("ERROR")
              ? "ERROR"
              : "PENDING";
        await logProviderEvent({
          provider: "ALRAHUZDATA",
          action,
          reference,
          statusCode: String(response.status),
          outcome: mappedOutcome,
          message: String(
            parsed.api_response || parsed.message || parsed.error || parsed.detail || statusSummary
          ).slice(0, 300),
        });
      }

      if (!response.ok) {
        // Return structured error payload so purchase classifiers can distinguish explicit 400 declines vs 5xx timeouts
        return {
          ...parsed,
          __httpStatus: response.status,
          error:
            parsed.error ||
            parsed.detail ||
            parsed.message ||
            `AlrahuzData HTTP ${response.status}`,
        };
      }

      return parsed;
    } catch (err) {
      if (!silentLog) {
        await logProviderEvent({
          provider: "ALRAHUZDATA",
          action,
          reference,
          statusCode: "NETWORK_ERROR",
          outcome: "ERROR",
          message: (err as Error).message.slice(0, 300),
        });
      }
      throw err;
    } finally {
      clearTimeout(timeout);
    }
  }

  /**
   * Fetches live AlrahuzData user profile, wallet balance, and full dynamic
   * product catalog (Dataplans, Cableplan, topup percentages, Exam PINs, Recharge Cards).
   */
  async fetchUserAndCatalog(): Promise<Record<string, unknown>> {
    return this.request("GET", "/user/", undefined, "GET /api/user/", "CATALOG_SYNC", true);
  }

  async getWalletBalance(): Promise<{ balanceNaira: number; raw: Record<string, unknown> }> {
    const data = await this.request("GET", "/user/", undefined, "GET /api/user/ (balance)", "HEALTH_CHECK", true);
    if (Number(data.__httpStatus || 200) >= 400) {
      throw new Error(String(data.error || "Unable to query AlrahuzData wallet balance."));
    }
    const userObj = (data.user || data) as Record<string, unknown>;
    const rawBal = String(
      userObj.wallet_balance ?? userObj.Account_Balance ?? userObj.balance ?? "0"
    ).replace(/[^0-9.]/g, "");
    const balanceNaira = Number(rawBal);
    return {
      balanceNaira: Number.isFinite(balanceNaira) ? balanceNaira : 0,
      raw: data,
    };
  }

  async buyAirtime(params: {
    mobileNetwork: string;
    amountNaira: number;
    mobileNumber: string;
    requestId: string;
  }) {
    return this.request(
      "POST",
      "/topup/",
      {
        network: mapToAlrahuzNetworkId(params.mobileNetwork),
        amount: Math.round(params.amountNaira),
        mobile_number: params.mobileNumber,
        Ported_number: true,
        airtime_type: "VTU",
      },
      "POST /api/topup/",
      params.requestId
    );
  }

  async buyData(params: {
    mobileNetwork: string;
    planId: string;
    mobileNumber: string;
    requestId: string;
  }) {
    const numericPlan = Number(params.planId);
    return this.request(
      "POST",
      "/data/",
      {
        network: mapToAlrahuzNetworkId(params.mobileNetwork),
        mobile_number: params.mobileNumber,
        plan: Number.isFinite(numericPlan) && numericPlan > 0 ? numericPlan : params.planId,
        Ported_number: true,
      },
      "POST /api/data/",
      params.requestId
    );
  }

  async verifyCableSmartCard(params: { cableTv: string; smartCardNo: string }) {
    const cableName = params.cableTv.trim().toUpperCase();
    const qs = new URLSearchParams({
      smart_card_number: params.smartCardNo,
      cablename: cableName,
    });
    return this.request("GET", `/validateiuc?${qs.toString()}`, undefined, "GET /api/validateiuc", params.smartCardNo, true);
  }

  async payCableTv(params: {
    cableTv: string;
    packageCode: string;
    smartCardNo: string;
    requestId: string;
  }) {
    const numericPlan = Number(params.packageCode);
    return this.request(
      "POST",
      "/cablesub/",
      {
        cablename: mapToAlrahuzCableId(params.cableTv),
        cableplan: Number.isFinite(numericPlan) && numericPlan > 0 ? numericPlan : params.packageCode,
        smart_card_number: params.smartCardNo,
      },
      "POST /api/cablesub/",
      params.requestId
    );
  }

  async verifyElectricityMeter(params: {
    electricCompany: string;
    meterNo: string;
    meterType: string;
  }) {
    const mtype = params.meterType.toLowerCase().includes("post") ? "POSTPAID" : "PREPAID";
    const qs = new URLSearchParams({
      meternumber: params.meterNo,
      disconame: String(mapToAlrahuzDiscoId(params.electricCompany)),
      mtype,
    });
    return this.request("GET", `/validatemeter?${qs.toString()}`, undefined, "GET /api/validatemeter", params.meterNo, true);
  }

  async payElectricity(params: {
    electricCompany: string;
    meterNo: string;
    meterType: string;
    amountNaira: number;
    requestId: string;
  }) {
    const meterTypeNum = params.meterType.toLowerCase().includes("post") ? 2 : 1;
    return this.request(
      "POST",
      "/billpayment/",
      {
        disco_name: mapToAlrahuzDiscoId(params.electricCompany),
        amount: Math.round(params.amountNaira),
        meter_number: params.meterNo,
        MeterType: meterTypeNum,
      },
      "POST /api/billpayment/",
      params.requestId
    );
  }

  async buyExamPin(params: { examName: string; quantity: number; requestId: string }) {
    const cleanExam = params.examName.toUpperCase().includes("NECO")
      ? "NECO"
      : params.examName.toUpperCase().includes("NABTEB")
        ? "NABTEB"
        : params.examName.toUpperCase().includes("JAMB")
          ? "JAMB"
          : "WAEC";
    return this.request(
      "POST",
      "/epin/",
      {
        exam_name: cleanExam,
        quantity: Math.max(1, Math.min(20, Math.floor(params.quantity || 1))),
      },
      "POST /api/epin/",
      params.requestId
    );
  }

  async buyRechargeCard(params: {
    mobileNetwork: string;
    denominationNaira: number;
    quantity: number;
    nameOnCard?: string;
    requestId: string;
  }) {
    return this.request(
      "POST",
      "/rechargepin/",
      {
        network: mapToAlrahuzNetworkId(params.mobileNetwork),
        network_amount: Math.round(params.denominationNaira),
        quantity: Math.max(1, Math.min(50, Math.floor(params.quantity || 1))),
        name_on_card: params.nameOnCard || "M-Sageer Pay",
      },
      "POST /api/rechargepin/",
      params.requestId
    );
  }

  async queryTransactionStatus(serviceType: string, orderId: string, reference: string) {
    const svc = serviceType.toUpperCase();
    const endpoint =
      svc === "AIRTIME"
        ? `/topup/${encodeURIComponent(orderId)}`
        : svc === "DATA"
          ? `/data/${encodeURIComponent(orderId)}`
          : svc === "CABLE_TV"
            ? `/cablesub/${encodeURIComponent(orderId)}`
            : svc === "ELECTRICITY"
              ? `/billpayment/${encodeURIComponent(orderId)}`
              : svc === "RECHARGE_CARD"
                ? `/rechargepin/${encodeURIComponent(orderId)}`
                : `/epin/${encodeURIComponent(orderId)}`;
    return this.request("GET", endpoint, undefined, `GET /api${endpoint}`, reference);
  }
}
