import { AlertTriangle, Check, ClipboardCheck, GitCommitHorizontal, History, Redo2, Undo2, X } from "lucide-react";

import type { Chat, ChatMessage, SessionRevisionSummary } from "@/app/types";
import { useAppContext } from "@/app/context";
import { formatRelativeTime } from "@/lib/utils";

interface LinkedRevisionSource {
  revisionId: string;
  chatId: string;
  chatName: string;
  messageId: string;
  messageCreatedAt: number;
  promptPreview: string;
  assistantPreview: string;
}

interface TimelineEntry {
  revision: SessionRevisionSummary;
  source: LinkedRevisionSource;
}

function compactPreview(text: string, maxChars = 96) {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return "";
  }
  if (normalized.length <= maxChars) {
    return normalized;
  }
  return `${normalized.slice(0, Math.max(0, maxChars - 3)).trimEnd()}...`;
}

function findPromptPreview(messages: ChatMessage[], assistantIndex: number) {
  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    if (messages[index].role === "user") {
      return compactPreview(messages[index].content, 84);
    }
  }
  return "";
}

function collectLinkedRevisionSources(chats: Chat[], sessionId: string | undefined) {
  const sources = new Map<string, LinkedRevisionSource>();

  chats.forEach((chat) => {
    chat.messages.forEach((message, index) => {
      const linkedRevision = message.linkedRevision;
      if (!linkedRevision?.revisionId) {
        return;
      }
      if (sessionId && linkedRevision.documentSessionId && linkedRevision.documentSessionId !== sessionId) {
        return;
      }

      const existing = sources.get(linkedRevision.revisionId);
      if (existing && existing.messageCreatedAt >= message.createdAt) {
        return;
      }

      sources.set(linkedRevision.revisionId, {
        revisionId: linkedRevision.revisionId,
        chatId: chat.id,
        chatName: chat.name,
        messageId: message.id,
        messageCreatedAt: message.createdAt,
        promptPreview: findPromptPreview(chat.messages, index),
        assistantPreview: compactPreview(message.content, 108),
      });
    });
  });

  return sources;
}

function revisionKind(revision: SessionRevisionSummary, currentRevisionId?: string | null) {
  const status = revision.status.toUpperCase();

  if (revision.revisionId === currentRevisionId) {
    return "current";
  }
  if (status === "APPLIED") {
    return "applied";
  }
  if (status === "REJECTED" && revision.appliedAt) {
    return "rolled_back";
  }
  if (status === "REJECTED") {
    return "rejected";
  }
  if (status === "CONFLICT") {
    return "conflict";
  }
  return "draft";
}

function revisionLabel(revision: SessionRevisionSummary, currentRevisionId?: string | null) {
  switch (revisionKind(revision, currentRevisionId)) {
    case "current":
      return "Current checkpoint";
    case "applied":
      return "Applied checkpoint";
    case "rolled_back":
      return "Rolled back";
    case "rejected":
      return "Rejected draft";
    case "conflict":
      return "Conflict";
    default:
      return "Pending draft";
  }
}

function revisionBadgeClass(revision: SessionRevisionSummary, currentRevisionId?: string | null) {
  switch (revisionKind(revision, currentRevisionId)) {
    case "current":
      return "border-docpilot-accent/30 bg-docpilot-accentSoft text-docpilot-accent";
    case "applied":
      return "border-docpilot-success/30 bg-docpilot-success/10 text-docpilot-success";
    case "rolled_back":
      return "border-docpilot-warning/30 bg-docpilot-warningSoft text-docpilot-warningText";
    case "rejected":
    case "conflict":
      return "border-docpilot-danger/30 bg-docpilot-dangerSoft text-docpilot-dangerText";
    default:
      return "border-docpilot-border bg-docpilot-surface text-docpilot-muted";
  }
}

function canRestoreCheckpoint(revision: SessionRevisionSummary, currentRevisionId?: string | null) {
  if (revision.revisionId === currentRevisionId) {
    return false;
  }
  return Boolean(revision.appliedAt);
}

function normalizeRevisionId(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}

export function ReviewPanel() {
  const {
    selectedDocument,
    selectedReview,
    selectedChat,
    applyStagedRevision,
    rejectStagedRevision,
    rollbackCurrentRevision,
    restoreRevisionCheckpoint,
    focusChatMessage,
  } = useAppContext();
  const review = selectedReview ?? null;
  const selectedChatList = selectedChat ? [selectedChat] : [];
  const linkedSources = collectLinkedRevisionSources(selectedChatList, selectedDocument?.documentSessionId);
  const pendingSource = selectedDocument?.pendingRevisionId
    ? linkedSources.get(selectedDocument.pendingRevisionId) ?? null
    : null;
  const hasPendingRevision = Boolean(selectedDocument?.pendingRevisionId && pendingSource);
  const pendingStatus = (review?.status ?? selectedDocument?.revisionStatus ?? "").toUpperCase();
  const activeValidation = review?.preview?.validation ?? review?.validation;
  const canApplyRevision = pendingStatus === "PENDING" && (activeValidation?.errors.length ?? 0) === 0;
  const timeline: TimelineEntry[] = (selectedDocument?.revisions ?? [])
    .map((revision) => {
      const source = linkedSources.get(revision.revisionId);
      return source ? { revision, source } : null;
    })
    .filter((entry): entry is TimelineEntry => entry !== null)
    .sort((left, right) => {
      const leftTime = left.revision.createdAt ?? left.source.messageCreatedAt;
      const rightTime = right.revision.createdAt ?? right.source.messageCreatedAt;
      return new Date(rightTime ?? 0).getTime() - new Date(leftTime ?? 0).getTime();
    });
  const currentTimelineEntry = selectedDocument?.currentRevisionId
    ? timeline.find((entry) => entry.revision.revisionId === selectedDocument.currentRevisionId) ?? null
    : null;
  const currentRevisionId = normalizeRevisionId(selectedDocument?.currentRevisionId);
  const redoCandidate = timeline.find((entry) => {
    if (revisionKind(entry.revision, currentRevisionId) !== "rolled_back") {
      return false;
    }

    const baseRevisionId = normalizeRevisionId(entry.revision.baseRevisionId);
    if (currentRevisionId) {
      return baseRevisionId === currentRevisionId;
    }

    return baseRevisionId === null;
  }) ?? null;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="panel-header">
        <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Review</p>
        <h2 className="panel-title">Revision timeline</h2>
      </div>

      <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto p-4">
        {!selectedDocument ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">Select a document to inspect its revision timeline.</div>
        ) : null}

        {selectedDocument && !selectedDocument.documentSessionId ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            Local HTML, Markdown, and TXT files do not create canonical revisions. Import a DOCX through the backend to
            get checkpoint history, review, undo, redo, and restore.
          </div>
        ) : null}

        {selectedDocument?.documentSessionId && !selectedChat ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            Select a specific chat session first. Review history is scoped to the currently open chat only.
          </div>
        ) : null}

        {selectedDocument?.documentSessionId && selectedChat ? (
          <div className="space-y-4">
            <div className="panel-card p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-sm font-medium text-docpilot-textStrong">Current checkpoint</p>
                  <p className="mt-2 text-sm leading-6 text-docpilot-muted">
                    Review is scoped to <span className="text-docpilot-textStrong">{selectedChat.name}</span>. Only revisions staged from this chat are shown here.
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {selectedDocument.currentRevisionId ? (
                    <button type="button" className="action-button" onClick={() => void rollbackCurrentRevision()}>
                      <Undo2 size={14} /> Undo current
                    </button>
                  ) : null}
                  {redoCandidate ? (
                    <button type="button" className="action-button" onClick={() => void restoreRevisionCheckpoint(redoCandidate.revision.revisionId)}>
                      <Redo2 size={14} /> Redo last undo
                    </button>
                  ) : null}
                </div>
              </div>

              <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Active revision</p>
                  <p className="mt-1 break-all text-sm font-semibold text-docpilot-textStrong">
                    {selectedDocument.currentRevisionId ?? "Not available"}
                  </p>
                </div>
                <div className="subtle-card p-3">
                  <p className="text-docpilot-muted">Tracked chat-linked checkpoints</p>
                  <p className="mt-1 text-xl font-semibold text-docpilot-textStrong">{timeline.length}</p>
                </div>
              </div>

              {currentTimelineEntry ? (
                <div className="mt-4 subtle-card p-3 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <p className="font-medium text-docpilot-textStrong">{currentTimelineEntry.source.chatName}</p>
                      <p className="mt-1 text-docpilot-muted">{currentTimelineEntry.source.promptPreview || currentTimelineEntry.source.assistantPreview}</p>
                    </div>
                    <button
                      type="button"
                      className="action-button"
                      onClick={() => focusChatMessage(currentTimelineEntry.source.chatId, currentTimelineEntry.source.messageId)}
                    >
                      Open source chat
                    </button>
                  </div>
                </div>
              ) : null}
            </div>

            {selectedDocument.documentSessionId && hasPendingRevision && review ? (
              <div className="panel-card p-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-medium text-docpilot-textStrong">Pending revision</p>
                    <p className="mt-2 text-sm leading-6 text-docpilot-muted">
                      This draft is staged but non-destructive. Apply to commit it as the next checkpoint, or reject it to discard the draft.
                    </p>
                  </div>
                  <span className={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${canApplyRevision ? "border-docpilot-success/30 bg-docpilot-success/10 text-docpilot-success" : "border-docpilot-warning/30 bg-docpilot-warningSoft text-docpilot-warningText"}`}>
                    {canApplyRevision ? "Ready" : pendingStatus || "Blocked"}
                  </span>
                </div>

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

                <div className="mt-4 subtle-card p-3 text-sm">
                  <p className="text-docpilot-muted">Summary</p>
                  <p className="mt-1 text-docpilot-textStrong">{review.summary || "No summary returned."}</p>
                </div>

                {pendingSource ? (
                  <div className="mt-4 subtle-card p-3 text-sm">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div>
                        <p className="font-medium text-docpilot-textStrong">Originated from {pendingSource.chatName}</p>
                        <p className="mt-1 text-docpilot-muted">{pendingSource.promptPreview || pendingSource.assistantPreview}</p>
                      </div>
                      <button
                        type="button"
                        className="action-button"
                        onClick={() => focusChatMessage(pendingSource.chatId, pendingSource.messageId)}
                      >
                        Open source chat
                      </button>
                    </div>
                  </div>
                ) : null}

                <div className="mt-4 grid grid-cols-1 gap-3">
                  {canApplyRevision ? (
                    <button type="button" className="action-button-primary" onClick={() => void applyStagedRevision()}>
                      <Check size={16} /> Apply revision
                    </button>
                  ) : (
                    <div className="subtle-card p-3 text-sm text-docpilot-muted">
                      Apply is unavailable for revisions with status <span className="text-docpilot-textStrong">{pendingStatus || "UNKNOWN"}</span>.
                    </div>
                  )}
                  <button type="button" className="action-button" onClick={() => void rejectStagedRevision()}>
                    <X size={16} /> Reject revision
                  </button>
                </div>

                {activeValidation ? (
                  <div className="mt-4">
                    <div className="mb-3 flex items-center gap-2 text-docpilot-textStrong">
                      <AlertTriangle size={16} /> Validation
                    </div>
                    <div className="grid grid-cols-2 gap-3 text-sm">
                      <div className="subtle-card p-3">
                        <p className="text-docpilot-muted">Structure</p>
                        <p className="mt-1 font-semibold text-docpilot-textStrong">
                          {activeValidation.structureOk ? "OK" : "Needs attention"}
                        </p>
                      </div>
                      <div className="subtle-card p-3">
                        <p className="text-docpilot-muted">Style</p>
                        <p className="mt-1 font-semibold text-docpilot-textStrong">
                          {activeValidation.styleOk ? "OK" : "Needs attention"}
                        </p>
                      </div>
                    </div>
                    {activeValidation.errors.length > 0 ? (
                      <div className="mt-4">
                        <p className="text-xs uppercase tracking-[0.18em] text-docpilot-dangerText">Errors</p>
                        <ul className="mt-2 space-y-2 text-sm text-docpilot-dangerText">
                          {activeValidation.errors.map((item) => (
                            <li key={item} className="subtle-card p-3">{item}</li>
                          ))}
                        </ul>
                      </div>
                    ) : null}
                    {activeValidation.warnings.length > 0 ? (
                      <div className="mt-4">
                        <p className="text-xs uppercase tracking-[0.18em] text-docpilot-warningText">Warnings</p>
                        <ul className="mt-2 space-y-2 text-sm text-docpilot-warningText">
                          {activeValidation.warnings.map((item) => (
                            <li key={item} className="subtle-card p-3">{item}</li>
                          ))}
                        </ul>
                      </div>
                    ) : null}
                  </div>
                ) : null}

                <div className="mt-4">
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

            <div className="panel-card p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="mb-2 flex items-center gap-2 text-docpilot-textStrong">
                    <History size={16} /> Revision history
                  </div>
                  <p className="text-sm leading-6 text-docpilot-muted">
                    Every item below comes from the current chat session. Click an item to jump back to the exact message that staged it.
                  </p>
                </div>
              </div>

              <div className="mt-4 space-y-3 text-sm">
                {timeline.length > 0 ? timeline.map((entry) => {
                  const restorable = canRestoreCheckpoint(entry.revision, selectedDocument.currentRevisionId);
                  const isRedo = redoCandidate?.revision.revisionId === entry.revision.revisionId;

                  return (
                    <div key={entry.revision.revisionId} className="subtle-card p-3">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <div className="flex flex-wrap items-center gap-2">
                            <span className={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${revisionBadgeClass(entry.revision, selectedDocument.currentRevisionId)}`}>
                              {revisionLabel(entry.revision, selectedDocument.currentRevisionId)}
                            </span>
                            <span className="text-xs text-docpilot-muted">{formatRelativeTime(entry.source.messageCreatedAt)}</span>
                            <span className="text-xs text-docpilot-muted">{entry.source.chatName}</span>
                          </div>
                          <p className="mt-2 text-sm font-medium text-docpilot-textStrong">
                            {entry.revision.summary || entry.source.promptPreview || "Untitled revision"}
                          </p>
                          {entry.source.promptPreview ? (
                            <p className="mt-1 text-xs text-docpilot-muted">Prompt: {entry.source.promptPreview}</p>
                          ) : null}
                          {entry.source.assistantPreview ? (
                            <p className="mt-1 text-xs text-docpilot-muted">Reply: {entry.source.assistantPreview}</p>
                          ) : null}
                          <p className="mt-2 break-all text-[11px] text-docpilot-muted">{entry.revision.revisionId}</p>
                        </div>

                        <div className="flex flex-wrap gap-2">
                          <button
                            type="button"
                            className="action-button"
                            onClick={() => focusChatMessage(entry.source.chatId, entry.source.messageId)}
                          >
                            Open chat
                          </button>
                          {restorable ? (
                            <button
                              type="button"
                              className="action-button"
                              onClick={() => void restoreRevisionCheckpoint(entry.revision.revisionId)}
                            >
                              {isRedo ? <Redo2 size={14} /> : <ClipboardCheck size={14} />} {isRedo ? "Redo" : "Restore"}
                            </button>
                          ) : null}
                        </div>
                      </div>
                    </div>
                  );
                }) : (
                  <div className="subtle-card p-3 text-docpilot-muted">
                    No chat-linked revision history yet. Stage a change from chat to create the first timeline entry.
                  </div>
                )}
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}