from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path


def setup_mcp_logging() -> Path:
    """Configure DOC-MCP logging to console and file."""
    root_dir = Path(__file__).resolve().parents[3]
    logs_dir = root_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    mcp_log = logs_dir / "doc-mcp.log"

    formatter = logging.Formatter(
        "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)

    if getattr(root_logger, "_docpilot_mcp_logging_ready", False):
        return logs_dir

    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(formatter)

    file_handler = RotatingFileHandler(
        mcp_log,
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(formatter)

    root_logger.addHandler(console_handler)
    root_logger.addHandler(file_handler)
    setattr(root_logger, "_docpilot_mcp_logging_ready", True)
    logging.getLogger(__name__).info("DOC-MCP logging initialized at %s", mcp_log)
    return logs_dir
