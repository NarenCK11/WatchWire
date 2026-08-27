"""Application configuration, loaded from environment variables (or a .env file).

All settings are prefixed with WATCHWIRE_ (e.g. WATCHWIRE_JWT_SECRET). See
backend/.env.example for the full list and sensible defaults.
"""
import secrets

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="WATCHWIRE_", extra="ignore")

    # Seed admin account for the web app login. Change these in production via env vars.
    admin_username: str = "admin"
    admin_password: str = "changeme123"

    # JWT auth for the web client. If not set explicitly, a random secret is generated at
    # process startup -- this is fine for local dev (tokens just get invalidated on restart)
    # but MUST be set to a fixed, secret value in production so tokens survive restarts and
    # can't be forged.
    jwt_secret: str = secrets.token_urlsafe(32)
    jwt_algorithm: str = "HS256"
    jwt_expire_minutes: int = 12 * 60

    # Comma-separated list of allowed CORS origins for the web app.
    cors_origins: str = "http://localhost:5173,http://127.0.0.1:5173"

    # Pairing code lifetime, in seconds, before it is rotated (if the camera is still
    # connected) or the session is dropped (if not).
    code_ttl_seconds: int = 300

    # How long a paired session is kept alive after BOTH sides have disconnected, in case
    # either side reconnects (camera generates a fresh code; client can resume by token).
    paired_session_grace_seconds: int = 120

    # Hard cap on how long a paired session may live, regardless of activity.
    session_max_lifetime_hours: int = 12

    # How often the background cleanup sweep runs.
    sweep_interval_seconds: int = 15

    # Login brute-force protection.
    login_max_attempts: int = 5
    login_lockout_seconds: int = 300

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


settings = Settings()
