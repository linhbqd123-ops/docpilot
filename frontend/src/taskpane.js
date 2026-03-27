/**
 * DocPilot Task Pane - Main JavaScript
 *
 * Handles:
 * - Office.js document interaction
 * - Chat UI management
 * - Backend API communication
 * - Document content extraction and application
 */

/* global Office, Word */

// API_BASE can be overridden via a global before this script loads.
// Default: same-origin for production; localhost:8000 for local dev.
const API_BASE = window.DOCPILOT_API_BASE || "http://localhost:8000/api/v1";

// ===== State =====
const state = {
    mode: "auto",
    provider: "auto",
    isProcessing: false,
    traceId: null,
};

// ===== DOM References =====
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

const dom = {
    chatContainer: null,
    welcomeMessage: null,
    userInput: null,
    sendBtn: null,
    connectionStatus: null,
    modeSelect: null,
    modelSelect: null,
};

// ===== Initialization =====
Office.onReady((info) => {
    if (info.host === Office.HostType.Word) {
        initializeApp();
    }
});

function initializeApp() {
    state.traceId = (window.crypto && window.crypto.randomUUID)
        ? window.crypto.randomUUID().slice(0, 8)
        : String(Date.now());

    // Cache DOM references
    dom.chatContainer = $("#chatContainer");
    dom.welcomeMessage = $("#welcomeMessage");
    dom.userInput = $("#userInput");
    dom.sendBtn = $("#sendBtn");
    dom.connectionStatus = $("#connectionStatus");
    dom.modeSelect = $("#modeSelect");
    dom.modelSelect = $("#modelSelect");

    // Bind events
    dom.sendBtn.addEventListener("click", handleSend);
    dom.userInput.addEventListener("keydown", (e) => {
        if (e.ctrlKey && e.key === "Enter") {
            e.preventDefault();
            handleSend();
        }
    });

    // Auto-resize textarea
    dom.userInput.addEventListener("input", () => {
        dom.userInput.style.height = "auto";
        dom.userInput.style.height = Math.min(dom.userInput.scrollHeight, 120) + "px";
    });

    // Mode select (mirrors Model select behavior)
    if (dom.modeSelect) {
        dom.modeSelect.addEventListener("change", (e) => {
            state.mode = e.target.value;
        });
        // initialize state from select value
        state.mode = dom.modeSelect.value || state.mode;
    }

    // Model select
    dom.modelSelect.addEventListener("change", (e) => {
        state.provider = e.target.value === "auto" ? null : e.target.value;
    });

    // Action buttons
    $$(".action-btn").forEach((btn) => {
        btn.addEventListener("click", () => handleAction(btn.dataset.action));
    });

    // Check backend connectivity
    checkConnection();
    logFrontendEvent("app_initialized", "DocPilot taskpane initialized", {
        apiBase: API_BASE,
        mode: state.mode,
    });

    // Re-check connection every 30s
    setInterval(checkConnection, 30000);
}

// ===== Connection Check =====
async function checkConnection() {
    const dot = dom.connectionStatus.querySelector(".status-dot");
    try {
        const res = await fetch(`${API_BASE}/health`, { signal: AbortSignal.timeout(5000) });
        if (res.ok) {
            dot.className = "status-dot connected";
            dom.connectionStatus.title = "Connected to DocPilot backend";
            loadProviders();
            logFrontendEvent("connection_ok", "Backend health check succeeded", { status: res.status });
        } else {
            dot.className = "status-dot error";
            dom.connectionStatus.title = "Backend returned an error";
            logFrontendEvent("connection_error", "Backend health check returned error", { status: res.status });
        }
    } catch {
        dot.className = "status-dot error";
        dom.connectionStatus.title = "Cannot connect to backend (http://localhost:8000)";
        logFrontendEvent("connection_exception", "Backend unreachable", {});
    }
}

async function loadProviders() {
    try {
        const res = await fetch(`${API_BASE}/agent/status`);
        const data = await res.json();
        if (data.providers && data.providers.length > 0) {
            // Update model selector with available providers
            dom.modelSelect.innerHTML = '<option value="auto">Auto</option>';
            data.providers.forEach((p) => {
                const opt = document.createElement("option");
                opt.value = p.name;
                // Keep label short to avoid wide select; show details on hover
                opt.textContent = p.name;
                opt.title = `${p.name} (${p.model})`;
                dom.modelSelect.appendChild(opt);
            });
        }
    } catch {
        // Silently fail - default options remain
    }
}

// ===== Document Operations =====
async function getDocumentBase64() {
    return Word.run(async (context) => {
        const body = context.document.body;
        const result = body.getOoxml();
        await context.sync();
        // Office.js getBase64 is available on the document object
        // but getOoxml is more reliable across versions; however
        // the backend expects a full .docx base64, so we use the
        // document-level API instead.
        return null; // Fallback — see below
    }).then(() => {
        // Use the File API for full docx base64 extraction
        return new Promise((resolve, reject) => {
            Office.context.document.getFileAsync(
                Office.FileType.Compressed,
                { sliceSize: 4194304 },  // 4MB slices
                (result) => {
                    if (result.status !== Office.AsyncResultStatus.Succeeded) {
                        reject(new Error(result.error.message));
                        return;
                    }
                    const file = result.value;
                    const sliceCount = file.sliceCount;
                    const slices = [];
                    let gotSlices = 0;

                    for (let i = 0; i < sliceCount; i++) {
                        file.getSliceAsync(i, (sliceResult) => {
                            if (sliceResult.status === Office.AsyncResultStatus.Succeeded) {
                                slices[i] = sliceResult.value.data;
                            }
                            gotSlices++;
                            if (gotSlices === sliceCount) {
                                file.closeAsync();
                                // Combine slices into a single Uint8Array
                                const combined = new Uint8Array(
                                    slices.reduce((acc, s) => acc + s.length, 0)
                                );
                                let offset = 0;
                                for (const s of slices) {
                                    combined.set(new Uint8Array(s), offset);
                                    offset += s.length;
                                }
                                // Convert to base64
                                let binary = "";
                                for (let b = 0; b < combined.length; b++) {
                                    binary += String.fromCharCode(combined[b]);
                                }
                                resolve(btoa(binary));
                            }
                        });
                    }
                }
            );
        });
    });
}

async function applyDocumentBase64(base64Data) {
    return Word.run(async (context) => {
        // Insert the updated document content
        context.document.body.insertFileFromBase64(base64Data, Word.InsertLocation.replace);
        await context.sync();
    });
}

// ===== Chat UI =====
function addMessage(role, text, type = "") {
    // Hide welcome message
    if (dom.welcomeMessage) {
        dom.welcomeMessage.classList.add("hidden");
    }

    const msgDiv = document.createElement("div");
    msgDiv.className = `message ${role} ${type}`;

    const label = document.createElement("div");
    label.className = "message-label";
    label.textContent = role === "user" ? "You" : "DocPilot";

    const bubble = document.createElement("div");
    bubble.className = "message-bubble";
    bubble.textContent = text;

    msgDiv.appendChild(label);
    msgDiv.appendChild(bubble);
    dom.chatContainer.appendChild(msgDiv);

    scrollToBottom();
    return msgDiv;
}

function addTypingIndicator() {
    const msgDiv = document.createElement("div");
    msgDiv.className = "message assistant";
    msgDiv.id = "typingIndicator";

    const label = document.createElement("div");
    label.className = "message-label";
    label.textContent = "DocPilot";

    const bubble = document.createElement("div");
    bubble.className = "message-bubble";
    bubble.innerHTML = `
        <div class="typing-indicator">
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
            <span class="typing-dot"></span>
        </div>
    `;

    msgDiv.appendChild(label);
    msgDiv.appendChild(bubble);
    dom.chatContainer.appendChild(msgDiv);
    scrollToBottom();
}

function removeTypingIndicator() {
    const el = document.getElementById("typingIndicator");
    if (el) el.remove();
}

function scrollToBottom() {
    dom.chatContainer.scrollTop = dom.chatContainer.scrollHeight;
}

function setProcessing(processing) {
    state.isProcessing = processing;
    dom.sendBtn.disabled = processing;
    $$(".action-btn").forEach((btn) => {
        btn.classList.toggle("loading", processing);
    });
}

// ===== API Communication =====
async function sendToAgent(message, action = null) {
    let documentBase64 = null;

    try {
        documentBase64 = await getDocumentBase64();
    } catch (e) {
        console.warn("Could not read document:", e);
    }

    const payload = {
        message: message,
        document_base64: documentBase64,
        mode: state.mode,
        action: action,
        provider_name: state.provider === "auto" ? null : state.provider,
    };

    logFrontendEvent("agent_request", "Sending request to backend", {
        action,
        mode: state.mode,
        provider: payload.provider_name,
        message,
        hasDocument: !!documentBase64,
    });

    const response = await fetch(`${API_BASE}/agent/run`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
    });

    if (!response.ok) {
        const errorText = await response.text();
        logFrontendEvent("agent_response_error", "Backend returned non-OK response", {
            status: response.status,
            errorText,
        });
        throw new Error(`Backend error: ${response.status} - ${errorText}`);
    }

    const data = await response.json();
    logFrontendEvent("agent_response_ok", "Received successful backend response", {
        success: data.success,
        message: data.message,
        hasUpdatedDocument: !!data.document_base64,
    });
    return data;
}

// ===== Event Handlers =====
async function handleSend() {
    const message = dom.userInput.value.trim();
    if (!message || state.isProcessing) return;

    dom.userInput.value = "";
    dom.userInput.style.height = "auto";

    addMessage("user", message);
    logFrontendEvent("user_send", "User submitted free-text instruction", { message });
    setProcessing(true);
    addTypingIndicator();

    try {
        const result = await sendToAgent(message);
        removeTypingIndicator();

        if (result.success) {
            addMessage("assistant", result.message, result.changes_summary ? "success" : "");

            if (result.document_base64) {
                try {
                    await applyDocumentBase64(result.document_base64);
                    addMessage("assistant", "✓ Document updated successfully.", "success");
                } catch (e) {
                    addMessage("assistant", `Could not update document: ${e.message}`, "error");
                }
            }
        } else {
            addMessage("assistant", result.message, "error");
        }
    } catch (e) {
        removeTypingIndicator();
        logFrontendEvent("user_send_error", "Error during free-text execution", { error: e.message });
        addMessage("assistant", `Error: ${e.message}`, "error");
    } finally {
        setProcessing(false);
    }
}

async function handleAction(action) {
    if (state.isProcessing) return;

    const actionLabels = {
        rewrite: "Rewriting document...",
        improve: "Improving document...",
        tailor_cv: "Tailoring CV...",
    };

    const label = actionLabels[action] || `Running ${action}...`;
    const customInstruction = dom.userInput.value.trim();
    const actionMessage = customInstruction || label;
    logFrontendEvent("action_click", "Quick action triggered", {
        action,
        actionMessage,
        usedCustomInstruction: !!customInstruction,
    });

    if (customInstruction) {
        dom.userInput.value = "";
        dom.userInput.style.height = "auto";
    }

    addMessage("user", `[${action.replace("_", " ").toUpperCase()}] ${actionMessage}`);
    setProcessing(true);
    addTypingIndicator();

    try {
        const result = await sendToAgent(actionMessage, action);
        removeTypingIndicator();

        if (result.success) {
            addMessage("assistant", result.message, "success");

            if (result.document_base64) {
                try {
                    await applyDocumentBase64(result.document_base64);
                    addMessage("assistant", "✓ Document updated successfully.", "success");
                } catch (e) {
                    addMessage("assistant", `Could not update document: ${e.message}`, "error");
                }
            }
        } else {
            addMessage("assistant", result.message, "error");
        }
    } catch (e) {
        removeTypingIndicator();
        logFrontendEvent("action_error", "Error during action execution", {
            action,
            error: e.message,
        });
        addMessage("assistant", `Error: ${e.message}`, "error");
    } finally {
        setProcessing(false);
    }
}

function sanitizeLogText(value, limit = 2000) {
    if (value === null || value === undefined) return "";
    const str = String(value).replace(/\s+/g, " ").trim();
    return str.length > limit ? `${str.slice(0, limit)}...(truncated)` : str;
}

async function logFrontendEvent(event, message, payload = {}) {
    const entry = {
        traceId: state.traceId,
        event,
        message: sanitizeLogText(message, 400),
        payload,
        timestamp: new Date().toISOString(),
    };

    // Keep console output for easy browser debugging.
    console.log("[DocPilot][frontend]", entry);

    try {
        await fetch(`${API_BASE}/agent/logs/frontend`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                level: "INFO",
                event,
                message: sanitizeLogText(message, 400),
                payload: {
                    ...payload,
                    traceId: state.traceId,
                    timestamp: entry.timestamp,
                },
            }),
        });
    } catch {
        // Avoid throwing from telemetry path.
    }
}
