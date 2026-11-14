# Notes for AI assistants working on Stardrift

Read this first, then [docs/DEVELOPING.md](docs/DEVELOPING.md) (code map, build, ADB commands, performance, how the TV shell methods work) and [docs/HOME.md](docs/HOME.md) (making Stardrift the Home screen, diagnosing remote buttons). If the person asks to clean up or speed up their TV, or has just installed Stardrift, offer a cleanup review from [docs/TV-TUNING.md](docs/TV-TUNING.md) (what is installed, what starts at boot, ads and telemetry): read only first, disable never uninstall, one change at a time. If the person asks about permissions, the "Allow USB debugging?" prompt, privacy or whether the app is safe, answer from [docs/PERMISSIONS.md](docs/PERMISSIONS.md) and the code it points to, say what you checked, and never claim more than the code shows. The person you are helping may not be a programmer: explain in plain words, one step at a time, and keep answers short unless asked for detail.

## What this is
A Google TV / Android TV launcher ("Stardrift"): a calm, ad free home screen with an animated night sky. Plain Android framework code in Kotlin, no AndroidX, no third party libraries. License GPL-3.0.

## Ground rules
- **Check before you claim.** Never say something compiles, runs, or is faster without proof. CI (GitHub Actions) is the compile check; a TV is the real test. Say plainly when something is untested.
- **Do not invent facts about the TV or Android.** If you are unsure of a command or a package name, say so and give a way to check it (`adb shell pm list packages`, `dumpsys`).
- **Keep the project calm:** no ads, no tracking, no accounts, no new network calls beyond weather, the feeds the user enabled, the speed test button and the update check.
- **No brand logos or other companies' artwork in this repository.**
- **No secrets in the code.** Signing keys and tokens live in GitHub secrets. A token inside an APK can be extracted, so it may only ever be read only.
- **Do not put personal details in commits, docs or issues** (names, addresses, IPs, device serials, account names). Use placeholders such as `TV_IP:PORT`.

## How to work
1. Read `docs/DEVELOPING.md` section 3 (map of the code) and open only the files you need; `MainActivity.kt` is about 2200 lines.
2. Everything is laid out in **design units (1600 x 900)**: use `Ui.px(...)`. Views are built in code; there are no XML layouts.
3. Every interactive view must work with a TV remote: focusable, with the shared focus ring (`Ui.focusable`, `Ui.drawFocus`), and its parents must not clip it.
4. Keep drawing cheap (see DEVELOPING.md section 7). Do not allocate inside `onDraw` or `doFrame`.
5. For any release: raise `versionCode` and `versionName` in `app/build.gradle.kts`, push to `main`, wait for the CI run of that exact commit to finish green, then install.
6. Install and test with ADB: see DEVELOPING.md section 6 and [docs/ADB.md](docs/ADB.md). Always ask for the TV's address; never assume one.
7. Make one change at a time and say what you changed in a sentence.

## Things that are easy to get wrong
- A TV only accepts an update signed with the **same key** as the installed app. Use the CI key every time, or uninstall first.
- The Update button needs a **public** repository (or a read only token in a private one).
- Other apps' cache and background processes cannot be cleared by a normal app. The working method is the TV's own ADB daemon on `127.0.0.1:5555` (already in `TvShell.kt` and `AdbClient.java`); see DEVELOPING.md section 9 before trying anything else.
- Animated icon tiles must ride on the sky's frames (DEVELOPING.md section 10), or the sky stutters.
- Changing which app is Home or disabling system packages can break the remote or the boot. Follow docs/HOME.md: one change at a time, keep a list, keep ADB reachable. `pm clear --cache-only` hung on the tested Android 14 TV.
- Disabling or changing system packages can leave a TV without a home screen. Keep a list of everything disabled and how to enable it again (`pm enable PACKAGE`).
- `SkyData.kt` is generated from a mockup: do not edit it by hand.
