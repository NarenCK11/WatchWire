"""WebSocket endpoint for the Android camera device.

The camera connects with no auth (it doesn't represent a logged-in user -- pairing is
what associates it with an authenticated web user). On connect it immediately receives a
short-lived pairing code to display to the person standing in front of it.
"""
import json
import logging

from fastapi import APIRouter, Query
from pydantic import ValidationError
from starlette.websockets import WebSocket, WebSocketDisconnect

from ..config import settings
from ..schemas import (
    CameraMonitoringStarted,
    CameraMonitoringStopped,
    CameraMotionEvent,
    PingMessage,
    camera_inbound_adapter,
)
from ..sessions import session_store
from ..ws_utils import send_json_safe

logger = logging.getLogger("watchwire.ws.camera")

router = APIRouter()


async def _send_code_issued(websocket: WebSocket, session) -> None:
    await send_json_safe(
        websocket,
        {
            "type": "code_issued",
            "code": session.code,
            "camera_token": session.camera_token,
            "expires_in": settings.code_ttl_seconds,
        },
    )


@router.websocket("/ws/camera")
async def camera_endpoint(websocket: WebSocket, camera_token: str | None = Query(default=None)) -> None:
    await websocket.accept()

    session = await session_store.get_by_camera_token(camera_token) if camera_token else None
    if session is not None:
        # Reconnect of a previously-known camera (e.g. a brief network drop) -- keep the
        # same pairing/session identity rather than forcing a fresh code.
        await session_store.resume_camera(session, websocket)
        if session.status == "paired":
            await send_json_safe(websocket, {"type": "paired", "paired_at": session.paired_at})
            await send_json_safe(session.client_ws, {"type": "camera_status", "connected": True, "monitoring": session.monitoring})
        else:
            await _send_code_issued(websocket, session)
    else:
        session = await session_store.create_camera_session(websocket)
        await _send_code_issued(websocket, session)

    try:
        while True:
            raw = await websocket.receive_text()

            try:
                data = json.loads(raw)
                message = camera_inbound_adapter.validate_python(data)
            except (json.JSONDecodeError, ValidationError) as exc:
                await send_json_safe(
                    websocket,
                    {"type": "error", "code": "INVALID_MESSAGE", "message": f"Malformed message: {exc}"[:300]},
                )
                continue

            if isinstance(message, PingMessage):
                await send_json_safe(websocket, {"type": "pong"})

            elif isinstance(message, CameraMotionEvent):
                if session.status != "paired":
                    await send_json_safe(
                        websocket,
                        {"type": "error", "code": "NOT_PAIRED", "message": "No client is paired yet."},
                    )
                    continue
                await session_store.record_motion_event(session.id, message.score, message.timestamp)
                await send_json_safe(
                    session.client_ws,
                    {"type": "motion_event", "score": message.score, "timestamp": message.timestamp},
                )

            elif isinstance(message, (CameraMonitoringStarted, CameraMonitoringStopped)):
                monitoring = isinstance(message, CameraMonitoringStarted)
                await session_store.set_monitoring(session.id, monitoring)
                await send_json_safe(
                    session.client_ws,
                    {"type": "camera_status", "connected": True, "monitoring": monitoring},
                )

    except WebSocketDisconnect:
        logger.info("Camera disconnected: session=%s", session.id)
    finally:
        await session_store.on_camera_disconnect(session.id)
        if session.client_ws is not None:
            await send_json_safe(
                session.client_ws,
                {"type": "camera_status", "connected": False, "monitoring": False},
            )
