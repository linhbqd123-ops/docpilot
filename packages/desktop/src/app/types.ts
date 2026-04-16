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

export interface RevisionValidation {
  structureOk: boolean;
  styleOk: boolean;
  scope: string;
  errors: string[];
  warnings: string[];
}

export interface ReviewOperation {
  op: string;
  description: string;
  blockId?: string | null;
}

export interface RevisionReview {
  revisionId: string;
  status: string;
  summary: string;
  author: string;
  scope: string;
  createdAt?: string | null;
  operationCount: number;
  validation?: RevisionValidation;
  operations: ReviewOperation[];
}

export interface RevisionProposal {
  revisionId: string;
  status: string;
  operationCount: number;
  summary: string;
  validation?: RevisionValidation;
}

export interface AgentNotice {
  code?: string;
  message?: string;
  statusCode?: number;
  items?: string[];
}

export interface ToolActivity {
  event: string;
  tool?: string;
  [key: string]: unknown;
}

export interface SessionRevisionSummary {
  revisionId: string;
  baseRevisionId?: string | null;
  status: string;
  summary: string;
  author?: string;
  scope?: string;
  createdAt?: string | null;
  appliedAt?: string | null;
}

export interface SessionSummary {
  sessionId: string;
  docId?: string | null;
  filename?: string;
  state?: string | null;
  currentRevisionId?: string | null;
  wordCount: number;
  paragraphCount?: number;
  tableCount?: number;
  imageCount?: number;
  sectionCount?: number;
  createdAt?: string | null;
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
  /** docId assigned by the canonical document service. */
  backendDocId?: string;
  documentSessionId?: string;
  baseRevisionId?: string | null;
  currentRevisionId?: string | null;
  pendingRevisionId?: string;
  revisionStatus?: string | null;
  sessionState?: string | null;
  reviewPayload?: RevisionReview | null;
  revisions: SessionRevisionSummary[];
  error?: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system" | "error";
  content: string;
  createdAt: number;
  status?: "streaming" | "sent" | "error";
  toolActivity?: ToolActivity[];
  notices?: AgentNotice[];
}

export interface Chat {
  id: string;
  name: string;
  documentId: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

export interface AppSettings {
  apiBaseUrl: string;
  provider: "ollama" | "openai" | "groq" | "openrouter" | "together" | "nvidia" | "zai" | "anthropic" | "azure" | "custom";
  /** Optional model override — sent as the `model` field in /api/agent/turn requests.
   *  If empty, the backend uses its default model for the selected provider. */
  modelOverride: string;
  requestTimeoutMs: number;
  streaming: boolean;
  connectOnStartup: boolean;
  /** Chat mode: 'agent' uses document-aware agent flows; 'ask' uses a simple Q&A mode. */
  mode: "agent" | "ask";
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
  chats: Chat[];
  selectedChatId: string | null;
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

export interface AgentTurnResponse {
  chatId?: string;
  message: string;
  mode: "ask" | "agent";
  intent?: string | null;
  resultType: "answer" | "clarify" | "revision_staged";
  documentSessionId?: string | null;
  baseRevisionId?: string | null;
  revisionId?: string | null;
  status: string;
  proposal?: RevisionProposal | null;
  review?: RevisionReview | null;
  toolActivity: ToolActivity[];
  notices: AgentNotice[];
}

export interface ImportedDocumentPayload {
  docId?: string | null;
  documentSessionId?: string | null;
  baseRevisionId?: string | null;
  html: string;
  wordCount: number;
  pageCount: number;
  filename: string;
}

export interface SessionRefreshPayload {
  documentSessionId: string;
  html?: string;
  session: SessionSummary;
  revisions: SessionRevisionSummary[];
  result?: {
    revisionId?: string | null;
    status?: string;
    currentRevisionId?: string | null;
  };
}