import { AlertTriangle, ArrowLeft, Bot, CheckCircle2, LoaderCircle, Plus, RotateCcw, Send, Square, User, X } from "lucide-react";

import type { AgentNotice, ChatMessage, ToolActivity } from "@/app/types";
import { useAppContext } from "@/app/context";
import { cn, formatRelativeTime } from "@/lib/utils";
import { useState, useRef, useEffect } from "react";

function roleLabel(role: "user" | "assistant" | "system" | "error") {
  if (role === "assistant") {
    return "DocPilot";
  }

  if (role === "user") {
    return "You";
  }

  if (role === "error") {
    return "Error";
  }

  return "System";
}

interface InferenceStep {
  key: string;
  label: string;
  detail: string;
  status: "running" | "completed";
}

function asString(value: unknown) {
  return typeof value === "string" && value.trim() ? value : "";
}

function asNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function startCase(value: string) {
  return value
    .replace(/_/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function activityKey(activity: ToolActivity) {
  return [
    asString(activity.tool) || "tool",
    asString(activity.phase),
    asString(activity.blockId ?? activity.block_id),
    asString(activity.revisionId ?? activity.revision_id),
  ].join(":");
}

function activityLabel(activity: ToolActivity) {
  const tool = asString(activity.tool);
  const phase = asString(activity.phase);

  if (tool === "get_session_summary") {
    return "Loaded document session";
  }
  if (tool === "get_context_window") {
    return "Read selected context";
  }
  if (tool === "answer_about_document") {
    return "Scanned relevant document context";
  }
  if (tool === "get_html_projection") {
    return "Read document projection";
  }
  if (tool === "inspect_document") {
    return "Inspected document structure";
  }
  if (tool === "locate_relevant_context") {
    return "Located relevant sections";
  }
  if (tool === "llm_inference" && phase === "compose_answer") {
    return "Generating answer";
  }
  if (tool === "llm_inference" && phase === "plan_revision") {
    return "Planning structured edits";
  }
  if (tool === "propose_document_edit") {
    return "Staged revision proposal";
  }
  if (tool === "review_pending_revision") {
    return "Prepared review details";
  }

  return startCase(tool || "assistant step");
}

function activityDetail(activity: ToolActivity) {
  const tool = asString(activity.tool);
  const event = asString(activity.event);

  if (tool === "answer_about_document" && event === "tool_finished") {
    const headingCount = asNumber(activity.headingCount);
    const snippetCount = asNumber(activity.snippetCount);
    const parts = [
      headingCount !== null ? `${headingCount} headings` : "",
      snippetCount !== null ? `${snippetCount} excerpts` : "",
    ].filter(Boolean);
    return parts.join(" · ");
  }

  if (tool === "locate_relevant_context" && event === "tool_finished") {
    const matchCount = asNumber(activity.matchCount);
    return matchCount !== null ? `${matchCount} matches` : "";
  }

  if (tool === "inspect_document" && event === "tool_finished") {
    const sectionCount = asNumber(activity.sectionCount);
    return sectionCount !== null ? `${sectionCount} sections detected` : "";
  }

  if (tool === "get_html_projection" && event === "tool_finished") {
    return "Fallback text loaded from the current document projection";
  }

  if (tool === "llm_inference" && event === "tool_finished") {
    const charCount = asNumber(activity.charCount);
    return charCount !== null ? `${charCount} chars generated` : "";
  }

  if (tool === "propose_document_edit" && event === "tool_finished") {
    const revisionId = asString(activity.revisionId ?? activity.revision_id);
    return revisionId ? `Revision ${revisionId}` : "Revision staged for review";
  }

  if (tool === "review_pending_revision" && event === "tool_finished") {
    return "Review payload ready";
  }

  return "";
}

function buildInferenceSteps(activities: ToolActivity[] | undefined) {
  const steps: InferenceStep[] = [];
  const stepIndexByKey = new Map<string, number>();

  for (const activity of activities ?? []) {
    const key = activityKey(activity);
    const nextStep: InferenceStep = {
      key,
      label: activityLabel(activity),
      detail: activityDetail(activity),
      status: activity.event === "tool_finished" ? "completed" : "running",
    };
    const existingIndex = stepIndexByKey.get(key);

    if (existingIndex === undefined) {
      stepIndexByKey.set(key, steps.length);
      steps.push(nextStep);
      continue;
    }

    const previous = steps[existingIndex];
    steps[existingIndex] = {
      ...previous,
      ...nextStep,
      detail: nextStep.detail || previous.detail,
    };
  }

  return steps;
}

function activeInferenceLabel(message: ChatMessage) {
  const steps = buildInferenceSteps(message.toolActivity);
  for (let index = steps.length - 1; index >= 0; index -= 1) {
    if (steps[index].status === "running") {
      return steps[index].label;
    }
  }
  return "";
}

function noticeText(notice: AgentNotice) {
  return [notice.message, ...(notice.items ?? [])].filter(Boolean).join("\n").trim();
}

export function ChatPanel() {
  const {
    state,
    selectedDocument,
    selectedChat,
    currentMessages,
    updateComposer,
    sendMessage,
    retryMessage,
    cancelRequest,
    clearChat,
    createNewChat,
    selectChat,
    deleteChat,
  } = useAppContext();

  const [inputHeight, setInputHeight] = useState(140); // Default height for input area
  const [isResizing, setIsResizing] = useState(false);
  const dividerRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const documentChats = selectedDocument
    ? state.chats
      .filter((chat) => chat.documentId === selectedDocument.id)
      .sort((left, right) => right.updatedAt - left.updatedAt)
    : [];
  const isDetailView = Boolean(selectedChat);

  // Handle vertical resize of input area
  useEffect(() => {
    if (!isResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      if (!containerRef.current || !dividerRef.current) return;

      const containerRect = containerRef.current.getBoundingClientRect();
      const newHeight = containerRect.height - (e.clientY - containerRect.top);

      // Constrain height between 100px and 400px
      const constrainedHeight = Math.max(100, Math.min(400, newHeight));
      setInputHeight(constrainedHeight);
    };

    const handleMouseUp = () => {
      setIsResizing(false);
    };

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);

    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
    };
  }, [isResizing]);

  const canSend =
    Boolean(selectedDocument) &&
    selectedDocument?.status === "ready" &&
    Boolean(selectedDocument?.documentSessionId) &&
    Boolean(state.settings.apiBaseUrl.trim()) &&
    Boolean(state.composerValue.trim()) &&
    !state.isSending;

  const helperText = !selectedDocument
    ? "Select or import a document to start an editing session."
    : selectedDocument.status !== "ready"
      ? "This document needs the backend import endpoint before DocPilot can edit it."
      : !selectedDocument.documentSessionId
        ? "This document can be edited locally, but AI review/apply requires a session-backed DOCX import."
        : !state.settings.apiBaseUrl.trim()
          ? "This build is missing the core engine configuration."
          : isDetailView
            ? "Continue the selected chat using the active provider."
            : "Select a saved chat or send a message to start a new chat.";

  const composerPlaceholder = isDetailView
    ? "Continue the current chat..."
    : "Send a first message to create a new chat...";

  return (
    <div className="flex h-full min-h-0 flex-col" ref={containerRef}>
      <div className="border-b border-docpilot-border">
        <div className="flex items-center justify-between px-4 py-4">
          <div className="min-w-0">
            <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Assistant</p>
            {selectedDocument && selectedChat ? (
              <div className="mt-2 flex min-w-0 items-start gap-3">
                <button
                  type="button"
                  className="action-button h-8 shrink-0 px-2.5 py-1"
                  onClick={() => selectChat(null)}
                  title="Back to chat history"
                >
                  <ArrowLeft size={14} /> Back
                </button>
                <div className="min-w-0">
                  <h2 className="truncate text-lg font-semibold text-docpilot-textStrong">{selectedChat.name}</h2>
                </div>
              </div>
            ) : (
              <>
                <h2 className="mt-1 text-lg font-semibold text-docpilot-textStrong">
                  {selectedDocument ? "Chat History" : "DocPilot Session"}
                </h2>
                <p className="mt-1 text-xs text-docpilot-muted">
                  {selectedDocument
                    ? `${documentChats.length} saved chat${documentChats.length === 1 ? "" : "s"} for this document.`
                    : "Select a document to open saved chats or start a new one."}
                </p>
              </>
            )}
          </div>
          <div className="flex items-center gap-3">
            {selectedDocument && selectedChat ? (
              <>
                <button
                  type="button"
                  className="action-button"
                  onClick={() => clearChat()}
                  title="Clear the current thread"
                >
                  <RotateCcw size={14} /> Clear
                </button>
                <button
                  type="button"
                  className="action-button"
                  onClick={() => createNewChat()}
                  title="Create a new chat for this document"
                >
                  <Plus size={14} /> New Chat
                </button>
              </>
            ) : null}
            <div className="flex items-center gap-2 rounded-full border border-docpilot-border bg-docpilot-panelAlt px-3 py-1 text-xs text-docpilot-muted">
              <span
                className={cn(
                  "h-2.5 w-2.5 rounded-full",
                  state.connection.status === "online"
                    ? "bg-docpilot-success"
                    : state.connection.status === "checking"
                      ? "bg-docpilot-warning"
                      : "bg-docpilot-danger",
                )}
              />
              <span>{state.settings.provider}</span>
            </div>
          </div>
        </div>
      </div>

      <div className="scrollbar-thin flex-1 space-y-5 overflow-y-auto px-4 py-4">
        {!selectedDocument ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            Choose a document on the left, then send an instruction here.
          </div>
        ) : !selectedChat ? (
          <>
            <div className="panel-card space-y-3 p-4 text-sm text-docpilot-muted">
              <p className="font-medium text-docpilot-text">Choose a saved chat or start a new one</p>
              <p>
                {documentChats.length > 0
                  ? "Pick an existing chat to continue its thread, or send a message below without selecting anything to create a fresh chat detail flow."
                  : "No saved chats yet for this document. The first message you send from here will create a new chat automatically."}
              </p>
            </div>

            {documentChats.length > 0 ? (
              <div className="space-y-2">
                {documentChats.map((chat) => {
                  const lastMessage = chat.messages[chat.messages.length - 1];

                  return (
                    <div key={chat.id} className="panel-card flex items-start gap-3 p-3">
                      <button
                        type="button"
                        className="min-w-0 flex-1 text-left"
                        onClick={() => selectChat(chat.id)}
                      >
                        <p className="truncate text-sm font-medium text-docpilot-textStrong">{chat.name}</p>
                        <p className="mt-1 text-xs text-docpilot-muted">
                          {chat.messages.length} message{chat.messages.length === 1 ? "" : "s"} · Updated {formatRelativeTime(chat.updatedAt)}
                        </p>
                        <p className="mt-2 text-xs text-docpilot-muted">
                          {lastMessage?.content
                            ? lastMessage.content.length > 110
                              ? `${lastMessage.content.slice(0, 107)}...`
                              : lastMessage.content
                            : "Empty chat"}
                        </p>
                      </button>
                      <button
                        type="button"
                        className="action-button shrink-0 px-2 py-1"
                        onClick={() => deleteChat(chat.id)}
                        title="Delete chat"
                      >
                        <X size={12} />
                      </button>
                    </div>
                  );
                })}
              </div>
            ) : null}
          </>
        ) : currentMessages.length === 0 ? (
          <div className="panel-card space-y-3 p-4 text-sm text-docpilot-muted">
            <p className="font-medium text-docpilot-text">No conversation yet</p>
            <p>
              {selectedDocument.documentSessionId
                ? "The current canonical document session is ready. Ask for rewriting, outlining, translation, formatting, or structure-aware edits."
                : "The current document is open locally. You can edit it in the workspace now, or import a DOCX through the backend to enable structured AI revisions."}
            </p>
          </div>
        ) : null}

        {selectedChat && currentMessages.map((message) => {
          const isUser = message.role === "user";
          const isError = message.role === "error";
          const inferenceSteps = buildInferenceSteps(message.toolActivity);
          const activeStep = activeInferenceLabel(message);
          const noticeTexts = (message.notices ?? []).map(noticeText).filter(Boolean);
          const messageBody = message.content || (message.status === "streaming" && inferenceSteps.length === 0 ? "Waiting for response..." : "");

          return (
            <article key={message.id} className="flex gap-3">
              <div
                className={cn(
                  "mt-1 flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl border",
                  isUser
                    ? "border-docpilot-accent/30 bg-docpilot-accentSoft text-docpilot-accent"
                    : isError
                      ? "border-docpilot-danger/40 bg-docpilot-dangerSoft text-docpilot-danger"
                      : "border-docpilot-border bg-docpilot-panelAlt text-docpilot-textStrong",
                )}
              >
                {isUser ? <User size={16} /> : <Bot size={16} />}
              </div>
              <div className="min-w-0 flex-1">
                <div className="mb-2 flex items-center gap-2 text-xs text-docpilot-muted">
                  <span className="font-medium text-docpilot-text">{roleLabel(message.role)}</span>
                  <span>{formatRelativeTime(message.createdAt)}</span>
                  {(!isUser && (message.role === "assistant" || message.role === "error") && message.status !== "streaming") ? (
                    <button
                      type="button"
                      className="action-button"
                      onClick={() => void retryMessage(message.id)}
                      title="Retry"
                    >
                      <RotateCcw size={12} /> Retry
                    </button>
                  ) : null}
                  {message.status === "streaming" ? (
                    <span className="inline-flex items-center gap-1 text-docpilot-warning">
                      <LoaderCircle size={12} className="animate-spin" /> {activeStep || "Thinking..."}
                    </span>
                  ) : null}
                </div>
                <div
                  className={cn(
                    "rounded-2xl border px-4 py-3 text-sm leading-6",
                    isUser
                      ? "border-docpilot-accent/30 bg-docpilot-accentSoft text-docpilot-textStrong"
                      : isError
                        ? "border-docpilot-danger/30 bg-docpilot-dangerSoft text-docpilot-dangerText"
                        : "border-docpilot-border bg-docpilot-panelAlt text-docpilot-text",
                  )}
                >
                  {!isUser && !isError && (inferenceSteps.length > 0 || noticeTexts.length > 0) ? (
                    <div className="mb-3 space-y-2 rounded-xl border border-docpilot-border bg-docpilot-surface px-3 py-3">
                      {inferenceSteps.length > 0 ? (
                        <div className="space-y-2">
                          {inferenceSteps.map((step) => (
                            <div key={step.key} className="flex items-start gap-2">
                              <span
                                className={cn(
                                  "mt-0.5 inline-flex shrink-0 items-center justify-center",
                                  step.status === "completed" ? "text-docpilot-success" : "text-docpilot-warning",
                                )}
                              >
                                {step.status === "completed" ? (
                                  <CheckCircle2 size={13} />
                                ) : (
                                  <LoaderCircle size={13} className="animate-spin" />
                                )}
                              </span>
                              <div className="min-w-0 flex-1">
                                <p className="text-xs font-medium text-docpilot-textStrong">{step.label}</p>
                                {step.detail ? <p className="mt-0.5 text-xs text-docpilot-muted">{step.detail}</p> : null}
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : null}

                      {noticeTexts.length > 0 ? (
                        <div className={cn("space-y-2", inferenceSteps.length > 0 ? "border-t border-docpilot-border pt-2" : "")}>
                          {noticeTexts.map((text, index) => (
                            <div
                              key={`${message.id}-notice-${index}`}
                              className="flex items-start gap-2 rounded-lg border border-docpilot-warning/30 bg-docpilot-warningSoft px-2.5 py-2 text-xs text-docpilot-warningText"
                            >
                              <AlertTriangle size={13} className="mt-0.5 shrink-0" />
                              <span className="whitespace-pre-wrap">{text}</span>
                            </div>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ) : null}

                  {messageBody ? <p className="whitespace-pre-wrap">{messageBody}</p> : null}
                </div>
              </div>
            </article>
          );
        })}
      </div>

      {/* Resize divider */}
      <div
        ref={dividerRef}
        onMouseDown={() => setIsResizing(true)}
        className="group relative -my-1.5 flex h-3 shrink-0 cursor-ns-resize items-center justify-center bg-transparent"
        title="Drag to resize input area"
      >
        <div className="h-px w-full bg-docpilot-border transition group-hover:bg-docpilot-accent/40" />
        <div className="absolute h-1 w-12 rounded-full bg-docpilot-hover opacity-0 transition group-hover:opacity-100" />
      </div>

      <div className="border-t border-docpilot-border p-4 min-h-[220px]" style={{ height: `${inputHeight}px`, overflow: "hidden", display: "flex", flexDirection: "column" }}>
        <div className="mb-3 flex items-center justify-between text-xs text-docpilot-muted">
          <span>{helperText}</span>
          {selectedDocument ? <span className="badge">{selectedDocument.name}</span> : null}
        </div>
        <div className="rounded-2xl border border-docpilot-border bg-docpilot-panelAlt p-3 flex flex-col flex-1 min-h-0">
          <textarea
            value={state.composerValue}
            onChange={(event) => updateComposer(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void sendMessage();
              }
            }}
            className="flex-1 w-full resize-none border-none bg-transparent text-sm leading-6 text-docpilot-text outline-none placeholder:text-docpilot-muted/70"
            placeholder={composerPlaceholder}
          />
          <div className="mt-3 flex items-center justify-between gap-3 border-t border-docpilot-border pt-3">
            <p className="text-xs text-docpilot-muted">
              Enter sends. Shift + Enter inserts a new line. Requests use the selected provider.
            </p>
            <div className="flex items-center gap-2">
              {state.isSending ? (
                <button type="button" className="action-button" onClick={cancelRequest}>
                  <Square size={14} /> Stop
                </button>
              ) : null}
              <button type="button" className="action-button-primary" onClick={() => void sendMessage()} disabled={!canSend}>
                <Send size={14} /> Send
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
