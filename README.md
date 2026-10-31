# Grably: website

Marketing website for **Grably**, the video downloader app for TikTok, Instagram, Facebook, X and Pinterest.

It's a static site: plain HTML, CSS and JavaScript with no build step and no dependencies. It works on any static host.

The app isn't on the App Store or Google Play yet, so the site is in **pre-launch mode**: it shows "Coming soon" instead of store buttons.

## What's on the page

- **Hero**: "Coming soon" badge, headline, and three phone mockups recreated in HTML/CSS from the app design (welcome, home, downloads).
- **Link checker**: visitors paste a link and the page tells them whether Grably supports that platform. It runs entirely in the browser and downloads nothing.
- **How it works**: three steps, plus where to find "Copy link" in each supported app.
- **Features**: an interactive download manager demo (All / Complete / Failed filters, live progress) and feature tiles.
- **Premium**: remove watermarks, download audio, exclusive features.
- **Respect creators** notice, **FAQ**, a "Grably is almost here" launch section, and a footer with a trademark disclaimer.

## Project structure

```
index.html            The page
assets/css/styles.css All styles (brand colors are CSS variables at the top)
assets/js/main.js     Link checker, mobile menu, demos
assets/img/logo.svg   App logo: teal G on navy (also used as favicon.svg)
assets/img/apple-touch-icon.png  180×180 icon for iPhone home-screen bookmarks
assets/img/og-image.png  1200×630 preview image for social sharing
```

## Run it locally

Open `index.html` in a browser, or serve the folder:

```bash
python3 -m http.server 8000
```

Then visit http://localhost:8000.

## Before launch

1. **Domain**: once you have one, change `og:image` in `index.html` to an absolute URL (for example `https://grably.app/assets/img/og-image.png`) so link previews work on social media.
2. **Privacy policy**: Apple and Google both require a privacy policy URL for app listings. Add a `privacy.html` page and link it in the footer.
3. **Copy review**: check the FAQ and Premium text against how the app actually behaves (for example, whether Grably is free and exactly what Premium includes).

## When the app launches

Replace the "Coming soon" bits with store buttons:

- Header: the "Coming soon" button in `.site-nav`.
- Hero: the badge (`.pill`) at the top of `.hero__copy`.
- Launch section (`#launch`): the badge, heading, text and button.
- Footer: the "Coming soon" line under the logo.
- FAQ: "When can I get Grably?" and the future-tense wording in "Is Grably free?".

The first version of the site had App Store / Google Play buttons; they're in this repository's history (commit `72d094f`) if you want to bring them back.

## Customizing

- **Colors**: the theme is teal on dark navy, from the logo. Edit the variables in `:root` at the top of `styles.css`: `--teal-500` (`#10B3A3`) is the accent and `--navy-900` (`#0B1A33`) the background. Buttons use navy text on teal, because white on teal is too low-contrast to read comfortably. App mockups keep a light palette, set in the block right after `:root`.
- **Fonts**: Outfit (headings and app mockups) and Inter (body), loaded from Google Fonts in `index.html`.
- **Supported platforms**: the link checker's list lives in `PLATFORMS` in `main.js`.

## Deploy

- **GitHub Pages**: Repository → Settings → Pages → Deploy from a branch → choose the branch and `/ (root)`.
- **Netlify / Vercel / Cloudflare Pages**: import the repository. No build command is needed, and the output directory is the repository root.

## Credits

Platform icons come from [Simple Icons](https://simpleicons.org/) (CC0). All trademarks belong to their respective owners.
