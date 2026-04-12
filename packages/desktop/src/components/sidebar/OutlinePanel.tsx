import { useAppContext } from "@/app/context";
import { cn } from "@/lib/utils";

export function OutlinePanel() {
  const { selectedDocument } = useAppContext();

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Outline</p>
        <h2 className="panel-title">Structure</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto p-4">
        {!selectedDocument ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">Select a document to inspect its heading hierarchy.</div>
        ) : null}

        {selectedDocument && selectedDocument.outline.length === 0 ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            No headings were detected in the current document.
          </div>
        ) : null}

        <div className="space-y-2">
          {selectedDocument?.outline.map((item) => (
            <a
              key={item.id}
              href={`#${item.id}`}
              className={cn(
                "flex items-start rounded-xl border border-transparent px-3 py-2 text-sm text-docpilot-muted transition hover:border-docpilot-border hover:bg-docpilot-hover hover:text-docpilot-textStrong",
                item.level === 1 ? "font-medium text-docpilot-textStrong" : "",
              )}
              style={{ paddingLeft: `${item.level * 12}px` }}
            >
              <span className="truncate">{item.title}</span>
            </a>
          ))}
        </div>
      </div>
    </div>
  );
}