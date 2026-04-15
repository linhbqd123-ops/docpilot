import {
  createContext,
  useContext,
  useEffect,
  useReducer,
  useRef,
  type PropsWithChildren,
} from "react";

import type {
  AgentNotice,
  AppSettings,
  AppState,
  Chat,
  ChatMessage,
  DocumentRecord,
  SidebarView,
} from "@/app/types";
import { appReducer, createInitialState } from "@/app/reducer";
import { getThemeDefinition } from "@/app/themes";
import {
  applyRevisionInBackend,
  checkBackendHealth,
  deleteChat as deleteChatApi,
  exportDocumentToDocx,
  getErrorMessage,
  importDocumentFromBackend,
  loadChatsFromBackend,
  refreshSessionFromBackend,
  rejectRevisionInBackend,
  rollbackRevisionInBackend,
  saveChat,
  sendAgentTurnToBackend,
  updateChat,
} from "@/lib/api";
import { createDocumentFromFile, normalizeDocumentHtml } from "@/lib/document";
import { loadPersistedState, savePersistedState } from "@/lib/storage";

interface AppContextValue {
  state: AppState;
  selectedDocument: DocumentRecord | null;
  currentMessages: ChatMessage[];
  importFiles: (files: FileList | File[]) => Promise<void>;
  removeDocument: (documentId: string) => void;
  selectDocument: (documentId: string | null) => void;
  setActiveSidebarView: (view: SidebarView) => void;
  updateComposer: (value: string) => void;
  updateSettings: (patch: Partial<AppSettings>) => void;
  testConnection: () => Promise<void>;
  sendMessage: () => Promise<void>;
  cancelRequest: () => void;
  applyStagedRevision: () => Promise<void>;
  rejectStagedRevision: () => Promise<void>;
  rollbackCurrentRevision: () => Promise<void>;
  updateSelectedDocumentHtml: (html: string) => void;
  exportDocument: () => Promise<void>;
  clearBanner: () => void;
  clearChat: () => void;
  createNewChat: () => void;
  selectChat: (chatId: string | null) => void;
  deleteChat: (chatId: string) => void;
  renameChat: (chatId: string, name: string) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

function formatNotices(notices?: AgentNotice[]) {
  if (!notices?.length) {
    return "";
  }

  const parts: string[] = [];

  notices.forEach((notice) => {
    if (notice.message) {
      parts.push(notice.message);
    }
    if (notice.items?.length) {
      parts.push(notice.items.join(" "));
    }
  });

  return parts.join(" ").trim();
}

export function AppProvider({ children }: PropsWithChildren) {
  const [state, dispatch] = useReducer(appReducer, loadPersistedState(), createInitialState);
  const abortControllerRef = useRef<AbortController | null>(null);

  const selectedDocument = state.documents.find((document) => document.id === state.selectedDocumentId) ?? null;
  const selectedChat = selectedDocument && state.selectedChatId
    ? state.chats.find((chat) => chat.id === state.selectedChatId && chat.documentId === selectedDocument.id) ?? null
    : null;
  const currentMessages = selectedChat
    ? state.messageThreads[selectedChat.id] ?? []
    : selectedDocument
      ? state.messageThreads[selectedDocument.id] ?? []
      : [];

  useEffect(() => {
    savePersistedState(state);
  }, [state]);

  useEffect(() => {
    const theme = getThemeDefinition(state.settings.theme);

    document.documentElement.dataset.theme = theme.id;
    document.documentElement.style.colorScheme = theme.appearance;
  }, [state.settings.theme]);

  useEffect(() => {
    if (state.settings.connectOnStartup && state.settings.apiBaseUrl.trim()) {
      let cancelled = false;

      dispatch({
        type: "setConnection",
        payload: { status: "checking", error: null, lastCheckedAt: Date.now() },
      });

      checkBackendHealth(state.settings.apiBaseUrl, state.settings.requestTimeoutMs)
        .then((payload) => {
          if (cancelled) {
            return;
          }

          dispatch({
            type: "setConnection",
            payload: {
              status: "online",
              error: null,
              version: payload.version ?? null,
              lastCheckedAt: Date.now(),
            },
          });
          dispatch({ type: "setBanner", payload: "Backend connection established ✓" });
        })
        .catch((error) => {
          if (cancelled) {
            return;
          }

          dispatch({
            type: "setConnection",
            payload: {
              status: "offline",
              error: getErrorMessage(error),
              version: null,
              lastCheckedAt: Date.now(),
            },
          });
          dispatch({ type: "setBanner", payload: getErrorMessage(error) });
        });

      return () => {
        cancelled = true;
      };
    }
  }, [state.settings.apiBaseUrl, state.settings.connectOnStartup, state.settings.requestTimeoutMs]);

  useEffect(() => {
    if (!state.settings.apiBaseUrl.trim()) {
      return;
    }

    loadChatsFromBackend(state.settings.apiBaseUrl)
      .then((chats) => {
        if (Array.isArray(chats) && chats.length > 0) {
          dispatch({ type: "hydrateChats", payload: chats as Chat[] });
        }
      })
      .catch((error) => {
        console.error("Failed to load chats from backend:", error);
      });
  }, [state.settings.apiBaseUrl]);

  useEffect(() => {
    if (!selectedDocument?.documentSessionId || !state.settings.apiBaseUrl.trim()) {
      return;
    }

    let cancelled = false;

    refreshSessionFromBackend({
      settings: state.settings,
      documentSessionId: selectedDocument.documentSessionId,
    })
      .then((payload) => {
        if (cancelled) {
          return;
        }

        if (payload.html) {
          dispatch({
            type: "setDocumentProjection",
            payload: {
              documentId: selectedDocument.id,
              html: payload.html,
              session: payload.session,
              revisions: payload.revisions,
            },
          });
          return;
        }

        dispatch({
          type: "setDocumentSession",
          payload: {
            documentId: selectedDocument.id,
            session: payload.session,
            revisions: payload.revisions,
          },
        });
      })
      .catch((error) => {
        console.error("Failed to refresh document session:", error);
      });

    return () => {
      cancelled = true;
    };
  }, [selectedDocument?.id, selectedDocument?.documentSessionId, state.settings]);

  async function importFiles(files: FileList | File[]) {
    const queue = Array.from(files);

    for (const file of queue) {
      try {
        const document = await createDocumentFromFile(file);
        dispatch({ type: "upsertDocument", payload: document });
        dispatch({ type: "selectDocument", payload: document.id });

        if (document.status !== "needs_backend") {
          continue;
        }

        if (!state.settings.apiBaseUrl.trim()) {
          dispatch({
            type: "setBanner",
            payload: `${document.name} requires the backend import flow. Verify the backend connection in the Connect panel.`,
          });
          dispatch({ type: "setActiveSidebarView", payload: "connect" });
          continue;
        }

        dispatch({ type: "setBanner", payload: `Importing ${document.name}…` });

        try {
          const result = await importDocumentFromBackend({ settings: state.settings, file });
          const normalized = normalizeDocumentHtml(result.html);

          dispatch({
            type: "upsertDocument",
            payload: {
              ...document,
              status: "ready",
              html: normalized.html,
              outline: normalized.outline,
              wordCount: normalized.wordCount,
              backendDocId: result.docId ?? undefined,
              documentSessionId: result.documentSessionId ?? undefined,
              baseRevisionId: result.baseRevisionId ?? null,
              currentRevisionId: result.baseRevisionId ?? null,
              reviewPayload: null,
              revisionStatus: null,
              revisions: [],
              sessionState: result.documentSessionId ? "READY" : null,
              error: undefined,
              updatedAt: Date.now(),
            },
          });

          dispatch({
            type: "setBanner",
            payload: result.documentSessionId
              ? `${document.name} imported into a canonical session.`
              : `${document.name} imported as HTML only. AI review/apply flow requires a session-backed DOCX import.`,
          });
        } catch (importError) {
          dispatch({
            type: "upsertDocument",
            payload: {
              ...document,
              status: "error",
              error: getErrorMessage(importError),
              updatedAt: Date.now(),
            },
          });
          dispatch({
            type: "setBanner",
            payload: `Failed to import ${document.name}: ${getErrorMessage(importError)}`,
          });
        }
      } catch (error) {
        dispatch({ type: "setBanner", payload: `Failed to import file: ${getErrorMessage(error)}` });
      }
    }
  }

  function removeDocument(documentId: string) {
    dispatch({ type: "removeDocument", payload: documentId });
  }

  function selectDocument(documentId: string | null) {
    dispatch({ type: "selectDocument", payload: documentId });
  }

  function setActiveSidebarView(view: SidebarView) {
    dispatch({ type: "setActiveSidebarView", payload: view });
  }

  function updateComposer(value: string) {
    dispatch({ type: "setComposer", payload: value });
  }

  function updateSettings(patch: Partial<AppSettings>) {
    dispatch({ type: "setSettings", payload: patch });
  }

  async function testConnection() {
    dispatch({
      type: "setConnection",
      payload: { status: "checking", error: null, lastCheckedAt: Date.now() },
    });

    try {
      const payload = await checkBackendHealth(state.settings.apiBaseUrl, state.settings.requestTimeoutMs);
      dispatch({
        type: "setConnection",
        payload: {
          status: "online",
          error: null,
          version: payload.version ?? null,
          lastCheckedAt: Date.now(),
        },
      });
      dispatch({ type: "setBanner", payload: "Backend connection established ✓" });
    } catch (error) {
      dispatch({
        type: "setConnection",
        payload: {
          status: "offline",
          error: getErrorMessage(error),
          version: null,
          lastCheckedAt: Date.now(),
        },
      });
      dispatch({ type: "setBanner", payload: getErrorMessage(error) });
    }
  }

  async function sendMessage() {
    const prompt = state.composerValue.trim();

    if (!prompt || !selectedDocument || state.isSending) {
      return;
    }

    if (selectedDocument.status !== "ready") {
      dispatch({ type: "setBanner", payload: "This document must finish importing before it can be used." });
      return;
    }

    if (!selectedDocument.documentSessionId) {
      dispatch({
        type: "setBanner",
        payload: "AI review/apply flow requires a session-backed DOCX import. Local HTML/Markdown/TXT documents can still be edited manually.",
      });
      return;
    }

    const messageThreadId = selectedChat?.id ?? selectedDocument.id;
    const userMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: "user",
      content: prompt,
      createdAt: Date.now(),
      status: "sent",
    };
    const assistantMessage: ChatMessage = {
      id: crypto.randomUUID(),
      role: "assistant",
      content: "",
      createdAt: Date.now(),
      status: "streaming",
    };

    dispatch({ type: "addMessage", payload: { documentId: messageThreadId, message: userMessage } });
    dispatch({ type: "addMessage", payload: { documentId: messageThreadId, message: assistantMessage } });
    dispatch({ type: "setComposer", payload: "" });
    dispatch({ type: "setIsSending", payload: true });
    dispatch({ type: "setBanner", payload: null });

    const controller = new AbortController();
    abortControllerRef.current = controller;
    let accumulated = "";

    try {
      const reply = await sendAgentTurnToBackend({
        settings: state.settings,
        chatId: messageThreadId,
        documentSessionId: selectedDocument.documentSessionId,
        currentRevisionId: selectedDocument.currentRevisionId ?? selectedDocument.baseRevisionId,
        documentId: selectedDocument.id,
        visibleBlockIds: selectedDocument.outline.map((item) => item.id),
        history: [...currentMessages, userMessage],
        prompt,
        signal: controller.signal,
        onTextChunk: (chunk) => {
          accumulated += chunk;
          dispatch({
            type: "updateMessage",
            payload: {
              documentId: messageThreadId,
              messageId: assistantMessage.id,
              patch: { content: accumulated, status: "streaming" },
            },
          });
        },
      });

      if (reply.status === "failed") {
        throw new Error(formatNotices(reply.notices) || reply.message || "Agent turn failed.");
      }

      const assistantContent = reply.message || accumulated;
      dispatch({
        type: "updateMessage",
        payload: {
          documentId: messageThreadId,
          messageId: assistantMessage.id,
          patch: { content: assistantContent, status: "sent" },
        },
      });

      if (selectedChat && state.settings.apiBaseUrl.trim()) {
        const updatedMessageThread: ChatMessage[] = [
          ...currentMessages,
          userMessage,
          { ...assistantMessage, content: assistantContent, status: "sent" },
        ];
        updateChat(state.settings.apiBaseUrl, selectedChat.id, {
          messages: updatedMessageThread,
        }).catch((error) => {
          console.error("Failed to sync messages to backend:", error);
        });
      }

      if (reply.resultType === "revision_staged" && reply.revisionId) {
        dispatch({
          type: "stageRevision",
          payload: {
            documentId: selectedDocument.id,
            revisionId: reply.revisionId,
            reviewPayload: reply.review ?? null,
            status: reply.proposal?.status ?? reply.status,
            baseRevisionId: reply.baseRevisionId ?? selectedDocument.currentRevisionId ?? null,
          },
        });
        dispatch({ type: "setActiveSidebarView", payload: "review" });
      }

      const noticeText = formatNotices(reply.notices);
      if (noticeText) {
        dispatch({ type: "setBanner", payload: noticeText });
      } else if (reply.resultType === "revision_staged") {
        dispatch({ type: "setBanner", payload: "Revision staged for review." });
      }
    } catch (error) {
      dispatch({
        type: "updateMessage",
        payload: {
          documentId: messageThreadId,
          messageId: assistantMessage.id,
          patch: { role: "error", content: getErrorMessage(error), status: "error" },
        },
      });
      dispatch({ type: "setBanner", payload: getErrorMessage(error) });
    } finally {
      abortControllerRef.current = null;
      dispatch({ type: "setIsSending", payload: false });
    }
  }

  function cancelRequest() {
    abortControllerRef.current?.abort();
  }

  async function applyStagedRevision() {
    if (!selectedDocument?.pendingRevisionId) {
      return;
    }

    try {
      dispatch({ type: "setBanner", payload: "Applying revision…" });
      const payload = await applyRevisionInBackend({
        settings: state.settings,
        revisionId: selectedDocument.pendingRevisionId,
      });

      if (!payload.html) {
        throw new Error("The backend did not return a refreshed HTML projection after apply.");
      }

      dispatch({
        type: "setDocumentProjection",
        payload: {
          documentId: selectedDocument.id,
          html: payload.html,
          session: payload.session,
          revisions: payload.revisions,
          clearReview: true,
          revisionStatus: payload.result?.status ?? "APPLIED",
        },
      });
      dispatch({ type: "setBanner", payload: "Revision applied to the canonical document." });
    } catch (error) {
      dispatch({ type: "setBanner", payload: `Failed to apply revision: ${getErrorMessage(error)}` });
    }
  }

  async function rejectStagedRevision() {
    if (!selectedDocument?.pendingRevisionId) {
      return;
    }

    try {
      dispatch({ type: "setBanner", payload: "Rejecting revision…" });
      const payload = await rejectRevisionInBackend({
        settings: state.settings,
        revisionId: selectedDocument.pendingRevisionId,
      });

      dispatch({
        type: "setDocumentSession",
        payload: {
          documentId: selectedDocument.id,
          session: payload.session,
          revisions: payload.revisions,
          clearReview: true,
          revisionStatus: payload.result?.status ?? "REJECTED",
        },
      });
      dispatch({ type: "setBanner", payload: "Revision rejected." });
    } catch (error) {
      dispatch({ type: "setBanner", payload: `Failed to reject revision: ${getErrorMessage(error)}` });
    }
  }

  async function rollbackCurrentRevision() {
    if (!selectedDocument?.currentRevisionId) {
      return;
    }

    try {
      dispatch({ type: "setBanner", payload: "Rolling back current revision…" });
      const payload = await rollbackRevisionInBackend({
        settings: state.settings,
        revisionId: selectedDocument.currentRevisionId,
      });

      if (!payload.html) {
        throw new Error("The backend did not return a refreshed HTML projection after rollback.");
      }

      dispatch({
        type: "setDocumentProjection",
        payload: {
          documentId: selectedDocument.id,
          html: payload.html,
          session: payload.session,
          revisions: payload.revisions,
          clearReview: true,
          revisionStatus: payload.result?.status ?? "REJECTED",
        },
      });
      dispatch({ type: "setBanner", payload: "Rolled back to the previous canonical revision." });
    } catch (error) {
      dispatch({ type: "setBanner", payload: `Failed to roll back revision: ${getErrorMessage(error)}` });
    }
  }

  function updateSelectedDocumentHtml(html: string) {
    if (!selectedDocument) {
      return;
    }

    if (selectedDocument.documentSessionId) {
      dispatch({
        type: "setBanner",
        payload: "Session-backed documents are read-only projections. Use the assistant to stage canonical revisions instead of editing the HTML directly.",
      });
      return;
    }

    dispatch({ type: "updateDocumentHtml", payload: { documentId: selectedDocument.id, html } });
  }

  async function exportDocument() {
    if (!selectedDocument || selectedDocument.status !== "ready") {
      return;
    }

    if (!selectedDocument.documentSessionId) {
      dispatch({
        type: "setBanner",
        payload: "DOCX export now requires a canonical document session. Import a DOCX through the backend-backed flow first.",
      });
      return;
    }

    try {
      dispatch({ type: "setBanner", payload: `Exporting ${selectedDocument.name}…` });
      const blob = await exportDocumentToDocx({
        settings: state.settings,
        documentSessionId: selectedDocument.documentSessionId,
        filename: selectedDocument.name,
      });

      const url = URL.createObjectURL(blob);
      const anchor = window.document.createElement("a");
      anchor.href = url;
      const baseName = selectedDocument.name.replace(/\.(html?|md|markdown|txt)$/i, "");
      anchor.download = baseName.endsWith(".docx") ? baseName : `${baseName}.docx`;
      anchor.click();
      URL.revokeObjectURL(url);

      dispatch({ type: "setBanner", payload: `${selectedDocument.name} exported.` });
    } catch (error) {
      dispatch({ type: "setBanner", payload: `Export failed: ${getErrorMessage(error)}` });
    }
  }

  function clearBanner() {
    dispatch({ type: "setBanner", payload: null });
  }

  function clearChat() {
    if (!selectedDocument) {
      return;
    }

    const messageThreadId = selectedChat?.id ?? selectedDocument.id;
    dispatch({ type: "clearMessages", payload: messageThreadId });
    dispatch({ type: "setComposer", payload: "" });

    if (selectedChat && state.settings.apiBaseUrl.trim()) {
      updateChat(state.settings.apiBaseUrl, selectedChat.id, { messages: [] }).catch((error) => {
        console.error("Failed to clear chat on backend:", error);
        dispatch({ type: "setBanner", payload: "Chat cleared locally, but failed to sync the backend copy." });
      });
    }
  }

  function createNewChat() {
    if (!selectedDocument) {
      return;
    }

    const chat: Chat = {
      id: crypto.randomUUID(),
      name: `Chat ${new Date().toLocaleTimeString()}`,
      documentId: selectedDocument.id,
      messages: [],
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    dispatch({ type: "createChat", payload: { chat } });
    dispatch({ type: "setComposer", payload: "" });

    if (state.settings.apiBaseUrl.trim()) {
      saveChat(state.settings.apiBaseUrl, chat)
        .then((savedChat) => {
          dispatch({ type: "createChat", payload: { chat: savedChat as Chat } });
        })
        .catch((error) => {
          console.error("Failed to save chat to backend:", error);
          dispatch({ type: "setBanner", payload: "Chat created locally, but failed to save to backend." });
        });
    }
  }

  function selectChat(chatId: string | null) {
    dispatch({ type: "selectChat", payload: chatId });
    dispatch({ type: "setComposer", payload: "" });
  }

  function deleteChat(chatId: string) {
    if (state.settings.apiBaseUrl.trim()) {
      deleteChatApi(state.settings.apiBaseUrl, chatId).catch((error) => {
        console.error("Failed to delete chat from backend:", error);
        dispatch({ type: "setBanner", payload: "Chat deleted locally, but failed to remove from backend." });
      });
    }
    dispatch({ type: "deleteChat", payload: chatId });
  }

  function renameChat(chatId: string, newName: string) {
    dispatch({ type: "renameChat", payload: { chatId, name: newName } });

    if (state.settings.apiBaseUrl.trim()) {
      updateChat(state.settings.apiBaseUrl, chatId, { name: newName }).catch((error) => {
        console.error("Failed to update chat on backend:", error);
        dispatch({ type: "setBanner", payload: "Chat renamed locally, but failed to update on backend." });
      });
    }
  }

  return (
    <AppContext.Provider
      value={{
        state,
        selectedDocument,
        currentMessages,
        importFiles,
        removeDocument,
        selectDocument,
        setActiveSidebarView,
        updateComposer,
        updateSettings,
        testConnection,
        sendMessage,
        cancelRequest,
        applyStagedRevision,
        rejectStagedRevision,
        rollbackCurrentRevision,
        updateSelectedDocumentHtml,
        exportDocument,
        clearBanner,
        clearChat,
        createNewChat,
        selectChat,
        deleteChat,
        renameChat,
      }}
    >
      {children}
    </AppContext.Provider>
  );
}

export function useAppContext() {
  const context = useContext(AppContext);

  if (!context) {
    throw new Error("useAppContext must be used inside AppProvider.");
  }

  return context;
}