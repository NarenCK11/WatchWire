"""WebSocket endpoint for the authenticated web (remote monitoring) client.

The client must present a valid JWT access token as a query parameter (browsers cannot
set custom headers on a WebSocket handshake). Once connected, it either pairs with a
camera using a human-entered code, or resumes a previously-paired session using the
client_token it was issued at pairing time.
"""
import json
import logging

from fastapi import APIRouter, Query
from pydantic import ValidationError
from starlette.websockets import WebSocket, WebSocketDisconnect

from ..schemas import ClientPairMessage, ClientResumeMessage, PingMessage, client_inbound_adapter
from ..security import decode_access_token
from ..sessions import Session, session_store
from ..users import user_store
from ..ws_utils import send_json_safe

logger = logging.getLogger("watchwire.ws.client")

router = APIRouter()

CLOSE_UNAUTHENTICATED = 4401


async def _send_paired(websocket: WebSocket, session: Session) -> None:
    await send_json_safe(
        websocket,
        {
            "type": "paired",
            "client_token": session.client_token,
            "paired_at": session.paired_at,
            "camera_connected": session.camera_connected,
            "monitoring": session.monitoring,
        },
    )


@router.websocket("/ws/client")
async def client_endpoint(websocket: WebSocket, token: str = Query(...)) -> None:
    username = decode_access_token(token)
    if username is None or user_store.get(username) is None:
        await websocket.close(code=CLOSE_UNAUTHENTICATED, reason="Not authenticated")
        return

    await websocket.accept()
    current_session: Session | None = None

    try:
        while True:
            raw = await websocket.receive_text()

            try:
                data = json.loads(raw)
                message = client_inbound_adapter.validate_python(data)
            except (json.JSONDecodeError, ValidationError) as exc:
                await send_json_safe(
                    websocket,
                    {"type": "error", "code": "INVALID_MESSAGE", "message": f"Malformed message: {exc}"[:300]},
                )
                continue

            if isinstance(message, PingMessage):
                await send_json_safe(websocket, {"type": "pong"})

            elif isinstance(message, ClientPairMessage):
                session = await session_store.get_by_code(message.code)
                if session is None:
                    await send_json_safe(
                        websocket,
                        {"type": "error", "code": "INVALID_CODE", "message": "That code is invalid or has expired."},
                    )
                    continue
                claimed = await session_store.pair(session, websocket, username)
                if not claimed:
                    await send_json_safe(
                        websocket,
                        {"type": "error", "code": "ALREADY_PAIRED", "message": "That camera is already paired with another client."},
                    )
                    continue
                current_session = session
                await _send_paired(websocket, session)
                await send_json_safe(session.camera_ws, {"type": "paired", "paired_at": session.paired_at})

            elif isinstance(message, ClientResumeMessage):
                session = await session_store.get_by_client_token(message.session_token)
                if session is None or session.user_id != username or session.status != "paired":
                    await send_json_safe(
                        websocket,
                        {"type": "error", "code": "INVALID_SESSION", "message": "That session is no longer available. Please pair again."},
                    )
                    continue

                await session_store.resume_client(session, websocket)
                current_session = session
                await _send_paired(websocket, session)

    except WebSocketDisconnect:
        logger.info("Client disconnected: user=%s", username)
    finally:
        if current_session is not None:
            await session_store.on_client_disconnect(current_session.id)
            await send_json_safe(current_session.camera_ws, {"type": "peer_disconnected"})
