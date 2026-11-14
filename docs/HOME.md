# Make Stardrift your Home screen, and keep the remote working

This page is for the part that is different on every TV: getting the Home button to open Stardrift, and finding out why a remote button does nothing. Everything here was learned on real Google TV devices, by trial and error. Your TV may behave differently, so read the "what to expect" notes and change one thing at a time.

Replace `TV_IP:PORT` with your TV's address (see [ADB.md](ADB.md)). Every command below is an `adb` command run on your computer.

## 1. Try it first, without changing anything

Open **Stardrift** from the TV's app list. If you like it, continue. Nothing below is needed to use it as a normal app.

## 2. Who is Home right now?

```
adb -s TV_IP:PORT shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s TV_IP:PORT shell cmd role get-role-holders android.app.role.HOME
```

- The first command prints the app that answers the **Home button**. The part before the `/` is the package name. On a Google TV it is usually Google's own launcher.
- The second prints the app holding the *Home* role (Android 10 and newer).

## 3. What did not work on its own

On the TV we tested, these ran without an error but Home still opened Google's launcher, because it answers with a higher priority:

```
adb -s TV_IP:PORT shell cmd package set-home-activity dev.tvlauncher.home/.MainActivity
adb -s TV_IP:PORT shell cmd role add-role-holder --user 0 android.app.role.HOME dev.tvlauncher.home
```

After the second one, `get-role-holders` listed Stardrift, yet Home still went to Google's launcher. Disabling the single component with `pm disable` is refused for the shell user. Some TVs do offer a "default Home app" choice in their settings: look there first.

## 4. What worked: turn the stock launcher off (reversible)

You do not uninstall anything; you disable it for your user, and you can switch it back on at any time.

**Before you start**
- Install Stardrift and open it once.
- Make sure you can reach the TV with ADB **after a reboot** (see section 7). If a TV boots without any home screen and ADB is not reachable, you cannot undo the change.
- Write down what is disabled already: `adb -s TV_IP:PORT shell pm list packages -d`

**On a Google TV (the Google TV home screen is the package `com.google.android.apps.tv.launcherx`)**

Disable the setup helper first, then the launcher:

```
adb -s TV_IP:PORT shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb -s TV_IP:PORT shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

Press Home: Stardrift should open. Restart the TV once and check again.

- On our first attempt we disabled only the launcher and the TV got stuck looping on the Google TV logo; we switched the launcher back on and it recovered. Disabling the setup helper first (we believe it is the TV's recovery screen) and the launcher second worked.
- Use `pm disable-user --user 0 PACKAGE`. Plain `pm disable` is refused for apps, and you cannot disable a single component.

**To go back (launcher first, then the helper)**

```
adb -s TV_IP:PORT shell pm enable com.google.android.apps.tv.launcherx
adb -s TV_IP:PORT shell pm enable com.google.android.tungsten.setupwraith
```

Or uninstall Stardrift: Google's home screen comes back if it is still enabled.

**On another kind of TV** the launcher has another package name. Do not guess: run the `resolve-activity` command from section 2 and use the package it prints. Change one package, press Home, and test before disabling anything else.

## 5. After you changed Home: things that may stop working

- **The remote's Input button** may stop working on a TV that sends it to the stock launcher. Stardrift's **Inputs** icon switches inputs instead. If its list is empty, restart the TV once.
- **The Settings (gear) and Quick Settings (wrench) buttons** may stop on some TVs. See section 6.
- **The remote's power button.** On one Chromecast with Google TV, disabling Google's infrared service (`com.google.android.tv.axel`) stopped the power button from turning the TV off. Enabling it again fixed it. Before you disable a package whose name you do not know, search for it first.
- **Keep a list** of everything you disable and enable (package, date, TV). `pm list packages -d` shows what is disabled right now, and `pm enable PACKAGE` undoes one. This list is the first thing you will want when a button stops working a week later.

## 6. A remote button does nothing: how to find out why

A key can be answered by Stardrift, by another app, by the stock launcher, or by the TV maker's own system app. Disabling packages changes who answers. Work through these in order.

**Does Android see the key at all?**

```
adb -s TV_IP:PORT shell getevent -l
```

Press the button; lines with names such as `KEY_...` (or a scan code) appear. Press Ctrl+C to stop. No lines means the remote, not the software, is the problem (batteries, pairing).

**Does an injected key do what the real one should?**

```
adb -s TV_IP:PORT shell input keyevent KEYCODE_HOME
adb -s TV_IP:PORT shell input keyevent KEYCODE_SETTINGS
```

`KEYCODE_HOME` is key 3. You can use any key name or number from the Android `KeyEvent` list, or a code you saw in the log. If the injected key works but the real button does not, the real key is not being delivered to apps.

**Who receives the key?** Press the button while this runs, then press Ctrl+C:

```
adb -s TV_IP:PORT logcat | grep -i -E "key|button"
```

Look for the app that logs the key. After a press, you can also look for a system broadcast:

```
adb -s TV_IP:PORT shell dumpsys activity broadcasts | grep -i -B2 -A8 GLOBAL_BUTTON
```

On the Google TV we tested, the Quick Settings key was sent **only** to the TV maker's own app as an explicit `android.intent.action.GLOBAL_BUTTON` broadcast, so no launcher or app ever sees it, and that app stopped answering after other apps were disabled. Stardrift has an optional **Quick Settings key** switch for this case (README, "Notes"). It needs the one-time permission in [ADB.md](ADB.md) and works only on TVs that have their own Quick Settings bar.

**Is a package the cause?** Compare `pm list packages -d` with what you disabled, `pm enable` the likely one, press the button, and note the result in your list. Often a button is answered by the stock launcher you just disabled; an accessibility service (as in Stardrift's switch) can answer in its place.

**To start the TV's own Settings or any other screen by hand,** to see whether it works at all:

```
adb -s TV_IP:PORT shell am start -a android.settings.SETTINGS
adb -s TV_IP:PORT shell am start -n PACKAGE/.ActivityName
```

## 7. Staying connected with ADB

- Some TVs (for example the Chromecast with Google TV) only offer **Wireless debugging**, with a random port that changes and needs pairing with a code (see [ADB.md](ADB.md), option B). Once connected, you can ask the TV for a fixed port:

  ```
  adb -s TV_IP:PORT tcpip 5555
  adb connect TV_IP:5555
  ```

  The fixed port may be forgotten when the TV restarts; run the command again if it is. Stardrift's own Close background apps and Clear cache buttons also use port 5555 on the TV itself.
- Keep **USB debugging** off when you are not using it. Stardrift's **ADB** switch in Settings turns it on and off without going into Developer options.
- We saw a TV power off once when an install replaced the app while the screensaver was running. Install while it is awake.

## 8. Quick checklist

1. Install, open once, try it.
2. `resolve-activity` and `get-role-holders`: who is Home?
3. Make sure ADB works after a reboot.
4. Disable the stock launcher the safe way (section 4), test Home, reboot, test again.
5. Test the Input button, Settings button, Quick Settings button and the power button.
6. Fix one thing at a time and keep your list of what you changed.
