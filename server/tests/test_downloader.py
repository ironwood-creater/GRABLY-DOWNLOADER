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


# ---------------------------------------------------------------- Twitch and Tumblr
@pytest.mark.parametrize("raw,platform", [
    ("https://clips.twitch.tv/FunnyClipName", "twitch"),
    ("https://www.twitch.tv/videos/123456789", "twitch"),
    ("https://staff.tumblr.com/post/123456789", "tumblr"),
    ("https://www.tumblr.com/staff/123456789", "tumblr"),
])
def test_accepts_twitch_and_tumblr(raw, platform):
    _, p = d.check_url(d.extract_url(raw))
    assert p.key == platform


def test_tumblr_short_links_are_followed():
    assert d._needs_resolving("https://tmblr.co/AbCd")


@pytest.mark.parametrize("url", [
    "https://www.youtube.com/watch?v=1",
    "https://www.reddit.com/r/videos/comments/abc/x/",
    "https://vimeo.com/76979871",
    "https://www.dailymotion.com/video/x8abcd",
    "https://www.snapchat.com/spotlight/abc",
    "https://www.linkedin.com/posts/someone-7012345678901234567-AbCd",
    "https://soundcloud.com/artist/track",
    "https://www.bilibili.com/video/BV1xx411c7mD",
])
def test_sites_grabbly_doesnt_support_are_refused(url):
    with pytest.raises(d.UnsupportedSite) as e:
        d.check_url(url)
    assert e.value.message.endswith("It works with TikTok, Instagram, Facebook, X, Pinterest, Twitch and Tumblr.")


def test_sound_only_posts_offer_mp3():
    info = {"title": "Lo-fi beats", "duration": 120,
            "formats": [{"vcodec": "none", "acodec": "opus", "abr": 64}, {"vcodec": "none", "acodec": "mp3", "abr": 128}]}
    tumblr = next(p for p in d.PLATFORMS if p.key == "tumblr")
    out = d.summarize(info, tumblr, "https://staff.tumblr.com/post/1")
    assert [o["id"] for o in out["options"]] == ["audio"]


# ---------------------------------------------------------------- posts with several videos and photos (app)
def photo_entry(index, sizes=((150, 150), (480, 600), (1080, 1350)), host="scontent.cdninstagram.com"):
    return {
        "playlist_index": index, "formats": [],
        "thumbnails": [{"url": f"https://{host}/p{index}_{w}.jpg", "width": w, "height": h} for w, h in sizes],
    }


CAROUSEL = {
    "_type": "playlist", "title": "Post by ava.travels", "channel": "ava.travels",
    "description": "Golden hour #roadtrip #sunset", "entries": [
        dict(SAMPLE, playlist_index=1, thumbnail="https://cdn.example/v1.jpg"),
        photo_entry(2),
        None,
        photo_entry(3, sizes=((640, 640),)),
    ],
}


def test_items_lists_every_video_and_photo_in_a_post():
    out = d.summarize_items(CAROUSEL, d.platform_for_host("instagram.com"), "u")
    assert [(i["index"], i["kind"]) for i in out["items"]] == [(1, "video"), (2, "photo"), (3, "photo")]
    photo = out["items"][1]
    assert photo["options"] == [{"id": "photo", "kind": "photo", "format": "JPG", "quality": "1080 × 1350", "size": None}]
    assert photo["_image"].endswith("p2_1080.jpg")       # the full-size picture is downloaded
    assert photo["thumbnail"].endswith("p2_480.jpg")     # a smaller one is the preview
    assert out["caption"] == "Golden hour #roadtrip #sunset"
    assert out["title"] == "Post by ava.travels"
    # The first item also fills the fields older clients read
    assert out["options"] == out["items"][0]["options"]


def test_single_photo_post():
    out = d.summarize_items(photo_entry(1) | {"title": "Lake", "description": "Calm"},
                            d.platform_for_host("pinterest.com"), "u")
    assert len(out["items"]) == 1 and out["items"][0]["kind"] == "photo"
    assert out["title"] == "Lake" and out["caption"] == "Calm"


def test_photos_are_only_offered_where_photo_posts_exist():
    with pytest.raises(d.VideoUnavailable):
        d.summarize_items(photo_entry(1), d.platform_for_host("tiktok.com"), "u")


def test_a_too_long_video_in_a_post_is_skipped(monkeypatch):
    monkeypatch.setattr(d.settings, "max_duration", 60)
    post = dict(CAROUSEL, entries=[dict(SAMPLE, duration=61, playlist_index=1), photo_entry(2)])
    out = d.summarize_items(post, d.platform_for_host("instagram.com"), "u")
    assert [i["kind"] for i in out["items"]] == ["photo"]
    with pytest.raises(d.TooLarge):
        d.summarize_items(dict(SAMPLE, duration=61), d.platform_for_host("instagram.com"), "u")


def test_long_captions_are_shortened():
    out = d.summarize_items(dict(SAMPLE, description="a" * 3000), d.PLATFORMS[0], "u")
    assert len(out["caption"]) == d.CAPTION_LIMIT + 1


def test_fetch_info_items_asks_for_the_whole_post_and_hides_image_links():
    out = d.fetch_info("https://www.instagram.com/p/Cx/", items=True, ydl_factory=lambda o: FakeYDL(o, CAROUSEL))
    assert FakeYDL.last_opts["playlist_items"] == "1:20"
    assert FakeYDL.last_opts["ignore_no_formats_error"] is True
    assert len(out["items"]) == 3
    assert all("_image" not in i for i in out["items"])


def test_website_lookups_are_unchanged():
    out = d.fetch_info("https://www.instagram.com/p/Cx/", ydl_factory=lambda o: FakeYDL(o, CAROUSEL))
    assert "items" not in out and "caption" not in out
    assert FakeYDL.last_opts["playlist_items"] == "1"


X_PHOTOS = [{"url": "https://pbs.twimg.com/media/A.jpg?name=orig", "preview": "https://pbs.twimg.com/media/A.jpg?name=small",
             "width": 2048, "height": 1536}]


def test_x_posts_with_only_photos(monkeypatch):
    monkeypatch.setattr(d, "x_photos", lambda url: (X_PHOTOS, "Four more years."))
    out = d.fetch_info("https://x.com/a/status/266031293945503744", items=True,
                       ydl_factory=lambda o: FakeYDL(o, error="ERROR: [twitter] 1: No video could be found in this tweet"))
    assert [(i["index"], i["kind"]) for i in out["items"]] == [(101, "photo")]
    assert out["caption"] == "Four more years."
    assert out["items"][0]["options"][0]["quality"] == "2048 × 1536"


def test_x_errors_still_show_when_there_are_no_photos(monkeypatch):
    monkeypatch.setattr(d, "x_photos", lambda url: ([], None))
    with pytest.raises(d.VideoUnavailable) as exc:
        d.fetch_info("https://x.com/a/status/1", items=True,
                     ydl_factory=lambda o: FakeYDL(o, error="ERROR: [twitter] 1: This account has been suspended"))
    assert "suspended" in exc.value.message


class FakeHttpClient:
    """Stands in for httpx.Client in x_photos."""
    def __init__(self, *a, **k):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def get(self, url, params=None):
        FakeHttpClient.params = params

        class Resp:
            status_code = 200

            @staticmethod
            def json():
                return {"text": "Hi", "mediaDetails": [
                    {"type": "photo", "media_url_https": "https://pbs.twimg.com/media/A.jpg", "original_info": {"width": 4, "height": 3}},
                    {"type": "video", "media_url_https": "https://pbs.twimg.com/ext/B.jpg"},
                    "junk",
                ]}
        return Resp()


def test_x_photos_reads_the_embed_answer(monkeypatch):
    monkeypatch.setattr(d.httpx, "Client", FakeHttpClient)
    photos, text = d.x_photos("https://x.com/a/status/20")
    assert text == "Hi"
    assert photos == [{"url": "https://pbs.twimg.com/media/A.jpg?name=orig", "preview": "https://pbs.twimg.com/media/A.jpg?name=small",
                       "width": 4, "height": 3}]
    assert FakeHttpClient.params["id"] == "20" and FakeHttpClient.params["token"]
    assert d.x_photos("https://x.com/a/likes") == ([], None)


def test_photo_download_saves_the_full_size_picture(monkeypatch, tmp_path):
    monkeypatch.setattr(d.settings, "tmp_root", tmp_path)
    fetched = []

    def fake_fetch(url, folder):
        fetched.append(url)
        path = folder / "photo.jpg"
        path.write_bytes(b"\xff\xd8\xff")
        return path, "image/jpeg"

    monkeypatch.setattr(d, "fetch_image", fake_fetch)
    result = d.download("https://www.instagram.com/p/Cx/", "photo", item=2, ydl_factory=lambda o: FakeYDL(o, CAROUSEL))
    assert fetched == ["https://scontent.cdninstagram.com/p2_1080.jpg"]
    assert result.media_type == "image/jpeg"
    assert result.filename == "Post by ava.travels 2.jpg"
    result.cleanup()
    assert not result.tmpdir.exists()
    with pytest.raises(d.VideoUnavailable):  # item 1 is a video, not a photo
        d.download("https://www.instagram.com/p/Cx/", "photo", item=1, ydl_factory=lambda o: FakeYDL(o, CAROUSEL))


@pytest.mark.parametrize("url", ["https://evil.example/a.jpg", "http://pbs.twimg.com/a.jpg", "https://twimg.com.evil.example/a.jpg"])
def test_photos_only_come_from_the_platforms_image_servers(url, tmp_path):
    with pytest.raises(d.VideoUnavailable):
        d.fetch_image(url, tmp_path)


@pytest.mark.parametrize("item", [0, -1, 500])
def test_download_rejects_unknown_items(item):
    with pytest.raises(d.InvalidLink):
        d.download("https://www.instagram.com/p/Cx/", "v720", item=item)


def test_one_video_of_a_post_can_be_downloaded(monkeypatch, tmp_path):
    monkeypatch.setattr(d.settings, "tmp_root", tmp_path)
    with pytest.raises(d.VideoUnavailable):
        d.download("https://www.instagram.com/p/Cx/", "v720", item=3, ydl_factory=lambda o: FakeYDL(o, error="ERROR: removed"))
    assert FakeYDL.last_opts["playlist_items"] == "3"
