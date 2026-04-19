import { AlertTriangle, ArrowLeft, Bot, CheckCircle2, ChevronDown, ChevronRight, LoaderCircle, Plus, RotateCcw, Send, Square, User, X } from "lucide-react";

import type { AgentNotice, AppSettings, ChatMessage, ChatMode, ToolActivity, TurnUsage, TurnUsageRequest } from "@/app/types";
import { useAppContext } from "@/app/context";
import Dropdown from "@/components/ui/Dropdown";
import { getProviderDefinition } from "@/lib/providers";
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

const compactNumberFormatter = new Intl.NumberFormat("en", {
  notation: "compact",
  maximumFractionDigits: 1,
});

function asString(value: unknown) {
  return typeof value === "string" && value.trim() ? value : "";
}

function asScalarString(value: unknown) {
  if (typeof value === "string" && value.trim()) {
    return value;
  }

  if (typeof value === "number" && Number.isFinite(value)) {
    return String(value);
  }

  return "";
}

function asNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function formatCompactMetric(value: number) {
  return compactNumberFormatter.format(value);
}

function formatTokenEstimate(value: number) {
  return `~${formatCompactMetric(value)} tok total`;
}

function formatTokenBreakdown(parts: {
  estimatedInputTokens: number;
  estimatedOutputTokens: number;
  estimatedTotalTokens: number;
}) {
  const segments: string[] = [];

  if (parts.estimatedInputTokens > 0) {
    segments.push(`in ~${formatCompactMetric(parts.estimatedInputTokens)}`);
  }

  if (parts.estimatedOutputTokens > 0) {
    segments.push(`out ~${formatCompactMetric(parts.estimatedOutputTokens)}`);
  }

  if (parts.estimatedTotalTokens > 0) {
    segments.push(`total ~${formatCompactMetric(parts.estimatedTotalTokens)}`);
  }

  return segments.join(" · ");
}

function formatRequestCount(value: number) {
  return `${value} AI req${value === 1 ? "" : "s"}`;
}

function defaultRequestPurpose(phase?: string | null) {
  if (phase === "plan_revision") {
    return "Prepare revision proposal";
  }

  if (phase === "agent_loop") {
    return "Reason about next step";
  }

  return "Compose final answer";
}

function estimateTokenCount(text: string) {
  const normalized = text.trim();
  if (!normalized) {
    return 0;
  }

  return Math.max(1, Math.ceil(normalized.length / 4));
}

function estimateThreadChars(messages: ChatMessage[]) {
  return messages.reduce((total, message) => total + message.content.length, 0);
}

function estimateThreadTokens(messages: ChatMessage[]) {
  return messages.reduce((total, message) => total + estimateTokenCount(message.content), 0);
}

function modeLabel(mode: ChatMode) {
  return mode === "agent" ? "Agent" : "Ask";
}

function phaseLabel(phase: string) {
  if (phase === "compose_answer") {
    return "Answer generation";
  }

  if (phase === "plan_revision") {
    return "Revision planning";
  }

  return startCase(phase || "model inference");
}

function startCase(value: string) {
  return value
    .replace(/_/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function activityMergeKey(activity: ToolActivity) {
  return [
    asScalarString(activity.tool) || "tool",
    asScalarString(activity.phase),
    asScalarString(activity.requestIndex ?? activity.request_index),
    asScalarString(activity.blockId ?? activity.block_id),
    asScalarString(activity.sessionId ?? activity.session_id),
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
  if (tool === "get_source_html") {
    return "Loaded fidelity source HTML";
  }
  if (tool === "get_analysis_html") {
    return "Loaded analysis HTML";
  }
  if (tool === "tool_batch") {
    return "Executed tool batch";
  }
  if (tool === "compact_session_context") {
    return "Compacted session context";
  }
  if (tool === "get_html_projection") {
    return "Read document projection (legacy)";
  }
  if (tool === "inspect_document") {
    return "Inspected document structure";
  }
  if (tool === "locate_relevant_context") {
    return "Located relevant sections";
  }
  if (tool === "llm_inference") {
    const requestIndex = asNumber(activity.requestIndex ?? activity.request_index);
    const providerDisplayName = asString(activity.providerDisplayName ?? activity.provider_display_name);
    const model = asString(activity.model);
    const target = [providerDisplayName, model].filter(Boolean).join(" / ");

    if (requestIndex !== null) {
      return target ? `API request #${requestIndex} · ${target}` : `API request #${requestIndex}`;
    }

    return target ? `API request · ${target}` : `API request · ${phaseLabel(phase)}`;
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

  if (tool === "get_source_html" && event === "tool_finished") {
    return "Full-fidelity source HTML loaded from the active document session";
  }

  if (tool === "get_analysis_html" && event === "tool_finished") {
    return "Optimized analysis HTML loaded for additional AI context";
  }

  if (tool === "tool_batch") {
    const execution = asString(activity.execution);
    const batchReason = asString(activity.batchReason ?? activity.batch_reason);
    const toolCount = asNumber(activity.completedToolCount ?? activity.toolCount);
    const parts = [batchReason || (execution ? `${execution} batch` : "tool batch")];
    if (batchReason && execution) {
      parts.push(`${execution} batch`);
    }
    if (toolCount !== null) {
      parts.push(`${toolCount} tool${toolCount === 1 ? "" : "s"}`);
    }
    return parts.join(" · ");
  }

  if (tool === "compact_session_context") {
    const original = asNumber(activity.originalEstimatedInputTokens);
    const final = asNumber(activity.finalEstimatedInputTokens);
    const summarized = asNumber(activity.summarizedMessageCount);
    const parts = ["Older turns summarized"];
    if (original !== null && final !== null) {
      parts.push(`~${formatCompactMetric(original)} -> ~${formatCompactMetric(final)} tok`);
    }
    if (summarized !== null) {
      parts.push(`${summarized} msg`);
    }
    return parts.join(" · ");
  }

  if (tool === "get_html_projection" && event === "tool_finished") {
    return "Legacy projection HTML loaded";
  }

  if (tool === "llm_inference") {
    const parts = [phaseLabel(asString(activity.phase))];
    const estimatedInputTokens = asNumber(activity.estimatedInputTokens ?? activity.estimated_input_tokens) ?? 0;
    const estimatedOutputTokens = asNumber(activity.estimatedOutputTokens ?? activity.estimated_output_tokens) ?? 0;
    const estimatedTotalTokens = asNumber(activity.estimatedTotalTokens ?? activity.estimated_total_tokens);
    const charCount = asNumber(activity.charCount);

    if (estimatedTotalTokens !== null && estimatedTotalTokens > 0) {
      parts.push(
        formatTokenBreakdown({
          estimatedInputTokens,
          estimatedOutputTokens,
          estimatedTotalTokens,
        }),
      );
    }

    if (charCount !== null && charCount > 0) {
      parts.push(`${formatCompactMetric(charCount)} chars`);
    }

    return parts.join(" · ");
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
    const key = activityMergeKey(activity);
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

function reasoningText(activity: ToolActivity | undefined) {
  if (!activity) {
    return "";
  }
  return asString(activity.reasoningText ?? activity.reasoning_text);
}

function reasoningActivityMap(activities: ToolActivity[] | undefined) {
  let fallbackIndex = 0;
  const entries = new Map<number, ToolActivity>();

  (activities ?? []).forEach((activity) => {
    if (asString(activity.tool) !== "llm_inference") {
      return;
    }

    fallbackIndex += 1;
    const requestIndex = asNumber(activity.requestIndex ?? activity.request_index) ?? fallbackIndex;
    if (requestIndex === null) {
      return;
    }

    const existing = entries.get(requestIndex);
    if (!existing || reasoningText(activity) || asString(activity.event) === "tool_finished") {
      entries.set(requestIndex, activity);
    }
  });

  return entries;
}

function usageRequestFromActivity(activity: ToolActivity, fallbackIndex: number): TurnUsageRequest | null {
  const event = asString(activity.event);
  if (asString(activity.tool) !== "llm_inference" || (event !== "tool_started" && event !== "tool_finished")) {
    return null;
  }

  const requestIndex = asNumber(activity.requestIndex ?? activity.request_index) ?? fallbackIndex;
  const estimatedInputTokens = asNumber(activity.estimatedInputTokens ?? activity.estimated_input_tokens) ?? 0;
  const estimatedOutputTokens = asNumber(activity.estimatedOutputTokens ?? activity.estimated_output_tokens) ?? 0;

  return {
    requestIndex,
    phase: asString(activity.phase) || null,
    requestPurpose: asString(activity.requestPurpose ?? activity.request_purpose) || defaultRequestPurpose(asString(activity.phase) || null),
    provider: asString(activity.provider) || undefined,
    providerDisplayName: asString(activity.providerDisplayName ?? activity.provider_display_name) || undefined,
    model: asString(activity.model) || null,
    inputChars: asNumber(activity.inputChars ?? activity.input_chars) ?? 0,
    outputChars: asNumber(activity.outputChars ?? activity.output_chars) ?? asNumber(activity.charCount) ?? 0,
    estimatedInputTokens,
    estimatedOutputTokens,
    estimatedTotalTokens:
      asNumber(activity.estimatedTotalTokens ?? activity.estimated_total_tokens)
      ?? estimatedInputTokens + estimatedOutputTokens,
  };
}

function requestPurposeLabel(request: TurnUsageRequest) {
  return request.requestPurpose || defaultRequestPurpose(request.phase);
}

function usageFromToolActivity(activities: ToolActivity[] | undefined): TurnUsage | undefined {
  let fallbackIndex = 0;
  const requestsByIndex = new Map<number, TurnUsageRequest>();

  (activities ?? []).forEach((activity) => {
    if (asString(activity.tool) !== "llm_inference") {
      return;
    }

    fallbackIndex += 1;
    const request = usageRequestFromActivity(activity, fallbackIndex);
    if (!request) {
      return;
    }

    const existing = requestsByIndex.get(request.requestIndex);
    if (!existing) {
      requestsByIndex.set(request.requestIndex, request);
      return;
    }

    requestsByIndex.set(request.requestIndex, {
      requestIndex: request.requestIndex,
      phase: request.phase || existing.phase,
      provider: request.provider || existing.provider,
      providerDisplayName: request.providerDisplayName || existing.providerDisplayName,
      model: request.model || existing.model,
      inputChars: request.inputChars > 0 ? request.inputChars : existing.inputChars,
      outputChars: request.outputChars > 0 ? request.outputChars : existing.outputChars,
      estimatedInputTokens:
        request.estimatedInputTokens > 0 ? request.estimatedInputTokens : existing.estimatedInputTokens,
      estimatedOutputTokens:
        request.estimatedOutputTokens > 0 ? request.estimatedOutputTokens : existing.estimatedOutputTokens,
      estimatedTotalTokens:
        request.estimatedTotalTokens > 0
          ? request.estimatedTotalTokens
          : existing.estimatedTotalTokens > 0
            ? existing.estimatedTotalTokens
            : Math.max(
              0,
              (request.estimatedInputTokens > 0 ? request.estimatedInputTokens : existing.estimatedInputTokens)
              + (request.estimatedOutputTokens > 0 ? request.estimatedOutputTokens : existing.estimatedOutputTokens),
            ),
    });
  });

  const requests = [...requestsByIndex.values()].sort((left, right) => left.requestIndex - right.requestIndex);

  if (requests.length === 0) {
    return undefined;
  }

  return {
    requestCount: requests.length,
    estimatedInputTokens: requests.reduce((total, request) => total + request.estimatedInputTokens, 0),
    estimatedOutputTokens: requests.reduce((total, request) => total + request.estimatedOutputTokens, 0),
    estimatedTotalTokens: requests.reduce((total, request) => total + request.estimatedTotalTokens, 0),
    requests,
  };
}

function fallbackMessageUsage(message: ChatMessage, history: ChatMessage[], settings?: AppSettings): TurnUsage | undefined {
  if (!settings || (message.role !== "assistant" && message.role !== "error")) {
    return undefined;
  }

  const hasInferenceTrace = (message.toolActivity ?? []).some((activity) => asString(activity.tool) === "llm_inference");
  if (!message.content.trim() && !hasInferenceTrace) {
    return undefined;
  }

  const providerDefinition = getProviderDefinition(settings.provider);
  const inputChars = estimateThreadChars(history);
  const outputChars = message.content.length;
  const estimatedInputTokens = estimateThreadTokens(history);
  const estimatedOutputTokens = estimateTokenCount(message.content);
  const llmActivity = [...(message.toolActivity ?? [])].reverse().find((activity) => asString(activity.tool) === "llm_inference");
  const phase = asString(llmActivity?.phase) || (message.mode === "agent" ? "plan_revision" : "compose_answer");

  return {
    requestCount: 1,
    estimatedInputTokens,
    estimatedOutputTokens,
    estimatedTotalTokens: estimatedInputTokens + estimatedOutputTokens,
    requests: [
      {
        requestIndex: 1,
        phase,
        provider: settings.provider,
        providerDisplayName: providerDefinition?.label ?? settings.provider,
        model: settings.modelOverride.trim() || providerDefinition?.defaultModel || null,
        inputChars,
        outputChars,
        estimatedInputTokens,
        estimatedOutputTokens,
        estimatedTotalTokens: estimatedInputTokens + estimatedOutputTokens,
      },
    ],
  };
}

function messageUsage(message: ChatMessage, history: ChatMessage[] = [], settings?: AppSettings): TurnUsage | undefined {
  if (message.usage && (message.usage.requestCount > 0 || message.usage.estimatedTotalTokens > 0)) {
    return message.usage;
  }

  return usageFromToolActivity(message.toolActivity) ?? fallbackMessageUsage(message, history, settings);
}

function summarizeChatUsage(messages: ChatMessage[], settings?: AppSettings): TurnUsage | undefined {
  const requests: TurnUsageRequest[] = [];

  messages.forEach((message, index) => {
    const usage = messageUsage(message, messages.slice(0, index), settings);
    if (!usage) {
      return;
    }

    usage.requests.forEach((request) => requests.push(request));
  });

  if (requests.length === 0) {
    return undefined;
  }

  return {
    requestCount: requests.length,
    estimatedInputTokens: requests.reduce((total, request) => total + request.estimatedInputTokens, 0),
    estimatedOutputTokens: requests.reduce((total, request) => total + request.estimatedOutputTokens, 0),
    estimatedTotalTokens: requests.reduce((total, request) => total + request.estimatedTotalTokens, 0),
    requests,
  };
}

function requestTargetLabel(request: TurnUsageRequest) {
  const provider = request.providerDisplayName || request.provider || "AI model";
  return request.model ? `${provider} / ${request.model}` : provider;
}

function summarizeRequestTargets(usage: TurnUsage | undefined) {
  if (!usage?.requests.length) {
    return "";
  }

  const uniqueTargets = Array.from(new Set(usage.requests.map(requestTargetLabel).filter(Boolean)));
  if (uniqueTargets.length === 0) {
    return "";
  }

  if (uniqueTargets.length === 1) {
    return uniqueTargets[0];
  }

  return `${uniqueTargets[0]} +${uniqueTargets.length - 1}`;
}

function usageSummaryText(usage: TurnUsage | undefined) {
  if (!usage || usage.requestCount <= 0) {
    return "";
  }

  const parts = [formatRequestCount(usage.requestCount)];
  if (usage.estimatedTotalTokens > 0) {
    parts.push(formatTokenEstimate(usage.estimatedTotalTokens));
  }

  return parts.join(" · ");
}

function hasTokenEstimate(usage: TurnUsage | undefined) {
  return Boolean(usage && usage.estimatedTotalTokens > 0);
}

export function ChatPanel() {
  const {
    state,
    selectedDocument,
    selectedChat,
    currentMessages,
    updateComposer,
    updateSettings,
    sendMessage,
    retryMessage,
    cancelRequest,
    clearChat,
    compactCurrentChat,
    createNewChat,
    selectChat,
    deleteChat,
  } = useAppContext();

  const [inputHeight, setInputHeight] = useState(140); // Default height for input area
  const [isResizing, setIsResizing] = useState(false);
  const [expandedTraceIds, setExpandedTraceIds] = useState<Record<string, boolean>>({});
  const dividerRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const messageRefs = useRef<Record<string, HTMLElement | null>>({});

  const documentChats = selectedDocument
    ? state.chats
      .filter((chat) => chat.documentId === selectedDocument.id)
      .sort((left, right) => right.updatedAt - left.updatedAt)
    : [];
  const isDetailView = Boolean(selectedChat);
  const selectedChatUsage = selectedChat ? summarizeChatUsage(currentMessages, state.settings) : undefined;

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

  useEffect(() => {
    setExpandedTraceIds((current) => {
      let nextState = current;

      currentMessages.forEach((message) => {
        if ((message.role === "assistant" || message.role === "error") && message.status === "streaming" && current[message.id] === undefined) {
          if (nextState === current) {
            nextState = { ...current };
          }
          nextState[message.id] = true;
        }
      });

      return nextState;
    });
  }, [currentMessages]);

  useEffect(() => {
    const focusedId = state.focusedChatMessageId;
    if (!selectedChat || focusedId == null) {
      return;
    }

    let raf1: number | null = null;
    let raf2: number | null = null;

    const doScroll = () => {
      const target = messageRefs.current[focusedId];
      if (!target) {
        return;
      }

      raf1 = requestAnimationFrame(() => {
        raf2 = requestAnimationFrame(() => {
          try {
            if (import.meta.env.DEV) {
              try {
                // eslint-disable-next-line no-console
                console.debug("[ChatPanel] scrollIntoView", {
                  messageId: state.focusedChatMessageId,
                  chatId: selectedChat?.id,
                  at: new Date().toISOString(),
                });
                // eslint-disable-next-line no-console
                console.debug(new Error().stack);
              } catch (err) {
                // ignore
              }
            }

            target.scrollIntoView({ behavior: "smooth", block: "center" });
          } catch (err) {
            // ignore
          }
        });
      });
    };

    doScroll();

    return () => {
      if (raf1 !== null) cancelAnimationFrame(raf1);
      if (raf2 !== null) cancelAnimationFrame(raf2);
    };
  }, [currentMessages, selectedChat, state.focusedChatMessageId, state.focusedChatMessageRequestId]);

  const canSend =
    Boolean(selectedDocument) &&
    selectedDocument?.status === "ready" &&
    Boolean(selectedDocument?.documentSessionId) &&
    Boolean(state.settings.apiBaseUrl.trim()) &&
    Boolean(state.composerValue.trim()) &&
    !state.isSending;

  const composerPlaceholder = state.settings.mode === "ask"
    ? (isDetailView ? "Ask about this document..." : "Ask about this document to start a chat...")
    : (isDetailView ? "Tell Agent what to change..." : "Describe the edit you want to stage...");

  const modeItems = [
    {
      value: "agent",
      label: "Agent",
      description: "Inspect the document and stage structured edits.",
    },
    {
      value: "ask",
      label: "Ask",
      description: "Read-only answers from the current document context.",
    },
  ];

  function toggleTrace(messageId: string) {
    setExpandedTraceIds((current) => ({
      ...current,
      [messageId]: !(current[messageId] ?? false),
    }));
  }

  return (
    <div className="flex h-full min-h-0 flex-col" ref={containerRef}>
      <div className="border-b border-docpilot-border">
        <div className="flex items-center justify-between px-4 py-4">
          <div className="min-w-0">
            {selectedDocument && selectedChat ? (
              <div className="mt-2 flex min-w-0 items-start gap-3">
                <button
                  type="button"
                  className="action-button h-8 shrink-0 px-2.5 py-1"
                  onClick={() => selectChat(null)}
                  title="Back to chat history"
                >
                  <ArrowLeft size={14} />
                </button>
                <div className="min-w-0">
                  <h2 className="truncate text-lg font-semibold text-docpilot-textStrong">{selectedChat.name}</h2>
                  {selectedChatUsage ? (
                    <p className="mt-1 text-xs text-docpilot-muted">
                      {usageSummaryText(selectedChatUsage)}
                    </p>
                  ) : null}
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
                  <RotateCcw size={14} />
                </button>
                <button
                  type="button"
                  className="action-button"
                  onClick={() => compactCurrentChat()}
                  title="Compact older turns into a system summary"
                  disabled={state.isSending || currentMessages.length < 8}
                >
                  Compact
                </button>
                <button
                  type="button"
                  className="action-button"
                  onClick={() => createNewChat()}
                  title="Create a new chat for this document"
                >
                  <Plus size={14} />
                </button>
              </>
            ) : null}
            <div className="flex items-center gap-2 rounded-full border border-docpilot-border bg-docpilot-panelAlt px-3 py-1 text-xs text-docpilot-muted">
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
                  const chatUsage = summarizeChatUsage(chat.messages, state.settings);

                  return (
                    <div key={chat.id} className="panel-card flex items-start gap-3 p-3 transition duration-150 hover:-translate-y-px hover:border-docpilot-accent/30 hover:shadow-active">
                      <button
                        type="button"
                        className="min-w-0 flex-1 text-left"
                        onClick={() => selectChat(chat.id)}
                      >
                        <p className="truncate text-sm font-medium text-docpilot-textStrong">{chat.name}</p>
                        <p className="mt-1 text-xs text-docpilot-muted">
                          {chat.messages.length} message{chat.messages.length === 1 ? "" : "s"}
                          {chatUsage ? ` · ${formatRequestCount(chatUsage.requestCount)}` : ""}
                          {chatUsage && hasTokenEstimate(chatUsage) ? ` · ${formatTokenEstimate(chatUsage.estimatedTotalTokens)}` : ""}
                          {` · Updated ${formatRelativeTime(chat.updatedAt)}`}
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

        {selectedChat && currentMessages.map((message, index) => {
          const isUser = message.role === "user";
          const isError = message.role === "error";
          const inferenceSteps = buildInferenceSteps(message.toolActivity);
          const activeStep = activeInferenceLabel(message);
          const usage = messageUsage(message, currentMessages.slice(0, index), state.settings);
          const noticeTexts = (message.notices ?? []).map(noticeText).filter(Boolean);
          const targetSummary = summarizeRequestTargets(usage);
          const reasoningByRequest = reasoningActivityMap(message.toolActivity);
          const hasTraceDetails = !isUser && (inferenceSteps.length > 0 || noticeTexts.length > 0 || Boolean(usage?.requests.length));
          const traceExpanded = expandedTraceIds[message.id] ?? false;
          const showMessageMeta = !isUser && Boolean(message.mode || targetSummary || usage?.requestCount || hasTokenEstimate(usage) || hasTraceDetails);
          const messageBody = message.content || (message.status === "streaming" && inferenceSteps.length === 0 ? "Waiting for response..." : "");

          return (
            <article
              key={message.id}
              ref={(node) => {
                messageRefs.current[message.id] = node;
              }}
              className={cn(
                "flex gap-3 rounded-3xl px-2 py-2 transition duration-300",
                state.focusedChatMessageId === message.id ? "bg-docpilot-accentSoft/60 ring-1 ring-docpilot-accent/30" : "",
              )}
            >
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
                  {showMessageMeta ? (
                    <div className="mb-3 flex flex-wrap items-start justify-between gap-3 border-b border-docpilot-border/70 pb-3">
                      <div className="flex flex-wrap items-center gap-2">
                        {message.mode ? (
                          <span className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] font-medium text-docpilot-textStrong">
                            {modeLabel(message.mode)}
                          </span>
                        ) : null}
                        {targetSummary ? (
                          <span className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] text-docpilot-muted">
                            {targetSummary}
                          </span>
                        ) : null}
                        {usage?.requestCount ? (
                          <span className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] text-docpilot-muted">
                            {formatRequestCount(usage.requestCount)}
                          </span>
                        ) : null}
                        {hasTokenEstimate(usage) ? (
                          <span className="rounded-full border border-docpilot-border bg-docpilot-surface px-2.5 py-1 text-[11px] text-docpilot-muted">
                            {usage ? formatTokenEstimate(usage.estimatedTotalTokens) : ""}
                          </span>
                        ) : null}
                      </div>

                      {hasTraceDetails ? (
                        <button
                          type="button"
                          onClick={() => toggleTrace(message.id)}
                          className="inline-flex items-center gap-1 rounded-xl border border-docpilot-border bg-docpilot-surface px-3 py-1.5 text-[11px] font-medium text-docpilot-muted transition duration-150 hover:-translate-y-px hover:border-docpilot-accent/30 hover:bg-docpilot-hover hover:text-docpilot-textStrong active:translate-y-0 active:scale-[0.99]"
                        >
                          {traceExpanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                          Execution details
                        </button>
                      ) : null}
                    </div>
                  ) : null}

                  {!isUser && hasTraceDetails && traceExpanded ? (
                    <div className="mb-3 space-y-3 rounded-2xl border border-docpilot-border bg-docpilot-surface px-3 py-3 shadow-sm">
                      {usage?.requests.length ? (
                        <div className="grid gap-2">
                          {usage.requests.map((request) => (
                            <div
                              key={`${message.id}-request-${request.requestIndex}`}
                              className="rounded-xl border border-docpilot-border/80 bg-docpilot-panel px-3 py-2 transition duration-150 hover:border-docpilot-accent/30"
                            >
                              <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-docpilot-muted">
                                API request #{request.requestIndex}
                              </p>
                              <p className="mt-1 text-xs font-medium text-docpilot-textStrong">{requestPurposeLabel(request)}</p>
                              <p className="mt-1 text-xs text-docpilot-muted">{requestTargetLabel(request)}</p>
                              <p className="mt-1 text-xs text-docpilot-muted">
                                {phaseLabel(request.phase ?? "")}
                                {request.estimatedTotalTokens > 0
                                  ? ` · ${formatTokenBreakdown({
                                    estimatedInputTokens: request.estimatedInputTokens,
                                    estimatedOutputTokens: request.estimatedOutputTokens,
                                    estimatedTotalTokens: request.estimatedTotalTokens,
                                  })}`
                                  : ""}
                              </p>
                              {reasoningText(reasoningByRequest.get(request.requestIndex)) ? (
                                <div className="mt-2 rounded-lg border border-docpilot-border/80 bg-docpilot-surface px-2.5 py-2">
                                  <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-docpilot-muted">
                                    Reasoning trace
                                  </p>
                                  <div className="mt-1 max-h-40 overflow-y-auto whitespace-pre-wrap text-xs leading-5 text-docpilot-muted">
                                    {reasoningText(reasoningByRequest.get(request.requestIndex))}
                                  </div>
                                </div>
                              ) : null}
                            </div>
                          ))}
                        </div>
                      ) : null}

                      {inferenceSteps.length > 0 ? (
                        <div className={cn("space-y-2", usage?.requests.length ? "border-t border-docpilot-border pt-3" : "")}>
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
                        <div className={cn("space-y-2", inferenceSteps.length > 0 || usage?.requests.length ? "border-t border-docpilot-border pt-3" : "")}>
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
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3 text-xs text-docpilot-muted">
          <div className="flex flex-wrap items-center gap-2">
            <Dropdown
              items={modeItems}
              value={state.settings.mode}
              onChange={(value) => updateSettings({ mode: value as ChatMode })}
              className="w-auto shrink-0"
              buttonClassName={cn(
                "min-h-[46px] rounded-2xl border-docpilot-border/90 bg-docpilot-surface transition duration-150 hover:-translate-y-px hover:border-docpilot-accent/30 hover:bg-docpilot-hover active:translate-y-0 active:scale-[0.99]",
                state.isSending ? "pointer-events-none opacity-70" : "",
              )}
              menuClassName="rounded-2xl border-docpilot-border/90 bg-docpilot-panel shadow-glow"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {selectedDocument ? <span className="badge">{selectedDocument.name}</span> : null}
          </div>
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
              {state.settings.mode === "agent"
                ? "Agent can inspect the document and stage structured edits. Enter sends. Shift + Enter inserts a new line."
                : "Ask is read-only and answers from the current document context. Enter sends. Shift + Enter inserts a new line."}
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
