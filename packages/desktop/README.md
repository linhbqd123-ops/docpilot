# DocPilot Desktop

`desktop` is the React frontend for DocPilot. Despite the name, this package currently builds a web app with Vite; it is not yet a bundled native shell.

## Responsibilities

- Present the document workspace, chat panel, review flow, library, and settings UI.
- Talk to `core-engine` over HTTP.
- Render imported document HTML and revision state returned by the backend.

## Run locally

Requirements:

- Node.js 20+
- npm 10+

```powershell
cd packages/desktop
npm install
npm run dev
```

Useful commands:

```powershell
npm run build
npm run preview
npm run lint
npm run typecheck
```

## Environment

The main frontend setting is:

| Variable | Default | Meaning |
| --- | --- | --- |
| `VITE_DOCPILOT_API_BASE_URL` | `http://localhost:8000` | FastAPI backend URL |

Additional optional `VITE_DOCPILOT_*` variables can preselect provider/model defaults.

## Packaging status

This package currently outputs static web assets. The Windows app-image flow in this repo packages `doc-mcp` rather than bundling the frontend into a single native executable.