import type {
  RevisionLayoutDiff,
  RevisionReview,
  RevisionStyleDiff,
  RevisionTextDiff,
  ReviewOperation,
} from "@/app/types";

export type ReviewChangeKind = "text" | "style" | "structure" | "mixed" | "metadata";
export type ReviewPreviewSource = "backend" | "simulated" | "metadata";

export interface ReviewChangeGroup {
  id: string;
  anchorBlockId: string | null;
  title: string;
  subtitle: string;
  beforeText: string;
  afterText: string;
  exactPreview: boolean;
  kind: ReviewChangeKind;
  previewSource: ReviewPreviewSource;
  details: string[];
  operations: ReviewOperation[];
}

interface BlockSnapshot {
  blockId: string;
  text: string;
  title: string;
  subtitle: string;
  html: string;
}

interface ReviewChangeAccumulator {
  id: string;
  order: number;
  anchorBlockId: string | null;
  operations: ReviewOperation[];
  textDiffs: RevisionTextDiff[];
  styleDiffs: RevisionStyleDiff[];
  layoutDiffs: RevisionLayoutDiff[];
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

function asString(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function normalizeWhitespace(text: string) {
  return text.replace(/\s+/g, " ").trim();
}

function trimPreview(text: string, maxChars = 220) {
  const normalized = normalizeWhitespace(text);
  if (!normalized) {
    return "";
  }

  if (normalized.length <= maxChars) {
    return normalized;
  }

  return `${normalized.slice(0, Math.max(0, maxChars - 3)).trimEnd()}...`;
}

function startCase(value: string) {
  return value
    .replace(/[_-]+/g, " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function textOpLabel(operation: string) {
  switch (operation) {
    case "REPLACE_TEXT_RANGE":
      return "Replace text";
    case "INSERT_TEXT_AT":
      return "Insert text";
    case "DELETE_TEXT_RANGE":
      return "Delete text";
    case "UPDATE_CELL_CONTENT":
      return "Update cell";
    case "CREATE_BLOCK":
      return "Create block";
    case "DELETE_BLOCK":
      return "Delete block";
    case "MOVE_BLOCK":
      return "Move block";
    case "CLONE_BLOCK":
      return "Clone block";
    case "APPLY_STYLE":
      return "Apply style";
    case "APPLY_INLINE_FORMAT":
      return "Inline format";
    case "SET_HEADING_LEVEL":
      return "Change heading";
    case "CHANGE_LIST_TYPE":
      return "Change list type";
    case "CHANGE_LIST_LEVEL":
      return "Change list level";
    case "INSERT_ROW":
      return "Insert row";
    case "DELETE_ROW":
      return "Delete row";
    case "INSERT_COLUMN":
      return "Insert column";
    case "DELETE_COLUMN":
      return "Delete column";
    default:
      return startCase(operation.toLowerCase());
  }
}

function operationKind(operation: string): ReviewChangeKind {
  switch (operation) {
    case "REPLACE_TEXT_RANGE":
    case "INSERT_TEXT_AT":
    case "DELETE_TEXT_RANGE":
    case "UPDATE_CELL_CONTENT":
      return "text";
    case "APPLY_STYLE":
    case "APPLY_INLINE_FORMAT":
    case "SET_HEADING_LEVEL":
      return "style";
    case "CREATE_BLOCK":
    case "DELETE_BLOCK":
    case "MOVE_BLOCK":
    case "CLONE_BLOCK":
    case "CHANGE_LIST_TYPE":
    case "CHANGE_LIST_LEVEL":
    case "INSERT_ROW":
    case "DELETE_ROW":
    case "INSERT_COLUMN":
    case "DELETE_COLUMN":
      return "structure";
    default:
      return "metadata";
  }
}

function mergeKind(current: ReviewChangeKind, next: ReviewChangeKind): ReviewChangeKind {
  if (current === "metadata") {
    return next;
  }

  if (next === "metadata" || current === next) {
    return current;
  }

  return "mixed";
}

function extractTextValue(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }

  if (Array.isArray(value)) {
    for (const entry of value) {
      const nested = extractTextValue(entry);
      if (nested) {
        return nested;
      }
    }
    return "";
  }

  const record = asRecord(value);
  const directKeys = [
    "text",
    "newText",
    "new_text",
    "content",
    "title",
    "label",
    "styleId",
    "style_id",
    "listType",
    "list_type",
    "type",
  ];

  for (const key of directKeys) {
    const direct = asString(record[key]);
    if (direct) {
      return direct;
    }
  }

  if (Array.isArray(record.children)) {
    for (const entry of record.children) {
      const nested = extractTextValue(entry);
      if (nested) {
        return nested;
      }
    }
  }

  return "";
}

function extractPrimaryBlockId(operation: ReviewOperation) {
  const directBlockId = operation.target?.blockId?.trim() || operation.blockId?.trim();
  if (directBlockId) {
    return directBlockId;
  }

  const valueRecord = asRecord(operation.value);
  return asString(valueRecord.blockId ?? valueRecord.block_id) || null;
}

function extractAnchorBlockId(operation: ReviewOperation) {
  const primary = extractPrimaryBlockId(operation);
  if (primary) {
    return primary;
  }

  const valueRecord = asRecord(operation.value);
  return (
    asString(valueRecord.afterBlockId ?? valueRecord.after_block_id)
    || asString(valueRecord.beforeBlockId ?? valueRecord.before_block_id)
    || null
  );
}

function applyTextOperation(currentText: string, operation: ReviewOperation) {
  const start = operation.target?.start ?? null;
  const end = operation.target?.end ?? null;
  const nextValue = extractTextValue(operation.value);

  switch (operation.op) {
    case "REPLACE_TEXT_RANGE":
    case "UPDATE_CELL_CONTENT":
      if (start === null || end === null || start < 0 || end < start || end > currentText.length) {
        return { nextText: nextValue || currentText, exact: false };
      }
      return {
        nextText: `${currentText.slice(0, start)}${nextValue}${currentText.slice(end)}`,
        exact: true,
      };

    case "INSERT_TEXT_AT":
      if (start === null || start < 0 || start > currentText.length) {
        return { nextText: `${currentText}${nextValue}`, exact: false };
      }
      return {
        nextText: `${currentText.slice(0, start)}${nextValue}${currentText.slice(start)}`,
        exact: true,
      };

    case "DELETE_TEXT_RANGE":
      if (start === null || end === null || start < 0 || end < start || end > currentText.length) {
        return { nextText: currentText, exact: false };
      }
      return {
        nextText: `${currentText.slice(0, start)}${currentText.slice(end)}`,
        exact: true,
      };

    default:
      return { nextText: currentText, exact: false };
  }
}

function blockTitle(node: HTMLElement, text: string) {
  const tagName = node.tagName.toLowerCase();
  if (/^h[1-6]$/.test(tagName)) {
    return trimPreview(text, 84) || `Heading ${tagName.slice(1)}`;
  }

  if (text) {
    return trimPreview(text, 84);
  }

  return startCase(node.getAttribute("data-doc-node-type") || tagName);
}

function blockSubtitle(node: HTMLElement, blockId: string) {
  const nodeType = startCase(node.getAttribute("data-doc-node-type") || node.tagName.toLowerCase());
  return `${nodeType} · ${blockId}`;
}

function buildBlockSnapshots(html: string | null | undefined) {
  const parser = new DOMParser();
  const parsed = parser.parseFromString(html || "<body></body>", "text/html");
  const snapshots = new Map<string, BlockSnapshot>();

  parsed.body.querySelectorAll<HTMLElement>("[data-doc-node-id]").forEach((node) => {
    const blockId = node.getAttribute("data-doc-node-id")?.trim();
    if (!blockId || snapshots.has(blockId)) {
      return;
    }

    const text = normalizeWhitespace(node.textContent ?? "");

    snapshots.set(blockId, {
      blockId,
      text,
      title: blockTitle(node, text),
      subtitle: blockSubtitle(node, blockId),
      html: node.outerHTML,
    });
  });

  return snapshots;
}

function hasBlockSnapshot(
  blockId: string | null | undefined,
  currentSnapshots: Map<string, BlockSnapshot>,
  previewSnapshots: Map<string, BlockSnapshot>,
) {
  const normalized = normalizeBlockId(blockId);
  return Boolean(normalized && (currentSnapshots.has(normalized) || previewSnapshots.has(normalized)));
}

function collectComparableTexts(values: Array<string | null | undefined>) {
  return dedupeText(
    values
      .map((value) => normalizeWhitespace(value ?? ""))
      .filter(Boolean),
  );
}

function scoreTextCandidate(candidateText: string, expectedTexts: string[], exactWeight: number, partialWeight: number) {
  const normalizedCandidate = normalizeWhitespace(candidateText);
  if (!normalizedCandidate || expectedTexts.length === 0) {
    return 0;
  }

  let score = 0;
  expectedTexts.forEach((expectedText) => {
    if (normalizedCandidate === expectedText) {
      score = Math.max(score, exactWeight);
      return;
    }

    if (normalizedCandidate.includes(expectedText) || expectedText.includes(normalizedCandidate)) {
      score = Math.max(score, partialWeight);
    }
  });

  return score;
}

function resolveRenderableAnchorBlockId(
  group: ReviewChangeAccumulator,
  currentSnapshots: Map<string, BlockSnapshot>,
  previewSnapshots: Map<string, BlockSnapshot>,
  textDiffs: RevisionTextDiff[],
) {
  const anchorBlockId = normalizeBlockId(group.anchorBlockId);
  if (hasBlockSnapshot(anchorBlockId, currentSnapshots, previewSnapshots)) {
    return anchorBlockId;
  }

  const expectedBeforeTexts = collectComparableTexts(group.textDiffs.map((entry) => entry.oldText ?? ""));
  const expectedAfterTexts = collectComparableTexts([
    ...group.textDiffs.map((entry) => entry.newText ?? ""),
    ...group.operations.map((operation) => extractTextValue(operation.value)),
  ]);

  const candidateIds = [...new Set(
    textDiffs
      .map((entry) => normalizeBlockId(entry.blockId))
      .filter((candidateId): candidateId is string => hasBlockSnapshot(candidateId, currentSnapshots, previewSnapshots)),
  )];

  let bestCandidateId = anchorBlockId;
  let bestScore = 0;

  candidateIds.forEach((candidateId) => {
    const currentText = currentSnapshots.get(candidateId)?.text ?? "";
    const previewText = previewSnapshots.get(candidateId)?.text ?? "";
    let score = 0;

    score += scoreTextCandidate(currentText, expectedBeforeTexts, 180, 80);
    score += scoreTextCandidate(previewText, expectedAfterTexts, 160, 70);

    textDiffs.forEach((entry) => {
      if (normalizeBlockId(entry.blockId) !== candidateId) {
        return;
      }

      score += scoreTextCandidate(entry.oldText ?? "", expectedBeforeTexts, 140, 60);
      score += scoreTextCandidate(entry.newText ?? "", expectedAfterTexts, 140, 60);
    });

    if (candidateId === anchorBlockId) {
      score += 20;
    }

    if (score > bestScore) {
      bestScore = score;
      bestCandidateId = candidateId;
    }
  });

  return bestScore > 0 ? bestCandidateId : anchorBlockId;
}

function ensureGroup(
  grouped: Map<string, ReviewChangeAccumulator>,
  key: string,
  order: number,
  anchorBlockId: string | null,
) {
  const existing = grouped.get(key);
  if (existing) {
    return existing;
  }

  const created: ReviewChangeAccumulator = {
    id: key,
    order,
    anchorBlockId,
    operations: [],
    textDiffs: [],
    styleDiffs: [],
    layoutDiffs: [],
  };
  grouped.set(key, created);
  return created;
}

function findUnanchoredGroupForDiff(
  grouped: Map<string, ReviewChangeAccumulator>,
  kind: "text" | "style" | "structure",
) {
  const groups = [...grouped.values()].sort((left, right) => left.order - right.order);

  return groups.find((group) => {
    if (group.anchorBlockId) {
      return false;
    }

    if (kind === "text" && group.textDiffs.length > 0) {
      return false;
    }
    if (kind === "style" && group.styleDiffs.length > 0) {
      return false;
    }
    if (kind === "structure" && group.layoutDiffs.length > 0) {
      return false;
    }

    return group.operations.some((operation) => operationKind(operation.op) === kind);
  }) ?? null;
}

function normalizeBlockId(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized ? normalized : null;
}

function formatDiffValue(value: string | null | undefined) {
  return value?.trim() ? value.trim() : "none";
}

function formatTextDiffDetail(entry: RevisionTextDiff) {
  switch (entry.changeType) {
    case "ADD":
      return "Added text";
    case "DELETE":
      return "Deleted text";
    case "REPLACE":
      return "Replaced text";
    default:
      return startCase(entry.changeType.toLowerCase());
  }
}

function formatStyleDiffDetail(entry: RevisionStyleDiff) {
  return `${startCase(entry.property)}: ${formatDiffValue(entry.oldValue)} -> ${formatDiffValue(entry.newValue)}`;
}

function formatLayoutDiffDetail(entry: RevisionLayoutDiff) {
  if (entry.oldValue || entry.newValue) {
    return `${startCase(entry.changeType.toLowerCase())}: ${formatDiffValue(entry.oldValue)} -> ${formatDiffValue(entry.newValue)}`;
  }

  return startCase(entry.changeType.toLowerCase());
}

function dedupeText(items: string[]) {
  return [...new Set(items.map((item) => item.trim()).filter(Boolean))];
}

function summarizeTextDiffPreview(textDiffs: RevisionTextDiff[]) {
  const firstWithPayload = textDiffs.find((entry) => entry.oldText || entry.newText) ?? null;
  if (!firstWithPayload) {
    return null;
  }

  return {
    beforeText: trimPreview(firstWithPayload.oldText ?? ""),
    afterText: trimPreview(firstWithPayload.newText ?? ""),
  };
}

function fallbackChangeTitle(group: ReviewChangeAccumulator) {
  if (group.layoutDiffs[0]) {
    return startCase(group.layoutDiffs[0].changeType.toLowerCase());
  }
  if (group.styleDiffs[0]) {
    return startCase(group.styleDiffs[0].property);
  }
  if (group.textDiffs[0]) {
    return formatTextDiffDetail(group.textDiffs[0]);
  }
  if (group.operations[0]) {
    return formatOperationLabel(group.operations[0].op);
  }
  if (group.anchorBlockId) {
    return `Affected block ${group.anchorBlockId}`;
  }
  return "Staged change";
}

export function formatOperationLabel(operation: string) {
  return textOpLabel(operation);
}

export function collectAffectedBlockIds(review: RevisionReview | null | undefined) {
  const blockIds = new Set<string>();

  review?.affectedBlockIds.forEach((blockId) => {
    const normalized = blockId.trim();
    if (normalized) {
      blockIds.add(normalized);
    }
  });

  review?.operations.forEach((operation) => {
    const blockId = extractAnchorBlockId(operation);
    if (blockId) {
      blockIds.add(blockId);
    }
  });

  review?.preview?.diff?.textDiffs.forEach((entry) => {
    const blockId = normalizeBlockId(entry.blockId);
    if (blockId) {
      blockIds.add(blockId);
    }
  });
  review?.preview?.diff?.styleDiffs.forEach((entry) => {
    const blockId = normalizeBlockId(entry.blockId);
    if (blockId) {
      blockIds.add(blockId);
    }
  });
  review?.preview?.diff?.layoutDiffs.forEach((entry) => {
    const blockId = normalizeBlockId(entry.blockId);
    if (blockId) {
      blockIds.add(blockId);
    }
  });

  return [...blockIds];
}

export function buildReviewChangeGroups(html: string, review: RevisionReview | null | undefined): ReviewChangeGroup[] {
  if (!review) {
    return [];
  }

  const currentSnapshots = buildBlockSnapshots(html);
  const previewSnapshots = buildBlockSnapshots(review.preview?.html ?? review.preview?.sourceHtml ?? "");
  const previewTextDiffs = review.preview?.diff?.textDiffs ?? [];
  const grouped = new Map<string, ReviewChangeAccumulator>();
  let nextOrder = 0;

  review.operations.forEach((operation, index) => {
    const anchorBlockId = extractAnchorBlockId(operation);
    const key = anchorBlockId ? `block:${anchorBlockId}` : `operation:${index + 1}`;
    ensureGroup(grouped, key, index, anchorBlockId).operations.push(operation);
    nextOrder = Math.max(nextOrder, index + 1);
  });

  // Attach diff data to existing operation-based groups only.
  // Never create new groups from diffs — they duplicate operation groups
  // when blockIds are null or differ in format.
  review.preview?.diff?.textDiffs.forEach((entry) => {
    const anchorBlockId = normalizeBlockId(entry.blockId);
    const key = anchorBlockId ? `block:${anchorBlockId}` : null;

    if (key && grouped.has(key)) {
      grouped.get(key)!.textDiffs.push(entry);
      return;
    }

    if (!anchorBlockId) {
      return;
    }

    const fallbackGroup = findUnanchoredGroupForDiff(grouped, "text");
    if (fallbackGroup) {
      fallbackGroup.anchorBlockId = anchorBlockId;
      fallbackGroup.textDiffs.push(entry);
    }
  });

  review.preview?.diff?.styleDiffs.forEach((entry) => {
    const anchorBlockId = normalizeBlockId(entry.blockId);
    const key = anchorBlockId ? `block:${anchorBlockId}` : null;

    if (key && grouped.has(key)) {
      grouped.get(key)!.styleDiffs.push(entry);
      return;
    }

    if (!anchorBlockId) {
      return;
    }

    const fallbackGroup = findUnanchoredGroupForDiff(grouped, "style");
    if (fallbackGroup) {
      fallbackGroup.anchorBlockId = anchorBlockId;
      fallbackGroup.styleDiffs.push(entry);
    }
  });

  review.preview?.diff?.layoutDiffs.forEach((entry) => {
    const anchorBlockId = normalizeBlockId(entry.blockId);
    const key = anchorBlockId ? `block:${anchorBlockId}` : null;

    if (key && grouped.has(key)) {
      grouped.get(key)!.layoutDiffs.push(entry);
      return;
    }

    if (!anchorBlockId) {
      return;
    }

    const fallbackGroup = findUnanchoredGroupForDiff(grouped, "structure");
    if (fallbackGroup) {
      fallbackGroup.anchorBlockId = anchorBlockId;
      fallbackGroup.layoutDiffs.push(entry);
    }
  });

  return [...grouped.values()]
    .sort((left, right) => left.order - right.order)
    .map((group) => {
      const resolvedAnchorBlockId = resolveRenderableAnchorBlockId(group, currentSnapshots, previewSnapshots, previewTextDiffs);
      const currentBlock = resolvedAnchorBlockId ? currentSnapshots.get(resolvedAnchorBlockId) ?? null : null;
      const previewBlock = resolvedAnchorBlockId ? previewSnapshots.get(resolvedAnchorBlockId) ?? null : null;
      let beforeText = currentBlock?.text ?? "";
      let afterText = previewBlock?.text ?? "";
      let exactPreview = false;
      let previewSource: ReviewPreviewSource = "metadata";
      let kind: ReviewChangeKind = "metadata";

      group.textDiffs.forEach(() => {
        kind = mergeKind(kind, "text");
      });
      group.styleDiffs.forEach(() => {
        kind = mergeKind(kind, "style");
      });
      group.layoutDiffs.forEach(() => {
        kind = mergeKind(kind, "structure");
      });
      group.operations.forEach((operation) => {
        kind = mergeKind(kind, operationKind(operation.op));
      });

      const detailLines = dedupeText([
        ...group.layoutDiffs.map(formatLayoutDiffDetail),
        ...group.styleDiffs.map(formatStyleDiffDetail),
        ...group.textDiffs.map(formatTextDiffDetail),
        ...group.operations.map((operation) => operation.description || formatOperationLabel(operation.op)),
      ]);

      if (
        review.preview?.available
        && previewSnapshots.size > 0
        && (currentBlock || previewBlock)
        && normalizeWhitespace(currentBlock?.text ?? "") !== normalizeWhitespace(previewBlock?.text ?? "")
      ) {
        exactPreview = true;
        previewSource = "backend";
      } else {
        const diffPreview = summarizeTextDiffPreview(group.textDiffs);
        if (diffPreview) {
          beforeText = diffPreview.beforeText;
          afterText = diffPreview.afterText;
          exactPreview = true;
          previewSource = "backend";
        }
      }

      if (!exactPreview && currentBlock && group.operations.some((operation) => operationKind(operation.op) === "text")) {
        let simulatedText = currentBlock.text;
        let simulatedExact = true;

        group.operations.forEach((operation) => {
          if (operationKind(operation.op) === "text") {
            const result = applyTextOperation(simulatedText, operation);
            simulatedText = result.nextText;
            simulatedExact = simulatedExact && result.exact;
          }
        });

        beforeText = currentBlock.text;
        afterText = simulatedText;
        exactPreview = simulatedExact;
        previewSource = "simulated";
      }

      if (!afterText) {
        afterText = trimPreview(
          group.operations
            .map((operation) => extractTextValue(operation.value) || operation.description)
            .find(Boolean) ?? "",
        );
      }

      const title = currentBlock?.title ?? previewBlock?.title ?? fallbackChangeTitle(group);
      const subtitle = detailLines[0]
        ?? currentBlock?.subtitle
        ?? previewBlock?.subtitle
        ?? "Review the affected content in the document surface.";

      return {
        id: group.id,
        anchorBlockId: resolvedAnchorBlockId,
        title,
        subtitle,
        beforeText: trimPreview(beforeText),
        afterText: trimPreview(afterText),
        exactPreview,
        kind,
        previewSource,
        details: detailLines,
        operations: group.operations,
      };
    });
}

// ─── Review display HTML ────────────────────────────────────────────────────

function escapeSelectorValue(value: string) {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(value);
  }
  return value.replace(/["\\]/g, "\\$&");
}

interface TextNodeRange {
  node: Text;
  start: number;
  end: number;
}

function parseElementFromHtml(doc: Document, html: string) {
  const template = doc.createElement("template");
  template.innerHTML = html.trim();
  return template.content.firstElementChild instanceof HTMLElement ? template.content.firstElementChild : null;
}

function serializeReviewDocument(doc: Document) {
  const seen = new Set<string>();
  const styleMarkup = [...doc.querySelectorAll("style")]
    .map((style) => style.outerHTML.trim())
    .filter((markup) => {
      if (!markup || seen.has(markup)) {
        return false;
      }

      seen.add(markup);
      return true;
    })
    .join("");

  const bodyClone = doc.body.cloneNode(true);
  if (!(bodyClone instanceof HTMLBodyElement)) {
    return `${styleMarkup}${doc.body.innerHTML}`;
  }

  bodyClone.querySelectorAll("style").forEach((style) => style.remove());
  return `${styleMarkup}${bodyClone.innerHTML}`;
}

function buildTextNodeRanges(block: Element) {
  const walker = block.ownerDocument.createTreeWalker(block, NodeFilter.SHOW_TEXT);
  const ranges: TextNodeRange[] = [];
  let cumOffset = 0;
  let node: Text | null;

  while ((node = walker.nextNode() as Text | null)) {
    ranges.push({ node, start: cumOffset, end: cumOffset + node.length });
    cumOffset += node.length;
  }

  return ranges;
}

function resolveTextBoundary(ranges: TextNodeRange[], offset: number, affinity: "start" | "end") {
  if (ranges.length === 0) {
    return null;
  }

  for (let index = 0; index < ranges.length; index += 1) {
    const range = ranges[index];
    if (offset > range.end) {
      continue;
    }

    if (offset === range.end && affinity === "start" && index < ranges.length - 1) {
      continue;
    }

    const localOffset = Math.max(0, Math.min(range.node.length, offset - range.start));
    return { node: range.node, offset: localOffset };
  }

  const last = ranges[ranges.length - 1];
  return { node: last.node, offset: last.node.length };
}

function isTextMutationOperation(operation: ReviewOperation) {
  return operation.op === "REPLACE_TEXT_RANGE"
    || operation.op === "INSERT_TEXT_AT"
    || operation.op === "DELETE_TEXT_RANGE"
    || operation.op === "UPDATE_CELL_CONTENT";
}

function applyTextOperationToElement(block: HTMLElement, operation: ReviewOperation) {
  const doc = block.ownerDocument;
  const nextValue = extractTextValue(operation.value);
  const ranges = buildTextNodeRanges(block);
  const totalTextLength = ranges[ranges.length - 1]?.end ?? 0;

  if (operation.op === "UPDATE_CELL_CONTENT") {
    block.textContent = nextValue;
    return true;
  }

  if (operation.op === "REPLACE_TEXT_RANGE") {
    const start = operation.target?.start ?? null;
    const end = operation.target?.end ?? null;
    if (start === null || end === null || start < 0 || end < start || end > totalTextLength) {
      if (!nextValue) {
        return false;
      }

      block.textContent = nextValue;
      return true;
    }
  }

  if (ranges.length === 0) {
    if (operation.op === "INSERT_TEXT_AT" && (operation.target?.start ?? 0) === 0 && nextValue) {
      block.textContent = nextValue;
      return true;
    }
    return false;
  }

  const start = Math.max(0, Math.min(totalTextLength, operation.target?.start ?? 0));
  const end = operation.op === "INSERT_TEXT_AT"
    ? start
    : Math.max(start, Math.min(totalTextLength, operation.target?.end ?? start));
  const startBoundary = resolveTextBoundary(ranges, start, "start");
  const endBoundary = resolveTextBoundary(ranges, end, operation.op === "INSERT_TEXT_AT" ? "start" : "end");

  if (!startBoundary || !endBoundary) {
    return false;
  }

  const range = doc.createRange();
  range.setStart(startBoundary.node, startBoundary.offset);
  range.setEnd(endBoundary.node, endBoundary.offset);
  range.deleteContents();

  if (operation.op !== "DELETE_TEXT_RANGE" && nextValue) {
    range.insertNode(doc.createTextNode(nextValue));
  }

  return true;
}

function buildSimulatedTextOnlyBlock(currentBlock: HTMLElement, operations: ReviewOperation[]) {
  const stagedBlock = currentBlock.cloneNode(true);
  if (!(stagedBlock instanceof HTMLElement)) {
    return null;
  }

  const sortedOperations = operations
    .filter(isTextMutationOperation)
    .slice()
    .sort((left, right) => {
      const leftStart = left.target?.start ?? 0;
      const rightStart = right.target?.start ?? 0;
      if (leftStart !== rightStart) {
        return rightStart - leftStart;
      }

      const leftEnd = left.target?.end ?? leftStart;
      const rightEnd = right.target?.end ?? rightStart;
      return rightEnd - leftEnd;
    });

  let applied = false;
  sortedOperations.forEach((operation) => {
    applied = applyTextOperationToElement(stagedBlock, operation) || applied;
  });

  return applied ? stagedBlock : null;
}

function isInlineReviewNode(node: HTMLElement) {
  return new Set(["A", "ABBR", "B", "CODE", "EM", "I", "LABEL", "SMALL", "SPAN", "STRONG", "SUB", "SUP", "U"]).has(
    node.tagName.toUpperCase(),
  );
}

function hasSingleReviewBlockDescendant(root: HTMLElement, blockId: string) {
  const descendants = [...root.querySelectorAll<HTMLElement>("[data-doc-node-id]")]
    .map((node) => node.getAttribute("data-doc-node-id")?.trim() ?? "")
    .filter(Boolean);

  if (descendants.length === 0) {
    return false;
  }

  return descendants.every((value) => value === blockId);
}

function findReviewRenderRoot(block: HTMLElement, blockId: string) {
  let candidate = block;
  if (!isInlineReviewNode(candidate)) {
    return candidate;
  }

  let current = block.parentElement;
  while (current && current.tagName.toUpperCase() !== "BODY") {
    if (!hasSingleReviewBlockDescendant(current, blockId)) {
      break;
    }

    candidate = current;
    if (!isInlineReviewNode(current)) {
      break;
    }
    current = current.parentElement;
  }

  return candidate;
}

function buildSimulatedTextOnlyRenderRoot(renderRoot: HTMLElement, blockId: string, operations: ReviewOperation[]) {
  const stagedRoot = renderRoot.cloneNode(true);
  if (!(stagedRoot instanceof HTMLElement)) {
    return null;
  }

  const targetBlock = stagedRoot.matches(`[data-doc-node-id="${escapeSelectorValue(blockId)}"]`)
    ? stagedRoot
    : stagedRoot.querySelector<HTMLElement>(`[data-doc-node-id="${escapeSelectorValue(blockId)}"]`);
  if (!targetBlock) {
    return null;
  }

  const simulatedBlock = buildSimulatedTextOnlyBlock(targetBlock, operations);
  if (!simulatedBlock) {
    return null;
  }

  if (simulatedBlock !== targetBlock) {
    targetBlock.replaceWith(simulatedBlock);
  }

  return stagedRoot;
}

function shouldWrapReviewBlock(parent: Element | null) {
  if (!(parent instanceof HTMLElement)) {
    return true;
  }

  return !new Set(["TABLE", "TBODY", "THEAD", "TFOOT", "TR", "UL", "OL", "DL", "SELECT"]).has(parent.tagName.toUpperCase());
}

function buildReviewVersionNode(
  doc: Document,
  block: HTMLElement,
  version: "before" | "after",
) {
  const shell = doc.createElement("div");
  shell.setAttribute("data-docpilot-review-version", version);

  const frame = doc.createElement("div");
  frame.setAttribute("data-docpilot-review-frame", "true");
  frame.append(block);

  shell.append(frame);
  return shell;
}

function buildReviewGroupNode(
  doc: Document,
  blockId: string,
  beforeNode: HTMLElement,
  afterNode: HTMLElement,
) {
  const group = doc.createElement("div");
  group.setAttribute("data-docpilot-review-group", "true");
  group.setAttribute("data-docpilot-review-block-id", blockId);
  group.append(beforeNode, afterNode);
  return group;
}

export function buildReviewDisplayHtml(
  html: string,
  review: RevisionReview | null | undefined,
  focusedChangeId?: string | null,
): string | null {
  if (!review || !html || !focusedChangeId) {
    return null;
  }

  const activeChange = buildReviewChangeGroups(html, review).find(
    (change): change is ReviewChangeGroup & { anchorBlockId: string } => change.id === focusedChangeId && Boolean(change.anchorBlockId),
  );
  if (!activeChange) {
    return null;
  }

  const parser = new DOMParser();
  const currentDoc = parser.parseFromString(html, "text/html");
  const previewDoc = parser.parseFromString(review.preview?.html ?? review.preview?.sourceHtml ?? "<body></body>", "text/html");
  const blockId = activeChange.anchorBlockId;
  const currentBlock = currentDoc.querySelector<HTMLElement>(`[data-doc-node-id="${escapeSelectorValue(blockId)}"]`);

  if (!currentBlock || !currentBlock.parentElement) {
    return null;
  }

  const currentRenderRoot = findReviewRenderRoot(currentBlock, blockId);
  if (!shouldWrapReviewBlock(currentRenderRoot.parentElement)) {
    return null;
  }

  const currentClone = currentRenderRoot.cloneNode(true);
  if (!(currentClone instanceof HTMLElement)) {
    return null;
  }

  let stagedBlock: HTMLElement | null = null;
  const previewBlock = previewDoc.querySelector<HTMLElement>(`[data-doc-node-id="${escapeSelectorValue(blockId)}"]`);
  if (previewBlock) {
    const previewRenderRoot = findReviewRenderRoot(previewBlock, blockId);
    const previewText = normalizeWhitespace(previewRenderRoot.textContent ?? "");
    const currentText = normalizeWhitespace(currentRenderRoot.textContent ?? "");
    const previewClone = previewRenderRoot.cloneNode(true);

    if (previewText && previewText !== currentText && previewClone instanceof HTMLElement) {
      stagedBlock = previewClone;
    }
  }

  if (!stagedBlock) {
    stagedBlock = buildSimulatedTextOnlyRenderRoot(currentRenderRoot, blockId, activeChange.operations);
  }

  if (!stagedBlock) {
    return null;
  }

  const beforeNode = buildReviewVersionNode(currentDoc, currentClone, "before");
  const afterNode = buildReviewVersionNode(currentDoc, stagedBlock, "after");
  const reviewGroup = buildReviewGroupNode(currentDoc, blockId, beforeNode, afterNode);

  currentRenderRoot.replaceWith(reviewGroup);
  return serializeReviewDocument(currentDoc);
}