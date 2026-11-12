"""A stand-in Grabbly server for the Android app's on-device tests and store screenshots in CI.

Answers /api/info with sample videos, /api/download with a few hundred KB of bytes, and
/thumbs/<name>.jpg with the illustrated thumbnails in ci/media/, so the app's whole download
path (background worker, saving to the gallery, the Downloads list) runs without reaching any
real site. Listens on port 8000; the emulator reaches it at http://10.0.2.2:8000.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MEDIA = Path(__file__).resolve().parent / "media"
SIZE = 300_000
MB = 1_048_576


def video(platform, name, url, title, uploader, duration, thumb, options):
    return {"platform": platform, "platform_name": name, "url": url, "title": title, "uploader": uploader,
            "duration": duration, "thumbnail": f"/thumbs/{thumb}.jpg" if thumb else None, "options": options}


def opt(id_, kind, fmt, quality, size):
    return {"id": id_, "kind": kind, "format": fmt, "quality": quality, "size": size}


# Picked by a part of the link (keep them distinct: none may contain another); the first is the fallback
VIDEOS = {
    "@grably/video/1234567890": video("tiktok", "TikTok", "https://www.tiktok.com/@grably/video/1234567890", "Grabbly test clip",
                        "grably", 12, None, [opt("v720", "video", "MP4", "720p", SIZE),
                                             opt("audio", "audio", "MP3", "Audio only", 40_000)]),
    "ava.travels": video("tiktok", "TikTok", "https://www.tiktok.com/@ava.travels/video/7301234567890",
                         "Golden hour drive along the coast", "@ava.travels", 38, "sunset",
                         [opt("v1080", "video", "MP4", "1080p", int(24.3 * MB)), opt("v720", "video", "MP4", "720p", int(14.1 * MB)),
                          opt("v480", "video", "MP4", "480p", int(7.9 * MB)), opt("audio", "audio", "MP3", "Audio only", int(0.9 * MB))]),
    "streetfood": video("instagram", "Instagram", "https://www.instagram.com/reel/Cstreetfood/",
                        "Street food tour: 10 dishes under $5", "@foodie.maps", 74, "food",
                        [opt("v1080", "video", "MP4", "1080p", int(31.6 * MB)), opt("v720", "video", "MP4", "720p", int(18.2 * MB)),
                         opt("audio", "audio", "MP3", "Audio only", int(1.7 * MB))]),
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, content_type, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        request = json.loads(self.rfile.read(length) or b"{}")
        print("info:", request, flush=True)
        if self.path != "/api/info":
            return self._send(404, b'{"error": "Not found"}', "application/json")
        link = request.get("url", "")
        found = next((v for key, v in VIDEOS.items() if key in link), next(iter(VIDEOS.values())))
        self._send(200, json.dumps(found).encode(), "application/json")

    def do_GET(self):
        print("get:", self.path, flush=True)
        if self.path.startswith("/thumbs/"):
            path = MEDIA / Path(self.path.split("?")[0]).name
            if path.suffix == ".jpg" and path.is_file():
                return self._send(200, path.read_bytes(), "image/jpeg")
        if not self.path.startswith("/api/download"):
            return self._send(404, b'{"error": "Not found"}', "application/json")
        self._send(
            200,
            os.urandom(SIZE),
            "video/mp4",
            {"Content-Disposition": "attachment; filename=\"Grabbly test clip.mp4\"; filename*=utf-8''Grabbly%20test%20clip.mp4"},
        )


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
