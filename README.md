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
