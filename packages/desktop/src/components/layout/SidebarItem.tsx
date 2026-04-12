import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

interface SidebarItemProps {
  icon: ReactNode;
  active?: boolean;
  label: string;
  indicator?: boolean;
  onClick?: () => void;
}

export function SidebarItem({ icon, active, label, indicator, onClick }: SidebarItemProps) {
  return (
    <button
      type="button"
      className={cn(
        "group relative flex h-11 w-11 items-center justify-center rounded-2xl border border-transparent text-docpilot-muted transition",
        active
          ? "border-docpilot-accent/40 bg-docpilot-accentSoft text-docpilot-accent shadow-active"
          : "hover:border-docpilot-border hover:bg-docpilot-hover hover:text-docpilot-textStrong",
      )}
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      {icon}
      {active && (
        <div className="absolute -left-[11px] top-1/2 h-8 w-1 -translate-y-1/2 rounded-r-full bg-docpilot-accent" />
      )}
      {indicator ? (
        <span className="absolute right-1 top-1 h-2.5 w-2.5 rounded-full border border-docpilot-rail bg-docpilot-warning" />
      ) : null}
    </button>
  );
}
