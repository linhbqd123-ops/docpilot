import type { AppState, PersistedState } from "@/app/types";
import { rehydrateDocument } from "@/lib/document";

const STORAGE_KEY = "docpilot.desktop.state";

export function loadPersistedState(): Partial<PersistedState> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);

    if (!raw) {
      return {};
    }

    const parsed = JSON.parse(raw) as PersistedState;

    return {
      ...parsed,
      documents: parsed.documents.map(rehydrateDocument),
    };
  } catch {
    return {};
  }
}

export function savePersistedState(state: AppState) {
  const payload: PersistedState = {
    documents: state.documents,
    selectedDocumentId: state.selectedDocumentId,
    messageThreads: state.messageThreads,
    settings: state.settings,
    activeSidebarView: state.activeSidebarView,
  };

  localStorage.setItem(STORAGE_KEY, JSON.stringify(payload));
}