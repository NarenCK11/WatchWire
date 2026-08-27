from app.config import settings


def test_login_success(client):
    resp = client.post(
        "/api/auth/login",
        json={"username": settings.admin_username, "password": settings.admin_password},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["token_type"] == "bearer"
    assert body["expires_in"] > 0
    assert len(body["access_token"]) > 20


def test_login_wrong_password(client):
    resp = client.post(
        "/api/auth/login",
        json={"username": settings.admin_username, "password": "wrong-password"},
    )
    assert resp.status_code == 401


def test_login_unknown_user(client):
    resp = client.post("/api/auth/login", json={"username": "nobody", "password": "x"})
    assert resp.status_code == 401


def test_login_rate_limited_after_repeated_failures(client):
    for _ in range(settings.login_max_attempts):
        client.post(
            "/api/auth/login",
            json={"username": settings.admin_username, "password": "wrong"},
        )
    resp = client.post(
        "/api/auth/login",
        json={"username": settings.admin_username, "password": settings.admin_password},
    )
    assert resp.status_code == 429


def test_me_requires_auth(client):
    resp = client.get("/api/auth/me")
    assert resp.status_code == 401


def test_me_with_valid_token(client, auth_token):
    resp = client.get("/api/auth/me", headers={"Authorization": f"Bearer {auth_token}"})
    assert resp.status_code == 200
    assert resp.json()["username"] == settings.admin_username


def test_me_with_garbage_token(client):
    resp = client.get("/api/auth/me", headers={"Authorization": "Bearer not-a-real-token"})
    assert resp.status_code == 401
