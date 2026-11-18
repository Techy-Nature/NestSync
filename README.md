# NestSync

A small app that syncs your git repos with one tap (or click). It runs on **Android**, **Linux** (AppImage) and **Mac**. It does the same thing as the old Termux shortcut:

```
git add -A
git commit -m "Sync from phone ..."
git pull --rebase
git push
```

It works with any git server that speaks HTTPS: Git.gay, GitHub, Codeberg, any Forgejo, Gitea or GitLab.

- A list of your repo folders, each with a big **Sync** button (plus **Sync all**)
- One saved login (username + access token) per server, encrypted with the Android Keystore
- Clone a repo straight onto your phone
- Clear messages: what was committed, pulled and pushed, or exactly what went wrong
- Dark theme, always

On a computer? Jump to **[NestSync on Linux and Mac](#nestsync-on-linux-and-mac)**.

---

## 1. Install the app (Android)

1. On your phone, open the **[latest release](https://github.com/Techy-Nature/NestSync/releases/latest)**.
2. Under **Assets**, tap **NestSync.apk** to download it.
3. Open the downloaded file. If Android asks, allow your browser or Files app to **install unknown apps**, then go back and tap **Install**.
4. Updates work the same way: download the newest APK and install it over the old one. Your repos and logins are kept.

> If Play Protect warns you, that's because the app isn't from the Play Store. Tap **More details → Install anyway**.

## 2. Let it see your files

The first time you open NestSync, there's a yellow **Storage access needed** card.

1. Tap **Allow access**.
2. Switch **Allow access to manage all files** on for NestSync.
3. Press back to return to the app. The card disappears.

## 3. Move your story repos to shared storage (one time)

Apps can't look inside Termux's private home folder, so `~/Stories` has to live in your phone's shared storage instead. Termux can still use it there. In **Termux**, run these one at a time:

1. Give Termux access to shared storage (tap **Allow** when asked):
   ```
   termux-setup-storage
   ```
2. Make a Stories folder in shared storage:
   ```
   mkdir -p ~/storage/shared/Stories
   ```
3. Move your repos into it (this keeps all git history):
   ```
   mv ~/Stories/* ~/storage/shared/Stories/
   ```
4. Put a shortcut where the old folder was, so your Termux commands and tweego paths still work:
   ```
   rmdir ~/Stories && ln -s ~/storage/shared/Stories ~/Stories
   ```
5. Tell Termux's git that shared-storage folders are fine (otherwise it complains about "dubious ownership"):
   ```
   git config --global --add safe.directory '*'
   ```

Your stories are now at **Internal storage → Stories** (the path is `/storage/emulated/0/Stories`).

> Don't have the repo on your phone at all? Skip this step and use **Add repo → Clone it instead** in the app.

## 4. Make an access token

NestSync logs in with a token instead of your password.

**Git.gay (or any Forgejo / Gitea server)**

1. Log in on the website, tap your avatar → **Settings** → **Applications**.
2. Under **Generate new token**, give it a name like `NestSync`.
3. Under permissions, set **repository** to **Read and write**.
4. Tap **Generate token** and copy it right away (it's only shown once).

**GitHub**

1. Go to **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Under **Repository access**, choose **All repositories** (or pick your story repos).
3. Under **Repository permissions**, set **Contents** to **Read and write**.
4. Generate it and copy it.

## 5. Add the token to NestSync

1. Tap the ⚙ **Settings** icon (top right).
2. Tap **Add a host**, then tap the **git.gay** or **github.com** quick-fill button.
3. Enter your **username** for that site and paste the **token**.
4. Tap **Save**. Repeat for each server you use.
5. Optional: under **Commit name**, enter the name and email your commits should use.

## 6. Add your repos and sync

1. Tap **Add repo**.
2. Tap **Browse for folder…** and pick a story folder (the one with `.git` inside), or type its path, like `/storage/emulated/0/Stories/Journey-Through-the-Astralscapes`.
3. Tap **Add**.
4. Tap **Sync**. When it's done, the card says ✓ **Synced** (or ✓ **Up to date**) in green.

Tap any repo card to see the full result of the last sync, test the login, change the remote URL, or remove it from the list.

---

## NestSync on Linux and Mac

The computer version looks and works like the phone app: the same repo cards, the same **Sync** button, the same Settings. It does the same `git add` / `commit` / `pull --rebase` / `push`, with the same code.

### Install on Linux (AppImage)

An AppImage is one file that runs on most Linux systems (Fedora, Nobara, Ubuntu, Arch, Mint…) without installing anything.

1. Open the **[latest release](https://github.com/Techy-Nature/NestSync/releases/latest)**.
2. Under **Assets**, download **NestSync-x86_64.AppImage**.
3. Make it executable. Either right-click it → **Properties** → **Permissions** → tick **Allow executing file as program**, or in a terminal:
   ```
   chmod +x ~/Downloads/NestSync-x86_64.AppImage
   ```
4. Double-click it (or run `~/Downloads/NestSync-x86_64.AppImage`).
5. Optional: move it somewhere permanent, like `~/Applications/`. Tools such as **Gear Lever** or **AppImageLauncher** can add it to your app menu.

To update, download the newest AppImage and replace the old file. Your repos and logins are kept.

> If it won't start and mentions **FUSE**, run it once with `--appimage-extract-and-run` after the file name, or install your system's `fuse` package.

### Install on a Mac

1. Open the **[latest release](https://github.com/Techy-Nature/NestSync/releases/latest)**.
2. Under **Assets**, download the right file:
   - **NestSync-mac-AppleSilicon.dmg** for Macs with an M1, M2, M3 or newer chip.
   - **NestSync-mac-Intel.dmg** for older Intel Macs.
   - Not sure? Open the Apple menu → **About This Mac**. "Chip: Apple M…" means Apple Silicon; "Processor: Intel" means Intel.
3. Open the DMG. Inside are **NestSync**, **Open NestSync** and a shortcut to **Applications**.
4. Drag **NestSync** onto the **Applications** shortcut.

NestSync isn't signed with a paid Apple Developer ID (99 US dollars a year), so the first time, macOS won't open it with a plain double-click. It is signed "ad-hoc" (a free signature that Apple Silicon Macs need), and there are two free ways past the warning. You only do this once. After that NestSync opens from Applications like any other app.

#### Way 1: the Open NestSync helper

5. In the DMG window, double-click **Open NestSync**.
6. macOS asks about the helper itself the first time, because it was downloaded too:
   - **macOS 15 (Sequoia) or newer:** click **Done**, open **System Settings** → **Privacy & Security**, scroll down to the message about **Open NestSync**, click **Open Anyway**, enter your Mac password, then click **Open Anyway** once more.
   - **macOS 14 (Sonoma) or older:** right-click (or Control-click) **Open NestSync**, choose **Open**, then click **Open** in the message.
7. A Terminal window opens, gets NestSync ready and opens it. You can close the Terminal window.

If it says NestSync is "still inside the disk image", you skipped step 4: drag NestSync into Applications, then double-click **Open NestSync** again.

#### Way 2: one Terminal command

5. Open **Terminal** (press ⌘ Space, type **Terminal**, press Return).
6. Paste this line and press Return:
   ```
   xattr -cr /Applications/NestSync.app && open /Applications/NestSync.app
   ```
7. NestSync opens. You can close Terminal.

That's the same thing the helper does. If macOS still says NestSync **"is damaged and can't be opened"**, paste this one instead, which also re-signs the app on your Mac:
```
xattr -cr /Applications/NestSync.app && codesign --force --deep --sign - /Applications/NestSync.app && open /Applications/NestSync.app
```

> You can also skip both and use **System Settings** → **Privacy & Security** → **Open Anyway** on NestSync itself, the same way as step 6 of Way 1.

To update, download the newest DMG and drag NestSync into Applications again, choosing **Replace**. A new copy needs Way 1 or Way 2 once more.

### Set it up on a computer

The steps are the same as on the phone, with a couple of differences:

1. **Tokens:** make one as in [step 4](#4-make-an-access-token), then click ⚙ **Settings** → **Add a host**. The same token can be used on your phone and your computer.
2. **Repos:** click **Add repo**, then **Browse for folder…** and pick the folder that has `.git` inside. You can also type a path such as `~/Stories/my-story`. No storage permission or Termux steps are needed.
3. **Clone:** **Add repo → Clone it instead** saves new clones in `~/Stories` unless you pick another folder.

**Good to know on a computer**

- Logins are saved in `~/.config/NestSync/hosts.json` on Linux and `~/Library/Application Support/NestSync/hosts.json` on a Mac. The file can only be read by your own account (the same way git's own credential store and the GitHub CLI keep tokens). The phone app encrypts them with the Android Keystore instead; computers don't have that.
- NestSync reads your normal git settings (`~/.gitconfig`), so commits use your usual name and email. It never signs commits, so if you sign commits on the command line, NestSync's commits will be unsigned. Your `~/.gitconfig` is never changed.
- Commit messages say "Sync from computer …" instead of "Sync from phone …".
- If a conflict happens, fix it in a terminal the same way as in the table below, then click **Sync** again.

---

## When something goes wrong

| Message | What to do |
| --- | --- |
| **The server refused the login** | The token for that server is wrong or expired. Make a new one and update it in Settings. |
| **The token works but isn't allowed** | The token needs **write** access to repositories. |
| **SSH remote not supported** | Tap the repo, then **Switch remote to HTTPS**. |
| **Conflict, nothing was pushed** | The same lines changed on the server and on your phone. Nothing is lost: your commit stays on the phone. Fix it in Termux: `git pull --rebase`, edit the listed files, `git add .`, `git rebase --continue`, then Sync again. |
| **Android blocked access to the folder** | Redo step 2 (All files access). |
| **Your computer blocked access to the folder** | Make sure your account can read and write that folder. On a Mac, check **System Settings → Privacy & Security → Files and Folders** for NestSync. |
| **This repo's own git settings ask for signed commits** | In a terminal, inside the repo, run `git config commit.gpgsign false` and `git config --unset gpg.format`. |
| **Can't reach the server** | Check your internet connection. |
| **Mac: "NestSync can't be opened" or "is damaged"** | Use **Way 1** or **Way 2** under [Install on a Mac](#install-on-a-mac). A newly downloaded copy needs it once more. |

## How the apps are built

The code is in three parts:

| Folder | What it is |
| --- | --- |
| `core/` | The git engine (all the sync work), in plain Kotlin with JGit. Both apps use it. |
| `app/` | The Android app. |
| `desktop/` | The Linux and Mac app, made with Compose for Desktop (the computer version of the UI kit the Android app uses). |

Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml) on GitHub Actions:

1. **Android:** runs the sync tests (real pushes, pulls, deletions and conflicts between two clones), then builds `NestSync.apk`.
2. **Linux:** builds the app on Ubuntu, packs it into `NestSync-x86_64.AppImage`, then runs it: a quick self-test (a real sync and an HTTPS check) and a 15-second launch on a virtual screen.
3. **Mac:** builds `NestSync-mac-AppleSilicon.dmg` and `NestSync-mac-Intel.dmg` on GitHub's Mac machines, with the same self-test and launch check.
4. **Release:** on `main`, once all of them pass, publishes all four files together as one new release.

Each desktop build includes its own trimmed-down Java, so nobody needs Java installed. Mac signing and notarization are explained in [docs/DESKTOP.md](docs/DESKTOP.md).

**Signing key:** to install updates over each other, every APK must be signed with the same key. The repo includes a public key (`app/nestsync-public.keystore`) so builds just work. Because it's public, anyone could sign an app with it. To use your own private key instead, add these repository secrets (Settings → Secrets and variables → Actions): `NESTSYNC_KEYSTORE_BASE64`, `NESTSYNC_KEYSTORE_PASSWORD`, `NESTSYNC_KEY_ALIAS`, `NESTSYNC_KEY_PASSWORD`. Switching keys means uninstalling the old app once (you'd re-enter your tokens).

## Building on a computer

Everything needs JDK 17 or newer.

**Sync tests:**

```
./gradlew :core:test
```

**Android APK** (also needs the Android SDK):

```
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

**Desktop app**, to try it straight away:

```
./gradlew :desktop:run
```

**Linux AppImage** (on x86_64 Linux):

```
./gradlew :desktop:createDistributable
desktop/packaging/linux/build-appimage.sh
```

**Mac DMG** (on a Mac):

```
./gradlew :desktop:createDistributable
desktop/packaging/macos/make-dmg.sh NestSync-mac.dmg
```

That signs the app ad-hoc and makes `NestSync-mac.dmg` with the app, the **Open NestSync** helper and an Applications shortcut. See [docs/DESKTOP.md](docs/DESKTOP.md) for more.
