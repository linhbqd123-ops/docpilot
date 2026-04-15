import DOMPurify from "dompurify";
import { marked } from "marked";

import type {
  DocumentKind,
  DocumentRecord,
  OutlineItem,
  RevisionReview,
  SessionRevisionSummary,
  SessionSummary,
} from "@/app/types";
import { countWords, slugify } from "@/lib/utils";

marked.setOptions({ gfm: true, breaks: true });

const BLOCK_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,blockquote,td,th";

function sanitizeHtml(html: string) {
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function createHtmlFromText(rawText: string) {
  const paragraphs = rawText
    .split(/\n{2,}/)
    .map((block) => block.trim())
    .filter(Boolean)
    .map((block) => `<p>${escapeHtml(block).replaceAll("\n", "<br />")}</p>`);

  return paragraphs.join("");
}

function inferNodeType(tagName: string) {
  if (/^h[1-6]$/i.test(tagName)) {
    return "heading";
  }

  if (tagName === "li") {
    return "list_item";
  }

  if (tagName === "td" || tagName === "th") {
    return "table_cell";
  }

  return "paragraph";
}

function ensureNodeIdentity(node: HTMLElement, fallbackId: string) {
  const existingId =
    node.getAttribute("data-doc-node-id") ??
    node.getAttribute("data-anchor") ??
    node.id;
  const resolvedId = existingId || fallbackId;

  if (!node.getAttribute("data-doc-node-id")) {
    node.setAttribute("data-doc-node-id", resolvedId);
  }

  if (!node.getAttribute("data-anchor")) {
    node.setAttribute("data-anchor", resolvedId);
  }

  if (!node.getAttribute("data-doc-node-type")) {
    node.setAttribute("data-doc-node-type", inferNodeType(node.tagName.toLowerCase()));
  }

  if (/^h[1-6]$/i.test(node.tagName) && !node.id) {
    node.id = resolvedId;
  }
}

export function inferDocumentKind(filename: string, mimeType: string): DocumentKind {
  const lowerName = filename.toLowerCase();

  if (mimeType.includes("html") || lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
    return "html";
  }

  if (mimeType.includes("markdown") || lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
    return "markdown";
  }

  if (mimeType === "text/plain" || lowerName.endsWith(".txt")) {
    return "text";
  }

  if (mimeType.includes("pdf") || lowerName.endsWith(".pdf")) {
    return "pdf";
  }

  if (
    mimeType.includes("wordprocessingml") ||
    lowerName.endsWith(".docx") ||
    lowerName.endsWith(".doc")
  ) {
    return "docx";
  }

  return "unknown";
}

export function normalizeDocumentHtml(rawHtml: string) {
  const parser = new DOMParser();
  const source = rawHtml.trim() ? rawHtml : "<p></p>";
  const parsed = parser.parseFromString(source, "text/html");
  const body = parsed.body;
  const outline: OutlineItem[] = [];

  body.querySelectorAll<HTMLElement>("h1,h2,h3,h4,h5,h6").forEach((heading, index) => {
    const title = heading.textContent?.trim() || `Section ${index + 1}`;
    const id =
      heading.id ||
      heading.getAttribute("data-doc-node-id") ||
      heading.getAttribute("data-anchor") ||
      slugify(title) ||
      `section-${index + 1}`;
    heading.id = id;
    ensureNodeIdentity(heading, id);
    outline.push({
      id,
      title,
      level: Number(heading.tagName.slice(1)),
    });
  });

  body.querySelectorAll<HTMLElement>(BLOCK_SELECTOR).forEach((node, index) => {
    ensureNodeIdentity(node, `block-${index + 1}`);
  });

  const html = sanitizeHtml(body.innerHTML);
  const text = body.textContent ?? "";

  return {
    html,
    outline,
    wordCount: countWords(text),
  };
}

export async function createDocumentFromFile(file: File): Promise<DocumentRecord> {
  const kind = inferDocumentKind(file.name, file.type);
  const timestamp = Date.now();

  if (kind === "docx" || kind === "pdf" || kind === "unknown") {
    return {
      id: crypto.randomUUID(),
      name: file.name,
      kind,
      mimeType: file.type,
      size: file.size,
      status: "needs_backend",
      html: "",
      outline: [],
      wordCount: 0,
      createdAt: timestamp,
      updatedAt: timestamp,
      baseRevisionId: null,
      currentRevisionId: null,
      reviewPayload: null,
      revisionStatus: null,
      revisions: [],
      sessionState: null,
      error: "This format requires the backend import endpoint before it can be rendered.",
    };
  }

  let rawHtml = "";

  if (kind === "html") {
    rawHtml = await file.text();
  } else if (kind === "markdown") {
    rawHtml = await marked.parse(await file.text()) as string;
  } else {
    rawHtml = createHtmlFromText(await file.text());
  }

  const normalized = normalizeDocumentHtml(rawHtml);

  return {
    id: crypto.randomUUID(),
    name: file.name,
    kind,
    mimeType: file.type,
    size: file.size,
    status: "ready",
    html: normalized.html,
    outline: normalized.outline,
    wordCount: normalized.wordCount,
    createdAt: timestamp,
    updatedAt: timestamp,
    baseRevisionId: null,
    currentRevisionId: null,
    reviewPayload: null,
    revisionStatus: null,
    revisions: [],
    sessionState: null,
  };
}

export function applyDocumentProjection(
  document: DocumentRecord,
  rawHtml: string,
  patch: Partial<DocumentRecord> = {},
): DocumentRecord {
  const normalized = normalizeDocumentHtml(rawHtml);

  return {
    ...document,
    html: normalized.html,
    outline: normalized.outline,
    wordCount: normalized.wordCount,
    updatedAt: Date.now(),
    ...patch,
  };
}

export function applyDocumentSessionSummary(
  document: DocumentRecord,
  session: SessionSummary,
  revisions: SessionRevisionSummary[] = document.revisions,
): DocumentRecord {
  return {
    ...document,
    backendDocId: session.docId ?? document.backendDocId,
    documentSessionId: session.sessionId || document.documentSessionId,
    currentRevisionId: session.currentRevisionId ?? document.currentRevisionId ?? null,
    baseRevisionId: session.currentRevisionId ?? document.baseRevisionId ?? null,
    sessionState: session.state ?? document.sessionState ?? null,
    revisions,
    updatedAt: Date.now(),
  };
}

export function stageDocumentReview(
  document: DocumentRecord,
  details: {
    revisionId: string;
    reviewPayload: RevisionReview | null;
    status?: string | null;
    baseRevisionId?: string | null;
  },
): DocumentRecord {
  return {
    ...document,
    pendingRevisionId: details.revisionId,
    reviewPayload: details.reviewPayload,
    revisionStatus: details.status ?? details.reviewPayload?.status ?? "PENDING",
    baseRevisionId: details.baseRevisionId ?? document.baseRevisionId ?? null,
    updatedAt: Date.now(),
  };
}

export function clearDocumentReview(
  document: DocumentRecord,
  options: {
    status?: string | null;
    reviewPayload?: RevisionReview | null;
  } = {},
): DocumentRecord {
  return {
    ...document,
    pendingRevisionId: undefined,
    reviewPayload: options.reviewPayload ?? null,
    revisionStatus: options.status ?? null,
    updatedAt: Date.now(),
  };
}

export function updateDocumentHtml(document: DocumentRecord, rawHtml: string): DocumentRecord {
  return applyDocumentProjection(document, rawHtml);
}

export function rehydrateDocument(document: DocumentRecord): DocumentRecord {
  const normalized = document.html ? normalizeDocumentHtml(document.html) : null;

  return {
    ...document,
    html: normalized?.html ?? document.html,
    outline: normalized?.outline ?? document.outline,
    wordCount: normalized?.wordCount ?? document.wordCount,
    baseRevisionId: document.baseRevisionId ?? null,
    currentRevisionId: document.currentRevisionId ?? null,
    reviewPayload: document.reviewPayload ?? null,
    revisionStatus: document.revisionStatus ?? null,
    revisions: document.revisions ?? [],
    sessionState: document.sessionState ?? null,
  };
}