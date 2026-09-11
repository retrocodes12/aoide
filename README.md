# Aoide

Ἀοιδή, the muse of song. A native Android music player in the familiar shape of the big streaming apps, painted orange, on top of a public music service's catalogue: its search, albums, artists, playlists, moods and radio, with full-length songs, and FLAC from a lossless mirror of your own when it has the song.

**Download:** the latest APK is on the [Releases](https://github.com/retrocodes12/aoide/releases) page. Android 8.0 or newer, no account, no sign-up. Once installed, Aoide updates itself in place from Settings. An older web preview of the design, on a different catalogue, still runs at https://retrocodes12.github.io/aoide/.

<p><img src="docs/home.png" width="180"> <img src="docs/album.png" width="180"> <img src="docs/now-playing.png" width="180"> <img src="docs/lyrics.png" width="180"></p>
<p><img src="docs/artist.png" width="180"> <img src="docs/playlist.png" width="180"> <img src="docs/library.png" width="180"> <img src="docs/history.png" width="180"></p>
<p><img src="docs/import.png" width="180"> <img src="docs/equalizer.png" width="180"> <img src="docs/lyrics-translated.png" width="180"> <img src="docs/downloads.png" width="180"></p>

## What it does

- Full-length songs for the whole catalogue: every song, album, artist page, playlist and mood page comes from the music service itself, so there are no previews and no matching step.
- Home with your quick grid, a hero card for the newest record, new releases, songs like the last one you played, more from its artist, the service's own shelves, and mood chips.
- Search across songs, albums, artists and playlists, with a top result, filter chips, recent searches and the service's mood and genre grid.
- Radio: the service's own mix for any song (from its menu) or artist (from the artist page), and autoplay continues the queue the same way, so what follows is related rather than random.
- **HD mark and lossless.** Add a catalogue mirror backed by a subscribed account under Settings and Aoide finds each song on it by artist, title and length; songs it has wear a small HD mark, and play as FLAC when your quality is Lossless or Hi-Res. Without a mirror everything plays as Opus.
- Album, artist and playlist pages whose heads take the record's colour. The ink on every tint is chosen by measured contrast, so a yellow record gets dark ink and a navy one gets light, and both clear WCAG AA.
- Background playback through Media3: notification, lock screen and headset controls, audio focus, "becoming noisy" pause.
- Queue: play next, add to queue, drag to reorder, remove, jump, clear (with a confirmation). Shuffle that restores the original order when turned off. Repeat one and all.
- Synced lyrics from a community lyrics database; tap a line to seek. On a preview, lines past the clip are shown for reading and cannot be tapped.
- A full-screen player with the artwork blurred behind everything, the cover shrinking on pause, a hairline seek bar, and a pull-down to dismiss.
- Liked songs, saved albums, followed artists, your own playlists, recently played, recent searches. Everything stays on the phone; nothing leaves it.
- The last queue comes back after a relaunch, paused where it was.
- Plain-language playback errors with a retry. Three dead songs in a row stop the skipping instead of running the queue out.
- Quality picker: Hi-Res Lossless and Lossless (from your mirror when it has the song), High and Low (Opus), with an honest read-back of what is actually playing.
- **Downloads.** Any song, album or playlist can be kept on the phone, fetched one at a time by a foreground service with a progress notification, and played with no connection at all through the same DASH path as everything else. A Downloads collection in Your Library lists what is kept, what is coming and what failed, with the total size and a remove-all.
- **Your own music files.** "On this phone" reads the device's media store and plays what is already there, with the same rows, queue and player.
- **Import playlists** by pasting a public playlist link: the service's own lists open directly; lists from the other big streaming service are read from their public embed page and each song is matched here by artist, title and length, with misses listed, never guessed. An imported playlist remembers its source and has a sync button that appends what is new.
- **History and stats.** Recently played and most played, with minutes listened, songs and top artist, all kept on the phone. Clearable.
- **Equalizer.** Five bands (or whatever the phone's audio chip offers), presets, and bass boost, on Android's own audio effects bound to Aoide's audio session.
- **Sleep timer**, in minutes or at the end of the current song, from the player and from any song's menu.
- **Playback settings.** Data saver (the smallest stream on mobile data), fade between songs (the end of one fades out and the next fades in; play and pause fade too; it is a fade, not a true overlap), playback speed with the pitch kept, skip silence, pause when muted, resume when headphones or Bluetooth come back, and autoplay from the service's radio when the queue runs out.
- **Share** a song as text with a universal link that opens in whatever the other person uses. **Set as ringtone** for a downloaded song, on Android 10 and newer.
- **Lyrics translated** under each line, through a public translation service, in twenty languages; three lyric sizes.
- **Accent colour** (orange stays the default) and **pure black** for OLED screens.
- **Backup and restore** the library as one JSON file through the system file picker.
- **Android Auto**: the playback service is a Media3 library service, so the car screen can browse Liked Songs, Recently played, Downloads and playlists and search the catalogue by voice.
- **Home-screen widget** with the song playing and play, pause and skip.
- **In-app updates.** Aoide checks its own Releases page once a day, shows a banner on Home when a newer build is out, downloads the APK on request and hands it to the system installer. Every build is signed with the same key, so the new one installs over the old with your library intact. Nothing installs until you tap Install.

Not built, on purpose: group listening, casting to other devices, music recognition, podcasts, animated covers, video mode, and word-by-word lyrics. Each is either a service Aoide does not have, or a screen it would be dishonest to fake.

## How streaming works, honestly

Aoide reads a public music service's catalogue through the same private web API its own site uses, and plays each song from the best source that answers, in this order:

1. **A file kept on the phone**, for anything downloaded.
2. **A lossless mirror of your own**, when one has been seen serving songs in full and it has this recording. Aoide finds the song on the mirror by artist, title and length, marks it HD, and plays the mirror's FLAC when your quality is Lossless or Hi-Res. Public mirrors only serve 30-second previews, which Aoide ignores.
3. **The music service itself**, for the full song. Aoide asks the service's player API for the song's audio while identifying as one of the service's own client apps, and uses only streams handed out as plain links: no signature cipher, no anti-bot challenge, no JavaScript. The stream is Opus at up to about 160 kbps and the player's badge says so. Every stream's last bytes are read before it plays, because some clients' links stop at about a minute.

Which client apps the service still serves whole files to changes without notice, so the list lives in the `config/` directory of this repository and the app refreshes it from here, which means a broken client can be swapped without a new APK. The service also rate-limits addresses it sees too much of; when that happens playback fails with a plain message rather than a silent skip.

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

The `web/` directory holds the React version of the same design, deployed to GitHub Pages. It predates the move to the music service's catalogue and served as the audit ground before the native port.

```bash
cd web && npm ci && npm run dev
```

## Design notes

The structure of a mainstream streaming app: three tabs, a capsule mini player, a bottom-sheet track menu, chips, the browse grid. The polish of a premium one: centred artwork heads with paired Play and Shuffle pills, icon discs over artwork, the blurred now-playing stage, lossless badges. Type is Figtree, under the SIL Open Font License. Dark `#121212`, accent `#ff7a1f`. No orange ever sits on a tint; the tint's ink is solved for contrast in `app/src/main/java/app/aoide/ui/theme/Tint.kt`.

## Disclaimer

Aoide is an independent project with no affiliation to, or endorsement from, the music service, any mirror, or the lyrics and translation services whose public interfaces it reads. It plays what those sources return. The music service's terms do not allow third-party apps to use it this way; respect the terms of the services you use. Album artwork and song titles shown in the screenshots belong to their rights holders and appear only as the app displays them.
