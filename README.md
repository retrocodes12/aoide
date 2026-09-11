# Aoide

Ἀοιδή, the muse of song. A native Android music player in the familiar shape of the big streaming apps, painted orange, on top of a public music catalogue.

**Download:** the latest APK is on the [Releases](https://github.com/retrocodes12/aoide/releases) page. Android 8.0 or newer, no account, no sign-up. Once installed, Aoide updates itself in place from Settings. A web preview of the same design runs at https://retrocodes12.github.io/aoide/ (phone-sized; open it on a phone or shrink the window).

<p><img src="docs/home.png" width="180"> <img src="docs/album.png" width="180"> <img src="docs/now-playing.png" width="180"> <img src="docs/lyrics.png" width="180"></p>
<p><img src="docs/artist.png" width="180"> <img src="docs/playlist.png" width="180"> <img src="docs/library.png" width="180"> <img src="docs/history.png" width="180"></p>
<p><img src="docs/import.png" width="180"> <img src="docs/equalizer.png" width="180"> <img src="docs/lyrics-translated.png" width="180"> <img src="docs/downloads.png" width="180"></p>

## What it does

- Full-length songs, matched to the catalogue by artist, title and length, with 30-second previews only when no match exists.
- Home with your quick grid, a hero card for the newest record, new releases, "more like" rows seeded from what you played, and mood rows.
- Search across songs, albums, artists and playlists, with a top result, filter chips, recent searches and a browse grid.
- Album, artist and playlist pages whose heads take the record's colour. The ink on every tint is chosen by measured contrast, so a yellow record gets dark ink and a navy one gets light, and both clear WCAG AA.
- Background playback through Media3: notification, lock screen and headset controls, audio focus, "becoming noisy" pause.
- Queue: play next, add to queue, drag to reorder, remove, jump, clear (with a confirmation). Shuffle that restores the original order when turned off. Repeat one and all.
- Synced lyrics from a community lyrics database; tap a line to seek. On a preview, lines past the clip are shown for reading and cannot be tapped.
- A full-screen player with the artwork blurred behind everything, the cover shrinking on pause, a hairline seek bar, and a pull-down to dismiss.
- Liked songs, saved albums, followed artists, your own playlists, recently played, recent searches. Everything stays on the phone; nothing leaves it.
- The last queue comes back after a relaunch, paused where it was.
- Plain-language playback errors with a retry. Three dead songs in a row stop the skipping instead of running the queue out.
- Instance manager: reads a list of public catalogue mirrors, fails over between them, benches a broken one for 90 seconds, and lets you add your own. When every mirror is down it browses the catalogue's own API directly and says so on Home and in Settings.
- Quality picker: Hi-Res Lossless, Lossless, High, Low, with an honest read-back when a song was only granted a lower tier.
- **Downloads.** Any song, album or playlist can be kept on the phone, fetched one at a time by a foreground service with a progress notification, and played with no connection at all through the same DASH path as everything else. A Downloads collection in Your Library lists what is kept, what is coming and what failed, with the total size and a remove-all.
- **Your own music files.** "On this phone" reads the device's media store and plays what is already there, with the same rows, queue and player.
- **Import playlists** from two other music services by pasting a public playlist link. Nothing to log into. Each song is matched in the catalogue by artist, title and length; misses are listed, never guessed. An imported playlist remembers its source and has a sync button that appends what is new.
- **History and stats.** Recently played and most played, with minutes listened, songs and top artist, all kept on the phone. Clearable.
- **Equalizer.** Five bands (or whatever the phone's audio chip offers), presets, and bass boost, on Android's own audio effects bound to Aoide's audio session.
- **Sleep timer**, in minutes or at the end of the current song, from the player and from any song's menu.
- **Playback settings.** Data saver (the smallest stream on mobile data), fade between songs (the end of one fades out and the next fades in; play and pause fade too; it is a fade, not a true overlap), playback speed with the pitch kept, skip silence, pause when muted, resume when headphones or Bluetooth come back, and autoplay of similar songs when the queue runs out.
- **Share** a song as text with a universal link that opens in whatever the other person uses. **Set as ringtone** for a downloaded song, on Android 10 and newer.
- **Lyrics translated** under each line, through a public translation service, in twenty languages; three lyric sizes.
- **Accent colour** (orange stays the default) and **pure black** for OLED screens.
- **Backup and restore** the library as one JSON file through the system file picker.
- **Android Auto**: the playback service is a Media3 library service, so the car screen can browse Liked Songs, Recently played, Downloads and playlists and search the catalogue by voice.
- **Home-screen widget** with the song playing and play, pause and skip.
- **In-app updates.** Aoide checks its own Releases page once a day, shows a banner on Home when a newer build is out, downloads the APK on request and hands it to the system installer. Every build is signed with the same key, so the new one installs over the old with your library intact. Nothing installs until you tap Install.

Not built, on purpose: group listening, casting to other devices, music recognition, podcasts, animated covers, video mode, and word-by-word lyrics. Each is either a service Aoide does not have, or a screen it would be dishonest to fake.

## How streaming works, honestly

Aoide browses a public music catalogue through community-run mirrors and plays each song from the best source that answers, in this order:

1. **A mirror of your own**, if you have added one under Settings, Instances. A mirror backed by a subscribed account serves lossless FLAC in full.
2. **A public video platform**, for the full song. Aoide searches the platform's music catalogue for the same artist and title, accepts only a recording within a few seconds of the catalogue's length, and asks the platform's player API for its audio while identifying as one of the platform's own client apps. The stream is Opus at up to about 160 kbps, not lossless, and the player's badge says so. Every stream's last bytes are read before it plays, because some clients' links stop at about a minute.
3. **The public mirrors and the catalogue's own manifest endpoint**, which serve 30-second previews, labelled PREVIEW in the player.

The full-length source can be switched off in Settings. It is unofficial and the platform can change the rules at any time, so the list of client identities lives in the `config/` directory of this repository and the app refreshes it from here, which means a broken client can be swapped without a new APK. Aoide only uses streams the platform hands out as plain links: it does not decode signature ciphers, run anti-bot challenges or execute any of the platform's JavaScript.

## Build

```bash
git clone https://github.com/retrocodes12/aoide
cd aoide
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Release builds are signed with a dedicated key so every build installs over the last one (`app/build.gradle.kts` reads it from `~/.aoide/release.jks` locally and from a secret in CI). The CI workflow publishes each version as a GitHub Release with the commit message as its notes; the app's update checker reads that page.

### Screenshots without an emulator

Every screen renders on the JVM through Robolectric's native graphics and Roborazzi, against the live catalogue:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:recordRoborazziDebug --tests app.aoide.Shots
```

PNGs land in `app/build/shots/`. This is how the app was audited on a laptop that cannot run the emulator. Two things the rig needs, both in `app/src/test/java/app/aoide/Shots.kt`: the native runtime is warmed up on the test thread before any image decodes, and artwork decodes on the main thread.

### Web preview

The `web/` directory holds the React version of the same design, deployed to GitHub Pages. It shares the catalogue layer's behaviour (mirror failover, upstream fallback, lyrics) and served as the audit ground before the native port.

```bash
cd web && npm ci && npm run dev
```

## Design notes

The structure of a mainstream streaming app: three tabs, a capsule mini player, a bottom-sheet track menu, chips, the browse grid. The polish of a premium one: centred artwork heads with paired Play and Shuffle pills, icon discs over artwork, the blurred now-playing stage, lossless badges. Type is Figtree, under the SIL Open Font License. Dark `#121212`, accent `#ff7a1f`. No orange ever sits on a tint; the tint's ink is solved for contrast in `app/src/main/java/app/aoide/ui/theme/Tint.kt`.

## Disclaimer

Aoide is an independent project with no affiliation to, or endorsement from, any streaming service, catalogue provider, video platform or lyrics database whose public interfaces it reads. It plays what the mirrors you configure and the sources above return. Some of those sources' terms of service do not allow third-party apps to use them this way; respect the terms of the services you use. Album artwork and song titles shown in the screenshots belong to their rights holders and appear only as the app displays them.
