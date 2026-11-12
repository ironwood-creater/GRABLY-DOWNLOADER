"""The website's pages: cache-busting links and Google AdSense IDs.

Used two ways:
  - by the Python server (server/app.py), which renders pages on request, and
  - as a build step on Vercel (`python -m server.site`), which rewrites public/ in place
    so Vercel's CDN serves finished pages.

Only uses the standard library, so the build step runs before anything is installed.
"""
from __future__ import annotations

import hashlib
import logging
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

log = logging.getLogger("grabbly.site")

ROOT = Path(__file__).resolve().parent.parent
PUBLIC = ROOT / "public"  # everything the website serves (pages, CSS, JS, fonts, images)
PAGES = ("index.html", "privacy.html")
VERSIONED_ASSETS = ("assets/css/styles.css", "assets/js/main.js")
GOOGLE_ADS_TXT_ID = "f08c47fec0942fa0"  # Google's fixed certification ID for ads.txt

PUBLISHER_ID = re.compile(r"ca-pub-\d{10,20}")
AD_UNIT_ID = re.compile(r"\d{5,20}")


@dataclass(frozen=True)
class Ads:
    client: str  # ca-pub-1234567890123456
    slot: str  # numeric display ad unit ID


def ads_from_env(env: Optional[dict] = None) -> Optional[Ads]:
    """AdSense IDs from GRABBLY_ADSENSE_CLIENT and GRABBLY_ADSENSE_SLOT, or None when either is
    missing or doesn't look right (ads stay off rather than showing broken ad spaces)."""
    env = os.environ if env is None else env
    client = (env.get("GRABBLY_ADSENSE_CLIENT") or "").strip()
    slot = (env.get("GRABBLY_ADSENSE_SLOT") or "").strip()
    if client and not PUBLISHER_ID.fullmatch(client):
        log.warning("GRABBLY_ADSENSE_CLIENT should look like ca-pub-1234567890123456; ads are off")
        return None
    if slot and not AD_UNIT_ID.fullmatch(slot):
        log.warning("GRABBLY_ADSENSE_SLOT should be the numeric ad unit ID; ads are off")
        return None
    return Ads(client, slot) if client and slot else None


def asset_digest(public: Path, rel: str) -> str:
    return hashlib.sha256((public / rel).read_bytes()).hexdigest()[:10]


def render(html: str, public: Path, ads: Optional[Ads]) -> str:
    """Adds ?v=<content hash> to the CSS and JS links, so browsers can cache those files for a
    year and still get changes right away (the URL changes with the content), and fills the
    AdSense IDs into the meta tags the page script reads."""
    for rel in VERSIONED_ASSETS:
        # The closing quote right after the path means links that already have ?v= are left alone
        html = html.replace(f'"{rel}"', f'"{rel}?v={asset_digest(public, rel)}"')
    if ads:
        html = html.replace(
            '<meta name="grabbly-ads-client" content="">',
            f'<meta name="grabbly-ads-client" content="{ads.client}">\n'
            f'  <meta name="google-adsense-account" content="{ads.client}">',
        )
        html = html.replace('<meta name="grabbly-ads-slot" content="">', f'<meta name="grabbly-ads-slot" content="{ads.slot}">')
    return html


def ads_txt(ads: Optional[Ads]) -> Optional[str]:
    """The ads.txt line that authorizes Google to sell ads on the site (AdSense checks for it)."""
    if not ads:
        return None
    return f"google.com, {ads.client.removeprefix('ca-')}, DIRECT, {GOOGLE_ADS_TXT_ID}\n"


def build(public: Path = PUBLIC, ads: Optional[Ads] = None) -> list[Path]:
    """Rewrites the pages in public/ for static hosting and writes ads.txt when ads are on.
    Returns the files written."""
    written = []
    for name in PAGES:
        path = public / name
        path.write_text(render(path.read_text(encoding="utf-8"), public, ads), encoding="utf-8")
        written.append(path)
    txt = ads_txt(ads)
    if txt:
        (public / "ads.txt").write_text(txt, encoding="utf-8")
        written.append(public / "ads.txt")
    return written


if __name__ == "__main__":
    import sys

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    if os.environ.get("VERCEL") != "1" and "--in-place" not in sys.argv:
        # It edits the files in public/, which is what a fresh build machine wants but not a checkout
        sys.exit("This rewrites public/ in place for hosting. Run it with --in-place to do that here.")
    current = ads_from_env()
    for f in build(ads=current):
        log.info("built %s", f.relative_to(ROOT))
    log.info("ads: %s", "on" if current else "off (set GRABBLY_ADSENSE_CLIENT and GRABBLY_ADSENSE_SLOT to turn them on)")
