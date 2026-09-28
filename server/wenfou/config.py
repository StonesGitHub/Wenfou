from functools import lru_cache
from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WENFOU_", extra="ignore")
    database_url: str = "sqlite:///./wenfou-dev.db"
    environment: str = "development"
    public_url: str = "http://localhost:8000"
    session_hours: int = Field(default=168, ge=1, le=720)
    registration_enabled: bool = True
    docs_enabled: bool = False
    rate_limit_enabled: bool = True
    release: str = "0.4.0"

    @model_validator(mode="after")
    def production_checks(self):
        if self.environment not in {"development", "production", "test"}:
            raise ValueError("Invalid environment")
        if self.environment == "production":
            if not self.database_url.startswith("postgresql+psycopg://"):
                raise ValueError("Production requires PostgreSQL")
            if not self.public_url.startswith("https://"):
                raise ValueError("Production requires an HTTPS public URL")
            if not self.rate_limit_enabled:
                raise ValueError("Production rate limits cannot be disabled")
        return self


@lru_cache
def settings():
    return Settings()
