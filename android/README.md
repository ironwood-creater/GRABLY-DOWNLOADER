# Grabbly for Android

The Grabbly phone app: paste a TikTok, Instagram, Facebook, X or Pinterest link (or share it to
Grabbly from that app) and the video is saved to the phone's gallery.

It uses the same Grabbly server as the website (`server/`, deployed on Cloudflare), so the server
must be running and the app must know its address.

## What's inside

- **Home**: link field with Paste, automatic pick-up of a link you just copied, shortcuts that
  open each app so you can copy a link, and the most recent download.
- **Quality picker**: the video's title and thumbnail with every quality the server offers
  (for example MP4 1080p, MP4 720p, MP3 sound only).
- **Downloads**: All / Complete / Failed tabs grouped by day, with progress, play, share,
  try again, cancel and delete.
- **Share to Grabbly**: in TikTok, Instagram, X and others, tap Share, then Grabbly.
- Downloads keep going in the background with a progress notification, and files are saved to
  `Movies/Grabbly` (videos) or `Music/Grabbly` (sound). No storage permission is needed.
- Light and dark mode in Grabbly's teal on navy, with the website's fonts (Outfit and Inter).

Built with Kotlin and Jetpack Compose. Android 10 or newer.

## Server address

The app talks to the live Grabbly site, https://grabbly-downloader-server.vercel.app, by default
(set in `app/build.gradle.kts`). To use another server:

- **GitHub build**: in the repository go to Settings → Secrets and variables → Actions →
  Variables, and add `GRABBLY_API_URL` with the address. Or run the "Android app" workflow by
  hand and type the address in.
- **On your computer**: put `grabbly.apiBaseUrl=https://…` in `android/gradle.properties`, or
  pass `-Pgrabbly.apiBaseUrl=https://…` to Gradle.

## Get the APK

Every push that changes `android/` runs the **Android app** workflow on GitHub Actions. Open
the run and download **grabbly-debug-apk** under Artifacts. Unzip it and install the `.apk` on a
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

## Publish on Google Play

Everything for the store listing (texts, the data safety answers, the foreground service
declaration, icon, feature graphic and 8 screenshots) is in [`store/`](store/LISTING.md).

### 1. Add the upload key to GitHub (once)

Google Play only accepts app bundles signed with your *upload key*. Add these four repository
secrets (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `GRABBLY_UPLOAD_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `GRABBLY_UPLOAD_KEYSTORE_PASSWORD` | the keystore password |
| `GRABBLY_UPLOAD_KEY_ALIAS` | the key's alias |
| `GRABBLY_UPLOAD_KEY_PASSWORD` | the key's password (same as the keystore password for PKCS12 keys) |

To make a new key yourself instead:

```bash
keytool -genkeypair -keystore grabbly-upload-key.jks -storetype PKCS12 -alias grabbly-upload -keyalg RSA -keysize 4096 -validity 10000
```

```bash
base64 -w0 grabbly-upload-key.jks > grabbly-upload-key.base64.txt
```

Keep the key and its password somewhere safe and never commit them. With Play App Signing,
Google keeps the real app signing key, so a lost upload key can be reset through Play support.

### 2. Get the bundle

Run the **Android app** workflow (or push a change). Its **grabbly-release** artifact contains
`app-release.aab`. Each run has a higher version code (the run number), which Play requires
for every new upload. The run also checks Google Play's 16 KB page size rule.

### 3. Upload

In [Play Console](https://play.google.com/console), create the app, fill in the store listing
and the App content forms from `store/LISTING.md`, then upload `app-release.aab` to a testing
track first (internal testing is quickest), and to Production when you're happy.

### Store screenshots

`store/screenshots/raw/` are taken from the real app on an emulator by the **Store
screenshots** workflow (`app/src/androidTest/.../ScreenshotTest.kt`); run it again after
changing the app's look. `store/screenshots/phone/` are the same images with captions, made
by `store/make_store_graphics.py`.
