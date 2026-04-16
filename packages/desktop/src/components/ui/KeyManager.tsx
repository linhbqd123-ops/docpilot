import { Edit2, Loader, AlertCircle } from "lucide-react";
import { useEffect, useMemo, useState, useCallback } from "react";
import { KeyEditorDialog } from "./KeyEditorDialog";
import { cn } from "@/lib/utils";
import { HTTPClient } from "@/lib/api";
import { PROVIDER_DEFINITIONS, type ProviderStatusPayload } from "@/lib/providers";

interface KeyManagerProps {
    baseUrl: string;
    selectedProvider?: string;
}

export function KeyManager({ baseUrl, selectedProvider }: KeyManagerProps) {
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [editingProvider, setEditingProvider] = useState<string | null>(null);
    const [statusMap, setStatusMap] = useState<Record<string, ProviderStatusPayload>>({});

    // Memoize API client - only recreate when baseUrl changes
    const apiClient = useMemo(() => new HTTPClient(baseUrl), [baseUrl]);

    const loadProviderStatus = useCallback(async () => {
        setLoading(true);
        setError("");

        try {
            const response = await apiClient.get<{ providers: ProviderStatusPayload[] }>("/api/keys/list");
            const providers = response.data.providers ?? [];
            const map: Record<string, ProviderStatusPayload> = {};

            for (const providerStatus of providers) {
                map[providerStatus.provider] = providerStatus;
            }

            setStatusMap(map);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to load key status");
        } finally {
            setLoading(false);
        }
    }, [apiClient]);

    // Memoize handleSaveKey function
    const handleSaveKey = useCallback(async (provider: string, key: string, endpoint?: string) => {
        try {
            await apiClient.post("/api/keys/set", {
                provider,
                key,
                endpoint,
            });

            // Refresh status
            await loadProviderStatus();
        } catch (err) {
            throw err instanceof Error
                ? err
                : new Error("Failed to save key");
        }
    }, [apiClient, loadProviderStatus]);

    // Memoize handleDeleteKey function
    const handleDeleteKey = useCallback(async (provider: string) => {
        try {
            await apiClient.delete(`/api/keys/delete/${provider}`);

            // Refresh status
            await loadProviderStatus();
        } catch (err) {
            throw err instanceof Error
                ? err
                : new Error("Failed to delete key");
        }
    }, [apiClient, loadProviderStatus]);

    // Load provider status on mount and when baseUrl changes
    useEffect(() => {
        void loadProviderStatus();
    }, [loadProviderStatus]);

    const visibleProviders = (selectedProvider
        ? PROVIDER_DEFINITIONS.filter((provider) => provider.id === selectedProvider)
        : PROVIDER_DEFINITIONS
    ).filter((provider) => provider.requiresApiKey);

    if (loading) {
        return (
            <div className="flex items-center justify-center py-8">
                <Loader size={20} className="animate-spin text-docpilot-muted" />
            </div>
        );
    }

    return (
        <div className="space-y-4">
            <div>
                <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                    API key
                </label>
                <p className="text-sm text-docpilot-muted">
                    Save the API key for the selected provider. Provider URLs are managed above.
                </p>
            </div>

            {error && (
                <div className="p-3 bg-red-500/10 border border-red-500/30 rounded text-sm text-red-600 flex items-center gap-2">
                    <AlertCircle size={16} />
                    {error}
                </div>
            )}

            <div className="space-y-2">
                {visibleProviders.map((providerInfo) => {
                    const status = statusMap[providerInfo.id];
                    const hasKey = status?.has_key || false;
                    const maskedKey = status?.masked_key || null;

                    return (
                        <div
                            key={providerInfo.id}
                            className="subtle-card flex items-center justify-between gap-4 p-4 text-sm"
                        >
                            <div>
                                <div className="font-medium text-docpilot-textStrong">
                                    {providerInfo.label}
                                </div>
                                {hasKey && maskedKey ? (
                                    <div className="mt-1 font-mono text-xs text-docpilot-muted">
                                        {maskedKey}
                                    </div>
                                ) : null}
                                {!hasKey ? (
                                    <div className="mt-1 text-xs text-docpilot-muted">
                                        No key configured
                                    </div>
                                ) : null}
                            </div>

                            <button
                                onClick={() => setEditingProvider(providerInfo.id)}
                                className={cn(
                                    "p-2 rounded transition-colors",
                                    hasKey
                                        ? "bg-docpilot-surface hover:bg-docpilot-border text-docpilot-text"
                                        : "bg-docpilot-accent/10 hover:bg-docpilot-accent/20 text-docpilot-accent",
                                )}
                                title={hasKey ? "Update API key" : "Add API key"}
                            >
                                <Edit2 size={16} />
                            </button>

                            {editingProvider === providerInfo.id && (
                                <KeyEditorDialog
                                    label={providerInfo.label}
                                    currentMaskedKey={maskedKey}
                                    isOpen={true}
                                    onClose={() => setEditingProvider(null)}
                                    onSave={(key) =>
                                        handleSaveKey(providerInfo.id, key)
                                    }
                                    onDelete={() => handleDeleteKey(providerInfo.id)}
                                    supportEndpoint={false}
                                    requiresApiKey={true}
                                />
                            )}
                        </div>
                    );
                })}
            </div>

            {visibleProviders.length === 0 ? (
                <div className="subtle-card p-4 text-sm text-docpilot-muted">
                    {selectedProvider === "ollama"
                        ? "Ollama does not need an API key. Only the provider URL matters."
                        : "This provider does not need an API key in the current setup."}
                </div>
            ) : null}

            <div className="mt-6 p-3 bg-blue-500/10 border border-blue-500/30 rounded text-sm text-blue-600">
                <div className="font-medium mb-1">Security note</div>
                <ul className="space-y-1 text-xs">
                    <li>• Keys are encrypted before being stored locally</li>
                    <li>• Keys are never exposed in frontend storage or logs</li>
                    <li>• Changes are synced to the backend immediately</li>
                    <li>• Deleting a key does not change the provider URL override</li>
                </ul>
            </div>
        </div>
    );
}

