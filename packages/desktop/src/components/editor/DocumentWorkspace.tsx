import { useEffect, useState } from "react";
import { Check, Eye, FileCog, PenSquare, RefreshCw, Sparkles, Undo2, X } from "lucide-react";

import { useAppContext } from "@/app/context";
import { DocumentCanvas } from "@/components/editor/DocumentCanvas";
import { cn } from "@/lib/utils";

export function DocumentWorkspace() {
  const {
    selectedDocument,
    updateSelectedDocumentHtml,
    acceptPendingChanges,
    discardPendingChanges,
  } = useAppContext();
  const [previewPending, setPreviewPending] = useState(false);

  useEffect(() => {
    if (!selectedDocument?.pendingHtml) {
      setPreviewPending(false);
    }
  }, [selectedDocument?.id, selectedDocument?.pendingHtml]);

  if (!selectedDocument) {
    return (
      <div className="flex h-full items-center justify-center bg-gradient-to-b from-transparent to-docpilot-panelAlt p-10">
        <div className="panel-card max-w-xl p-10 text-center shadow-glow">
          <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Workspace</p>
          <h2 className="mt-3 font-display text-4xl text-docpilot-textStrong">Document-native AI editing starts here</h2>
          <p className="mt-4 text-base leading-8 text-docpilot-muted">
            Import a document from the Library panel to open the editor surface. Text-based formats render locally.
            DOCX and PDF stay in the workflow without fake preview until your backend import pipeline is ready.
          </p>
        </div>
      </div>
    );
  }

  const currentHtml = previewPending && selectedDocument.pendingHtml
    ? selectedDocument.pendingHtml
    : selectedDocument.html;
  const currentWordCount = previewPending && selectedDocument.pendingWordCount !== undefined
    ? selectedDocument.pendingWordCount
    : selectedDocument.wordCount;

  return (
    <div className="flex h-full min-h-0 flex-col bg-gradient-to-b from-transparent to-docpilot-panelAlt">
      <div className="border-b border-docpilot-border px-6 py-4">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <span className="badge">{selectedDocument.kind}</span>
              <span className="badge">{selectedDocument.status === "ready" ? "editable" : "backend import"}</span>
              {selectedDocument.pendingHtml ? <span className="badge">revision staged</span> : null}
            </div>
            <h1 className="mt-3 text-2xl font-semibold text-docpilot-textStrong">{selectedDocument.name}</h1>
            <p className="mt-1 text-sm text-docpilot-muted">
              {currentWordCount} words · direct manual edits are autosaved on blur.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {selectedDocument.pendingHtml ? (
              <>
                <button
                  type="button"
                  className={cn("action-button", !previewPending ? "border-docpilot-accent/40 bg-docpilot-accentSoft shadow-active" : "")}
                  onClick={() => setPreviewPending(false)}
                >
                  <PenSquare size={14} /> Current
                </button>
                <button
                  type="button"
                  className={cn("action-button", previewPending ? "border-docpilot-accent/40 bg-docpilot-accentSoft shadow-active" : "")}
                  onClick={() => setPreviewPending(true)}
                >
                  <Eye size={14} /> Revision
                </button>
                <button type="button" className="action-button-primary" onClick={acceptPendingChanges}>
                  <Check size={14} /> Apply
                </button>
                <button type="button" className="action-button" onClick={discardPendingChanges}>
                  <X size={14} /> Reject
                </button>
              </>
            ) : (
              <button type="button" className="action-button" disabled>
                <Sparkles size={14} /> Waiting for backend revision
              </button>
            )}
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
              This file format cannot be rendered client-side without inventing a fake preview. Connect your backend and
              implement `/api/documents/import` to convert it into reviewable HTML.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="border-b border-docpilot-border px-6 py-3 text-sm text-docpilot-muted">
            <div className="flex flex-wrap items-center gap-4">
              <span className="inline-flex items-center gap-2">
                <RefreshCw size={14} /> autosave on blur
              </span>
              {selectedDocument.pendingHtml ? (
                <span className="inline-flex items-center gap-2 text-docpilot-warning">
                  <Undo2 size={14} /> review mode is non-destructive until you apply it
                </span>
              ) : null}
            </div>
          </div>

          <div className="scrollbar-thin flex-1 overflow-auto px-10 py-10">
            <DocumentCanvas
              html={currentHtml}
              editable={!previewPending}
              onCommit={updateSelectedDocumentHtml}
            />
          </div>
        </>
      )}
    </div>
  );
}