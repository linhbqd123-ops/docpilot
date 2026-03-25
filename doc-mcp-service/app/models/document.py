"""Document structure models for the DOC-MCP service.

These models define the structured JSON representation of Word documents,
enabling block-level operations while preserving formatting metadata.
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class BlockType(str, Enum):
    PARAGRAPH = "paragraph"
    LIST_ITEM = "list_item"
    TABLE = "table"
    IMAGE = "image"


class RunFormat(BaseModel):
    """Inline formatting for a text run within a block."""
    bold: Optional[bool] = None
    italic: Optional[bool] = None
    underline: Optional[bool] = None
    font_name: Optional[str] = None
    font_size: Optional[float] = None
    color: Optional[str] = None


class TextRun(BaseModel):
    """A contiguous run of text sharing the same formatting."""
    text: str
    format: Optional[RunFormat] = None


class TableCell(BaseModel):
    """A single cell within a table row."""
    text: str
    runs: list[TextRun] = Field(default_factory=list)
    style: Optional[str] = None


class TableRow(BaseModel):
    """A row within a table."""
    cells: list[TableCell] = Field(default_factory=list)


class DocumentBlock(BaseModel):
    """A single structural block of the document."""
    id: str
    type: BlockType
    style: Optional[str] = None
    text: str = ""
    runs: list[TextRun] = Field(default_factory=list)
    level: Optional[int] = None  # For list items / heading level
    rows: list[TableRow] = Field(default_factory=list)  # For tables


class DocumentStructure(BaseModel):
    """The complete structured representation of a document."""
    blocks: list[DocumentBlock] = Field(default_factory=list)
    metadata: dict = Field(default_factory=dict)


class BlockChange(BaseModel):
    """A change to be applied to a specific block."""
    block_id: str
    new_text: Optional[str] = None
    new_runs: Optional[list[TextRun]] = None
    action: str = "update"  # update | delete | insert_after


class ApplyChangesRequest(BaseModel):
    """Request to apply a set of changes to the document."""
    changes: list[BlockChange]
    document_base64: Optional[str] = None


class InsertContentRequest(BaseModel):
    """Request to insert structured content into a document."""
    blocks: list[DocumentBlock]
    position: str = "end"  # start | end | after:<block_id>
    document_base64: Optional[str] = None


class ExtractRequest(BaseModel):
    """Request to extract structure from raw document content."""
    document_base64: Optional[str] = None
    include_formatting: bool = True


class DocumentResponse(BaseModel):
    """Response containing document structure and optional binary."""
    structure: DocumentStructure
    document_base64: Optional[str] = None
    message: str = "OK"
