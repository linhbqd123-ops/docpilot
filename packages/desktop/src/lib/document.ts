import DOMPurify from "dompurify";
import { marked } from "marked";

import type { DocumentKind, DocumentRecord, OutlineItem } from "@/app/types";
import { countWords, slugify } from "@/lib/utils";

marked.setOptions({ gfm: true, breaks: true });

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

  body.querySelectorAll("h1,h2,h3,h4,h5,h6").forEach((heading, index) => {
    const title = heading.textContent?.trim() || `Section ${index + 1}`;
    const id = heading.id || slugify(title) || `section-${index + 1}`;
    heading.id = id;
    outline.push({
      id,
      title,
      level: Number(heading.tagName.slice(1)),
    });
  });

  body.querySelectorAll("p,li,blockquote,td,th").forEach((node, index) => {
    if (!node.getAttribute("data-block-id")) {
      node.setAttribute("data-block-id", `block-${index + 1}`);
    }
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
  };
}

export function stageDocumentHtml(document: DocumentRecord, rawHtml: string): DocumentRecord {
  const normalized = normalizeDocumentHtml(rawHtml);

  return {
    ...document,
    pendingHtml: normalized.html,
    pendingOutline: normalized.outline,
    pendingWordCount: normalized.wordCount,
    updatedAt: Date.now(),
  };
}

export function commitPendingDocument(document: DocumentRecord): DocumentRecord {
  if (!document.pendingHtml) {
    return document;
  }

  return {
    ...document,
    html: document.pendingHtml,
    outline: document.pendingOutline ?? document.outline,
    wordCount: document.pendingWordCount ?? document.wordCount,
    pendingHtml: undefined,
    pendingOutline: undefined,
    pendingWordCount: undefined,
    updatedAt: Date.now(),
  };
}

export function discardPendingDocument(document: DocumentRecord): DocumentRecord {
  return {
    ...document,
    pendingHtml: undefined,
    pendingOutline: undefined,
    pendingWordCount: undefined,
    updatedAt: Date.now(),
  };
}

export function updateDocumentHtml(document: DocumentRecord, rawHtml: string): DocumentRecord {
  const normalized = normalizeDocumentHtml(rawHtml);

  return {
    ...document,
    html: normalized.html,
    outline: normalized.outline,
    wordCount: normalized.wordCount,
    updatedAt: Date.now(),
  };
}

export function rehydrateDocument(document: DocumentRecord): DocumentRecord {
  const normalized = document.html ? normalizeDocumentHtml(document.html) : null;
  const pending = document.pendingHtml ? normalizeDocumentHtml(document.pendingHtml) : null;

  return {
    ...document,
    html: normalized?.html ?? document.html,
    outline: normalized?.outline ?? document.outline,
    wordCount: normalized?.wordCount ?? document.wordCount,
    pendingHtml: pending?.html,
    pendingOutline: pending?.outline,
    pendingWordCount: pending?.wordCount,
  };
}