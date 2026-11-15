# NestSync

A small Android app that syncs your git repos with one tap. It does the same thing as the old Termux shortcut:

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

---

## 1. Install the app

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

## When something goes wrong

| Message | What to do |
| --- | --- |
| **The server refused the login** | The token for that server is wrong or expired. Make a new one and update it in Settings. |
| **The token works but isn't allowed** | The token needs **write** access to repositories. |
| **SSH remote not supported** | Tap the repo, then **Switch remote to HTTPS**. |
| **Conflict, nothing was pushed** | The same lines changed on the server and on your phone. Nothing is lost: your commit stays on the phone. Fix it in Termux: `git pull --rebase`, edit the listed files, `git add .`, `git rebase --continue`, then Sync again. |
| **Android blocked access to the folder** | Redo step 2 (All files access). |
| **Can't reach the server** | Check your internet connection. |

## How the APK is built

Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml) on GitHub Actions. It runs the sync tests (real pushes, pulls, deletions and conflicts between two clones), builds the APK, and on `main` publishes it as a new release.

**Signing key:** to install updates over each other, every APK must be signed with the same key. The repo includes a public key (`app/nestsync-public.keystore`) so builds just work. Because it's public, anyone could sign an app with it. To use your own private key instead, add these repository secrets (Settings → Secrets and variables → Actions): `NESTSYNC_KEYSTORE_BASE64`, `NESTSYNC_KEYSTORE_PASSWORD`, `NESTSYNC_KEY_ALIAS`, `NESTSYNC_KEY_PASSWORD`. Switching keys means uninstalling the old app once (you'd re-enter your tokens).

## Building on a computer

Needs JDK 17 and the Android SDK:

```
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
