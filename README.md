# Grably: website and web downloader

The website for **Grably**. It downloads videos from TikTok, Instagram, Facebook, X and Pinterest right in the browser, so it works on any laptop, PC or Mac. The phone app for iPhone and Android is coming soon.

- **Frontend**: plain HTML, CSS and JavaScript (`index.html`, `assets/`), no build step.
- **Backend**: a small Python server (`server/`) built on [FastAPI](https://fastapi.tiangolo.com/) and [yt-dlp](https://github.com/yt-dlp/yt-dlp). It looks up videos and sends the files to the browser.

## How downloading works

1. The visitor pastes a link. The page sends it to `POST /api/info`.
2. The server checks the link is from a supported site, asks yt-dlp for the video's details, and returns the title, thumbnail, length and available qualities.
3. The visitor picks a quality. The page calls `GET /api/download`; the server downloads the video with yt-dlp (merging video and audio with ffmpeg when needed), sends it as an MP4, then deletes its temporary copy.
4. The page shows live progress and saves the file to the visitor's Downloads folder.

Videos are saved as MP4, preferring H.264/AAC so they play in QuickTime, Photos and Windows Media Player as well as VLC.

**Safety measures:**
- Only TikTok, Instagram, Facebook, X/Twitter and Pinterest links are accepted.
- Short links (t.co, pin.it, fb.watch, vm.tiktok.com…) are followed one hop at a time, and every hop must stay on one of those sites. yt-dlp is restricted to those sites' extractors. Together these stop the server from being used to fetch arbitrary addresses.
- Requests are rate-limited per visitor, and concurrent downloads are capped.
- Files are limited by size and length. Thumbnails are only proxied for URLs the server signed itself.
- Only `index.html` and `/assets` are served publicly, never the server code.

If the API can't be reached (for example when `index.html` is opened as a file or hosted on a static host), the page falls back to checking whether a link is supported and says downloads aren't available right now.

## Project structure

```
index.html                 The page
assets/css/styles.css      All styles (teal-on-navy theme variables at the top)
assets/js/main.js          Downloader UI, mobile menu, demos
assets/img/                Logo, favicon, Apple touch icon, social preview image
server/app.py              Web server: API routes, rate limits, serves the website
server/downloader.py       Link checks, yt-dlp lookup and download
server/tests/              Tests (pytest)
Dockerfile                 Production image (Python + ffmpeg)
```

## Run it locally

You need Python 3.10+ and [ffmpeg](https://ffmpeg.org/download.html) (macOS: `brew install ffmpeg`; Windows: `winget install ffmpeg`; Ubuntu: `sudo apt install ffmpeg`).

```bash
python3 -m venv .venv
```

```bash
source .venv/bin/activate
```

On Windows, activate with `.venv\Scripts\activate` instead.

```bash
pip install -r server/requirements-dev.txt
```

```bash
uvicorn server.app:app --reload
```

Then open http://localhost:8000.

Run the tests:

```bash
pytest server/tests
```

The tests include a real yt-dlp + ffmpeg download of a locally served clip; they skip that part if ffmpeg isn't installed.

## Deploy

The site now needs a server, so static-only hosts such as GitHub Pages can't run the downloader. Any host that runs a Docker container works: Render, Railway, Fly.io, Google Cloud Run, or your own VPS.

1. Create a new web service from this repository and choose **Docker** (the `Dockerfile` is at the root).
2. The container listens on `$PORT` (default 8000); most hosts set this automatically.
3. Set `GRABLY_SECRET` to a long random string so thumbnail links stay valid across restarts and multiple instances.

Things to plan for:

- **Keep yt-dlp current.** Sites change often and yt-dlp ships fixes every few weeks. Redeploy regularly (`requirements.txt` asks for the latest yt-dlp at or above the pinned version).
- **Bandwidth.** Every download passes through your server, so pick a host whose data-transfer pricing fits your expected traffic.
- **Blocking.** Instagram and TikTok sometimes rate-limit or block requests from data-center IPs, especially at volume. Some downloads may fail with "needs a login" or "limiting requests" even for public videos.
- **Private content.** Private videos and anything behind a login can't be downloaded. This is intentional.

### Configuration

| Variable | Default | What it does |
| --- | --- | --- |
| `PORT` | `8000` | Port the server listens on (Docker) |
| `GRABLY_SECRET` | random per start | Signs thumbnail URLs; set it in production |
| `GRABLY_AUDIO` | `0` | `1` adds an "MP3 · Audio only" option. Off by default because audio downloads are a Premium feature in the app |
| `GRABLY_MAX_MB` | `500` | Largest file the server will send |
| `GRABLY_MAX_DURATION` | `10800` | Longest video, in seconds (3 hours) |
| `GRABLY_INFO_PER_MIN` | `30` | Link lookups per visitor per minute |
| `GRABLY_DOWNLOADS_PER_MIN` | `10` | Downloads per visitor per minute |
| `GRABLY_MAX_CONCURRENT` | `3` | Downloads processed at the same time per server |
| `GRABLY_TRUST_PROXY` | `0` (`1` in Docker) | Use `X-Forwarded-For` for visitor IPs; enable only behind a proxy you control |
| `GRABLY_ALLOWED_ORIGINS` | none | Comma-separated origins allowed to call the API, if the website is hosted on a different domain |
| `GRABLY_TMP` | system temp | Where temporary downloads are written |

To host the website and the API on different domains, set `<meta name="grably-api" content="https://your-api.example.com">` in `index.html` and list the website's origin in `GRABLY_ALLOWED_ORIGINS`.

## Before launch

1. **Terms of use and takedown contact**: a public downloader should have terms of use and a way for rights holders to reach you (for example a DMCA/copyright contact). Have these reviewed by someone qualified.
2. **Privacy policy**: needed for the website, and required by Apple and Google for the app listings.
3. **Domain**: once you have one, change `og:image` in `index.html` to an absolute URL (for example `https://yourdomain.com/assets/img/og-image.png`) so link previews work.
4. **Copy review**: check the FAQ and Premium text against how the app actually behaves.

## When the phone app launches

Replace the "coming soon" bits with store buttons: the hero badge (`.pill`), the launch section (`#launch`), the footer line under the logo, and the FAQ entry "When is the phone app coming?". The original App Store / Google Play buttons are in this repository's history (commit `72d094f`).

## Customizing

- **Colors**: the theme is teal on dark navy, from the logo. Edit the variables in `:root` at the top of `styles.css`: `--teal-500` (`#10B3A3`) is the accent and `--navy-900` (`#0B1A33`) the background. Buttons use navy text on teal, because white on teal is too low-contrast to read comfortably. App mockups keep a light palette, set in the block right after `:root`.
- **Fonts**: Outfit (headings and mockups) and Inter (body), from Google Fonts.
- **Supported sites**: `PLATFORMS` in `server/downloader.py` (the real allowlist) and `PLATFORMS` in `assets/js/main.js` (instant feedback in the browser). Keep them in sync.

## Credits

Platform icons come from [Simple Icons](https://simpleicons.org/) (CC0). All trademarks belong to their respective owners. Grably is not affiliated with TikTok, Instagram, Facebook, X or Pinterest.
