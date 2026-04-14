import { useRef } from "react";
import { FilePlus2, FolderOpen, Trash2, Upload } from "lucide-react";

import { useAppContext } from "@/app/context";
import { cn, formatBytes, formatRelativeTime } from "@/lib/utils";

export function LibraryPanel() {
  const { state, selectedDocument, importFiles, removeDocument, selectDocument } = useAppContext();
  const inputRef = useRef<HTMLInputElement | null>(null);

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Library</p>
        <h2 className="panel-title">Documents</h2>
      </div>

      <div className="border-b border-docpilot-border p-4">
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          accept=".html,.htm,.md,.markdown,.txt,.doc,.docx,.pdf"
          onChange={(event) => {
            if (event.target.files?.length) {
              void importFiles(event.target.files);
              event.target.value = "";
            }
          }}
        />

        <button type="button" className="action-button-primary w-full" onClick={() => inputRef.current?.click()}>
          <Upload size={16} /> Import documents
        </button>

        <div
          className="subtle-card-dashed mt-3 p-4 text-sm text-docpilot-muted"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            if (event.dataTransfer.files.length) {
              void importFiles(event.dataTransfer.files);
            }
          }}
        >
          <div className="mb-2 flex items-center gap-2 text-docpilot-textStrong">
            <FilePlus2 size={16} /> Drag files here or use the import button
          </div>
          <p>Preview is native for HTML, Markdown, and TXT. DOCX and PDF stay in the library until the backend import endpoint is available.</p>
        </div>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto overflow-x-hidden p-4">
        {state.documents.length === 0 ? (
          <div className="panel-card flex flex-col items-start gap-3 p-4 text-sm text-docpilot-muted">
            <FolderOpen size={18} className="text-docpilot-accent" />
            <p className="font-medium text-docpilot-text">No documents yet</p>
            <p>Import your first document to start an editing session.</p>
          </div>
        ) : null}

        <div className="space-y-3">
          {state.documents.map((document) => {
            const isSelected = document.id === selectedDocument?.id;

            return (
              <article
                key={document.id}
                className={cn(
                  "rounded-2xl border p-4 transition",
                  isSelected
                    ? "border-docpilot-accent/40 bg-docpilot-accentSoft shadow-active"
                    : "border-docpilot-border bg-docpilot-panelAlt hover:border-docpilot-accent/20 hover:bg-docpilot-hover",
                )}
              >
                <div className="flex items-start justify-between gap-3">
                  <button
                    type="button"
                    className="min-w-0 flex-1 overflow-hidden text-left"
                    onClick={() => selectDocument(document.id)}
                  >
                    <p className="block line-clamp-1 font-medium text-docpilot-textStrong">{document.name}</p>
                    <p className="mt-1 line-clamp-1 text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                      {document.kind} • {formatBytes(document.size)}
                    </p>
                  </button>
                  <button
                    type="button"
                    className="action-button shrink-0 px-2.5 py-2"
                    onClick={() => removeDocument(document.id)}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>

                <div className="mt-3 flex flex-wrap gap-2 text-xs text-docpilot-muted">
                  <span className="badge">{document.status === "ready" ? "ready" : "backend import"}</span>
                  {document.pendingHtml ? <span className="badge">review staged</span> : null}
                  <span>updated {formatRelativeTime(document.updatedAt)}</span>
                </div>

                {document.status === "error" && document.error ? (
                  <p className="mt-3 text-sm text-docpilot-dangerText">{document.error}</p>
                ) : null}
              </article>
            );
          })}
        </div>
      </div>
    </div>
  );
}