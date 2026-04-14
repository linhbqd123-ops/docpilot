from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from keys import get_public_key_pem, decrypt_base64
from providers import REGISTRY, list_providers

router = APIRouter()


class EncryptedKeyRequest(BaseModel):
    encrypted_key: str


def _env_file_path() -> Path:
    # packages/core-engine/.env
    return Path(__file__).resolve().parent.parent / ".env"


def _write_env_var(name: str, value: str) -> None:
    env_path = _env_file_path()
    lines = []
    if env_path.exists():
        lines = env_path.read_text().splitlines()

    replaced = False
    out_lines: list[str] = []

    for line in lines:
        if line.strip().startswith(name + "="):
            out_lines.append(f"{name}={value}")
            replaced = True
        else:
            out_lines.append(line)

    if not replaced:
        out_lines.append(f"{name}={value}")

    env_path.write_text("\n".join(out_lines) + ("\n" if out_lines and not out_lines[-1].endswith("\n") else ""))


def _remove_env_var(name: str) -> None:
    env_path = _env_file_path()
    if not env_path.exists():
        return
    lines = env_path.read_text().splitlines()
    out_lines = [l for l in lines if not l.strip().startswith(name + "=")]
    env_path.write_text("\n".join(out_lines) + ("\n" if out_lines and not out_lines[-1].endswith("\n") else ""))


def _provider_env_name(provider: str) -> str | None:
    cfg = REGISTRY.get(provider)
    if not cfg:
        return None
    if cfg.api_key_env:
        return cfg.api_key_env
    # Special cases
    if provider == "azure":
        return "AZURE_OPENAI_API_KEY"
    if provider == "custom":
        return "CUSTOM_API_KEY"
    return None


@router.get("/providers")
async def get_providers():
    # Extend provider info with whether a key is present in env
    items = []
    for p in list_providers():
        pid = p.get("id")
        cfg = REGISTRY.get(pid)
        has_key = False
        if cfg and cfg.api_key_env:
            has_key = bool(os.getenv(cfg.api_key_env))
        elif pid == "azure":
            has_key = bool(os.getenv("AZURE_OPENAI_API_KEY") and os.getenv("AZURE_OPENAI_ENDPOINT"))
        elif pid == "custom":
            has_key = bool(os.getenv("CUSTOM_API_KEY"))

        items.append({**p, "hasKey": has_key})

    return {"providers": items}


@router.get("/providers/public_key")
async def public_key() -> dict[str, str]:
    return {"publicKey": get_public_key_pem()}


@router.post("/providers/{provider}/key")
async def set_provider_key(provider: str, body: EncryptedKeyRequest) -> dict[str, Any]:
    if provider not in REGISTRY:
        raise HTTPException(status_code=404, detail="Unknown provider")

    env_name = _provider_env_name(provider)
    if not env_name:
        raise HTTPException(status_code=400, detail="This provider does not accept a configurable API key")

    try:
        secret = decrypt_base64(body.encrypted_key)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Decryption failed: {exc}")

    os.environ[env_name] = secret
    try:
        _write_env_var(env_name, secret)
    except Exception:
        # Best-effort persistence; environment var set for current process is primary
        pass

    return {"ok": True}


@router.delete("/providers/{provider}/key")
async def delete_provider_key(provider: str) -> dict[str, Any]:
    if provider not in REGISTRY:
        raise HTTPException(status_code=404, detail="Unknown provider")

    env_name = _provider_env_name(provider)
    if not env_name:
        raise HTTPException(status_code=400, detail="This provider does not accept a configurable API key")

    os.environ.pop(env_name, None)
    try:
        _remove_env_var(env_name)
    except Exception:
        pass

    return {"ok": True}
