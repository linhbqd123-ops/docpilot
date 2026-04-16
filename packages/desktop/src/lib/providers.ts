import type { AppSettings } from "@/app/types";

export interface ProviderDefinition {
    id: AppSettings["provider"];
    label: string;
    description: string;
    supportsEndpoint: boolean;
    requiresApiKey: boolean;
    defaultEndpoint?: string;
    endpointPlaceholder?: string;
}

export interface ProviderStatusPayload {
    provider: string;
    has_key: boolean;
    masked_key?: string | null;
    endpoint?: string | null;
    resolved_endpoint?: string | null;
}

export const PROVIDER_DEFINITIONS: ProviderDefinition[] = [
    {
        id: "ollama",
        label: "Ollama",
        description: "Local model - recommended for privacy",
        supportsEndpoint: true,
        requiresApiKey: false,
        defaultEndpoint: "http://localhost:11434",
        endpointPlaceholder: "http://localhost:11434",
    },
    {
        id: "openai",
        label: "OpenAI",
        description: "gpt-4o, gpt-4o-mini, o1, ...",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.openai.com/v1",
        endpointPlaceholder: "https://api.openai.com/v1",
    },
    {
        id: "groq",
        label: "Groq",
        description: "Ultra-fast inference (llama3, gemma, ...)",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.groq.com/openai/v1",
        endpointPlaceholder: "https://api.groq.com/openai/v1",
    },
    {
        id: "openrouter",
        label: "OpenRouter",
        description: "300+ models via one API key",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://openrouter.ai/api/v1",
        endpointPlaceholder: "https://openrouter.ai/api/v1",
    },
    {
        id: "together",
        label: "TogetherAI",
        description: "Open-source model hosting",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.together.xyz/v1",
        endpointPlaceholder: "https://api.together.xyz/v1",
    },
    {
        id: "zai",
        label: "z.ai",
        description: "z.ai endpoint",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.z.ai/api/v1",
        endpointPlaceholder: "https://api.z.ai/api/v1",
    },
    {
        id: "anthropic",
        label: "Anthropic",
        description: "Claude 3.5 Sonnet / Haiku / Opus",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.anthropic.com",
        endpointPlaceholder: "https://api.anthropic.com",
    },
    {
        id: "azure",
        label: "Azure OpenAI",
        description: "Azure-hosted models",
        supportsEndpoint: true,
        requiresApiKey: true,
        endpointPlaceholder: "https://your-resource.openai.azure.com",
    },
    {
        id: "custom",
        label: "Custom",
        description: "Any OpenAI-compatible endpoint",
        supportsEndpoint: true,
        requiresApiKey: true,
        endpointPlaceholder: "https://your-openai-compatible-host/v1",
    },
];

export function getProviderDefinition(provider: AppSettings["provider"]) {
    return PROVIDER_DEFINITIONS.find((entry) => entry.id === provider);
}