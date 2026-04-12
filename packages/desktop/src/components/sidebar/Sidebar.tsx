import { ConnectionPanel } from "@/components/sidebar/ConnectionPanel";
import { LibraryPanel } from "@/components/sidebar/LibraryPanel";
import { OutlinePanel } from "@/components/sidebar/OutlinePanel";
import { ReviewPanel } from "@/components/sidebar/ReviewPanel";
import { SettingsPanel } from "@/components/sidebar/SettingsPanel";
import { useAppContext } from "@/app/context";

export function Sidebar() {
  const { state } = useAppContext();

  return (
    <section className="flex h-full min-h-0 flex-col border-r border-docpilot-border bg-docpilot-sidebar">
      {state.activeSidebarView === "library" ? <LibraryPanel /> : null}
      {state.activeSidebarView === "outline" ? <OutlinePanel /> : null}
      {state.activeSidebarView === "review" ? <ReviewPanel /> : null}
      {state.activeSidebarView === "connect" ? <ConnectionPanel /> : null}
      {state.activeSidebarView === "settings" ? <SettingsPanel /> : null}
    </section>
  );
}