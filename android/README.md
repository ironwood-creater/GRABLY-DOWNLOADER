# Grably for Android

The Grably phone app: paste a TikTok, Instagram, Facebook, X or Pinterest link (or share it to
Grably from that app) and the video is saved to the phone's gallery.

It uses the same Grably server as the website (`server/`, deployed on Cloudflare), so the server
must be running and the app must know its address.

## What's inside

- **Home**: link field with Paste, automatic pick-up of a link you just copied, shortcuts that
  open each app so you can copy a link, and the most recent download.
- **Quality picker**: the video's title and thumbnail with every quality the server offers
  (for example MP4 1080p, MP4 720p, MP3 sound only).
- **Downloads**: All / Complete / Failed tabs grouped by day, with progress, play, share,
  try again, cancel and delete.
- **Share to Grably**: in TikTok, Instagram, X and others, tap Share, then Grably.
- Downloads keep going in the background with a progress notification, and files are saved to
  `Movies/Grably` (videos) or `Music/Grably` (sound). No storage permission is needed.
- Light and dark mode in Grably's teal on navy, with the website's fonts (Outfit and Inter).

Built with Kotlin and Jetpack Compose. Android 10 or newer.

## Set the server address

The app needs the address of your Grably server, such as
`https://grably-downloader.<your-subdomain>.workers.dev`. Set it one of these ways:

- **GitHub build**: in the repository go to Settings → Secrets and variables → Actions →
  Variables, and add `GRABLY_API_URL` with the address. Or run the "Android app" workflow by
  hand and type the address in.
- **On your computer**: put `grably.apiBaseUrl=https://…` in `android/gradle.properties`, or
  pass `-Pgrably.apiBaseUrl=https://…` to Gradle.

Without it the app still opens but shows "Grably's server address isn't set in this version of
the app" when you try to download.

## Get the APK

Every push that changes `android/` runs the **Android app** workflow on GitHub Actions. Open
the run and download **grably-debug-apk** under Artifacts. Unzip it and install the `.apk` on a
phone (you'll need to allow installing apps from that source).

## Build it yourself

Needs Android Studio (or JDK 17+ and the Android SDK, platform 37).

```bash
cd android
./gradlew testDebugUnitTest      # tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

On the emulator, a server running on your computer is reachable at `http://10.0.2.2:8000`
(plain HTTP is allowed for that address in debug builds only).

## Before publishing on Google Play

- Create an upload key and set up signing for `assembleRelease` / `bundleRelease`
  (keep the key out of the repository).
- Write a privacy policy page for the app (the website's `privacy.html` is a good start).
- Google Play doesn't allow apps that download videos from YouTube, and it removes apps that
  help people infringe copyright. Many downloader apps are distributed outside Play (on the
  website as an APK, or other stores) for this reason. Keep the "only download videos you own
  or have permission to save" notice.
