import pytest
from fastapi.testclient import TestClient

from server import app as server_app
from server import downloader as d


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(server_app, "info_limit", server_app.RateLimiter(100))
    monkeypatch.setattr(server_app, "download_limit", server_app.RateLimiter(100))
    with TestClient(server_app.app) as c:
        yield c


INFO = {
    "platform": "tiktok", "platform_name": "TikTok", "url": "https://www.tiktok.com/@a/video/1",
    "title": "Golden Hour Drive", "uploader": "ava", "duration": 42,
    "thumbnail": "https://cdn.example/t.jpg",
    "options": [{"id": "v720", "kind": "video", "format": "MP4", "quality": "720p", "size": 123}],
}


def test_health(client):
    assert client.get("/api/health").json()["ok"] is True


def test_info_returns_signed_thumbnail(client, monkeypatch):
    monkeypatch.setattr(d, "fetch_info", lambda url: dict(INFO))
    r = client.post("/api/info", json={"url": "https://www.tiktok.com/@a/video/1"})
    assert r.status_code == 200
    body = r.json()
    assert body["title"] == "Golden Hour Drive"
    assert body["thumbnail"].startswith("/api/thumb?u=https%3A%2F%2Fcdn.example%2Ft.jpg&s=")


def test_info_errors_are_json(client):
    r = client.post("/api/info", json={"url": "https://youtube.com/watch?v=1"})
    assert r.status_code == 400
    assert "doesn't support" in r.json()["error"]
    r = client.post("/api/info", json={"url": ""})
    assert r.status_code == 400 and "error" in r.json()


def test_info_is_rate_limited(client, monkeypatch):
    monkeypatch.setattr(server_app, "info_limit", server_app.RateLimiter(2))
    monkeypatch.setattr(d, "fetch_info", lambda url: dict(INFO))
    codes = [client.post("/api/info", json={"url": "https://x.com/a/status/1"}).status_code for _ in range(3)]
    assert codes == [200, 200, 429]


def test_download_streams_file_and_cleans_up(client, monkeypatch, tmp_path):
    work = tmp_path / "dl-1"
    work.mkdir()
    media = work / "media.mp4"
    media.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"0" * 1000)
    seen = {}

    def fake_download(url, option):
        seen.update(url=url, option=option)
        return d.Download(path=media, filename="Café vibes.mp4", media_type="video/mp4", tmpdir=work)

    monkeypatch.setattr(d, "download", fake_download)
    r = client.get("/api/download", params={"url": "https://x.com/a/status/1", "option": "v720"})
    assert r.status_code == 200
    assert r.headers["content-type"] == "video/mp4"
    assert r.headers["content-length"] == "1012"
    assert "attachment" in r.headers["content-disposition"]
    assert r.headers["content-disposition"] == "attachment; filename*=utf-8''Caf%C3%A9%20vibes.mp4"
    assert seen == {"url": "https://x.com/a/status/1", "option": "v720"}
    assert not work.exists()  # temp folder removed after sending


def test_download_rejects_bad_option(client):
    r = client.get("/api/download", params={"url": "https://x.com/a/status/1", "option": "rm -rf"})
    assert r.status_code == 400


def test_thumbnail_proxy_only_serves_signed_urls(client):
    r = client.get("/api/thumb", params={"u": "https://169.254.169.254/", "s": "0" * 32})
    assert r.status_code == 404


def test_website_is_served_but_server_code_is_not(client):
    assert client.get("/").status_code == 200
    assert "Grably" in client.get("/").text
    assert client.get("/assets/css/styles.css").status_code == 200
    for path in ("/server/app.py", "/README.md", "/Dockerfile", "/assets/../server/app.py"):
        assert client.get(path).status_code == 404, path


def test_security_headers(client):
    r = client.get("/")
    assert r.headers["x-content-type-options"] == "nosniff"
    assert r.headers["x-frame-options"] == "DENY"
