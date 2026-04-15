import { X, Loader } from "lucide-react";
import { useState } from "react";
import { MaskedKeyInput } from "./MaskedKeyInput";

interface KeyEditorDialogProps {
    label: string;
    currentMaskedKey: string | null;
    isOpen: boolean;
    onClose: () => void;
    onSave: (key: string, endpoint?: string) => Promise<void>;
    onDelete?: () => Promise<void>;
    supportEndpoint?: boolean;
    endpointPlaceholder?: string;
}

export function KeyEditorDialog({
    label,
    currentMaskedKey,
    isOpen,
    onClose,
    onSave,
    onDelete,
    supportEndpoint = false,
    endpointPlaceholder = "https://api.example.com",
}: KeyEditorDialogProps) {
    const [key, setKey] = useState("");
    const [endpoint, setEndpoint] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    if (!isOpen) return null;

    const handleSave = async () => {
        if (!key.trim()) {
            setError("API key cannot be empty");
            return;
        }

        setLoading(true);
        setError("");

        try {
            await onSave(key, endpoint || undefined);
            setKey("");
            setEndpoint("");
            onClose();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to save key");
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async () => {
        if (!currentMaskedKey || !onDelete) return;

        if (!confirm(`Are you sure you want to delete the key for ${label}?`)) {
            return;
        }

        setLoading(true);
        setError("");

        try {
            await onDelete();
            setKey("");
            setEndpoint("");
            onClose();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Failed to delete key");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
            <div className="bg-docpilot-surface rounded-lg shadow-xl max-w-md w-full mx-4 overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between p-4 border-b border-docpilot-border">
                    <h2 className="font-semibold text-docpilot-textStrong">
                        {currentMaskedKey ? "Update" : "Add"} {label} Key
                    </h2>
                    <button
                        onClick={onClose}
                        className="text-docpilot-muted hover:text-docpilot-text transition-colors"
                    >
                        <X size={20} />
                    </button>
                </div>

                {/* Body */}
                <div className="p-4 space-y-4">
                    <MaskedKeyInput
                        label="API Key"
                        value={key}
                        onChange={setKey}
                        maskedDisplay={currentMaskedKey}
                        onClear={() => setKey("")}
                        disabled={loading}
                        placeholder="Paste your API key here..."
                        error={error}
                    />

                    {supportEndpoint && (
                        <div className="space-y-2">
                            <label className="block text-xs uppercase tracking-[0.18em] text-docpilot-muted">
                                Endpoint (Optional)
                            </label>
                            <input
                                type="text"
                                value={endpoint}
                                onChange={(e) => setEndpoint(e.target.value)}
                                placeholder={endpointPlaceholder}
                                disabled={loading}
                                className="field-shell w-full"
                            />
                        </div>
                    )}

                    {error && (
                        <div className="p-3 bg-red-500/10 border border-red-500/30 rounded text-sm text-red-600">
                            {error}
                        </div>
                    )}
                </div>

                {/* Footer */}
                <div className="flex gap-2 p-4 pl-0 border-t border-docpilot-border bg-docpilot-chrome">
                    {currentMaskedKey && onDelete && (
                        <button
                            onClick={handleDelete}
                            disabled={loading}
                            className="px-4 py-2 text-sm font-medium text-red-600 hover:bg-red-500/10 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            Delete
                        </button>
                    )}

                    <div className="flex-1" />

                    <button
                        onClick={onClose}
                        disabled={loading}
                        className="px-4 py-2 text-sm font-medium text-docpilot-text hover:bg-docpilot-surface rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        Cancel
                    </button>

                    <button
                        onClick={handleSave}
                        disabled={loading}
                        className="px-4 py-2 text-sm font-medium bg-docpilot-accent text-white rounded hover:opacity-90 transition-opacity disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                    >
                        {loading && <Loader size={14} className="animate-spin" />}
                        {loading ? "Saving..." : "Save"}
                    </button>
                </div>
            </div>
        </div>
    );
}
