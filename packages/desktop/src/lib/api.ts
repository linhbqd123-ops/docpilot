import type {
  AgentNotice,
  AgentTurnResponse,
  AppSettings,
  ChatMessage,
  ImportedDocumentPayload,
  RevisionProposal,
  RevisionReview,
  RevisionValidation,
  ReviewOperation,
  SessionRefreshPayload,
  SessionRevisionSummary,
  SessionSummary,
  ToolActivity,
} from "@/app/types";

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
    mode: record.mode === "ask" ? "ask" : "agent",
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

  return {
    documentSessionId:
      asNonEmptyString(record.documentSessionId ?? record.document_session_id) ?? session.sessionId,
    html: asNonEmptyString(record.html),
    session,
    revisions,
    result: parseMutationResult(record.result),
  };
}

function tryParseJson(payload: string) {
  try {
    return JSON.parse(payload) as unknown;
  } catch {
    return payload;
  }
}

async function readAgentEventStream(
  response: Response,
  onTextChunk?: (chunk: string) => void,
): Promise<AgentTurnResponse> {
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

  while (true) {
    const { value, done } = await reader.read();

    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const eventBlock of events) {
      if (!eventBlock.trim()) {
        continue;
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
        continue;
      }

      const parsed = tryParseJson(dataLines.join("\n"));

      if (eventName === "assistant_delta") {
        const chunk = typeof parsed === "string" ? parsed : asNonEmptyString(asRecord(parsed).message) ?? "";
        if (chunk) {
          message += chunk;
          onTextChunk?.(chunk);
        }
        continue;
      }

      if (eventName === "tool_started" || eventName === "tool_finished") {
        const activity = parseToolActivity({ event: eventName, ...asRecord(parsed) });
        if (activity) {
          toolActivity.push(activity);
        }
        continue;
      }

      if (eventName === "notice") {
        notices.push(parseNotice(parsed));
        continue;
      }

      if (eventName === "done") {
        finalPayload = parseAgentTurnResponse(parsed);
      }
    }
  }

  const resolved = finalPayload ?? {
    message,
    mode: "agent",
    resultType: "answer",
    status: "completed",
    toolActivity: [],
    notices: [],
  };

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
    throw new ApiError(bodyText || fallbackMessage, response.status);
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
    signal,
  } = args;

  const payload = {
    provider: settings.provider,
    model: settings.modelOverride.trim() || undefined,
    chatId,
    documentSessionId,
    mode: "agent",
    baseRevisionId: currentRevisionId ?? undefined,
    prompt,
    workspaceContext: {
      documentIds: [documentId],
      activePane: "editor",
      visibleBlockIds,
    },
    history: history.map((message) => ({
      role: message.role === "error" ? "assistant" : message.role,
      content: message.content,
    })),
  };

  const endpoint = settings.streaming ? "/api/agent/turn/stream" : "/api/agent/turn";
  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, endpoint),
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    },
    settings.requestTimeoutMs,
    signal,
  );

  const contentType = response.headers.get("content-type") ?? "";
  if (settings.streaming && contentType.includes("text/event-stream")) {
    return readAgentEventStream(response, onTextChunk);
  }

  return readJsonResponse(response, "The backend returned an error.", parseAgentTurnResponse);
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
    throw new ApiError(bodyText || "Export failed.", response.status);
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
      throw new ApiError(error || "Request failed", response.status);
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
      throw new ApiError(error || "Request failed", response.status);
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
      throw new ApiError(error || "Request failed", response.status);
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
  return payload.chats;
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
  return payload.chat;
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
  return payload.chat;
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