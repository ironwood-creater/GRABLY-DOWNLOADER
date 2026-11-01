# Grably: website and web downloader

The website for **Grably**. It downloads videos from TikTok, Instagram, Facebook, X and Pinterest right in the browser, so it works on any laptop, PC or Mac. The phone app for iPhone and Android is coming soon.

- **Frontend**: plain HTML, CSS and JavaScript in `public/`, no build step.
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
- Only the files in `public/` are served, never the server code.

If the API can't be reached (for example when `public/index.html` is opened as a file or hosted on a static host), the page falls back to checking whether a link is supported and says downloads aren't available right now.

## Project structure

```
public/                    Everything the website serves:
  index.html               The page
  privacy.html             Privacy policy (required by Google AdSense)
  robots.txt, _headers     Search-engine rules; response headers on Cloudflare
  assets/css/styles.css    All styles (teal-on-navy theme variables at the top)
  assets/js/main.js        Downloader UI, mobile menu, demos
  assets/img/              Logo, favicon, Apple touch icon, social preview image
  assets/fonts/            Inter and Outfit, self-hosted (SIL Open Font License)
server/app.py              Web server: API routes, rate limits, serves the website
server/downloader.py       Link checks, yt-dlp lookup and download
server/tests/              Tests (pytest)
Dockerfile                 Production image (Python + ffmpeg)
wrangler.jsonc             Cloudflare configuration (Worker, static files, Container)
cloudflare/worker.ts       Cloudflare Worker: serves public/, sends /api/* to the Container
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

## Deploy to Cloudflare (recommended)

On Cloudflare the website is served straight from Cloudflare's network (fast, even when nobody has visited for a while), and the download server runs in a **Cloudflare Container** built from the `Dockerfile`. The Worker in `cloudflare/worker.ts` sends `/api/…` requests to the container, adds your AdSense IDs to the page and serves `/ads.txt`. The container sleeps after 30 idle minutes and starts again on the next download, so the first download after a quiet period takes a few extra seconds.

**You need:** a Cloudflare account on the **Workers Paid** plan (Containers aren't available on the free plan). Check Cloudflare's Workers and Containers pricing pages for current prices; containers are billed while running, plus network traffic for the videos they send.

### Deploy from GitHub (no Docker needed on your computer)

1. In the [Cloudflare dashboard](https://dash.cloudflare.com), go to **Workers & Pages → Create → Import a repository**, connect GitHub and pick this repository.
2. Use these settings:
   - **Project name:** `grably` (it must match `"name"` in `wrangler.jsonc`)
   - **Production branch:** the branch with this code (for example `main`)
   - **Build command:** leave empty
   - **Deploy command:** `npx wrangler deploy`
3. Click **Deploy**. The first deploy takes several minutes while Cloudflare builds the Docker image and prepares the container. The page may load before downloads work; give it a few minutes.
4. Open the address it shows (`https://grably.<your-subdomain>.workers.dev`) and try a download.

After that, every push to the production branch deploys automatically.

### Or deploy from your computer

With [Docker Desktop](https://www.docker.com/products/docker-desktop/) running:

```bash
npm install
```

```bash
npx wrangler login
```

```bash
npx wrangler deploy
```

### After the first deploy

- **Your own domain** (needed for AdSense): in the Worker, go to **Settings → Domains & Routes → Add → Custom domain**. The domain must be on Cloudflare (you can also buy one through Cloudflare).
- **AdSense IDs:** set `ADSENSE_CLIENT` and `ADSENSE_SLOT` under `vars` in `wrangler.jsonc` and push. The Worker adds Google's verification tag and serves `/ads.txt` automatically.
- **Optional secret:** in the Worker's **Settings → Variables and Secrets**, add a secret named `GRABLY_SECRET` (a long random string) so thumbnail links keep working after the container restarts.
- **Size:** the container uses the `standard-1` instance type (1/2 vCPU, 4 GiB memory). Change `instance_type` in `wrangler.jsonc` to `basic` to spend less, or a larger type if downloads are slow.
- **Check the setup without deploying:** `npm run check` type-checks the Worker and runs a dry-run deploy (container build skipped).

## Deploy elsewhere (Docker)

Static-only hosts such as GitHub Pages can't run the downloader. Besides Cloudflare, any host that runs a Docker container works: Render, Railway, Fly.io, Google Cloud Run, or your own VPS.

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
| `GRABLY_AUDIO` | `1` | Offers an "MP3 · Audio only" option; `0` turns it off |
| `GRABLY_ADSENSE_CLIENT` | none | Your AdSense publisher ID, like `ca-pub-1234567890123456`. Turns ads on (see below) |
| `GRABLY_ADSENSE_SLOT` | none | The numeric ID of the display ad unit used in the ad spaces |
| `GRABLY_MAX_MB` | `500` | Largest file the server will send |
| `GRABLY_MAX_DURATION` | `10800` | Longest video, in seconds (3 hours) |
| `GRABLY_INFO_PER_MIN` | `30` | Link lookups per visitor per minute |
| `GRABLY_DOWNLOADS_PER_MIN` | `10` | Downloads per visitor per minute |
| `GRABLY_MAX_CONCURRENT` | `3` | Downloads processed at the same time per server |
| `GRABLY_TRUST_PROXY` | `0` (`1` in Docker) | Use `X-Forwarded-For` for visitor IPs; enable only behind a proxy you control |
| `GRABLY_PROXY_HOPS` | `1` | How many proxies sit in front of the server (for example 2 for a CDN plus a load balancer). The visitor's IP is read that many entries from the right of `X-Forwarded-For`, which visitors can't fake |
| `GRABLY_ALLOWED_ORIGINS` | none | Comma-separated origins allowed to call the API, if the website is hosted on a different domain |
| `GRABLY_TMP` | system temp | Where temporary downloads are written |

To host the website and the API on different domains, set `<meta name="grably-api" content="https://your-api.example.com">` in `public/index.html` and list the website's origin in `GRABLY_ALLOWED_ORIGINS`.

## Performance

The page is built to stay smooth (60 FPS) and load fast:

- **Animations use the GPU.** Every animation moves only `transform` or `opacity`, so the browser never has to repaint the page to animate it. Endless animations pause when their section is off screen, and the demo animations stop while the browser tab is hidden.
- **No work on scroll.** The header shadow and the scroll-reveal effects use `IntersectionObserver` instead of scroll listeners.
- **Fast loading.** Fonts are self-hosted and preloaded, so there are no requests to Google and no render-blocking font stylesheet. The server compresses HTML, CSS, JS and JSON (gzip). CSS and JS links get a content hash (`?v=…`), so browsers cache them for a year and still pick up changes immediately.
- **Font files never change at the same URL** (they're cached for a year). If you replace a font, give the file a new name.

## Free, supported by ads (Google AdSense)

Grably is completely free: there's no subscription or premium plan. The site earns money from Google AdSense ads.

**Read this first:** Google's publisher policies don't allow ads on sites that help people download copyrighted content without permission, and AdSense often rejects social-video downloaders or disables ads on them later. Before applying, read the "Copyrighted material" section of Google's Publisher Policies (search for "Google Publisher Policies" in the [AdSense Help Center](https://support.google.com/adsense)), and keep a backup ad network in mind.

### Setup

1. Deploy the site on your own domain first (AdSense reviews a live site).
2. Edit `public/privacy.html` and replace **[add your contact email]** with a real email address.
3. Apply at [adsense.google.com](https://adsense.google.com) and add your site.
4. Set your publisher ID (`ca-pub-…`): on Cloudflare in `ADSENSE_CLIENT` under `vars` in `wrangler.jsonc`, on other hosts in the `GRABLY_ADSENSE_CLIENT` environment variable. The site then automatically:
   - adds the `google-adsense-account` verification tag to the page, and
   - serves `/ads.txt` with the right line (`google.com, pub-…, DIRECT, f08c47fec0942fa0`).
5. In AdSense, create a **Display ad** unit (responsive) and put its numeric ID in `ADSENSE_SLOT` (Cloudflare) or `GRABLY_ADSENSE_SLOT` (other hosts). Ads appear in the three ad spaces once both IDs are set; without them the site shows no ads and no empty boxes.
6. In AdSense, turn on the **Privacy & messaging** consent message for the EEA, UK and Switzerland. Google requires consent there, and the privacy policy says visitors will be asked.
7. Keep **Auto ads** off. The ad spaces are placed between content sections, well away from the download box and buttons. Ads next to download buttons can be mistaken for them, which is against AdSense rules and can get the account suspended.

If you host the page without the Python server, put the two IDs in the `grably-ads-client` and `grably-ads-slot` meta tags in `public/index.html`, and create an `ads.txt` file yourself.

### How the ads are loaded

- Google's ad script loads only after the page has finished loading, and each ad is requested when it's about to scroll into view, so ads don't slow down the first paint or scrolling.
- Each ad space reserves its height before the ad arrives, so the page doesn't jump. A space collapses if Google has no ad for it.
- With an ad blocker, the ad spaces are removed before they scroll into view, so visitors don't see empty boxes.

## Before launch

1. **Terms of use and takedown contact**: a public downloader should have terms of use and a way for rights holders to reach you (for example a DMCA/copyright contact). Have these reviewed by someone qualified.
2. **Privacy policy**: `public/privacy.html` describes what this website does (links aren't stored, files are deleted after download, IP addresses are only used briefly for rate limiting, and Google AdSense cookies). Add your contact email, have it reviewed, and update it if you change how the site works. The phone app will need its own policy for the App Store and Google Play.
3. **Domain**: once you have one, change `og:image` in `public/index.html` to an absolute URL (for example `https://yourdomain.com/assets/img/og-image.png`) so link previews work.
4. **Copy review**: check the FAQ against how the site and app actually behave.

## When the phone app launches

Replace the "coming soon" bits with store buttons: the hero badge (`.pill`), the footer line under the logo, and the FAQ entry "When is the phone app coming?". The original App Store / Google Play buttons are in this repository's history (commit `72d094f`).

## Customizing

- **Colors**: the theme is teal on dark navy, from the logo. Edit the variables in `:root` at the top of `styles.css`: `--teal-500` (`#10B3A3`) is the accent and `--navy-900` (`#0B1A33`) the background. Buttons use navy text on teal, because white on teal is too low-contrast to read comfortably. App mockups keep a light palette, set in the block right after `:root`.
- **Fonts**: Outfit (headings and mockups) and Inter (body), self-hosted in `public/assets/fonts/` (Latin and Latin-Extended character sets).
- **Supported sites**: `PLATFORMS` in `server/downloader.py` (the real allowlist) and `PLATFORMS` in `public/assets/js/main.js` (instant feedback in the browser). Keep them in sync.

## Credits

Platform icons come from [Simple Icons](https://simpleicons.org/) (CC0). All trademarks belong to their respective owners. Grably is not affiliated with TikTok, Instagram, Facebook, X or Pinterest.
