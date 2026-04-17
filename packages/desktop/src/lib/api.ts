import type {
  AgentNotice,
  AgentTurnResponse,
  AppSettings,
  Chat,
  ChatMode,
  ChatMessage,
  DocumentRecord,
  ImportedDocumentPayload,
  RevisionProposal,
  RevisionReview,
  RevisionValidation,
  ReviewOperation,
  SessionRefreshPayload,
  SessionRevisionSummary,
  SessionSummary,
  ToolActivity,
  TurnUsage,
  TurnUsageRequest,
} from "@/app/types";
import { createTraceId, logFrontendDebug } from "@/lib/debug";

class ApiError extends Error {
  status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

function joinUrl(baseUrl: string, path: string) {
  const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  const normalizedPath = path.startsWith("/") ? path.slice(1) : path;
  return new URL(normalizedPath, base).toString();
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number, signal?: AbortSignal) {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), timeoutMs);

  if (signal) {
    signal.addEventListener("abort", () => controller.abort(), { once: true });
  }

  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

function asNonEmptyString(value: unknown) {
  return typeof value === "string" && value.trim() ? value : undefined;
}

function asNumber(value: unknown, fallback = 0) {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function asStringArray(value: unknown) {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === "string") : [];
}

function asChatMode(value: unknown): ChatMode | undefined {
  return value === "ask" || value === "agent" ? value : undefined;
}

function parseDocumentKind(value: unknown): DocumentRecord["kind"] {
  switch (value) {
    case "html":
    case "markdown":
    case "text":
    case "docx":
    case "pdf":
      return value;
    default:
      return "unknown";
  }
}

function parseDocumentStatus(value: unknown): DocumentRecord["status"] {
  switch (value) {
    case "needs_backend":
    case "error":
      return value;
    default:
      return "ready";
  }
}

function parseValidation(value: unknown): RevisionValidation | undefined {
  const record = asRecord(value);
  if (Object.keys(record).length === 0) {
    return undefined;
  }

  return {
    structureOk: Boolean(record.structureOk ?? record.structure_ok),
    styleOk: Boolean(record.styleOk ?? record.style_ok),
    scope: asNonEmptyString(record.scope) ?? "minor",
    errors: asStringArray(record.errors),
    warnings: asStringArray(record.warnings),
  };
}

function parseReviewOperation(value: unknown): ReviewOperation | null {
  const record = asRecord(value);
  const op = asNonEmptyString(record.op);
  if (!op) {
    return null;
  }

  return {
    op,
    description: asNonEmptyString(record.description) ?? "",
    blockId: asNonEmptyString(record.blockId ?? record.block_id) ?? null,
  };
}

function parseReview(value: unknown): RevisionReview | null {
  const record = asRecord(value);
  const revisionId = asNonEmptyString(record.revisionId ?? record.revision_id);
  if (!revisionId) {
    return null;
  }

  const operations = Array.isArray(record.operations)
    ? record.operations
      .map(parseReviewOperation)
      .filter((operation): operation is ReviewOperation => operation !== null)
    : [];

  return {
    revisionId,
    status: asNonEmptyString(record.status) ?? "UNKNOWN",
    summary: asNonEmptyString(record.summary) ?? "",
    author: asNonEmptyString(record.author) ?? "",
    scope: asNonEmptyString(record.scope) ?? "minor",
    createdAt: asNonEmptyString(record.createdAt ?? record.created_at) ?? null,
    operationCount: asNumber(record.operationCount ?? record.operation_count, operations.length),
    validation: parseValidation(record.validation),
    operations,
  };
}

function parseProposal(value: unknown): RevisionProposal | null {
  const record = asRecord(value);
  const revisionId = asNonEmptyString(record.revisionId ?? record.revision_id);
  if (!revisionId) {
    return null;
  }

  return {
    revisionId,
    status: asNonEmptyString(record.status) ?? "UNKNOWN",
    operationCount: asNumber(record.operationCount ?? record.operation_count, 0),
    summary: asNonEmptyString(record.summary) ?? "",
    validation: parseValidation(record.validation),
  };
}

function parseSessionRevisionSummary(value: unknown): SessionRevisionSummary | null {
  const record = asRecord(value);
  const revisionId = asNonEmptyString(record.revisionId ?? record.revision_id);
  if (!revisionId) {
    return null;
  }

  return {
    revisionId,
    baseRevisionId: asNonEmptyString(record.baseRevisionId ?? record.base_revision_id) ?? null,
    status: asNonEmptyString(record.status) ?? "UNKNOWN",
    summary: asNonEmptyString(record.summary) ?? "",
    author: asNonEmptyString(record.author),
    scope: asNonEmptyString(record.scope),
    createdAt: asNonEmptyString(record.createdAt ?? record.created_at) ?? null,
    appliedAt: asNonEmptyString(record.appliedAt ?? record.applied_at) ?? null,
  };
}

function parseSessionSummary(value: unknown): SessionSummary {
  const record = asRecord(value);

  return {
    sessionId: asNonEmptyString(record.sessionId ?? record.session_id) ?? "",
    docId: asNonEmptyString(record.docId ?? record.doc_id) ?? null,
    filename: asNonEmptyString(record.filename),
    state: asNonEmptyString(record.state) ?? null,
    currentRevisionId: asNonEmptyString(record.currentRevisionId ?? record.current_revision_id) ?? null,
    wordCount: asNumber(record.wordCount ?? record.word_count, 0),
    paragraphCount: asNumber(record.paragraphCount ?? record.paragraph_count, 0),
    tableCount: asNumber(record.tableCount ?? record.table_count, 0),
    imageCount: asNumber(record.imageCount ?? record.image_count, 0),
    sectionCount: asNumber(record.sectionCount ?? record.section_count, 0),
    createdAt: asNonEmptyString(record.createdAt ?? record.created_at) ?? null,
  };
}

function parseMutationResult(value: unknown) {
  const record = asRecord(value);
  if (Object.keys(record).length === 0) {
    return undefined;
  }

  return {
    revisionId: asNonEmptyString(record.revisionId ?? record.revision_id) ?? null,
    status: asNonEmptyString(record.status),
    currentRevisionId: asNonEmptyString(record.currentRevisionId ?? record.current_revision_id) ?? null,
  };
}

function parseChatRole(value: unknown): ChatMessage["role"] {
  switch (value) {
    case "assistant":
    case "system":
    case "error":
      return value;
    default:
      return "user";
  }
}

function parseChatStatus(value: unknown): ChatMessage["status"] {
  switch (value) {
    case "streaming":
    case "error":
      return value;
    default:
      return "sent";
  }
}

function parseTurnUsageRequest(value: unknown): TurnUsageRequest | null {
  const record = asRecord(value);
  const requestIndex = asNumber(record.requestIndex ?? record.request_index, 0);

  if (requestIndex <= 0) {
    return null;
  }

  return {
    requestIndex,
    phase: asNonEmptyString(record.phase) ?? null,
    provider: asNonEmptyString(record.provider),
    providerDisplayName: asNonEmptyString(record.providerDisplayName ?? record.provider_display_name),
    model: asNonEmptyString(record.model) ?? null,
    inputChars: asNumber(record.inputChars ?? record.input_chars, 0),
    outputChars: asNumber(record.outputChars ?? record.output_chars, 0),
    estimatedInputTokens: asNumber(record.estimatedInputTokens ?? record.estimated_input_tokens, 0),
    estimatedOutputTokens: asNumber(record.estimatedOutputTokens ?? record.estimated_output_tokens, 0),
    estimatedTotalTokens: asNumber(record.estimatedTotalTokens ?? record.estimated_total_tokens, 0),
  };
}

function parseTurnUsage(value: unknown): TurnUsage | undefined {
  const record = asRecord(value);
  if (Object.keys(record).length === 0) {
    return undefined;
  }

  const requests = Array.isArray(record.requests)
    ? record.requests
      .map(parseTurnUsageRequest)
      .filter((request): request is TurnUsageRequest => request !== null)
    : [];

  const requestCount = asNumber(record.requestCount ?? record.request_count, requests.length);
  const estimatedInputTokens = asNumber(record.estimatedInputTokens ?? record.estimated_input_tokens, 0);
  const estimatedOutputTokens = asNumber(record.estimatedOutputTokens ?? record.estimated_output_tokens, 0);
  const estimatedTotalTokens = asNumber(record.estimatedTotalTokens ?? record.estimated_total_tokens, 0);

  if (requestCount <= 0 && estimatedTotalTokens <= 0 && requests.length === 0) {
    return undefined;
  }

  return {
    requestCount,
    estimatedInputTokens,
    estimatedOutputTokens,
    estimatedTotalTokens,
    requests,
  };
}

function parseChatMessage(value: unknown): ChatMessage | null {
  const record = asRecord(value);
  const id = asNonEmptyString(record.id);

  if (!id) {
    return null;
  }

  return {
    id,
    role: parseChatRole(record.role),
    content: asNonEmptyString(record.content) ?? "",
    createdAt: asNumber(record.createdAt ?? record.created_at, Date.now()),
    status: parseChatStatus(record.status),
    mode: asChatMode(record.mode),
    usage: parseTurnUsage(record.usage),
    toolActivity: Array.isArray(record.toolActivity ?? record.tool_activity)
      ? ((record.toolActivity ?? record.tool_activity) as unknown[])
        .map(parseToolActivity)
        .filter((entry: ToolActivity | null): entry is ToolActivity => entry !== null)
      : [],
    notices: Array.isArray(record.notices) ? record.notices.map(parseNotice) : [],
  };
}

function parseChat(value: unknown): Chat | null {
  const record = asRecord(value);
  const id = asNonEmptyString(record.id);
  const documentId = asNonEmptyString(record.documentId ?? record.document_id);

  if (!id || !documentId) {
    return null;
  }

  const messages = Array.isArray(record.messages)
    ? record.messages
      .map(parseChatMessage)
      .filter((message): message is ChatMessage => message !== null)
    : [];

  const createdAt = asNumber(record.createdAt ?? record.created_at, Date.now());

  return {
    id,
    name: asNonEmptyString(record.name) ?? "Untitled chat",
    documentId,
    messages,
    createdAt,
    updatedAt: asNumber(record.updatedAt ?? record.updated_at, createdAt),
  };
}

function parseToolActivity(value: unknown): ToolActivity | null {
  const record = asRecord(value);
  const event = asNonEmptyString(record.event);
  if (!event) {
    return null;
  }

  return {
    event,
    ...record,
  };
}

function parseNotice(value: unknown): AgentNotice {
  if (typeof value === "string") {
    return { message: value };
  }

  const record = asRecord(value);
  return {
    code: asNonEmptyString(record.code),
    message: asNonEmptyString(record.message),
    statusCode: typeof record.statusCode === "number" ? record.statusCode : undefined,
    items: asStringArray(record.items),
  };
}

function parseAgentTurnResponse(value: unknown): AgentTurnResponse {
  const record = asRecord(value);
  const proposal = parseProposal(record.proposal);
  const review = parseReview(record.review);
  const rawToolActivity = record.toolActivity ?? record.tool_activity;
  const toolActivity = Array.isArray(rawToolActivity)
    ? rawToolActivity
      .map(parseToolActivity)
      .filter((entry: ToolActivity | null): entry is ToolActivity => entry !== null)
    : [];
  const notices = Array.isArray(record.notices) ? record.notices.map(parseNotice) : [];

  return {
    chatId: asNonEmptyString(record.chatId ?? record.chat_id),
    message: asNonEmptyString(record.message) ?? "",
    mode: asChatMode(record.mode) ?? "agent",
    intent: asNonEmptyString(record.intent) ?? null,
    resultType:
      record.resultType === "revision_staged" || record.result_type === "revision_staged"
        ? "revision_staged"
        : record.resultType === "clarify" || record.result_type === "clarify"
          ? "clarify"
          : "answer",
    documentSessionId: asNonEmptyString(record.documentSessionId ?? record.document_session_id) ?? null,
    baseRevisionId: asNonEmptyString(record.baseRevisionId ?? record.base_revision_id) ?? null,
    revisionId:
      asNonEmptyString(record.revisionId ?? record.revision_id) ??
      proposal?.revisionId ??
      review?.revisionId ??
      null,
    status: asNonEmptyString(record.status) ?? "completed",
    usage: parseTurnUsage(record.usage),
    proposal,
    review,
    toolActivity,
    notices,
  };
}

function parseSessionRefreshPayload(value: unknown): SessionRefreshPayload {
  const record = asRecord(value);
  const session = parseSessionSummary(record.session);
  const revisions = Array.isArray(record.revisions)
    ? record.revisions
      .map(parseSessionRevisionSummary)
      .filter((revision): revision is SessionRevisionSummary => revision !== null)
    : [];
  const sourceHtml = asNonEmptyString(record.sourceHtml ?? record.source_html) ?? null;

  return {
    documentSessionId:
      asNonEmptyString(record.documentSessionId ?? record.document_session_id) ?? session.sessionId,
    html: asNonEmptyString(record.html) ?? sourceHtml ?? undefined,
    sourceHtml,
    session,
    revisions,
    result: parseMutationResult(record.result),
  };
}

function parseDocumentRecord(value: unknown): DocumentRecord | null {
  const record = asRecord(value);
  const id = asNonEmptyString(record.id);
  if (!id) {
    return null;
  }

  const outline = Array.isArray(record.outline)
    ? record.outline
      .map((item) => {
        const outlineRecord = asRecord(item);
        const outlineId = asNonEmptyString(outlineRecord.id);
        if (!outlineId) {
          return null;
        }

        return {
          id: outlineId,
          title: asNonEmptyString(outlineRecord.title) ?? "Untitled",
          level: asNumber(outlineRecord.level, 1),
        };
      })
      .filter((item): item is DocumentRecord["outline"][number] => item !== null)
    : [];

  const revisions = Array.isArray(record.revisions)
    ? record.revisions
      .map(parseSessionRevisionSummary)
      .filter((revision): revision is SessionRevisionSummary => revision !== null)
    : [];

  return {
    id,
    name: asNonEmptyString(record.name) ?? "Untitled document",
    kind: parseDocumentKind(record.kind),
    mimeType: asNonEmptyString(record.mimeType ?? record.mime_type) ?? "",
    size: asNumber(record.size, 0),
    status: parseDocumentStatus(record.status),
    html: asNonEmptyString(record.html) ?? "",
    sourceHtml: asNonEmptyString(record.sourceHtml ?? record.source_html) ?? null,
    outline,
    wordCount: asNumber(record.wordCount ?? record.word_count, 0),
    createdAt: asNumber(record.createdAt ?? record.created_at, Date.now()),
    updatedAt: asNumber(record.updatedAt ?? record.updated_at, Date.now()),
    backendDocId: asNonEmptyString(record.backendDocId ?? record.backend_doc_id),
    documentSessionId: asNonEmptyString(record.documentSessionId ?? record.document_session_id),
    baseRevisionId: asNonEmptyString(record.baseRevisionId ?? record.base_revision_id) ?? null,
    currentRevisionId: asNonEmptyString(record.currentRevisionId ?? record.current_revision_id) ?? null,
    pendingRevisionId: asNonEmptyString(record.pendingRevisionId ?? record.pending_revision_id),
    revisionStatus: asNonEmptyString(record.revisionStatus ?? record.revision_status) ?? null,
    sessionState: asNonEmptyString(record.sessionState ?? record.session_state) ?? null,
    reviewPayload: parseReview(record.reviewPayload ?? record.review_payload) ?? null,
    revisions,
    error: asNonEmptyString(record.error),
  };
}

function tryParseJson(payload: string) {
  try {
    return JSON.parse(payload) as unknown;
  } catch {
    return payload;
  }
}

function extractErrorMessage(payload: unknown): string | undefined {
  if (typeof payload === "string") {
    return payload.trim() || undefined;
  }

  const record = asRecord(payload);
  const detail = asNonEmptyString(record.detail);
  if (detail) {
    return detail;
  }

  const error = asNonEmptyString(record.error);
  if (error) {
    return error;
  }

  const message = asNonEmptyString(record.message);
  if (message) {
    return message;
  }

  if (Array.isArray(record.notices)) {
    const noticeText = record.notices
      .map((notice) => {
        const noticeRecord = asRecord(notice);
        const parts = [
          asNonEmptyString(noticeRecord.message),
          ...asStringArray(noticeRecord.items),
        ].filter(Boolean);
        return parts.join(" ").trim();
      })
      .filter(Boolean)
      .join(" ")
      .trim();

    if (noticeText) {
      return noticeText;
    }
  }

  return undefined;
}

function extractErrorMessageFromBody(bodyText: string, fallbackMessage: string) {
  const trimmed = bodyText.trim();
  if (!trimmed) {
    return fallbackMessage;
  }

  return extractErrorMessage(tryParseJson(trimmed)) ?? trimmed;
}

interface AgentTurnStreamCallbacks {
  onTextChunk?: (chunk: string) => void;
  onToolActivity?: (toolActivity: ToolActivity[]) => void;
  onNotice?: (notices: AgentNotice[]) => void;
  traceId?: string;
  apiBaseUrl?: string;
}

async function readAgentEventStream(
  response: Response,
  callbacks: AgentTurnStreamCallbacks = {},
): Promise<AgentTurnResponse> {
  const { onTextChunk, onToolActivity, onNotice, traceId, apiBaseUrl } = callbacks;
  const reader = response.body?.getReader();

  if (!reader) {
    throw new ApiError("Streaming response did not provide a readable body.");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let message = "";
  const notices: AgentNotice[] = [];
  const toolActivity: ToolActivity[] = [];
  let finalPayload: AgentTurnResponse | null = null;

  const processEventBlock = (eventBlock: string) => {
    if (!eventBlock.trim()) {
      return;
    }

    let eventName = "message";
    const dataLines: string[] = [];

    for (const line of eventBlock.split("\n")) {
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trim());
      }
    }

    if (dataLines.length === 0) {
      return;
    }

    const parsed = tryParseJson(dataLines.join("\n"));

    if (eventName === "assistant_delta") {
      const chunk = typeof parsed === "string" ? parsed : asNonEmptyString(asRecord(parsed).message) ?? "";
      if (chunk) {
        message += chunk;
        onTextChunk?.(chunk);
      }
      return;
    }

    if (eventName === "tool_started" || eventName === "tool_finished") {
      const activity = parseToolActivity({ event: eventName, ...asRecord(parsed) });
      if (activity) {
        toolActivity.push(activity);
        onToolActivity?.([...toolActivity]);
        if (traceId && apiBaseUrl) {
          void logFrontendDebug(apiBaseUrl, traceId, "agent_turn.stream.tool_activity", activity);
        }
      }
      return;
    }

    if (eventName === "notice") {
      notices.push(parseNotice(parsed));
      onNotice?.([...notices]);
      if (traceId && apiBaseUrl) {
        void logFrontendDebug(apiBaseUrl, traceId, "agent_turn.stream.notice", parsed);
      }
      return;
    }

    if (eventName === "done") {
      finalPayload = parseAgentTurnResponse(parsed);
      if (traceId && apiBaseUrl) {
        void logFrontendDebug(apiBaseUrl, traceId, "agent_turn.stream.done", parsed);
      }
    }
  };

  while (true) {
    const { value, done } = await reader.read();

    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const eventBlock of events) {
      processEventBlock(eventBlock);
    }
  }

  buffer += decoder.decode();
  if (buffer.trim()) {
    buffer.split("\n\n").forEach(processEventBlock);
  }

  const resolved: AgentTurnResponse = finalPayload ?? {
    message,
    mode: "agent",
    resultType: "answer",
    status: "completed",
    toolActivity: [],
    notices: [],
  };

  if (traceId && apiBaseUrl) {
    void logFrontendDebug(apiBaseUrl, traceId, "agent_turn.stream.resolved", {
      ...resolved,
      message: resolved.message || message,
      toolActivity: resolved.toolActivity.length ? resolved.toolActivity : toolActivity,
      notices: resolved.notices.length ? resolved.notices : notices,
    });
  }

  return {
    ...resolved,
    message: resolved.message || message,
    toolActivity: resolved.toolActivity.length ? resolved.toolActivity : toolActivity,
    notices: resolved.notices.length ? resolved.notices : notices,
  };
}

async function readJsonResponse<T>(response: Response, fallbackMessage: string, transform: (payload: unknown) => T) {
  if (!response.ok) {
    const bodyText = await response.text();
    throw new ApiError(extractErrorMessageFromBody(bodyText, fallbackMessage), response.status);
  }

  return transform(await response.json());
}

export async function checkBackendHealth(baseUrl: string, timeoutMs: number) {
  const candidates = ["/health", "/api/health"];

  for (const path of candidates) {
    try {
      const response = await fetchWithTimeout(joinUrl(baseUrl, path), { method: "GET" }, timeoutMs);

      if (!response.ok) {
        continue;
      }

      const payload = await response.json() as { status?: string; version?: string };
      return {
        status: payload.status ?? "ok",
        version: payload.version ?? null,
      };
    } catch {
      // Try the next health endpoint candidate.
    }
  }

  throw new ApiError("Unable to reach the backend health endpoint.");
}

export async function sendAgentTurnToBackend(args: {
  settings: AppSettings;
  chatId: string;
  documentSessionId: string;
  currentRevisionId?: string | null;
  documentId: string;
  visibleBlockIds?: string[];
  history: ChatMessage[];
  prompt: string;
  onTextChunk?: (chunk: string) => void;
  onToolActivity?: (toolActivity: ToolActivity[]) => void;
  onNotice?: (notices: AgentNotice[]) => void;
  signal?: AbortSignal;
}): Promise<AgentTurnResponse> {
  const {
    settings,
    chatId,
    documentSessionId,
    currentRevisionId,
    documentId,
    visibleBlockIds = [],
    history,
    prompt,
    onTextChunk,
    onToolActivity,
    onNotice,
    signal,
  } = args;
  const traceId = createTraceId();

  const payload = {
    provider: settings.provider,
    model: settings.modelOverride.trim() || undefined,
    chatId,
    documentSessionId,
    mode: settings.mode,
    baseRevisionId: currentRevisionId ?? undefined,
    prompt,
    workspaceContext: {
      documentIds: [documentId],
      activePane: "editor",
      visibleBlockIds,
    },
    agentConfig: {
      maxInputTokens: settings.agentConfig.maxInputTokens,
      sessionContextBudgetTokens: settings.agentConfig.sessionContextBudgetTokens,
      toolResultBudgetTokens: settings.agentConfig.toolResultBudgetTokens,
      maxToolBatchSize: settings.agentConfig.maxToolBatchSize,
      maxParallelTools: settings.agentConfig.maxParallelTools,
      maxHeavyToolsPerTurn: settings.agentConfig.maxHeavyToolsPerTurn,
      autoCompactSession: settings.agentConfig.autoCompactSession,
    },
    history: history.map((message) => ({
      role: message.role === "error" ? "assistant" : message.role,
      content: message.content,
    })),
  };

  const endpoint = settings.streaming ? "/api/agent/turn/stream" : "/api/agent/turn";
  await logFrontendDebug(settings.apiBaseUrl, traceId, "agent_turn.request_prepared", {
    endpoint,
    streaming: settings.streaming,
    payload,
  });

  let response: Response;
  try {
    response = await fetchWithTimeout(
      joinUrl(settings.apiBaseUrl, endpoint),
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-DocPilot-Trace-Id": traceId,
        },
        body: JSON.stringify(payload),
      },
      settings.requestTimeoutMs,
      signal,
    );
  } catch (error) {
    await logFrontendDebug(settings.apiBaseUrl, traceId, "agent_turn.request_failed", {
      message: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }

  await logFrontendDebug(settings.apiBaseUrl, traceId, "agent_turn.response_received", {
    statusCode: response.status,
    contentType: response.headers.get("content-type") ?? "",
    responseTraceId: response.headers.get("X-DocPilot-Trace-Id") ?? null,
  });

  const contentType = response.headers.get("content-type") ?? "";
  if (settings.streaming && contentType.includes("text/event-stream")) {
    return readAgentEventStream(response, {
      onTextChunk,
      onToolActivity,
      onNotice,
      traceId,
      apiBaseUrl: settings.apiBaseUrl,
    });
  }

  const parsed = await readJsonResponse(response, "The backend returned an error.", parseAgentTurnResponse);
  await logFrontendDebug(settings.apiBaseUrl, traceId, "agent_turn.response_resolved", parsed);
  return parsed;
}

export async function importDocumentFromBackend(args: {
  settings: AppSettings;
  file: File;
  signal?: AbortSignal;
}): Promise<ImportedDocumentPayload> {
  const { settings, file, signal } = args;
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, "/api/documents/import"),
    { method: "POST", body: formData },
    settings.requestTimeoutMs,
    signal,
  );

  return readJsonResponse(response, "Failed to import document.", (payload) => {
    const record = asRecord(payload);
    return {
      docId: asNonEmptyString(record.docId ?? record.doc_id) ?? null,
      documentSessionId: asNonEmptyString(record.documentSessionId ?? record.document_session_id) ?? null,
      baseRevisionId: asNonEmptyString(record.baseRevisionId ?? record.base_revision_id) ?? null,
      html: asNonEmptyString(record.html) ?? "",
      wordCount: asNumber(record.wordCount ?? record.word_count, 0),
      pageCount: asNumber(record.pageCount ?? record.page_count, 0),
      filename: asNonEmptyString(record.filename) ?? file.name,
    };
  });
}

export async function exportDocumentToDocx(args: {
  settings: AppSettings;
  documentSessionId: string;
  filename: string;
}): Promise<Blob> {
  const { settings, documentSessionId, filename } = args;

  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, "/api/documents/export"),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ documentSessionId, filename }),
    },
    settings.requestTimeoutMs,
  );

  if (!response.ok) {
    const bodyText = await response.text();
    throw new ApiError(extractErrorMessageFromBody(bodyText, "Export failed."), response.status);
  }

  return response.blob();
}

export async function refreshSessionFromBackend(args: {
  settings: AppSettings;
  documentSessionId: string;
  signal?: AbortSignal;
}): Promise<SessionRefreshPayload> {
  const { settings, documentSessionId, signal } = args;
  const [sessionResponse, projectionResponse] = await Promise.all([
    fetchWithTimeout(
      joinUrl(settings.apiBaseUrl, `/api/agent/sessions/${documentSessionId}`),
      { method: "GET", headers: { "Content-Type": "application/json" } },
      settings.requestTimeoutMs,
      signal,
    ),
    fetchWithTimeout(
      joinUrl(settings.apiBaseUrl, `/api/agent/sessions/${documentSessionId}/projection`),
      { method: "GET", headers: { "Content-Type": "application/json" } },
      settings.requestTimeoutMs,
      signal,
    ),
  ]);

  const sessionPayload = await readJsonResponse(sessionResponse, "Failed to load document session.", (payload) => payload);
  const projectionPayload = await readJsonResponse(projectionResponse, "Failed to load document projection.", (payload) => payload);

  return parseSessionRefreshPayload({
    ...asRecord(sessionPayload),
    ...asRecord(projectionPayload),
  });
}

export async function applyRevisionInBackend(args: {
  settings: AppSettings;
  revisionId: string;
}): Promise<SessionRefreshPayload> {
  const { settings, revisionId } = args;
  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, `/api/agent/revisions/${revisionId}/apply`),
    { method: "POST", headers: { "Content-Type": "application/json" } },
    settings.requestTimeoutMs,
  );

  return readJsonResponse(response, "Failed to apply revision.", parseSessionRefreshPayload);
}

export async function rejectRevisionInBackend(args: {
  settings: AppSettings;
  revisionId: string;
}): Promise<SessionRefreshPayload> {
  const { settings, revisionId } = args;
  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, `/api/agent/revisions/${revisionId}/reject`),
    { method: "POST", headers: { "Content-Type": "application/json" } },
    settings.requestTimeoutMs,
  );

  return readJsonResponse(response, "Failed to reject revision.", parseSessionRefreshPayload);
}

export async function rollbackRevisionInBackend(args: {
  settings: AppSettings;
  revisionId: string;
}): Promise<SessionRefreshPayload> {
  const { settings, revisionId } = args;
  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, `/api/agent/revisions/${revisionId}/rollback`),
    { method: "POST", headers: { "Content-Type": "application/json" } },
    settings.requestTimeoutMs,
  );

  return readJsonResponse(response, "Failed to roll back revision.", parseSessionRefreshPayload);
}

export function getErrorMessage(error: unknown) {
  if (error instanceof DOMException && error.name === "AbortError") {
    return "Request cancelled.";
  }

  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown error.";
}

export class HTTPClient {
  constructor(private baseUrl: string) { }

  async get<T>(path: string): Promise<{ data: T }> {
    const response = await fetch(joinUrl(this.baseUrl, path), {
      method: "GET",
      headers: { "Content-Type": "application/json" },
    });

    if (!response.ok) {
      const error = await response.text();
      throw new ApiError(extractErrorMessageFromBody(error, "Request failed"), response.status);
    }

    const data = (await response.json()) as T;
    return { data };
  }

  async post<T>(path: string, body: unknown): Promise<{ data: T }> {
    const response = await fetch(joinUrl(this.baseUrl, path), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const error = await response.text();
      throw new ApiError(extractErrorMessageFromBody(error, "Request failed"), response.status);
    }

    const data = (await response.json()) as T;
    return { data };
  }

  async delete<T>(path: string): Promise<{ data: T }> {
    const response = await fetch(joinUrl(this.baseUrl, path), {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
    });

    if (!response.ok) {
      const error = await response.text();
      throw new ApiError(extractErrorMessageFromBody(error, "Request failed"), response.status);
    }

    const data = (await response.json()) as T;
    return { data };
  }
}

export async function loadChatsFromBackend(baseUrl: string) {
  const response = await fetch(joinUrl(baseUrl, "/api/chats"), {
    method: "GET",
    headers: { "Content-Type": "application/json" },
  });

  if (!response.ok) {
    throw new ApiError("Failed to load chats", response.status);
  }

  const payload = (await response.json()) as { chats: unknown[] };
  return (payload.chats ?? [])
    .map(parseChat)
    .filter((chat): chat is Chat => chat !== null);
}

export async function loadDocumentsFromBackend(baseUrl: string): Promise<DocumentRecord[]> {
  const response = await fetch(joinUrl(baseUrl, "/api/documents"), {
    method: "GET",
    headers: { "Content-Type": "application/json" },
  });

  if (!response.ok) {
    throw new ApiError("Failed to load documents", response.status);
  }

  const payload = (await response.json()) as { documents: unknown[] };
  return (payload.documents ?? [])
    .map(parseDocumentRecord)
    .filter((document): document is DocumentRecord => document !== null);
}

export async function saveDocumentToBackend(baseUrl: string, document: DocumentRecord): Promise<DocumentRecord> {
  const response = await fetch(joinUrl(baseUrl, "/api/documents"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(document),
  });

  if (!response.ok) {
    throw new ApiError("Failed to save document", response.status);
  }

  const payload = (await response.json()) as { document: unknown };
  const saved = parseDocumentRecord(payload.document);
  if (!saved) {
    throw new ApiError("Backend returned an invalid document payload.");
  }
  return saved;
}

export async function deleteDocumentFromBackend(baseUrl: string, documentId: string) {
  const response = await fetch(joinUrl(baseUrl, `/api/documents/${documentId}`), {
    method: "DELETE",
    headers: { "Content-Type": "application/json" },
  });

  if (!response.ok) {
    throw new ApiError("Failed to delete document", response.status);
  }

  return { ok: true };
}

export async function saveChat(
  baseUrl: string,
  chat: { id: string; name: string; documentId: string; messages: ChatMessage[] },
) {
  const response = await fetch(joinUrl(baseUrl, "/api/chats"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(chat),
  });

  if (!response.ok) {
    throw new ApiError("Failed to save chat", response.status);
  }

  const payload = (await response.json()) as { chat: unknown };
  const savedChat = parseChat(payload.chat);

  if (!savedChat) {
    throw new ApiError("Backend returned an invalid chat payload.");
  }

  return savedChat;
}

export async function updateChat(
  baseUrl: string,
  chatId: string,
  updates: { name?: string; messages?: ChatMessage[] },
) {
  const response = await fetch(joinUrl(baseUrl, `/api/chats/${chatId}`), {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(updates),
  });

  if (!response.ok) {
    throw new ApiError("Failed to update chat", response.status);
  }

  const payload = (await response.json()) as { chat: unknown };
  const savedChat = parseChat(payload.chat);

  if (!savedChat) {
    throw new ApiError("Backend returned an invalid chat payload.");
  }

  return savedChat;
}

export async function deleteChat(baseUrl: string, chatId: string) {
  const response = await fetch(joinUrl(baseUrl, `/api/chats/${chatId}`), {
    method: "DELETE",
    headers: { "Content-Type": "application/json" },
  });

  if (!response.ok) {
    throw new ApiError("Failed to delete chat", response.status);
  }

  return { ok: true };
}