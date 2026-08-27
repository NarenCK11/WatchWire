"""In-memory pairing session store.

A `Session` represents one Android camera. It starts in "waiting" status with a short-lived
human-readable code. Once a web client submits that code, the session becomes "paired" and
the two WebSocket connections are linked so motion events can be relayed directly between
them.

Two separate reconnection secrets are issued per session: `camera_token` (given only to the
camera, lets it reclaim its own connection slot after a network drop) and `client_token`
(given only to the paired web client, lets it resume after a reload). They are intentionally
kept separate -- a web client that knew the *same* secret used by the camera could otherwise
hijack the camera's connection slot and inject fake motion events.

Everything here lives in process memory, guarded by a single asyncio.Lock (the event loop
is single-threaded, but WebSocket handlers are separate coroutines and we want atomic
read-modify-write on the dicts below). This class is intentionally the *only* place that
touches session state, so swapping the storage backend for Redis later only means
reimplementing this class -- nothing else in the app needs to change.
"""
import asyncio
import secrets
import time
from dataclasses import dataclass, field
from typing import Optional

from starlette.websockets import WebSocket

from .config import settings

# Unambiguous uppercase alphabet: no 0/O, 1/I/L, to keep codes easy to read aloud/type.
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
CODE_LENGTH = 6


def _generate_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


@dataclass
class Session:
    id: str
    code: str
    code_expires_at: float
    camera_token: str = field(default_factory=lambda: secrets.token_urlsafe(24))
    client_token: str = field(default_factory=lambda: secrets.token_urlsafe(24))
    status: str = "waiting"  # waiting | paired | closed
    camera_ws: Optional[WebSocket] = None
    client_ws: Optional[WebSocket] = None
    user_id: Optional[str] = None
    monitoring: bool = False
    created_at: float = field(default_factory=time.time)
    paired_at: Optional[float] = None
    last_event: Optional[dict] = None
    camera_disconnected_at: Optional[float] = None
    client_disconnected_at: Optional[float] = None

    @property
    def camera_connected(self) -> bool:
        return self.camera_ws is not None

    @property
    def client_connected(self) -> bool:
        return self.client_ws is not None


class SessionStore:
    def __init__(self) -> None:
        self._by_id: dict[str, Session] = {}
        self._by_code: dict[str, str] = {}
        self._by_camera_token: dict[str, str] = {}
        self._by_client_token: dict[str, str] = {}
        self._lock = asyncio.Lock()

    async def create_camera_session(self, camera_ws: WebSocket) -> Session:
        async with self._lock:
            session = Session(
                id=secrets.token_urlsafe(16),
                code=self._unique_code(),
                code_expires_at=time.time() + settings.code_ttl_seconds,
                camera_ws=camera_ws,
            )
            self._by_id[session.id] = session
            self._by_code[session.code] = session.id
            self._by_camera_token[session.camera_token] = session.id
            self._by_client_token[session.client_token] = session.id
            return session

    def _unique_code(self) -> str:
        for _ in range(50):
            code = _generate_code()
            if code not in self._by_code:
                return code
        raise RuntimeError("Unable to allocate a unique pairing code")

    async def get_by_code(self, code: str) -> Optional[Session]:
        async with self._lock:
            session_id = self._by_code.get(code.strip().upper())
            if session_id is None:
                return None
            session = self._by_id.get(session_id)
            if session is None or session.status != "waiting":
                return None
            if time.time() >= session.code_expires_at:
                return None
            return session

    async def get_by_camera_token(self, token: str) -> Optional[Session]:
        async with self._lock:
            session_id = self._by_camera_token.get(token)
            return self._by_id.get(session_id) if session_id else None

    async def get_by_client_token(self, token: str) -> Optional[Session]:
        async with self._lock:
            session_id = self._by_client_token.get(token)
            return self._by_id.get(session_id) if session_id else None

    async def pair(self, session: Session, client_ws: WebSocket, user_id: str) -> bool:
        """Atomically claims a waiting session. Returns False (no-op) if another client
        already paired it first -- this can happen if two clients race on the same code."""
        async with self._lock:
            if session.status != "waiting":
                return False
            self._by_code.pop(session.code, None)
            session.client_ws = client_ws
            session.user_id = user_id
            session.status = "paired"
            session.paired_at = time.time()
            session.client_disconnected_at = None
            return True

    async def resume_client(self, session: Session, client_ws: WebSocket) -> None:
        async with self._lock:
            session.client_ws = client_ws
            session.client_disconnected_at = None

    async def resume_camera(self, session: Session, camera_ws: WebSocket) -> None:
        async with self._lock:
            session.camera_ws = camera_ws
            session.camera_disconnected_at = None

    async def on_camera_disconnect(self, session_id: str) -> Optional[Session]:
        async with self._lock:
            session = self._by_id.get(session_id)
            if session is None:
                return None
            session.camera_ws = None
            session.monitoring = False
            session.camera_disconnected_at = time.time()
            return session

    async def on_client_disconnect(self, session_id: str) -> Optional[Session]:
        async with self._lock:
            session = self._by_id.get(session_id)
            if session is None:
                return None
            session.client_ws = None
            session.client_disconnected_at = time.time()
            return session

    async def record_motion_event(self, session_id: str, score: float, timestamp: str) -> None:
        async with self._lock:
            session = self._by_id.get(session_id)
            if session is not None:
                session.last_event = {"score": score, "timestamp": timestamp}

    async def set_monitoring(self, session_id: str, monitoring: bool) -> None:
        async with self._lock:
            session = self._by_id.get(session_id)
            if session is not None:
                session.monitoring = monitoring

    async def _remove(self, session_id: str) -> None:
        session = self._by_id.pop(session_id, None)
        if session is None:
            return
        self._by_code.pop(session.code, None)
        self._by_camera_token.pop(session.camera_token, None)
        self._by_client_token.pop(session.client_token, None)

    async def sweep(self) -> dict[str, list[Session]]:
        """Cleans up expired/abandoned sessions. Returns sessions whose code was just
        rotated (so a fresh code can be pushed to the still-connected camera), and
        sessions that were torn down entirely (so a lingering peer can be notified)."""
        now = time.time()
        removed: list[Session] = []
        rotated: list[Session] = []

        async with self._lock:
            for session in list(self._by_id.values()):
                max_lifetime = settings.session_max_lifetime_hours * 3600
                if now - session.created_at > max_lifetime:
                    removed.append(session)
                    await self._remove(session.id)
                    continue

                if session.status == "waiting" and now >= session.code_expires_at:
                    if session.camera_ws is not None:
                        self._by_code.pop(session.code, None)
                        session.code = self._unique_code()
                        session.code_expires_at = now + settings.code_ttl_seconds
                        self._by_code[session.code] = session.id
                        rotated.append(session)
                    else:
                        removed.append(session)
                        await self._remove(session.id)
                    continue

                if session.status == "paired":
                    both_gone = session.camera_ws is None and session.client_ws is None
                    grace = settings.paired_session_grace_seconds
                    camera_stale = (
                        session.camera_ws is None
                        and session.camera_disconnected_at is not None
                        and now - session.camera_disconnected_at > grace
                    )
                    client_stale = (
                        session.client_ws is None
                        and session.client_disconnected_at is not None
                        and now - session.client_disconnected_at > grace
                    )
                    if both_gone or camera_stale or client_stale:
                        removed.append(session)
                        await self._remove(session.id)

        return {"rotated": rotated, "removed": removed}


session_store = SessionStore()
