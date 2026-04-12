import {
  createContext,
  useContext,
  useEffect,
  useReducer,
  useRef,
  type PropsWithChildren,
} from "react";

import type { AppSettings, AppState, ChatMessage, DocumentRecord, SidebarView } from "@/app/types";
import { appReducer, createInitialState } from "@/app/reducer";
import { getThemeDefinition } from "@/app/themes";
import { checkBackendHealth, getErrorMessage, sendPromptToBackend } from "@/lib/api";
import { createDocumentFromFile } from "@/lib/document";
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
  acceptPendingChanges: () => void;
  discardPendingChanges: () => void;
  updateSelectedDocumentHtml: (html: string) => void;
  clearBanner: () => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: PropsWithChildren) {
  const [state, dispatch] = useReducer(appReducer, loadPersistedState(), createInitialState);
  const abortControllerRef = useRef<AbortController | null>(null);

  const selectedDocument =
    state.documents.find((document) => document.id === state.selectedDocumentId) ?? null;

  const currentMessages = selectedDocument
    ? state.messageThreads[selectedDocument.id] ?? []
    : [];

  useEffect(() => {
    savePersistedState(state);
  }, [state.documents, state.selectedDocumentId, state.messageThreads, state.settings, state.activeSidebarView]);

  useEffect(() => {
    const theme = getThemeDefinition(state.settings.theme);

    document.documentElement.dataset.theme = theme.id;
    document.documentElement.style.colorScheme = theme.appearance;
  }, [state.settings.theme]);

  useEffect(() => {
    if (state.settings.connectOnStartup && state.settings.apiBaseUrl.trim()) {
      void testConnection();
    }
  }, []);

  async function importFiles(files: FileList | File[]) {
    const queue = Array.from(files);

    for (const file of queue) {
      try {
        const document = await createDocumentFromFile(file);
        dispatch({ type: "upsertDocument", payload: document });
        dispatch({ type: "selectDocument", payload: document.id });

        if (document.status === "needs_backend") {
          dispatch({
            type: "setBanner",
            payload: `${document.name} needs the backend import endpoint before it can be rendered.`,
          });
          dispatch({ type: "setActiveSidebarView", payload: "connect" });
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
    const baseUrl = state.settings.apiBaseUrl.trim();

    if (!baseUrl) {
      dispatch({ type: "setBanner", payload: "Set the backend URL before running a connection check." });
      return;
    }

    dispatch({
      type: "setConnection",
      payload: { status: "checking", error: null, lastCheckedAt: Date.now() },
    });

    try {
      const result = await checkBackendHealth(baseUrl, state.settings.requestTimeoutMs);
      dispatch({
        type: "setConnection",
        payload: {
          status: "online",
          error: null,
          version: result.version,
          lastCheckedAt: Date.now(),
        },
      });
      dispatch({ type: "setBanner", payload: "Backend connection established." });
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

    if (!state.settings.apiBaseUrl.trim()) {
      dispatch({ type: "setActiveSidebarView", payload: "connect" });
      dispatch({ type: "setBanner", payload: "Set the backend URL before sending a request." });
      return;
    }

    if (selectedDocument.status !== "ready") {
      dispatch({ type: "setBanner", payload: "This document requires backend import before it can be edited." });
      return;
    }

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

    dispatch({ type: "addMessage", payload: { documentId: selectedDocument.id, message: userMessage } });
    dispatch({ type: "addMessage", payload: { documentId: selectedDocument.id, message: assistantMessage } });
    dispatch({ type: "setComposer", payload: "" });
    dispatch({ type: "setIsSending", payload: true });
    dispatch({ type: "setBanner", payload: null });

    const controller = new AbortController();
    abortControllerRef.current = controller;
    let accumulated = "";

    try {
      const reply = await sendPromptToBackend({
        settings: state.settings,
        document: selectedDocument,
        history: [...currentMessages, userMessage],
        prompt,
        signal: controller.signal,
        onTextChunk: (chunk) => {
          accumulated += chunk;
          dispatch({
            type: "updateMessage",
            payload: {
              documentId: selectedDocument.id,
              messageId: assistantMessage.id,
              patch: { content: accumulated, status: "streaming" },
            },
          });
        },
      });

      dispatch({
        type: "updateMessage",
        payload: {
          documentId: selectedDocument.id,
          messageId: assistantMessage.id,
          patch: { content: reply.message || accumulated, status: "sent" },
        },
      });

      if (reply.documentHtml) {
        dispatch({
          type: "stageDocument",
          payload: { documentId: selectedDocument.id, html: reply.documentHtml },
        });
        dispatch({ type: "setActiveSidebarView", payload: "review" });
      }

      if (reply.notices?.length) {
        dispatch({ type: "setBanner", payload: reply.notices.join(" ") });
      }
    } catch (error) {
      dispatch({
        type: "updateMessage",
        payload: {
          documentId: selectedDocument.id,
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

  function acceptPendingChanges() {
    if (!selectedDocument?.pendingHtml) {
      return;
    }

    dispatch({ type: "acceptPending", payload: { documentId: selectedDocument.id } });
    dispatch({ type: "setBanner", payload: "Revision applied to the current document." });
  }

  function discardPendingChanges() {
    if (!selectedDocument?.pendingHtml) {
      return;
    }

    dispatch({ type: "discardPending", payload: { documentId: selectedDocument.id } });
    dispatch({ type: "setBanner", payload: "Revision discarded." });
  }

  function updateSelectedDocumentHtml(html: string) {
    if (!selectedDocument) {
      return;
    }

    dispatch({ type: "updateDocumentHtml", payload: { documentId: selectedDocument.id, html } });
  }

  function clearBanner() {
    dispatch({ type: "setBanner", payload: null });
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
        acceptPendingChanges,
        discardPendingChanges,
        updateSelectedDocumentHtml,
        clearBanner,
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