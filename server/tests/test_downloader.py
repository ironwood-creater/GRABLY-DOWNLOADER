import pytest

from server import downloader as d


# ---------------------------------------------------------------- URL checks
@pytest.mark.parametrize("raw,platform,url", [
    ("https://www.tiktok.com/@ava/video/7311", "tiktok", "https://www.tiktok.com/@ava/video/7311"),
    ("tiktok.com/@ava/video/7311", "tiktok", "https://tiktok.com/@ava/video/7311"),
    ("Look! https://www.instagram.com/reel/Cxyz/?igsh=1 so good", "instagram", "https://www.instagram.com/reel/Cxyz/?igsh=1"),
    ("https://m.facebook.com/watch/?v=123", "facebook", "https://m.facebook.com/watch/?v=123"),
    ("http://twitter.com/a/status/1#frag", "x", "https://twitter.com/a/status/1"),
    ("https://x.com/a/status/1", "x", "https://x.com/a/status/1"),
    ("https://www.pinterest.co.uk/pin/123/", "pinterest", "https://www.pinterest.co.uk/pin/123/"),
    ("https://www.pinterest.com/pin/123/", "pinterest", "https://www.pinterest.com/pin/123/"),
])
def test_accepts_supported_links(raw, platform, url):
    clean, p = d.check_url(d.extract_url(raw))
    assert p.key == platform
    assert clean == url


@pytest.mark.parametrize("raw", [
    "https://youtube.com/watch?v=1",
    "https://example.com/tiktok.com/video",
    "https://tiktok.com.evil.com/@a/video/1",
    "https://eviltiktok.com/@a/video/1",
    "https://127.0.0.1/video",
    "https://pinterest.evil.com/pin/1",
])
def test_rejects_other_sites(raw):
    with pytest.raises(d.UnsupportedSite):
        d.check_url(d.extract_url(raw))


@pytest.mark.parametrize("raw", [
    "", "   ", "hello there", "https://user:pw@tiktok.com/@a/video/1",
    "https://tiktok.com:8080/@a/video/1", "x" * 3000,
])
def test_rejects_invalid_links(raw):
    with pytest.raises(d.InvalidLink):
        d.check_url(d.extract_url(raw))


def test_rejects_non_web_schemes():
    with pytest.raises(d.InvalidLink):
        d.check_url("ftp://tiktok.com/x")


# ---------------------------------------------------------------- short links
def fake_peek(routes):
    calls = []

    def peek(url):
        calls.append(url)
        return routes.get(url, (200, None))

    peek.calls = calls
    return peek


def test_follows_short_links_to_the_post():
    peek = fake_peek({
        "https://t.co/abc": (301, "https://x.com/ava/status/42"),
    })
    url, p = d.resolve("https://t.co/abc", peek)
    assert (url, p.key) == ("https://x.com/ava/status/42", "x")


def test_follows_relative_and_multi_hop_redirects():
    peek = fake_peek({
        "https://pin.it/3abc": (302, "https://www.pinterest.com/url_shortener/3abc/redirect/"),
    })
    url, p = d.resolve("https://pin.it/3abc", peek)
    assert p.key == "pinterest"
    assert url == "https://www.pinterest.com/url_shortener/3abc/redirect/"


def test_short_link_to_another_site_is_refused_before_fetching_it():
    peek = fake_peek({"https://t.co/evil": (301, "http://169.254.169.254/latest/meta-data/")})
    with pytest.raises(d.UnsupportedSite):
        d.resolve("https://t.co/evil", peek)
    assert peek.calls == ["https://t.co/evil"]  # the metadata address was never requested


def test_redirect_loops_stop():
    peek = fake_peek({"https://t.co/loop": (301, "https://t.co/loop")})
    with pytest.raises(d.VideoUnavailable):
        d.resolve("https://t.co/loop", peek)
    assert len(peek.calls) == d.MAX_REDIRECTS


def test_regular_links_are_not_fetched_during_validation():
    peek = fake_peek({})
    d.resolve("https://www.tiktok.com/@ava/video/1", peek)
    assert peek.calls == []


# ---------------------------------------------------------------- summarize
SAMPLE = {
    "title": "Golden hour drive   along the coast",
    "uploader": "ava.travels",
    "duration": 42,
    "thumbnail": "https://cdn.example/thumb.jpg",
    "formats": [
        {"format_id": "a", "vcodec": "none", "acodec": "mp4a", "abr": 128, "filesize": 600_000},
        {"format_id": "360", "vcodec": "avc1", "acodec": "none", "width": 640, "height": 360, "tbr": 600},
        {"format_id": "540", "vcodec": "avc1", "acodec": "none", "width": 960, "height": 540, "tbr": 1200},
        {"format_id": "720", "vcodec": "avc1", "acodec": "none", "width": 1280, "height": 720, "filesize": 9_000_000},
        {"format_id": "1080", "vcodec": "vp9", "acodec": "none", "width": 1920, "height": 1080, "filesize": 15_000_000},
    ],
}


def test_summarize_offers_clear_quality_steps():
    out = d.summarize(SAMPLE, d.PLATFORMS[0], "https://www.tiktok.com/@a/video/1")
    assert [o["quality"] for o in out["options"] if o["kind"] == "video"] == ["1080p", "720p", "360p"]
    assert out["options"][1]["size"] == 9_600_000  # video + separate audio
    assert out["title"] == "Golden hour drive along the coast"
    assert out["platform_name"] == "TikTok"
    assert out["duration"] == 42


def test_portrait_videos_are_labelled_by_short_side():
    info = {"title": "t", "formats": [{"vcodec": "h264", "acodec": "aac", "width": 1080, "height": 1920}]}
    out = d.summarize(info, d.PLATFORMS[0], "u")
    assert out["options"][0]["quality"] == "1080p"


def test_audio_option_follows_setting(monkeypatch):
    monkeypatch.setattr(d.settings, "allow_audio", False)
    assert all(o["kind"] == "video" for o in d.summarize(SAMPLE, d.PLATFORMS[0], "u")["options"])
    monkeypatch.setattr(d.settings, "allow_audio", True)
    assert d.summarize(SAMPLE, d.PLATFORMS[0], "u")["options"][-1]["id"] == "audio"


def test_playlist_uses_first_video():
    info = {"_type": "playlist", "entries": [None, dict(SAMPLE, title="first")]}
    assert d.summarize(info, d.PLATFORMS[1], "u")["title"] == "first"


def test_image_only_posts_are_rejected():
    with pytest.raises(d.VideoUnavailable):
        d.summarize({"title": "pic", "formats": [{"vcodec": "none", "acodec": "none"}]}, d.PLATFORMS[4], "u")


def test_too_long_videos_are_rejected(monkeypatch):
    monkeypatch.setattr(d.settings, "max_duration", 60)
    with pytest.raises(d.TooLarge):
        d.summarize(dict(SAMPLE, duration=61), d.PLATFORMS[0], "u")


def test_unknown_resolution_gets_single_best_option():
    out = d.summarize({"title": "t", "url": "https://cdn/x.mp4"}, d.PLATFORMS[2], "u")
    assert [o["id"] for o in out["options"]] == ["best", "audio"]  # audio is on by default


# ---------------------------------------------------------------- fetch_info
class FakeYDL:
    last_opts = None

    def __init__(self, opts, info=None, error=None):
        FakeYDL.last_opts = opts
        self.info, self.error = info, error

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def extract_info(self, url, download=False):
        if self.error:
            raise d.DownloadError(self.error)
        return self.info

    def sanitize_info(self, info):
        return info


def test_fetch_info_limits_yt_dlp_to_the_platform():
    out = d.fetch_info("https://www.instagram.com/reel/Cx/", ydl_factory=lambda o: FakeYDL(o, SAMPLE))
    assert out["platform"] == "instagram"
    assert FakeYDL.last_opts["allowed_extractors"] == ["instagram"]
    assert FakeYDL.last_opts["noplaylist"] is True


@pytest.mark.parametrize("message,expected", [
    ("ERROR: [Instagram] Cx: This content is private", "private"),
    ("ERROR: [TikTok] 1: Requested content is not available, rate-limit reached or login required", "login"),
    ("ERROR: Unsupported URL: https://www.tiktok.com/@ava", "single video"),
    ("ERROR: something odd happened", "Check the link"),
])
def test_yt_dlp_errors_become_friendly(message, expected):
    with pytest.raises(d.VideoUnavailable) as exc:
        d.fetch_info("https://www.tiktok.com/@a/video/1", ydl_factory=lambda o: FakeYDL(o, error=message))
    assert expected in exc.value.message


# ---------------------------------------------------------------- helpers
def test_safe_filename():
    assert d.safe_filename('Golden: "Hour" / Drive?', ".mp4") == "Golden Hour Drive.mp4"
    assert d.safe_filename("", ".mp4", fallback="TikTok video") == "TikTok video.mp4"
    assert d.safe_filename("Café ☕ vibes", ".mp4") == "Café ☕ vibes.mp4"
    assert len(d.safe_filename("x" * 300, ".mp4")) == 84


def test_format_opts_prefer_compatible_mp4():
    opts = d.format_opts("v720")
    assert opts["format_sort"][:2] == ["res:720", "vcodec:h264"]
    assert opts["merge_output_format"] == "mp4"
    assert d.format_opts("audio")["postprocessors"][0]["preferredcodec"] == "mp3"


@pytest.mark.parametrize("option", ["", "v1", "x720", "best;rm", "audio2"])
def test_download_rejects_unknown_options(option):
    with pytest.raises(d.InvalidLink):
        d.download("https://www.tiktok.com/@a/video/1", option)


# ---------------------------------------------------------------- retries
def test_passing_errors_are_retried_once(monkeypatch):
    monkeypatch.setattr(d, "RETRY_DELAY", 0)
    calls, cleaned = [], []

    def flaky():
        calls.append(1)
        if len(calls) == 1:
            raise d.DownloadError("ERROR: [TikTok] 123: Unexpected response from webpage request")
        return "ok"

    assert d._retry_once(flaky, before_retry=lambda: cleaned.append(1)) == "ok"
    assert len(calls) == 2 and cleaned == [1]


def test_lasting_errors_are_not_retried(monkeypatch):
    monkeypatch.setattr(d, "RETRY_DELAY", 0)
    calls = []

    def private():
        calls.append(1)
        raise d.DownloadError("ERROR: This video is private")

    with pytest.raises(d.DownloadError):
        d._retry_once(private)
    assert len(calls) == 1


def test_a_second_failure_is_reported(monkeypatch):
    monkeypatch.setattr(d, "RETRY_DELAY", 0)

    def always():
        raise d.DownloadError("ERROR: Read timed out")

    with pytest.raises(d.DownloadError):
        d._retry_once(always)


@pytest.mark.parametrize("message,expected", [
    ("ERROR: [twitter] 1694928419052458133: Suspended", "suspended"),
    ("ERROR: No suitable extractor found for URL https://x.com/i/broadcasts/1rmxPMjLzAXKN", "live streams"),
])
def test_more_friendly_errors(message, expected):
    assert expected in d.friendly_error(message)
