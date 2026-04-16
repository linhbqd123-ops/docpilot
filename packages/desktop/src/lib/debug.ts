function joinUrl(baseUrl: string, path: string) {
  const base = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  const normalizedPath = path.startsWith("/") ? path.slice(1) : path;
  return new URL(normalizedPath, base).toString();
}

export function createTraceId() {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function logFrontendDebug(baseUrl: string, traceId: string, event: string, payload: unknown) {
  try {
    await fetch(joinUrl(baseUrl, "/api/debug/frontend"), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        traceId,
        event,
        payload,
      }),
      keepalive: true,
    });
  } catch {
    // Debug logging must never interrupt the user flow.
  }
}