# What Stardrift asks for, why, and how to check it

Stardrift asks for more than a normal app, so you should know exactly what and why. This page lists every permission and prompt, what it **allows in general**, what Stardrift **actually does** with it, and how you can **check that for yourself**. You do not have to take our word for it: the whole source code is in this repository.

**If you do not understand something here, do not grant it.** The launcher works without the optional parts (see section 5).

## 1. The short version

- No accounts, no ads, no analytics, no crash reporting, no tracking. The app has no third party libraries (there is no `dependencies` section in `app/build.gradle.kts`), and a search of the code for common tracking and ad tools finds none.
- It talks to the internet only for the things in section 4.
- The strong permissions (section 3) are **optional**, are **off by default**, and each one is explained below.
- This project does not publish a ready made APK. You build your own from the source (see [BUILD.md](BUILD.md)), so what runs on your TV is code you can read.

## 2. Permissions the app declares

These are in `app/src/main/AndroidManifest.xml`. You can read that file in a minute.

| Permission | Why it is there | What Stardrift does with it |
|---|---|---|
| `INTERNET` | Weather, news feeds, pictures, speed test, update check (section 4) | Only the connections listed in section 4. The small "add from your phone" page also needs it (section 3, item 5) |
| `ACCESS_NETWORK_STATE` | The Wi-Fi icon and the Network popup | Reads whether the TV is online and on which kind of network |
| `ACCESS_WIFI_STATE` | The signal quality and link speed in the Network popup | Reads the current Wi-Fi signal strength and link speed. It does not scan for other networks |
| `READ_TV_LISTINGS` | The **Continue watching** and **New series** rows | Reads the "Watch Next" rows that your streaming apps share with the TV home screen: title, episode, progress and a picture address. It stays on the TV. It can come back empty, because Android only gives a normal launcher the rows that apps mark as shareable |
| `REQUEST_DELETE_PACKAGES` | **Uninstall** in an app's long press menu | Asks Android to uninstall an app. Android shows its own confirmation, nothing is removed without you pressing OK there |
| `REQUEST_INSTALL_PACKAGES` | The **Update** button (only in builds made in your own fork) | Installs the newer version of Stardrift. Android shows its own confirmation, and only an update signed with the same key as the installed app is accepted |
| `WRITE_SECURE_SETTINGS` | See section 3, item 1 | **The strong one.** Read that section |

The manifest also has a `queries` list. It lets Stardrift see which apps are installed and launchable (that is how the app dock and **All apps** work), the Play Store app, and the TV maker's own quick settings app. Stardrift does not send the list of your apps anywhere.

## 3. The strong ones: what they allow, what Stardrift does, how to check

### 1. `WRITE_SECURE_SETTINGS` (given once over ADB)

- **Why it needs ADB:** Android does not let a normal app have this permission. It can only be granted from a computer with one command: `adb shell pm grant dev.tvlauncher.home android.permission.WRITE_SECURE_SETTINGS` (see [ADB.md](ADB.md)).
- **What it allows in general:** changing protected system settings, for example switching USB debugging on or off, or switching an accessibility service on. An app with this permission could change more than Stardrift does, so only grant it to code you trust.
- **What Stardrift does with it:** three things only. (a) The **ADB** switch in Settings turns USB debugging on or off. (b) The **Quick Settings key** switch adds or removes Stardrift's own helper in the list of accessibility services. (c) **Close background apps** and **Clear cache** turn USB debugging on for a moment and off again afterwards (if it was off).
- **Check it yourself:** search the code for the places that write settings: `grep -rn "Settings.Global.putInt\|Settings.Secure.put" app/src`. There are a handful of lines, in `MainActivity.kt`, `TvShell.kt` and `QuickKeyService.kt`.
- **Take it back:** `adb shell pm revoke dev.tvlauncher.home android.permission.WRITE_SECURE_SETTINGS` (documented by Android, not run by us). Uninstalling Stardrift removes it too.

### 2. The **Allow USB debugging?** prompt on the TV

- **When it appears:** the first time you press **Close background apps** or **Clear cache**.
- **Why:** these buttons need to stop other apps and trim their caches, which a normal app is not allowed to do. The only way that works on the TVs we tested is to ask the TV's own debugging service to do it, the same service your computer uses with ADB.
- **What the app does:** it connects to the TV **itself** (the address `127.0.0.1`, port `5555`), not to your network or the internet. It signs in with a key it creates and keeps in its own private storage (`adb_key.pk8`). Ticking **Always allow** lets that key connect without asking again.
- **Which commands it sends:** only these: `ps -A -o NAME` (list running apps), `am force-stop PACKAGE` (stop an app), and `pm trim-caches N` (free cache space). You can read them in `MainActivity.kt` (search for `c.shell(`).
- **What this access allows in general:** USB debugging access can run any command on the TV. That is why it is switched on only for the moment of the action, and why you should not leave **Always allow** on for computers or keys you do not know. Stardrift's own code limits itself to the three commands above; you can verify that by reading `TvShell.kt`, `AdbClient.java` and the `shell(` calls.
- **Take it back:** in the TV's Developer options, **Revoke USB debugging authorizations**, and keep the **ADB** switch in Stardrift's Settings off.

### 3. Accessibility service (the optional **Quick Settings key** helper)

- **Off by default.** It is only useful on a TV that has its own Quick Settings bar and a remote key for it, and only if that key stops working (see [HOME.md](HOME.md) section 6).
- **What an accessibility service allows in general:** the permission we declare (`canRetrieveWindowContent`) technically lets a service read what is on screen. That is a lot of power, so this one deserves a careful look.
- **What Stardrift does:** it watches for window changes, reads only the **package name** of the window on screen (to avoid opening the bar twice), reads and resets one TV setting flag, and starts the TV's own Quick Settings screen. It does not log key presses, read text or screen contents, or use the network. The whole service is one short file you can read: `QuickKeyService.kt` (about 100 lines).
- **Check it yourself:** open `QuickKeyService.kt` and `app/src/main/res/xml/quick_key_service.xml`. Search the file for `rootInActiveWindow`, `findAccessibilityNodeInfo` and `getText`: they are not there. It does look at each window's top node, but only to read its package name (`w.root?.packageName`).
- **Take it back:** switch it off in Stardrift's Settings, or in the TV's accessibility settings.

### 4. Being your Home screen

Stardrift declares itself as a Home app so Android can offer it as one. That adds no extra permission. Which Home app you use is your choice and you can change it back (see [HOME.md](HOME.md)).

### 5. The "add from your phone" page

- **What it is:** while the **Add from your phone** screen is open on the TV, Stardrift runs a small web page on your home network (ports 8765 to 8775). The TV shows the address and a random 6 digit PIN.
- **What it allows:** whoever opens that page on your network **and** types the PIN can add feed addresses. Nothing else: it cannot read anything from the TV. After 5 wrong PINs it stops accepting guesses until you close and reopen the screen.
- **When it runs:** only while that screen is open. Close it and the page is gone.
- **Note:** the page uses plain `http`, not `https` (a web page on a home network cannot get a normal certificate), so use it on a network you trust.
- **Check it yourself:** read `PhoneAdd.kt`.

## 4. The internet connections

Every address the app can contact is in the code. This is the full list:

| Where to | What for | What is sent | When |
|---|---|---|---|
| `api.open-meteo.com` | Weather | The latitude and longitude of the city **you chose** | When the weather refreshes |
| `geocoding-api.open-meteo.com` | City search | The city name you typed | Only when you search for a city |
| The feeds you use, and the hosts of their pictures | The Picks row | A normal web request for the feed or picture | When the row refreshes |
| `speed.cloudflare.com` | Speed test | Nothing about you; it downloads test data | Only when you press the speed test button |
| `api.github.com` (and the file host GitHub sends you to) | Update button | A request for the latest release of **your own fork** | Only in builds made in a fork (they know the repository name), at most once an hour, plus when you press Update. A build made some other way has no update check |

The app does not look up your location by IP address. As with any website, the service you contact sees your network's address, the same as when you open it in a browser.

Pictures for **Continue watching** come from the address the streaming app provides.

## 5. What works if you grant nothing

Without the one-time ADB grant, these parts do less:

- the **ADB** switch and the **Quick Settings key** switch cannot be turned on;
- **Close background apps** and **Clear cache** use weaker methods and tell you so;
- everything else (the home screen, app dock, weather, feeds, sounds, screensaver, Update button in your fork) works.

## 6. What is stored on the TV

Your settings (name, city, app order, hidden apps, feed list, switches), the short list of recently opened apps, and the ADB key (section 3, item 2) are kept in the app's own private storage. Backups are switched off (`allowBackup="false"`). Nothing is sent to a server of ours: there is none.

## 7. How to check all this yourself

You do not need to be a programmer.

1. **Read the manifest** (`app/src/main/AndroidManifest.xml`) and compare it with the table in section 2.
2. **Ask your AI assistant.** Give it the manifest and the file you want checked, and ask: *"Explain in plain words what each permission allows, what this code actually does with it, and whether it does anything else, such as sending data or reading the screen."* Give it `TvShell.kt`, `AdbClient.java`, `QuickKeyService.kt`, `PhoneAdd.kt`, `Updater.kt`, `Weather.kt`, `SpeedTest.kt` and `WatchNext.kt` one at a time. AI can make mistakes, so for anything that matters read the lines it points to.
3. **Find every internet address in the code:** `grep -rn "https://" app/src/main/java`. Compare it with section 4.
4. **See what is granted on the TV:** `adb -s TV_IP:PORT shell dumpsys package dev.tvlauncher.home` and look at the "requested permissions" and "granted" parts (documented, not run by us). The TV's own **Settings, Apps** page also lists permissions.
5. **Watch the network.** A DNS filter such as Pi-hole or AdGuard Home, or your router's log, shows which names the TV contacts. After a day of use you should only see the ones in section 4 (and the apps you installed are separate). See [TV-TUNING.md](TV-TUNING.md).
6. **Build it yourself** from the source ([BUILD.md](BUILD.md)), so the app on your TV is the code you read.

This page was written from the code at the time; if the code changes, check the code, not only this page. If you find something that does not match, fork the repository and correct it.
