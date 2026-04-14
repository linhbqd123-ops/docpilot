import { Cpu, Link2, PlugZap } from "lucide-react";

import Dropdown from "@/components/ui/Dropdown";
import { KeyManager } from "@/components/ui/KeyManager";
import { useAppContext } from "@/app/context";
import { formatRelativeTime } from "@/lib/utils";

export function ConnectionPanel() {
  const { state, updateSettings, testConnection } = useAppContext();

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Connect</p>
        <h2 className="panel-title">Backend endpoint</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
        <div className="panel-card p-4">
          <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Provider Endpoint</label>
          <div className="relative">
            <Link2 size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-docpilot-muted" />
            <input
              value={state.settings.providerEndpoint ?? ""}
              onChange={(event) => updateSettings({ providerEndpoint: event.target.value })}
              className="field-shell w-full pl-10"
              placeholder="e.g. https://api.groq.com/openai/v1"
            />
          </div>
          <p className="mt-1.5 text-xs text-docpilot-muted">Provider API endpoint URL (optional for some providers)</p>

          <label className="mb-2 mt-4 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Provider</label>
          <Dropdown
            items={[
              { value: "ollama", label: "Ollama", description: "Local model — recommended for privacy" },
              { value: "openai", label: "OpenAI", description: "gpt-4o, gpt-4o-mini, o1, …" },
              { value: "groq", label: "Groq", description: "Ultra-fast inference (llama3, gemma, …)" },
              { value: "openrouter", label: "OpenRouter", description: "300+ models via one API key" },
              { value: "together", label: "TogetherAI", description: "Open-source model hosting" },
              { value: "zai", label: "z.ai", description: "z.ai endpoint" },
              { value: "anthropic", label: "Anthropic", description: "Claude 3.5 Sonnet / Haiku / Opus" },
              { value: "azure", label: "Azure OpenAI", description: "Azure-hosted models" },
              { value: "custom", label: "Custom", description: "Any OpenAI-compatible endpoint" },
            ]}
            value={state.settings.provider}
            onChange={(v) => updateSettings({ provider: v as AppSettings["provider"] })}
            placeholder="Select provider"
            className="w-full"
          />

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

          <button type="button" className="action-button-primary mt-4 w-full" onClick={() => void testConnection()}>
            <PlugZap size={16} /> Test connection
          </button>
        </div>

        <div className="panel-card p-4">
          <KeyManager baseUrl={state.settings.apiBaseUrl} selectedProvider={state.settings.provider} />
        </div>

        <div className="panel-card p-4 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-docpilot-muted">Status</span>
            <span className="badge">{state.connection.status}</span>
          </div>
          {state.connection.version ? (
            <p className="mt-1 text-xs text-docpilot-muted">Version {state.connection.version}</p>
          ) : null}
          <p className="mt-3 text-docpilot-text">
            {state.connection.error ?? "Configure the URL above and verify the backend before running edit requests."}
          </p>
          {state.connection.lastCheckedAt ? (
            <p className="mt-3 text-xs text-docpilot-muted">
              Last checked {formatRelativeTime(state.connection.lastCheckedAt)}
            </p>
          ) : null}
        </div>

        <div className="panel-card p-4 text-sm text-docpilot-muted">
          <p className="font-medium text-docpilot-text">Expected API contract</p>
          <ul className="mt-3 space-y-2 leading-6">
            <li><code>GET  /health</code> — liveness + version</li>
            <li><code>POST /api/chat</code> — document-aware editing (SSE)</li>
            <li><code>POST /api/documents/import</code> — DOCX/PDF → HTML</li>
            <li><code>POST /api/documents/export</code> — HTML → DOCX</li>
            <li><code>GET  /api/providers</code> — available providers</li>
          </ul>
        </div>
      </div>
    </div>
  );
}

// Re-export type import for inline use
type AppSettings = import("@/app/types").AppSettings;
