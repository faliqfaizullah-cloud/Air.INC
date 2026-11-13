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
