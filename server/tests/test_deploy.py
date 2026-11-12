"""Checks for the hosting setup: Vercel (pyproject.toml, vercel.json, the static build step)
and ffmpeg discovery."""
import json
import os
import re
import shutil
import subprocess
import sys
import tomllib
from pathlib import Path

from server import downloader as d
from server import site

ROOT = Path(__file__).resolve().parents[2]


def _requirements() -> list[str]:
    lines = (ROOT / "server" / "requirements.txt").read_text().splitlines()
    return [ln.strip() for ln in lines if ln.strip() and not ln.lstrip().startswith("#")]


def test_vercel_and_docker_install_the_same_packages():
    project = tomllib.loads((ROOT / "pyproject.toml").read_text())
    assert project["project"]["dependencies"] == _requirements()


def test_vercel_points_at_the_fastapi_app():
    project = tomllib.loads((ROOT / "pyproject.toml").read_text())
    module, _, attr = project["tool"]["vercel"]["entrypoint"].partition(":")
    assert (ROOT / (module.replace(".", "/") + ".py")).is_file() and attr == "app"
    config = json.loads((ROOT / "vercel.json").read_text())
    for path, fn in config["functions"].items():
        assert (ROOT / path).is_file()
        assert fn["maxDuration"] <= 300  # the most a Hobby project allows
    assert (ROOT / ".python-version").read_text().strip() == "3.12"


def _copy_public(tmp_path: Path) -> Path:
    public = tmp_path / "public"
    shutil.copytree(site.PUBLIC, public)
    return public


def test_static_build_versions_assets_and_leaves_ads_off(tmp_path):
    public = _copy_public(tmp_path)
    written = site.build(public, ads=None)
    assert public / "ads.txt" not in written and not (public / "ads.txt").exists()
    for name in site.PAGES:
        html = (public / name).read_text()
        assert re.search(r'"assets/css/styles\.css\?v=[0-9a-f]{10}"', html)
        assert "google-adsense-account" not in html
    assert re.search(r'"assets/js/main\.js\?v=[0-9a-f]{10}"', (public / "index.html").read_text())


def test_static_build_fills_in_adsense_and_is_safe_to_run_twice(tmp_path):
    public = _copy_public(tmp_path)
    ads = site.Ads("ca-pub-1234567890123456", "9876543210")
    site.build(public, ads)
    once = (public / "index.html").read_text()
    site.build(public, ads)
    twice = (public / "index.html").read_text()
    assert once == twice
    assert once.count("?v=") == 2
    assert '<meta name="grabbly-ads-client" content="ca-pub-1234567890123456">' in once
    assert '<meta name="google-adsense-account" content="ca-pub-1234567890123456">' in once
    assert '<meta name="grabbly-ads-slot" content="9876543210">' in once
    assert (public / "ads.txt").read_text() == "google.com, pub-1234567890123456, DIRECT, f08c47fec0942fa0\n"


def test_ads_need_both_valid_ids():
    assert site.ads_from_env({}) is None
    assert site.ads_from_env({"GRABBLY_ADSENSE_CLIENT": "ca-pub-1234567890123456"}) is None
    assert site.ads_from_env({"GRABBLY_ADSENSE_CLIENT": "pub-123", "GRABBLY_ADSENSE_SLOT": "9876543210"}) is None
    assert site.ads_from_env({"GRABBLY_ADSENSE_CLIENT": " ca-pub-1234567890123456 ", "GRABBLY_ADSENSE_SLOT": "9876543210"}) == \
        site.Ads("ca-pub-1234567890123456", "9876543210")


def test_build_step_runs_with_only_the_standard_library(tmp_path):
    # Vercel runs `python -m server.site`; it must not need FastAPI or yt-dlp
    source = (ROOT / "server" / "site.py").read_text()
    imports = set(re.findall(r"^(?:from|import) (\w+)", source, re.M))
    assert imports <= {"__future__", "hashlib", "logging", "os", "re", "dataclasses", "pathlib", "typing"}


def test_ffmpeg_is_found_without_a_system_copy(monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    exe = d.find_ffmpeg()
    assert exe and os.access(exe, os.X_OK)
    assert "ffmpeg" in Path(exe).name


def test_without_ffmpeg_only_single_file_formats_are_used(monkeypatch):
    monkeypatch.setattr(d, "FFMPEG", None)
    assert d.format_opts("v720")["format"] == "b"
    monkeypatch.setattr(d, "FFMPEG", "/usr/bin/ffmpeg")
    assert d.format_opts("v720")["format"] == "bv*+ba/b"


def _server_settings(env: dict) -> dict:
    code = ("import json; from server import app as a, downloader as d; "
            "print(json.dumps({'trust': a.TRUST_PROXY, 'mb': d.settings.max_bytes // 1048576}))")
    clean = {k: v for k, v in os.environ.items() if not k.startswith(("VERCEL", "GRABBLY_"))}
    out = subprocess.run([sys.executable, "-c", code], cwd=ROOT, env={**clean, **env},
                         capture_output=True, text=True, check=True)
    return json.loads(out.stdout.strip().splitlines()[-1])


def test_vercel_defaults():
    assert _server_settings({}) == {"trust": False, "mb": 500}
    assert _server_settings({"VERCEL": "1", "GRABBLY_SECRET": "x"}) == {"trust": True, "mb": 200}
    assert _server_settings({"VERCEL": "1", "GRABBLY_SECRET": "x", "GRABBLY_TRUST_PROXY": "0"})["trust"] is False
