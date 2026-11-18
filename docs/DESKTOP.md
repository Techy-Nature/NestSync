# NestSync on Linux and Mac: how it's built

This guide is for working on the desktop version. To just **install** it, see [NestSync on Linux and Mac](../README.md#nestsync-on-linux-and-mac) in the README.

## How it fits together

| Folder | What it is |
| --- | --- |
| `core/` | `GitEngine.kt`: every git step (commit, pull with rebase, push, clone, test login). Plain Kotlin + JGit, no Android or desktop code. Its tests live here too. |
| `app/` | The Android app. Uses `core`. |
| `desktop/` | The Linux and Mac app. Uses `core`. |

The desktop app is a close copy of the Android screens, written with **Compose for Desktop** (JetBrains' version of the same Compose UI kit the Android app uses), so the code reads almost the same:

| Android (`app/`) | Desktop (`desktop/`) |
| --- | --- |
| `MainActivity.kt` (screens) | `Main.kt` (window + the same screens) |
| `MainViewModel.kt` | `AppState.kt` |
| `Storage.kt` (SharedPreferences, Android Keystore) | `DesktopStorage.kt` (JSON files in the config folder) |

A few small things differ on purpose:

- **Words:** `core` has a `Wording` object. The desktop app switches it to "computer", "Click" and "a terminal" at startup, so messages don't mention phones or Termux.
- **Folder picker:** the Mac's own folder picker on macOS, a standard one on Linux.
- **Logins:** saved in `hosts.json` in the config folder (`~/.config/NestSync` on Linux, `~/Library/Application Support/NestSync` on a Mac), readable only by your account.
- **Commit signing:** `core/CommitSigning.kt` lets NestSync read your `~/.gitconfig` (name, email and the rest) while ignoring the signing settings. Without it, a `~/.gitconfig` with `gpg.format = ssh` would stop every sync, because JGit can't read that setting. Your file is never changed.
- **No storage permission** and no `core.fileMode` change: computers keep file permissions properly.

## Running it while you work on it

```
./gradlew :desktop:run
```

That opens the app straight from the code, no packaging needed.

There's also a quick check that runs without a window. The build workflow runs it on every finished AppImage and Mac app:

```
./gradlew :desktop:run --args="--self-test"
```

It makes a real sync between two temporary folders, then checks that HTTPS works by asking GitHub for this repo's branches. It prints what happened and exits with 0 when both work.

## Making the files yourself

**Linux AppImage** (on an x86_64 Linux machine):

1. Build the app folder:
   ```
   ./gradlew :desktop:createDistributable
   ```
2. Pack it into one file:
   ```
   desktop/packaging/linux/build-appimage.sh
   ```
3. `NestSync-x86_64.AppImage` appears in the repo's top folder.

The script downloads `appimagetool` each time, puts `AppRun`, `nestsync.desktop` and the icon next to the app, and packs it. The files it uses are in `desktop/packaging/linux/`.

**Mac DMG** (on a Mac):

1. Build the app:
   ```
   ./gradlew :desktop:createDistributable
   ```
2. Sign it ad-hoc and pack the DMG:
   ```
   desktop/packaging/macos/make-dmg.sh NestSync-mac.dmg
   ```
3. `NestSync-mac.dmg` appears where you ran it.

The script signs `NestSync.app` ad-hoc (`codesign --force --deep --sign -`), checks the signature, and packs a DMG with three things in it: the app, the **Open NestSync** helper (`desktop/packaging/macos/Open NestSync.command`) and a shortcut to Applications.

A DMG built on an Apple Silicon Mac only runs on Apple Silicon Macs, and one built on an Intel Mac only on Intel Macs. That's why the workflow builds both.

`./gradlew :desktop:packageDmg` still works for a quick local DMG, but it doesn't include the helper.

**Version numbers:** on GitHub Actions every file gets `1.0.<run number>`, the same as the APK. Local builds are `1.0.1`.

## Opening the Mac app without a paid Apple account

### What the build does

- **Ad-hoc signature.** `make-dmg.sh` signs the whole app, Java runtime included, with `codesign --sign -`. That's free and needs no Apple account. Apple Silicon Macs refuse to run code with no signature at all, and a whole-app ad-hoc signature avoids the "is damaged" message that a half-signed app can trigger. It does **not** say who made the app, so Gatekeeper still blocks the first open of a downloaded copy.
- **The Open NestSync helper.** A small script in the DMG. When double-clicked, it finds `NestSync.app` in `/Applications` (or `~/Applications`), removes the "downloaded from the internet" mark (`xattr -cr`), re-signs the app ad-hoc on that Mac if the signature doesn't check out, and opens it. It refuses to run on the copy inside the DMG, because a DMG is read-only, and tells the person to drag the app to Applications first.

### What it can't do

Everything inside a downloaded DMG carries the same "downloaded from the internet" mark, **the helper included**. So the helper doesn't remove the warning entirely: it moves it from the app to the helper, once.

- On **macOS 15 (Sequoia) and newer**, Apple removed the right-click → Open shortcut. The first double-click on the helper is blocked, and the person has to click **Open Anyway** in **System Settings → Privacy & Security** once. That's the same number of steps as doing it for the app directly. The helper's real gain there is that it also fixes the "is damaged" case.
- On **macOS 14 (Sonoma) and older**, right-click → **Open** on the helper works without visiting System Settings.
- The **one Terminal command** in the README (`xattr -cr /Applications/NestSync.app && open /Applications/NestSync.app`) skips Gatekeeper's prompt completely, because text pasted into Terminal isn't a downloaded file. For people comfortable with Terminal it's the fastest way.
- Every new download needs this again once. Updating by replacing the app in Applications brings the mark back.

Only a Developer ID signature plus notarization makes a downloaded Mac app open with a plain double-click. That's the next section.

## Mac signing and notarization (for later)

Right now the Mac app is only signed **ad-hoc** (see above). That's why people need the helper, the Terminal command or **Open Anyway** the first time. Nothing is wrong with the app; macOS just can't tell who made it.

To get rid of that step, the app has to be **signed** with an Apple Developer ID and **notarized** (scanned by Apple). That needs a paid **Apple Developer Program** membership (99 US dollars a year).

Once there's a membership, the steps are:

1. **Make a certificate.** In Xcode or on [developer.apple.com](https://developer.apple.com/account/resources/certificates/list), create a **Developer ID Application** certificate. Export it from Keychain Access as a `.p12` file with a password.
2. **Make an app-specific password** at [account.apple.com](https://account.apple.com) → **Sign-In and Security** → **App-Specific Passwords**. Notarization uses it.
3. **Find your Team ID** on the developer website under **Membership details**.
4. **Add repository secrets** (GitHub → Settings → Secrets and variables → Actions):
   - `MAC_CERT_P12_BASE64`: the `.p12` file, base64-encoded (`base64 -i cert.p12 | pbcopy`)
   - `MAC_CERT_PASSWORD`: the `.p12` password
   - `MAC_SIGNING_IDENTITY`: the certificate's name, like `Developer ID Application: Your Name (TEAMID1234)`
   - `APPLE_ID`: the Apple ID email
   - `APPLE_APP_PASSWORD`: the app-specific password
   - `APPLE_TEAM_ID`: the Team ID
5. **Turn signing on in the build.** In `desktop/build.gradle.kts`, inside `macOS { }`, add:
   ```kotlin
   signing {
       sign.set(System.getenv("MAC_SIGNING_IDENTITY") != null)
       identity.set(System.getenv("MAC_SIGNING_IDENTITY"))
   }
   notarization {
       appleID.set(System.getenv("APPLE_ID"))
       password.set(System.getenv("APPLE_APP_PASSWORD"))
       teamID.set(System.getenv("APPLE_TEAM_ID"))
   }
   ```
6. **Import the certificate in the workflow.** In the `macos` job, before "Build the app", add a step that imports the `.p12` into a temporary keychain (the [`apple-actions/import-codesign-certs`](https://github.com/Apple-Actions/import-codesign-certs) action does this) and pass the secrets as environment variables to the build step.
7. **Switch the DMG step back to Gradle.** Replace the "Sign ad-hoc and make the DMG" step with `./gradlew --no-daemon :desktop:packageDmg :desktop:notarizeDmg`, and copy the DMG from `desktop/build/compose/binaries/main/dmg/` to `NestSync-mac-<label>.dmg` as before. A Developer ID signature replaces the ad-hoc one, and the **Open NestSync** helper is no longer needed.

After that, the Mac app opens with a double-click like any other app. The Linux AppImage doesn't need signing.
