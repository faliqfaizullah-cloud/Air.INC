# Air.INC
Minimal "Air OS" style full-screen music player (Android).

## Publish from Termux
```
pkg install -y unzip
unzip AirInc.zip && cd AirInc
bash publish.sh <your-username>
```
GitHub Actions builds `Air.INC.apk` automatically (Actions tab -> artifact, or Releases for tag v1.0).
No Gradle/Android SDK needed on the phone.

## Memories
Catch a memory with the camera, a video, the gallery, your voice or a written note. Everything is saved privately inside the app (Favorites collage, Capture view, All memories).

## Android 8+
minSdk 24 (Android 7+), so Android 8 (API 26) is fully supported.

## Karaoke
Lyrics screen -> KARAOKE: word-by-word lyrics, vocal remover (centre-channel removal between 140 Hz and 8.5 kHz, bass/kick/cymbals/panned instruments kept), key change +/-6 semitones, lyric sync nudge, sing-along with score (saved to Memories).

## In-app updates
Builds are signed with the fixed key `app/air-release.jks`, so a new release installs over the old app (no uninstall, data kept). Tap the download-arrow button on the Music screen to check GitHub Releases, download and install.

## Motion
Screen transitions use depth + slide with staggered entrances (compositor-only). Controls ripple on tap, and scrolling/swiping applies a light speed-based directional motion blur. If frames drop, the blur turns itself off for that session. Animations pause when the app is hidden.

## Windows version
`windows/` is an Electron app that reuses the same UI (`app/src/main/assets/index.html`) with a desktop arrangement:
sidebar with every section, the section in a centred stage, a searchable library/queue panel on the right and a
full-width now-playing bar. Audio runs on Web Audio (5-band EQ, bass, loudness, real-time vocal remover, key shift
without changing tempo), media keys + Windows media overlay, tray icon so the alarm can ring, in-app updates.

Shortcuts: Space play/pause, ←/→ seek 5 s (Shift 15 s), Ctrl+←/→ previous/next, ↑/↓ volume, M mute, S shuffle, R repeat,
L lyrics, K karaoke, Ctrl+K or / search, Alt+1…9 sections, Esc close, F11 full screen.

Build: push a tag (e.g. `v3.1`) – GitHub Actions attaches `Air.INC-Setup-<ver>.exe` (installer) and
`Air.INC-<ver>-portable.exe` to the same release as the APK. Local: `cd windows && npm install && npm start`.

## Link devices (phone ⇄ Windows)
Open **Link** (sidebar → *Devices* on Windows, menu on the phone) and scroll down: your **QR code** and 10-digit **code** are there, plus *Enter a code* / *Scan QR code*.
Either device can show the code and the other one connects; pairing is mutual, so afterwards both can control and send songs to each other.
- Control the other device (play, pause, seek, volume, queue) and browse its songs.
- **Send a song** to the other device (saved in its `Air.INC` music folder).
- **Play my song there** streams a song from the phone to the PC without copying it (PC only).
- Both devices must be on the same Wi-Fi / network. Windows asks for a Firewall exception the first time (allow *Private* networks).
- Security: the code is a short-lived pairing secret (6 wrong tries locks it for a minute); after pairing each device holds a random 192-bit key. Remove a device any time from *Allowed to control this device*.

## Sketch lyrics
In the lyrics view tap the song title and choose **Sketch**. Every lyric line becomes a hand-drawn 1-bit card inside a Decker-style window (menu bar, pens, markers, toolbar). Cards cut hard on each line, the drawing "boils" at ~12 fps, and the words of the line pick the doodle (moon + sea, ringed planet, heart, sun, eye, flower, rain, fire, bird, house, skull, stars, or a bust). Night cards get a navy dimmed window with a light halo behind the handwriting. The handwriting font is downloaded at build time (Shadows Into Light Two, with fallbacks).

### Drawing on Sketch cards
Pick a pen or marker from the right-hand column (fountain, fine, brush, spray, chisel; pink/cyan/yellow markers use the colours of the current card) and draw on the card with a finger or mouse. **OOPS!** undoes the last stroke, **ALTER** toggles the eraser, **OBLITERATE!** clears the card, the arrows next to *marker size* change the size, and **EXPORT** shares/saves the card with your drawing as a PNG. Your drawing is one shared layer: it stays on screen when the lyric card changes and is remembered after you close the app, until you press OBLITERATE!. Pen ink turns cream on night cards and black on light ones.
