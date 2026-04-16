import { GitCompareArrows, Palette, PlugZap, Settings2, TextSearch, Files } from "lucide-react";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";

import { useAppContext } from "@/app/context";
import { getNextTheme, getThemeDefinition } from "@/app/themes";
import { ChatPanel } from "@/components/chat/ChatPanel";
import { DocumentWorkspace } from "@/components/editor/DocumentWorkspace";
import { Sidebar } from "@/components/sidebar/Sidebar";
import { formatRelativeTime } from "@/lib/utils";

import { SidebarItem } from "./SidebarItem";

function ResizeGrip() {
  return (
    <PanelResizeHandle className="group relative -mx-1.5 flex w-3 shrink-0 items-center justify-center bg-transparent">
      <div className="h-full w-px bg-docpilot-border transition group-hover:bg-docpilot-accent/40" />
      <div className="absolute h-12 w-1 rounded-full bg-docpilot-hover opacity-0 transition group-hover:opacity-100" />
    </PanelResizeHandle>
  );
}

export function AppLayout() {
  const { state, selectedDocument, setActiveSidebarView, clearBanner, updateSettings } = useAppContext();
  const activeTheme = getThemeDefinition(state.settings.theme);

  return (
    <div className="relative h-screen overflow-hidden bg-docpilot-bg text-docpilot-text">
      <div className="pointer-events-none absolute inset-0 bg-mesh opacity-90" />
      <div className="relative flex h-full min-h-0">
        <aside className="flex w-[72px] shrink-0 flex-col items-center gap-3 border-r border-docpilot-border bg-docpilot-rail px-3 py-4 backdrop-blur-xl">
          <SidebarItem
            icon={<Files size={20} />}
            label="Library"
            active={state.activeSidebarView === "library"}
            onClick={() => setActiveSidebarView("library")}
          />
          <SidebarItem
            icon={<TextSearch size={20} />}
            label="Outline"
            active={state.activeSidebarView === "outline"}
            onClick={() => setActiveSidebarView("outline")}
          />
          <SidebarItem
            icon={<GitCompareArrows size={20} />}
            label="Review"
            active={state.activeSidebarView === "review"}
            indicator={Boolean(selectedDocument?.pendingRevisionId)}
            onClick={() => setActiveSidebarView("review")}
          />
          <div className="flex-1" />
          <SidebarItem
            icon={<PlugZap size={20} />}
            label="Connect"
            active={state.activeSidebarView === "connect"}
            onClick={() => setActiveSidebarView("connect")}
          />
          <SidebarItem
            icon={<Settings2 size={20} />}
            label="Settings"
            active={state.activeSidebarView === "settings"}
            onClick={() => setActiveSidebarView("settings")}
          />
        </aside>

        <div className="flex min-h-0 flex-1 flex-col">
          {state.banner ? (
            <div className="flex items-center justify-between border-b border-docpilot-warning/30 bg-docpilot-warningSoft px-4 py-3 text-sm text-docpilot-warningText">
              <span>{state.banner}</span>
              <button type="button" className="action-button" onClick={clearBanner}>
                Dismiss
              </button>
            </div>
          ) : null}

          <div className="min-h-0 flex-1 p-3 pt-4">
            <div className="panel-shell flex h-full min-h-0 overflow-hidden">
              <PanelGroup direction="horizontal" className="min-h-0 flex-1">
                <Panel defaultSize={21} minSize={15} maxSize={25} className="min-h-0 min-w-0" collapsible={false}>
                  <Sidebar />
                </Panel>

                <ResizeGrip />

                <Panel defaultSize={49} minSize={35} maxSize={60} className="min-h-0 min-w-0" collapsible={false}>
                  <DocumentWorkspace />
                </Panel>

                <ResizeGrip />

                <Panel defaultSize={30} minSize={26} maxSize={50} className="min-h-0 min-w-0" collapsible={false}>
                  <ChatPanel />
                </Panel>
              </PanelGroup>
            </div>
          </div>

          <footer className="flex h-9 items-center justify-between border-t border-docpilot-border bg-docpilot-rail px-4 text-xs text-docpilot-muted backdrop-blur-xl">
            <div className="flex items-center gap-4">
              <span>Provider: {state.settings.provider}</span>
              {state.settings.modelOverride.trim() ? <span>Model: {state.settings.modelOverride.trim()}</span> : null}
            </div>
            <div className="flex items-center gap-4">
              <button
                type="button"
                className="action-button h-7 px-2.5 py-1 text-[11px]"
                onClick={() => updateSettings({ theme: getNextTheme(state.settings.theme) })}
                title="Cycle theme"
              >
                <Palette size={13} /> {activeTheme.shortLabel}
              </button>
              <span>{selectedDocument ? `${selectedDocument.wordCount} words` : "No document selected"}</span>
              {selectedDocument ? <span>Updated {formatRelativeTime(selectedDocument.updatedAt)}</span> : null}
            </div>
          </footer>
        </div>
      </div>
    </div>
  );
}
