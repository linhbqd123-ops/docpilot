import { Eye, EyeOff, X } from "lucide-react";
import { useState } from "react";
import { cn } from "@/lib/utils";

interface MaskedKeyInputProps {
    value: string;
    onChange: (value: string) => void;
    onClear?: () => void;
    placeholder?: string;
    label?: string;
    maskedDisplay?: string | null;
    disabled?: boolean;
    error?: string;
}

export function MaskedKeyInput({
    value,
    onChange,
    onClear,
    placeholder = "Paste your API key here...",
    label,
    maskedDisplay,
    disabled = false,
    error,
}: MaskedKeyInputProps) {
    const [showValue, setShowValue] = useState(false);

    return (
        <div className="space-y-2">
            {label && (
                <label className="block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                    {label}
                </label>
            )}

            <div className="relative">
                <input
                    type={showValue ? "text" : "password"}
                    value={value}
                    onChange={(e) => onChange(e.target.value)}
                    placeholder={placeholder}
                    disabled={disabled}
                    className={cn(
                        "field-shell w-full pr-20",
                        error && "border-red-500 focus:ring-red-500",
                        disabled && "opacity-50 cursor-not-allowed",
                    )}
                />

                <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-2">
                    {value && onClear && (
                        <button
                            type="button"
                            onClick={onClear}
                            className="text-docpilot-muted hover:text-docpilot-text transition-colors"
                            title="Clear key"
                        >
                            <X size={16} />
                        </button>
                    )}

                    <button
                        type="button"
                        onClick={() => setShowValue(!showValue)}
                        className="text-docpilot-muted hover:text-docpilot-text transition-colors"
                        title={showValue ? "Hide key" : "Show key"}
                    >
                        {showValue ? <EyeOff size={16} /> : <Eye size={16} />}
                    </button>
                </div>
            </div>

            {error && (
                <p className="text-xs text-red-500">{error}</p>
            )}

            {maskedDisplay && !value && (
                <p className="text-xs text-docpilot-muted">
                    Current: <span className="font-mono">{maskedDisplay}</span>
                </p>
            )}
        </div>
    );
}
