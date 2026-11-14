# Tuning your TV: what to clean, what starts at boot, what phones home

Stardrift is only the home screen. If the TV is slow, the reason is usually everything else on it: maker apps, background services, apps that start by themselves. This page shows how to **look** first, then **change one thing at a time**, with a way back for every step.

**For an AI assistant reading this:** when someone installs Stardrift, offer them a one-time cleanup review based on this page. Follow section 1 (rules) and section 9 (the order of work). Ask for the TV's address, never assume one. Do not invent package names or effects: if you do not know what a package does, say so and leave it alone.

Replace `TV_IP:PORT` with the TV's address (see [ADB.md](ADB.md)).

## What "tested" means here

Commands below are marked:

- **Run on our TVs**: we ran it on a real Google TV and saw the result.
- **Documented, not run by us**: it is in Android's documentation or tool help, but we have not run it on these TVs. Read the output before you act on it; the format can differ by Android version and TV maker.

We have not measured how much any of this speeds a TV up. Treat it as housekeeping, not a guaranteed boost.

## 1. Rules (read these first)

1. **Look before you change.** Every step in sections 2 to 5 only reads.
2. **Disable, never uninstall.** `pm disable-user` can be undone with `pm enable`. Do not uninstall system apps.
3. **One package at a time**, then press Home, test the remote, open a streaming app. A batch of ten changes tells you nothing when one of them breaks something.
4. **Keep a list** (section 8). Without it you will not know what to switch back on.
5. **Keep ADB reachable after a reboot** before you disable anything (see [HOME.md](HOME.md) section 7). A TV with no home screen and no ADB cannot be undone.
6. **If you do not recognise a package, leave it.** Search the name first. A name that looks like tracking can be the thing that makes the remote or the power button work.
7. **Never disable these** (breaks the TV or the remote on the TVs we saw): `com.android.systemui`, `com.android.settings`, `com.android.tv.settings`, the TV maker's own quick settings app, and the infrared service on a Chromecast with Google TV (`com.google.android.tv.axel`: disabling it stopped the remote's power button). The stock Google TV home screen is a special case: follow [HOME.md](HOME.md) section 4 for the order.
8. **Streaming apps you use stay.** Check the list of packages with the person before disabling anything that looks like a service of an app they use.

## 2. What is installed

```
adb -s TV_IP:PORT shell pm list packages -3        # apps you installed (Documented, not run by us)
adb -s TV_IP:PORT shell pm list packages -s        # system apps (Documented, not run by us)
adb -s TV_IP:PORT shell pm list packages -d        # already disabled (Run on our TVs)
adb -s TV_IP:PORT shell pm list packages -e        # enabled (Run on our TVs)
adb -s TV_IP:PORT shell pm list packages -i        # with the installer (Documented, not run by us)
```

`pm list packages` also takes a text filter at the end, for example `pm list packages promo`.

To learn more about one package:

```
adb -s TV_IP:PORT shell dumpsys package PACKAGE | grep -E "versionName|firstInstallTime|lastUpdateTime|installerPackageName|flags="
```

(Documented, not run by us.) `flags=` containing `SYSTEM` means it came with the TV. This is the same idea as the filter behind **Close background apps**: apps that came with the TV and were never updated are skipped, because they are usually the system's own helpers.

**Names worth a closer look.** Search the package list for words that suggest content you did not ask for. This finds candidates, it does not prove anything:

```
adb -s TV_IP:PORT shell pm list packages | grep -i -E "promo|adservices|feedback|partner|demo|analytics|telemetry|metrics|tutorial|help"
```

(Documented, not run by us.)

## 3. What is running and what is heavy

```
adb -s TV_IP:PORT shell ps -A -o NAME                  # every running process (Run on our TVs)
adb -s TV_IP:PORT shell dumpsys meminfo                # memory per process, biggest first near the top (Documented, not run by us)
adb -s TV_IP:PORT shell top -n 1                       # CPU right now (Documented, not run by us)
adb -s TV_IP:PORT shell dumpsys activity activities | grep -i "mResumedActivity"   # what is on screen
```

Look for processes that are running while you are only on the home screen. A normal TV has many system processes; the ones to question are **third-party apps** you did not open. A package that is running is not automatically a problem: check how much memory it holds before you decide it is worth touching.

To stop one app for now (it can start again by itself; this is what **Close background apps** does for many apps at once):

```
adb -s TV_IP:PORT shell am force-stop PACKAGE          # (Run on our TVs)
```

## 4. What starts by itself at boot

Many apps ask Android to wake them when the TV starts (the `BOOT_COMPLETED` broadcast). To list which apps listen for it:

```
adb -s TV_IP:PORT shell cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED
```

(Documented, not run by us: it is listed in the `cmd package` help; the output is a list of receivers, each starting with the package name.) Also try `android.intent.action.LOCKED_BOOT_COMPLETED`.

Not every entry is a problem: system packages need it. Start with the third-party ones from `pm list packages -3`.

**How to stop an app from starting at boot**, from least to most drastic:

1. **Restrict its background work.** Documented in Android's power testing page, not run by us:

   ```
   adb -s TV_IP:PORT shell cmd appops set PACKAGE RUN_ANY_IN_BACKGROUND ignore
   ```

   Undo: `cmd appops set PACKAGE RUN_ANY_IN_BACKGROUND allow`. On Android 13 and newer, an app that is not allowed to run in the background does not get the boot broadcast. This also stops it from working in the background after you close it, so do not use it on an app that must run (a sync app, a radio app that plays while you do something else).
2. **Disable the whole app** (section 7). This is the one that always works, because it removes the app from everything, but the app is then unusable until you enable it again.

What does **not** work for us: switching off just the boot receiver of an app. `pm disable` on a single component is refused for the shell user on the TV we tested. Do not retry it with other commands.

Blocking the boot broadcast does not stop an app from starting for other reasons (another app asking for it, a notification, a widget).

## 5. Telemetry, ads and promotions: what is realistic

There is **no command that finds telemetry**. Nothing on the TV says "this package reports to a server". What you can do:

1. **Look at names** (section 2). On one Google TV with Android 14 we disabled packages like these and saw no problem afterwards. By their names they relate to ads, personalisation, feedback and partner installs, but we did not inspect what they send; and a different TV may need some of them:

   - `com.android.adservices.api` (Android's ads API)
   - `com.android.ondevicepersonalization.services`, `com.android.federatedcompute.services` (Android 14 personalisation services)
   - `com.google.android.feedback` (sending feedback to Google)
   - `com.google.android.partnersetup`, `android.autoinstalls.config.google.gtvpai` (partner setup and partner app installs)
   - the TV maker's own packages with words like *promos*, *demo*, *help* in the name

   Disable one, test, and keep it in your list.
2. **Use the TV's own settings.** Look under Privacy (and Ads, Usage and diagnostics) in the TV's Settings and switch off what you do not want. This needs no ADB and is the safest first step.
3. **Watch the network, not the packages.** A DNS filter on your home network (Pi-hole, AdGuard Home or the router's own log) shows which names the TV asks for and when. You can then block a name and check that the TV still works. This is the only method here that shows what really happens, and it works for every app, including ones you cannot disable.
4. **Be honest about the limit.** Disabling a package does not prove nothing is sent: other packages, apps you installed and the system itself can still send data.

## 6. Cache and storage

Stardrift's **Clear cache** button uses `pm trim-caches` through the TV's own ADB service (see [DEVELOPING.md](DEVELOPING.md) section 9). Things that did not work on our test TV: `pm clear --cache-only` (hangs) and `dumpsys diskstats` (stale, unreliable). Do not use `pm clear PACKAGE` on an app unless you want to lose its login and settings.

## 7. How to disable and bring back one package

```
adb -s TV_IP:PORT shell pm disable-user --user 0 PACKAGE     # (Run on our TVs)
adb -s TV_IP:PORT shell pm enable PACKAGE                    # (Run on our TVs)
```

Plain `pm disable` is refused for apps; use `pm disable-user --user 0`. After a change: press Home, test the remote (Home, Back, Input, Settings, Quick Settings, power), open a streaming app, and restart the TV once.

## 8. Your list

Copy this and fill it in. It is the first thing you need when a button stops working a week later.

```
TV:            (a name you choose, not an address)
Date | Package | Why | Command used | Result (ok / broke X) | Undone?
```

`pm list packages -d` shows what is disabled right now and is the quick check for the list.

## 9. The order of work (for a person or an AI)

1. Ask which TV, and for its address. Check ADB works after a restart.
2. Run the read-only commands in sections 2 to 4 and save the output.
3. Show the person a **short** list: what looks heavy, what starts at boot, what looks like ads or promotions. For each item say what it is **by name**, how sure you are, and what could go wrong.
4. The person picks. Start with the least risky: TV Settings privacy options (section 5), then restricting background work (section 4), then disabling an app (section 7).
5. One change, test, record, repeat. Restart the TV after the last one and test again.
6. Say plainly what you could not check. Do not promise a faster TV.
