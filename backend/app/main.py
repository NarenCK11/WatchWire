"""WatchWire backend entrypoint.

Run with:  uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
"""
import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .config import settings
from .routers import auth, download, ws_camera, ws_client
from .sessions import session_store
from .users import user_store
from .ws_utils import send_json_safe

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("watchwire")


async def _sweep_loop() -> None:
    while True:
        await asyncio.sleep(settings.sweep_interval_seconds)
        try:
            result = await session_store.sweep()
        except Exception:  # noqa: BLE001 - background loop must never die
            logger.exception("Session sweep failed")
            continue

        for session in result["rotated"]:
            logger.info("Rotated expired pairing code for session=%s", session.id)
            await send_json_safe(
                session.camera_ws,
                {
                    "type": "code_issued",
                    "code": session.code,
                    "camera_token": session.camera_token,
                    "expires_in": settings.code_ttl_seconds,
                },
            )

        for session in result["removed"]:
            logger.info("Removed stale session=%s", session.id)
            for ws in (session.camera_ws, session.client_ws):
                if ws is not None:
                    await send_json_safe(ws, {"type": "error", "code": "SESSION_EXPIRED", "message": "Session expired."})
                    with contextlib.suppress(Exception):
                        await ws.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    user_store.seed(settings.admin_username, settings.admin_password)
    logger.info("Seeded admin user '%s'", settings.admin_username)
    sweep_task = asyncio.create_task(_sweep_loop())
    try:
        yield
    finally:
        sweep_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await sweep_task


app = FastAPI(title="WatchWire Backend", version="1.0.0", lifespan=lifespan)

# Matches http(s)://<private-LAN-ip>[:port] so the web app can be opened from a phone on the
# same network without pinning that machine's IP in config. See Settings.cors_allow_private_lan.
PRIVATE_LAN_ORIGIN_REGEX = (
    r"https?://(?:"
    r"192\.168\.\d{1,3}\.\d{1,3}"
    r"|10\.\d{1,3}\.\d{1,3}\.\d{1,3}"
    r"|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}"
    r"|localhost|127\.0\.0\.1"
    r")(?::\d+)?"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_origin_regex=PRIVATE_LAN_ORIGIN_REGEX if settings.cors_allow_private_lan else None,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(download.router)
app.include_router(ws_camera.router)
app.include_router(ws_client.router)


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok"}
