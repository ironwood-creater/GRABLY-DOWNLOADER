"""Grabbly web server: serves the website and the video download API.

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
from typing import Optional
from urllib.parse import urlencode, urlsplit

import httpx
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, PlainTextResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool
from starlette.middleware.gzip import DEFAULT_EXCLUDED_CONTENT_TYPES, GZipMiddleware

from . import downloader, site

log = logging.getLogger("grabbly")
logging.basicConfig(level=os.environ.get("GRABBLY_LOG_LEVEL", "INFO"))

PUBLIC = site.PUBLIC  # everything the website serves (pages, CSS, JS, fonts, images)
ON_VERCEL = os.environ.get("VERCEL") == "1"
SECRET = (os.environ.get("GRABBLY_SECRET") or "").encode()
if not SECRET:
    if ON_VERCEL:
        # Each Vercel instance would pick its own random key and thumbnails would break
        log.warning("GRABBLY_SECRET isn't set; thumbnails may not load. Set it in the project's environment variables")
    SECRET = secrets.token_hex(32).encode()
# Behind a proxy that sets X-Forwarded-For (Vercel always does, and its value can't be faked)
TRUST_PROXY = os.environ.get("GRABBLY_TRUST_PROXY", "1" if ON_VERCEL else "0").lower() in ("1", "true", "yes")
PROXY_HOPS = max(1, int(os.environ.get("GRABBLY_PROXY_HOPS", 1)))
MAX_THUMB_BYTES = 5 * 1024 * 1024
STALE_AFTER = 3 * 60 * 60  # seconds before an abandoned temp download folder is removed

# Google AdSense: publisher ID (ca-pub-...) and the display ad unit ID used for the ad spaces.
_ads = site.ads_from_env()
ADS_CLIENT = _ads.client if _ads else ""
ADS_SLOT = _ads.slot if _ads else ""


# --------------------------------------------------------------------------
# Rate limiting and concurrency
# --------------------------------------------------------------------------
class RateLimiter:
    """Sliding-window limit per client, kept in memory."""

    def __init__(self, limit: int, window: float = 60.0):
        self.limit, self.window = limit, window
        self.hits: dict[str, deque] = defaultdict(deque)
        self._next_sweep = time.monotonic() + window

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        if now >= self._next_sweep:
            self._sweep(now)
        q = self.hits[key]
        while q and now - q[0] > self.window:
            q.popleft()
        if len(q) >= self.limit:
            return False
        q.append(now)
        return True

    def _sweep(self, now: float) -> None:
        """Forget visitors with no recent requests, so memory doesn't grow forever."""
        self.hits = defaultdict(deque, {k: q for k, q in self.hits.items() if q and now - q[-1] <= self.window})
        self._next_sweep = now + self.window


info_limit = RateLimiter(int(os.environ.get("GRABBLY_INFO_PER_MIN", 30)))
download_limit = RateLimiter(int(os.environ.get("GRABBLY_DOWNLOADS_PER_MIN", 10)))
download_slots = asyncio.Semaphore(int(os.environ.get("GRABBLY_MAX_CONCURRENT", 3)))


def client_ip(request: Request) -> str:
    if TRUST_PROXY:
        # Each proxy appends the address it received the request from, so the trustworthy entry
        # is counted from the right. The leftmost entries can be typed in by the visitor.
        hops = [h.strip() for h in request.headers.get("x-forwarded-for", "").split(",") if h.strip()]
        if len(hops) >= PROXY_HOPS:
            return hops[-PROXY_HOPS]
    return request.client.host if request.client else "unknown"


def error(message: str, status: int) -> JSONResponse:
    return JSONResponse({"error": message}, status_code=status)


# --------------------------------------------------------------------------
# App
# --------------------------------------------------------------------------
@asynccontextmanager
async def lifespan(_app: FastAPI):
    removed = downloader.cleanup_stale(max_age=STALE_AFTER)
    if removed:
        log.info("removed %d leftover download folders", removed)
    yield


app = FastAPI(title="Grabbly", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

origins = [o.strip() for o in os.environ.get("GRABBLY_ALLOWED_ORIGINS", "").split(",") if o.strip()]
if origins:  # only needed when the website is hosted somewhere else than the API
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
        expose_headers=["Content-Disposition", "Content-Length"],
    )


# Compress HTML, CSS, JS and JSON. Videos, audio, images and fonts are already compressed and are
# skipped, which also keeps Content-Length on downloads so the progress bar works.
app.add_middleware(
    GZipMiddleware,
    minimum_size=1000,
    exclude_content_types=DEFAULT_EXCLUDED_CONTENT_TYPES + ("application/octet-stream",),
)


@app.middleware("http")
async def response_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "strict-origin-when-cross-origin")
    response.headers.setdefault("X-Frame-Options", "DENY")
    path = request.url.path
    if path.startswith("/assets/") and response.status_code == 200:
        if "v" in request.query_params or path.startswith("/assets/fonts/"):
            # versioned CSS/JS (see render_index) and fonts never change at the same URL
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        else:
            response.headers["Cache-Control"] = "public, max-age=86400"
    return response


@app.exception_handler(downloader.GrabblyError)
async def grabbly_error(_request: Request, exc: downloader.GrabblyError):
    return error(exc.message, exc.status)


@app.exception_handler(RequestValidationError)
async def validation_error(_request: Request, _exc: RequestValidationError):
    return error("That request wasn't valid. Paste a video link and try again.", 400)


@app.exception_handler(Exception)
async def unexpected_error(request: Request, _exc: Exception):
    log.exception("unexpected error on %s", request.url.path)
    return error("Something went wrong on our side. Please try again.", 500)


def sign(value: str) -> str:
    return hmac.new(SECRET, value.encode(), hashlib.sha256).hexdigest()[:32]


# --------------------------------------------------------------------------
# API
# --------------------------------------------------------------------------
class InfoRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    # The app sets this to get every video and photo in a post, and the caption
    items: bool = False


def thumb_link(url: Optional[str]) -> Optional[str]:
    return f"/api/thumb?{urlencode({'u': url, 's': sign(url)})}" if url else None


@app.get("/api/health")
async def health():
    return {"ok": True, "audio": downloader.settings.allow_audio and downloader.FFMPEG is not None}


@app.post("/api/info")
async def info(body: InfoRequest, request: Request):
    if not info_limit.allow(client_ip(request)):
        return error("You're going a bit fast. Wait a minute and try again.", 429)
    if body.items:
        data = await run_in_threadpool(downloader.fetch_info, body.url, items=True)
        for item in data.get("items") or []:
            item["thumbnail"] = thumb_link(item.get("thumbnail"))
    else:
        data = await run_in_threadpool(downloader.fetch_info, body.url)
    data["thumbnail"] = thumb_link(data.pop("thumbnail", None))
    return data


@app.get("/api/download")
async def download(
    request: Request,
    url: str = Query(min_length=1, max_length=2048),
    option: str = Query("best", max_length=8),
    item: Optional[int] = Query(None, ge=1, le=200),
):
    if not download_limit.allow(client_ip(request)):
        return error("You're downloading a bit fast. Wait a minute and try again.", 429)
    if download_slots.locked():
        return error("Grabbly is busy right now. Try again in a moment.", 503)
    # Sweep folders left behind by downloads that were cut off mid-transfer
    await run_in_threadpool(downloader.cleanup_stale, STALE_AFTER)
    async with download_slots:
        if item is None:
            result = await run_in_threadpool(downloader.download, url, option)
        else:
            result = await run_in_threadpool(downloader.download, url, option, item=item)
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
_page_cache: dict = {}


def render_page(name: str) -> str:
    """An HTML page with versioned CSS/JS links and the AdSense IDs filled in (see site.render).
    Cached until one of the files changes."""
    ads = site.Ads(ADS_CLIENT, ADS_SLOT) if ADS_CLIENT and ADS_SLOT else None
    key = (ads, *((PUBLIC / p).stat().st_mtime_ns for p in (name, *site.VERSIONED_ASSETS)))
    cached = _page_cache.get(name)
    if cached and cached[0] == key:
        return cached[1]
    html = site.render((PUBLIC / name).read_text(encoding="utf-8"), PUBLIC, ads)
    _page_cache[name] = (key, html)
    return html


def page(name: str) -> HTMLResponse:
    return HTMLResponse(render_page(name), headers={"Cache-Control": "no-cache"})


@app.api_route("/", methods=["GET", "HEAD"], include_in_schema=False)
async def index():
    return page("index.html")


@app.api_route("/privacy.html", methods=["GET", "HEAD"], include_in_schema=False)
@app.api_route("/privacy", methods=["GET", "HEAD"], include_in_schema=False)
async def privacy():
    return page("privacy.html")


@app.get("/ads.txt", include_in_schema=False)
async def ads_txt():
    """Authorizes Google to sell ads on this site (AdSense checks for this file)."""
    txt = site.ads_txt(site.Ads(ADS_CLIENT, ADS_SLOT) if ADS_CLIENT and ADS_SLOT else None)
    if not txt:
        return PlainTextResponse("Not found.\n", status_code=404)
    return PlainTextResponse(txt, headers={"Cache-Control": "public, max-age=86400"})


@app.get("/robots.txt", include_in_schema=False)
async def robots_txt():
    return FileResponse(PUBLIC / "robots.txt", media_type="text/plain", headers={"Cache-Control": "public, max-age=86400"})


if (PUBLIC / "assets").is_dir():  # on Vercel the CDN serves public/ and it may not be bundled
    app.mount("/assets", StaticFiles(directory=PUBLIC / "assets"), name="assets")
