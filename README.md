# tv-launcher

🇪🇸 [Leer en español](README.es.md)

A custom launcher and an on-TV torrent downloader for a **Sony BRAVIA (Android TV 9)**, plus a web manager you open from your computer or phone.

Two Android apps in one Gradle project:

| Module | Package | What it is |
|---|---|---|
| `app/` | `app.tvlauncher` | Home screen: apps, HDMI inputs as buttons, popular titles per streaming platform with backdrops, synopsis, cast and rating (TMDB). Also remaps remote-control buttons. |
| `torrent/` | `app.tvtorrent` | Downloads and plays content **on the TV itself** (libtorrent4j engine, USB storage) and serves a web manager. |

> The UI strings of both apps are in Spanish. Everything else (code, docs) is bilingual.
> Use the downloader only for content you have the right to download and watch.

## Launcher (`app/`)

- Home with apps, HDMI inputs, "continue watching" (opens the Google launcher) and Settings.
- Focusing an app shows what is popular on that platform (TMDB discover by watch provider; region and language are set in `local.properties`), with backdrop, synopsis, cast, rating and runtime/seasons.
- Stremio titles deep-link to the exact page (`stremio:///detail/<series|movie>/<imdb>`). Other platforms expose no public id, so only the app opens.
- Remote-control buttons are remappable through an accessibility service (it only sees key events; it does not read screen content and logs nothing): open an app, switch input, open Settings, go home, or "do nothing".
- Known limit: a third-party launcher cannot read other apps' "continue watching" (system-only permission), and the HOME key itself cannot be overridden.

### Build and install

```bash
# local.properties (not committed):
#   sdk.dir=/path/to/android-sdk
#   tmdb.key=YOUR_TMDB_V3_API_KEY
#   tmdb.region=CO        (optional)
#   tmdb.lang=es-CO       (optional)
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :torrent:assembleDebug

adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <TV_IP>:5555 install -r --install-location 1 torrent/build/outputs/apk/debug/torrent-debug.apk
```

Both APKs must be signed with the same key (the apps talk through a signature-protected ContentProvider).

- Reinstalling `app.tvlauncher` makes Android disable its accessibility service. Re-enable it with
  `adb shell settings put secure enabled_accessibility_services app.tvlauncher/app.tvlauncher.KeyMapService`
  (the app also self-heals if `WRITE_SECURE_SETTINGS` was granted with `adb shell pm grant`).
- The Google launcher keeps HOME. Reach this launcher with the remote's Google Play button (mapped to "go home"), from the Google home (Apps → "Inicio"), or `adb shell am start -n app.tvlauncher/.MainActivity`.

## Downloads on the TV (`torrent/`)

- libtorrent4j engine + NanoHTTPD web manager at `http://<TV_IP>:8800` (user `admin`; the password is shown on the TV's *Descargas* screen and you can set your own fixed one).
- Add content from the web manager or by opening a `magnet:` link on the TV
  (`adb shell am start -a android.intent.action.VIEW -d "magnet:..." app.tvtorrent`).
- **Per download** (TV: select a row; web: card buttons): pause/resume, ask for more peers (re-announce to trackers and DHT), sequential order, recheck, move up the queue, skip/include files, remove (keeps files), cancel and delete. *Details* shows speeds, seeds/peers, files, trackers and best connections.
- **Download mode** (button on the TV and in the web manager): the service takes a partial wake lock and a Wi-Fi lock and the launcher's accessibility service turns the screen off (`GLOBAL_ACTION_LOCK_SCREEN`). Verified: while the TV is `Asleep`, data keeps arriving and the manager responds. When everything finishes the locks are released and the TV is left to power down; if someone is watching, it is not switched off and a notification is posted. Safety cap: 12 h.
- **Resume data** is saved in the app's `files/resume/` (metadata + verified pieces), so a reboot neither re-fetches the magnet nor re-hashes whole files.
- **Playback**: Kodi (default) or VLC. On this TV VLC shows green stripes; Kodi is clean. The app opens the file straight from the USB so playback does not depend on the web manager being alive.
- **Web manager tabs**: Downloads, Buttons (remap the remote from the browser, with key learning) and Settings (fixed password, player, "always on", storage status).
- The TV UI lives in `Ui.kt` (shared style), `DownloadsActivity.kt` and `TorrentDetailActivity.kt`.

### Security model

Password (Basic auth), `X-Portal` header on every action (anti-CSRF), Host-header check, magnet links only, media files served under a separate key, config ContentProvider guarded by a signature permission. Do not expose port 8800 to the internet.

### Storage: use a *portable* USB, not an adopted one

- An app installed on an *adopted* USB lost access to its data directory (EACCES) after unmounts/reboots on this Sony: the password changed on every boot and the download list was lost.
- Fix: format the USB as portable FAT32 (`sm partition disk:8,0 public`, `sm format public:8,1`, `sm mount public:8,1`) and install the app in internal storage (`pm install --install-location 1`). Downloads go to `/storage/<uuid>/Android/data/app.tvtorrent/files/downloads`, chosen once and remembered.
- If the folder disappears (USB unplugged) the engine stops and resumes on its own when it returns; it never writes anywhere else.
- FAT32 limit: no single file over 4 GB. FAT32 also has no sparse files, so large torrents are zero-filled when allocated and saturate the USB for a while.

### Static IP

Set it in the TV: Settings → Network → IP settings → Static. Pick an address outside your router's DHCP pool.

## Reverting the system tweaks

`revertir_tv.sh` re-enables the Sony/Google packages that were disabled (never deleted) and restores animation scales:

```bash
bash revertir_tv.sh <TV_IP>:5555
```

## Lessons learned

- All engine and disk work must run off the main thread: a slow USB on the main thread gets the app killed (ANR).
- Idle shutdown of the web service must account for open streams, or long playbacks through it die after the idle timeout.
- Rows in the TV list are updated in place (not rebuilt) so D-pad focus is not lost.
