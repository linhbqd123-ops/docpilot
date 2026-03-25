from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "DocPilot Agent Backend"
    version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8000
    cors_origins: list[str] = ["*"]
    doc_mcp_url: str = "http://localhost:8001/api/v1"
    llm_config_path: str = ""
    max_agent_steps: int = 10

    model_config = {"env_prefix": "AGENT_"}


settings = Settings()
