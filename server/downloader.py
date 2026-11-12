"""Look up and download videos from supported platforms with yt-dlp.

Only links from the sites in PLATFORMS are accepted (TikTok, Instagram, Facebook, X/Twitter,
Pinterest, Twitch and Tumblr).
Every URL is checked against an allowlist before anything is fetched, short
links are followed one redirect at a time (each hop is checked again), and
yt-dlp is limited to the matching extractors. That way the server can't be
used to fetch arbitrary URLs.
"""
from __future__ import annotations

import logging
import math
import os
import re
import shutil
import tempfile
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, TypeVar
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx
import yt_dlp
from yt_dlp.utils import DownloadError

try:  # used to ask X for the photos in a post; yt-dlp keeps it outside its public utils
    from yt_dlp.jsinterp import js_number_to_string
except ImportError:  # pragma: no cover - only if yt-dlp moves it
    js_number_to_string = None

log = logging.getLogger("grabbly.downloader")


# --------------------------------------------------------------------------
# Settings (environment variables)
# --------------------------------------------------------------------------
@dataclass
class Settings:
    max_duration: int = 3 * 60 * 60          # seconds
    max_bytes: int = 500 * 1024 * 1024       # largest file we'll serve
    allow_audio: bool = True                 # offer an "MP3 · Audio only" option
    tmp_root: Path = Path(tempfile.gettempdir()) / "grabbly"
    socket_timeout: int = 20

    @classmethod
    def from_env(cls) -> "Settings":
        s = cls()
        s.max_duration = int(os.environ.get("GRABBLY_MAX_DURATION", s.max_duration))
        # Vercel Functions have about 500 MB of temporary disk, and merging needs room for both parts
        default_mb = 200 if os.environ.get("VERCEL") == "1" else 500
        s.max_bytes = int(os.environ.get("GRABBLY_MAX_MB", default_mb)) * 1024 * 1024
        s.allow_audio = os.environ.get("GRABBLY_AUDIO", "1").lower() in ("1", "true", "yes")
        s.tmp_root = Path(os.environ.get("GRABBLY_TMP", s.tmp_root))
        return s


settings = Settings.from_env()


def find_ffmpeg() -> Optional[str]:
    """ffmpeg merges separate video and audio streams and converts audio to MP3.
    Uses the system ffmpeg (Docker) or the copy bundled with imageio-ffmpeg (Vercel)."""
    system = shutil.which("ffmpeg")
    if system:
        return system
    try:
        import imageio_ffmpeg
    except ImportError:
        return None
    try:
        exe = imageio_ffmpeg.get_ffmpeg_exe()
    except RuntimeError:
        return None
    if not os.access(exe, os.X_OK):
        # Some hosts unpack the bundle without the executable bit: use a copy in /tmp
        copy = Path(tempfile.gettempdir()) / "grabbly-ffmpeg"
        if not copy.exists():
            shutil.copyfile(exe, copy)
        copy.chmod(0o755)
        exe = str(copy)
    return exe


FFMPEG = find_ffmpeg()
if FFMPEG is None:
    log.warning("ffmpeg not found: only single-file formats are offered and MP3 downloads are off")

USER_AGENT = "Mozilla/5.0 (compatible; GrabblyBot/1.0)"
MAX_REDIRECTS = 5
OPTION_RE = re.compile(r"best|audio|photo|v\d{3,4}")

# Posts with several videos or photos (the app asks for all of them; the website only for the first)
MAX_ITEMS = 20
PHOTO_PLATFORMS = {"instagram", "pinterest", "x"}       # where photo posts can be saved
IMAGE_HOSTS = ("cdninstagram.com", "fbcdn.net", "pinimg.com", "twimg.com")
IMAGE_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif", "image/heic": ".heic"}
X_PHOTO_INDEX = 100   # X photos come from a second source; numbered from 101 so they never clash with videos
CAPTION_LIMIT = 2200  # Instagram's own caption limit


# --------------------------------------------------------------------------
# Errors shown to the user
# --------------------------------------------------------------------------
class GrabblyError(Exception):
    status = 400

    def __init__(self, message: str, status: Optional[int] = None):
        super().__init__(message)
        self.message = message
        if status is not None:
            self.status = status


class InvalidLink(GrabblyError):
    status = 400


class UnsupportedSite(GrabblyError):
    status = 400


class VideoUnavailable(GrabblyError):
    status = 422


class TooLarge(GrabblyError):
    status = 413


# --------------------------------------------------------------------------
# Supported platforms
# --------------------------------------------------------------------------
@dataclass(frozen=True)
class Platform:
    key: str
    name: str
    domains: tuple[str, ...]                    # the host itself or any subdomain
    extractors: tuple[str, ...]                 # yt-dlp extractor names (regex, full match)
    host_pattern: Optional[re.Pattern] = None   # extra host rule (Pinterest's country domains)

    def matches(self, host: str) -> bool:
        if any(host == d or host.endswith("." + d) for d in self.domains):
            return True
        return bool(self.host_pattern and self.host_pattern.search(host))


PLATFORMS: tuple[Platform, ...] = (
    Platform("tiktok", "TikTok", ("tiktok.com",), ("tiktok", r"vm\.tiktok")),
    Platform("instagram", "Instagram", ("instagram.com", "instagr.am"), ("instagram",)),
    Platform("facebook", "Facebook", ("facebook.com", "fb.com", "fb.watch"), ("facebook", "facebook:reel")),
    Platform("x", "X", ("x.com", "twitter.com", "t.co"), ("twitter", "twitter:shortener")),
    Platform(
        "pinterest", "Pinterest", ("pin.it",), ("pinterest",),
        re.compile(r"(?:^|\.)pinterest\.(?:[a-z]{2,4}|co\.[a-z]{2}|com\.[a-z]{2})$"),
    ),
    # Clips and past broadcasts; live streams can't be downloaded
    Platform("twitch", "Twitch", ("twitch.tv",), ("twitch:clips", "twitch:vod")),
    Platform("tumblr", "Tumblr", ("tumblr.com", "tmblr.co"), ("tumblr",)),
)
SUPPORTED_NAMES = ", ".join(p.name for p in PLATFORMS[:-1]) + " and " + PLATFORMS[-1].name

# Short links that redirect to the real post. We follow these ourselves.
SHORT_HOSTS = {"t.co", "pin.it", "fb.watch", "vm.tiktok.com", "vt.tiktok.com", "instagr.am", "tmblr.co"}
SHARE_PATH_HOSTS = ("instagram.com", "facebook.com")  # .../share/... links redirect too

URL_IN_TEXT = re.compile(r"https?://[^\s<>\"']+|(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s<>\"']*", re.I)


def platform_for_host(host: str) -> Optional[Platform]:
    host = host.rstrip(".").lower()
    for p in PLATFORMS:
        if p.matches(host):
            return p
    return None


def extract_url(text: str) -> str:
    """Pull the first link out of pasted text such as 'Look at this! https://...'."""
    text = (text or "").strip()
    if not text:
        raise InvalidLink("Paste a video link first.")
    if len(text) > 2048:
        raise InvalidLink("That link is too long.")
    m = URL_IN_TEXT.search(text)
    if not m:
        raise InvalidLink("That doesn't look like a link. In the app, tap Share, then Copy link, and paste it here.")
    url = m.group(0).rstrip(".,);]'\"")
    if not re.match(r"https?://", url, re.I):
        url = "https://" + url
    return url


def check_url(url: str) -> tuple[str, Platform]:
    """Validate a URL against the allowlist. Returns a cleaned https URL and its platform."""
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise InvalidLink("That doesn't look like a valid link.") from None
    if parts.scheme.lower() not in ("http", "https") or parts.username or parts.password:
        raise InvalidLink("That doesn't look like a valid link.")
    if port not in (None, 80, 443):
        raise InvalidLink("That doesn't look like a valid link.")
    host = (parts.hostname or "").rstrip(".").lower()
    platform = platform_for_host(host) if host else None
    if platform is None:
        raise UnsupportedSite(
            f"Grabbly doesn't support {host or 'that site'} yet. It works with {SUPPORTED_NAMES}."
        )
    clean = urlunsplit(("https", host, parts.path or "/", parts.query, ""))
    return clean, platform


def _needs_resolving(url: str) -> bool:
    parts = urlsplit(url)
    host = parts.hostname or ""
    if host in SHORT_HOSTS:
        return True
    return parts.path.startswith("/share/") and any(
        host == d or host.endswith("." + d) for d in SHARE_PATH_HOSTS
    )


# A "peek" returns (status_code, Location header) without following redirects.
Peek = Callable[[str], tuple[int, Optional[str]]]


def _peek(url: str) -> tuple[int, Optional[str]]:
    with httpx.Client(follow_redirects=False, timeout=10, headers={"User-Agent": USER_AGENT}) as client:
        with client.stream("GET", url) as resp:
            return resp.status_code, resp.headers.get("location")


def resolve(url: str, peek: Optional[Peek] = None) -> tuple[str, Platform]:
    """Follow short links one hop at a time, re-checking the allowlist on every hop."""
    url, platform = check_url(url)
    peek = peek or _peek
    hops = 0
    while _needs_resolving(url):
        if hops >= MAX_REDIRECTS:
            raise VideoUnavailable("That short link redirects too many times.")
        try:
            status, location = peek(url)
        except httpx.HTTPError:
            raise VideoUnavailable("We couldn't open that link. Check it and try again.") from None
        if status not in (301, 302, 303, 307, 308) or not location:
            break  # not a redirect; let yt-dlp handle the URL as it is
        try:
            url, platform = check_url(urljoin(url, location))
        except UnsupportedSite:
            raise UnsupportedSite("That link doesn't lead to a supported video.") from None
        hops += 1
    return url, platform


# --------------------------------------------------------------------------
# yt-dlp
# --------------------------------------------------------------------------
class _YdlLogger:
    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        log.debug("yt-dlp: %s", msg)

    def error(self, msg):
        log.info("yt-dlp: %s", msg)


YdlFactory = Callable[[dict], "yt_dlp.YoutubeDL"]
T = TypeVar("T")


def _ydl_opts(platform: Optional[Platform], **extra) -> dict:
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": True,
        "playlist_items": "1",
        "socket_timeout": settings.socket_timeout,
        "retries": 2,
        "extractor_retries": 1,
        "cachedir": False,
        "restrictfilenames": True,
        "logger": _YdlLogger(),
    }
    if platform is not None:
        opts["allowed_extractors"] = list(platform.extractors)
    if FFMPEG:
        opts["ffmpeg_location"] = FFMPEG
    opts.update(extra)
    return opts


_FRIENDLY = (
    (("private",), "This video is private, so it can't be downloaded."),
    (("suspended",), "This account has been suspended, so its videos can't be downloaded."),
    (("broadcasts/", "live event", "is live"), "Grabbly can't download live streams. Try again once it has ended and been saved."),
    (("login", "log in", "sign in", "cookies", "authenticat"), "This video needs a login to view, so Grabbly can't download it."),
    (("unsupported url",), "Grabbly can't download from that page. Paste a link to a single video."),
    (("no video", "no media", "no formats", "requested format is not available"), "There's no downloadable video in that post."),
    (("429", "rate-limit", "rate limit", "too many requests"), "That site is limiting requests right now. Try again in a minute."),
    (("not available", "unavailable", "404", "removed", "deleted", "does not exist"), "This video isn't available. It may have been removed."),
)


# Errors that usually go away when asked again a moment later, for example TikTok briefly
# answering cloud servers with a bot check instead of the video page
_TRANSIENT = ("unexpected response from webpage", "timed out", "connection reset", "remote end closed",
              "http error 5", "temporarily unavailable")
RETRY_DELAY = 1.5  # seconds


def _retry_once(action: Callable[[], T], before_retry: Optional[Callable[[], None]] = None) -> T:
    """Runs a yt-dlp step, and once more after a short pause if it failed with a passing error."""
    try:
        return action()
    except DownloadError as e:
        if not any(t in str(e).lower() for t in _TRANSIENT):
            raise
        log.info("retrying after a passing error: %s", e)
        time.sleep(RETRY_DELAY)
        if before_retry:
            before_retry()
        return action()


def friendly_error(message: str) -> str:
    low = message.lower()
    for needles, text in _FRIENDLY:
        if any(n in low for n in needles):
            return text
    return "We couldn't get this video. Check the link and try again."


def _single_entry(info: dict) -> dict:
    if info.get("_type") in ("playlist", "multi_video"):
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise VideoUnavailable("We couldn't find a video at that link.")
        info = entries[0]
    return info


def _short_side(f: dict) -> Optional[int]:
    w, h = f.get("width"), f.get("height")
    if w and h:
        return min(int(w), int(h))
    return int(h) if h else None


def _has_video(f: dict) -> bool:
    return f.get("vcodec") != "none"


def _has_audio(f: dict) -> bool:
    return f.get("acodec") != "none"


def _size(f: dict, duration: Optional[float]) -> Optional[int]:
    size = f.get("filesize") or f.get("filesize_approx")
    if not size and f.get("tbr") and duration:
        size = f["tbr"] * duration * 125  # kbit/s -> bytes
    return int(size) if size else None


def _pick_tiers(resolutions: list[int], limit: int = 3) -> list[int]:
    """1080, 720, 540, 480, 360 -> 1080, 720, 480 (each clearly smaller than the last)."""
    tiers: list[int] = []
    for r in sorted(set(resolutions), reverse=True):
        if not tiers or r < tiers[-1] * 0.75:
            tiers.append(r)
        if len(tiers) == limit:
            break
    return tiers


def _media_options(info: dict) -> tuple[list[dict], bool]:
    """The quality choices for one video (or sound-only post), and whether it is sound only."""
    formats = info.get("formats") or ([info] if info.get("url") else [])
    videos = [f for f in formats if _has_video(f)]
    sound_only = not videos and any(_has_audio(f) for f in formats)  # e.g. a Tumblr audio post
    if not videos and not (sound_only and settings.allow_audio and FFMPEG):
        raise VideoUnavailable("There's no downloadable video in that post.")
    duration = info.get("duration")
    if duration and duration > settings.max_duration:
        raise TooLarge(f"This video is too long. Grabbly downloads videos up to {settings.max_duration // 60} minutes.")

    best_audio = max(
        (f for f in formats if _has_audio(f) and not _has_video(f)),
        key=lambda f: f.get("abr") or f.get("tbr") or 0,
        default=None,
    )

    options = []
    tiers = _pick_tiers([r for r in (_short_side(f) for f in videos) if r])
    for res in tiers:
        candidates = [f for f in videos if (_short_side(f) or 0) <= res]
        top = max(candidates, key=lambda f: ((_short_side(f) or 0), f.get("tbr") or 0))
        size = _size(top, duration)
        if size and not _has_audio(top) and best_audio:
            size += _size(best_audio, duration) or 0
        options.append({"id": f"v{res}", "kind": "video", "format": "MP4", "quality": f"{res}p", "size": size})
    if not options and videos:
        options.append({"id": "best", "kind": "video", "format": "MP4", "quality": "Best quality", "size": None})
    if settings.allow_audio and FFMPEG and any(_has_audio(f) for f in formats):
        options.append({"id": "audio", "kind": "audio", "format": "MP3", "quality": "Audio only", "size": None})
    return options, sound_only


def _title(info: dict) -> str:
    title = re.sub(r"\s+", " ", (info.get("title") or info.get("description") or "").strip())
    if len(title) > 120:
        title = title[:117].rstrip() + "…"
    return title


def _uploader(info: dict) -> Optional[str]:
    return info.get("uploader") or info.get("channel") or info.get("uploader_id")


def summarize(info: dict, platform: Platform, url: str) -> dict:
    """Turn a yt-dlp info dict into what the website needs: the first video of the post."""
    info = _single_entry(info)
    options, sound_only = _media_options(info)
    duration = info.get("duration")
    return {
        "platform": platform.key,
        "platform_name": platform.name,
        "url": url,
        "title": _title(info) or f"{platform.name} {'audio' if sound_only else 'video'}",
        "uploader": _uploader(info),
        "duration": int(duration) if duration else None,
        "thumbnail": info.get("thumbnail"),
        "options": options,
    }


# --------------------------------------------------------------------------
# Every video and photo in a post (used by the Android app)
# --------------------------------------------------------------------------
def _int(value) -> Optional[int]:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _image_of(info: dict) -> tuple[Optional[dict], Optional[str]]:
    """The full-size picture of a photo post, and a smaller copy of it for previews."""
    pictures = [t for t in (info.get("thumbnails") or []) if isinstance(t, dict) and t.get("url")]
    if not pictures and info.get("thumbnail"):
        pictures = [{"url": info["thumbnail"]}]
    if not pictures:
        return None, None
    sized = [dict(t, width=_int(t.get("width")), height=_int(t.get("height"))) for t in pictures]
    sized = [t for t in sized if t["width"] and t["height"]]
    # Without sizes, yt-dlp lists pictures from worst to best
    best = max(sized, key=lambda t: t["width"] * t["height"]) if sized else pictures[-1]
    previews = sorted((t for t in sized if t["width"] >= 320), key=lambda t: t["width"])
    return best, (previews[0]["url"] if previews else best["url"])


def _photo_option(image: dict) -> dict:
    ext = os.path.splitext(urlsplit(image["url"]).path)[1].lower().lstrip(".")
    fmt = {"jpeg": "JPG", "jpg": "JPG", "png": "PNG", "webp": "WEBP", "gif": "GIF", "heic": "HEIC"}.get(ext, "JPG")
    w, h = _int(image.get("width")), _int(image.get("height"))
    return {"id": "photo", "kind": "photo", "format": fmt, "quality": f"{w} × {h}" if w and h else "Original", "size": None}


def _photo_item(index: int, image: dict, preview: Optional[str]) -> dict:
    return {
        "index": index, "kind": "photo", "thumbnail": preview or image["url"], "duration": None,
        "width": _int(image.get("width")), "height": _int(image.get("height")),
        "options": [_photo_option(image)],
        "_image": image["url"],  # removed before the answer goes out; used to download the photo
    }


def _summarize_item(entry: dict, platform: Platform, index: int) -> Optional[dict]:
    try:
        options, sound_only = _media_options(entry)
    except VideoUnavailable:
        options, sound_only = None, False
    if options:
        duration = entry.get("duration")
        return {
            "index": index, "kind": "audio" if sound_only else "video", "thumbnail": entry.get("thumbnail"),
            "duration": int(duration) if duration else None, "options": options,
        }
    if platform.key in PHOTO_PLATFORMS:
        image, preview = _image_of(entry)
        if image:
            return _photo_item(index, image, preview)
    return None


def summarize_items(info: dict, platform: Platform, url: str, extra_photos: tuple = ()) -> dict:
    """Like summarize, plus "items": every video and photo in the post, each with its own choices."""
    is_list = info.get("_type") in ("playlist", "multi_video")
    entries = [e for e in (info.get("entries") or []) if e] if is_list else [info]
    items: list[dict] = []
    first_error: Optional[GrabblyError] = None
    for position, entry in enumerate(entries, 1):
        try:
            item = _summarize_item(entry, platform, _int(entry.get("playlist_index")) or position)
        except GrabblyError as e:  # e.g. one video in the post is too long: offer the others
            first_error = first_error or e
            continue
        if item:
            items.append(item)
    for number, photo in enumerate(extra_photos, 1):
        items.append(_photo_item(X_PHOTO_INDEX + number, photo, photo.get("preview")))
    if not items:
        raise first_error or VideoUnavailable("There's no video or photo to download in that post.")
    items = items[:MAX_ITEMS]

    main = entries[0] if len(entries) == 1 else info
    caption = (info.get("description") or main.get("description") or "").strip() or None
    if caption and len(caption) > CAPTION_LIMIT:
        caption = caption[:CAPTION_LIMIT].rstrip() + "…"
    first = items[0]
    if first["kind"] == "photo":
        kind = "photos" if len(items) > 1 else "photo"
    else:
        kind = "post" if len(items) > 1 else ("audio" if first["kind"] == "audio" else "video")
    title = _title(main) or _title(info) or f"{platform.name} {kind}"
    if all(i["kind"] == "photo" for i in items) and title.startswith("Video by "):
        # yt-dlp names every Instagram post "Video by <user>", photos too
        title = ("Photos by " if len(items) > 1 else "Photo by ") + title[len("Video by "):]
    return {
        "platform": platform.key,
        "platform_name": platform.name,
        "url": url,
        "title": title,
        "uploader": _uploader(main) or _uploader(info),
        "duration": first["duration"],
        "thumbnail": first["thumbnail"],
        "options": first["options"],
        "caption": caption,
        "items": items,
    }


_TWEET_ID = re.compile(r"/status(?:es)?/(\d+)")


def x_photos(url: str) -> tuple[list[dict], Optional[str]]:
    """Photos in a post on X, and its text. yt-dlp only reports videos, so ask X's public
    embed endpoint (the one yt-dlp itself falls back to). Returns ([], None) when it can't."""
    match = _TWEET_ID.search(urlsplit(url).path)
    if not match or js_number_to_string is None:
        return [], None
    tweet_id = match.group(1)
    token = js_number_to_string((int(tweet_id) / 1e15) * math.pi, 36).translate(str.maketrans(dict.fromkeys("0.")))
    try:
        with httpx.Client(timeout=10, follow_redirects=False, headers={"User-Agent": "Googlebot"}) as client:
            resp = client.get("https://cdn.syndication.twimg.com/tweet-result", params={"id": tweet_id, "token": token})
        data = resp.json() if resp.status_code == 200 else {}
    except (httpx.HTTPError, ValueError):
        return [], None
    photos = []
    for detail in data.get("mediaDetails") or []:
        if not isinstance(detail, dict) or detail.get("type") != "photo":
            continue
        base = detail.get("media_url_https")
        if not base:
            continue
        size = detail.get("original_info") or {}
        photos.append({"url": f"{base}?name=orig", "preview": f"{base}?name=small",
                       "width": _int(size.get("width")), "height": _int(size.get("height"))})
    return photos, (data.get("text") or None)


def _lookup_items(url: str, platform: Platform, ydl_factory: YdlFactory) -> dict:
    """Looks up every video and photo in a post (with private "_image" links still in it)."""
    opts = _ydl_opts(platform, playlist_items=f"1:{MAX_ITEMS}", ignore_no_formats_error=True)

    def look_up() -> dict:
        with ydl_factory(opts) as ydl:
            return ydl.sanitize_info(ydl.extract_info(url, download=False))

    photos, text = [], None
    try:
        info = _retry_once(look_up)
    except DownloadError as e:
        if platform.key == "x":  # a post with only photos: yt-dlp finds nothing, X's embed does
            photos, text = x_photos(url)
        if not photos:
            log.info("info failed for %s: %s", url, e)
            raise VideoUnavailable(friendly_error(str(e))) from None
        info = {"_type": "playlist", "entries": []}
    else:
        if platform.key == "x":
            photos, text = x_photos(url)
    if text and not info.get("description"):
        info = dict(info, description=text)
    return summarize_items(info, platform, url, tuple(photos))


def fetch_info(
    raw: str,
    *,
    items: bool = False,
    peek: Optional[Peek] = None,
    ydl_factory: YdlFactory = yt_dlp.YoutubeDL,
) -> dict:
    """Look up a video without downloading it. With items=True, also every video and photo in the post."""
    url, platform = resolve(extract_url(raw), peek)
    if items:
        summary = _lookup_items(url, platform, ydl_factory)
        for item in summary["items"]:
            item.pop("_image", None)
        return summary

    def look_up() -> dict:
        with ydl_factory(_ydl_opts(platform)) as ydl:
            return ydl.sanitize_info(ydl.extract_info(url, download=False))

    try:
        info = _retry_once(look_up)
    except DownloadError as e:
        log.info("info failed for %s: %s", url, e)
        raise VideoUnavailable(friendly_error(str(e))) from None
    return summarize(info, platform, url)


# --------------------------------------------------------------------------
# Downloading
# --------------------------------------------------------------------------
MEDIA_TYPES = {
    ".mp4": "video/mp4", ".m4v": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm",
    ".mkv": "video/x-matroska", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".opus": "audio/ogg",
}


@dataclass
class Download:
    path: Path
    filename: str
    media_type: str
    tmpdir: Path

    def cleanup(self) -> None:
        shutil.rmtree(self.tmpdir, ignore_errors=True)


def format_opts(option: str) -> dict:
    """yt-dlp options for a download choice. Video prefers H.264/AAC MP4 so it plays
    in QuickTime, Photos and Windows Media Player, not just VLC."""
    if option == "audio":
        return {
            "format": "ba/b",
            "postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}],
        }
    sort = ["vcodec:h264", "acodec:aac", "ext:mp4:m4a"]
    if option.startswith("v"):
        sort.insert(0, f"res:{int(option[1:])}")
    if not FFMPEG:
        # Without ffmpeg separate video and audio can't be joined: pick files that have both
        return {"format": "b", "format_sort": sort}
    return {"format": "bv*+ba/b", "format_sort": sort, "merge_output_format": "mp4"}


def safe_filename(title: str, ext: str, fallback: str = "video") -> str:
    name = unicodedata.normalize("NFKC", title or "")
    name = re.sub(r"[\x00-\x1f\x7f\\/:*?\"<>|#%]+", " ", name)
    name = re.sub(r"\s+", " ", name).strip(" .-_")
    if len(name) > 80:
        name = name[:80].rstrip(" .-_")
    return f"{name or fallback}{ext}"


def ensure_tmp_root() -> Path:
    settings.tmp_root.mkdir(parents=True, exist_ok=True)
    return settings.tmp_root


def cleanup_stale(max_age: int = 3600) -> int:
    """Remove leftover download folders (for example after a crash)."""
    removed = 0
    root = settings.tmp_root
    if not root.is_dir():
        return 0
    cutoff = time.time() - max_age
    for child in root.iterdir():
        try:
            if child.is_dir() and child.stat().st_mtime < cutoff:
                shutil.rmtree(child, ignore_errors=True)
                removed += 1
        except OSError:
            pass
    return removed


def fetch_image(image_url: str, folder: Path) -> tuple[Path, str]:
    """Saves a photo from a platform's image servers into folder. Returns its path and type."""
    parts = urlsplit(image_url)
    host = (parts.hostname or "").lower()
    if parts.scheme != "https" or not any(host == h or host.endswith("." + h) for h in IMAGE_HOSTS):
        raise VideoUnavailable("We couldn't get this photo.")
    try:
        with httpx.Client(timeout=20, follow_redirects=False, headers={"User-Agent": USER_AGENT}) as client:
            with client.stream("GET", image_url) as resp:
                ctype = resp.headers.get("content-type", "").split(";")[0].strip().lower()
                if resp.status_code != 200 or not ctype.startswith("image/"):
                    raise VideoUnavailable("We couldn't get this photo. It may have been removed.")
                path = folder / f"photo{IMAGE_TYPES.get(ctype, '.jpg')}"
                size = 0
                with path.open("wb") as out:
                    for chunk in resp.iter_bytes():
                        size += len(chunk)
                        if size > settings.max_bytes:
                            raise TooLarge("This photo is too large to download.")
                        out.write(chunk)
    except httpx.HTTPError:
        raise VideoUnavailable("We couldn't get this photo. Check your connection and try again.") from None
    return path, ctype


def _download_photo(url: str, platform: Platform, index: int, ydl_factory: YdlFactory) -> Download:
    summary = _lookup_items(url, platform, ydl_factory)
    items = summary["items"]
    position, chosen = next(((n, i) for n, i in enumerate(items, 1) if i["index"] == index and i["kind"] == "photo"),
                            (0, None))
    if chosen is None:
        raise VideoUnavailable("We couldn't find that photo in the post. Look the link up again.")
    tmp = Path(tempfile.mkdtemp(prefix="dl-", dir=ensure_tmp_root()))
    try:
        path, media_type = fetch_image(chosen["_image"], tmp)
        title = summary["title"] + (f" {position}" if len(items) > 1 else "")
        return Download(
            path=path,
            filename=safe_filename(title, path.suffix, fallback=f"{platform.name} photo"),
            media_type=media_type,
            tmpdir=tmp,
        )
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise


def download(
    raw: str,
    option: str = "best",
    *,
    item: Optional[int] = None,
    peek: Optional[Peek] = None,
    ydl_factory: YdlFactory = yt_dlp.YoutubeDL,
) -> Download:
    """Download a video (or with option "photo", a photo) to a temporary folder. The caller must
    call .cleanup(). item picks one video or photo of a post with several (numbered from 1)."""
    if not OPTION_RE.fullmatch(option or ""):
        raise InvalidLink("Unknown download option.")
    if option == "audio" and not (settings.allow_audio and FFMPEG):
        raise InvalidLink("Audio downloads aren't available.")
    if item is not None and not 1 <= item <= X_PHOTO_INDEX + MAX_ITEMS:
        raise InvalidLink("Unknown item.")

    url, platform = resolve(extract_url(raw), peek)
    if option == "photo":
        return _download_photo(url, platform, item or 1, ydl_factory)

    tmp = Path(tempfile.mkdtemp(prefix="dl-", dir=ensure_tmp_root()))
    try:
        opts = _ydl_opts(
            platform,
            outtmpl=str(tmp / "media.%(ext)s"),
            max_filesize=settings.max_bytes,
            **format_opts(option),
            **({"playlist_items": str(item)} if item else {}),
        )

        def fetch() -> dict:
            with ydl_factory(opts) as ydl:
                found = _single_entry(ydl.extract_info(url, download=False))
                duration = found.get("duration")
                if duration and duration > settings.max_duration:
                    raise TooLarge(f"This video is too long. Grabbly downloads videos up to {settings.max_duration // 60} minutes.")
                return ydl.process_ie_result(found, download=True)

        def empty_tmp() -> None:
            for leftover in tmp.iterdir():
                if leftover.is_dir():
                    shutil.rmtree(leftover, ignore_errors=True)
                else:
                    leftover.unlink(missing_ok=True)

        info = _retry_once(fetch, before_retry=empty_tmp)

        files = [p for p in tmp.iterdir() if p.is_file() and not p.name.endswith((".part", ".ytdl", ".temp"))]
        if not files:
            # yt-dlp skips files over max_filesize without raising
            raise TooLarge(f"This video is too large. Grabbly downloads files up to {settings.max_bytes // (1024 * 1024)} MB.")
        path = max(files, key=lambda p: p.stat().st_size)
        if path.stat().st_size > settings.max_bytes:
            raise TooLarge(f"This video is too large. Grabbly downloads files up to {settings.max_bytes // (1024 * 1024)} MB.")

        ext = path.suffix.lower()
        name = f"{platform.name} video"
        return Download(
            path=path,
            filename=safe_filename(info.get("title") or "", ext, fallback=name),
            media_type=MEDIA_TYPES.get(ext, "application/octet-stream"),
            tmpdir=tmp,
        )
    except DownloadError as e:
        shutil.rmtree(tmp, ignore_errors=True)
        log.info("download failed for %s: %s", url, e)
        raise VideoUnavailable(friendly_error(str(e))) from None
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
