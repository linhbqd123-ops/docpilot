import { Check, Undo2, X } from "lucide-react";

import { useAppContext } from "@/app/context";

export function ReviewPanel() {
  const { selectedDocument, acceptPendingChanges, discardPendingChanges } = useAppContext();

  const deltaWords =
    selectedDocument?.pendingWordCount !== undefined
      ? selectedDocument.pendingWordCount - selectedDocument.wordCount
      : 0;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Review</p>
        <h2 className="panel-title">Pending revision</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto p-4">
        {!selectedDocument ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">Select a document to review staged revisions.</div>
        ) : null}

        {selectedDocument && !selectedDocument.pendingHtml ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            The review queue is empty. When the backend returns a revised HTML document, it will appear here before being applied.
          </div>
        ) : null}

        {selectedDocument?.pendingHtml ? (
          <div className="space-y-4">
            <div className="panel-card p-4">
              <p className="text-sm font-medium text-docpilot-textStrong">Revision ready</p>
              <p className="mt-2 text-sm leading-6 text-docpilot-muted">
                A new HTML revision has been staged for <span className="text-docpilot-text">{selectedDocument.name}</span>.
                Review it in the workspace, then accept or discard it here.
              </p>
              <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Current words</p>
                  <p className="mt-1 text-xl font-semibold text-docpilot-textStrong">{selectedDocument.wordCount}</p>
                </div>
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Revision delta</p>
                  <p className="mt-1 text-xl font-semibold text-docpilot-textStrong">
                    {deltaWords > 0 ? `+${deltaWords}` : deltaWords}
                  </p>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 gap-3">
              <button type="button" className="action-button-primary" onClick={acceptPendingChanges}>
                <Check size={16} /> Apply revision
              </button>
              <button type="button" className="action-button" onClick={discardPendingChanges}>
                <X size={16} /> Reject revision
              </button>
              <div className="subtle-card p-3 text-sm text-docpilot-muted">
                <div className="mb-2 flex items-center gap-2 text-docpilot-text">
                  <Undo2 size={14} /> Safe review flow
                </div>
                The original document remains untouched until you explicitly apply the revision.
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}