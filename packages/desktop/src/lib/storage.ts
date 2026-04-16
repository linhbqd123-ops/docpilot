import type { AppState, PersistedState } from "@/app/types";
import { DEFAULT_SETTINGS } from "@/app/reducer";
import { rehydrateDocument } from "@/lib/document";

const STORAGE_KEY = "docpilot.desktop.state";

function readPersistedState(): PersistedState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);

    if (!raw) {
      return null;
    }

    return JSON.parse(raw) as PersistedState;
  } catch {
    return null;
  }
}

export function loadPersistedState(): Partial<PersistedState> {
  const parsed = readPersistedState();

  if (!parsed) {
    return {};
  }

  return {
    ...parsed,
    documents: [],
    chats: parsed.chats ?? [],
    selectedChatId: null,
    messageThreads: parsed.messageThreads ?? {},
  };
}

export function loadLegacyDocumentsSnapshot() {
  const parsed = readPersistedState();
  return (parsed?.documents ?? []).map(rehydrateDocument);
}

export function savePersistedState(state: AppState) {
  const payload: PersistedState = {
    documents: [],
    selectedDocumentId: state.selectedDocumentId,
    chats: state.chats,
    selectedChatId: null,
    messageThreads: state.messageThreads,
    settings: {
      ...state.settings,
      // Never persist apiBaseUrl from runtime state. The bundled build decides it.
      apiBaseUrl: DEFAULT_SETTINGS.apiBaseUrl,
    },
    activeSidebarView: state.activeSidebarView,
  };

  localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
}