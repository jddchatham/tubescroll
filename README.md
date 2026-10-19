# TubeScroll

YouTube channel scroller with yt-dlp downloads (MP4/MP3) via Termux.

## Features
channel RSS with hl=en workaround · add-by-handle · Termux RUN_COMMAND downloads · paste-any-URL download box

## Build (fully inside Termux, no Android Studio)

Toolchain: `aapt2`, `ecj`, `dx`, `zipalign`, `apksigner` (Termux packages), plus an API-33 `android.jar`.

```sh
./build.sh
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed; a keystore is auto-generated on first build).

Pure framework Java, no dependencies, no Gradle, minSdk 21.
