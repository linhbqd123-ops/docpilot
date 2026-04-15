import { Edit2, Loader, AlertCircle } from "lucide-react";
import { useEffect, useMemo, useState, useCallback } from "react";
import { KeyEditorDialog } from "./KeyEditorDialog";
import { cn } from "@/lib/utils";
import { HTTPClient } from "@/lib/api";

interface ProviderInfo {
    provider: string;
    label: string;
    icon?: string;
    supportEndpoint?: boolean;
    endpointPlaceholder?: string;
}

interface ProviderStatus {
    provider: string;
    has_key: boolean;
    masked_key?: string | null;
}

interface KeyManagerProps {
    baseUrl: string;
    selectedProvider?: string;
}

const PROVIDERS: ProviderInfo[] = [
    {
        provider: "openai",
        label: "OpenAI",
    },
    {
        provider: "groq",
        label: "Groq",
    },
    {
        provider: "anthropic",
        label: "Anthropic / Claude",
    },
    {
        provider: "azure",
        label: "Azure OpenAI",
        supportEndpoint: true,
        endpointPlaceholder: "https://your-resource.openai.azure.com",
    },
    {
        provider: "openrouter",
        label: "OpenRouter",
    },
    {
        provider: "together",
        label: "TogetherAI",
    },
    {
        provider: "zai",
        label: "Z.AI",
        supportEndpoint: true,
        endpointPlaceholder: "https://api.z.ai/api/v1",
    },
    {
        provider: "ollama",
        label: "Ollama (Local)",
        supportEndpoint: true,
        endpointPlaceholder: "http://localhost:11434",
    },
];

export function KeyManager({ baseUrl, selectedProvider }: KeyManagerProps) {
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState("");
    const [editingProvider, setEditingProvider] = useState<string | null>(null);
    const [statusMap, setStatusMap] = useState<Record<string, ProviderStatus>>({});

    // Memoize API client - only recreate when baseUrl changes
    const apiClient = useMemo(() => new HTTPClient(baseUrl), [baseUrl]);

    const loadProviderStatus = useCallback(async () => {
        setLoading(true);
        setError("");

        try {
            const response = await apiClient.get<{ providers: ProviderStatus[] }>("/api/keys/list");
            const providers = response.data.providers ?? [];
            const map: Record<string, ProviderStatus> = {};

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
                    API Keys Management
                </label>
                <p className="text-sm text-docpilot-muted">
                    Securely store your API keys. Keys are encrypted and never logged on the frontend.
                </p>
            </div>

            {error && (
                <div className="p-3 bg-red-500/10 border border-red-500/30 rounded text-sm text-red-600 flex items-center gap-2">
                    <AlertCircle size={16} />
                    {error}
                </div>
            )}

            <div className="space-y-2">
                {(
                    selectedProvider ? PROVIDERS.filter((p) => p.provider === selectedProvider) : PROVIDERS
                ).map((providerInfo) => {
                    const status = statusMap[providerInfo.provider];
                    const hasKey = status?.has_key || false;
                    const maskedKey = status?.masked_key || null;

                    return (
                        <div
                            key={providerInfo.provider}
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
                                ) : (
                                    <div className="mt-1 text-xs text-docpilot-muted">
                                        No key configured
                                    </div>
                                )}
                            </div>

                            <button
                                onClick={() => setEditingProvider(providerInfo.provider)}
                                className={cn(
                                    "p-2 rounded transition-colors",
                                    hasKey
                                        ? "bg-docpilot-surface hover:bg-docpilot-border text-docpilot-text"
                                        : "bg-docpilot-accent/10 hover:bg-docpilot-accent/20 text-docpilot-accent",
                                )}
                                title={hasKey ? "Update key" : "Add key"}
                            >
                                <Edit2 size={16} />
                            </button>

                            {editingProvider === providerInfo.provider && (
                                <KeyEditorDialog
                                    label={providerInfo.label}
                                    currentMaskedKey={maskedKey}
                                    isOpen={true}
                                    onClose={() => setEditingProvider(null)}
                                    onSave={(key, endpoint) =>
                                        handleSaveKey(providerInfo.provider, key, endpoint)
                                    }
                                    onDelete={() => handleDeleteKey(providerInfo.provider)}
                                    supportEndpoint={providerInfo.supportEndpoint}
                                    endpointPlaceholder={providerInfo.endpointPlaceholder}
                                />
                            )}
                        </div>
                    );
                })}
            </div>

            <div className="mt-6 p-3 bg-blue-500/10 border border-blue-500/30 rounded text-sm text-blue-600">
                <div className="font-medium mb-1">🔒 Security Note</div>
                <ul className="space-y-1 text-xs">
                    <li>• Keys are encrypted before being stored locally</li>
                    <li>• Keys are never exposed in frontend storage or logs</li>
                    <li>• Changes to keys are synced to the backend immediately</li>
                    <li>• Deleting a key will update all providers using it</li>
                </ul>
            </div>
        </div>
    );
}

