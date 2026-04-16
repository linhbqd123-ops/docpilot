import { useEffect, useRef, useState } from "react";

interface DocumentCanvasProps {
  html: string;
  editable: boolean;
  variant?: "editable" | "fidelity";
  onCommit: (html: string) => void;
}

export function DocumentCanvas({ html, editable, variant = "editable", onCommit }: DocumentCanvasProps) {
  const ref = useRef<HTMLDivElement | null>(null);
  const [dirty, setDirty] = useState(false);
  const contentClassName = variant === "fidelity"
    ? "max-w-none focus:outline-none"
    : "prose prose-slate max-w-none font-sans prose-headings:font-display prose-headings:text-docpilot-ink prose-h1:text-[2.4rem] prose-h1:leading-tight prose-h2:text-[1.65rem] prose-p:text-[1.02rem] prose-p:leading-8 prose-li:leading-8 focus:outline-none";

  useEffect(() => {
    if (ref.current && ref.current.innerHTML !== html) {
      ref.current.innerHTML = html;
      setDirty(false);
    }
  }, [html]);

  return (
    <div className="mx-auto w-full max-w-[860px] rounded-[28px] border border-docpilot-paperBorder bg-docpilot-paper px-12 py-14 text-docpilot-ink shadow-paper">
      <div
        ref={ref}
        contentEditable={editable}
        suppressContentEditableWarning
        spellCheck
        className={contentClassName}
        onInput={() => setDirty(true)}
        onBlur={() => {
          const nextHtml = ref.current?.innerHTML ?? "";

          if (dirty && nextHtml !== html) {
            onCommit(nextHtml);
            setDirty(false);
          }
        }}
      />
    </div>
  );
}