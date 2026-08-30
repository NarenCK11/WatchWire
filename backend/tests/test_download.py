import app.routers.download as download


def test_apk_info_reports_unavailable_when_directory_empty(client, tmp_path, monkeypatch):
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)
    resp = client.get("/download/apk/info")
    assert resp.status_code == 200
    assert resp.json() == {"available": False}


def test_apk_download_404s_with_actionable_message_when_missing(client, tmp_path, monkeypatch):
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)
    resp = client.get("/download/apk")
    assert resp.status_code == 404
    assert "assembleRelease" in resp.json()["detail"]


def test_apk_download_serves_file_with_android_media_type(client, tmp_path, monkeypatch):
    apk = tmp_path / "watchwire-arm64-v8a-release.apk"
    apk.write_bytes(b"PK\x03\x04fake-apk-bytes")
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)

    info = client.get("/download/apk/info").json()
    assert info["available"] is True
    assert info["filename"] == "watchwire-arm64-v8a-release.apk"

    resp = client.get("/download/apk")
    assert resp.status_code == 200
    # Android only offers to install when served with this exact content type.
    assert resp.headers["content-type"] == "application/vnd.android.package-archive"
    assert "watchwire.apk" in resp.headers["content-disposition"]
    assert resp.content == b"PK\x03\x04fake-apk-bytes"


def test_prefers_arm64_over_other_builds(client, tmp_path, monkeypatch):
    (tmp_path / "watchwire-universal-release.apk").write_bytes(b"universal")
    (tmp_path / "watchwire-arm64-v8a-release.apk").write_bytes(b"arm64")
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)

    assert client.get("/download/apk").content == b"arm64"


def test_never_hands_out_the_emulator_only_x86_build(client, tmp_path, monkeypatch):
    (tmp_path / "watchwire-x86_64-release.apk").write_bytes(b"x86")
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)

    assert client.get("/download/apk/info").json() == {"available": False}


def test_download_requires_no_authentication(client, tmp_path, monkeypatch):
    # The APK is the client you need in order to log in, so gating it behind login would
    # be circular. Assert that explicitly so it isn't "fixed" into a regression later.
    (tmp_path / "watchwire-arm64-v8a-release.apk").write_bytes(b"arm64")
    monkeypatch.setattr(download, "_apk_directory", lambda: tmp_path)

    resp = client.get("/download/apk")  # no Authorization header
    assert resp.status_code == 200


def test_lan_origin_is_allowed_by_cors(client):
    resp = client.get(
        "/api/health",
        headers={"Origin": "http://192.168.1.6:5173"},
    )
    assert resp.status_code == 200
    assert resp.headers.get("access-control-allow-origin") == "http://192.168.1.6:5173"


def test_public_internet_origin_is_not_allowed_by_cors(client):
    resp = client.get("/api/health", headers={"Origin": "http://evil.example.com"})
    assert resp.status_code == 200
    assert "access-control-allow-origin" not in resp.headers
