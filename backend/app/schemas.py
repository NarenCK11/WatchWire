"""REST and WebSocket message schemas.

Every inbound WebSocket message is validated against one of the models below via a
discriminated union on the `type` field. Anything that doesn't match is rejected with an
`error` message instead of being processed -- clients (Android or web) can never send
arbitrary data into the relay.
"""
from datetime import datetime, timezone
from typing import Annotated, Literal, Union

from pydantic import BaseModel, Field, TypeAdapter, field_validator

# ---------------------------------------------------------------------------
# REST
# ---------------------------------------------------------------------------


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=200)


class LoginResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    expires_in: int


class MeResponse(BaseModel):
    username: str


# ---------------------------------------------------------------------------
# WebSocket: messages sent BY the Android camera, received by the backend
# ---------------------------------------------------------------------------


class CameraMotionEvent(BaseModel):
    type: Literal["motion_event"]
    score: float = Field(ge=0.0, le=1.0)
    timestamp: str

    @field_validator("timestamp")
    @classmethod
    def _validate_timestamp(cls, value: str) -> str:
        # Accept any ISO-8601 string; normalize "Z" suffix which fromisoformat rejects
        # on Python < 3.11 semantics for some formats.
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return value


class CameraMonitoringStarted(BaseModel):
    type: Literal["monitoring_started"]


class CameraMonitoringStopped(BaseModel):
    type: Literal["monitoring_stopped"]


class PingMessage(BaseModel):
    type: Literal["ping"]


CameraInbound = Annotated[
    Union[CameraMotionEvent, CameraMonitoringStarted, CameraMonitoringStopped, PingMessage],
    Field(discriminator="type"),
]
camera_inbound_adapter: TypeAdapter = TypeAdapter(CameraInbound)


# ---------------------------------------------------------------------------
# WebSocket: messages sent BY the web client, received by the backend
# ---------------------------------------------------------------------------


class ClientPairMessage(BaseModel):
    type: Literal["pair"]
    code: str = Field(min_length=4, max_length=12)

    @field_validator("code")
    @classmethod
    def _normalize_code(cls, value: str) -> str:
        return value.strip().upper()


class ClientResumeMessage(BaseModel):
    type: Literal["resume"]
    session_token: str = Field(min_length=8, max_length=200)


ClientInbound = Annotated[
    Union[ClientPairMessage, ClientResumeMessage, PingMessage],
    Field(discriminator="type"),
]
client_inbound_adapter: TypeAdapter = TypeAdapter(ClientInbound)


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
