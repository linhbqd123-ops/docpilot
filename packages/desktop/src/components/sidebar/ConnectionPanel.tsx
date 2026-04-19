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

  // Model override state
  const [modelOverride, setModelOverride] = useState("");
  const [modelOverrideSaved, setModelOverrideSaved] = useState<string | null>(null);
  const [modelOverrideError, setModelOverrideError] = useState("");
  const [isSavingModelOverride, setIsSavingModelOverride] = useState(false);

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

  // Load model override when provider changes
  useEffect(() => {
    let cancelled = false;

    async function loadModelOverride() {
      try {
        const { data } = await apiClient.get<{ model_override: string | null }>(
          `/api/keys/${state.settings.provider}/model-override`
        );
        if (!cancelled) {
          const saved = data.model_override || null;
          setModelOverrideSaved(saved);
          setModelOverride(saved || "");
          setModelOverrideError("");
        }
      } catch (error) {
        if (!cancelled) {
          // Not an error if endpoint doesn't exist - just means no override saved
          setModelOverrideSaved(null);
          setModelOverride("");
        }
      }
    }

    loadModelOverride();
    return () => {
      cancelled = true;
    };
  }, [apiClient, state.settings.provider]);

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

  async function saveModelOverride() {
    const trimmedModel = modelOverride.trim();

    setIsSavingModelOverride(true);
    setModelOverrideError("");

    try {
      const { data } = await apiClient.post<{ model_override: string | null }>(
        `/api/keys/${state.settings.provider}/model-override`,
        {
          model_override: trimmedModel,
        }
      );
      setModelOverrideSaved(data.model_override || null);
      setModelOverride(data.model_override || "");
    } catch (error) {
      setModelOverrideError(error instanceof Error ? error.message : "Failed to save model override");
    } finally {
      setIsSavingModelOverride(false);
    }
  }

  async function resetModelOverride() {
    if (!modelOverrideSaved) {
      setModelOverride("");
      return;
    }

    setIsSavingModelOverride(true);
    setModelOverrideError("");

    try {
      await apiClient.delete(`/api/keys/${state.settings.provider}/model-override`);
      setModelOverrideSaved(null);
      setModelOverride("");
    } catch (error) {
      setModelOverrideError(error instanceof Error ? error.message : "Failed to reset model override");
    } finally {
      setIsSavingModelOverride(false);
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
            Model override <span className="normal-case text-docpilot-muted/60">(optional, per-provider)</span>
          </label>
          <div className="relative">
            <Cpu size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-docpilot-muted" />
            <input
              value={modelOverride}
              onChange={(event) => setModelOverride(event.target.value)}
              className="field-shell w-full pl-10"
              placeholder="e.g. llama3.2, gpt-4o-mini, claude-3-haiku"
              disabled={isLoadingConfig || isSavingModelOverride}
            />
          </div>
          <div className="mt-2 flex items-center justify-between gap-3 text-xs text-docpilot-muted">
            <span>
              {modelOverrideSaved
                ? `Using saved override: ${modelOverrideSaved}`
                : "Using backend default for this provider."}
            </span>
            {modelOverrideSaved ? <span className="badge">saved</span> : null}
          </div>
          <div className="mt-3 flex items-center gap-2">
            <button
              type="button"
              className="action-button-primary"
              onClick={() => void saveModelOverride()}
              disabled={isLoadingConfig || isSavingModelOverride || modelOverride === (modelOverrideSaved || "")}
            >
              <Save size={14} /> Save Model
            </button>
            <button
              type="button"
              className="action-button"
              onClick={() => void resetModelOverride()}
              disabled={isLoadingConfig || isSavingModelOverride || !modelOverrideSaved}
            >
              <RotateCcw size={14} /> Reset
            </button>
          </div>
          {modelOverrideError ? <p className="mt-2 text-xs text-docpilot-dangerText">{modelOverrideError}</p> : null}
        </div>

        <div className="panel-card p-4">
          <KeyManager baseUrl={state.settings.apiBaseUrl} selectedProvider={state.settings.provider} />
        </div>
      </div>
    </div>
  );
}
