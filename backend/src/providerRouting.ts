export type ApiProviderId = "CLUBKONNECT" | "ALRAHUZDATA";
export type ProviderRoutingMode = "AUTO_CHEAPEST" | ApiProviderId;

export type RoutingServiceType =
  | "AIRTIME"
  | "DATA"
  | "ELECTRICITY"
  | "CABLE_TV"
  | "RECHARGE_CARD"
  | "DATA_CARD"
  | "WAEC_PIN"
  | "JAMB_PIN"
  | "SMILE"
  | "SPECTRANET"
  | "BETTING";

export interface ProviderToggleState {
  id: ApiProviderId;
  name: string;
  enabled: boolean;
}

export interface ProviderControlsConfig {
  providers: Record<ApiProviderId, ProviderToggleState>;
  serviceRouting: Record<RoutingServiceType, ProviderRoutingMode>;
  updatedBy?: string;
  updatedAtIso?: string;
}

export const ALL_API_PROVIDERS: ApiProviderId[] = ["CLUBKONNECT", "ALRAHUZDATA"];

export const PROVIDER_DISPLAY_NAMES: Record<ApiProviderId, string> = {
  CLUBKONNECT: "ClubKonnect",
  ALRAHUZDATA: "AlrahuzData",
};

export const ALL_ROUTING_SERVICE_TYPES: RoutingServiceType[] = [
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

export const DEFAULT_PROVIDER_CONTROLS: ProviderControlsConfig = {
  providers: {
    CLUBKONNECT: { id: "CLUBKONNECT", name: "ClubKonnect", enabled: true },
    ALRAHUZDATA: { id: "ALRAHUZDATA", name: "AlrahuzData", enabled: true },
  },
  serviceRouting: {
    AIRTIME: "AUTO_CHEAPEST",
    DATA: "AUTO_CHEAPEST",
    ELECTRICITY: "AUTO_CHEAPEST",
    CABLE_TV: "AUTO_CHEAPEST",
    RECHARGE_CARD: "AUTO_CHEAPEST",
    DATA_CARD: "CLUBKONNECT",
    WAEC_PIN: "AUTO_CHEAPEST",
    JAMB_PIN: "AUTO_CHEAPEST",
    SMILE: "CLUBKONNECT",
    SPECTRANET: "CLUBKONNECT",
    BETTING: "CLUBKONNECT",
  },
};

export function sanitizeProviderControls(
  raw: Record<string, unknown> | null | undefined
): ProviderControlsConfig {
  const rawProviders = (raw?.providers || {}) as Record<string, Record<string, unknown>>;
  const rawRouting = (raw?.serviceRouting || {}) as Record<string, unknown>;

  const providers: Record<ApiProviderId, ProviderToggleState> = {
    CLUBKONNECT: {
      id: "CLUBKONNECT",
      name: PROVIDER_DISPLAY_NAMES.CLUBKONNECT,
      enabled:
        typeof rawProviders?.CLUBKONNECT?.enabled === "boolean"
          ? (rawProviders.CLUBKONNECT.enabled as boolean)
          : DEFAULT_PROVIDER_CONTROLS.providers.CLUBKONNECT.enabled,
    },
    ALRAHUZDATA: {
      id: "ALRAHUZDATA",
      name: PROVIDER_DISPLAY_NAMES.ALRAHUZDATA,
      enabled:
        typeof rawProviders?.ALRAHUZDATA?.enabled === "boolean"
          ? (rawProviders.ALRAHUZDATA.enabled as boolean)
          : DEFAULT_PROVIDER_CONTROLS.providers.ALRAHUZDATA.enabled,
    },
  };

  const serviceRouting = {} as Record<RoutingServiceType, ProviderRoutingMode>;
  for (const svc of ALL_ROUTING_SERVICE_TYPES) {
    const candidate = String(rawRouting?.[svc] || "").toUpperCase().trim();
    if (candidate === "CLUBKONNECT" || candidate === "ALRAHUZDATA" || candidate === "AUTO_CHEAPEST") {
      serviceRouting[svc] = candidate;
    } else {
      serviceRouting[svc] = DEFAULT_PROVIDER_CONTROLS.serviceRouting[svc];
    }
  }

  return {
    providers,
    serviceRouting,
    updatedBy: typeof raw?.updatedBy === "string" ? raw.updatedBy : undefined,
    updatedAtIso: typeof raw?.updatedAtIso === "string" ? raw.updatedAtIso : undefined,
  };
}

export function isProviderEnabled(controls: ProviderControlsConfig, providerId: ApiProviderId): boolean {
  return controls.providers[providerId]?.enabled === true;
}

/**
 * Resolves which enabled API provider should execute a service.
 * STRICT PROTECTION: Disabled providers are NEVER returned under any routing mode.
 */
export function selectActiveProviderForService(params: {
  controls: ProviderControlsConfig;
  serviceType: string;
  configuredProviders: ApiProviderId[];
  requestedProvider?: ApiProviderId | null;
  costByProviderKobo?: Partial<Record<ApiProviderId, number>>;
}): ApiProviderId | null {
  const { controls, serviceType, configuredProviders, requestedProvider, costByProviderKobo } = params;
  const enabledAndConfigured = configuredProviders.filter((p) => isProviderEnabled(controls, p));
  if (enabledAndConfigured.length === 0) {
    return null;
  }

  // If a specific catalog plan belongs to a provider, strictly enforce that provider is enabled
  if (requestedProvider) {
    return enabledAndConfigured.includes(requestedProvider) ? requestedProvider : null;
  }

  const svcKey = serviceType.toUpperCase() as RoutingServiceType;
  const routeMode: ProviderRoutingMode = controls.serviceRouting[svcKey] || "AUTO_CHEAPEST";

  if (routeMode === "CLUBKONNECT" || routeMode === "ALRAHUZDATA") {
    if (enabledAndConfigured.includes(routeMode)) return routeMode;
    // Automatic safe failover to the other enabled & configured provider if preferred one is turned OFF
    return enabledAndConfigured[0];
  }

  // AUTO_CHEAPEST: pick lowest positive cost among enabled & configured providers
  if (costByProviderKobo) {
    let bestProvider: ApiProviderId | null = null;
    let lowestCost = Number.POSITIVE_INFINITY;
    for (const p of enabledAndConfigured) {
      const c = Number(costByProviderKobo[p] ?? 0);
      if (c > 0 && c < lowestCost) {
        lowestCost = c;
        bestProvider = p;
      }
    }
    if (bestProvider) return bestProvider;
  }

  return enabledAndConfigured[0];
}
