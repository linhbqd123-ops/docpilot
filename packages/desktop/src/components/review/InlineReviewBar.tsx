import { Check, ChevronDown, ChevronUp, X } from "lucide-react";

import type { ReviewChangeGroup } from "@/lib/review";
import { cn } from "@/lib/utils";

interface InlineReviewBarProps {
  changes: ReviewChangeGroup[];
  focusedChangeId: string | null;
  canApply: boolean;
  /** IDs of change groups the user has explicitly rejected (all others are accepted). */
  rejectedChangeIds: Set<string>;
  onApply: () => void;
  onReject: () => void;
  /** Accept (un-reject) the specified change group. */
  onAcceptChange: (changeId: string) => void;
  /** Mark the specified change group as rejected. */
  onRejectChange: (changeId: string) => void;
  onSelectChange: (change: ReviewChangeGroup) => void;
}

export function InlineReviewBar({
  changes,
  focusedChangeId,
  canApply,
  rejectedChangeIds,
  onApply,
  onReject,
  onAcceptChange,
  onRejectChange,
  onSelectChange,
}: InlineReviewBarProps) {
  const currentIndex = focusedChangeId
    ? changes.findIndex((c) => c.id === focusedChangeId)
    : -1;

  const focusedChange = currentIndex >= 0 ? changes[currentIndex] : null;
  const focusedIsRejected = focusedChange ? rejectedChangeIds.has(focusedChange.id) : false;

  const acceptedCount = changes.filter((c) => !rejectedChangeIds.has(c.id)).length;
  const hasPartialSelection = rejectedChangeIds.size > 0 && rejectedChangeIds.size < changes.length;
  const allRejected = changes.length > 0 && rejectedChangeIds.size === changes.length;

  function navigateTo(direction: "prev" | "next") {
    if (changes.length === 0) return;
    let nextIndex: number;
    if (currentIndex < 0) {
      nextIndex = 0;
    } else if (direction === "prev") {
      nextIndex = currentIndex <= 0 ? changes.length - 1 : currentIndex - 1;
    } else {
      nextIndex = currentIndex >= changes.length - 1 ? 0 : currentIndex + 1;
    }
    onSelectChange(changes[nextIndex]);
  }

  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-30 flex justify-center px-6 pb-5">
      <div
        className={cn(
          "pointer-events-auto inline-flex items-center gap-1 rounded-lg border border-docpilot-border",
          "bg-docpilot-panel/95 px-1.5 py-1 text-sm shadow-lg backdrop-blur-md",
        )}
      >
        {/* ── Per-change accept/reject (shown when a change is focused) ── */}
        {focusedChange && (
          <>
            <button
              type="button"
              className={cn(
                "inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition",
                focusedIsRejected
                  ? "text-docpilot-muted hover:bg-docpilot-hover hover:text-docpilot-text"
                  : "bg-emerald-600/20 text-emerald-400 hover:bg-emerald-600/30",
              )}
              onClick={() => onAcceptChange(focusedChange.id)}
              title="Accept this change"
            >
              <Check size={13} />
              {focusedIsRejected ? "Keep" : "Accepted"}
            </button>

            <button
              type="button"
              className={cn(
                "inline-flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-xs font-medium transition",
                focusedIsRejected
                  ? "bg-red-600/20 text-red-400 hover:bg-red-600/30"
                  : "text-docpilot-muted hover:bg-docpilot-hover hover:text-docpilot-text",
              )}
              onClick={() => onRejectChange(focusedChange.id)}
              title="Reject this change"
            >
              <X size={13} />
              {focusedIsRejected ? "Rejected" : "Reject"}
            </button>

            <div className="mx-0.5 h-4 w-px bg-docpilot-border" />
          </>
        )}

        {/* ── Change counter + navigation ── */}
        <span className="px-1.5 text-xs tabular-nums text-docpilot-muted">
          {currentIndex >= 0 ? currentIndex + 1 : 0} of {changes.length}
        </span>

        <button
          type="button"
          className="inline-flex items-center justify-center rounded-md p-1 text-docpilot-muted transition hover:bg-docpilot-hover hover:text-docpilot-text disabled:opacity-40"
          onClick={() => navigateTo("prev")}
          disabled={changes.length === 0}
          title="Previous change"
        >
          <ChevronUp size={14} />
        </button>

        <button
          type="button"
          className="inline-flex items-center justify-center rounded-md p-1 text-docpilot-muted transition hover:bg-docpilot-hover hover:text-docpilot-text disabled:opacity-40"
          onClick={() => navigateTo("next")}
          disabled={changes.length === 0}
          title="Next change"
        >
          <ChevronDown size={14} />
        </button>

        <div className="mx-0.5 h-4 w-px bg-docpilot-border" />

        {/* ── Bulk actions ── */}
        {!allRejected && (
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md bg-docpilot-accent px-3 py-1.5 text-xs font-medium text-docpilot-accentContrast transition hover:bg-docpilot-accentHover disabled:cursor-not-allowed disabled:opacity-50"
            onClick={onApply}
            disabled={!canApply}
            title={
              hasPartialSelection
                ? `Apply ${acceptedCount} accepted change${acceptedCount !== 1 ? "s" : ""}`
                : "Accept all changes"
            }
          >
            <Check size={14} />
            {hasPartialSelection ? `Apply ${acceptedCount}` : "Accept All"}
          </button>
        )}

        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-medium text-docpilot-text transition hover:bg-docpilot-hover"
          onClick={onReject}
          title="Discard all changes"
        >
          <X size={14} /> {allRejected ? "Discard All" : "Discard"}
        </button>
      </div>
    </div>
  );
}
