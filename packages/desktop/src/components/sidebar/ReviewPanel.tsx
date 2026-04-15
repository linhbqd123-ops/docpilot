import { AlertTriangle, Check, ClipboardCheck, GitCommitHorizontal, Undo2, X } from "lucide-react";

import { useAppContext } from "@/app/context";

export function ReviewPanel() {
  const { selectedDocument, applyStagedRevision, rejectStagedRevision, rollbackCurrentRevision } = useAppContext();
  const review = selectedDocument?.reviewPayload ?? null;
  const hasPendingRevision = Boolean(selectedDocument?.pendingRevisionId);

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

        {selectedDocument && !selectedDocument.documentSessionId ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            Local HTML, Markdown, and TXT files do not create canonical revisions. Import a DOCX through the backend to
            use the MCP review, apply, and rollback flow.
          </div>
        ) : null}

        {selectedDocument?.documentSessionId && !hasPendingRevision ? (
          <div className="space-y-4">
            <div className="panel-card p-4">
              <div className="mb-3 flex items-center gap-2 text-docpilot-textStrong">
                <ClipboardCheck size={16} /> No pending revision
              </div>
              <p className="text-sm leading-6 text-docpilot-muted">
                The current canonical revision is active. Ask the assistant for a change to stage a reviewable patch, or
                roll back the current revision if you need to undo the last applied change.
              </p>
              <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Current revision</p>
                  <p className="mt-1 break-all text-sm font-semibold text-docpilot-textStrong">
                    {selectedDocument.currentRevisionId ?? "Not available"}
                  </p>
                </div>
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Session state</p>
                  <p className="mt-1 text-xl font-semibold text-docpilot-textStrong">
                    {selectedDocument.sessionState ?? "READY"}
                  </p>
                </div>
              </div>
            </div>

            {selectedDocument.currentRevisionId ? (
              <button type="button" className="action-button" onClick={() => void rollbackCurrentRevision()}>
                <Undo2 size={16} /> Roll back current revision
              </button>
            ) : null}

            {selectedDocument.revisions.length > 0 ? (
              <div className="panel-card p-4">
                <div className="mb-3 flex items-center gap-2 text-docpilot-textStrong">
                  <GitCommitHorizontal size={16} /> Revision history
                </div>
                <div className="space-y-3 text-sm">
                  {selectedDocument.revisions.slice(0, 6).map((revision) => (
                    <div key={revision.revisionId} className="subtle-card p-3">
                      <div className="flex items-center justify-between gap-3">
                        <span className="font-medium text-docpilot-textStrong">{revision.status}</span>
                        <span className="text-xs text-docpilot-muted">{revision.scope ?? "document"}</span>
                      </div>
                      <p className="mt-2 break-all text-xs text-docpilot-muted">{revision.revisionId}</p>
                      {revision.summary ? <p className="mt-2 text-docpilot-text">{revision.summary}</p> : null}
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}

        {selectedDocument?.documentSessionId && hasPendingRevision && review ? (
          <div className="space-y-4">
            <div className="panel-card p-4">
              <p className="text-sm font-medium text-docpilot-textStrong">Revision ready</p>
              <p className="mt-2 text-sm leading-6 text-docpilot-muted">
                A canonical revision has been staged for <span className="text-docpilot-text">{selectedDocument.name}</span>.
                Review the summary and operations below, then apply or reject it.
              </p>
              <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Revision ID</p>
                  <p className="mt-1 break-all text-sm font-semibold text-docpilot-textStrong">{review.revisionId}</p>
                </div>
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Operations</p>
                  <p className="mt-1 text-xl font-semibold text-docpilot-textStrong">{review.operationCount}</p>
                </div>
              </div>

              <div className="mt-4 space-y-3 text-sm">
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Summary</p>
                  <p className="mt-1 text-docpilot-textStrong">{review.summary || "No summary returned."}</p>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="subtle-card p-3">
                    <p className="text-docpilot-muted">Scope</p>
                    <p className="mt-1 text-docpilot-textStrong">{review.scope}</p>
                  </div>
                  <div className="subtle-card p-3">
                    <p className="text-docpilot-muted">Author</p>
                    <p className="mt-1 text-docpilot-textStrong">{review.author || "assistant"}</p>
                  </div>
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 gap-3">
              <button type="button" className="action-button-primary" onClick={() => void applyStagedRevision()}>
                <Check size={16} /> Apply revision
              </button>
              <button type="button" className="action-button" onClick={() => void rejectStagedRevision()}>
                <X size={16} /> Reject revision
              </button>
              <div className="subtle-card p-3 text-sm text-docpilot-muted">
                <div className="mb-2 flex items-center gap-2 text-docpilot-text">
                  <Undo2 size={14} /> Safe review flow
                </div>
                The original document remains untouched until you explicitly apply the revision.
              </div>
            </div>

            {review.validation ? (
              <div className="panel-card p-4">
                <div className="mb-3 flex items-center gap-2 text-docpilot-textStrong">
                  <AlertTriangle size={16} /> Validation
                </div>
                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div className="subtle-card p-3">
                    <p className="text-docpilot-muted">Structure</p>
                    <p className="mt-1 font-semibold text-docpilot-textStrong">
                      {review.validation.structureOk ? "OK" : "Needs attention"}
                    </p>
                  </div>
                  <div className="subtle-card p-3">
                    <p className="text-docpilot-muted">Style</p>
                    <p className="mt-1 font-semibold text-docpilot-textStrong">
                      {review.validation.styleOk ? "OK" : "Needs attention"}
                    </p>
                  </div>
                </div>
                {review.validation.errors.length > 0 ? (
                  <div className="mt-4">
                    <p className="text-xs uppercase tracking-[0.18em] text-docpilot-dangerText">Errors</p>
                    <ul className="mt-2 space-y-2 text-sm text-docpilot-dangerText">
                      {review.validation.errors.map((item) => (
                        <li key={item} className="subtle-card p-3">{item}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
                {review.validation.warnings.length > 0 ? (
                  <div className="mt-4">
                    <p className="text-xs uppercase tracking-[0.18em] text-docpilot-warningText">Warnings</p>
                    <ul className="mt-2 space-y-2 text-sm text-docpilot-warningText">
                      {review.validation.warnings.map((item) => (
                        <li key={item} className="subtle-card p-3">{item}</li>
                      ))}
                    </ul>
                  </div>
                ) : null}
              </div>
            ) : null}

            <div className="panel-card p-4">
              <div className="mb-3 flex items-center gap-2 text-docpilot-textStrong">
                <GitCommitHorizontal size={16} /> Planned operations
              </div>
              <div className="space-y-3 text-sm">
                {review.operations.length > 0 ? review.operations.map((operation, index) => (
                  <div key={`${operation.op}-${operation.blockId ?? index}`} className="subtle-card p-3">
                    <div className="flex items-center justify-between gap-3">
                      <span className="font-medium text-docpilot-textStrong">{operation.op}</span>
                      {operation.blockId ? <span className="text-xs text-docpilot-muted">{operation.blockId}</span> : null}
                    </div>
                    <p className="mt-2 text-docpilot-text">{operation.description || "No description provided."}</p>
                  </div>
                )) : (
                  <div className="subtle-card p-3 text-docpilot-muted">No operation list returned for this revision.</div>
                )}
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}