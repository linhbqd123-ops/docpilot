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
  return new URL(path, baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`).toString();
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

export function getErrorMessage(error: unknown) {
  if (error instanceof DOMException && error.name === "AbortError") {
    return "Request cancelled.";
  }

  if (error instanceof Error) {
    return error.message;
  }

  return "Unknown error.";
}