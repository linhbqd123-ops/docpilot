"""Prompt templates for the DocPilot agent.

Each prompt enforces JSON output and is designed to be deterministic.

JSON Schema Structure:
- Schemas include "name" field for API identification (Groq/OpenAI requirement)
- Supports both modes:
  * json_schema: Structured outputs with schema validation (limited model support)
  * json_object: Simple JSON without schema (broader model support)
  
Current setup:
- Groq llama-3.3-70b: json_object mode (no schema validation)
- Future upgrade: Can use json_schema with models like openai/gpt-oss-20b
"""

# JSON Schemas for structured outputs
CLASSIFY_INTENT_SCHEMA = {
    "name": "classify_intent",
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
    "name": "preserve_changes",
    "type": "object",
    "properties": {
        "changes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "block_id": {"type": "string"},
                    "new_text": {"type": "string"},
                    "new_style": {
                        "type": ["string", "null"],
                        "enum": [
                            "Heading1",
                            "Heading2",
                            "Heading3",
                            "Normal",
                            "List Bullet",
                            "List Number",
                            "ListBullet",
                            "ListNumber",
                            None,
                        ],
                    }
                },
                "required": ["block_id", "new_text"]
            }
        },
        "summary": {"type": "string"}
    },
    "required": ["changes", "summary"]
}

REBUILD_BLOCKS_SCHEMA = {
    "name": "rebuild_blocks",
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

AVAILABLE DOCUMENT TOOLS:
- apply_changes: Modify specific blocks while preserving formatting
- insert_structured_content: Add new blocks (paragraphs, lists, tables, headings)
- clear_document: Remove all content
- extract_structure: Get document structure analysis

AVAILABLE WORD STYLES (use these names exactly):
Headings: "Heading1", "Heading2", "Heading3"
Text: "Normal", "List Bullet", "List Number"
Tables: Tables will be created with available styles (Table Grid, Table Normal, or default)

FORMATTING CAPABILITIES:
- Bold, italic, underline text
- Create bulleted and numbered lists
- Create tables with multiple rows and columns
- Apply professional styles to headings
- Control text formatting and spacing

RULES:
1. Always analyze the document structure before making changes.
2. In PRESERVE mode, rewrite text block-by-block while keeping styles and formatting intact.
3. In REBUILD mode, generate entirely new structured content.
4. Always respond in valid JSON format.
5. Only use documented styles and tools - never use unknown functions.
6. When creating tables, focus on clarity and professional presentation.
7. For CV/resume documents, use tables for skills and qualifications to maximize readability.
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
- Create tables for complex data (skills, qualifications, comparisons)
- Respond with ONLY the JSON object"""

GENERATE_CV_PROMPT = """You are creating a professional CV/resume from scratch based on user specifications.

Original document context (for reference):
{blocks_json}

User instruction: "{user_instruction}"

CREATE A PROFESSIONAL CV WITH THE FOLLOWING STRUCTURE:

REQUIRED SECTIONS:
1. Professional Summary: 2-3 concise lines highlighting key strengths
2. Core Skills: Presented in a professional table format by category
3. Professional Experience: Each role with company, position, duration, and 3-5 key achievements
4. Education: Degree, institution, and graduation year
5. Optional: Certifications, Languages, Projects

FORMAT REQUIREMENTS:
- Use Heading1 style for the main title/name
- Use Heading2 style for all section headers (Summary, Skills, Experience, Education)
- Use ListBullet style for all experience achievements and details
- Create a 2-column table for Skills with headers "Category | Skills"
- Make the CV visually scannable and ATS-friendly
- Keep formatting consistent throughout

TABLE FORMAT FOR SKILLS:
Each row in skills table should have:
- Column 1: Skill category (Languages, Frameworks, Tools, Soft Skills, etc.)
- Column 2: Comma-separated list of specific skills

EXPERIENCE DETAIL FORMAT:
For each position, include:
- Company Name | Job Title | Duration (dates)
- Then 3-5 bullet points with key achievements and responsibilities

OUTPUT JSON STRUCTURE:
{{
    "blocks": [
        {{"type": "paragraph", "style": "Heading1", "text": "FULL NAME"}},
        {{"type": "paragraph", "style": "Heading2", "text": "Professional Summary"}},
        {{"type": "paragraph", "style": "Normal", "text": "Summary text here"}},
        {{"type": "paragraph", "style": "Heading2", "text": "Core Skills"}},
        {{
            "type": "table",
            "rows": [
                {{"cells": [{{"text": "Category"}}, {{"text": "Skills"}}]}},
                {{"cells": [{{"text": "Languages"}}, {{"text": "JavaScript, Python, Go"}}]}}
            ]
        }},
        {{"type": "paragraph", "style": "Heading2", "text": "Professional Experience"}},
        {{"type": "list_item", "style": "ListBullet", "text": "Company | Position | 2020-2024"}},
        {{"type": "list_item", "style": "ListBullet", "text": "Achievement 1"}},
        {{"type": "list_item", "style": "ListBullet", "text": "Achievement 2"}},
        {{"type": "paragraph", "style": "Heading2", "text": "Education"}},
        {{"type": "list_item", "style": "ListBullet", "text": "Degree | University | 2020"}}
    ],
    "summary": "Professional CV created with skills table and detailed experience section"
}}

CRITICAL REQUIREMENTS:
- ALWAYS include a skills table - this is non-negotiable
- Use only the specified styles (don't make up new style names)
- Structure must be clear and professional
- All spacing and special characters must be correct
- Do NOT fabricate information - use only what's provided
- Respond with ONLY the JSON object, no other text"""

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
            "new_text": "the improved text for this block",
            "new_style": "optional style: Heading1|Heading2|Heading3|Normal|List Bullet|List Number|null"
        }}
    ],
    "summary": "Brief summary of improvements made"
}}

Rules:
- Only include blocks that actually need improvements
- Keep all formatting and structure intact
- If user asks to improve formatting/style, set new_style on relevant blocks
- Only use these style names: Heading1, Heading2, Heading3, Normal, List Bullet, List Number
- Respond with ONLY the JSON object"""

TAILOR_CV_PROMPT = """You are an expert CV/resume optimizer. Your task is to enhance and tailor a CV/resume to be more impactful and professional.

CV document blocks:
{blocks_json}

Job description or user instruction: "{user_instruction}"

TAILOR THE CV BY:
1. Highlight relevant experience and skills that match the job description
2. Adjust keyword usage to align with job requirements (ATS optimization)
3. Improve impact statements with strong action verbs
4. Ensure clear structure: Summary → Experience → Skills → Education
5. Make formatting professional and readable

FORMATTING IMPROVEMENTS TO MAKE:
- If Skills section is just text, convert to a well-formatted skills table with categories
- Group related skills in a table format for better readability
- Use Heading2 style for section headers
- Use List Bullet for experience details
- Ensure consistent formatting throughout
- Make summary concise and impactful (2-3 sentences)

OUTPUT STRUCTURE:
If significantly restructuring, use rebuild mode with:
- Heading1: Full name/title (optional)
- Heading2: Professional Summary
- Heading2: Core Skills (followed by skills table if many skills)
- Heading2: Professional Experience  
- List items: Job details
- Heading2: Education
- List items: Education details

Respond with a JSON object:
{{
    "changes": [
        {{
            "block_id": "id of the block to change",
            "new_text": "the optimized text for this block",
            "new_style": "optional style: Heading1|Heading2|Heading3|Normal|List Bullet|List Number|null"
        }}
    ],
    "summary": "Brief summary of improvements made"
}}

IMPORTANT RULES:
- Only include blocks that need meaningful changes
- ALWAYS improve formatting and structure, not just content
- Preserve truthfulness - don't fabricate experience
- Add tables for skills when there are 5+ skill items
- Make the CV stand out while maintaining professionalism
- Respond with ONLY the JSON object"""
