# Aoide

Ἀοιδή, the muse of song. An Android music player in the shape of Spotify, painted orange, on top of the [Monochrome](https://monochrome.tf) catalogue.

**Download:** the latest APK is on the [Releases](https://github.com/retrocodes12/aoide/releases) page. Android 8.0 or newer, no Google account, no sign-up.

<p><img src="docs/home.png" width="200"> <img src="docs/album.png" width="200"> <img src="docs/now-playing.png" width="200"> <img src="docs/lyrics.png" width="200"></p>

## What it does

- Home with your quick grid, new releases, and editorial rows.
- Search across songs, albums, artists and playlists, with a top result and filter chips, plus a browse grid.
- Album, artist and playlist pages with headers tinted from the artwork.
- Background playback with a media notification, lock-screen and headset controls (Media3).
- Queue: play next, add to queue, drag to reorder, remove, jump, clear.
- Shuffle, repeat one/all, seek, gapless-ish preloading of the next song.
- Synced lyrics from [lrclib](https://lrclib.net), tap a line to seek.
- Full-screen now-playing view with the record's colour as the backdrop.
- Liked songs, saved albums, followed artists, your own playlists. All stored on the phone; nothing leaves it.
- Instance manager: reads the same hifi-api mirrors Monochrome lists, fails over between them, and lets you add your own.
- Quality picker: Hi-Res, Lossless (FLAC), High, Low.

## How streaming works, honestly

Monochrome is an open front end for TIDAL. Its public mirrors currently serve **30-second previews** unless the mirror is backed by a subscribed account (`FULL_REQUIRES_SUBSCRIPTION` in the manifest). Aoide plays whatever the mirror returns and says so in the player: `FLAC 16/44` for a full stream, `PREVIEW` for a clip. If you run or know a full-stream hifi-api instance, add it under **Settings → Instances**; your instances are tried first.

Aoide does not touch Monochrome's Turnstile-gated "Unified Playback" service. That gate is theirs to keep.

Resolution order for a song:

1. `GET {instance}/track/?id=…&quality=…` on each configured mirror → base64 DASH manifest → ExoPlayer, via a `data:` URI resolved on ExoPlayer's loader thread the moment the song is reached.
2. TIDAL's public manifest endpoint as a last resort (previews only).

## Build

```bash
git clone https://github.com/retrocodes12/aoide
cd aoide
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, Android SDK platform 36. Kotlin 2.2, Jetpack Compose, Media3 ExoPlayer, Coil, OkHttp, kotlinx.serialization. Release builds are signed with the Aoide key (`~/.aoide/release.jks` locally, a secret in CI) so every build installs over the last.

## Credits and disclaimer

Catalogue and audio come from community-run Monochrome / hifi-api mirrors and TIDAL's public endpoints. Lyrics come from lrclib. Aoide is a client; it hosts no media and ships no credentials beyond TIDAL's public browser client id, the same one Monochrome uses. Use it with the same care you'd use Monochrome itself.

Not affiliated with Spotify, TIDAL, or Monochrome.
