# DocPilot — Doc Processor

> Java Spring Boot microservice for high-fidelity DOCX / PDF processing.

Part of the [DocPilot](../../README.md) monorepo.

---

## What it does

| Endpoint | Description |
|----------|-------------|
| `POST /api/convert/docx-to-html` | DOCX → HTML + full **StyleRegistry** |
| `POST /api/convert/html-to-docx` | HTML → DOCX (restores original styles) |
| `POST /api/convert/docx-to-markdown` | DOCX → GitHub-Flavored Markdown |
| `POST /api/convert/pdf-to-text` | PDF → plain text (PDFBox) |
| `POST /api/structure` | Extract document outline / statistics |
| `GET  /api/styles/{docId}` | Retrieve stored StyleRegistry |
| `GET  /api/structure/{docId}` | Retrieve stored DocumentStructure |

Interactive API docs: **http://localhost:8080/swagger-ui**

---

## Quick start

### Prerequisites
- Java 17+
- Maven 3.9+

```bash
# Clone the repo
cd packages/doc-processor

# Copy env file
cp .env.example .env

# Build & run
mvn spring-boot:run
# → http://localhost:8080/swagger-ui
```

The `.mvn/jvm.config` file automatically adds the required `--add-opens` flags for docx4j on Java 17+.

### Docker

```bash
# Build
docker build -t docpilot/doc-processor .

# Run (with persistent volumes)
docker compose up -d

# Logs
docker compose logs -f
```

### Run tests

```bash
mvn test
```

---

## API usage examples

### DOCX → HTML

```bash
curl -X POST http://localhost:8080/api/convert/docx-to-html \
  -F "file=@contract.docx" \
  | jq '{docId: .docId, wordCount: .wordCount, htmlLength: (.html | length)}'
```

**Response:**
```json
{
  "docId": "550e8400-e29b-41d4-a716-446655440000",
  "html": "<!DOCTYPE html>...",
  "styleRegistry": {
    "docId": "550e8400-...",
    "styles": {
      "Heading1": { "fontAscii": "Calibri", "sizePt": 16.0, "bold": true, "color": "#1F4E79" },
      "Normal":   { "fontAscii": "Calibri", "sizePt": 11.0 }
    },
    "pageLayout": { "orientation": "portrait", "paperSize": "A4", "widthPt": 595.28 }
  },
  "wordCount": 3240
}
```

### HTML → DOCX (with style restore)

```bash
curl -X POST http://localhost:8080/api/convert/html-to-docx \
  -H "Content-Type: application/json" \
  -d '{"html": "<h1>Rewritten Contract</h1><p>...</p>", "doc_id": "550e8400-..."}' \
  --output restored.docx
```

### Extract document structure

```bash
curl -X POST http://localhost:8080/api/structure \
  -F "file=@report.docx" \
  | jq '{docType: .docType, wordCount: .wordCount, headings: [.outline[] | select(.type=="heading") | .text]}'
```

---

## StyleRegistry schema

```jsonc
{
  "docId": "uuid",
  "filename": "contract.docx",
  "styles": {
    "Heading1": {
      "type": "paragraph",
      "name": "heading 1",
      "fontAscii": "Calibri",
      "sizePt": 16.0,
      "bold": true,
      "color": "#1F4E79",
      "spacingBeforePt": 12.0,
      "spacingAfterPt": 4.0
    }
    // … more styles
  },
  "pageLayout": {
    "orientation": "portrait",
    "paperSize": "A4",
    "widthPt": 595.28,
    "heightPt": 841.89,
    "marginTopPt": 72.0,
    "marginBottomPt": 72.0,
    "marginLeftPt": 90.0,
    "marginRightPt": 72.0
  },
  "defaultFontAscii": "Calibri",
  "defaultFontSizePt": 11.0
}
```

---

## Configuration

All settings can be overridden via environment variables (see `.env.example`):

| Variable | Default | Description |
|----------|---------|-------------|
| `DOC_PROCESSOR_PORT` | `8080` | HTTP port |
| `MAX_FILE_SIZE` | `100MB` | Multipart upload limit |
| `MAX_FILE_SIZE_BYTES` | `104857600` | Processing validation limit |
| `UPLOAD_DIR` | `./data/uploads` | DOCX upload storage |
| `REGISTRY_DIR` | `./data/registries` | Style registry JSON files |
| `EMBED_IMAGES` | `true` | Embed images as base64 in HTML |

---

## Architecture

```
controller/
  ConvertController    ← POST /api/convert/*
  StyleController      ← GET /api/styles/*, /api/structure

service/
  ConversionService    ← orchestration, validation, file I/O
  RegistryQueryService ← read-only registry/structure queries

converter/
  DocxToHtmlConverter        ← docx4j HTML exporter + style extraction
  HtmlToDocxConverter        ← docx4j XHTMLImporter + style overlay
  DocxToMarkdownConverter    ← custom OOXML tree walker
  PdfToTextConverter         ← Apache PDFBox 3.x
  DocumentStructureExtractor ← outline builder + doc-type heuristics

storage/
  RegistryStore    ← ConcurrentHashMap L1 cache + JSON disk persistence

model/
  StyleRegistry    ← full style map + page layout + numbering defs
  DocumentStructure ← hierarchical outline + page/word/table stats
```

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 3.2, Java 17 |
| DOCX processing | [docx4j 11.4.9](https://github.com/plutext/docx4j) |
| PDF text extraction | Apache PDFBox 3.0 |
| API docs | SpringDoc OpenAPI 2.3 (Swagger UI) |
| Container | Eclipse Temurin 17 Alpine (multi-stage Docker) |
