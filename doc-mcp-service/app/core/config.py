from pydantic_settings import BaseSettings
from pathlib import Path


class Settings(BaseSettings):
    app_name: str = "DOC-MCP Service"
    version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8001
    cors_origins: list[str] = ["*"]
    upload_dir: str = str(Path(__file__).parent.parent / "uploads")
    max_file_size_mb: int = 50

    model_config = {"env_prefix": "DOC_MCP_"}


settings = Settings()
