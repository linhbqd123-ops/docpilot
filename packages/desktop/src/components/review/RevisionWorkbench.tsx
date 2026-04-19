import { AlertTriangle, ArrowRight, Check, Sparkles, X } from "lucide-react";

import type { RevisionReview } from "@/app/types";
import type { ReviewChangeGroup, ReviewChangeKind } from "@/lib/review";
import { cn } from "@/lib/utils";
import { formatOperationLabel } from "@/lib/review";

interface RevisionWorkbenchProps {
  review: RevisionReview;
  changes: ReviewChangeGroup[];
  focusedChangeId: string | null;
  /** IDs of change groups the user has explicitly rejected (all others are accepted). */
  rejectedChangeIds: Set<string>;
  onSelectChange: (change: ReviewChangeGroup) => void;
  onAcceptChange: (changeId: string) => void;
  onRejectChange: (changeId: string) => void;
}

function kindLabel(kind: ReviewChangeKind) {
  switch (kind) {
    case "text":
      return "Text preview";
    case "style":
      return "Style diff";
    case "structure":
      return "Structure diff";
    case "mixed":
      return "Mixed change";
    default:
      return "Review item";
  }
}

function kindBadgeClass(kind: ReviewChangeKind) {
  switch (kind) {
    case "text":
      return "border-docpilot-success/30 bg-docpilot-success/10 text-docpilot-success";
    case "style":
      return "border-docpilot-accent/30 bg-docpilot-accentSoft text-docpilot-accent";
    case "structure":
      return "border-docpilot-warning/30 bg-docpilot-warningSoft text-docpilot-warningText";
    case "mixed":
      return "border-docpilot-border bg-docpilot-panelAlt text-docpilot-textStrong";
    default:
      return "border-docpilot-border bg-docpilot-panelAlt text-docpilot-muted";
  }
}

export function RevisionWorkbench({ review, changes, focusedChangeId, rejectedChangeIds, onSelectChange, onAcceptChange, onRejectChange }: RevisionWorkbenchProps) {
  const activeValidation = review.preview?.validation ?? review.validation;
  const validationErrors = activeValidation?.errors ?? [];
  const validationWarnings = activeValidation?.warnings ?? [];
  const previewMessage = !review.preview?.available ? review.preview?.message?.trim() ?? "" : "";

  return (
    <div className="panel-card flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <div className="border-b border-docpilot-border px-5 py-4">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Review Mode</p>
            <h2 className="mt-1 text-lg font-semibold text-docpilot-textStrong">Staged document changes</h2>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-docpilot-muted">
              {review.summary || "Review the staged document update before you apply it to the fidelity-backed session."}
            </p>
          </div>

          <div className="flex flex-wrap gap-2 text-xs">
            <span className="badge">{changes.length} change{changes.length === 1 ? "" : "s"}</span>
            <span className="badge">{review.operationCount} op{review.operationCount === 1 ? "" : "s"}</span>
            <span className="badge">{review.scope}</span>
          </div>
        </div>

        {(previewMessage || validationErrors.length > 0 || validationWarnings.length > 0) ? (
          <div className="mt-4 flex flex-wrap gap-3 text-sm">
            {previewMessage ? (
              <div className="inline-flex items-center gap-2 rounded-2xl border border-docpilot-warning/30 bg-docpilot-warningSoft px-3 py-2 text-docpilot-warningText">
                <AlertTriangle size={15} /> {previewMessage}
              </div>
            ) : null}
            {validationErrors.length > 0 ? (
              <div className="inline-flex items-center gap-2 rounded-2xl border border-docpilot-danger/30 bg-docpilot-dangerSoft px-3 py-2 text-docpilot-dangerText">
                <AlertTriangle size={15} /> {validationErrors.length} validation error{validationErrors.length === 1 ? "" : "s"}
              </div>
            ) : null}
            {validationWarnings.length > 0 ? (
              <div className="inline-flex items-center gap-2 rounded-2xl border border-docpilot-warning/30 bg-docpilot-warningSoft px-3 py-2 text-docpilot-warningText">
                <AlertTriangle size={15} /> {validationWarnings.length} warning{validationWarnings.length === 1 ? "" : "s"}
              </div>
            ) : null}
          </div>
        ) : null}
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto overflow-x-hidden p-4">
        {changes.length > 0 ? (
          <div className="space-y-3">
            {changes.map((change) => {
              const operationLabels = [...new Set(change.operations.map((operation) => formatOperationLabel(operation.op)).filter(Boolean))];
              const isRejected = rejectedChangeIds.has(change.id);
              const previewNote = change.previewSource === "backend"
                ? "Preview is computed from a backend-generated pending revision projection."
                : change.previewSource === "simulated"
                  ? "Preview is simulated from the current fidelity block and staged text operations."
                  : "Exact preview is unavailable for this item. Use the highlighted document block below as the final review surface.";

              return (
                <button
                  key={change.id}
                  type="button"
                  className={cn(
                    "w-full max-w-full overflow-hidden rounded-[26px] border p-4 text-left transition duration-150 hover:-translate-y-px",
                    focusedChangeId === change.id && !isRejected
                      ? "border-docpilot-accent/40 bg-docpilot-accentSoft/40 shadow-active hover:border-docpilot-accent/30 hover:bg-docpilot-hover/40"
                      : isRejected
                        ? "border-red-500/20 bg-red-900/10 opacity-60 hover:border-red-500/30 hover:bg-red-900/15"
                        : "border-docpilot-border bg-docpilot-panelAlt/70 hover:border-docpilot-accent/30 hover:bg-docpilot-hover/40",
                  )}
                  onClick={() => onSelectChange(change)}
                >
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="text-base font-semibold text-docpilot-textStrong">{change.title}</p>
                        <span className={cn("rounded-full border px-2.5 py-1 text-[11px] font-medium", kindBadgeClass(change.kind))}>
                          {kindLabel(change.kind)}
                        </span>
                      </div>
                      <p className="mt-1 break-words text-xs leading-5 text-docpilot-muted">{change.subtitle}</p>
                      {change.details.length > 1 ? (
                        <p className="mt-2 break-words text-xs leading-5 text-docpilot-muted">{change.details.slice(1, 3).join(" · ")}</p>
                      ) : null}
                    </div>

                    <div className="flex flex-wrap items-center gap-2">
                      {operationLabels.map((label) => (
                        <span key={`${change.id}-${label}`} className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] text-docpilot-muted">
                          {label}
                        </span>
                      ))}
                      {/* Per-change accept/reject toggle */}
                      <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                        <button
                          type="button"
                          title="Accept this change"
                          className={cn(
                            "inline-flex items-center justify-center rounded-full p-1.5 transition",
                            !isRejected
                              ? "bg-emerald-600/20 text-emerald-400 hover:bg-emerald-600/30"
                              : "text-docpilot-muted hover:bg-docpilot-hover hover:text-emerald-400",
                          )}
                          onClick={() => onAcceptChange(change.id)}
                        >
                          <Check size={13} />
                        </button>
                        <button
                          type="button"
                          title="Reject this change"
                          className={cn(
                            "inline-flex items-center justify-center rounded-full p-1.5 transition",
                            isRejected
                              ? "bg-red-600/20 text-red-400 hover:bg-red-600/30"
                              : "text-docpilot-muted hover:bg-docpilot-hover hover:text-red-400",
                          )}
                          onClick={() => onRejectChange(change.id)}
                        >
                          <X size={13} />
                        </button>
                      </div>
                    </div>
                  </div>

                  {(change.beforeText || change.afterText) ? (
                    <div className="mt-4 grid gap-3 lg:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
                      <div className="rounded-2xl border border-docpilot-danger/20 bg-docpilot-dangerSoft/30 px-3 py-3">
                        <p className="text-[11px] uppercase tracking-[0.18em] text-docpilot-dangerText">Current</p>
                        <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-docpilot-textStrong">
                          {change.beforeText || "No current text preview available for this block."}
                        </p>
                      </div>

                      <div className="hidden items-center justify-center text-docpilot-muted lg:flex">
                        <ArrowRight size={16} />
                      </div>

                      <div className="rounded-2xl border border-docpilot-success/20 bg-docpilot-success/10 px-3 py-3">
                        <p className="text-[11px] uppercase tracking-[0.18em] text-docpilot-success">Staged</p>
                        <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-docpilot-textStrong">
                          {change.afterText || "No staged text preview available for this item."}
                        </p>
                      </div>
                    </div>
                  ) : null}

                  <div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-xs text-docpilot-muted">
                    <span>{previewNote}</span>
                    <span className="inline-flex items-center gap-1 text-docpilot-textStrong">
                      <Sparkles size={13} /> Review in document
                    </span>
                  </div>
                </button>
              );
            })}
          </div>
        ) : (
          <div className="subtle-card p-4 text-sm text-docpilot-muted">
            This revision is staged, but the backend did not return a block-level preview list. Apply it only if the summary and validation status look correct.
          </div>
        )}
      </div>
    </div>
  );
}