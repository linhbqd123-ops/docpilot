import { Bot, LoaderCircle, Send, Square, User, RotateCcw } from "lucide-react";

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

export function ChatPanel() {
  const {
    state,
    selectedDocument,
    currentMessages,
    updateComposer,
    sendMessage,
    cancelRequest,
    clearChat,
  } = useAppContext();

  const [inputHeight, setInputHeight] = useState(140); // Default height for input area
  const [isResizing, setIsResizing] = useState(false);
  const dividerRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

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
    Boolean(state.settings.apiBaseUrl.trim()) &&
    Boolean(state.composerValue.trim()) &&
    !state.isSending;

  const helperText = !selectedDocument
    ? "Select or import a document to start an editing session."
    : selectedDocument.status !== "ready"
      ? "This document needs the backend import endpoint before DocPilot can edit it."
      : !state.settings.apiBaseUrl.trim()
        ? "Set the backend URL in Connect before sending requests."
        : "Send an instruction to the backend. Responses can stream into this panel."
    ;

  return (
    <div className="flex h-full min-h-0 flex-col" ref={containerRef}>
      <div className="flex items-center justify-between border-b border-docpilot-border px-4 py-4">
        <div>
          <p className="text-xs uppercase tracking-[0.22em] text-docpilot-muted">Assistant</p>
          <h2 className="mt-1 text-lg font-semibold text-docpilot-textStrong">DocPilot Session</h2>
        </div>
        <div className="flex items-center gap-3">
          {currentMessages.length > 0 && selectedDocument ? (
            <button
              type="button"
              className="action-button"
              onClick={clearChat}
              title="Clear conversation and start a new chat"
            >
              <RotateCcw size={14} /> New Chat
            </button>
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

      <div className="scrollbar-thin flex-1 space-y-5 overflow-y-auto px-4 py-4">
        {!selectedDocument ? (
          <div className="panel-card p-4 text-sm text-docpilot-muted">
            This panel talks to your backend once it is configured. Choose a document on the left, then send an instruction here.
          </div>
        ) : null}

        {selectedDocument && currentMessages.length === 0 ? (
          <div className="panel-card space-y-3 p-4 text-sm text-docpilot-muted">
            <p className="font-medium text-docpilot-text">No conversation yet</p>
            <p>
              The current document is ready in the workspace. Ask for rewriting, outlining, translation, formatting,
              or structure-aware edits.
            </p>
          </div>
        ) : null}

        {currentMessages.map((message) => {
          const isUser = message.role === "user";
          const isError = message.role === "error";

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
                  {message.status === "streaming" ? (
                    <span className="inline-flex items-center gap-1 text-docpilot-warning">
                      <LoaderCircle size={12} className="animate-spin" /> streaming
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
                  <p className="whitespace-pre-wrap">{message.content || (message.status === "streaming" ? "Waiting for tokens…" : "")}</p>
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
            placeholder="Describe the edit you want the backend to perform on the current document..."
          />
          <div className="mt-3 flex items-center justify-between gap-3 border-t border-docpilot-border pt-3">
            <p className="text-xs text-docpilot-muted">
              Enter sends. Shift + Enter inserts a new line. Requests use the configured backend URL.
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
