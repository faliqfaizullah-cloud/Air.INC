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
