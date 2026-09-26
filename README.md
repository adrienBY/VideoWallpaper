# Video Wallpaper

Open-source Android live wallpaper that reacts to your lock screen.
Pick your own video for each state:

| State | Behaviour |
|---|---|
| **Locked** | loops while the screen is locked / off |
| **Unlock transition** | optional — plays once when you unlock, only if you've set a clip for it |
| **Unlocked** | loops after unlocking, until the screen turns off (then back to Locked) |

No network, no ads, no permissions. Videos are copied into the app's private storage.
Built with Kotlin and Media3 ExoPlayer only.

## Use it

1. Open the app, tap **Choose video** on Locked and Unlocked (or leave the defaults). The transition card is optional — leave it unset to cut straight from Locked to Unlocked, or add a clip to play something in between.
2. Tap **Set as wallpaper** and choose **Home and lock screen**.
3. Lock the phone, wake it, unlock.

Tap **Reset** on a card to go back to the bundled default clip (Locked/Unlocked) or to remove the transition clip.

## Build

Open the folder in Android Studio (it creates the Gradle wrapper on sync) and hit Run.
Or, with a wrapper present: `./gradlew assembleDebug`.
APK ends up in `app/build/outputs/apk/debug/`.

Requirements: JDK 17, Android SDK 35. minSdk 26.

## Video tips

- H.264 MP4 plays everywhere. Anything ExoPlayer supports works.
- Match your screen's aspect ratio (portrait, e.g. 1080x2400). The video is centre-cropped to fill.
- Keep loops short, and make the last frame of `Locked` / `Unlocked` match the first so loops are seamless.
- Make the last frame of `Locked` ≈ first frame of `Transition`, and the last frame of `Transition` ≈ first frame of `Unlocked`.
- Audio is muted.
- 1080p at 30fps is plenty. Bigger costs battery.

## How it works

- `VideoWallpaperService`: `WallpaperService` + `Engine`, renders ExoPlayer onto the wallpaper `SurfaceHolder`.
- Listens for `ACTION_USER_PRESENT` (unlock) and `ACTION_SCREEN_OFF` (reset).
- On unlock: if you've set a custom transition clip, queues `[transition, unlocked]` as a playlist and `Player.Listener.onMediaItemTransition` switches to looping the unlocked clip; otherwise it skips straight to looping Unlocked.
- `onVisibilityChanged` pauses/resumes playback.
- `VideoStore` keeps custom clips in `filesDir/videos/` and falls back to `res/raw/` defaults.
- `MainActivity` is the picker UI (also opens from the wallpaper picker's settings gear).

## Known limits

- Some OEMs restrict live wallpapers on the lock screen or kill background work aggressively. Behaviour varies by device.
- Changes to your clips apply next time the wallpaper becomes visible.

## Contributing

Issues and PRs welcome. Keep dependencies minimal.

## License

MIT, see `LICENSE`.
