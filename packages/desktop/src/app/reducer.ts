import type {
  AppSettings,
  AppState,
  Chat,
  ChatMessage,
  ConnectionState,
  DocumentRecord,
  PersistedState,
  RevisionReview,
  SessionRevisionSummary,
  SessionSummary,
  SidebarView,
} from "@/app/types";
import { DEFAULT_THEME, isThemeMode } from "@/app/themes";
import {
  applyDocumentProjection,
  applyDocumentSessionSummary,
  clearDocumentReview,
  stageDocumentReview,
  updateDocumentHtml,
} from "@/lib/document";

function sortChatsByUpdatedAt(chats: Chat[]) {
  return [...chats].sort((left, right) => right.updatedAt - left.updatedAt);
}

export const DEFAULT_SETTINGS: AppSettings = {
  apiBaseUrl: import.meta.env.VITE_DOCPILOT_API_BASE_URL?.trim() || "http://localhost:8000",
  provider: (import.meta.env.VITE_DOCPILOT_PROVIDER as AppSettings["provider"] | undefined) ?? "ollama",
  modelOverride: "",
  requestTimeoutMs: 30_000,
  streaming: true,
  connectOnStartup: false,
  agentConfig: {
    maxInputTokens: 6_000,
    sessionContextBudgetTokens: 4_200,
    toolResultBudgetTokens: 2_200,
    maxToolBatchSize: 4,
    maxParallelTools: 3,
    maxHeavyToolsPerTurn: 1,
    autoCompactSession: true,
  },
  mode: "agent",
  theme: DEFAULT_THEME,
};

const DEFAULT_CONNECTION: ConnectionState = {
  status: "idle",
  lastCheckedAt: null,
  error: null,
  version: null,
};

export function createInitialState(persisted: Partial<PersistedState>): AppState {
  const persistedTheme = persisted.settings?.theme;
  const persistedAgentConfig = persisted.settings?.agentConfig;

  return {
    documents: persisted.documents ?? [],
    selectedDocumentId: persisted.selectedDocumentId ?? null,
    chats: persisted.chats ?? [],
    selectedChatId: persisted.selectedChatId ?? null,
    messageThreads: persisted.messageThreads ?? {},
    settings: {
      ...DEFAULT_SETTINGS,
      ...persisted.settings,
      agentConfig: {
        ...DEFAULT_SETTINGS.agentConfig,
        ...(persistedAgentConfig ?? {}),
      },
      // apiBaseUrl is bundled with the desktop build and must not be user-configurable.
      apiBaseUrl: DEFAULT_SETTINGS.apiBaseUrl,
      theme: persistedTheme && isThemeMode(persistedTheme) ? persistedTheme : DEFAULT_SETTINGS.theme,
    },
    activeSidebarView: persisted.activeSidebarView ?? "library",
    connection: DEFAULT_CONNECTION,
    composerValue: "",
    isSending: false,
    banner: null,
  };
}

type Action =
  | { type: "setActiveSidebarView"; payload: SidebarView }
  | { type: "hydrateDocuments"; payload: DocumentRecord[] }
  | { type: "upsertDocument"; payload: DocumentRecord }
  | { type: "removeDocument"; payload: string }
  | { type: "selectDocument"; payload: string | null }
  | { type: "setComposer"; payload: string }
  | { type: "setConnection"; payload: Partial<ConnectionState> }
  | { type: "setSettings"; payload: Partial<AppSettings> }
  | { type: "setBanner"; payload: string | null }
  | { type: "setIsSending"; payload: boolean }
  | { type: "addMessage"; payload: { documentId: string; message: ChatMessage } }
  | { type: "updateMessage"; payload: { documentId: string; messageId: string; patch: Partial<ChatMessage> } }
  | { type: "clearMessages"; payload: string }
  | { type: "hydrateChats"; payload: Chat[] }
  | { type: "createChat"; payload: { chat: Chat } }
  | { type: "selectChat"; payload: string | null }
  | { type: "deleteChat"; payload: string }
  | { type: "renameChat"; payload: { chatId: string; name: string } }
  | {
    type: "setDocumentProjection";
    payload: {
      documentId: string;
      html: string;
      sourceHtml?: string | null;
      displayMode?: "projection" | "preserve" | "restore_source";
      session?: SessionSummary;
      revisions?: SessionRevisionSummary[];
      clearReview?: boolean;
      revisionStatus?: string | null;
    };
  }
  | {
    type: "setDocumentSession";
    payload: {
      documentId: string;
      session: SessionSummary;
      revisions?: SessionRevisionSummary[];
      clearReview?: boolean;
      revisionStatus?: string | null;
    };
  }
  | {
    type: "stageRevision";
    payload: {
      documentId: string;
      revisionId: string;
      reviewPayload: RevisionReview | null;
      status?: string | null;
      baseRevisionId?: string | null;
    };
  }
  | { type: "clearRevision"; payload: { documentId: string; status?: string | null } }
  | { type: "updateDocumentHtml"; payload: { documentId: string; html: string } };

export function appReducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "setActiveSidebarView":
      return {
        ...state,
        activeSidebarView: action.payload,
      };

    case "hydrateDocuments": {
      const documents = [...action.payload].sort((left, right) => right.updatedAt - left.updatedAt);
      const selectedDocumentId =
        state.selectedDocumentId && documents.some((document) => document.id === state.selectedDocumentId)
          ? state.selectedDocumentId
          : documents[0]?.id ?? null;

      return {
        ...state,
        documents,
        selectedDocumentId,
        selectedChatId:
          selectedDocumentId && state.selectedChatId && state.chats.some(
            (chat) => chat.id === state.selectedChatId && chat.documentId === selectedDocumentId,
          )
            ? state.selectedChatId
            : null,
      };
    }

    case "upsertDocument": {
      const exists = state.documents.some((document) => document.id === action.payload.id);
      const documents = exists
        ? state.documents.map((document) => (document.id === action.payload.id ? action.payload : document))
        : [action.payload, ...state.documents];

      return {
        ...state,
        documents,
      };
    }

    case "removeDocument": {
      const documents = state.documents.filter((document) => document.id !== action.payload);
      const removedChatIds = state.chats
        .filter((chat) => chat.documentId === action.payload)
        .map((chat) => chat.id);
      const chats = state.chats.filter((chat) => chat.documentId !== action.payload);
      const messageThreads = { ...state.messageThreads };
      delete messageThreads[action.payload];
      removedChatIds.forEach((chatId) => delete messageThreads[chatId]);

      return {
        ...state,
        documents,
        chats,
        messageThreads,
        selectedDocumentId:
          state.selectedDocumentId === action.payload ? documents[0]?.id ?? null : state.selectedDocumentId,
        selectedChatId:
          state.selectedChatId && removedChatIds.includes(state.selectedChatId) ? null : state.selectedChatId,
      };
    }

    case "selectDocument":
      return {
        ...state,
        selectedDocumentId: action.payload,
        selectedChatId: null,
      };

    case "setComposer":
      return {
        ...state,
        composerValue: action.payload,
      };

    case "setConnection":
      return {
        ...state,
        connection: {
          ...state.connection,
          ...action.payload,
        },
      };

    case "setSettings":
      return {
        ...state,
        settings: {
          ...state.settings,
          ...action.payload,
        },
      };

    case "setBanner":
      return {
        ...state,
        banner: action.payload,
      };

    case "setIsSending":
      return {
        ...state,
        isSending: action.payload,
      };

    case "addMessage": {
      const currentThread = state.messageThreads[action.payload.documentId] ?? [];
      const nextThread = [...currentThread, action.payload.message];
      const chats = sortChatsByUpdatedAt(
        state.chats.map((chat) =>
          chat.id === action.payload.documentId
            ? { ...chat, messages: nextThread, updatedAt: Date.now() }
            : chat,
        ),
      );

      return {
        ...state,
        chats,
        messageThreads: {
          ...state.messageThreads,
          [action.payload.documentId]: nextThread,
        },
      };
    }

    case "updateMessage": {
      const currentThread = state.messageThreads[action.payload.documentId] ?? [];
      const nextThread = currentThread.map((message) =>
        message.id === action.payload.messageId ? { ...message, ...action.payload.patch } : message,
      );
      const chats = sortChatsByUpdatedAt(
        state.chats.map((chat) =>
          chat.id === action.payload.documentId
            ? { ...chat, messages: nextThread, updatedAt: Date.now() }
            : chat,
        ),
      );

      return {
        ...state,
        chats,
        messageThreads: {
          ...state.messageThreads,
          [action.payload.documentId]: nextThread,
        },
      };
    }

    case "clearMessages": {
      const messageThreads = { ...state.messageThreads };
      messageThreads[action.payload] = [];
      const chats = sortChatsByUpdatedAt(
        state.chats.map((chat) =>
          chat.id === action.payload ? { ...chat, messages: [], updatedAt: Date.now() } : chat,
        ),
      );

      return {
        ...state,
        chats,
        messageThreads,
      };
    }

    case "hydrateChats": {
      const mergedChats = new Map(state.chats.map((chat) => [chat.id, chat]));
      action.payload.forEach((chat) => mergedChats.set(chat.id, chat));

      const chats = Array.from(mergedChats.values()).sort((left, right) => right.updatedAt - left.updatedAt);
      const messageThreads = { ...state.messageThreads };
      chats.forEach((chat) => {
        messageThreads[chat.id] = chat.messages;
      });

      return {
        ...state,
        chats,
        messageThreads,
        selectedChatId:
          state.selectedChatId && chats.some((chat) => chat.id === state.selectedChatId)
            ? state.selectedChatId
            : null,
      };
    }

    case "createChat": {
      const exists = state.chats.some((chat) => chat.id === action.payload.chat.id);
      const chats = sortChatsByUpdatedAt(
        exists
          ? state.chats.map((chat) => (chat.id === action.payload.chat.id ? action.payload.chat : chat))
          : [action.payload.chat, ...state.chats],
      );
      return {
        ...state,
        chats,
        selectedChatId: action.payload.chat.id,
        messageThreads: {
          ...state.messageThreads,
          [action.payload.chat.id]: action.payload.chat.messages,
        },
      };
    }

    case "selectChat":
      return {
        ...state,
        selectedChatId:
          action.payload && state.selectedDocumentId && state.chats.some(
            (chat) => chat.id === action.payload && chat.documentId === state.selectedDocumentId,
          )
            ? action.payload
            : null,
      };

    case "deleteChat": {
      const chats = state.chats.filter((chat) => chat.id !== action.payload);
      const messageThreads = { ...state.messageThreads };
      delete messageThreads[action.payload];

      return {
        ...state,
        chats,
        messageThreads,
        selectedChatId: state.selectedChatId === action.payload ? null : state.selectedChatId,
      };
    }

    case "renameChat": {
      const chats = sortChatsByUpdatedAt(
        state.chats.map((chat) =>
          chat.id === action.payload.chatId
            ? { ...chat, name: action.payload.name, updatedAt: Date.now() }
            : chat,
        ),
      );

      return {
        ...state,
        chats,
      };
    }

    case "setDocumentProjection":
      return {
        ...state,
        documents: state.documents.map((document) => {
          if (document.id !== action.payload.documentId) {
            return document;
          }

          let nextDocument = applyDocumentProjection(document, action.payload.html, {
            sourceHtml: action.payload.sourceHtml,
            displayMode: action.payload.displayMode,
          });

          if (action.payload.session) {
            nextDocument = applyDocumentSessionSummary(
              nextDocument,
              action.payload.session,
              action.payload.revisions ?? nextDocument.revisions,
            );
          }

          if (action.payload.clearReview) {
            nextDocument = clearDocumentReview(nextDocument, {
              status: action.payload.revisionStatus ?? null,
            });
          } else if (action.payload.revisionStatus !== undefined) {
            nextDocument = {
              ...nextDocument,
              revisionStatus: action.payload.revisionStatus,
              updatedAt: Date.now(),
            };
          }

          return nextDocument;
        }),
      };

    case "setDocumentSession":
      return {
        ...state,
        documents: state.documents.map((document) => {
          if (document.id !== action.payload.documentId) {
            return document;
          }

          let nextDocument = applyDocumentSessionSummary(
            document,
            action.payload.session,
            action.payload.revisions ?? document.revisions,
          );

          if (action.payload.clearReview) {
            nextDocument = clearDocumentReview(nextDocument, {
              status: action.payload.revisionStatus ?? null,
            });
          } else if (action.payload.revisionStatus !== undefined) {
            nextDocument = {
              ...nextDocument,
              revisionStatus: action.payload.revisionStatus,
              updatedAt: Date.now(),
            };
          }

          return nextDocument;
        }),
      };

    case "stageRevision":
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId
            ? stageDocumentReview(document, {
              revisionId: action.payload.revisionId,
              reviewPayload: action.payload.reviewPayload,
              status: action.payload.status,
              baseRevisionId: action.payload.baseRevisionId,
            })
            : document,
        ),
      };

    case "clearRevision":
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId
            ? clearDocumentReview(document, { status: action.payload.status ?? null })
            : document,
        ),
      };

    case "updateDocumentHtml":
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId ? updateDocumentHtml(document, action.payload.html) : document,
        ),
      };

    default:
      return state;
  }
}