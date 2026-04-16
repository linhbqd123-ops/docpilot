import { Check, Download, FileCog, Lock, RefreshCw, Sparkles, Undo2, X } from "lucide-react";

import { useAppContext } from "@/app/context";
import { DocumentCanvas } from "@/components/editor/DocumentCanvas";
import { cn } from "@/lib/utils";

export function DocumentWorkspace() {
  const {
    selectedDocument,
    updateSelectedDocumentHtml,
    applyStagedRevision,
    rejectStagedRevision,
    rollbackCurrentRevision,
    exportDocument,
  } = useAppContext();

  if (!selectedDocument) {
    return (
      <div className="flex h-full items-center justify-center bg-gradient-to-b from-transparent to-docpilot-panelAlt p-10">
        <div className="panel-card max-w-xl p-10 text-center shadow-glow">
          <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Workspace</p>
          <h2 className="mt-3 font-display text-4xl text-docpilot-textStrong">Document-native AI editing starts here</h2>
          <p className="mt-4 text-base leading-8 text-docpilot-muted">
            Import a document from the Library panel to open the editor surface. Local HTML, Markdown, and TXT files
            can be edited directly. DOCX imports stay fidelity-backed and revision-backed through the MCP session flow.
          </p>
        </div>
      </div>
    );
  }

  const isSessionBacked = Boolean(selectedDocument.documentSessionId);
  const hasPendingRevision = Boolean(selectedDocument.pendingRevisionId);
  const isEditable = selectedDocument.status === "ready" && !isSessionBacked;
  const isFidelitySurface = isSessionBacked && Boolean(selectedDocument.sourceHtml);

  return (
    <div className="flex h-full min-h-0 flex-col bg-gradient-to-b from-transparent to-docpilot-panelAlt">
      <div className="border-b border-docpilot-border px-6 py-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="badge">{selectedDocument.kind}</span>
              <span className="badge">{isSessionBacked ? "session-backed" : selectedDocument.status === "ready" ? "local draft" : "backend import"}</span>
              {hasPendingRevision ? <span className="badge">revision staged</span> : null}
            </div>
            <h1 className="mt-3 text-2xl font-semibold text-docpilot-textStrong">{selectedDocument.name}</h1>
            <p className="mt-1 text-sm text-docpilot-muted">
              {selectedDocument.wordCount} words
              {isSessionBacked
                ? isFidelitySurface
                  ? " · fidelity-backed DOCX surface mirrored from the active session"
                  : " · session-backed document surface"
                : " · direct manual edits are autosaved on blur"}
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {hasPendingRevision ? (
              <>
                <button type="button" className="action-button-primary" onClick={() => void applyStagedRevision()}>
                  <Check size={14} /> Apply
                </button>
                <button type="button" className="action-button" onClick={() => void rejectStagedRevision()}>
                  <X size={14} /> Reject
                </button>
              </>
            ) : (
              <button type="button" className="action-button" disabled={!isSessionBacked}>
                <Sparkles size={14} /> {isSessionBacked ? "No staged revision" : "Local editing only"}
              </button>
            )}
            {isSessionBacked && selectedDocument.currentRevisionId ? (
              <button type="button" className="action-button" onClick={() => void rollbackCurrentRevision()}>
                <Undo2 size={14} /> Roll back
              </button>
            ) : null}
            {selectedDocument.status === "ready" ? (
              <button type="button" className={cn("action-button", !isSessionBacked ? "opacity-60" : "")} onClick={() => void exportDocument()} disabled={!isSessionBacked}>
                <Download size={14} /> Export DOCX
              </button>
            ) : null}
          </div>
        </div>
      </div>

      {selectedDocument.status !== "ready" ? (
        <div className="flex flex-1 items-center justify-center p-10">
          <div className="panel-card max-w-lg p-8 text-left shadow-glow">
            <div className="mb-4 flex items-center gap-3 text-docpilot-warning">
              <FileCog size={18} />
              <p className="font-medium text-docpilot-textStrong">Backend import required</p>
            </div>
            <p className="leading-7 text-docpilot-muted">
              This file format cannot be rendered client-side without inventing a fake preview. Import it through the
              backend so the editor can attach a canonical document session and revision history.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="border-b border-docpilot-border px-6 py-3 text-sm text-docpilot-muted">
            <div className="flex flex-wrap items-center gap-4">
              <span className="inline-flex items-center gap-2">
                {isSessionBacked ? <Lock size={14} /> : <RefreshCw size={14} />}
                {isSessionBacked ? "read-only DOCX fidelity surface" : "autosave on blur"}
              </span>
              {hasPendingRevision ? (
                <span className="inline-flex items-center gap-2 text-docpilot-warning">
                  <Undo2 size={14} /> review mode is non-destructive until you apply it
                </span>
              ) : isSessionBacked ? (
                <span className="inline-flex items-center gap-2 text-docpilot-muted">
                  <Sparkles size={14} /> use the assistant to stage structured revisions
                </span>
              ) : null}
            </div>
          </div>

          <div className="scrollbar-thin flex-1 overflow-auto px-10 py-10">
            <DocumentCanvas
              html={selectedDocument.html}
              editable={isEditable}
              variant={isSessionBacked ? "fidelity" : "editable"}
              onCommit={updateSelectedDocumentHtml}
            />
          </div>
        </>
      )}
    </div>
  );
}