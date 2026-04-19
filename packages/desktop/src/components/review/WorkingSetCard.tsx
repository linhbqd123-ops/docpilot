import { AlertTriangle, Check, GitCompareArrows, X } from "lucide-react";

import type { RevisionReview } from "@/app/types";
import type { ReviewChangeGroup } from "@/lib/review";
import { cn } from "@/lib/utils";
import { formatOperationLabel } from "@/lib/review";

interface WorkingSetCardProps {
  documentName: string;
  review: RevisionReview;
  changes: ReviewChangeGroup[];
  focusedChangeId: string | null;
  canApply: boolean;
  variant?: "panel" | "embedded";
  onApply: () => void;
  onReject: () => void;
  onSelectChange: (change: ReviewChangeGroup) => void;
  onOpenTimeline: () => void;
}

export function WorkingSetCard({
  documentName,
  review,
  changes,
  focusedChangeId,
  canApply,
  variant = "panel",
  onApply,
  onReject,
  onSelectChange,
  onOpenTimeline,
}: WorkingSetCardProps) {
  const activeValidation = review.preview?.validation ?? review.validation;
  const validationErrors = activeValidation?.errors ?? [];
  const validationWarnings = activeValidation?.warnings ?? [];
  const previewMessage = !review.preview?.available ? review.preview?.message?.trim() ?? "" : "";
  const isEmbedded = variant === "embedded";

  return (
    <div className={cn(isEmbedded ? "rounded-[24px] border border-docpilot-border/80 bg-docpilot-surface p-4" : "panel-card p-4 shadow-glow")}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">{isEmbedded ? "Revision Card" : "Working Set"}</p>
          <h3 className="mt-1 text-base font-semibold text-docpilot-textStrong">{isEmbedded ? "Proposed document revision" : "Staged document revision"}</h3>
          <p className="mt-2 text-sm leading-6 text-docpilot-muted">
            {review.summary || "Review the staged changes, then apply or discard them."}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="action-button-primary" onClick={onApply} disabled={!canApply}>
            <Check size={14} /> Apply
          </button>
          <button type="button" className="action-button" onClick={onReject}>
            <X size={14} /> Discard
          </button>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2 text-xs text-docpilot-muted">
        <span className="badge">{documentName}</span>
        <span className="badge">{changes.length} change{changes.length === 1 ? "" : "s"}</span>
        <span className="badge">{review.operationCount} op{review.operationCount === 1 ? "" : "s"}</span>
        <button type="button" className="action-button h-8 px-3 py-1.5 text-[11px]" onClick={onOpenTimeline}>
          <GitCompareArrows size={13} /> Timeline
        </button>
      </div>

      {(previewMessage || validationErrors.length > 0 || validationWarnings.length > 0) ? (
        <div className="mt-4 space-y-2 text-xs">
          {previewMessage ? (
            <div className="rounded-2xl border border-docpilot-warning/30 bg-docpilot-warningSoft px-3 py-2 text-docpilot-warningText">
              <div className="flex items-start gap-2">
                <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                <span>{previewMessage}</span>
              </div>
            </div>
          ) : null}
          {validationErrors.length > 0 ? (
            <div className="rounded-2xl border border-docpilot-danger/30 bg-docpilot-dangerSoft px-3 py-2 text-docpilot-dangerText">
              <div className="flex items-start gap-2">
                <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                <span>{validationErrors.join(" ")}</span>
              </div>
            </div>
          ) : null}
          {validationWarnings.length > 0 ? (
            <div className="rounded-2xl border border-docpilot-warning/30 bg-docpilot-warningSoft px-3 py-2 text-docpilot-warningText">
              <div className="flex items-start gap-2">
                <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                <span>{validationWarnings.join(" ")}</span>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}

      <div className="mt-4 space-y-2">
        {changes.length > 0 ? changes.map((change) => {
          const summary = change.details[0] || change.operations[0]?.description || change.afterText || change.subtitle;
          const operationLabels = [...new Set(change.operations.map((operation) => formatOperationLabel(operation.op)))];

          return (
            <button
              key={change.id}
              type="button"
              className={cn(
                "w-full rounded-2xl border px-3 py-3 text-left transition duration-150 hover:-translate-y-px hover:border-docpilot-accent/30 hover:bg-docpilot-hover/40",
                focusedChangeId === change.id
                  ? "border-docpilot-accent/40 bg-docpilot-accentSoft/40 shadow-active"
                  : "border-docpilot-border bg-docpilot-panelAlt/70",
              )}
              onClick={() => onSelectChange(change)}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium text-docpilot-textStrong">{change.title}</p>
                  <p className="mt-1 line-clamp-2 text-xs leading-5 text-docpilot-muted">{summary}</p>
                  {change.details.length > 1 ? (
                    <p className="mt-1 line-clamp-1 text-[11px] text-docpilot-muted">{change.details.slice(1, 3).join(" · ")}</p>
                  ) : null}
                </div>
                <span className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] text-docpilot-muted">
                  {change.operations.length || 1}
                </span>
              </div>

              {operationLabels.length > 0 ? (
                <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-docpilot-muted">
                  {operationLabels.map((label) => (
                    <span key={`${change.id}-${label}`} className="rounded-full border border-docpilot-border bg-docpilot-surface px-2 py-1">
                      {label}
                    </span>
                  ))}
                </div>
              ) : null}
            </button>
          );
        }) : (
          <div className="subtle-card p-3 text-sm text-docpilot-muted">
            No block-level working set details were returned for this staged revision.
          </div>
        )}
      </div>
    </div>
  );
}