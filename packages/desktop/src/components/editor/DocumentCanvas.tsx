import { useEffect, useLayoutEffect, useRef } from "react";

interface DocumentCanvasProps {
  html: string;
  displayHtml?: string;
  editable: boolean;
  variant?: "editable" | "fidelity";
  onCommit: (html: string) => void;
  highlightedBlockIds?: string[];
  focusedBlockId?: string | null;
  focusRequestId?: number;
  onBlockSelect?: (blockId: string) => void;
}

const fidelitySurfaceStyles = `
  :host {
    display: block;
    color: rgb(var(--docpilot-ink, 34 40 52));
  }

  * {
    box-sizing: border-box;
  }

  .docpilot-fidelity-surface {
    min-height: 1px;
    color: inherit;
    font-family: "IBM Plex Sans", "Segoe UI", system-ui, sans-serif;
    line-height: 1.5;
    word-break: break-word;
    overflow-wrap: anywhere;
    outline: none;
  }

  .docpilot-fidelity-surface:focus {
    outline: none;
  }

  .docpilot-fidelity-surface img,
  .docpilot-fidelity-surface svg,
  .docpilot-fidelity-surface canvas,
  .docpilot-fidelity-surface table {
    max-width: 100%;
  }

  ::selection {
    background: var(--docpilot-selection, rgba(74, 158, 255, 0.22));
    color: rgb(var(--docpilot-selection-text, 255 255 255));
  }

  .docpilot-review-change,
  .docpilot-review-focus {
    scroll-margin-block: 8rem;
  }

  .docpilot-review-change {
    cursor: pointer;
    transition: box-shadow 160ms ease;
    border-radius: 4px;
    box-shadow: inset 3px 0 0 0 var(--docpilot-review-block-border, rgba(35, 134, 54, 0.50));
    background: var(--docpilot-review-block, rgba(35, 134, 54, 0.06));
  }

  .docpilot-review-change:hover {
    box-shadow: inset 3px 0 0 0 var(--docpilot-review-block-border, rgba(35, 134, 54, 0.50)),
                0 0 0 1px rgba(74, 158, 255, 0.24);
  }

  .docpilot-review-focus {
    box-shadow:
      inset 3px 0 0 0 var(--docpilot-review-focus-border, rgba(74, 158, 255, 0.7)),
      0 0 0 2px var(--docpilot-review-focus-border, rgba(74, 158, 255, 0.7)),
      0 14px 32px rgba(74, 158, 255, 0.12);
    background: var(--docpilot-review-focus, rgba(74, 158, 255, 0.06));
  }

  [data-docpilot-review-group="true"] {
    display: block;
    margin-block: 18px;
    cursor: pointer;
  }

  [data-docpilot-review-group="true"].docpilot-review-change {
    border-radius: 20px;
    box-shadow: 0 0 0 1px rgba(74, 158, 255, 0.12);
    background: transparent;
  }

  [data-docpilot-review-group="true"].docpilot-review-focus {
    box-shadow: none;
    background: transparent;
  }

  [data-docpilot-review-version] {
    position: relative;
    display: block;
    border-radius: 18px;
    border: 1px solid transparent;
    overflow: hidden;
  }

  [data-docpilot-review-version] + [data-docpilot-review-version] {
    margin-top: 12px;
  }

  [data-docpilot-review-version="before"] {
    border-color: rgba(215, 58, 73, 0.28);
    background: rgba(215, 58, 73, 0.08);
  }

  [data-docpilot-review-version="after"] {
    border-color: rgba(46, 160, 67, 0.30);
    background: rgba(46, 160, 67, 0.08);
  }

  [data-docpilot-review-version]::before {
    display: block;
    margin-bottom: 10px;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.14em;
    text-transform: uppercase;
  }

  [data-docpilot-review-version="before"]::before {
    color: rgb(180, 40, 40);
  }

  [data-docpilot-review-version="after"]::before {
    color: rgb(22, 130, 50);
  }

  [data-docpilot-review-frame="true"] {
    display: flow-root;
  }

  [data-docpilot-review-frame="true"] > :first-child {
    margin-top: 0 !important;
  }

  [data-docpilot-review-frame="true"] > :last-child {
    margin-bottom: 0 !important;
  }
`;

function ensureFidelitySurface(host: HTMLDivElement) {
  const shadowRoot = host.shadowRoot ?? host.attachShadow({ mode: "open" });
  let style = shadowRoot.querySelector<HTMLStyleElement>('style[data-docpilot-fidelity-surface="true"]');

  if (!style) {
    style = document.createElement("style");
    style.dataset.docpilotFidelitySurface = "true";
    style.textContent = fidelitySurfaceStyles;
    shadowRoot.append(style);
  }

  let surface = shadowRoot.querySelector<HTMLDivElement>('div[data-docpilot-fidelity-content="true"]');

  if (!surface) {
    surface = document.createElement("div");
    surface.dataset.docpilotFidelityContent = "true";
    surface.className = "docpilot-fidelity-surface";
    shadowRoot.append(surface);
  }

  return surface;
}

function escapeSelectorValue(value: string) {
  if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
    return CSS.escape(value);
  }

  return value.replace(/["\\]/g, "\\$&");
}

function clearReviewClasses(root: ParentNode) {
  root.querySelectorAll(".docpilot-review-change, .docpilot-review-focus").forEach((node) => {
    node.classList.remove("docpilot-review-change", "docpilot-review-focus");
  });
}

function findBlockElements(root: ParentNode | null | undefined, blockId: string) {
  if (!root || !blockId) {
    return [] as HTMLElement[];
  }

  const reviewElements = [...root.querySelectorAll<HTMLElement>(`[data-docpilot-review-block-id="${escapeSelectorValue(blockId)}"]`)];
  if (reviewElements.length > 0) {
    return reviewElements;
  }

  const block = root.querySelector<HTMLElement>(`[data-doc-node-id="${escapeSelectorValue(blockId)}"]`);
  return block ? [block] : [];
}

function syncReviewClasses(root: ParentNode, highlightedBlockIds: string[], focusedBlockId: string | null | undefined) {
  clearReviewClasses(root);

  highlightedBlockIds.forEach((blockId) => {
    findBlockElements(root, blockId).forEach((element) => {
      element.classList.add("docpilot-review-change");
    });
  });

  if (focusedBlockId) {
    findBlockElements(root, focusedBlockId).forEach((element) => {
      element.classList.add("docpilot-review-change", "docpilot-review-focus");
    });
  }
}

export function DocumentCanvas({
  html,
  displayHtml,
  editable,
  variant = "editable",
  onCommit,
  highlightedBlockIds = [],
  focusedBlockId = null,
  focusRequestId = 0,
  onBlockSelect,
}: DocumentCanvasProps) {
  const lightDomRef = useRef<HTMLDivElement | null>(null);
  const shadowHostRef = useRef<HTMLDivElement | null>(null);
  const shadowContentRef = useRef<HTMLDivElement | null>(null);
  const dirtyRef = useRef(false);
  const htmlRef = useRef(html);
  const displayHtmlRef = useRef(displayHtml ?? html);
  const onCommitRef = useRef(onCommit);
  const isFidelitySurface = variant === "fidelity";
  const contentClassName = variant === "fidelity"
    ? "max-w-none focus:outline-none"
    : "prose prose-slate max-w-none font-sans prose-headings:font-display prose-headings:text-docpilot-ink prose-h1:text-[2.4rem] prose-h1:leading-tight prose-h2:text-[1.65rem] prose-p:text-[1.02rem] prose-p:leading-8 prose-li:leading-8 focus:outline-none";

  function updateDirtyState(nextDirty: boolean) {
    dirtyRef.current = nextDirty;
  }

  function commitIfNeeded(nextHtml: string) {
    if (dirtyRef.current && nextHtml !== htmlRef.current) {
      onCommitRef.current(nextHtml);
    }

    updateDirtyState(false);
  }

  function emitBlockSelection(target: EventTarget | null) {
    if (!onBlockSelect) {
      return;
    }

    const element = target instanceof HTMLElement
      ? target
      : target instanceof Text
        ? target.parentElement
        : null;
    if (!element) {
      return;
    }

    const blockElement = element.closest<HTMLElement>("[data-doc-node-id]");
    const reviewElement = element.closest<HTMLElement>("[data-docpilot-review-block-id]");
    const blockId = blockElement?.getAttribute("data-doc-node-id")?.trim()
      || reviewElement?.getAttribute("data-docpilot-review-block-id")?.trim();
    if (blockId) {
      onBlockSelect(blockId);
    }
  }

  useEffect(() => {
    htmlRef.current = html;
    displayHtmlRef.current = displayHtml ?? html;
    onCommitRef.current = onCommit;
  }, [html, displayHtml, onCommit]);

  useLayoutEffect(() => {
    if (!isFidelitySurface || !shadowHostRef.current) {
      shadowContentRef.current = null;
      return;
    }

    const surface = ensureFidelitySurface(shadowHostRef.current);
    surface.contentEditable = editable ? "true" : "false";
    surface.spellcheck = true;
    shadowContentRef.current = surface;

    if (surface.innerHTML !== displayHtmlRef.current) {
      surface.innerHTML = displayHtmlRef.current;
      updateDirtyState(false);
    }

    const handleInput = () => updateDirtyState(true);
    const handleBlur = () => commitIfNeeded(surface.innerHTML ?? "");
    const handleClick = (event: Event) => emitBlockSelection(event.target);

    surface.addEventListener("input", handleInput);
    surface.addEventListener("blur", handleBlur);
    surface.addEventListener("click", handleClick);

    return () => {
      surface.removeEventListener("input", handleInput);
      surface.removeEventListener("blur", handleBlur);
      surface.removeEventListener("click", handleClick);
      shadowContentRef.current = null;
    };
  }, [editable, isFidelitySurface, onBlockSelect]);

  useLayoutEffect(() => {
    const target = isFidelitySurface ? shadowContentRef.current : lightDomRef.current;
    const toRender = displayHtml ?? html;

    if (target && target.innerHTML !== toRender) {
      target.innerHTML = toRender;
      updateDirtyState(false);
    }
  }, [html, displayHtml, isFidelitySurface]);

  useEffect(() => {
    const root = isFidelitySurface
      ? shadowHostRef.current?.shadowRoot ?? shadowContentRef.current
      : lightDomRef.current;

    if (!root) {
      return;
    }

    syncReviewClasses(root, highlightedBlockIds, focusedBlockId);
  }, [focusedBlockId, highlightedBlockIds, html, displayHtml, isFidelitySurface]);

  useEffect(() => {
    if (!focusedBlockId || focusRequestId <= 0) {
      return;
    }

    const root = isFidelitySurface
      ? shadowHostRef.current?.shadowRoot ?? shadowContentRef.current
      : lightDomRef.current;
    const target = findBlockElements(root, focusedBlockId)[0] ?? null;

    target?.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
  }, [focusRequestId, focusedBlockId, html, isFidelitySurface]);

  return (
    <div className="mx-auto min-h-full w-full max-w-[860px] rounded-[28px] border border-docpilot-paperBorder bg-docpilot-paper px-12 py-14 text-docpilot-ink shadow-paper">
      {isFidelitySurface ? (
        <div ref={shadowHostRef} className={contentClassName} />
      ) : (
        <div
          ref={lightDomRef}
          contentEditable={editable}
          suppressContentEditableWarning
          spellCheck
          className={contentClassName}
          onInput={() => updateDirtyState(true)}
          onBlur={() => commitIfNeeded(lightDomRef.current?.innerHTML ?? "")}
          onClick={(event) => emitBlockSelection(event.target)}
        />
      )}
    </div>
  );
}