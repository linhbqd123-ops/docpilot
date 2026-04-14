"""
Key Management API Endpoints

Provides secure APIs for:
- Setting API keys with encryption
- Checking if a provider has a configured key
- Listing all providers with their key status
- Deleting keys
"""

from typing import Optional
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from pathlib import Path

from config import Settings
from crypto import encrypt_key, decrypt_key, mask_key

router = APIRouter(prefix="/api/keys", tags=["keys"])


# ============================================================================
# Models
# ============================================================================

class KeySetRequest(BaseModel):
    """Request to set a provider's API key."""
    provider: str  # e.g., "openai", "groq", "anthropic"
    key: str
    endpoint: Optional[str] = None  # Optional for providers that need custom endpoint


class KeyResponse(BaseModel):
    """Response with masked key info."""
    provider: str
    has_key: bool
    masked_key: Optional[str] = None  # Only if key exists


class ProviderStatus(BaseModel):
    """Status of all providers."""
    provider: str
    has_key: bool
    masked_key: Optional[str] = None


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
        elif provider == "ollama":
            return "OLLAMA_BASE_URL"
        else:
            return f"{provider_upper}_API_KEY"
    
    elif var_type == "endpoint":
        if provider == "azure":
            return "AZURE_OPENAI_ENDPOINT"
        elif provider == "zai":
            return "ZAI_BASE_URL"
        elif provider == "ollama":
            return "OLLAMA_BASE_URL"
        else:
            return f"{provider_upper}_ENDPOINT"


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


def _get_current_key(settings: Settings, provider: str) -> Optional[str]:
    """Get the current key for a provider from settings."""
    provider_lower = provider.lower()
    
    if provider_lower == "openai":
        return settings.openai_api_key if settings.openai_api_key else None
    elif provider_lower == "groq":
        return settings.groq_api_key if settings.groq_api_key else None
    elif provider_lower == "anthropic":
        return settings.anthropic_api_key if settings.anthropic_api_key else None
    elif provider_lower == "azure":
        return settings.azure_openai_api_key if settings.azure_openai_api_key else None
    elif provider_lower == "openrouter":
        return settings.openrouter_api_key if settings.openrouter_api_key else None
    elif provider_lower == "together":
        return settings.together_api_key if settings.together_api_key else None
    elif provider_lower == "zai":
        return settings.zai_api_key if settings.zai_api_key else None
    
    return None


# ============================================================================
# Endpoints
# ============================================================================

@router.post("/set")
async def set_key(request: KeySetRequest) -> KeyResponse:
    """
    Set an API key for a provider.
    
    The key will be encrypted before saving to .env
    """
    if not request.key or not request.key.strip():
        raise HTTPException(status_code=400, detail="Key cannot be empty")
    
    if not request.provider or not request.provider.strip():
        raise HTTPException(status_code=400, detail="Provider must be specified")
    
    try:
        # Encrypt the key
        encrypted_key = encrypt_key(request.key.strip())
        
        # Read current .env
        env_vars = _read_env_file()
        
        # Determine environment variable names
        key_var_name = _get_env_var_name(request.provider, "key")
        endpoint_var_name = _get_env_var_name(request.provider, "endpoint")
        
        # Update .env with encrypted key
        env_vars[key_var_name] = encrypted_key
        
        # If endpoint provided, update that too
        if request.endpoint and request.endpoint.strip():
            env_vars[endpoint_var_name] = request.endpoint.strip()
        
        # Write back to .env
        _write_env_file(env_vars)
        
        # Return masked key
        return KeyResponse(
            provider=request.provider,
            has_key=True,
            masked_key=mask_key(request.key),
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
    known_providers = [
        "openai",
        "groq",
        "anthropic",
        "azure",
        "openrouter",
        "together",
        "zai",
        "ollama",
    ]

    for provider in known_providers:
        key_var_name = _get_env_var_name(provider, "key")
        encrypted_key = env_vars.get(key_var_name)

        if encrypted_key:
            decrypted_key = decrypt_key(encrypted_key)
            providers.append(ProviderStatus(
                provider=provider,
                has_key=True,
                masked_key=mask_key(decrypted_key) if decrypted_key else None,
            ))
        else:
            providers.append(ProviderStatus(
                provider=provider,
                has_key=False,
            ))

    return KeyListResponse(providers=providers)


@router.delete("/delete/{provider}")
async def delete_key(provider: str) -> dict:
    """Delete an API key for a provider."""
    try:
        env_vars = _read_env_file()
        
        key_var_name = _get_env_var_name(provider, "key")
        endpoint_var_name = _get_env_var_name(provider, "endpoint")
        
        # Remove key and endpoint if they exist
        env_vars.pop(key_var_name, None)
        env_vars.pop(endpoint_var_name, None)
        
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
