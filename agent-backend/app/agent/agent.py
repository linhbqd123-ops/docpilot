"""DocPilot Agent - Core agent loop implementation.

Implements a lightweight, tool-based agent that:
1. Receives user intent + document context
2. Classifies the intent
3. Decides which tools to call
4. Executes tools in sequence
5. Returns the final result
"""

from __future__ import annotations

import json
import logging
from enum import Enum
from typing import Optional

from app.prompts.templates import (
    SYSTEM_PROMPT,
    CLASSIFY_INTENT_PROMPT,
    CLASSIFY_INTENT_SCHEMA,
    PRESERVE_CHANGES_SCHEMA,
    PRESERVE_REWRITE_PROMPT,
    REBUILD_BLOCKS_SCHEMA,
    REBUILD_GENERATION_PROMPT,
    GENERATE_CV_PROMPT,
    IMPROVE_PROMPT,
    TAILOR_CV_PROMPT,
)
from app.tools.document_tools import DocMCPClient

from llm_core.models.llm_models import LLMRequest
from llm_core.core.router import LLMRouter

logger = logging.getLogger(__name__)


def _short_text(text: Optional[str], limit: int = 1600) -> str:
    if not text:
        return ""
    normalized = " ".join(text.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "...(truncated)"


class AgentMode(str, Enum):
    AUTO = "auto"
    PRESERVE = "preserve"
    REBUILD = "rebuild"


class AgentIntent(str, Enum):
    REWRITE = "rewrite"
    IMPROVE = "improve"
    TAILOR_CV = "tailor_cv"
    GENERATE = "generate"
    CHAT = "chat"


class AgentResult:
    """Result of an agent execution."""

    def __init__(
        self,
        success: bool,
        message: str,
        document_base64: Optional[str] = None,
        changes_summary: Optional[str] = None,
        structure: Optional[dict] = None,
    ):
        self.success = success
        self.message = message
        self.document_base64 = document_base64
        self.changes_summary = changes_summary
        self.structure = structure

    def to_dict(self) -> dict:
        return {
            "success": self.success,
            "message": self.message,
            "document_base64": self.document_base64,
            "changes_summary": self.changes_summary,
            "structure": self.structure,
        }


class DocPilotAgent:
    """The main DocPilot agent that orchestrates document operations."""

    def __init__(self, llm_router: LLMRouter, mcp_client: DocMCPClient):
        self.llm_router = llm_router
        self.mcp_client = mcp_client

    async def run(
        self,
        user_message: str,
        document_base64: Optional[str] = None,
        mode: Optional[str] = None,
        action: Optional[str] = None,
        provider_name: Optional[str] = None,
    ) -> AgentResult:
        """Main agent loop (auto mode).

        The agent automatically determines the best approach based on user intent:
        - Action-based: Uses provided action (rewrite, improve, tailor_cv, generate)
        - Intent-based: Uses LLM to classify intent if no action specified
        - Always follows user prompt - mode is just implementation detail

        Steps:
        1. Extract document structure
        2. Classify intent (or use explicit action)
        3. Execute the appropriate tool chain
        4. Return result with updated document
        """
        try:
            logger.info(
                "Agent flow start action=%s mode=%s provider=%s user_message=%s",
                action,
                mode,
                provider_name,
                _short_text(user_message, 500),
            )
            # Step 1: Extract document structure
            structure = await self.mcp_client.extract_structure(
                document_base64=document_base64
            )
            blocks = structure.get("blocks", [])
            blocks_json = json.dumps(blocks, ensure_ascii=False, indent=2)
            logger.debug("Extracted structure blocks=%d", len(blocks))

            # Step 2: Determine intent and mode
            intent, detected_mode = await self._classify_intent(
                user_message, blocks, action, mode
            )

            logger.info("Agent intent=%s mode=%s", intent, detected_mode)

            # Step 3: Execute based on intent
            logger.info("Starting agent execution")
            if intent == AgentIntent.CHAT:
                return await self._handle_chat(user_message, blocks_json, provider_name)

            if detected_mode == AgentMode.REBUILD or intent == AgentIntent.GENERATE:
                return await self._handle_rebuild(
                    user_message, blocks_json, document_base64, provider_name
                )

            # PRESERVE mode operations
            if intent == AgentIntent.IMPROVE:
                return await self._handle_improve(
                    user_message, blocks_json, document_base64, provider_name
                )
            elif intent == AgentIntent.TAILOR_CV:
                return await self._handle_tailor_cv(
                    user_message, blocks_json, document_base64, provider_name
                )
            else:
                return await self._handle_rewrite(
                    user_message, blocks_json, document_base64, provider_name
                )

        except Exception as e:
            logger.exception("Agent execution failed")
            return AgentResult(
                success=False,
                message=f"Agent error: {str(e)}",
            )

    async def _classify_intent(
        self, user_message: str, blocks: list[dict], explicit_action: Optional[str], explicit_mode: Optional[str]
    ) -> tuple[AgentIntent, AgentMode]:
        """Classify the user's intent and determine the mode."""

        # Use explicit mode if provided (unless auto)
        if explicit_mode and explicit_mode != "auto":
            try:
                user_mode = AgentMode(explicit_mode)
            except ValueError:
                user_mode = AgentMode.PRESERVE  # fallback
        else:
            user_mode = None

        # Use explicit action if provided
        if explicit_action:
            action_map = {
                "rewrite": (AgentIntent.REWRITE, AgentMode.PRESERVE),
                "improve": (AgentIntent.IMPROVE, AgentMode.PRESERVE),
                "tailor_cv": (AgentIntent.TAILOR_CV, AgentMode.PRESERVE),
                "generate": (AgentIntent.GENERATE, AgentMode.REBUILD),
                "rebuild": (AgentIntent.REWRITE, AgentMode.REBUILD),
            }
            intent, default_mode = action_map.get(
                explicit_action, (AgentIntent.REWRITE, AgentMode.PRESERVE)
            )
            # Override mode if user specified
            mode = user_mode or default_mode
            return intent, mode

        # Use LLM to classify if no explicit action
        doc_summary = f"{len(blocks)} blocks" if blocks else "empty document"

        prompt = CLASSIFY_INTENT_PROMPT.format(
            user_message=user_message,
            document_summary=doc_summary,
        )

        logger.info("Classifying user intent with LLM")
        logger.debug("Intent prompt=%s", _short_text(prompt))
        try:
            result = await self.llm_router.complete_json(
                LLMRequest(
                    messages=[{"role": "user", "content": prompt}],
                    system_prompt=SYSTEM_PROMPT,
                ),
                json_schema=CLASSIFY_INTENT_SCHEMA
            )

            intent = AgentIntent(result.get("intent", "rewrite"))
            detected_mode = AgentMode(result.get("mode", "preserve"))
            logger.debug("Intent classify response=%s", _short_text(json.dumps(result, ensure_ascii=False)))
            # Override mode if user specified
            mode = user_mode or detected_mode
            return intent, mode
        except Exception as e:
            logger.warning(f"Intent classification failed, defaulting: {e}")
            return AgentIntent.REWRITE, user_mode or AgentMode.PRESERVE

    async def _handle_chat(
        self, user_message: str, blocks_json: str, provider_name: Optional[str]
    ) -> AgentResult:
        """Handle a simple chat message (no document changes)."""
        logger.info("Handling chat request with LLM")
        logger.debug("Chat user_message=%s", _short_text(user_message))
        response = await self.llm_router.complete(
            LLMRequest(
                messages=[{"role": "user", "content": user_message}],
                system_prompt=SYSTEM_PROMPT + f"\n\nDocument context:\n{blocks_json[:2000]}",
                provider_name=provider_name,
            )
        )
        return AgentResult(
            success=True,
            message=response.content,
        )

    async def _handle_rewrite(
        self,
        user_message: str,
        blocks_json: str,
        document_base64: Optional[str],
        provider_name: Optional[str],
    ) -> AgentResult:
        """Handle a PRESERVE mode rewrite."""
        logger.info("Handling rewrite request with LLM")
        prompt = PRESERVE_REWRITE_PROMPT.format(
            blocks_json=blocks_json,
            user_instruction=user_message,
        )
        logger.debug("Rewrite prompt=%s", _short_text(prompt))

        result = await self.llm_router.complete_json(
            LLMRequest(
                messages=[{"role": "user", "content": prompt}],
                system_prompt=SYSTEM_PROMPT,
                provider_name=provider_name,
            ),
            json_schema=PRESERVE_CHANGES_SCHEMA
        )

        changes = result.get("changes", [])
        summary = result.get("summary", "Changes applied")
        logger.debug(
            "Rewrite response summary=%s changes_count=%d",
            _short_text(summary, 300),
            len(changes),
        )

        if not changes:
            return AgentResult(
                success=True,
                message="No changes needed.",
                document_base64=document_base64,
            )

        # Apply changes via DOC-MCP
        apply_result = await self.mcp_client.apply_changes(
            changes=[
                {
                    "block_id": c["block_id"],
                    "new_text": c["new_text"],
                    "new_style": c.get("new_style"),
                    "action": "update",
                }
                for c in changes
            ],
            document_base64=document_base64,
        )

        return AgentResult(
            success=True,
            message=summary,
            document_base64=apply_result.get("document_base64"),
            changes_summary=summary,
            structure=apply_result.get("structure"),
        )

    async def _handle_improve(
        self,
        user_message: str,
        blocks_json: str,
        document_base64: Optional[str],
        provider_name: Optional[str],
    ) -> AgentResult:
        """Handle document improvement."""
        logger.info("Handling improve request with LLM")
        prompt = IMPROVE_PROMPT.format(
            blocks_json=blocks_json,
            user_instruction=user_message,
        )
        logger.debug("Improve prompt=%s", _short_text(prompt))

        result = await self.llm_router.complete_json(
            LLMRequest(
                messages=[{"role": "user", "content": prompt}],
                system_prompt=SYSTEM_PROMPT,
                provider_name=provider_name,
            ),
            json_schema=PRESERVE_CHANGES_SCHEMA
        )

        changes = result.get("changes", [])
        summary = result.get("summary", "Improvements applied")
        logger.debug(
            "Improve response summary=%s changes_count=%d",
            _short_text(summary, 300),
            len(changes),
        )

        if not changes:
            return AgentResult(success=True, message="No improvements needed.", document_base64=document_base64)

        apply_result = await self.mcp_client.apply_changes(
            changes=[
                {
                    "block_id": c["block_id"],
                    "new_text": c["new_text"],
                    "new_style": c.get("new_style"),
                    "action": "update",
                }
                for c in changes
            ],
            document_base64=document_base64,
        )

        return AgentResult(
            success=True,
            message=summary,
            document_base64=apply_result.get("document_base64"),
            changes_summary=summary,
            structure=apply_result.get("structure"),
        )

    async def _handle_tailor_cv(
        self,
        user_message: str,
        blocks_json: str,
        document_base64: Optional[str],
        provider_name: Optional[str],
    ) -> AgentResult:
        """Handle CV tailoring."""
        logger.info("Handling CV tailoring request with LLM")
        prompt = TAILOR_CV_PROMPT.format(
            blocks_json=blocks_json,
            user_instruction=user_message,
        )
        logger.debug("Tailor CV prompt=%s", _short_text(prompt))

        result = await self.llm_router.complete_json(
            LLMRequest(
                messages=[{"role": "user", "content": prompt}],
                system_prompt=SYSTEM_PROMPT,
                provider_name=provider_name,
                temperature=0.2,  # Lower temperature for consistent CV formatting
            ),
            json_schema=PRESERVE_CHANGES_SCHEMA
        )

        changes = result.get("changes", [])
        summary = result.get("summary", "CV tailored")
        logger.debug(
            "Tailor CV response summary=%s changes_count=%d",
            _short_text(summary, 300),
            len(changes),
        )

        if not changes:
            return AgentResult(success=True, message="No tailoring changes needed.", document_base64=document_base64)

        apply_result = await self.mcp_client.apply_changes(
            changes=[
                {
                    "block_id": c["block_id"],
                    "new_text": c["new_text"],
                    "new_style": c.get("new_style"),
                    "action": "update",
                }
                for c in changes
            ],
            document_base64=document_base64,
        )

        return AgentResult(
            success=True,
            message=summary,
            document_base64=apply_result.get("document_base64"),
            changes_summary=summary,
            structure=apply_result.get("structure"),
        )

    async def _handle_rebuild(
        self,
        user_message: str,
        blocks_json: str,
        document_base64: Optional[str],
        provider_name: Optional[str],
    ) -> AgentResult:
        """Handle REBUILD mode - generate new document."""
        logger.info("Handling rebuild request with LLM")
        
        # Detect if this is a CV generation request
        cv_keywords = ["cv", "resume", "curriculum", "vitae", "job", "position", "experience", "skills", "profile"]
        is_cv_request = any(keyword in user_message.lower() for keyword in cv_keywords)
        
        # Use CV-specific prompt for CV generation requests
        if is_cv_request:
            logger.info("Detected CV generation request, using specialized CV prompt")
            prompt = GENERATE_CV_PROMPT.format(
                blocks_json=blocks_json,
                user_instruction=user_message,
            )
        else:
            prompt = REBUILD_GENERATION_PROMPT.format(
                blocks_json=blocks_json,
                user_instruction=user_message,
            )
        logger.debug("Rebuild prompt=%s", _short_text(prompt))

        # Lower temperature for CV generation to ensure consistent formatting
        # Use provider_name from request, but we'll handle temp at LLMRequest level
        result = await self.llm_router.complete_json(
            LLMRequest(
                messages=[{"role": "user", "content": prompt}],
                system_prompt=SYSTEM_PROMPT,
                provider_name=provider_name,
                temperature=0.2 if is_cv_request else 0.3,  # Lower temp for CV to reduce hallucination
            ),
            json_schema=REBUILD_BLOCKS_SCHEMA
        )

        new_blocks = result.get("blocks", [])
        summary = result.get("summary", "Document generated")
        logger.debug(
            "Rebuild response summary=%s blocks_count=%d",
            _short_text(summary, 300),
            len(new_blocks),
        )

        if not new_blocks:
            return AgentResult(success=False, message="Failed to generate document content.")

        # Clear the document first
        clear_result = await self.mcp_client.clear_document(document_base64=document_base64)
        cleared_base64 = clear_result.get("document_base64")

        # Assign IDs and proper types to blocks
        for i, block in enumerate(new_blocks):
            block["id"] = f"gen_{i}"
            if "type" not in block:
                block["type"] = "paragraph"

        # Insert new content
        insert_result = await self.mcp_client.insert_structured_content(
            blocks=new_blocks,
            position="end",
            document_base64=cleared_base64,
        )

        return AgentResult(
            success=True,
            message=summary,
            document_base64=insert_result.get("document_base64"),
            changes_summary=summary,
            structure=insert_result.get("structure"),
        )
