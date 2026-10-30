"""Server configuration, read from environment variables (see .env.example)."""
import os
import secrets
import warnings
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Settings:
    environment: str = os.getenv("ACCISAFE_ENV", "development")
    database_url: str = os.getenv("ACCISAFE_DATABASE_URL", "sqlite:///./data/accisafe_rdc.db")
    secret_key: str = os.getenv("ACCISAFE_SECRET_KEY", "")
    access_token_minutes: int = int(os.getenv("ACCISAFE_ACCESS_TOKEN_MINUTES", "15"))
    refresh_token_days: int = int(os.getenv("ACCISAFE_REFRESH_TOKEN_DAYS", "14"))
    attachment_dir: str = os.getenv("ACCISAFE_ATTACHMENT_DIR", "./data/attachments")
    max_attachment_bytes: int = int(os.getenv("ACCISAFE_MAX_ATTACHMENT_BYTES", str(5 * 1024 * 1024)))
    # "log" records SMS as SIMULATED without sending; "http" uses the configured provider.
    sms_provider: str = os.getenv("ACCISAFE_SMS_PROVIDER", "log")
    sms_http_url: str = os.getenv("ACCISAFE_SMS_HTTP_URL", "")
    sms_http_token: str = os.getenv("ACCISAFE_SMS_HTTP_TOKEN", "")
    sms_sender_id: str = os.getenv("ACCISAFE_SMS_SENDER_ID", "AcciSafe")
    escalation_interval_seconds: int = int(os.getenv("ACCISAFE_ESCALATION_INTERVAL_SECONDS", "60"))
    login_attempts_per_minute: int = int(os.getenv("ACCISAFE_LOGIN_ATTEMPTS_PER_MINUTE", "5"))
    cors_origins: list[str] = field(default_factory=lambda: [o for o in os.getenv("ACCISAFE_CORS_ORIGINS", "").split(",") if o])

    @property
    def is_production(self) -> bool:
        return self.environment == "production"


def _normalise_db_url(url: str) -> str:
    """Hosting providers hand out postgres:// URLs; SQLAlchemy needs the psycopg 3 driver name."""
    for prefix in ("postgres://", "postgresql://"):
        if url.startswith(prefix):
            return "postgresql+psycopg://" + url[len(prefix):]
    return url


def _load() -> Settings:
    s = Settings()
    object.__setattr__(s, "database_url", _normalise_db_url(s.database_url))
    if not s.secret_key:
        if s.environment == "production":
            raise RuntimeError("ACCISAFE_SECRET_KEY must be set in production")
        warnings.warn("ACCISAFE_SECRET_KEY not set: using a random development key; tokens end on restart")
        object.__setattr__(s, "secret_key", secrets.token_urlsafe(48))
    if s.is_production and s.sms_provider == "log":
        warnings.warn("SMS provider is 'log': SMS notifications are simulated, not sent")
    return s


settings = _load()
