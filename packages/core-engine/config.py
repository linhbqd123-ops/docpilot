from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from pathlib import Path

from crypto import decrypt_key


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )
    
    @field_validator(
        "openai_api_key",
        "groq_api_key",
        "openrouter_api_key",
        "together_api_key",
        "zai_api_key",
        "anthropic_api_key",
        "azure_openai_api_key",
        mode="before",
    )
    @classmethod
    def decrypt_api_keys(cls, v: str) -> str:
        """Automatically decrypt encrypted API keys on load."""
        if not v:
            return v
        
        # Check if key is encrypted (starts with "encrypted:")
        if isinstance(v, str) and v.startswith("encrypted:"):
            decrypted = decrypt_key(v)
            if decrypted is None:
                print(f"⚠️  Warning: Failed to decrypt key, using empty value")
                return ""
            return decrypted
        
        return v

    # Internal Java document service
    doc_mcp_url: str = "http://localhost:8080"

    # Chat persistence
    chat_database_path: str = str(Path(__file__).resolve().parent / "data" / "docpilot.db")
    legacy_chats_path: str = str(Path(__file__).resolve().parent / "data" / "chats.json")

    # ── Ollama ──────────────────────────────────────────────────────────────
    ollama_base_url: str = "http://localhost:11434"
    ollama_default_model: str = "llama3.2"

    # ── OpenAI ──────────────────────────────────────────────────────────────
    openai_api_key: str = ""
    openai_default_model: str = "gpt-4o"
    openai_base_url: str = "https://api.openai.com/v1"

    # ── Groq ────────────────────────────────────────────────────────────────
    groq_api_key: str = ""
    groq_base_url: str = "https://api.groq.com/openai/v1"
    groq_default_model: str = "llama-3.3-70b-versatile"

    # ── OpenRouter ──────────────────────────────────────────────────────────
    openrouter_api_key: str = ""
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    openrouter_default_model: str = "meta-llama/llama-3.3-70b-instruct"

    # ── TogetherAI ──────────────────────────────────────────────────────────
    together_api_key: str = ""
    together_base_url: str = "https://api.together.xyz/v1"
    together_default_model: str = "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo"

    # ── z.ai ────────────────────────────────────────────────────────────────
    zai_api_key: str = ""
    zai_base_url: str = "https://api.z.ai/api/v1"
    zai_default_model: str = "zai-v1"

    # ── Anthropic (own SDK — Messages API format) ───────────────────────────
    anthropic_api_key: str = ""
    anthropic_base_url: str = "https://api.anthropic.com"
    anthropic_default_model: str = "claude-3-5-sonnet-20241022"

    # ── Azure OpenAI (own SDK — different auth model) ───────────────────────
    azure_openai_api_key: str = ""
    azure_openai_endpoint: str = ""
    azure_openai_deployment: str = "gpt-4o"
    azure_openai_api_version: str = "2024-08-01-preview"

    # ── Custom (any OpenAI-compatible endpoint) ──────────────────────────────
    custom_base_url: str = ""
    custom_api_key: str = ""
    custom_default_model: str = ""

    # ── Processing limits ───────────────────────────────────────────────────
    request_timeout_seconds: int = 120


settings = Settings()
