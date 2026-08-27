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
from .routers import auth, ws_camera, ws_client
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

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(ws_camera.router)
app.include_router(ws_client.router)


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok"}
