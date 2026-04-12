import { Link2, PlugZap } from "lucide-react";

import Dropdown from "@/components/ui/Dropdown";
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
          <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Base URL</label>
          <div className="relative">
            <Link2 size={16} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-docpilot-muted" />
            <input
              value={state.settings.apiBaseUrl}
              onChange={(event) => updateSettings({ apiBaseUrl: event.target.value })}
              className="field-shell w-full pl-10"
              placeholder="http://localhost:8000"
            />
          </div>

          <label className="mb-2 mt-4 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Provider</label>
          <Dropdown
            items={[
              { value: "ollama", label: "Ollama", description: "Local model (recommended for privacy)" },
              { value: "openai", label: "OpenAI", description: "OpenAI cloud models" },
              { value: "anthropic", label: "Anthropic", description: "Anthropic models" },
              { value: "azure", label: "Azure OpenAI", description: "Azure-hosted OpenAI" },
              { value: "custom", label: "Custom", description: "Custom provider endpoint" },
            ]}
            value={state.settings.provider}
            onChange={(v) => updateSettings({ provider: v as any })}
            placeholder="Select provider"
            className="w-full"
          />

          <button type="button" className="action-button-primary mt-4 w-full" onClick={() => void testConnection()}>
            <PlugZap size={16} /> Test connection
          </button>
        </div>

        <div className="panel-card p-4 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-docpilot-muted">Status</span>
            <span className="badge">{state.connection.status}</span>
          </div>
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
          <p className="font-medium text-docpilot-text">Expected minimum API</p>
          <ul className="mt-3 space-y-2 leading-6">
            <li>`GET /health` for liveness and version</li>
            <li>`POST /api/chat` for document-aware editing requests</li>
            <li>`POST /api/documents/import` for DOCX/PDF conversion when backend is ready</li>
          </ul>
        </div>
      </div>
    </div>
  );
}