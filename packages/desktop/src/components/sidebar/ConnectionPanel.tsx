import { Cpu, Link2, RotateCcw, Save } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import type { AppSettings } from "@/app/types";
import { useAppContext } from "@/app/context";
import Dropdown from "@/components/ui/Dropdown";
import { KeyManager } from "@/components/ui/KeyManager";
import { HTTPClient } from "@/lib/api";
import { PROVIDER_DEFINITIONS, getProviderDefinition, type ProviderStatusPayload } from "@/lib/providers";

export function ConnectionPanel() {
  const { state, updateSettings } = useAppContext();
  const apiClient = useMemo(() => new HTTPClient(state.settings.apiBaseUrl), [state.settings.apiBaseUrl]);
  const [statusMap, setStatusMap] = useState<Record<string, ProviderStatusPayload>>({});
  const [providerUrl, setProviderUrl] = useState("");
  const [providerUrlError, setProviderUrlError] = useState("");
  const [isLoadingConfig, setIsLoadingConfig] = useState(true);
  const [isSavingProviderUrl, setIsSavingProviderUrl] = useState(false);

  const selectedProviderInfo = getProviderDefinition(state.settings.provider);
  const selectedProviderStatus = statusMap[state.settings.provider];
  const currentSavedEndpoint = selectedProviderStatus?.endpoint?.trim() ?? "";
  const currentResolvedEndpoint = selectedProviderStatus?.resolved_endpoint?.trim()
    ?? selectedProviderInfo?.defaultEndpoint
    ?? "";
  const providerUrlChanged = providerUrl.trim() !== currentResolvedEndpoint;

  useEffect(() => {
    let cancelled = false;

    setIsLoadingConfig(true);
    setProviderUrlError("");

    apiClient.get<{ providers: ProviderStatusPayload[] }>("/api/keys/list")
      .then(({ data }) => {
        if (cancelled) {
          return;
        }

        const nextMap: Record<string, ProviderStatusPayload> = {};
        for (const provider of data.providers ?? []) {
          nextMap[provider.provider] = provider;
        }
        setStatusMap(nextMap);
      })
      .catch((error) => {
        if (!cancelled) {
          setProviderUrlError(error instanceof Error ? error.message : "Failed to load provider configuration");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoadingConfig(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [apiClient]);

  useEffect(() => {
    setProviderUrl(currentResolvedEndpoint);
    setProviderUrlError("");
  }, [currentResolvedEndpoint, state.settings.provider]);

  async function reloadProviderStatus() {
    const { data } = await apiClient.get<{ providers: ProviderStatusPayload[] }>("/api/keys/list");
    const nextMap: Record<string, ProviderStatusPayload> = {};
    for (const provider of data.providers ?? []) {
      nextMap[provider.provider] = provider;
    }
    setStatusMap(nextMap);
  }

  async function saveProviderUrlOverride() {
    const trimmedProviderUrl = providerUrl.trim();
    if (!trimmedProviderUrl) {
      setProviderUrlError("Provider URL cannot be empty");
      return;
    }

    setIsSavingProviderUrl(true);
    setProviderUrlError("");

    try {
      await apiClient.post("/api/keys/set", {
        provider: state.settings.provider,
        endpoint: trimmedProviderUrl,
      });
      await reloadProviderStatus();
    } catch (error) {
      setProviderUrlError(error instanceof Error ? error.message : "Failed to save provider URL");
    } finally {
      setIsSavingProviderUrl(false);
    }
  }

  async function resetProviderUrlOverride() {
    if (!currentSavedEndpoint) {
      setProviderUrl(selectedProviderInfo?.defaultEndpoint ?? "");
      return;
    }

    setIsSavingProviderUrl(true);
    setProviderUrlError("");

    try {
      await apiClient.delete(`/api/keys/delete/${state.settings.provider}/endpoint`);
      await reloadProviderStatus();
    } catch (error) {
      setProviderUrlError(error instanceof Error ? error.message : "Failed to reset provider URL");
    } finally {
      setIsSavingProviderUrl(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Connect</p>
        <h2 className="panel-title">Provider runtime</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
        <div className="panel-card p-4">
          <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Provider</label>
          <Dropdown
            items={PROVIDER_DEFINITIONS.map((provider) => ({
              value: provider.id,
              label: provider.label,
              description: provider.description,
            }))}
            value={state.settings.provider}
            onChange={(v) => updateSettings({ provider: v as AppSettings["provider"] })}
            placeholder="Select provider"
            className="w-full"
          />
          <p className="mt-1.5 text-xs text-docpilot-muted">{selectedProviderInfo?.description}</p>

          <label className="mb-2 mt-4 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Provider URL</label>
          <div className="relative">
            <Link2 size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-docpilot-muted" />
            <input
              value={providerUrl}
              onChange={(event) => setProviderUrl(event.target.value)}
              className="field-shell w-full pl-10"
              placeholder={selectedProviderInfo?.endpointPlaceholder ?? "https://api.example.com"}
              disabled={isLoadingConfig || isSavingProviderUrl}
            />
          </div>
          <div className="mt-2 flex items-center justify-between gap-3 text-xs text-docpilot-muted">
            <span>
              {currentSavedEndpoint
                ? "Using your saved override for this provider."
                : currentResolvedEndpoint
                  ? "Using the default provider URL."
                  : "Set a provider URL for this provider."}
            </span>
            {currentResolvedEndpoint ? <span className="badge">{currentSavedEndpoint ? "override" : "default"}</span> : null}
          </div>
          <div className="mt-3 flex items-center gap-2">
            <button
              type="button"
              className="action-button-primary"
              onClick={() => void saveProviderUrlOverride()}
              disabled={isLoadingConfig || isSavingProviderUrl || !providerUrl.trim() || !providerUrlChanged}
            >
              <Save size={14} /> Save URL
            </button>
            <button
              type="button"
              className="action-button"
              onClick={() => void resetProviderUrlOverride()}
              disabled={isLoadingConfig || isSavingProviderUrl || (!currentSavedEndpoint && !providerUrlChanged)}
            >
              <RotateCcw size={14} /> Reset
            </button>
          </div>
          {providerUrlError ? <p className="mt-2 text-xs text-docpilot-dangerText">{providerUrlError}</p> : null}

          <label className="mb-2 mt-4 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
            Model override <span className="normal-case text-docpilot-muted/60">(optional)</span>
          </label>
          <div className="relative">
            <Cpu size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-docpilot-muted" />
            <input
              value={state.settings.modelOverride}
              onChange={(event) => updateSettings({ modelOverride: event.target.value })}
              className="field-shell w-full pl-10"
              placeholder="e.g. llama3.2, gpt-4o-mini, anthropic/claude-3-haiku"
            />
          </div>
          <p className="mt-1.5 text-xs text-docpilot-muted">
            Leave blank to use the backend default for the selected provider.
          </p>
        </div>

        <div className="panel-card p-4">
          <KeyManager baseUrl={state.settings.apiBaseUrl} selectedProvider={state.settings.provider} />
        </div>
      </div>
    </div>
  );
}
