# Grably: website

Marketing website for **Grably**, the video downloader app for TikTok, Instagram, Facebook, X and Pinterest.

It's a static site: plain HTML, CSS and JavaScript with no build step and no dependencies. It works on any static host.

## What's on the page

- **Hero**: headline, App Store / Google Play buttons, and three phone mockups recreated in HTML/CSS from the app design (welcome, home, downloads).
- **Link checker**: visitors paste a link and the page tells them whether Grably supports that platform. It runs entirely in the browser and downloads nothing.
- **How it works**: three steps, plus where to find "Copy link" in each supported app.
- **Features**: an interactive download manager demo (All / Complete / Failed filters, live progress) and feature tiles.
- **Premium**: remove watermarks, download audio, exclusive features.
- **Respect creators** notice, **FAQ**, a final download CTA, and a footer with a trademark disclaimer.

## Project structure

```
index.html            The page
assets/css/styles.css All styles (brand colors are CSS variables at the top)
assets/js/main.js     Link checker, mobile menu, store links, demos
assets/img/logo.svg   App logo (also used as favicon.svg)
assets/img/og-image.png  1200×630 preview image for social sharing
```

## Run it locally

Open `index.html` in a browser, or serve the folder:

```bash
python3 -m http.server 8000
```

Then visit http://localhost:8000.

## Before launch

1. **Store links**: add your App Store and Google Play URLs at the top of `assets/js/main.js` (`STORE_LINKS`). Until then, the store buttons show a "coming soon" message.
2. **Domain**: once you have one, change `og:image` in `index.html` to an absolute URL (for example `https://grably.app/assets/img/og-image.png`) so link previews work on social media.
3. **Privacy policy**: Apple and Google both require a privacy policy URL for app listings. Add a `privacy.html` page and link it in the footer.
4. **Copy review**: check the FAQ and Premium text against how the app actually behaves (for example, whether Grably is free and exactly what Premium includes).

## Customizing

- **Colors**: edit the variables in `:root` at the top of `styles.css`. `--purple-500` is the brand purple.
- **Fonts**: Outfit (headings and app mockups) and Inter (body), loaded from Google Fonts in `index.html`.
- **Supported platforms**: the link checker's list lives in `PLATFORMS` in `main.js`.

## Deploy

- **GitHub Pages**: Repository → Settings → Pages → Deploy from a branch → choose the branch and `/ (root)`.
- **Netlify / Vercel / Cloudflare Pages**: import the repository. No build command is needed, and the output directory is the repository root.

## Credits

Platform icons come from [Simple Icons](https://simpleicons.org/) (CC0). All trademarks belong to their respective owners.
