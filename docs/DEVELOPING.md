# Developing Stardrift

For anyone who wants to continue the project on their own: change it, extend it, or hand it to an AI assistant. For using the launcher see [MANUAL.md](MANUAL.md); for building and installing see [BUILD.md](BUILD.md) and [ADB.md](ADB.md); for making it the Home screen and fixing remote buttons see [HOME.md](HOME.md); for cleaning up a TV see [TV-TUNING.md](TV-TUNING.md). AI assistants: start with [AGENTS.md](../AGENTS.md).

Placeholders used below: `TV_IP:PORT` is your TV's address (see [ADB.md](ADB.md)). Replace it everywhere.

## 1. Your own copy

- **A fork of a public repository is always public on GitHub.** That is fine for the launcher: the code is GPL-3.0 and the Update button works with public forks.
- **A private copy** is also fine (the license allows private use). Create an empty private repository, then push this code into it:
  ```
  git clone https://github.com/SpeakingColors/StarDriftTVLauncher.git
  cd StarDriftTVLauncher
  git remote set-url origin https://github.com/YOU/YOUR-PRIVATE-REPO.git
  git push -u origin main
  ```
  It is no longer a fork, so GitHub will not offer to sync it. Nothing else changes: add the two signing secrets ([BUILD.md](BUILD.md)) and the workflow builds it.
- **The Update button needs a public repository** (it reads the `latest` release without logging in). In a private copy the button shows, but the release cannot be read without a token, so it never finds an update. To keep it you need a read only token baked into the build; see section 8.
- If you share a modified version, share its source under the same license.

## 2. Building

- **GitHub Actions is the tested way.** Push to `main` (or run the workflow by hand). It needs `KEYSTORE_B64` and `KEYSTORE_PASSWORD`. A push to `main` builds, signs and replaces the `latest` release.
- **On your computer** (not tested by the author, the CI is): the Gradle wrapper is included, so `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`) should work with a JDK 17 and the Android SDK (platform 34) installed, for example through Android Studio. The APK lands in `app/build/outputs/apk/`.
  - A debug build is signed with Android's debug key. A TV refuses to update an app signed with another key, so uninstall the CI build first (settings reset), and the reverse.
  - A release build locally needs `KEYSTORE_FILE` (path) and `KEYSTORE_PASSWORD` set as environment variables; the key alias is `tvlauncher`.
  - The workflow installs Gradle 8.9 itself; the wrapper points at the same version.
- **Version:** raise `versionCode` and `versionName` in `app/build.gradle.kts` for every release. The Update button only offers a build whose `versionCode` is higher than the installed one.
- Project facts: `minSdk` 26, `targetSdk` and `compileSdk` 34, Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Java 17. No AndroidX and no third party libraries: everything is plain Android framework code.

## 3. Map of the code

All code is in `app/src/main/java/dev/tvlauncher/home/`. There are no XML layouts: every screen is built in code.

| File | What it does |
|---|---|
| `MainActivity.kt` | The home screen and almost all UI: header, clock, weather box, dock, rows, Settings / Network / Inputs / Sound popups, quick actions. By far the biggest file (about 2200 lines). |
| `Ui.kt` | Shared look and helpers. **Design units are 1600 x 900**; `Ui.s` is pixels per unit, `Ui.px(u)` converts. Also the focus ring and glow (`drawFocus`). |
| `SpaceView.kt` | The animated sky (home and screensaver). Frame pacing, baked bitmaps, shooting stars, comet, satellite, nova. |
| `SkyNew.kt`, `SkyData.kt`, `assets/sky_new.json`, `assets/sky_band_*.webp` | Sky layout data (galaxies, nebulae, star boxes) and the milky way pictures. `SkyData.kt` is generated from the mockup: do not edit by hand. |
| `docs/mockup/` | The interactive mockup (sample content, plain web page). The sky data above is generated from it. See its README. |
| `StardriftDream.kt` | The screensaver (`DreamService`): same sky, 24 hour clock, date, Wi-Fi icon. |
| `Apps.kt`, `AppTile.kt`, `IconArt.kt`, `IconStyle.kt` | Installed apps, the pinned order, hidden apps, tiles and the icon looks (Original, Black, White). |
| `HomeRows.kt`, `PickCard.kt`, `ContinueCard.kt`, `WatchNext.kt` | The rows under the header: which show and in what order, the Picks cards, Continue watching (reads Google TV rows, may come back empty). |
| `Feeds.kt`, `Images.kt`, `PhoneAdd.kt` | RSS feeds, a small image loader with a memory cache, and the tiny web page for adding feeds from a phone (PIN protected, runs only while that screen is open). |
| `Weather.kt`, `WeatherIconView.kt` | Open-Meteo weather (no key) and the icons drawn on a 64 x 64 grid. |
| `SpeedTest.kt` | Download speed and latency against Cloudflare's public endpoint, only when the button is pressed. |
| `HomeMusic.kt`, `Sfx.kt` | The ambient loop (raw PCM fed to an `AudioTrack`) and the interface sounds (`SoundPool`). Files are in `res/raw/`, see [CUSTOMIZE.md](CUSTOMIZE.md). |
| `TvShell.kt`, `AdbClient.java` | The real Close background apps and Clear cache: a tiny ADB client that talks to the TV's own ADB daemon (section 9). |
| `QuickKeyService.kt` | Optional accessibility service that opens the TV's own Quick Settings bar. Off by default. |
| `Updater.kt`, `UpdateReceiver.kt` | The Update button: reads your fork's `latest` release, downloads the APK, installs with `PackageInstaller`. |
| `Dialogs.kt`, `GlowRow.kt` | Menus (including press and hold) and focusable rows with the shared glow. |

Settings are kept in one `SharedPreferences` file named `tvlauncher`. Keys include `name`, `city_name`, `city_lat`, `city_lon`, `wx_json`, `pinned`, `hidden`, `rows_order`, `rows_off`, `look_<package>`, `play_style`, `motion`, `music`, `music_vol`, `fx`, `fx_vol`, and `upd_at` / `upd_code` / `upd_name` (update check).

## 4. Rules the code follows

- **Calm by design.** No ads, no suggestions, no sponsored items, no tracking, no accounts. The only network calls are weather, the feeds and images you enabled, the speed test button and the update check. Keep it that way.
- **Everything is laid out in design units** (1600 x 900), so it looks the same on every TV resolution. Use `Ui.px` / `Ui.pxi`; do not hard code pixels.
- **Remote control first.** Every interactive view must be focusable (`Ui.focusable`) and show the shared focus ring. Parents of focusable items must not clip, or the glow is cut off.
- **Draw cheaply.** Big pictures are baked once and only slid; small things are drawn where they are (see section 7).
- **Keep it dependency free.** No AndroidX, no image or network libraries. `Images.kt` and the `HttpURLConnection` calls are enough.
- **Do not add brand logos** to the public code. App icons come from the apps installed on the TV.
- Work off the main thread for network and disk; post results back to the UI thread.

## 5. Release flow

1. Raise `versionCode` and `versionName` (`app/build.gradle.kts`).
2. Push to `main`. CI builds and signs the APK and replaces the `latest` release (`tvlauncher.apk`). The release text carries `{"code":N,"name":"x"}`, which the Update button reads.
3. On the TV, **Settings, Launcher, Update** (or `adb install -r`).

A branch that is not `main` can be run by hand from the Actions tab; use that as a compile check without touching the release.

## 6. Handy ADB commands

Connect and install (see [ADB.md](ADB.md) for turning debugging on):

```
adb connect TV_IP:PORT
adb -s TV_IP:PORT install -r tvlauncher.apk
adb -s TV_IP:PORT shell am start -n dev.tvlauncher.home/.MainActivity
```

The one permission the ADB switch, the Quick Settings key switch and the commands in section 9 need:

```
adb -s TV_IP:PORT shell pm grant dev.tvlauncher.home android.permission.WRITE_SECURE_SETTINGS
```

Look at what is going on:

```
adb -s TV_IP:PORT logcat | grep -i tvlauncher          # the launcher's log
adb -s TV_IP:PORT shell dumpsys meminfo dev.tvlauncher.home   # memory use
adb -s TV_IP:PORT shell dumpsys gfxinfo dev.tvlauncher.home   # frame statistics (janky frames)
adb -s TV_IP:PORT shell dumpsys gfxinfo dev.tvlauncher.home reset   # start counting again
adb -s TV_IP:PORT shell dumpsys activity activities | grep -i "mResumedActivity\|topResumed"   # who is on screen
adb -s TV_IP:PORT shell top -n 1                       # what is using the CPU right now
adb -s TV_IP:PORT shell pm list packages -d            # packages that are disabled
adb -s TV_IP:PORT shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # which app is Home
```

System settings the launcher reads or that matter for a TV:

```
adb -s TV_IP:PORT shell settings get system screen_off_timeout   # milliseconds; on Android TV this is also when the screensaver starts
adb -s TV_IP:PORT shell settings get secure screensaver_enabled
adb -s TV_IP:PORT shell settings get global adb_enabled
```

Anything you disable on the TV (`pm disable-user --user 0 PACKAGE`) can be undone with `pm enable PACKAGE`; keep a list of what you disabled.

## 7. Performance and GPU

TVs, and Google TV streamers in particular, have weak GPUs and little memory. The sky is the most expensive thing the launcher draws, so it is built to be cheap. What the code does today:

- **About 30 frames a second, not 60** (`FRAME_MS` in `SpaceView.kt`). A `Choreographer` callback ticks the sky. Animated icon tiles are meant to ride on the same ticks, so the screen is composed 30 times a second (this repository has the hook but no animated tiles; see section 10).
- **Bake once, then slide.** The milky way is two big bitmaps (`TILE` units wide, scaled by `BAND_SCALE`), made once per process and shared by Home and the screensaver. Each frame only moves them.
- **Everything else is drawn where it is.** Stars, galaxy sprites and glows are small draws, so the GPU never fills a whole screen for a few dots.
- **It stops when it is not seen.** `onPause` stops the sky and the music. The `motion` preference (read in `MainActivity`, default on) freezes the sky when false; there is no switch for it in Settings yet, so a handy first project is to add one.
- Pictures from feeds go through `Images.kt` with a memory cache; keep them small.

Ideas to try, each one to be measured before and after (not all are proven on every TV):

- **Measure first.** Run `dumpsys gfxinfo dev.tvlauncher.home reset`, use the TV for a minute, then run `dumpsys gfxinfo dev.tvlauncher.home` and read "Janky frames" and the percentile times. `dumpsys gfxinfo dev.tvlauncher.home framestats` gives per frame timings.
- **Fewer or smaller layers:** lower `BAND_SCALE`, fewer galaxy sprites, fewer star groups (`STAR_GROUPS`), or a lower frame rate (raise `FRAME_MS`). Each is a quality against smoothness choice.
- **Avoid overdraw:** do not stack large translucent rectangles; clip or shrink what you draw. Android's "Debug GPU overdraw" in Developer options shows it.
- **Prefer bitmaps you reuse** over paths you rebuild each frame; avoid allocating objects inside `doFrame` or `onDraw`.
- **Free memory for the GPU:** closing background apps (section 9) leaves more RAM, which helps most on 1.5 to 2 GB devices.
- **Lower the render resolution of the whole TV** if the screen is 4K and the GPU struggles: `adb shell wm size 1920x1080` (undo with `adb shell wm size reset`). This changes the whole system, so test it carefully. The launcher scales itself, so it still lays out correctly.
- **Shorter system animations** make the whole TV feel quicker: `adb shell settings put global window_animation_scale 0.5` (also `transition_animation_scale` and `animator_duration_scale`; `1.0` is the default).
- **Compile the app ahead of time** after an install: `adb shell cmd package compile -m speed-profile -f dev.tvlauncher.home`.
- **Heat:** software cannot cool a TV. If a TV gets hot it may slow itself down (throttle) and drop frames; `dumpsys thermalservice` may show it on some TVs. The only things that help are doing less work (the points above, closing background apps) and giving the TV air. We have not seen evidence of throttling on the TVs this was built on; this is a generic check, not a finding.

## 8. The Update button, and keeping it in a private copy

`Updater.kt` reads `https://api.github.com/repos/<your repo>/releases/tags/latest`. The repository name comes from `BuildConfig.UPDATE_REPO`, which GitHub Actions fills in (`GITHUB_REPOSITORY`); a local build has none and the button is hidden. It reads the release text (`{"code":N,"name":"x"}`), downloads `tvlauncher.apk`, and installs it through a `PackageInstaller` session. The installer's answer goes to `UpdateReceiver`, which opens Android's confirmation screen. The result `PendingIntent` must name the receiver explicitly (a plain action does not reach it, because the receiver has no intent filter).

For a **private repository** the same code needs a token. The usual approach is a fine grained, **read only** token for that one repository, stored as a repository secret and written into `BuildConfig` at build time, sent as `Authorization: Bearer`. Downloading the asset needs `Accept: application/octet-stream`, and the redirect must be followed without the Authorization header. Two warnings: a token inside an APK can be extracted by anyone who has the APK, so it must only ever be able to read; and never put a token that can write into the app.

## 9. Closing background apps and clearing cache

Since Android 8 an app can only stop its own background processes (`killBackgroundProcesses` does nothing to other apps), and an app cannot clear other apps' cache at all (that permission is for system apps). The method below really works on the TVs it was tested on, and it is what the **Close background apps** and **Clear cache** buttons in Settings use (`TvShell.kt`, `AdbClient.java`). If ADB is not available, Clear cache falls back to asking Android for free space (`StorageManager.allocateBytes`), which usually frees little.

**The method: talk to the TV's own ADB daemon from inside the app.**

1. The app needs `WRITE_SECURE_SETTINGS` (the one `pm grant` command above). With it the app can switch `Settings.Global` `adb_enabled` on for the duration of the work and off again afterwards (only if it was off before).
2. The TV's `adbd` listens on TCP port 5555 on the loopback address `127.0.0.1` (on the tested TVs, while USB debugging is on). The app connects to it as an ordinary ADB client.
3. A tiny ADB client is about 260 lines of plain Java with no libraries: open a socket, send `CNXN`; the TV answers with an `AUTH` token; sign it with an RSA key that the app creates and keeps in its private storage; if the TV does not know the key yet, send the public key (`AUTH_RSAPUBLICKEY`), and the TV shows **Allow USB debugging?** once (tick *Always allow*); then `OPEN` a `shell:<command>` stream and read `WRTE` packets until `CLSE`. The legacy shell service is enough: it merges stdout and stderr and gives no exit code, which is fine here. The protocol is documented in the Android source: `platform/packages/modules/adb`, file `protocol.txt`.
4. Do the work on a background thread (never the main thread), with timeouts, and turn `adb_enabled` back off in a `finally` block.

**Close background apps:**

```
ps -A -o NAME                  # what is running; names look like com.app or com.app:service
am force-stop com.example.app  # stop one app (the launcher sends them in chunks of 20, joined with ;)
```

The launcher takes its own list of installed apps, keeps only those that are running (if `ps` returns nothing it tries them all), and stops the rest. It skips itself, a short `neverStop` list (system UI, settings, the home screen packages) and apps that came with the TV and were never updated (`FLAG_SYSTEM` without `FLAG_UPDATED_SYSTEM_APP`), because those are usually the system's own helpers. That last filter is on the careful side and was not tuned on many TVs: if an app you want closed is skipped, adjust `isStockApp`. It reads free memory (`ActivityManager.getMemoryInfo`) before and after to tell you how much was freed.

**Clear cache:**

```
pm trim-caches <bytes>
```

`trim-caches` asks the system to delete cached files until that much space is free. Passing a number larger than the disk (the total size from `StatFs`) clears all of it. Free space is read before and after to report the result. Things that did **not** work on the tested Android 14 TV: `pm clear --cache-only` for each app hangs, and `dumpsys diskstats` is stale and unreliable for sizes. If the ADB route fails, fall back to the `allocateBytes` method above.

**First use:** the TV shows **Allow USB debugging?** once; tick *Always allow*. The app also needs the one-time `WRITE_SECURE_SETTINGS` permission (section 6). If either is missing the buttons tell you and fall back.

**Safety notes:** this gives the app the power of the ADB shell for a moment, so only run fixed commands you wrote, never anything built from feed or network text. The key never leaves the app's private storage. Keep the ADB switch in Settings off when you are not using it.

## 10. Animated icon tiles (not included, and the limit to know about)

This repository has no animated tiles, but the sky has the hook for them, because animating a tile naively makes the whole launcher slower. If you add animated icons, read this first. Draw your own art; do not copy brand logos or characters.

**The idea.** An animated tile is a small class that draws into a 100 x 100 box and loops by the clock:

```kotlin
abstract class TileAnim(val periodMs: Long) {
    abstract fun draw(c: Canvas, phase: Float)   // phase runs 0..1 over periodMs
}
```

In the tile's `onDraw`, scale the canvas so the 100 x 100 box fills the tile, then draw:

```kotlin
val ph = (SystemClock.uptimeMillis() % an.periodMs).toFloat() / an.periodMs
c.save(); c.translate(dst.left, dst.top); c.scale(dst.width() / 100f, dst.height() / 100f)
an.draw(c, ph)
c.restore()
```

**The limit.** The sky is drawn on the same screen, and every time any view asks for a redraw, the whole sky is replayed on the render thread. A tile that calls `postInvalidateOnAnimation()` runs at the screen's 60 frames a second on top of the sky's own 30, which roughly doubles the work and makes the sky stutter on weak TVs.

**The fix (already in `SpaceView`).** While the sky is ticking, the tile does not ask for its own frames. It registers with the sky, which redraws all registered tiles on its own tick:

```kotlin
val now = SystemClock.uptimeMillis()
if (now < SpaceView.driveUntil) SpaceView.animTiles[this] = now   // ride on the sky's frame
else postInvalidateOnAnimation()                                   // sky is not ticking: drive yourself
```

`SpaceView.tickTiles()` invalidates every tile that drew in the last 250 ms and forgets the others (`animTiles` is a `WeakHashMap`, so closed screens do not leak). The screen is then composed 30 times a second, whether or not tiles are animating.

**Restart when Home comes back.** While Home is hidden (All apps open, an app in front, the screensaver) the tile does not draw, so the sky forgets it. When Home shows again, Android can reuse the tile's last picture without calling `onDraw`, and the animation stays frozen until focus touches the tile. Redraw once when the tile becomes visible:

```kotlin
override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    if (isVisible) invalidate()
}

override fun onWindowVisibilityChanged(visibility: Int) {
    super.onWindowVisibilityChanged(visibility)
    if (visibility == VISIBLE) invalidate()
}
```

**Keep the animation cheap.**
- Make `Paint`, `Path` and `RectF` objects once (in the constructor), never inside `draw`.
- Draw simple shapes; avoid decoding bitmaps in `draw`. Decode once and keep the bitmap.
- Keep the loop long enough to feel calm (6 to 30 seconds) with a few short moments of movement (a blink, a peek).
- Give the tile a still frame (a bitmap) for places that cannot animate, such as app lists.
- Test on the weakest TV you own: if the sky starts to stutter, lower the tile's detail first.

## 11. Testing and sending changes back

There are no automated tests; the check is to build (CI is the compile check) and use it on a real TV with a remote. When you change anything visible, look at it on the TV and use only the remote. Pull requests and issues to the original repository are not being reviewed ([README](../README.md#as-is)); your copy is yours to take wherever you like.
