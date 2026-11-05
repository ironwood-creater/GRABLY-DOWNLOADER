"""A stand-in Grably server for the Android app's on-device test in CI.

Answers /api/info with one fake video and /api/download with ~300 KB of bytes, so the app's whole
download path (background worker, saving to the gallery, the Downloads list) runs without
reaching TikTok. Listens on port 8000; the emulator reaches it at http://10.0.2.2:8000.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SIZE = 300_000
INFO = {
    "platform": "tiktok",
    "platform_name": "TikTok",
    "url": "https://www.tiktok.com/@grably/video/1234567890",
    "title": "Grably test clip",
    "uploader": "grably",
    "duration": 12,
    "thumbnail": None,
    "options": [
        {"id": "v720", "kind": "video", "format": "MP4", "quality": "720p", "size": SIZE},
        {"id": "audio", "kind": "audio", "format": "MP3", "quality": "Audio only", "size": 40_000},
    ],
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
        self._send(200, json.dumps(INFO).encode(), "application/json")

    def do_GET(self):
        print("get:", self.path, flush=True)
        if not self.path.startswith("/api/download"):
            return self._send(404, b'{"error": "Not found"}', "application/json")
        self._send(
            200,
            os.urandom(SIZE),
            "video/mp4",
            {"Content-Disposition": "attachment; filename=\"Grably test clip.mp4\"; filename*=utf-8''Grably%20test%20clip.mp4"},
        )


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
