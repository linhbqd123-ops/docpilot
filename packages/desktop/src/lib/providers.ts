import type { AppSettings } from "@/app/types";

export interface ProviderDefinition {
    id: AppSettings["provider"];
    label: string;
    description: string;
    defaultModel?: string;
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
        defaultModel: "llama3.2",
        supportsEndpoint: true,
        requiresApiKey: false,
        defaultEndpoint: "http://localhost:11434",
        endpointPlaceholder: "http://localhost:11434",
    },
    {
        id: "openai",
        label: "OpenAI",
        description: "gpt-4o, gpt-4o-mini, o1, ...",
        defaultModel: "gpt-4o",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.openai.com/v1",
        endpointPlaceholder: "https://api.openai.com/v1",
    },
    {
        id: "groq",
        label: "Groq",
        description: "Ultra-fast inference (llama3, gemma, ...)",
        defaultModel: "llama-3.3-70b-versatile",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.groq.com/openai/v1",
        endpointPlaceholder: "https://api.groq.com/openai/v1",
    },
    {
        id: "openrouter",
        label: "OpenRouter",
        description: "300+ models via one API key",
        defaultModel: "meta-llama/llama-3.3-70b-instruct",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://openrouter.ai/api/v1",
        endpointPlaceholder: "https://openrouter.ai/api/v1",
    },
    {
        id: "together",
        label: "TogetherAI",
        description: "Open-source model hosting",
        defaultModel: "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.together.xyz/v1",
        endpointPlaceholder: "https://api.together.xyz/v1",
    },
    {
        id: "nvidia",
        label: "NVIDIA",
        description: "NVIDIA Integrate API / NIM hosted models",
        defaultModel: "meta/llama-3.1-70b-instruct",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://integrate.api.nvidia.com/v1",
        endpointPlaceholder: "https://integrate.api.nvidia.com/v1",
    },
    {
        id: "zai",
        label: "z.ai",
        description: "z.ai endpoint",
        defaultModel: "zai-v1",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.z.ai/api/v1",
        endpointPlaceholder: "https://api.z.ai/api/v1",
    },
    {
        id: "anthropic",
        label: "Anthropic",
        description: "Claude 3.5 Sonnet / Haiku / Opus",
        defaultModel: "claude-3-5-sonnet-20241022",
        supportsEndpoint: true,
        requiresApiKey: true,
        defaultEndpoint: "https://api.anthropic.com",
        endpointPlaceholder: "https://api.anthropic.com",
    },
    {
        id: "azure",
        label: "Azure OpenAI",
        description: "Azure-hosted models",
        defaultModel: "gpt-4o",
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