"""Small WebSocket helpers shared by the camera and client routers."""
import logging
from typing import Any, Optional

from starlette.websockets import WebSocket, WebSocketState

logger = logging.getLogger("watchwire.ws")


async def send_json_safe(ws: Optional[WebSocket], payload: dict[str, Any]) -> bool:
    """Best-effort send: swallows errors from a peer that disconnected concurrently."""
    if ws is None:
        return False
    if ws.client_state != WebSocketState.CONNECTED:
        return False
    try:
        await ws.send_json(payload)
        return True
    except Exception:  # noqa: BLE001 - a broken peer socket must never crash the sender
        logger.debug("Failed to send WS message; peer likely disconnected", exc_info=True)
        return False
