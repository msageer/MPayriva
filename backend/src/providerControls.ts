import { FieldValue } from "firebase-admin/firestore";
import { db } from "./config";
import {
  ALL_API_PROVIDERS,
  ALL_ROUTING_SERVICE_TYPES,
  ApiProviderId,
  DEFAULT_PROVIDER_CONTROLS,
  PROVIDER_DISPLAY_NAMES,
  ProviderControlsConfig,
  ProviderRoutingMode,
  ProviderToggleState,
  isProviderEnabled,
  sanitizeProviderControls,
  selectActiveProviderForService,
} from "./providerRouting";

export {
  ALL_API_PROVIDERS,
  DEFAULT_PROVIDER_CONTROLS,
  PROVIDER_DISPLAY_NAMES,
  isProviderEnabled,
  sanitizeProviderControls,
  selectActiveProviderForService,
};
export type { ApiProviderId, ProviderControlsConfig, ProviderRoutingMode, ProviderToggleState };

export async function getProviderControls(): Promise<ProviderControlsConfig> {
  const snap = await db.collection("platformConfig").doc("providerControls").get();
  if (!snap.exists) {
    return sanitizeProviderControls(null);
  }
  return sanitizeProviderControls(snap.data() as Record<string, unknown>);
}

export async function saveProviderControls(
  patch: {
    providers?: Partial<Record<ApiProviderId, { enabled?: boolean }>>;
    serviceRouting?: Partial<Record<string, string>>;
  },
  updatedBy: string
): Promise<ProviderControlsConfig> {
  const current = await getProviderControls();
  const mergedProviders: Record<ApiProviderId, ProviderToggleState> = {
    CLUBKONNECT: {
      ...current.providers.CLUBKONNECT,
      enabled:
        typeof patch.providers?.CLUBKONNECT?.enabled === "boolean"
          ? patch.providers.CLUBKONNECT.enabled
          : current.providers.CLUBKONNECT.enabled,
    },
    ALRAHUZDATA: {
      ...current.providers.ALRAHUZDATA,
      enabled:
        typeof patch.providers?.ALRAHUZDATA?.enabled === "boolean"
          ? patch.providers.ALRAHUZDATA.enabled
          : current.providers.ALRAHUZDATA.enabled,
    },
  };

  const mergedRouting: Record<string, string> = { ...current.serviceRouting };
  if (patch.serviceRouting && typeof patch.serviceRouting === "object") {
    for (const [k, v] of Object.entries(patch.serviceRouting)) {
      const upperKey = k.toUpperCase().trim();
      const upperVal = String(v || "").toUpperCase().trim();
      if (
        (ALL_ROUTING_SERVICE_TYPES as string[]).includes(upperKey) &&
        (upperVal === "AUTO_CHEAPEST" || upperVal === "CLUBKONNECT" || upperVal === "ALRAHUZDATA")
      ) {
        mergedRouting[upperKey] = upperVal;
      }
    }
  }

  const next = sanitizeProviderControls({
    providers: mergedProviders,
    serviceRouting: mergedRouting,
    updatedBy,
    updatedAtIso: new Date().toISOString(),
  });

  await db
    .collection("platformConfig")
    .doc("providerControls")
    .set(
      {
        providers: next.providers,
        serviceRouting: next.serviceRouting,
        updatedBy,
        updatedAtIso: next.updatedAtIso,
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

  return next;
}
