import { Check, MoonStar, SunMedium } from "lucide-react";

import { useAppContext } from "@/app/context";
import { THEME_OPTIONS } from "@/app/themes";
import { cn } from "@/lib/utils";

function ThemeIcon({ themeId }: { themeId: string }) {
  return themeId === "light" ? <SunMedium size={16} className="shrink-0" /> : <MoonStar size={16} className="shrink-0" />;
}

export function SettingsPanel() {
  const { state, updateSettings, requestCompactNextTurn } = useAppContext();

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Settings</p>
        <h2 className="panel-title">Runtime behavior</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
        <div className="panel-card space-y-4 p-4">
          <div>
            <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">Theme</label>
            <p className="text-sm text-docpilot-muted">Theme options are registry-driven, so new themes can be added without refactoring the UI again.</p>
          </div>

          <div className="grid grid-cols-2 gap-2">
            {THEME_OPTIONS.map((theme) => {
              const active = state.settings.theme === theme.id;

              return (
                <button
                  key={theme.id}
                  type="button"
                  onClick={() => updateSettings({ theme: theme.id })}
                  className={cn(
                    "theme-card text-left",
                    active ? "theme-card-active" : "",
                  )}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex items-start gap-3">
                      <ThemeIcon themeId={theme.id} />
                      <span>
                        <span className="block font-medium text-docpilot-textStrong">{theme.label}</span>
                        <span className="mt-0.5 block text-xs text-docpilot-muted">{theme.description}</span>
                      </span>
                    </div>
                    {active ? <Check size={15} className="mt-0.5 text-docpilot-accent" /> : null}
                  </div>

                  <div className="theme-card-swatches">
                    <span className="theme-card-swatch" style={{ backgroundColor: theme.preview.chrome, borderColor: theme.preview.chrome }} />
                    <span className="theme-card-swatch" style={{ backgroundColor: theme.preview.panel, borderColor: theme.preview.panel }} />
                    <span className="theme-card-swatch" style={{ backgroundColor: theme.preview.surface, borderColor: theme.preview.surface }} />
                    <span className="theme-card-swatch" style={{ backgroundColor: theme.preview.accent, borderColor: theme.preview.accent }} />
                    <span className="theme-card-swatch" style={{ backgroundColor: theme.preview.text, borderColor: theme.preview.text }} />
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        <div className="panel-card space-y-4 p-4">
          <div>
            <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
              Request timeout (ms)
            </label>
            <input
              type="number"
              value={state.settings.requestTimeoutMs}
              onChange={(event) => updateSettings({ requestTimeoutMs: Number(event.target.value) })}
              className="field-shell w-full"
            />
          </div>

          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Max input tokens
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.maxInputTokens}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      maxInputTokens: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Session context budget
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.sessionContextBudgetTokens}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      sessionContextBudgetTokens: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Tool result budget
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.toolResultBudgetTokens}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      toolResultBudgetTokens: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Max tools per batch
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.maxToolBatchSize}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      maxToolBatchSize: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Max parallel tools
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.maxParallelTools}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      maxParallelTools: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                Max heavy tools per turn
              </label>
              <input
                type="number"
                value={state.settings.agentConfig.maxHeavyToolsPerTurn}
                onChange={(event) =>
                  updateSettings({
                    agentConfig: {
                      ...state.settings.agentConfig,
                      maxHeavyToolsPerTurn: Number(event.target.value),
                    },
                  })
                }
                className="field-shell w-full"
              />
            </div>
          </div>

          <label className="subtle-card flex items-center justify-between gap-4 px-4 py-3 text-sm text-docpilot-text">
            <span>
              <span className="block font-medium text-docpilot-textStrong">Auto-compact session context</span>
              <span className="mt-1 block text-docpilot-muted">Summarize older turns automatically when the request is close to the configured input budget.</span>
            </span>
            <input
              type="checkbox"
              checked={state.settings.agentConfig.autoCompactSession}
              onChange={(event) =>
                updateSettings({
                  agentConfig: {
                    ...state.settings.agentConfig,
                    autoCompactSession: event.target.checked,
                  },
                })
              }
              className="h-4 w-4 rounded border-docpilot-border bg-transparent"
            />
          </label>

          {/* 'Compact now (next request)' removed — compaction is available on the chat toolbar */}

          <label className="subtle-card flex items-center justify-between gap-4 px-4 py-3 text-sm text-docpilot-text">
            <span>
              <span className="block font-medium text-docpilot-textStrong">Stream assistant responses</span>
              <span className="mt-1 block text-docpilot-muted">Use server-sent events when the backend supports it.</span>
            </span>
            <input
              type="checkbox"
              checked={state.settings.streaming}
              onChange={(event) => updateSettings({ streaming: event.target.checked })}
              className="h-4 w-4 rounded border-docpilot-border bg-transparent"
            />
          </label>

          <label className="subtle-card flex items-center justify-between gap-4 px-4 py-3 text-sm text-docpilot-text">
            <span>
              <span className="block font-medium text-docpilot-textStrong">Connect on startup</span>
              <span className="mt-1 block text-docpilot-muted">Auto-run a health check when the app boots.</span>
            </span>
            <input
              type="checkbox"
              checked={state.settings.connectOnStartup}
              onChange={(event) => updateSettings({ connectOnStartup: event.target.checked })}
              className="h-4 w-4 rounded border-docpilot-border bg-transparent"
            />
          </label>
        </div>
      </div>
    </div>
  );
}