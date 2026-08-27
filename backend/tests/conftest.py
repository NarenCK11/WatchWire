import pytest
from starlette.testclient import TestClient

from app.main import app
from app.routers.auth import _rate_limiter
from app.sessions import session_store
from app.users import user_store


@pytest.fixture()
def client():
    # Each test gets a clean in-memory session table and a clean rate-limiter/user store;
    # the user store is reseeded by the app's own lifespan startup hook when the
    # TestClient context is entered.
    session_store._by_id.clear()
    session_store._by_code.clear()
    session_store._by_camera_token.clear()
    session_store._by_client_token.clear()
    _rate_limiter._failures.clear()
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def auth_token(client):
    from app.config import settings

    resp = client.post(
        "/api/auth/login",
        json={"username": settings.admin_username, "password": settings.admin_password},
    )
    assert resp.status_code == 200
    return resp.json()["access_token"]
