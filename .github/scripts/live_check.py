"""Checks a live Grabbly site: pages, the API, and real lookups and downloads on each platform.

    python .github/scripts/live_check.py https://grabbly-downloader-server.vercel.app

Prints one line per check and never downloads more than it needs to report on.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://grabbly-downloader-server.vercel.app").rstrip("/")
UA = {"User-Agent": "Grabbly-live-check/1.0"}

# Public videos that yt-dlp itself uses in its tests
SAMPLES = {
    "TikTok": ["https://www.tiktok.com/@pokemonlife22/video/7059698374567611694",
               "https://www.tiktok.com/@leenabhushan/video/6748451240264420610"],
    "Instagram": ["https://www.instagram.com/reel/Chunk8-jurw/", "https://www.instagram.com/p/BQ0eAlwhDrw/"],
    "Facebook": ["https://www.facebook.com/reel/1195289147628387",
                 "https://www.facebook.com/watch/?v=647537299265662",
                 "https://www.facebook.com/radiokicksfm/videos/3676516585958356/"],
    "X": ["https://twitter.com/JessicaDobsonWX/status/1731121063248175384",
          "https://twitter.com/playstrumpcard/status/1695424220702888009",
          "https://twitter.com/s2FAKER/status/1621117700482416640"],
    "Pinterest": ["https://www.pinterest.com/pin/664281013778109217/",
                  "https://www.pinterest.com/pin/1084663891475263837/"],
    "Twitch": ["https://clips.twitch.tv/FaintLightGullWholeWheat", "https://www.twitch.tv/videos/6528877"],
    "Tumblr": ["https://maskofthedragon.tumblr.com/post/626907179849564160/mona-talking-in-english", "https://bartlebyshop.tumblr.com/post/180294460076/duality-of-bird"],
}


def request(method, path, body=None, read_limit=None, timeout=310):
    url = path if path.startswith("http") else BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={**UA, **({"Content-Type": "application/json"} if data else {})})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            if read_limit is None:
                content = r.read()
                size = len(content)
            else:
                size, content = 0, b""
                while chunk := r.read(256 * 1024):
                    size += len(chunk)
                    if len(content) < read_limit:
                        content += chunk[: read_limit - len(content)]
            return r.status, dict(r.headers), content, size, time.monotonic() - start
    except urllib.error.HTTPError as e:
        content = e.read()
        return e.code, dict(e.headers), content, len(content), time.monotonic() - start
    except Exception as e:  # network errors, timeouts
        return 0, {}, str(e).encode(), 0, time.monotonic() - start


def line(ok, text):
    print(("PASS " if ok else "FAIL ") + text, flush=True)
    return ok


def main():
    print(f"Checking {BASE}\n")
    status, _, html, _, t = request("GET", "/")
    line(status == 200 and b"styles.css?v=" in html, f"home page {status} in {t:.1f}s, versioned CSS: {b'styles.css?v=' in html}")
    status, _, _, _, t = request("GET", "/privacy")
    line(status == 200, f"privacy page {status}")
    status, _, body, _, t = request("GET", "/api/health")
    line(status == 200, f"health {status} {body.decode(errors='replace')} in {t:.1f}s")

    # Test builds of the Android app in public/download: served whole, as an Android package
    for apk in sorted((Path(__file__).resolve().parents[2] / "public" / "download").glob("*.apk")):
        s, h, head, size, t = request("GET", "/download/" + urllib.parse.quote(apk.name), read_limit=4)
        ok = s == 200 and size == apk.stat().st_size and head == b"PK\x03\x04" \
            and "android.package-archive" in h.get("Content-Type", "")
        line(ok, f"app download {apk.name}: {s} {h.get('Content-Type')} {size} of {apk.stat().st_size} bytes in {t:.1f}s")

    for platform, links in SAMPLES.items():
        for link in links:
            status, _, body, _, t = request("POST", "/api/info", {"url": link})
            try:
                data = json.loads(body)
            except ValueError:
                data = {"error": body[:120].decode(errors="replace")}
            if status != 200:
                line(False, f"{platform} info {status} in {t:.1f}s: {data.get('error')}  ({link})")
                continue
            options = [o["id"] + (f"~{o['size'] // 1_000_000}MB" if o.get("size") else "") for o in data["options"]]
            line(True, f"{platform} info in {t:.1f}s: {data['title'][:50]!r} options={options}")
            if data.get("thumbnail"):
                s, h, _, size, _ = request("GET", data["thumbnail"])
                line(s == 200, f"{platform} thumbnail {s} {h.get('Content-Type')} {size} bytes")
            if data["options"]:
                option = data["options"][0]["id"]
                query = urllib.parse.urlencode({"url": data["url"], "option": option})
                s, h, head, size, t = request("GET", f"/api/download?{query}", read_limit=16)
                ok = s == 200 and size > 10_000
                line(ok, f"{platform} download {option}: {s} {h.get('Content-Type')} "
                         f"length={h.get('Content-Length')} got={size} bytes in {t:.1f}s "
                         f"name={h.get('Content-Disposition')}" + ("" if ok else f" body={head[:200]!r}"))
            break  # one working link per platform is enough


if __name__ == "__main__":
    main()
