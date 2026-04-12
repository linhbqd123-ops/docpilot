import React, { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";

type Item = {
  value: string;
  label: string;
  description?: string;
  icon?: React.ReactNode;
};

type Props = {
  items: Item[];
  value?: string | null;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  buttonClassName?: string;
  menuClassName?: string;
};

export default function Dropdown({
  items,
  value,
  onChange,
  placeholder = "Select",
  className,
  buttonClassName,
  menuClassName,
}: Props) {
  const [open, setOpen] = useState(false);
  const [focusedIndex, setFocusedIndex] = useState<number>(-1);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const [menuStyle, setMenuStyle] = useState<React.CSSProperties | null>(null);

  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  useEffect(() => {
    if (open) {
      const idx = items.findIndex((i) => i.value === value);
      setFocusedIndex(idx >= 0 ? idx : 0);

      // compute menu position in viewport and render to portal
      const btn = buttonRef.current;
      if (btn) {
        const rect = btn.getBoundingClientRect();
        setMenuStyle({
          position: "absolute",
          left: `${rect.left + window.scrollX}px`,
          top: `${rect.bottom + window.scrollY + 8}px`,
          minWidth: `${Math.max(rect.width, 220)}px`,
          zIndex: 9999,
        });
      }
    } else {
      setFocusedIndex(-1);
    }
  }, [open, value, items]);

  useEffect(() => {
    function onScrollOrResize() {
      if (!open) return;
      const btn = buttonRef.current;
      if (btn) {
        const rect = btn.getBoundingClientRect();
        setMenuStyle({
          position: "absolute",
          left: `${rect.left + window.scrollX}px`,
          top: `${rect.bottom + window.scrollY + 8}px`,
          minWidth: `${Math.max(rect.width, 220)}px`,
          zIndex: 9999,
        });
      }
    }

    window.addEventListener("scroll", onScrollOrResize, true);
    window.addEventListener("resize", onScrollOrResize);
    return () => {
      window.removeEventListener("scroll", onScrollOrResize, true);
      window.removeEventListener("resize", onScrollOrResize);
    };
  }, [open]);

  function handleKeyDown(e: React.KeyboardEvent) {
    if (!open && (e.key === "ArrowDown" || e.key === "Enter")) {
      e.preventDefault();
      setOpen(true);
      return;
    }

    if (!open) return;

    if (e.key === "ArrowDown") {
      e.preventDefault();
      setFocusedIndex((v) => (v + 1) % items.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setFocusedIndex((v) => (v - 1 + items.length) % items.length);
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (focusedIndex >= 0) select(items[focusedIndex]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      setOpen(false);
    }
  }

  function select(item: Item) {
    onChange(item.value);
    setOpen(false);
  }

  const selected = items.find((i) => i.value === value) ?? null;

  return (
    <div ref={rootRef} className={cn("relative w-full", className)} onKeyDown={handleKeyDown} tabIndex={0}>
      <button
        ref={buttonRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "field-shell flex w-full items-center justify-between gap-3 pr-3",
          buttonClassName,
        )}
      >
        <div className="min-w-0 text-left">
          <div className="truncate font-medium text-docpilot-textStrong">{selected?.label ?? placeholder}</div>
          {selected?.description ? (
            <div className="text-[11px] text-docpilot-muted">{selected.description}</div>
          ) : null}
        </div>
        <ChevronDown size={16} className="text-docpilot-muted" />
      </button>

      {open && menuStyle
        ? createPortal(
            <div
              role="listbox"
              aria-activedescendant={focusedIndex >= 0 ? `option-${focusedIndex}` : undefined}
              style={menuStyle}
              className={cn(
                "scrollbar-thin max-h-64 overflow-auto rounded-xl border border-docpilot-border bg-docpilot-panel py-1 shadow-lg",
                menuClassName,
              )}
            >
              {items.map((item, idx) => {
                const isSelected = item.value === value;
                const isFocused = idx === focusedIndex;

                return (
                  <div
                    id={`option-${idx}`}
                    key={item.value}
                    role="option"
                    aria-selected={isSelected}
                    className={cn(
                      "flex cursor-pointer items-start gap-3 px-3 py-2 text-sm",
                      isSelected
                        ? "bg-docpilot-accentSoft text-docpilot-textStrong"
                        : isFocused
                        ? "bg-docpilot-hover text-docpilot-textStrong"
                        : "text-docpilot-text hover:bg-docpilot-hover",
                    )}
                    onMouseEnter={() => setFocusedIndex(idx)}
                    onClick={() => select(item)}
                  >
                    {item.icon ? <div className="mt-0.5">{item.icon}</div> : null}
                    <div className="min-w-0">
                      <div className="truncate font-medium">{item.label}</div>
                      {item.description ? <div className="text-xs text-docpilot-muted">{item.description}</div> : null}
                    </div>
                  </div>
                );
              })}
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}
