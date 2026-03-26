"""Prompt templates for the DocPilot agent.

Each prompt enforces JSON output and is designed to be deterministic.
"""

# JSON Schemas for structured outputs
CLASSIFY_INTENT_SCHEMA = {
    "type": "object",
    "properties": {
        "intent": {"type": "string", "enum": ["rewrite", "improve", "tailor_cv", "generate", "chat"]},
        "mode": {"type": "string", "enum": ["preserve", "rebuild"]},
        "scope": {"type": "string", "enum": ["full", "selection"]},
        "description": {"type": "string"}
    },
    "required": ["intent", "mode", "scope", "description"]
}

PRESERVE_CHANGES_SCHEMA = {
    "type": "object",
    "properties": {
        "changes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "block_id": {"type": "string"},
                    "new_text": {"type": "string"}
                },
                "required": ["block_id", "new_text"]
            }
        },
        "summary": {"type": "string"}
    },
    "required": ["changes", "summary"]
}

REBUILD_BLOCKS_SCHEMA = {
    "type": "object",
    "properties": {
        "blocks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "type": {"type": "string", "enum": ["paragraph", "list_item", "table"]},
                    "style": {"type": ["string", "null"]},
                    "text": {"type": "string"},
                    "level": {"type": ["integer", "null"]},
                    "rows": {  # For table type
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "cells": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {"text": {"type": "string"}},
                                        "required": ["text"]
                                    }
                                }
                            },
                            "required": ["cells"]
                        }
                    }
                },
                "required": ["type", "text"]
            }
        },
        "summary": {"type": "string"}
    },
    "required": ["blocks", "summary"]
}

SYSTEM_PROMPT = """You are DocPilot, an AI assistant specialized in editing and improving Microsoft Word documents.
You operate by analyzing document structure and making precise, block-level changes.

You have access to the following tools:
- extract_document_structure: Extract the structured blocks from the current document
- rewrite_blocks: Rewrite specific blocks while preserving formatting
- generate_document: Generate a complete new document from scratch
- apply_changes: Apply a set of block-level changes to the document

RULES:
1. Always analyze the document structure before making changes.
2. In PRESERVE mode, rewrite text block-by-block while keeping styles and formatting intact.
3. In REBUILD mode, generate entirely new structured content.
4. Always respond in valid JSON format.
5. Never execute arbitrary code - only use the provided tools.
"""

CLASSIFY_INTENT_PROMPT = """Analyze the user's request and classify it.

User request: "{user_message}"

Document summary: {document_summary}

Respond with a JSON object:
{{
    "intent": "rewrite" | "improve" | "tailor_cv" | "generate" | "chat",
    "mode": "preserve" | "rebuild",
    "scope": "full" | "selection",
    "description": "Brief description of what to do"
}}

IMPORTANT: Respond with ONLY the JSON object, no other text."""

PRESERVE_REWRITE_PROMPT = """You are rewriting a document while PRESERVING its structure and formatting.

Document blocks:
{blocks_json}

User instruction: "{user_instruction}"

For each block that needs changes, provide the rewritten text.
Keep all style names and block types exactly as they are.

Respond with a JSON object:
{{
    "changes": [
        {{
            "block_id": "id of the block to change",
            "new_text": "the rewritten text for this block"
        }}
    ],
    "summary": "Brief summary of changes made"
}}

Rules:
- Only include blocks that actually need changes
- Preserve the original meaning unless instructed otherwise
- Keep headings as headings, lists as lists, etc.
- Do NOT add or remove blocks
- Respond with ONLY the JSON object"""

REBUILD_GENERATION_PROMPT = """You are generating a completely new document based on the user's request.

Original document context (for reference):
{blocks_json}

User instruction: "{user_instruction}"

Generate new structured content for the document.

Respond with a JSON object:
{{
    "blocks": [
        {{
            "type": "paragraph" | "list_item" | "table",
            "style": "Normal" | "Heading1" | "Heading2" | "Heading3" | "ListBullet" | "ListNumber",
            "text": "content text",
            "level": null
        }}
    ],
    "summary": "Brief summary of generated content"
}}

For tables, use this format instead:
{{
    "type": "table",
    "style": null,
    "text": "",
    "rows": [
        {{
            "cells": [
                {{"text": "cell content"}}
            ]
        }}
    ]
}}

Rules:
- Use appropriate Word styles (Heading1, Heading2, Normal, ListBullet, etc.)
- Structure the document logically with headings and sections
- Respond with ONLY the JSON object"""

IMPROVE_PROMPT = """You are improving the writing quality of a document while preserving its structure.

Document blocks:
{blocks_json}

User instruction: "{user_instruction}"

Improve the text by:
- Fixing grammar and spelling
- Improving clarity and readability
- Making language more professional
- Maintaining the original meaning and tone

Respond with a JSON object:
{{
    "changes": [
        {{
            "block_id": "id of the block to change",
            "new_text": "the improved text for this block"
        }}
    ],
    "summary": "Brief summary of improvements made"
}}

Rules:
- Only include blocks that actually need improvements
- Keep all formatting and structure intact
- Respond with ONLY the JSON object"""

TAILOR_CV_PROMPT = """You are tailoring a CV/resume document to match a specific job description.

CV document blocks:
{blocks_json}

Job description or user instruction: "{user_instruction}"

Tailor the CV by:
- Highlighting relevant experience and skills
- Adjusting keyword usage to match the job description
- Improving impact statements
- Maintaining professional formatting

Respond with a JSON object:
{{
    "changes": [
        {{
            "block_id": "id of the block to change",
            "new_text": "the tailored text for this block"
        }}
    ],
    "summary": "Brief summary of tailoring changes"
}}

Rules:
- Only include blocks that need changes
- Preserve the document structure
- Keep it truthful - don't fabricate experience
- Respond with ONLY the JSON object"""
