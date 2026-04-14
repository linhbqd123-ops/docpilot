import type { ThemeMode } from "@/app/themes";

export type SidebarView = "library" | "outline" | "review" | "connect" | "settings";

export type DocumentKind = "html" | "markdown" | "text" | "docx" | "pdf" | "unknown";

export type DocumentStatus = "ready" | "needs_backend" | "error";

export type ConnectionStatus = "idle" | "checking" | "online" | "offline";

export interface OutlineItem {
  id: string;
  title: string;
  level: number;
}

export interface DocumentRecord {
  id: string;
  name: string;
  kind: DocumentKind;
  mimeType: string;
  size: number;
  status: DocumentStatus;
  html: string;
  outline: OutlineItem[];
  wordCount: number;
  createdAt: number;
  updatedAt: number;
  /** docId assigned by the Java doc-processor; used for DOCX export to restore original styles. */
  backendDocId?: string;
  error?: string;
  pendingHtml?: string;
  pendingOutline?: OutlineItem[];
  pendingWordCount?: number;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "error";
  content: string;
  createdAt: number;
  status?: "streaming" | "sent" | "error";
}

export interface AppSettings {
  apiBaseUrl: string;
  providerEndpoint?: string;
  provider: "ollama" | "openai" | "groq" | "openrouter" | "together" | "zai" | "anthropic" | "azure" | "custom";
  /** Optional model override — sent as the `model` field in /api/chat requests.
   *  If empty, the backend uses its default model for the selected provider. */
  modelOverride: string;
  requestTimeoutMs: number;
  streaming: boolean;
  connectOnStartup: boolean;
  theme: ThemeMode;
}

export interface ConnectionState {
  status: ConnectionStatus;
  lastCheckedAt: number | null;
  error: string | null;
  version: string | null;
}

export interface PersistedState {
  documents: DocumentRecord[];
  selectedDocumentId: string | null;
  messageThreads: Record<string, ChatMessage[]>;
  settings: AppSettings;
  activeSidebarView: SidebarView;
}

export interface AppState extends PersistedState {
  connection: ConnectionState;
  composerValue: string;
  isSending: boolean;
  banner: string | null;
}

export interface ChatResponse {
  message: string;
  documentHtml?: string;
  notices?: string[];
}