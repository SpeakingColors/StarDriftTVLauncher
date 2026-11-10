# Build your own APK

You need a GitHub account and a computer with Java (for one command). You do not need Android Studio.

**Why your own key?** Android only installs an update over an app when both are signed with the same key. If you build with your own key from the start, every later build updates cleanly. Keep that key safe (step 2).

## 1. Fork the repository

On the repository page, press **Fork** (top right). Everything below happens in your fork.

Forks start with Actions switched off: open the **Actions** tab of your fork and press the green button that enables workflows.

## 2. Create your keystore

The keystore is one file that holds your signing key. Make it once.

You need `keytool`, which comes with any JDK (version 17 is fine). Check with `keytool -help`.

```
keytool -genkeypair -v -keystore tvlauncher.keystore -alias tvlauncher \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass CHOOSE_A_PASSWORD -keypass CHOOSE_A_PASSWORD \
  -dname "CN=Stardrift"
```

Rules, because the build expects them:

- the alias must be exactly `tvlauncher`
- the keystore password and the key password must be the **same** password
- use a long password and write it down somewhere safe

**Back up `tvlauncher.keystore` and the password** (password manager, a backup drive). If you lose them you cannot update the launcher on the TV, you would have to uninstall it and install a build with a new key (settings are lost). **Never commit the keystore to the repository.** The `.gitignore` already ignores `signing/` and you should keep the file outside the repo folder anyway.

## 3. Add the two secrets to your fork

On your fork: **Settings**, **Secrets and variables**, **Actions**, **New repository secret**.

| Name | Value |
|---|---|
| `KEYSTORE_B64` | the keystore file, base64 encoded (command below) |
| `KEYSTORE_PASSWORD` | the password you chose |

Get the base64 text of the keystore:

- Linux: `base64 -w0 tvlauncher.keystore`
- macOS: `base64 -i tvlauncher.keystore`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("tvlauncher.keystore"))`

Copy the whole output into the secret. Secrets cannot be read back later, only replaced.

If you use the GitHub command line (`gh`), this does the same:

```
base64 -i tvlauncher.keystore | gh secret set KEYSTORE_B64 --repo YOUR_NAME/YOUR_FORK
gh secret set KEYSTORE_PASSWORD --repo YOUR_NAME/YOUR_FORK
```

(`gh` asks you to type the password for the second command. On Linux use `base64 -w0` in the first one.)

## 4. Build

On your fork: **Actions**, **Build APK**, **Run workflow**. It takes a few minutes. The build also runs by itself on every push to `main`.

When it is green:

- the APK is on the **Releases** page of your fork, in the `latest` release (it is marked pre-release, that is normal), file `tvlauncher.apk`
- or on the run page, under **Artifacts**, `tvlauncher-apk` (a zip that contains the APK)

If it is red, open the run, open the failed step and read the last lines. The usual causes:

| Message | Fix |
|---|---|
| `The KEYSTORE_B64 and KEYSTORE_PASSWORD secrets are missing` | Step 3 was not done, or a secret name has a typo |
| `Keystore was tampered with, or password was incorrect` | `KEYSTORE_PASSWORD` is not the password of the keystore |
| `Cannot recover key` | the key password differs from the keystore password; recreate with the same password for both |
| `Failed to read key tvlauncher` | the alias is not `tvlauncher` |
| `base64: invalid input` | the secret was copied with extra characters; copy it again |

## 5. Install on the TV

Follow [ADB.md](ADB.md). Short version (replace `TV_IP:PORT` with your TV's IP address and port):

```
adb connect TV_IP:PORT
adb -s TV_IP:PORT install -r tvlauncher.apk
adb -s TV_IP:PORT shell am start -n dev.tvlauncher.home/.MainActivity
```

## Updating later

Pull or sync the upstream changes into your fork, the build runs, download the new `tvlauncher.apk` and run the same `install -r` command. Your settings stay because the key is the same.

## Optional: refuse a wrong key

To make the build fail if it is ever signed with a different key, add a **repository variable** (not a secret) named `EXPECTED_CERT_SHA256` with the SHA-256 fingerprint of your key. You find the fingerprint in the build log of the step *Show the signing certificate* (line `Signer #1 certificate SHA-256 digest`), written in lowercase hex without colons.
