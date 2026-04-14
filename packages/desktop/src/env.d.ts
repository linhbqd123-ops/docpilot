/// <reference types="vite/client" />

// Explicit Vite env typings for DocPilot frontend.
// Add additional VITE_... keys here as the project grows.

interface ImportMetaEnv {
    readonly VITE_DOCPILOT_API_BASE_URL?: string;
    readonly VITE_DOCPILOT_PROVIDER?: string;
    readonly VITE_DOCPILOT_MODEL?: string;
    readonly VITE_DOCPILOT_PROVIDER_HINT?: string;

    // Allow other user-defined VITE_* vars without TS errors
    [key: string]: string | undefined;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
