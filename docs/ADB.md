# Install with ADB

ADB (Android Debug Bridge) is a command line tool that talks to your TV over your home network. You use it once to install the APK, and again for each update.

In the commands below, replace `TV_IP:PORT` with your TV's own IP address and port, written as the IP address, a colon, then the port. `TV_IP` is the address from step 2. `PORT` is usually `5555` for the simple connect, or the port the TV shows for wireless debugging.

## 1. Get ADB on your computer

ADB is part of Google's *platform-tools*.

- **macOS:** `brew install --cask android-platform-tools` (needs [Homebrew](https://brew.sh))
- **Windows and Linux:** download *SDK Platform-Tools* from the [official page](https://developer.android.com/tools/releases/platform-tools), unzip it, and run the commands from inside that folder (on Windows use `adb` in PowerShell or Command Prompt, on Linux `./adb`)

Check that it works:

```
adb version
```

You should see a version number. Use a recent version: the wireless pairing in step 3 (option B) needs a current one.

## 2. Turn on debugging on the TV

1. Open **Settings**, **System**, **About**.
2. Click **Android TV OS build** (on some TVs *Build*) seven times. A message says you are now a developer.
3. Go back to **Settings**, **System**, **Developer options** and switch on **USB debugging**. If you see **Wireless debugging** or **Network debugging**, switch that on too.

Menu names differ a little between TVs and software versions. If you cannot find Developer options, search the web for your TV model plus "enable developer options".

Find the TV's IP address: **Settings**, **Network & Internet**, click your network. Or look at your router's list of connected devices. For stable commands, give the TV a fixed address in your router (DHCP reservation).

The computer and the TV must be on the same network (same router, not a guest network).

## 3. Connect

Pick the option your TV offers.

### Option A: simple connect (port 5555)

```
adb connect TV_IP:PORT
```

The port is usually `5555`. The first time, the TV shows **Allow USB debugging?** with your computer's fingerprint. Choose **Always allow** and confirm with OK. Then run `adb connect` again if the first try said it could not connect.

### Option B: wireless debugging with pairing (Android 11 and newer)

Some TVs and streaming devices use this. The port **changes** each time wireless debugging is restarted, so read it again from the TV when a connection stops working.

1. On the TV: **Developer options**, **Wireless debugging**, switch on, then **Pair device with pairing code**. The TV shows an address with a port, shown as `TV_IP:PAIRING_PORT`, and a 6 digit code.
2. On the computer:
   ```
   adb pair TV_IP:PAIRING_PORT
   ```
   Type the 6 digit code when asked. You should see `Successfully paired`.
3. Go back one screen on the TV. The main Wireless debugging screen shows **another** port (not the pairing port), shown as `TV_IP:PORT`. Connect to that one:
   ```
   adb connect TV_IP:PORT
   ```

Pairing is done once per computer. After that only `adb connect` with the current port is needed.

### Check the connection

```
adb devices
```

You want the line to end in `device`:

```
TV_IP:PORT    device
```

## 4. Install and open the launcher

Run these in the folder where `tvlauncher.apk` is (or give the full path). Replace `TV_IP:PORT` with the address you connected to:

```
adb -s TV_IP:PORT install -r tvlauncher.apk
adb -s TV_IP:PORT shell am start -n dev.tvlauncher.home/.MainActivity
```

`install` prints `Success` when it worked. The `-r` keeps the settings when you update. The second command opens the launcher so you can look at it. It also appears in the TV's app list under the name Stardrift.

To make it the Home screen, see [MANUAL.md](MANUAL.md#making-it-your-home-screen).

`-s ADDRESS` tells ADB which device to use. It is required when more than one device is connected, and harmless when only one is.

## What to look for (messages and fixes)

### `adb devices` shows something other than `device`

| You see | Meaning | Fix |
|---|---|---|
| `unauthorized` | the TV did not accept this computer yet | look at the TV, accept **Allow USB debugging?** (Always allow). If no prompt shows, switch USB debugging off and on in Developer options, then connect again |
| `offline` | stale connection | `adb disconnect`, then `adb connect ...` again; if it stays, restart debugging on the TV |
| no line at all | not connected | run `adb connect` again and read its message |

### `adb connect` problems

| You see | Fix |
|---|---|
| `failed to connect` or `Connection refused` | debugging is off, the port is wrong (option B: read the current port again), or the TV went to sleep: wake it up and try again |
| `No route to host`, `Operation timed out` | wrong IP, different network, or a firewall; check the TV's address |
| `error: more than one device/emulator` | add `-s ADDRESS` to the command |

### `adb install` problems

| You see | Meaning and fix |
|---|---|
| `Success` | done |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | the installed copy was signed with a **different key**. Uninstall it first with `adb -s ADDRESS uninstall dev.tvlauncher.home` (settings are lost), then install again. Build every later version with the same key to avoid this |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | the installed version is newer than the APK. Install a newer build, or uninstall first |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | the TV is full. Free space in **Settings**, **Storage** (remove apps) |
| `no such file` / `cannot open` | the path to `tvlauncher.apk` is wrong; run the command in the folder that holds it |

## Useful commands

```
adb disconnect                      # drop all connections
adb -s ADDRESS uninstall dev.tvlauncher.home     # remove the launcher
adb -s ADDRESS logcat | grep -i tvlauncher       # watch the launcher's log (Ctrl+C to stop)
```

## When you are done

Switch **USB debugging** and **Wireless debugging** off again in Developer options. Debugging gives full control of the TV to any computer that was allowed, so leave it off when you are not installing. Never expose the TV's ADB port to the internet.

## Where to get help

- Official ADB reference: [developer.android.com/tools/adb](https://developer.android.com/tools/adb) (see the part about connecting over Wi-Fi)
- Official download and release notes for platform-tools: [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)
- Community help for Android TV and Google TV devices: [r/AndroidTV](https://www.reddit.com/r/AndroidTV/) and the [XDA forums](https://xdaforums.com/)
- Problem with this launcher itself: open an [issue](https://github.com/SpeakingColors/StarDriftTvLauncher/issues) in this repository. Include your TV model, what you ran, and the exact message you got.

## Optional: switches in Settings

Settings has an **ADB** switch (USB debugging on or off), and on TVs that have their own Quick Settings bar a **Quick Settings key** switch. Both need one permission that Android only allows to be given over ADB. Give it once:

```
adb -s TV_IP:PORT shell pm grant dev.tvlauncher.home android.permission.WRITE_SECURE_SETTINGS
```

Until then the switches show a message with this command. Bright means on, dimmed means off.

