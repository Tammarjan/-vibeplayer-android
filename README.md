# VibePlayer

Premium, offline-first Android video player.

## Current features
- Scans videos indexed by Android MediaStore.
- Search local video filenames.
- Plays local videos with AndroidX Media3 / ExoPlayer.
- Built-in playback controls, seeking, and aspect-ratio handling.
- No sign-in, ads SDK, streaming service, or network permission.

## Build an APK
Every push to `main` and every manual workflow run builds a debug APK.
Open **Actions → Android APK → latest successful run → Artifacts** and download
`VibePlayer-debug-apk`. Extract the ZIP to get `app-debug.apk`.

## Build locally
Requires JDK 17 and Gradle 8.9:
```bash
gradle assembleDebug
```

## Android permissions
The app requests access to videos on the device. On Android 14+, users may grant
access to selected videos only; the library will show the videos Android makes
available to the app.

## Package
- Application ID: `com.vibeplayer.offline`
- Minimum Android: 6.0 (API 23)
- Target Android: API 35
