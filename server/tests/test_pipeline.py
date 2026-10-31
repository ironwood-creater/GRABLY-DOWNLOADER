"""Runs the real yt-dlp + ffmpeg download path against a video served from localhost.

The platform check is bypassed (resolve() is patched) because the test can't
reach TikTok and friends; everything after that is the production code path.
"""
import http.server
import json
import shutil
import subprocess
import threading
from functools import partial

import pytest

from server import downloader as d

pytestmark = pytest.mark.skipif(not shutil.which("ffmpeg"), reason="ffmpeg not installed")


@pytest.fixture(scope="module")
def served_video(tmp_path_factory):
    root = tmp_path_factory.mktemp("site")
    # a 2-second 1280x720 H.264/AAC test clip
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=24:duration=2",
         "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx264", "-pix_fmt", "yuv420p",
         "-c:a", "aac", "-shortest", str(root / "clip.mp4")],
        check=True,
    )
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass

    handler = partial(Quiet, directory=str(root))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_port}/clip.mp4"
    server.shutdown()


@pytest.fixture
def local(monkeypatch, tmp_path, served_video):
    monkeypatch.setenv("NO_PROXY", "127.0.0.1,localhost")
    monkeypatch.setenv("no_proxy", "127.0.0.1,localhost")
    monkeypatch.setattr(d.settings, "tmp_root", tmp_path / "work")
    monkeypatch.setattr(d, "resolve", lambda url, peek=None: (served_video, d.PLATFORMS[0]))
    # allow yt-dlp's direct-file extractor for this local URL
    original = d._ydl_opts
    monkeypatch.setattr(d, "_ydl_opts", lambda platform, **extra: {**original(platform, **extra), "allowed_extractors": ["generic"]})
    return served_video


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)],
                         capture_output=True, text=True, check=True)
    return {s["codec_type"]: s["codec_name"] for s in json.loads(out.stdout)["streams"]}


def test_downloads_mp4_video(local):
    result = d.download("https://www.tiktok.com/@a/video/1", "v720")
    try:
        assert result.path.exists() and result.path.suffix == ".mp4"
        assert result.media_type == "video/mp4"
        assert result.filename.endswith(".mp4")
        assert probe(result.path) == {"video": "h264", "audio": "aac"}
    finally:
        result.cleanup()
    assert not result.tmpdir.exists()


def test_downloads_mp3_audio(local, monkeypatch):
    monkeypatch.setattr(d.settings, "allow_audio", True)
    result = d.download("https://www.tiktok.com/@a/video/1", "audio")
    try:
        assert result.path.suffix == ".mp3"
        assert result.media_type == "audio/mpeg"
        assert probe(result.path) == {"audio": "mp3"}
    finally:
        result.cleanup()


def test_files_over_the_size_limit_are_refused_and_cleaned_up(local, monkeypatch):
    monkeypatch.setattr(d.settings, "max_bytes", 1000)
    with pytest.raises(d.TooLarge):
        d.download("https://www.tiktok.com/@a/video/1", "best")
    assert list((d.settings.tmp_root).iterdir()) == []
