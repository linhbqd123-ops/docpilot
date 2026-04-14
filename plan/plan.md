# DocPilot — The AI Agent for Documents

> *GitHub Copilot là Copilot cho code. Claude Code là agent cho terminal. **DocPilot** là agent cho tài liệu.*

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Stars](https://img.shields.io/github/stars/your-org/docpilot?style=social)](https://github.com/your-org/docpilot)

---

## 🎯 Vision & Positioning

### Vấn đề thực sự cần giải quyết
Hàng tỷ người dùng Microsoft Word, Google Docs mỗi ngày — nhưng:
- AI hiện tại (ChatGPT, Copilot) **không hiểu cấu trúc tài liệu** (style, heading hierarchy, table layout, section break).
- Paste văn bản vào ChatGPT → mất hết format → không dùng được thực tế.
- Không có tool nào như **"Claude Code nhưng cho DOCX/PDF"** — ra lệnh bằng ngôn ngữ tự nhiên, agent tự xử lý toàn bộ.

### Định vị sản phẩm
```
GitHub Copilot  → inline AI suggestion cho code
Claude Code     → agentic terminal, hiểu toàn bộ codebase
DocPilot        → agentic document editor, hiểu toàn bộ tài liệu
```

### Đối tượng người dùng mục tiêu
| Nhóm | Nhu cầu cụ thể |
|------|---------------|
| **Luật sư / Legal** | Review hàng trăm trang hợp đồng, rewrite clause, standardize format |
| **Kỹ sư / Technical Writer** | Maintain spec doc, API doc, generate doc từ code |
| **Researcher / Academic** | Format theo APA/IEEE, rewrite abstract, translate + keep format |
| **Business / Consultant** | Reformat report, update template, merge nhiều doc |
| **Developer** | Cần tool CLI `docpilot edit contract.docx "rewrite section 3 in formal tone"` |

---

## 🚨 Product Focus — Production-Ready Desktop App

> **Mục tiêu duy nhất:** Xây dựng một **Standalone Desktop App** production-ready với đầy đủ tính năng trước khi mở rộng sang CLI / VS Code extension.  Chỉ khi Desktop App hoàn thiện và stable mới phát triển thêm platform khác.

- Primary deliverable: Standalone Desktop App (React + Vite, wrap Tauri sau) với Copilot-like UX — document viewer, assistant chat, diff/accept-reject panel, import DOCX/PDF, export DOCX.
- CLI và VS Code extension: **để sau**, chỉ sau khi Desktop App production-ready.
- Provider strategy: Python FastAPI làm **API Gateway duy nhất** mà frontend kết nối. Python gọi Java doc-processor nội bộ. Hỗ trợ Ollama (mặc định, local), OpenAI, Anthropic, Azure OpenAI.

---

## 🏗️ Kiến trúc tổng thể (Enhanced)

### Delivery Modes — Điểm khác biệt lớn nhất với plan cũ

```
┌─────────────────────────────────────────────────────────────┐
│                     DocPilot Ecosystem                       │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  CLI Tool    │  │ Desktop App  │  │  VS Code Ext.    │  │
│  │  (như Claude │  │ (Tauri/Elec- │  │  (như Copilot    │  │
│  │   Code)      │  │  tron)       │  │   nhưng cho doc) │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         │                 │                    │            │
│  ┌──────┴─────────────────┴────────────────────┴─────────┐  │
│  │              DocPilot Core Engine (Python)             │  │
│  │         FastAPI + LangGraph + OpenAI / Anthropic       │  │
│  └──────────────────────────┬────────────────────────────┘  │
│                             │                               │
│  ┌──────────────┬───────────┴──────────┬──────────────────┐ │
│  │ Doc Processor│   AI Agent Swarm     │  Plugin System   │ │
│  │ (Java/Python)│   (Multi-agent)      │  (3rd party ext) │ │
│  └──────────────┴──────────────────────┴──────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Stack kỹ thuật (Đã xác nhận)

| Layer | Technology | Trạng thái |
|-------|-----------|------------|
| **Desktop App** | React + Vite SPA (wrap Tauri sau) | ✅ UI hoàn chỉnh |
| **AI Gateway** | Python FastAPI (port 8000) | ✅ Implemented |
| **Doc Processor** | Java Spring Boot + docx4j (port 8080, internal) | ✅ Implemented |
| **Agent Orchestration** | LangGraph (Phase 2) / Simple agent (Phase 0) | 🔄 Simple agent done |
| **Database** | Local JSON files → SQLite (Phase 1) → PostgreSQL+pgvector (Phase 3) | 📋 Planned |
| **Storage** | Local filesystem | ✅ Done |
| **Desktop Shell** | Tauri 2.0 (Phase 2) | 📋 Planned |

**Architecture cố định:**
```
Frontend (React :5173) → Python FastAPI (:8000) → Java doc-processor (:8080)
                                    ↓
                            LLM Provider
                     (Ollama local / OpenAI / Anthropic / Azure)
```

---

## 🤖 Multi-Agent Architecture (Chi tiết)

### Agent Graph (LangGraph State Machine)

```
User Request
     │
     ▼
┌─────────────────┐
│  Orchestrator   │ ← Phân tích intent, routing
│     Agent       │
└────────┬────────┘
         │
    ┌────┴────────────────────────────────┐
    │                                     │
    ▼                                     ▼
┌─────────┐                        ┌─────────────┐
│  Parse  │                        │  Understand │
│  Agent  │ → Đọc cấu trúc doc    │    Agent    │ → Semantic understanding
│         │   (sections, styles)   │             │   full document context
└────┬────┘                        └──────┬──────┘
     │                                    │
     └──────────────┬─────────────────────┘
                    │
                    ▼
            ┌──────────────┐
            │  Edit Agent  │ → Thực thi thay đổi, giữ style
            │              │   (có thể gọi sub-agent)
            └──────┬───────┘
                   │
         ┌─────────┴──────────┐
         │                    │
         ▼                    ▼
  ┌─────────────┐    ┌──────────────────┐
  │   Refine    │    │   Validate Agent │
  │ Style Agent │    │  (diff + review) │
  │  (optional) │    └────────┬─────────┘
  └─────────────┘             │
                              ▼
                       ┌─────────────┐
                       │   Export    │
                       │   Agent     │ → DOCX / PDF / MD
                       └─────────────┘
```

### Các Agent cụ thể

#### 1. Orchestrator Agent
- Phân tích user intent từ natural language
- Quyết định sub-agent nào cần kích hoạt
- Quản lý state của toàn bộ session
- Tool calls: `parse_document`, `call_edit_agent`, `call_refine_agent`

#### 2. Parse Agent (Doc Intelligence)
- Đọc structure: headings, paragraphs, tables, lists, images, footnotes
- Extract style metadata: font, spacing, color, numbering
- Build document semantic map (context-aware)
- Output: JSON document tree + style registry

#### 3. Understand Agent (Semantic Context)
- Đọc toàn bộ doc để AI hiểu context (dùng chunking + summarization nếu doc dài)
- Xây dựng "document memory" cho session
- Detect document type: contract, report, academic paper, spec...
- Output: semantic summary + intent hints cho Edit Agent

#### 4. Edit Agent (Core AI Worker)
- Nhận instruction từ user (natural language)
- Thực thi: rewrite, translate, summarize, format, insert, delete
- Không phá vỡ style — edit content-only, style được restore từ style registry
- Streaming output để UX real-time

#### 5. Refine Style Agent (Auto-trigger)
- Kích hoạt khi: style mapping confidence < threshold
- RAG từ style history DB + embedding similarity
- Học từ accept/reject của user → cải thiện theo thời gian

#### 6. Validate Agent
- Diff engine: word-level, paragraph-level
- Fidelity check (style preserved?)
- User review queue: accept / reject / partial accept

#### 7. Export Agent
- HTML → DOCX (qua Java MCP)
- DOCX → PDF (LibreOffice headless / WeasyPrint)
- DOCX → Markdown (pandoc)
- Versioning: lưu mọi export với timestamp

---

## 🛠️ Chi tiết Kỹ thuật từng Layer

### Layer 1: CLI Tool — `docpilot`

**Installation:**
```bash
pip install docpilot
# hoặc
brew install docpilot       # macOS
winget install docpilot    # Windows
```

**Usage (như Claude Code):**
```bash
# Interactive mode — giống Claude Code
docpilot chat contract.docx

# One-shot command
docpilot edit report.docx "rewrite executive summary in formal tone"
docpilot translate thesis.docx --to en --keep-format
docpilot diff old.docx new.docx --output diff.html
docpilot export contract.docx --format pdf

# Pipe support
cat prompt.txt | docpilot edit report.docx --stdin
```

**Core CLI modules:**
```
docpilot-cli/
├── commands/
│   ├── chat.py         ← interactive REPL với streaming
│   ├── edit.py         ← one-shot edit command
│   ├── translate.py    ← translate giữ format
│   ├── diff.py         ← compare 2 docs
│   └── export.py       ← multi-format export
├── config.py           ← API key, model selection, local/cloud mode
└── display.py          ← Rich terminal UI, progress bars
```

### Layer 2: VS Code Extension

**Features:**
- Sidebar panel: mở doc, chat với AI về nội dung doc
- Inline suggestion khi viết trong `.md` file
- Command Palette: `DocPilot: Edit Selection`, `DocPilot: Translate`, `DocPilot: Summarize`
- Webview để preview DOCX trong VS Code

**Tại sao VS Code Extension quan trọng:**
- 35M+ VS Code users → massive free distribution channel
- Developer demographic → viral trên GitHub, Twitter
- Không cần install riêng → friction = 0

### Layer 3: Desktop App (Tauri)

```
┌────────────────────────────────────────────┐
│ DocPilot Desktop                    _ □ ✕  │
├────────────┬───────────────────────────────┤
│ 📁 Files   │  [ contract.docx ]            │
│ ─────────  │  ┌─────────────────────────┐  │
│ contract.. │  │  Section 1: Parties...  │  │
│ report.do  │  │  [CHANGED] Lorem ipsum  │  │  ← diff view
│ thesis.do  │  │  Section 2: Terms...    │  │
│            │  └─────────────────────────┘  │
│            │                               │
│            │  💬 Ask DocPilot...           │
│            │  > "Rewrite section 2 in     │
│            │     plain English"            │
│            │  ┌─────────────────────────┐  │
│            │  │ ✓ Accept  ✗ Reject      │  │
│            │  │ ✓ Accept All            │  │
│            │  └─────────────────────────┘  │
└────────────┴───────────────────────────────┘
```

**Tech:** Tauri 2.0 (Rust backend) + React frontend
- Bundle size: ~15MB (so với Electron ~150MB)
- Native performance, cross-platform: Windows / macOS / Linux

### Layer 4: Doc Processor (Java MCP Service)

**Tại sao giữ Java:**
- `docx4j` là thư viện tốt nhất cho DOCX round-trip — không thư viện Python nào match được fidelity.
- Chạy như sidecar process, CLI tự start/stop khi cần.

**Enhanced API:**
```
POST /convert/docx-to-html
  → trả về HTML + style_registry (JSON)

POST /convert/html-to-docx
  body: { html, style_registry, doc_id }
  → trả về DOCX binary

POST /convert/docx-to-markdown  ← MỚI
POST /convert/pdf-to-text       ← MỚI (PDF support)
GET  /styles/{docId}
GET  /structure/{docId}         ← MỚI: document outline/tree
```

**Style Registry (enhanced):**
```json
{
  "doc_id": "uuid",
  "styles": {
    "Heading1": { "font": "Calibri", "size": 16, "bold": true, "color": "#1F4E79", "spacing_before": 12 },
    "Normal": { "font": "Calibri", "size": 11, "color": "#000000" },
    "TableHeader": { "font": "Calibri", "size": 11, "bold": true, "bg_color": "#D6E4F0" }
  },
  "custom_styles": [...],
  "page_layout": { "margins": {...}, "orientation": "portrait", "size": "A4" },
  "numbering_definitions": [...]
}
```

### Layer 5: Core AI Engine

**LangGraph State:**
```python
class DocPilotState(TypedDict):
    document_id: str
    original_html: str
    current_html: str
    style_registry: dict
    semantic_map: dict          # document structure understanding
    conversation_history: list  # multi-turn context
    pending_edits: list         # edits awaiting user approval
    edit_history: list          # accepted edits
    active_agents: list         # agents currently running
    export_config: dict
```

**Model strategy:**
```python
# Configurable — user tự chọn
MODELS = {
    "fast":    "gpt-4o-mini",   # cheap, quick tasks
    "default": "gpt-4o",        # balanced
    "power":   "claude-3-5-sonnet",  # complex restructuring
    "local":   "ollama/llama3.2",    # privacy mode, fully local
}
```

**Local-first / Privacy mode:**
- Chạy hoàn toàn local với Ollama + llama3.2 / mistral
- Không gửi document ra ngoài → quan trọng cho legal / enterprise
- Đây là **USP lớn** so với web-based tools

---

## 📐 Database Schema (Enhanced)

```sql
-- Documents table
CREATE TABLE documents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    filename    VARCHAR(500),
    file_path   TEXT,
    file_hash   VARCHAR(64),      -- SHA-256, detect duplicates
    doc_type    VARCHAR(50),      -- contract, report, academic, etc.
    page_count  INTEGER,
    word_count  INTEGER,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Style Registry
CREATE TABLE style_registries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id      UUID REFERENCES documents(id) ON DELETE CASCADE,
    style_data  JSONB NOT NULL,   -- full style_registry JSON
    version     INTEGER DEFAULT 1,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Edit Sessions
CREATE TABLE edit_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id          UUID REFERENCES documents(id),
    user_instruction TEXT,
    status          VARCHAR(50),  -- pending, accepted, rejected, partial
    diff_snapshot   JSONB,        -- word-level diff
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Edit History (for learning)
CREATE TABLE edit_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      UUID REFERENCES edit_sessions(id),
    doc_id          UUID REFERENCES documents(id),
    paragraph_id    VARCHAR(100),
    original_text   TEXT,
    edited_text     TEXT,
    instruction     TEXT,
    accepted        BOOLEAN,
    timestamp       TIMESTAMPTZ DEFAULT NOW()
);

-- Style Mapping (for Refine Agent RAG)
CREATE TABLE style_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    style_class     VARCHAR(200),
    predicted_style JSONB,
    confidence      FLOAT,
    embedding       vector(1536),  -- pgvector
    source_doc_type VARCHAR(50),
    verified        BOOLEAN DEFAULT FALSE
);

-- Versions
CREATE TABLE document_versions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id      UUID REFERENCES documents(id),
    version_num INTEGER,
    html_path   TEXT,
    docx_path   TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    message     TEXT         -- "After edit: rewrite section 3"
);
```

---

## 📁 Monorepo Structure

```
docpilot/                          ← GitHub repo root
├── README.md                      ← VIRAL README với GIF demos
├── CONTRIBUTING.md
├── LICENSE (MIT)
├── packages/
│   ├── cli/                       ← pip install docpilot
│   │   ├── docpilot/
│   │   │   ├── commands/
│   │   │   ├── agents/
│   │   │   └── core/
│   │   └── pyproject.toml
│   │
│   ├── vscode-extension/          ← VS Code Marketplace
│   │   ├── src/
│   │   │   ├── extension.ts
│   │   │   ├── sidebar/
│   │   │   └── webview/
│   │   └── package.json
│   │
│   ├── desktop/                   ← Tauri app (cross-platform)
│   │   ├── src-tauri/
│   │   └── src/ (React)
│   │
│   ├── core-engine/               ← Python AI Agent
│   │   ├── agents/
│   │   │   ├── orchestrator.py
│   │   │   ├── parse_agent.py
│   │   │   ├── edit_agent.py
│   │   │   ├── refine_agent.py
│   │   │   └── export_agent.py
│   │   ├── api/
│   │   │   └── routes/
│   │   └── models/
│   │
│   ├── doc-processor/             ← Java MCP Service
│   │   └── src/main/java/
│   │       └── io/docpilot/
│   │           ├── convert/
│   │           ├── style/
│   │           └── api/
│   │
│   └── shared/                    ← Shared types, utils
│       ├── types/
│       └── proto/                 ← gRPC proto definitions
│
├── apps/
│   └── web/                       ← Web app (optional, Phase 4)
│
├── docker/
│   ├── docker-compose.yml
│   └── docker-compose.dev.yml
│
└── turbo.json                     ← Turborepo config
```

---

## 🔌 Plugin System (Killer Feature cho OSS)

Đây là thứ giúp repo **kiếm sao** từ community:

```python
# Plugin interface — ai cũng có thể build plugin
class DocPilotPlugin:
    name: str
    version: str
    doc_types: list[str]  # ["contract", "academic", "*"]

    def on_parse(self, doc_tree: DocTree) -> DocTree: ...
    def on_edit(self, instruction: str, context: EditContext) -> EditResult: ...
    def on_export(self, html: str, format: str) -> bytes: ...
    def custom_commands(self) -> list[Command]: ...
```

**Plugin examples để seed community:**
```bash
docpilot plugin install docpilot-legal     # Legal clause detection + rewrite
docpilot plugin install docpilot-academic  # Citation format, APA/IEEE
docpilot plugin install docpilot-translate # DeepL integration
docpilot plugin install docpilot-notion    # Import/export Notion pages
docpilot plugin install docpilot-gdocs     # Google Docs integration
```

---

## 🚀 Roadmap — Production-Ready Desktop App

> **Triết lý:** Làm đúng từng phase, không chuyển sang feature mới khi feature hiện tại chưa production-ready.

### Phase 0 — Core Working ✅ (Đang thực hiện)
- [x] React frontend hoàn chỉnh (layout, chat, diff/accept-reject, settings)
- [x] Java doc-processor: DOCX↔HTML, PDF→Text, style registry, structure extraction
- [x] Python FastAPI gateway: `/api/chat` SSE streaming, `/api/documents/import`, `/api/documents/export`
- [x] Provider adapters: Ollama, OpenAI, Anthropic, Azure
- [x] Document import flow: DOCX/PDF → backend → HTML rendered in editor
- [x] Export DOCX: HTML + backendDocId → Java → DOCX download
- [x] Edit agent: streaming với `<DOCUMENT>` block extraction
- [ ] **Tiếp theo:** End-to-end test với Ollama local, record demo GIF

### Phase 1 — Production Polish (2–3 tuần)
- [ ] Fidelity test suite: DOCX → HTML → edit → HTML → DOCX roundtrip ≥ 85%
- [ ] Error UX: retry import button, clearer error states trong LibraryPanel
- [ ] Connection stability: auto-reconnect, health polling
- [ ] Large document support: chunking cho doc > 100K chars (hiện bị truncate)
- [ ] Request cancellation: abort DOCX import mid-flight
- [ ] Local Ollama setup guide + model recommendation trong app
- [ ] **Target: App stable đủ để demo public**

### Phase 2 — Multi-Agent & Tauri Shell (3–4 tuần)
- [ ] LangGraph integration: Orchestrator + Edit + Validate agents
- [ ] Word-level diff viewer (inline highlight changes)
- [ ] SQLite persistence: document history, edit sessions
- [ ] Tauri 2.0 wrapper: `src-tauri/` shell, cross-platform installer
- [ ] PDF viewer: render PDF trong DocumentCanvas (không chỉ text)
- [ ] Plugin system v1: Python plugin interface

### Phase 3 — Ecosystem & Distribution (4–6 tuần)
- [ ] Tauri auto-update, GitHub Releases CI/CD
- [ ] VS Code Extension (sau khi Desktop App stable)
- [ ] PostgreSQL + pgvector: style embedding RAG
- [ ] Refine Style Agent: học từ accept/reject history
- [ ] Plugin marketplace groundwork

### Phase 4 — Community & Scale (6+ tháng)
- [ ] CLI tool (`pip install docpilot`)
- [ ] Google Docs / Notion integration plugins
- [ ] Web app (optional)
- [ ] DocPilot Cloud (hosted, free tier + Pro)

---

## 📣 Open Source Growth Strategy (Triệu star plan)

### Tại sao project này có thể viral

1. **"Copilot for Docs"** — hook ngay trong headline, ai cũng hiểu ngay
2. **Demo-driven** — 1 GIF ấn tượng worth 1000 words. Demo: AI rewrite cả hợp đồng 50 trang, giữ nguyên format
3. **Dev-friendly CLI** — developer = Twitter/Reddit influencer → viral tự nhiên
4. **Local-first / Privacy** — đúng trend 2025-2026, không cần gửi doc lên cloud
5. **Plugin ecosystem** — community có thể contribute → network effect

### Viral content plan

```
Week 1: Post PoC demo GIF
  → Reddit r/MachineLearning: "I built Claude Code but for DOCX documents"
  → HackerNews: "Show HN: DocPilot – AI agent for your Word documents"
  → Twitter/X: demo video 60 giây

Week 2-4: Blog series
  → "How I achieved 90% DOCX fidelity with open source tools"
  → "Building a LangGraph multi-agent system for document editing"
  → Dev.to / Medium / Hashnode

Month 2: VS Code Extension launch
  → ProductHunt launch
  → VS Code Marketplace feature request

Month 3+: Community
  → Office hours / Discord
  → Plugin bounty program
  → "Good first issue" label → attract contributors
```

### README Structure (Viral README blueprint)

```markdown
# DocPilot
> The AI agent for your documents. Like GitHub Copilot, but for DOCX.

[Demo GIF — 10 giây, wow factor]

## Install in 30 seconds
pip install docpilot

## What it does
[5 dòng, copy tốt]

## Demo
[GIF 1: Rewrite contract section]
[GIF 2: Translate thesis, keep format]
[GIF 3: diff view, accept/reject]

## Why DocPilot?
[So sánh với alternatives, DocPilot wins]

## Star History
[Star history chart]
```

---

## 🔒 Security & Privacy

| Risk | Mitigation |
|------|-----------|
| Sensitive document leak | Local-first mode (Ollama), no cloud required |
| Prompt injection qua document content | Sanitize document content trước khi inject vào prompt |
| API key exposure | Keyring/OS credential store, không lưu plaintext |
| File path traversal | Validate & sandbox tất cả file operations |
| Malicious DOCX (macro, external ref) | Quarantine check, disable macro execution |

---

## 📊 KPIs & Success Metrics

| Metric | Phase 1 Target | Phase 4 Target |
|--------|---------------|---------------|
| GitHub Stars | 500 | 20,000+ |
| PyPI downloads/month | 1,000 | 50,000+ |
| VS Code installs | — | 10,000+ |
| DOCX fidelity | ≥ 85% | ≥ 95% |
| Edit latency (p95) | < 5s | < 2s |
| Convert time (<10MB) | < 5s | < 2s |
| Community plugins | 0 | 20+ |

---

## 🧪 Testing Strategy

```
tests/
├── fixtures/
│   ├── simple.docx         ← heading + paragraph
│   ├── complex.docx        ← table + image + footnote
│   ├── legal.docx          ← multi-column, numbering
│   └── academic.docx       ← citation, abstract
├── unit/
│   ├── test_parse_agent.py
│   ├── test_edit_agent.py
│   └── test_style_mapping.py
├── integration/
│   ├── test_roundtrip.py   ← DOCX → HTML → DOCX → compare
│   └── test_pipeline.py    ← end-to-end edit flow
└── fidelity/
    └── test_fidelity.py    ← tự động đo fidelity score
```

**Fidelity score formula:**
```
fidelity = (
    style_preserved_ratio * 0.4 +
    layout_preserved_ratio * 0.3 +
    content_accuracy_ratio * 0.3
) * 100
```

---

## 💡 Tóm tắt & Next Steps

### Bức tranh toàn cảnh
DocPilot không chỉ là tool convert DOCX — đây là **AI agent hoàn chỉnh cho tài liệu**, có thể cài trên máy như một dev tool, tích hợp vào VS Code, là nền tảng open source cho community build plugin.

### Bắt đầu ngay — Priority order
1. **Ngay bây giờ:** Setup repo, viết README killer, implement CLI PoC
2. **Tuần 1:** Java doc processor hoạt động được
3. **Tuần 2:** Core Agent + `docpilot edit` command
4. **Tuần 3:** Demo GIF + Post lên Reddit/HN
5. **Tuần 4+:** Iterate dựa trên feedback cộng đồng

### Tech stack cuối cùng
```
CLI:        Python (Typer + Rich)
Desktop:    Tauri 2.0 + React
VS Code:    TypeScript Extension API  
AI Engine:  FastAPI + LangGraph + OpenAI/Ollama
Doc Layer:  Java (docx4j) + Spring Boot
Database:   PostgreSQL + pgvector (prod) / SQLite (local)
Storage:    Local filesystem (default) / S3 (opt-in)
Monorepo:   Turborepo
CI/CD:      GitHub Actions
```

---

*DocPilot — Edit smarter, not harder.*