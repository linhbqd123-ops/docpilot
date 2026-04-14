import type { AppSettings, AppState, Chat, ChatMessage, ConnectionState, DocumentRecord, PersistedState, SidebarView } from "@/app/types";
import { DEFAULT_THEME, isThemeMode } from "@/app/themes";
import { commitPendingDocument, discardPendingDocument, stageDocumentHtml, updateDocumentHtml } from "@/lib/document";

export const DEFAULT_SETTINGS: AppSettings = {
  apiBaseUrl: "http://localhost:8000", // Backend server - FIXED, not user-configurable
  providerEndpoint: "", // Provider API endpoint - user-configurable
  provider: (import.meta.env.VITE_DOCPILOT_PROVIDER as AppSettings["provider"] | undefined) ?? "ollama",
  modelOverride: "",
  requestTimeoutMs: 30_000,
  streaming: true,
  connectOnStartup: false,
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

  return {
    documents: persisted.documents ?? [],
    selectedDocumentId: persisted.selectedDocumentId ?? null,
    chats: persisted.chats ?? [],
    selectedChatId: persisted.selectedChatId ?? null,
    messageThreads: persisted.messageThreads ?? {},
    settings: {
      ...DEFAULT_SETTINGS,
      ...persisted.settings,
      // CRITICAL: apiBaseUrl must ALWAYS be the backend server, never user-configurable
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
  | { type: "createChat"; payload: { chat: Chat } }
  | { type: "selectChat"; payload: string | null }
  | { type: "deleteChat"; payload: string }
  | { type: "renameChat"; payload: { chatId: string; name: string } }
  | { type: "stageDocument"; payload: { documentId: string; html: string } }
  | { type: "acceptPending"; payload: { documentId: string } }
  | { type: "discardPending"; payload: { documentId: string } }
  | { type: "updateDocumentHtml"; payload: { documentId: string; html: string } };

export function appReducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case "setActiveSidebarView":
      return {
        ...state,
        activeSidebarView: action.payload,
      };

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
      const messageThreads = { ...state.messageThreads };
      delete messageThreads[action.payload];

      return {
        ...state,
        documents,
        messageThreads,
        selectedDocumentId:
          state.selectedDocumentId === action.payload ? documents[0]?.id ?? null : state.selectedDocumentId,
      };
    }

    case "selectDocument":
      return {
        ...state,
        selectedDocumentId: action.payload,
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

      return {
        ...state,
        messageThreads: {
          ...state.messageThreads,
          [action.payload.documentId]: [...currentThread, action.payload.message],
        },
      };
    }

    case "updateMessage": {
      const currentThread = state.messageThreads[action.payload.documentId] ?? [];

      return {
        ...state,
        messageThreads: {
          ...state.messageThreads,
          [action.payload.documentId]: currentThread.map((message) =>
            message.id === action.payload.messageId ? { ...message, ...action.payload.patch } : message,
          ),
        },
      };
    }

    case "clearMessages": {
      const messageThreads = { ...state.messageThreads };
      messageThreads[action.payload] = [];

      return {
        ...state,
        messageThreads,
      };
    }

    case "createChat": {
      const newChats = [action.payload.chat, ...state.chats];
      return {
        ...state,
        chats: newChats,
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
        selectedChatId: action.payload,
      };

    case "deleteChat": {
      const chats = state.chats.filter((chat) => chat.id !== action.payload);
      const messageThreads = { ...state.messageThreads };
      delete messageThreads[action.payload];

      return {
        ...state,
        chats,
        messageThreads,
        selectedChatId:
          state.selectedChatId === action.payload ? chats[0]?.id ?? null : state.selectedChatId,
      };
    }

    case "renameChat": {
      return {
        ...state,
        chats: state.chats.map((chat) =>
          chat.id === action.payload.chatId
            ? { ...chat, name: action.payload.name, updatedAt: Date.now() }
            : chat,
        ),
      };
    }

    case "stageDocument":
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId ? stageDocumentHtml(document, action.payload.html) : document,
        ),
      };

    case "acceptPending":
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId ? commitPendingDocument(document) : document,
        ),
      };
      return {
        ...state,
        documents: state.documents.map((document) =>
          document.id === action.payload.documentId ? discardPendingDocument(document) : document,
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