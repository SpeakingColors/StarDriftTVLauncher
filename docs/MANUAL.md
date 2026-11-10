# Stardrift manual

How the launcher works and how to use it. Everything is done with the TV remote: arrows move, **OK** opens, **Back** goes back. "Press and hold" means hold OK on the item.

## Contents

- [Making it your home screen](#making-it-your-home-screen)
- [The home screen](#the-home-screen)
- [Name and weather](#name-and-weather)
- [Apps (the dock)](#apps-the-dock)
- [Picks and New series](#picks-and-new-series)
- [Continue watching](#continue-watching)
- [Top icons](#top-icons)
- [Sound](#sound)
- [Screensaver](#screensaver)
- [Adding feeds from your phone](#adding-feeds-from-your-phone)
- [Privacy and network](#privacy-and-network)
- [Updating and removing](#updating-and-removing)
- [Problems](#problems)

## Making it your home screen

After you install it ([ADB.md](ADB.md)), Stardrift appears in the TV's app list as **Stardrift**. Opening it from there is a good way to try it first.

The app declares itself as a Home app, so Android can use it when you press the Home button. How the TV lets you choose it as the default differs between TVs and software versions: some ask which Home app to use the next time you press Home, others hide the choice in system settings. If your TV does not offer it, search the web for your TV model plus "change default launcher".

To go back to the original Home screen, uninstall Stardrift (see [Updating and removing](#updating-and-removing)).

## The home screen

From top to bottom:

1. **Greeting, clock, date** and the **weather** block, with the **top icons** on the right
2. **Apps**: your dock
3. **Picks / New series**: a row of news cards (three dots show which page you are on)
4. **Continue watching**: cards for what you were watching

The background is a night sky that drifts slowly; it is only decoration and costs no attention.

## Name and weather

- **Clock:** press OK on the clock to type a name for the greeting ("Good morning, Name"). Leave it empty for just "Good morning".
- **Weather:** the first time it says *Choose your city*, *Press OK here*. Type a city name and press Search on the keyboard, then pick it from the list. The city is chosen by name; nothing looks up your location. Press OK on the weather block later to change the city.

The weather comes from [Open-Meteo](https://open-meteo.com/). If it says *Weather unavailable*, check the network (see [Privacy and network](#privacy-and-network)).

## Apps (the dock)

The dock shows the apps you want, in your order. The last tile, **+**, opens **All apps**.

- **OK** opens an app.
- **Press and hold** an app for its menu:
  - **Move**: use Left and Right to move it, then press OK to place it
  - **App info**: Android's own page for that app
  - **Remove from dock**: it goes to All apps; add it back from All apps with **Add to dock**
  - **Hide**: hides it from the dock and All apps; undo in Settings, Hidden apps
  - **Uninstall**: Android asks you to confirm
- **Play Store** has one more entry, **Icon look**. Each press changes the tile between **Colors**, **Black** and **White**, and the menu stays open so you can compare.
- In **All apps**, press and hold an app to add it to or remove it from the dock, open its App info, and so on.

The dock has no app names under the tiles, on purpose.

## Picks and New series

The row under the dock has one card per category: **New series**, **Anime** and **Games**. A category only shows when at least one source in it is switched on. Each card steps through its latest headlines by itself (the dots show how many), with a preview image when the feed has one.

- **OK** on a card opens the full list for that category. **OK** on an item opens a reader popup with the article text from the feed (when a feed only shares a title, the popup says so). Up and Down scroll, OK for the next page, Back to close.
- **Press and hold** a card for **Refresh** and **Edit sources**.
- **Edit sources** lists the feeds. OK switches a feed on or off. A feed you added yourself can be removed with press and hold. You can add a feed by web address, or from your phone (see below).
- **New series** is not a web feed: it shows new releases that the streaming apps you choose share with the TV. Press and hold it, **Choose apps**. The card stays hidden until your apps share something.

Only what you switch on shows up. There are no suggestions and nothing sponsored.

## Continue watching

This row shows what you were in the middle of.

- It reads the Google TV *Continue watching* rows that your streaming apps share. The first time, the TV asks for permission to read them; accept it, otherwise the row can only show recently opened apps.
- If none of your apps share anything, the row is labelled **Recently opened** and shows the apps you opened last.
- **Press and hold** the row: **Refresh** or **Choose apps**. Only the apps you switch on in Choose apps show in the row.

## Top icons

The round icons at the top right open popups on the right side of the screen:

| Icon | What it does |
|---|---|
| Play Store | Opens the Google Play Store |
| Inputs | Lists the inputs your TV shares with apps (HDMI and others) so you can switch. If your TV shares none, it says so: use the INPUT button on the remote |
| Network | Connection status, signal, IP address, gateway, link speed, and a **download speed test** (about 12 seconds). The network name is never shown |
| Speaker | [Sound](#sound): music and effects |
| Settings | Quick actions, shortcuts and launcher settings (below) |

**Settings** contains:

- **All TV settings**: opens Android's own settings
- **Quick actions**: **Close background apps** (best effort, Android decides what it allows) and **Clear cache** (asks Android to clear it; it says how much it cleared)
- **Shortcuts**: Apps, Display, Sound, Storage (straight to those Android pages)
- **Launcher**: **Hidden apps** (to show an app again)

## Sound

The speaker icon opens two switches, each with its own volume:

- **Music:** a soft ambient song on the home screen. It fades in and out, loops without a gap, and stays silent when another app plays audio.
- **Effects:** soft interface sounds for moving, selecting, going back, opening and closing, and a small bump at the edges.

On a switch, **OK** turns it on or off and **Left and Right** change the volume.

## Screensaver

Stardrift includes its own screensaver, **Stardrift**: the same sky, a 24 hour clock, the date and a Wi-Fi icon, all drifting slowly. It plays the ambient song when Music is on.

Choose it in the TV's own settings, under the screensaver (Ambient mode) options. On many Google TVs: **Settings**, **System**, **Ambient mode**. The name and place can differ by TV.

## Adding feeds from your phone

Typing web addresses with a remote is slow, so you can do it from a phone:

1. On the TV open **Edit sources**, then **Add from phone**.
2. The TV shows a web address and a **PIN**. On your phone, on the **same Wi-Fi**, open that address.
3. Type the PIN shown on the TV, then paste up to 20 feed addresses at a time.
4. Each address is checked; the ones that are not working feeds are reported.

The page only runs while that screen is open on the TV. Close the screen and it is gone. After too many wrong PINs, close the screen on the TV and open it again.

## Privacy and network

- No accounts, no ads, no analytics.
- The launcher contacts only the weather service ([Open-Meteo](https://open-meteo.com/)) and the feeds and images you switched on.
- The home network page for adding feeds is protected by a PIN and only open while its screen is shown.

If your network filters DNS (an ad blocker, for example), allow the hosts listed in the [README](../README.md#network-dns-filters). Symptoms of a blocked host: *Weather unavailable* (weather host), *Nothing to show yet. Check the internet connection.* (feeds), or pictures that stay empty.

After a TV restart the network can come up late; the launcher retries feeds, pictures and Continue watching by itself, and reloads when the network comes up.

## Updating and removing

- **Update:** build a new APK and install it with the same command as the first time (`install -r`). See [BUILD.md](BUILD.md) and [ADB.md](ADB.md). Settings stay as long as the APK is signed with the same key.
- **Remove:** `adb -s ADDRESS uninstall dev.tvlauncher.home`, or uninstall **Stardrift** from the TV's Apps settings. The original Home screen comes back.

## Problems

| Problem | Try |
|---|---|
| Weather says *Choose your city* | Press OK on it and search for your city |
| *Weather unavailable* | Check the TV's internet; allow `api.open-meteo.com` in your DNS filter |
| News row empty | Press and hold the row, **Refresh**; check **Edit sources** has feeds on; check the DNS filter |
| Continue watching empty or only recent apps | Accept the permission when asked; apps decide themselves whether they share their rows; use **Choose apps** |
| No sound | Open the speaker icon: Music and Effects must be **On**; the music stays silent while another app plays audio |
| A tile looks wrong | Press and hold it, **Hide** it or **Remove from dock**, and add it back from All apps |
| Update refuses to install | The new APK was signed with a different key: see the `INSTALL_FAILED_UPDATE_INCOMPATIBLE` row in [ADB.md](ADB.md) |

Still stuck? Open an [issue](https://github.com/SpeakingColors/StarDriftTvLauncher/issues) with your TV model and what you see.
