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

log = logging.getLogger("grably.downloader")


# --------------------------------------------------------------------------
# Settings (environment variables)
# --------------------------------------------------------------------------
@dataclass
class Settings:
    max_duration: int = 3 * 60 * 60          # seconds
    max_bytes: int = 500 * 1024 * 1024       # largest file we'll serve
    allow_audio: bool = True                 # offer an "MP3 · Audio only" option
    tmp_root: Path = Path(tempfile.gettempdir()) / "grably"
    socket_timeout: int = 20

    @classmethod
    def from_env(cls) -> "Settings":
        s = cls()
        s.max_duration = int(os.environ.get("GRABLY_MAX_DURATION", s.max_duration))
        # Vercel Functions have about 500 MB of temporary disk, and merging needs room for both parts
        default_mb = 200 if os.environ.get("VERCEL") == "1" else 500
        s.max_bytes = int(os.environ.get("GRABLY_MAX_MB", default_mb)) * 1024 * 1024
        s.allow_audio = os.environ.get("GRABLY_AUDIO", "1").lower() in ("1", "true", "yes")
        s.tmp_root = Path(os.environ.get("GRABLY_TMP", s.tmp_root))
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
        copy = Path(tempfile.gettempdir()) / "grably-ffmpeg"
        if not copy.exists():
            shutil.copyfile(exe, copy)
        copy.chmod(0o755)
        exe = str(copy)
    return exe


FFMPEG = find_ffmpeg()
if FFMPEG is None:
    log.warning("ffmpeg not found: only single-file formats are offered and MP3 downloads are off")

USER_AGENT = "Mozilla/5.0 (compatible; GrablyBot/1.0)"
MAX_REDIRECTS = 5
OPTION_RE = re.compile(r"best|audio|v\d{3,4}")


# --------------------------------------------------------------------------
# Errors shown to the user
# --------------------------------------------------------------------------
class GrablyError(Exception):
    status = 400

    def __init__(self, message: str, status: Optional[int] = None):
        super().__init__(message)
        self.message = message
        if status is not None:
            self.status = status


class InvalidLink(GrablyError):
    status = 400


class UnsupportedSite(GrablyError):
    status = 400


class VideoUnavailable(GrablyError):
    status = 422


class TooLarge(GrablyError):
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


def summarize(info: dict, platform: Platform, url: str) -> dict:
    """Turn a yt-dlp info dict into what the website needs."""
    info = _single_entry(info)
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

    title = (info.get("title") or info.get("description") or "").strip()
    title = re.sub(r"\s+", " ", title)
    if len(title) > 120:
        title = title[:117].rstrip() + "…"
    uploader = info.get("uploader") or info.get("channel") or info.get("uploader_id")
    return {
        "platform": platform.key,
        "platform_name": platform.name,
        "url": url,
        "title": title or f"{platform.name} {'audio' if sound_only else 'video'}",
        "uploader": uploader,
        "duration": int(duration) if duration else None,
        "thumbnail": info.get("thumbnail"),
        "options": options,
    }


def fetch_info(raw: str, *, peek: Optional[Peek] = None, ydl_factory: YdlFactory = yt_dlp.YoutubeDL) -> dict:
    """Look up a video without downloading it."""
    url, platform = resolve(extract_url(raw), peek)
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


def download(
    raw: str,
    option: str = "best",
    *,
    peek: Optional[Peek] = None,
    ydl_factory: YdlFactory = yt_dlp.YoutubeDL,
) -> Download:
    """Download a video to a temporary folder. The caller must call .cleanup()."""
    if not OPTION_RE.fullmatch(option or ""):
        raise InvalidLink("Unknown download option.")
    if option == "audio" and not (settings.allow_audio and FFMPEG):
        raise InvalidLink("Audio downloads aren't available.")

    url, platform = resolve(extract_url(raw), peek)

    tmp = Path(tempfile.mkdtemp(prefix="dl-", dir=ensure_tmp_root()))
    try:
        opts = _ydl_opts(
            platform,
            outtmpl=str(tmp / "media.%(ext)s"),
            max_filesize=settings.max_bytes,
            **format_opts(option),
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
