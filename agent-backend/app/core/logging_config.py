from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path


class _PrefixFilter(logging.Filter):
    def __init__(self, prefix: str):
        super().__init__()
        self.prefix = prefix

    def filter(self, record: logging.LogRecord) -> bool:
        return record.name.startswith(self.prefix)


def setup_backend_logging() -> Path:
    """Configure console + per-module file logging for backend process."""
    root_dir = Path(__file__).resolve().parents[3]
    logs_dir = root_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    backend_log = logs_dir / "agent-backend.log"
    llm_log = logs_dir / "llm-layer.log"
    frontend_log = logs_dir / "frontend.log"

    formatter = logging.Formatter(
        "%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    root_logger = logging.getLogger()
    root_logger.setLevel(logging.DEBUG)

    # Avoid duplicated handlers on reload.
    if getattr(root_logger, "_docpilot_logging_ready", False):
        return logs_dir

    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(formatter)

    backend_handler = RotatingFileHandler(
        backend_log,
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    backend_handler.setLevel(logging.DEBUG)
    backend_handler.setFormatter(formatter)

    llm_handler = RotatingFileHandler(
        llm_log,
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    llm_handler.setLevel(logging.DEBUG)
    llm_handler.setFormatter(formatter)
    llm_handler.addFilter(_PrefixFilter("llm_core"))

    frontend_handler = RotatingFileHandler(
        frontend_log,
        maxBytes=10 * 1024 * 1024,
        backupCount=5,
        encoding="utf-8",
    )
    frontend_handler.setLevel(logging.DEBUG)
    frontend_handler.setFormatter(formatter)
    frontend_handler.addFilter(_PrefixFilter("frontend_trace"))

    root_logger.addHandler(console_handler)
    root_logger.addHandler(backend_handler)
    root_logger.addHandler(llm_handler)
    root_logger.addHandler(frontend_handler)

    setattr(root_logger, "_docpilot_logging_ready", True)
    logging.getLogger(__name__).info("Logging initialized. Log directory: %s", logs_dir)
    return logs_dir
