# Aoide

Ἀοιδή, the muse of song. A phone music player with the aesthetic of Spotify and the feel of Apple Music, painted orange, on top of the [Monochrome](https://monochrome.tf) catalogue.

**Get it:** the Android APK is on the [Releases](https://github.com/retrocodes12/aoide/releases) page (Android 7.0 or newer, no account, no sign-up). A web preview of the same build runs at https://retrocodes12.github.io/aoide/ and is best opened on a phone.

<p><img src="docs/home.png" width="200"> <img src="docs/album.png" width="200"> <img src="docs/now-playing.png" width="200"> <img src="docs/lyrics.png" width="200"></p>

## What it does

- Home with your quick grid, a hero card for the newest record, new releases and editorial rows.
- Search across songs, albums, artists and playlists, with a top result, filter chips and a browse grid.
- Album, artist and playlist pages with headers tinted from the artwork, centred art, Play and Shuffle pills, Lossless and Hi-Res badges.
- Full-screen now-playing view on a blurred-artwork stage; the cover shrinks when you pause, as it does in Apple Music.
- Synced lyrics from [lrclib](https://lrclib.net); tap a line to seek.
- Queue: play next, add to queue, drag to reorder, remove, jump, clear. Shuffle and repeat.
- Liked songs, saved albums, followed artists and your own playlists, all stored on the device.
- Background playback with a media notification, lock-screen and headset controls in the Android app.
- Instance manager: reads the same hifi-api mirrors Monochrome lists, benches dead ones, lets you add your own, and falls back to TIDAL's public catalogue for browsing when every mirror is down.

## How streaming works, honestly

Monochrome is an open front end for TIDAL. Its public mirrors currently serve **30-second previews** unless the mirror is backed by a subscribed account (`FULL_REQUIRES_SUBSCRIPTION` in the manifest). Aoide plays whatever the mirror returns and says so: a `Lossless` or `Hi-Res Lossless` badge for a full stream, `Preview` for a clip. If you run or know a full-stream hifi-api instance, add it under **Settings → Instances**; yours are tried first.

Aoide does not touch Monochrome's Turnstile-gated "Unified Playback" service. That gate is theirs to keep.

Resolution order for a song: each configured mirror's `GET /track/?id=…&quality=…` (a base64 DASH manifest played by [shaka-player](https://github.com/shaka-project/shaka-player)), then TIDAL's public manifest endpoint as a last resort (previews only).

## Build

```bash
npm install
npm run dev              # web, http://localhost:5173 (open in a phone-sized viewport)
npm run build            # web preview for GitHub Pages, served from /aoide/
npm run android:debug    # web bundle for the app + Capacitor sync + debug APK
npm run android:release  # signed release APK (key at ~/.aoide/release.jks, or -PaoideKeystore=…)
```

Vite 8, React 19, TypeScript, zustand, react-router, shaka-player, Capacitor 8 with `@capgo/capacitor-media-session`. The Android project lives in `android/`; JDK 21 and the Android SDK (platform 36) are needed for the APK. A native Kotlin/Compose version of the same app lives on the `native-compose` branch for reference.

## Credits and disclaimer

Catalogue and audio come from community-run Monochrome / hifi-api mirrors and TIDAL's public endpoints. Lyrics come from lrclib. Aoide is a client; it hosts no media and ships no credentials beyond TIDAL's public browser client id, the same one Monochrome uses. Use it with the same care you'd use Monochrome itself.

Not affiliated with Spotify, Apple, TIDAL, or Monochrome.
