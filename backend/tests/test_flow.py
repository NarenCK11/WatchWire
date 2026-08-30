"""End-to-end tests of the pairing + motion-relay flow, using Starlette's TestClient
WebSocket support (no real network sockets involved, but the full app/router/session-store
stack is exercised exactly as it runs in production)."""
import time


def test_full_pairing_and_motion_relay(client, auth_token):
    with client.websocket_connect("/ws/camera") as camera_ws:
        issued = camera_ws.receive_json()
        assert issued["type"] == "code_issued"
        code = issued["code"]
        assert len(code) == 6

        with client.websocket_connect(f"/ws/client?token={auth_token}") as client_ws:
            client_ws.send_json({"type": "pair", "code": code})
            paired_client = client_ws.receive_json()
            assert paired_client["type"] == "paired"
            assert paired_client["camera_connected"] is True

            paired_camera = camera_ws.receive_json()
            assert paired_camera["type"] == "paired"

            camera_ws.send_json({"type": "monitoring_started"})
            status_msg = client_ws.receive_json()
            assert status_msg == {"type": "camera_status", "connected": True, "monitoring": True}

            camera_ws.send_json({"type": "motion_event", "score": 0.82, "timestamp": "2026-08-27T10:00:00+00:00"})
            event = client_ws.receive_json()
            assert event["type"] == "motion_event"
            assert event["score"] == 0.82


def test_pairing_with_invalid_code_returns_error(client, auth_token):
    with client.websocket_connect(f"/ws/client?token={auth_token}") as client_ws:
        client_ws.send_json({"type": "pair", "code": "ZZZZZZ"})
        resp = client_ws.receive_json()
        assert resp["type"] == "error"
        assert resp["code"] == "INVALID_CODE"


def test_motion_event_rejected_before_pairing(client):
    with client.websocket_connect("/ws/camera") as camera_ws:
        camera_ws.receive_json()  # code_issued
        camera_ws.send_json({"type": "motion_event", "score": 0.5, "timestamp": "2026-08-27T10:00:00+00:00"})
        resp = camera_ws.receive_json()
        assert resp["type"] == "error"
        assert resp["code"] == "NOT_PAIRED"


def test_second_client_cannot_pair_already_paired_camera(client, auth_token):
    # Once a code has been used to pair, it is consumed -- a second attempt with the same
    # code is rejected as invalid (the code no longer resolves to a waiting session). This
    # also covers the case of someone retrying a code they mistakenly think still works.
    with client.websocket_connect("/ws/camera") as camera_ws:
        code = camera_ws.receive_json()["code"]

        with client.websocket_connect(f"/ws/client?token={auth_token}") as first_client:
            first_client.send_json({"type": "pair", "code": code})
            first_client.receive_json()  # paired
            camera_ws.receive_json()  # paired (camera side)

            with client.websocket_connect(f"/ws/client?token={auth_token}") as second_client:
                second_client.send_json({"type": "pair", "code": code})
                resp = second_client.receive_json()
                assert resp["type"] == "error"
                assert resp["code"] == "INVALID_CODE"


def test_pair_race_is_resolved_atomically(client, auth_token):
    # Simulates two clients racing on the *same* still-waiting session object (bypassing
    # get_by_code, which already filters out non-waiting sessions) to prove SessionStore.pair
    # itself is atomic: exactly one caller may win.
    import asyncio

    from app.sessions import session_store

    async def _race():
        session = await session_store.create_camera_session(camera_ws=object())
        results = await asyncio.gather(
            session_store.pair(session, client_ws=object(), user_id="a"),
            session_store.pair(session, client_ws=object(), user_id="b"),
        )
        return results

    results = asyncio.run(_race())
    assert sorted(results) == [False, True]


def test_client_ws_rejects_invalid_token_with_a_readable_reason(client):
    # The connection must be accepted before closing, otherwise Starlette emits a bare HTTP
    # 403 and the browser reports a generic 1006 -- leaving the web app unable to tell an
    # expired token apart from a network blip, and retrying forever.
    from starlette.websockets import WebSocketDisconnect

    with client.websocket_connect("/ws/client?token=garbage") as ws:
        message = ws.receive_json()
        assert message["type"] == "error"
        assert message["code"] == "UNAUTHENTICATED"

        try:
            ws.receive_json()
            assert False, "expected the server to close the connection"
        except WebSocketDisconnect as exc:
            assert exc.code == 4401


def test_client_ws_rejects_a_token_signed_with_a_different_secret(client):
    # Exactly what happens after a backend restart: the JWT secret is regenerated, so tokens
    # still sitting in the browser's localStorage no longer verify.
    import jwt as pyjwt

    forged = pyjwt.encode({"sub": "admin"}, "some-other-secret", algorithm="HS256")
    with client.websocket_connect(f"/ws/client?token={forged}") as ws:
        assert ws.receive_json()["code"] == "UNAUTHENTICATED"


def test_invalid_message_shape_does_not_crash_connection(client):
    with client.websocket_connect("/ws/camera") as camera_ws:
        camera_ws.receive_json()  # code_issued
        camera_ws.send_json({"type": "not_a_real_type"})
        resp = camera_ws.receive_json()
        assert resp["type"] == "error"
        assert resp["code"] == "INVALID_MESSAGE"

        # connection must still be alive afterwards
        camera_ws.send_json({"type": "ping"})
        pong = camera_ws.receive_json()
        assert pong == {"type": "pong"}


def test_client_resume_after_reconnect(client, auth_token):
    with client.websocket_connect("/ws/camera") as camera_ws:
        code = camera_ws.receive_json()["code"]

        with client.websocket_connect(f"/ws/client?token={auth_token}") as client_ws:
            client_ws.send_json({"type": "pair", "code": code})
            paired = client_ws.receive_json()
            client_token = paired["client_token"]
            camera_ws.receive_json()  # paired (camera side)

        # client socket above is now closed (context manager exited) -- camera should be
        # told the peer disconnected.
        disconnect_notice = camera_ws.receive_json()
        assert disconnect_notice["type"] == "peer_disconnected"

        # Reconnect using the saved client_token instead of re-entering the code.
        with client.websocket_connect(f"/ws/client?token={auth_token}") as resumed_ws:
            resumed_ws.send_json({"type": "resume", "session_token": client_token})
            resumed = resumed_ws.receive_json()
            assert resumed["type"] == "paired"
            assert resumed["camera_connected"] is True

            camera_ws.send_json({"type": "motion_event", "score": 0.4, "timestamp": "2026-08-27T10:05:00+00:00"})
            event = resumed_ws.receive_json()
            assert event["type"] == "motion_event"
            assert event["score"] == 0.4


def test_camera_is_notified_when_client_resumes(client, auth_token):
    # The camera has no other way to learn its viewer came back after a drop -- it must
    # receive a fresh "paired" message, not just the earlier peer_disconnected.
    with client.websocket_connect("/ws/camera") as camera_ws:
        code = camera_ws.receive_json()["code"]

        with client.websocket_connect(f"/ws/client?token={auth_token}") as client_ws:
            client_ws.send_json({"type": "pair", "code": code})
            client_token = client_ws.receive_json()["client_token"]
            camera_ws.receive_json()  # paired (camera side)

        camera_ws.receive_json()  # peer_disconnected

        with client.websocket_connect(f"/ws/client?token={auth_token}") as resumed_ws:
            resumed_ws.send_json({"type": "resume", "session_token": client_token})
            resumed_ws.receive_json()  # paired (client side)

            camera_notice = camera_ws.receive_json()
            assert camera_notice["type"] == "paired"


def test_camera_resume_preserves_pairing(client, auth_token):
    with client.websocket_connect("/ws/camera") as camera_ws:
        issued = camera_ws.receive_json()
        code = issued["code"]
        camera_token = issued["camera_token"]

        with client.websocket_connect(f"/ws/client?token={auth_token}") as client_ws:
            client_ws.send_json({"type": "pair", "code": code})
            client_ws.receive_json()  # paired
            camera_ws.receive_json()  # paired (camera side)

            # Simulate the camera's socket dropping and it reconnecting with its saved token.
            with client.websocket_connect(f"/ws/camera?camera_token={camera_token}") as resumed_camera_ws:
                resumed = resumed_camera_ws.receive_json()
                assert resumed["type"] == "paired"

                status = client_ws.receive_json()
                assert status == {"type": "camera_status", "connected": True, "monitoring": False}

                resumed_camera_ws.send_json({"type": "motion_event", "score": 0.9, "timestamp": "2026-08-27T10:10:00+00:00"})
                event = client_ws.receive_json()
                assert event["score"] == 0.9
