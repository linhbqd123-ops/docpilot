import type { ThemeMode } from "@/app/themes";

export type SidebarView = "library" | "outline" | "review" | "connect" | "settings";
export type ChatMode = "ask" | "agent";

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

export interface RevisionTextDiff {
  blockId?: string | null;
  changeType: string;
  oldText?: string | null;
  newText?: string | null;
  offset?: number | null;
}

export interface RevisionStyleDiff {
  blockId?: string | null;
  runId?: string | null;
  property: string;
  oldValue?: string | null;
  newValue?: string | null;
}

export interface RevisionLayoutDiff {
  blockId?: string | null;
  changeType: string;
  oldValue?: string | null;
  newValue?: string | null;
}

export interface RevisionDiff {
  baseRevisionId?: string | null;
  targetRevisionId?: string | null;
  textEditCount: number;
  styleEditCount: number;
  layoutEditCount: number;
  hasConflicts: boolean;
  textDiffs: RevisionTextDiff[];
  styleDiffs: RevisionStyleDiff[];
  layoutDiffs: RevisionLayoutDiff[];
}

export interface PendingRevisionPreview {
  revisionId: string;
  documentSessionId?: string | null;
  baseRevisionId?: string | null;
  currentRevisionId?: string | null;
  available: boolean;
  message?: string | null;
  html?: string | null;
  sourceHtml?: string | null;
  validation?: RevisionValidation;
  diff?: RevisionDiff | null;
}

export interface ReviewOperationTarget {
  blockId?: string | null;
  runId?: string | null;
  start?: number | null;
  end?: number | null;
  tableId?: string | null;
  rowId?: string | null;
  cellId?: string | null;
  cellLogicalAddress?: string | null;
}

export interface ReviewOperation {
  op: string;
  description: string;
  operationIndex?: number | null;
  blockId?: string | null;
  target?: ReviewOperationTarget | null;
  value?: unknown;
}

export interface ChatMessageRevisionLink {
  revisionId: string;
  documentSessionId?: string | null;
  baseRevisionId?: string | null;
  summary?: string | null;
  status?: string | null;
}

export interface RevisionReview {
  revisionId: string;
  status: string;
  summary: string;
  author: string;
  scope: string;
  createdAt?: string | null;
  operationCount: number;
  affectedBlockIds: string[];
  validation?: RevisionValidation;
  preview?: PendingRevisionPreview | null;
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

export interface TurnUsageRequest {
  requestIndex: number;
  phase?: string | null;
  requestPurpose?: string | null;
  provider?: string;
  providerDisplayName?: string;
  model?: string | null;
  inputChars: number;
  outputChars: number;
  estimatedInputTokens: number;
  estimatedOutputTokens: number;
  estimatedTotalTokens: number;
}

export interface TurnUsage {
  requestCount: number;
  estimatedInputTokens: number;
  estimatedOutputTokens: number;
  estimatedTotalTokens: number;
  requests: TurnUsageRequest[];
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
  sourceHtml?: string | null;
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
  mode?: ChatMode;
  usage?: TurnUsage;
  toolActivity?: ToolActivity[];
  notices?: AgentNotice[];
  linkedRevision?: ChatMessageRevisionLink | null;
}

export interface Chat {
  id: string;
  name: string;
  documentId: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
}

export interface AgentExecutionSettings {
  maxInputTokens: number;
  sessionContextBudgetTokens: number;
  toolResultBudgetTokens: number;
  maxToolBatchSize: number;
  maxParallelTools: number;
  maxHeavyToolsPerTurn: number;
  autoCompactSession: boolean;
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
  agentConfig: AgentExecutionSettings;
  /** Chat mode: 'agent' uses document-aware agent flows; 'ask' uses a simple Q&A mode. */
  mode: ChatMode;
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
  focusedChatMessageId: string | null;
  focusedChatMessageRequestId: number;
}

export interface AgentTurnResponse {
  chatId?: string;
  message: string;
  mode: ChatMode;
  intent?: string | null;
  resultType: "answer" | "clarify" | "revision_staged";
  documentSessionId?: string | null;
  baseRevisionId?: string | null;
  revisionId?: string | null;
  status: string;
  usage?: TurnUsage;
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
  sourceHtml?: string | null;
  session: SessionSummary;
  revisions: SessionRevisionSummary[];
  result?: {
    revisionId?: string | null;
    status?: string;
    currentRevisionId?: string | null;
  };
}