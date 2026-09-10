# Aoide

Ἀοιδή, the muse of song. A native Android music player in the shape of Spotify with the polish of Apple Music, painted orange, on top of the [Monochrome](https://monochrome.tf) catalogue.

**Download:** the latest APK is on the [Releases](https://github.com/retrocodes12/aoide/releases) page. Android 8.0 or newer, no account, no sign-up. A web preview of the same design runs at https://retrocodes12.github.io/aoide/ (phone-sized; open it on a phone or shrink the window).

<p><img src="docs/home.png" width="180"> <img src="docs/album.png" width="180"> <img src="docs/now-playing.png" width="180"> <img src="docs/lyrics.png" width="180"></p>
<p><img src="docs/artist.png" width="180"> <img src="docs/playlist.png" width="180"></p>

## What it does

- Home with your quick grid, a hero card for the newest record, new releases, "more like" rows seeded from what you played, and mood rows.
- Search across songs, albums, artists and playlists, with a top result, filter chips, recent searches and a browse grid.
- Album, artist and playlist pages whose heads take the record's colour. The ink on every tint is chosen by measured contrast, so a yellow record gets dark ink and a navy one gets light, and both clear WCAG AA.
- Background playback through Media3: notification, lock screen and headset controls, audio focus, "becoming noisy" pause.
- Queue: play next, add to queue, drag to reorder, remove, jump, clear (with a confirmation). Shuffle that restores the original order when turned off. Repeat one and all.
- Synced lyrics from [lrclib](https://lrclib.net); tap a line to seek. On a preview, lines past the clip are shown for reading and cannot be tapped.
- A full-screen player with the artwork blurred behind everything, the cover shrinking on pause, a hairline seek bar, and a pull-down to dismiss.
- Liked songs, saved albums, followed artists, your own playlists, recently played, recent searches. Everything stays on the phone; nothing leaves it.
- The last queue comes back after a relaunch, paused where it was.
- Plain-language playback errors with a retry. Three dead songs in a row stop the skipping instead of running the queue out.
- Instance manager: reads the same hifi-api mirrors Monochrome lists, fails over between them, benches a broken one for 90 seconds, and lets you add your own. When every mirror is down it browses TIDAL's catalogue directly and says so on Home and in Settings.
- Quality picker: Hi-Res Lossless, Lossless, High, Low, with an honest read-back when a song was only granted a lower tier.

## How streaming works, honestly

Monochrome is an open front end for TIDAL. Its public mirrors currently serve **30-second previews** unless the mirror is backed by a subscribed account. Aoide plays whatever the mirror returns and says so in the player: `LOSSLESS` for a full stream, `PREVIEW` for a clip. If you run or know a full-stream hifi-api instance, add it under **Settings → Instances**; your instances are tried first.

Resolution order for a song:

1. `GET {instance}/track/?id=…&quality=…` on each configured mirror, which returns a base64 DASH manifest that ExoPlayer opens through a `data:` URI resolved on its loader thread the moment the song is reached.
2. TIDAL's public manifest endpoint as a last resort. It honours the requested tier but only serves previews without a subscription.

Aoide does not touch Monochrome's Turnstile-gated "Unified Playback" service. That gate is theirs to keep.

## Build

```bash
git clone https://github.com/retrocodes12/aoide
cd aoide
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Release builds are signed with a dedicated key so every build installs over the last one (`app/build.gradle.kts` reads it from `~/.aoide/release.jks` locally and from a secret in CI).

### Screenshots without an emulator

Every screen renders on the JVM through Robolectric's native graphics and Roborazzi, against the live catalogue:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:recordRoborazziDebug --tests app.aoide.Shots
```

PNGs land in `app/build/shots/`. This is how the app was audited on a laptop that cannot run the emulator. Two things the rig needs, both in `app/src/test/java/app/aoide/Shots.kt`: the native runtime is warmed up on the test thread before any image decodes, and artwork decodes on the main thread.

### Web preview

The `web/` directory holds the React version of the same design, deployed to GitHub Pages. It shares the catalogue layer's behaviour (mirror failover, TIDAL fallback, lyrics) and served as the audit ground before the native port.

```bash
cd web && npm ci && npm run dev
```

## Design notes

Spotify's structure: three tabs, a capsule mini player, a bottom-sheet track menu, chips, the browse grid. Apple Music's polish: centred artwork heads with paired Play and Shuffle pills, icon discs over artwork, the blurred now-playing stage, lossless badges, Figtree throughout. Dark `#121212`, accent `#ff7a1f`. No orange ever sits on a tint; the tint's ink is solved for contrast in `app/src/main/java/app/aoide/ui/theme/Tint.kt`.

## Disclaimer

Aoide is an independent project. It is not affiliated with Spotify, Apple, TIDAL or Monochrome. It plays what the mirrors you configure return; respect the terms of the services you use.
