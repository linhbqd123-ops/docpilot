import type { AppSettings, ChatMessage, ChatResponse, DocumentRecord } from "@/app/types";

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
  // Remove leading slash to make path relative to base
  const p = path.startsWith("/") ? path.slice(1) : path;
  return new URL(p, base).toString();
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
      // Keep trying candidate endpoints.
    }
  }

  throw new ApiError("Unable to reach the backend health endpoint.");
}

export async function checkProviderConnection(providerUrl: string, timeoutMs: number) {
  /**
   * Test connection to provider API endpoint.
   * Simply verify the provider domain is reachable by making a HEAD request to root.
   */
  try {
    // Extract base domain from provider URL
    // e.g., "https://api.groq.com/openai/v1" → "https://api.groq.com"
    const url = new URL(providerUrl);
    const baseUrl = `${url.protocol}//${url.host}`;

    const response = await fetchWithTimeout(baseUrl + "/", { method: "GET" }, timeoutMs);

    // Accept any 2xx or 3xx response as success
    if (response.ok || response.status < 400) {
      return {
        status: "ok",
        version: null,
      };
    }

    throw new ApiError(`Provider returned status ${response.status}`);
  } catch (error) {
    throw new ApiError(
      `Unable to reach provider endpoint: ${error instanceof Error ? error.message : String(error)}`
    );
  }
}

async function readEventStream(
  response: Response,
  onTextChunk?: (chunk: string) => void,
): Promise<ChatResponse> {
  const reader = response.body?.getReader();

  if (!reader) {
    throw new ApiError("Streaming response did not provide a readable body.");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let message = "";
  let documentHtml: string | undefined;
  let notices: string[] | undefined;

  while (true) {
    const { value, done } = await reader.read();

    if (done) {
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split("\n\n");
    buffer = events.pop() ?? "";

    for (const event of events) {
      const line = event
        .split("\n")
        .find((entry) => entry.startsWith("data:"));

      if (!line) {
        continue;
      }

      const payload = line.slice(5).trim();

      if (!payload || payload === "[DONE]") {
        continue;
      }

      try {
        const parsed = JSON.parse(payload) as {
          delta?: string;
          message?: string;
          documentHtml?: string;
          notices?: string[];
        };
        const nextChunk = parsed.delta ?? parsed.message ?? "";

        if (nextChunk) {
          message += nextChunk;
          onTextChunk?.(nextChunk);
        }

        if (parsed.documentHtml) {
          documentHtml = parsed.documentHtml;
        }

        if (parsed.notices?.length) {
          notices = parsed.notices;
        }
      } catch {
        message += payload;
        onTextChunk?.(payload);
      }
    }
  }

  return { message, documentHtml, notices };
}

export async function sendPromptToBackend(args: {
  settings: AppSettings;
  document: DocumentRecord;
  history: ChatMessage[];
  prompt: string;
  onTextChunk?: (chunk: string) => void;
  signal?: AbortSignal;
}): Promise<ChatResponse> {
  const { settings, document, history, prompt, onTextChunk, signal } = args;

  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, "/api/chat"),
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        provider: settings.provider,
        model: settings.modelOverride.trim() || undefined,
        streaming: settings.streaming,
        prompt,
        document: {
          id: document.id,
          name: document.name,
          kind: document.kind,
          html: document.pendingHtml ?? document.html,
          outline: document.pendingOutline ?? document.outline,
          wordCount: document.pendingWordCount ?? document.wordCount,
        },
        history: history.map((message) => ({ role: message.role, content: message.content })),
      }),
    },
    settings.requestTimeoutMs,
    signal,
  );

  if (!response.ok) {
    const bodyText = await response.text();
    throw new ApiError(bodyText || "The backend returned an error.", response.status);
  }

  const contentType = response.headers.get("content-type") ?? "";

  if (settings.streaming && contentType.includes("text/event-stream")) {
    return readEventStream(response, onTextChunk);
  }

  const payload = await response.json() as ChatResponse;
  return {
    message: payload.message ?? "",
    documentHtml: payload.documentHtml,
    notices: payload.notices ?? [],
  };
}

export async function importDocumentFromBackend(args: {
  settings: AppSettings;
  file: File;
  signal?: AbortSignal;
}): Promise<{ docId: string; html: string; wordCount: number; pageCount: number }> {
  const { settings, file, signal } = args;
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, "/api/documents/import"),
    { method: "POST", body: formData },
    settings.requestTimeoutMs,
    signal,
  );

  if (!response.ok) {
    const bodyText = await response.text();
    throw new ApiError(bodyText || "Failed to import document.", response.status);
  }

  return response.json() as Promise<{ docId: string; html: string; wordCount: number; pageCount: number }>;
}

export async function exportDocumentToDocx(args: {
  settings: AppSettings;
  html: string;
  backendDocId?: string;
  filename: string;
}): Promise<Blob> {
  const { settings, html, backendDocId, filename } = args;

  const response = await fetchWithTimeout(
    joinUrl(settings.apiBaseUrl, "/api/documents/export"),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ html, doc_id: backendDocId ?? null, filename }),
    },
    settings.requestTimeoutMs,
  );

  if (!response.ok) {
    const bodyText = await response.text();
    throw new ApiError(bodyText || "Export failed.", response.status);
  }

  return response.blob();
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

// Simple HTTP client for general API calls (used by key management, etc.)
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