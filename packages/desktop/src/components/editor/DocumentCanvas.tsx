import { useEffect, useLayoutEffect, useRef } from "react";

interface DocumentCanvasProps {
  html: string;
  editable: boolean;
  variant?: "editable" | "fidelity";
  onCommit: (html: string) => void;
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

export function DocumentCanvas({ html, editable, variant = "editable", onCommit }: DocumentCanvasProps) {
  const lightDomRef = useRef<HTMLDivElement | null>(null);
  const shadowHostRef = useRef<HTMLDivElement | null>(null);
  const shadowContentRef = useRef<HTMLDivElement | null>(null);
  const dirtyRef = useRef(false);
  const htmlRef = useRef(html);
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

  useEffect(() => {
    htmlRef.current = html;
    onCommitRef.current = onCommit;
  }, [html, onCommit]);

  useLayoutEffect(() => {
    if (!isFidelitySurface || !shadowHostRef.current) {
      shadowContentRef.current = null;
      return;
    }

    const surface = ensureFidelitySurface(shadowHostRef.current);
    surface.contentEditable = editable ? "true" : "false";
    surface.spellcheck = true;
    shadowContentRef.current = surface;

    if (surface.innerHTML !== htmlRef.current) {
      surface.innerHTML = htmlRef.current;
      updateDirtyState(false);
    }

    const handleInput = () => updateDirtyState(true);
    const handleBlur = () => commitIfNeeded(surface.innerHTML ?? "");

    surface.addEventListener("input", handleInput);
    surface.addEventListener("blur", handleBlur);

    return () => {
      surface.removeEventListener("input", handleInput);
      surface.removeEventListener("blur", handleBlur);
      shadowContentRef.current = null;
    };
  }, [editable, isFidelitySurface]);

  useLayoutEffect(() => {
    const target = isFidelitySurface ? shadowContentRef.current : lightDomRef.current;

    if (target && target.innerHTML !== html) {
      target.innerHTML = html;
      updateDirtyState(false);
    }
  }, [html, isFidelitySurface]);

  return (
    <div className="mx-auto w-full max-w-[860px] rounded-[28px] border border-docpilot-paperBorder bg-docpilot-paper px-12 py-14 text-docpilot-ink shadow-paper">
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
        />
      )}
    </div>
  );
}