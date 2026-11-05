# Google Play store listing for Grably

Everything Play Console asks for, ready to paste. Graphics are in this folder:

| What Play Console asks for | File | Size |
|---|---|---|
| App icon | `graphics/icon-512.png` | 512 × 512 PNG |
| Feature graphic | `graphics/feature-graphic.png` | 1024 × 500 PNG, no transparency |
| Phone screenshots (8) | `screenshots/phone/01-…08-….png` | 1080 × 1920 PNG |
| Plain screenshots, if you prefer them without captions | `screenshots/raw/` | 1080 × 1920 PNG |

Play allows 2 to 8 phone screenshots, so all 8 fit.

## Main store listing

**App name** (30 characters max)

```
Grably: Video Saver
```

**Short description** (80 characters max)

```
Save videos from your favorite apps to your phone. Free, fast and no sign-up.
```

**Full description** (4,000 characters max)

```
Grably saves videos to your phone in a couple of taps. Copy a video's link, open Grably, and pick the quality you want. The video lands in your gallery, ready to watch offline or share.

HOW IT WORKS
• Copy a link in your favorite app, or tap Share and pick Grably
• Grably finds the video and shows the qualities available
• Choose MP4 in up to 1080p, or save just the sound as MP3
• Find everything in Downloads, or in your phone's Gallery

WHY GRABLY
• Free, with no account and no sign-up
• Downloads keep going in the background, with a progress notification
• Saved straight to Movies/Grably (videos) and Music/Grably (sound)
• Your download list stays on your phone
• Light and dark mode
• No storage permission needed

SUPPORTED LINKS
Grably works with public video links from TikTok, Instagram, Facebook, X and Pinterest.

PLEASE NOTE
Only download videos you own or have permission to save, and respect the rights of creators. Private videos and videos that need a login can't be downloaded.

Grably is an independent app and isn't affiliated with, endorsed by or sponsored by TikTok, Instagram, Facebook, X or Pinterest. All trademarks belong to their owners.
```

**Screenshot captions** (already on the framed screenshots)

1. Save videos from your favorite apps
2. Paste a link. That's it.
3. Links are recognised instantly
4. Pick the quality you want
5. All your downloads in one place
6. Easy on the eyes in dark mode
7. Or save just the sound as MP3
8. Watch, share or delete anytime

## Store settings

- **App category:** Video Players & Editors (or Tools)
- **Contact email:** your support email (required)
- **Website:** https://grably-downloader-server.vercel.app
- **Privacy policy:** https://grably-downloader-server.vercel.app/privacy

## App content (Policy section in Play Console)

- **Ads:** No, the app doesn't contain ads.
- **App access:** All functionality is available without special access (no login).
- **Content rating:** fill in the questionnaire as a utility app. No violence, sexual content,
  gambling or user-to-user communication. Users can't share content with each other in the app.
- **Target audience:** 18 and over is the safest choice (13 and over also works). Don't include
  children: a downloader app for under-13s brings extra Families policy requirements.
- **News app:** No.
- **Government app:** No.
- **Financial features:** None.
- **Health:** None.

### Data safety form

The app has no analytics, no ads SDK and no account. What leaves the phone is the video link
the user enters, sent to the Grably server to look up and download the video.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS)
- Do you provide a way for users to request that their data is deleted? **No** (nothing is
  stored on the server; the download list is only on the phone and can be cleared in the app)
- Data type: **App activity → Other user-generated content** (the video links)
  - Collected: **Yes**. Shared: **No**.
  - Processed ephemerally: **Yes** (links aren't stored)
  - Required or optional: **Required** (the app can't work without it)
  - Purpose: **App functionality**

### Foreground service declaration

The app uses a foreground service of type **dataSync** so downloads continue when the user
leaves the app. In **App content → Foreground service permissions**, pick *Data sync* and
describe it like this:

```
When the user taps Download, the app downloads the chosen video file from the Grably server
and saves it to the phone's gallery. The download runs as a user-visible foreground service
with a progress notification so it isn't interrupted if the user switches apps. It stops as
soon as the file is saved.
```

Play asks for a short video showing this: record the screen while starting a download, leaving
the app and pulling down the notification shade to show the progress notification.

### Permissions the app uses

| Permission | Why |
|---|---|
| Internet, network state | Look up and download videos |
| Notifications | Download progress and "saved" messages (asked on the first download) |
| Foreground service (data sync) | Keep a download going in the background |
| Wake lock, run at startup | Added by Android's WorkManager library, so queued downloads resume after a restart |

No storage, camera, location, contacts or "all apps" permissions.

## Before you submit: policy risk

Google Play removes apps that help people download content without the owner's permission,
and video downloaders for social apps are often rejected or later suspended, especially if
the listing uses other companies' names or logos prominently. To lower the risk:

- Keep platform names out of the app name, short description and graphics (as above).
- Keep the "only download videos you own or have permission to save" notice (it's in the app
  and the description).
- Have a plan B: you can also offer the APK on your website, or use another store (for example
  Samsung Galaxy Store, Amazon Appstore or APKPure).
