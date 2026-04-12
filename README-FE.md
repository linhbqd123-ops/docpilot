# DocPilot Frontend Setup

Frontend này là shell production-oriented cho Desktop App của DocPilot. Nó không dùng dữ liệu fake trong source. Toàn bộ UI chạy thật với state thật, import file thật, local persistence, chat request thật tới backend và review flow thật khi backend trả về revision HTML.

## Những gì đã có sẵn

- App shell desktop 3-panel: Library / Workspace / Assistant
- Import tài liệu thật từ máy local
- Preview và chỉnh tay trực tiếp cho `.html`, `.htm`, `.md`, `.txt`
- Nhận `.docx` và `.pdf` vào thư viện, chờ backend import endpoint để convert
- Outline tự sinh từ heading trong document HTML
- Chat panel gọi backend thật qua HTTP
- Review flow cho bản revision do backend trả về
- Local persistence cho settings, documents text-based và message threads

## Tech stack

- React 18
- TypeScript
- Vite
- Tailwind CSS
- react-resizable-panels
- marked + DOMPurify

## Chạy local

```bash
cd packages/desktop
npm install
npm run dev
```

App dev server chạy tại `http://localhost:5173`.

## Build production

```bash
cd packages/desktop
npm run build
```

Output nằm trong thư mục `packages/desktop/dist`.

## Backend contract hiện tại

Frontend này chờ backend với contract tối thiểu như sau:

### 1. Health check

`GET /health`

Response mẫu:

```json
{
	"status": "ok",
	"version": "0.1.0"
}
```

### 2. Chat / agent request

`POST /api/chat`

Request body:

```json
{
	"provider": "ollama",
	"prompt": "Rewrite the executive summary in a more formal tone.",
	"document": {
		"id": "uuid",
		"name": "report.md",
		"kind": "markdown",
		"html": "<h1>...</h1>",
		"outline": [{ "id": "intro", "title": "Introduction", "level": 1 }],
		"wordCount": 1200
	},
	"history": [
		{ "role": "user", "content": "Previous instruction" },
		{ "role": "assistant", "content": "Previous response" }
	]
}
```

JSON response:

```json
{
	"message": "I rewrote the section and prepared a revision for review.",
	"documentHtml": "<h1>Updated</h1><p>...</p>",
	"notices": ["Revision staged. Review it before applying."]
}
```

`documentHtml` là optional. Nếu có, frontend sẽ stage một revision để user review và accept/reject.

### 3. Import endpoint cho DOCX/PDF

Khi backend sẵn sàng, nên thêm:

`POST /api/documents/import`

Input: multipart file upload

Output:

```json
{
	"name": "contract.docx",
	"kind": "docx",
	"html": "<h1>Contract</h1><p>...</p>"
}
```

## File structure chính

```text
packages/desktop/
├── src/app        # context, reducer, types
├── src/lib        # parser, storage, api client, utils
├── src/components # layout, sidebar, workspace, chat
├── index.html
├── vite.config.ts
└── tailwind.config.js
```

## Ghi chú thực tế

- Frontend hiện đã usable để làm UI integration trước backend.
- `.docx` và `.pdf` không bị fake preview ở client vì đó là việc của doc processor/backend.
- Để đóng gói thành Tauri sau này, có thể giữ nguyên `src/` và bọc bằng `src-tauri` mà không phải viết lại UI.
