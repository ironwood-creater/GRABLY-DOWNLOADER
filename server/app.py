"""Grably web server: serves the website and the video download API.

Run locally:
    uvicorn server.app:app --reload

API
    GET  /api/health                      -> {"ok": true, "audio": false}
    POST /api/info      {"url": "..."}    -> video title, thumbnail and download options
    GET  /api/download?url=...&option=... -> the video file (attachment)
    GET  /api/thumb?u=...&s=...           -> thumbnail image (only URLs this server signed)
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import os
import secrets
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlencode, urlsplit

import httpx
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool

from . import downloader

log = logging.getLogger("grably")
logging.basicConfig(level=os.environ.get("GRABLY_LOG_LEVEL", "INFO"))

ROOT = Path(__file__).resolve().parent.parent
SECRET = (os.environ.get("GRABLY_SECRET") or secrets.token_hex(32)).encode()
TRUST_PROXY = os.environ.get("GRABLY_TRUST_PROXY", "0").lower() in ("1", "true", "yes")
MAX_THUMB_BYTES = 5 * 1024 * 1024


# --------------------------------------------------------------------------
# Rate limiting and concurrency
# --------------------------------------------------------------------------
class RateLimiter:
    """Sliding-window limit per client, kept in memory."""

    def __init__(self, limit: int, window: float = 60.0):
        self.limit, self.window = limit, window
        self.hits: dict[str, deque] = defaultdict(deque)

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        q = self.hits[key]
        while q and now - q[0] > self.window:
            q.popleft()
        if len(q) >= self.limit:
            return False
        q.append(now)
        if len(self.hits) > 10_000:  # forget idle clients
            for k in [k for k, v in self.hits.items() if not v]:
                del self.hits[k]
        return True


info_limit = RateLimiter(int(os.environ.get("GRABLY_INFO_PER_MIN", 30)))
download_limit = RateLimiter(int(os.environ.get("GRABLY_DOWNLOADS_PER_MIN", 10)))
download_slots = asyncio.Semaphore(int(os.environ.get("GRABLY_MAX_CONCURRENT", 3)))


def client_ip(request: Request) -> str:
    if TRUST_PROXY:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def error(message: str, status: int) -> JSONResponse:
    return JSONResponse({"error": message}, status_code=status)


# --------------------------------------------------------------------------
# App
# --------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(_app: FastAPI):
    removed = downloader.cleanup_stale(max_age=3600)
    if removed:
        log.info("removed %d leftover download folders", removed)
    yield


app = FastAPI(title="Grably", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

origins = [o.strip() for o in os.environ.get("GRABLY_ALLOWED_ORIGINS", "").split(",") if o.strip()]
if origins:  # only needed when the website is hosted somewhere else than the API
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
        expose_headers=["Content-Disposition", "Content-Length"],
    )


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    response.headers.setdefault("X-Frame-Options", "DENY")
    return response


@app.exception_handler(downloader.GrablyError)
async def grably_error(_request: Request, exc: downloader.GrablyError):
    return error(exc.message, exc.status)


@app.exception_handler(RequestValidationError)
async def validation_error(_request: Request, _exc: RequestValidationError):
    return error("That request wasn't valid. Paste a video link and try again.", 400)


def sign(value: str) -> str:
    return hmac.new(SECRET, value.encode(), hashlib.sha256).hexdigest()[:32]


# --------------------------------------------------------------------------
# API
# --------------------------------------------------------------------------
class InfoRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)


@app.get("/api/health")
async def health():
    return {"ok": True, "audio": downloader.settings.allow_audio}


@app.post("/api/info")
async def info(body: InfoRequest, request: Request):
    if not info_limit.allow(client_ip(request)):
        return error("You're going a bit fast. Wait a minute and try again.", 429)
    data = await run_in_threadpool(downloader.fetch_info, body.url)
    thumb = data.pop("thumbnail", None)
    data["thumbnail"] = f"/api/thumb?{urlencode({'u': thumb, 's': sign(thumb)})}" if thumb else None
    return data


@app.get("/api/download")
async def download(
    request: Request,
    url: str = Query(min_length=1, max_length=2048),
    option: str = Query("best", max_length=8),
):
    if not download_limit.allow(client_ip(request)):
        return error("You're downloading a bit fast. Wait a minute and try again.", 429)
    if download_slots.locked():
        return error("Grably is busy right now. Try again in a moment.", 503)
    # Sweep folders left behind by downloads that were cut off mid-transfer
    await run_in_threadpool(downloader.cleanup_stale, 3600)
    async with download_slots:
        result = await run_in_threadpool(downloader.download, url, option)
    return FileResponse(
        result.path,
        media_type=result.media_type,
        filename=result.filename,
        headers={"Cache-Control": "no-store"},
        background=BackgroundTask(result.cleanup),
    )


@app.get("/api/thumb")
async def thumb(u: str = Query(max_length=4096), s: str = Query(max_length=64)):
    if not hmac.compare_digest(sign(u), s) or urlsplit(u).scheme != "https":
        return error("Not found.", 404)
    try:
        async with httpx.AsyncClient(timeout=10, follow_redirects=False,
                                     headers={"User-Agent": downloader.USER_AGENT}) as client:
            async with client.stream("GET", u) as resp:
                ctype = resp.headers.get("content-type", "")
                if resp.status_code != 200 or not ctype.startswith("image/"):
                    return error("Not found.", 404)
                body = bytearray()
                async for chunk in resp.aiter_bytes():
                    body += chunk
                    if len(body) > MAX_THUMB_BYTES:
                        return error("Not found.", 404)
    except httpx.HTTPError:
        return error("Not found.", 404)
    return Response(bytes(body), media_type=ctype, headers={"Cache-Control": "public, max-age=3600"})


# --------------------------------------------------------------------------
# Website (only index.html and /assets are public, not the server code)
# --------------------------------------------------------------------------
@app.get("/", include_in_schema=False)
async def index():
    return FileResponse(ROOT / "index.html")


app.mount("/assets", StaticFiles(directory=ROOT / "assets"), name="assets")
