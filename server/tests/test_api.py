import re
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


# ---------------------------------------------------------------- audit fixes
def test_rate_limiter_forgets_idle_visitors(monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr(server_app.time, "monotonic", lambda: clock[0])
    limiter = server_app.RateLimiter(5, window=60)
    for i in range(500):
        limiter.allow(f"10.0.{i // 250}.{i % 250}")
    assert len(limiter.hits) == 500
    clock[0] += 61
    limiter.allow("fresh-visitor")
    assert list(limiter.hits) == ["fresh-visitor"]  # old visitors dropped, memory freed


def test_rate_limit_cannot_be_dodged_with_a_fake_forwarded_for(client, monkeypatch):
    monkeypatch.setattr(server_app, "TRUST_PROXY", True)
    monkeypatch.setattr(server_app, "info_limit", server_app.RateLimiter(2))
    monkeypatch.setattr(d, "fetch_info", lambda url: dict(INFO))
    codes = []
    for fake in ("1.1.1.1", "2.2.2.2", "3.3.3.3"):  # visitor types a different address each time
        r = client.post("/api/info", json={"url": "https://x.com/a/status/1"},
                        headers={"X-Forwarded-For": f"{fake}, 203.0.113.9"})  # proxy appends the real one
        codes.append(r.status_code)
    assert codes == [200, 200, 429]


def test_pages_are_compressed_but_downloads_keep_their_length(client, monkeypatch, tmp_path):
    r = client.get("/", headers={"Accept-Encoding": "gzip"})
    assert r.headers.get("content-encoding") == "gzip"
    assert client.get("/assets/css/styles.css", headers={"Accept-Encoding": "gzip"}).headers.get("content-encoding") == "gzip"

    work = tmp_path / "dl-2"
    work.mkdir()
    media = work / "media.bin"
    media.write_bytes(b"x" * 5000)
    monkeypatch.setattr(d, "download", lambda url, option: d.Download(
        path=media, filename="v.bin", media_type="application/octet-stream", tmpdir=work))
    r = client.get("/api/download", params={"url": "https://x.com/a/status/1"}, headers={"Accept-Encoding": "gzip"})
    assert "content-encoding" not in r.headers
    assert r.headers["content-length"] == "5000"


def test_css_and_js_are_versioned_and_cached(client):
    html = client.get("/").text
    css = re.search(r'assets/css/styles\.css\?v=([0-9a-f]{10})', html)
    js = re.search(r'assets/js/main\.js\?v=([0-9a-f]{10})', html)
    assert css and js
    assert client.get("/").headers["cache-control"] == "no-cache"
    r = client.get(f"/assets/css/styles.css?v={css.group(1)}")
    assert r.headers["cache-control"] == "public, max-age=31536000, immutable"
    assert client.get("/assets/fonts/inter-latin.woff2").headers["cache-control"].endswith("immutable")
    assert client.get("/assets/img/logo.svg").headers["cache-control"] == "public, max-age=86400"


def test_head_request_on_homepage(client):
    assert client.head("/").status_code == 200


def test_unexpected_errors_return_json(monkeypatch):
    def boom(url):
        raise RuntimeError("bug")
    monkeypatch.setattr(d, "fetch_info", boom)
    with TestClient(server_app.app, raise_server_exceptions=False) as c:
        r = c.post("/api/info", json={"url": "https://x.com/a/status/1"})
    assert r.status_code == 500
    assert r.json() == {"error": "Something went wrong on our side. Please try again."}


# ---------------------------------------------------------------- free + AdSense
@pytest.fixture
def ads(monkeypatch):
    monkeypatch.setattr(server_app, "ADS_CLIENT", "ca-pub-1234567890123456")
    monkeypatch.setattr(server_app, "ADS_SLOT", "9876543210")


def test_no_ads_until_configured(client):
    html = client.get("/").text
    assert '<meta name="grably-ads-client" content="">' in html
    assert "google-adsense-account" not in html
    assert client.get("/ads.txt").status_code == 404


def test_adsense_ids_are_filled_in_and_ads_txt_is_served(client, ads):
    html = client.get("/").text
    assert '<meta name="grably-ads-client" content="ca-pub-1234567890123456">' in html
    assert '<meta name="google-adsense-account" content="ca-pub-1234567890123456">' in html
    assert '<meta name="grably-ads-slot" content="9876543210">' in html
    r = client.get("/ads.txt")
    assert r.status_code == 200
    assert r.text == "google.com, pub-1234567890123456, DIRECT, f08c47fec0942fa0\n"


def test_privacy_page_and_robots(client):
    for path in ("/privacy.html", "/privacy"):
        r = client.get(path)
        assert r.status_code == 200 and "Privacy policy" in r.text
        assert re.search(r'assets/css/styles\.css\?v=[0-9a-f]{10}', r.text)
        assert "Google AdSense" in r.text
    r = client.get("/robots.txt")
    assert r.status_code == 200 and "Disallow: /api/" in r.text


def test_audio_downloads_are_on_by_default():
    assert d.Settings().allow_audio is True
