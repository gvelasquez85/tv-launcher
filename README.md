# tv-launcher

🇪🇸 [Leer en español](README.es.md)

A custom launcher and an on-TV torrent downloader for **Android TV**, installed from your computer in a few minutes.
Developed and tested on a Sony BRAVIA (Android 9); the installer is generic, but see [Compatibility](#compatibility).

- **Launcher** (`app.tvlauncher`): apps and HDMI inputs as buttons, popular titles per streaming service with backdrop, synopsis, cast and rating (TMDB), remappable remote-control buttons.
- **Downloads** (`app.tvtorrent`): downloads with a built-in torrent engine **on the TV itself**, saves to a USB drive, plays with Kodi/VLC/your player, and serves a **web manager** you open from any computer or phone on your network (downloads, remote buttons, settings).

> The UI strings of both apps are in Spanish for now. Use the downloader only for content you have the right to download and watch.

## Quick start

**1. Enable network debugging on the TV.**
Settings → Device Preferences → About → tap *Build* seven times → back → Developer options → turn on *USB debugging* / *Network debugging* (wording varies by brand). Note the TV's IP address (Settings → Network).

**2. Run the installer on your computer** (Windows, macOS or Linux; needs only Python 3.8+):

```bash
git clone https://github.com/gvelasquez85/tv-launcher.git
cd tv-launcher
python3 install.py            # on Windows: py install.py
```

The wizard will:
1. find ADB (or download Google's official platform-tools into `./.tools`) and connect to your TV (it can scan your network);
2. get the APKs (from `./dist`, the latest GitHub Release, or build them from source);
3. ask for a free [TMDB API key](https://www.themoviedb.org/settings/api), your country and language;
4. install both apps, enable the remote-button service and open them on the TV;
5. optionally disable known telemetry/promo apps (reversible).

Useful options: `--tv 192.168.1.50`, `--tmdb-key KEY --region US --lang en-US -y` (non-interactive), `--build` (compile from source), `--dry-run`.
Other commands: `status`, `debloat`, `revert`, `uninstall`.

**3. Open the manager.** The *Descargas* app on the TV shows the address (`http://<TV_IP>:8800`), user `admin` and a password; set your own fixed one under *Ajustes*.

## Compatibility

| | |
|---|---|
| Android | 9 (API 28) or newer |
| CPU | ARM 32/64-bit (the torrent engine ships ARM libraries only) |
| Tested | Sony BRAVIA 4K GB (Android 9). Other brands should work but are untested: please open an issue. |
| Computer | Windows, macOS, Linux. Same Wi-Fi/LAN as the TV |

**Google TV / Android 11+:** wireless debugging needs a one-time pairing. On the TV: Developer options → Wireless debugging → *Pair device with pairing code*, then run `python3 install.py --pair IP:PORT --pair-code 123456 --tv IP:CONNECT_PORT` (or just run the wizard and answer the prompts). From Android 11 the app plays videos through its own web server (other apps cannot read another app's folders), and with no USB drive it simply downloads to the TV's internal storage. Google TV support is **untested** so far.

Things that depend on the TV, not on this project: the Google launcher keeps the HOME key (open this launcher from Apps → "Inicio" or map a remote button to it); on some brands the accessibility-based button remapping may be restricted; the screen-off call used by *Download mode* (`GLOBAL_ACTION_LOCK_SCREEN`) may behave differently on other firmware.

## Build from source

Needs JDK 17 and the Android SDK.

```bash
# local.properties (never committed):
#   sdk.dir=/path/to/android-sdk
#   tmdb.key=...  region=US  lang=en-US     # optional build-time defaults; normally set on the TV at run time
./gradlew :app:assembleRelease :torrent:assembleRelease
```

Both APKs must be signed with the **same key** (they talk through a signature-protected ContentProvider). Without configuration, builds use your machine's debug key (fine for personal use). For releases set `signing.keystore`, `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword` in `local.properties` (or the `TVL_*` environment variables used by the CI workflow in `.github/workflows/release.yml`; pushing a `v*` tag builds and publishes the APKs).

## Launcher (`app/`)

- Home with apps, HDMI inputs, "continue watching" (opens the Google launcher) and Settings.
- Focusing an app shows what is popular on that platform (TMDB discover by watch provider; region and language are set on the TV: installer, web manager → Settings), with backdrop, synopsis, cast, rating and runtime/seasons.
- Stremio titles deep-link to the exact page (`stremio:///detail/<series|movie>/<imdb>`). Other platforms expose no public id, so only the app opens.
- Remote-control buttons are remappable through an accessibility service (it only sees key events; it does not read screen content and logs nothing): open an app, switch input, open Settings, go home, or "do nothing".
- Known limit: a third-party launcher cannot read other apps' "continue watching" (system-only permission), and the HOME key itself cannot be overridden.


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


## Lessons learned

- All engine and disk work must run off the main thread: a slow USB on the main thread gets the app killed (ANR).
- Idle shutdown of the web service must account for open streams, or long playbacks through it die after the idle timeout.
- Rows in the TV list are updated in place (not rebuilt) so D-pad focus is not lost.
