"""
Key Management API Endpoints

Provides secure APIs for:
- Setting API keys with encryption
- Checking if a provider has a configured key
- Listing all providers with their key status
- Deleting keys
"""

from typing import Optional
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from pathlib import Path

from config import settings
from crypto import encrypt_key, decrypt_key, mask_key
from providers import REGISTRY

router = APIRouter(prefix="/api/keys", tags=["keys"])

KNOWN_PROVIDERS = [
    "openai",
    "groq",
    "anthropic",
    "azure",
    "openrouter",
    "together",
    "zai",
    "ollama",
    "custom",
]

ENDPOINT_ONLY_PROVIDERS = {"ollama"}


# ============================================================================
# Models
# ============================================================================

class KeySetRequest(BaseModel):
    """Request to set a provider's API key and/or endpoint."""
    provider: str  # e.g., "openai", "groq", "anthropic"
    key: str = ""
    endpoint: Optional[str] = None  # Optional for providers that need custom endpoint


class KeyResponse(BaseModel):
    """Response with masked key and provider endpoint info."""
    provider: str
    has_key: bool
    masked_key: Optional[str] = None  # Only if key exists
    endpoint: Optional[str] = None
    resolved_endpoint: Optional[str] = None


class ProviderStatus(BaseModel):
    """Status of all providers."""
    provider: str
    has_key: bool
    masked_key: Optional[str] = None
    endpoint: Optional[str] = None
    resolved_endpoint: Optional[str] = None


class KeyListResponse(BaseModel):
    providers: list[ProviderStatus]


# ============================================================================
# Helpers
# ============================================================================

def _get_env_path() -> Path:
    """Get the .env file path."""
    return Path(__file__).parent.parent / ".env"


def _get_env_var_name(provider: str, var_type: str = "key") -> str:
    """
    Get the environment variable name for a provider.
    
    Args:
        provider: Provider name (e.g., "openai", "anthropic")
        var_type: "key" or "endpoint" or "model"
        
    Returns:
        Environment variable name (e.g., "OPENAI_API_KEY")
    """
    provider_upper = provider.upper()
    
    if var_type == "key":
        if provider == "anthropic":
            return "ANTHROPIC_API_KEY"
        elif provider == "azure":
            return "AZURE_OPENAI_API_KEY"
        elif provider == "openai":
            return "OPENAI_API_KEY"
        elif provider == "groq":
            return "GROQ_API_KEY"
        elif provider == "openrouter":
            return "OPENROUTER_API_KEY"
        elif provider == "together":
            return "TOGETHER_API_KEY"
        elif provider == "zai":
            return "ZAI_API_KEY"
        elif provider == "custom":
            return "CUSTOM_API_KEY"
        else:
            return f"{provider_upper}_API_KEY"
    
    elif var_type == "endpoint":
        if provider == "azure":
            return "AZURE_OPENAI_ENDPOINT"
        elif provider == "openai":
            return "OPENAI_BASE_URL"
        elif provider == "groq":
            return "GROQ_BASE_URL"
        elif provider == "openrouter":
            return "OPENROUTER_BASE_URL"
        elif provider == "together":
            return "TOGETHER_BASE_URL"
        elif provider == "zai":
            return "ZAI_BASE_URL"
        elif provider == "anthropic":
            return "ANTHROPIC_BASE_URL"
        elif provider == "ollama":
            return "OLLAMA_BASE_URL"
        elif provider == "custom":
            return "CUSTOM_BASE_URL"
        else:
            return f"{provider_upper}_ENDPOINT"


def _supports_endpoint(provider: str) -> bool:
    return provider in KNOWN_PROVIDERS


def _requires_api_key(provider: str) -> bool:
    return provider not in ENDPOINT_ONLY_PROVIDERS


def _read_saved_key(env_vars: dict, provider: str) -> Optional[str]:
    if not _requires_api_key(provider):
        return None

    encrypted_key = env_vars.get(_get_env_var_name(provider, "key"), "")
    if not encrypted_key:
        return None

    return decrypt_key(encrypted_key)


def _read_saved_endpoint(env_vars: dict, provider: str) -> Optional[str]:
    if not _supports_endpoint(provider):
        return None

    endpoint = env_vars.get(_get_env_var_name(provider, "endpoint"), "")
    return endpoint.strip() or None


def _resolve_provider_endpoint(env_vars: dict, provider: str) -> Optional[str]:
    saved_endpoint = _read_saved_endpoint(env_vars, provider)
    if saved_endpoint:
        return saved_endpoint

    if provider == "ollama":
        return "http://localhost:11434"

    provider_config = REGISTRY.get(provider)
    if provider_config and provider_config.base_url:
        return provider_config.base_url

    return None


def _get_settings_attr_name(provider: str, var_type: str) -> Optional[str]:
    if var_type == "key":
        return {
            "openai": "openai_api_key",
            "groq": "groq_api_key",
            "anthropic": "anthropic_api_key",
            "azure": "azure_openai_api_key",
            "openrouter": "openrouter_api_key",
            "together": "together_api_key",
            "zai": "zai_api_key",
            "custom": "custom_api_key",
        }.get(provider)

    if var_type == "endpoint":
        return {
            "openai": "openai_base_url",
            "groq": "groq_base_url",
            "anthropic": "anthropic_base_url",
            "azure": "azure_openai_endpoint",
            "openrouter": "openrouter_base_url",
            "together": "together_base_url",
            "zai": "zai_base_url",
            "ollama": "ollama_base_url",
            "custom": "custom_base_url",
        }.get(provider)

    return None


def _update_runtime_setting(provider: str, var_type: str, value: str) -> None:
    attr_name = _get_settings_attr_name(provider, var_type)
    if attr_name:
        setattr(settings, attr_name, value)


def _read_env_file() -> dict:
    """Read .env file and parse variables."""
    env_path = _get_env_path()
    env_vars = {}
    
    if env_path.exists():
        with open(env_path, "r") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                
                if "=" in line:
                    key, value = line.split("=", 1)
                    env_vars[key.strip()] = value.strip()
    
    return env_vars


def _write_env_file(env_vars: dict) -> None:
    """Write environment variables to .env file."""
    env_path = _get_env_path()
    env_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(env_path, "w") as f:
        for key, value in env_vars.items():
            f.write(f"{key}={value}\n")


# ============================================================================
# Endpoints
# ============================================================================

@router.post("/set", response_model=KeyResponse)
async def set_key(request: KeySetRequest) -> KeyResponse:
    """
    Set an API key for a provider.
    
    The key will be encrypted before saving to .env
    """
    if not request.provider or not request.provider.strip():
        raise HTTPException(status_code=400, detail="Provider must be specified")

    provider = request.provider.strip().lower()
    if provider not in KNOWN_PROVIDERS:
        raise HTTPException(status_code=400, detail=f"Unsupported provider: {provider}")

    key_value = request.key.strip()
    endpoint_value = request.endpoint.strip() if request.endpoint else ""

    if provider == "ollama" and not endpoint_value and key_value:
        # Backward compatibility for older clients that sent the URL in the key field.
        endpoint_value = key_value
        key_value = ""

    if not key_value and not endpoint_value:
        raise HTTPException(status_code=400, detail="Provide an API key or provider URL")

    if endpoint_value and not _supports_endpoint(provider):
        raise HTTPException(status_code=400, detail=f"{provider} does not support a configurable provider URL")
    
    try:
        # Read current .env
        env_vars = _read_env_file()
        
        # Determine environment variable names
        key_var_name = _get_env_var_name(provider, "key")
        endpoint_var_name = _get_env_var_name(provider, "endpoint")

        if key_value:
            env_vars[key_var_name] = encrypt_key(key_value)
            _update_runtime_setting(provider, "key", key_value)

        if endpoint_value:
            env_vars[endpoint_var_name] = endpoint_value
            _update_runtime_setting(provider, "endpoint", endpoint_value)
        
        # Write back to .env
        _write_env_file(env_vars)

        saved_key = _read_saved_key(env_vars, provider)
        saved_endpoint = _read_saved_endpoint(env_vars, provider)
        
        return KeyResponse(
            provider=provider,
            has_key=bool(saved_key),
            masked_key=mask_key(saved_key) if saved_key else None,
            endpoint=saved_endpoint,
            resolved_endpoint=_resolve_provider_endpoint(env_vars, provider),
        )
    
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to set key: {str(e)}"
        )




@router.get("/list", response_model=KeyListResponse)
async def list_keys() -> KeyListResponse:
    """List all providers and their configured key status."""
    env_vars = _read_env_file()
    providers = []

    for provider in KNOWN_PROVIDERS:
        decrypted_key = _read_saved_key(env_vars, provider)
        providers.append(ProviderStatus(
            provider=provider,
            has_key=bool(decrypted_key),
            masked_key=mask_key(decrypted_key) if decrypted_key else None,
            endpoint=_read_saved_endpoint(env_vars, provider),
            resolved_endpoint=_resolve_provider_endpoint(env_vars, provider),
        ))

    return KeyListResponse(providers=providers)


@router.delete("/delete/{provider}")
async def delete_key(provider: str) -> dict:
    """Delete only the API key for a provider."""
    try:
        provider = provider.strip().lower()
        if provider not in KNOWN_PROVIDERS:
            raise HTTPException(status_code=404, detail="Unknown provider")

        env_vars = _read_env_file()
        
        key_var_name = _get_env_var_name(provider, "key")
        
        env_vars.pop(key_var_name, None)
        _update_runtime_setting(provider, "key", "")
        
        _write_env_file(env_vars)
        
        return {
            "success": True,
            "message": f"Key for {provider} deleted"
        }
    
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to delete key: {str(e)}"
        )


@router.delete("/delete/{provider}/endpoint")
async def delete_endpoint(provider: str) -> dict:
    """Delete only the provider URL override for a provider."""
    try:
        provider = provider.strip().lower()
        if provider not in KNOWN_PROVIDERS:
            raise HTTPException(status_code=404, detail="Unknown provider")

        env_vars = _read_env_file()
        endpoint_var_name = _get_env_var_name(provider, "endpoint")

        env_vars.pop(endpoint_var_name, None)
        _update_runtime_setting(provider, "endpoint", "")

        _write_env_file(env_vars)

        return {
            "success": True,
            "message": f"Endpoint override for {provider} deleted"
        }

    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to delete endpoint: {str(e)}"
        )
