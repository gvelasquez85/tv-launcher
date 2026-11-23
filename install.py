#!/usr/bin/env python3
"""
tv-launcher installer  (Python 3.8+, standard library only; Windows, macOS and Linux)

Connects to an Android TV over the network (ADB), installs the launcher and the downloader,
configures them, and (optionally) disables bloatware in a reversible way.

  python3 install.py                      interactive wizard
  python3 install.py --tv 192.168.1.50    skip the TV question
  python3 install.py status               what is installed / configured
  python3 install.py debloat              optionally disable telemetry/promo apps (reversible)
  python3 install.py revert               re-enable everything `debloat` disabled
  python3 install.py uninstall            remove both apps

Instalador de tv-launcher: se conecta a tu Android TV por ADB, instala y configura las dos apps.
Ejecuta `python3 install.py --help` para ver las opciones.
"""
import argparse
import concurrent.futures
import json
import locale
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / ".tools"
STATE_DIR = Path.home() / ".tv-launcher"
GITHUB_REPO = "gvelasquez85/tv-launcher"
LAUNCHER, TORRENT = "app.tvlauncher", "app.tvtorrent"
SERVICE = f"{LAUNCHER}/{LAUNCHER}.KeyMapService"
APKS = {LAUNCHER: "app-release.apk", TORRENT: "torrent-release.apk"}

# Packages that are known to be safe to disable (never deleted) on some TVs. Each entry is optional and only
# offered if it is installed. Anything not listed here is never touched.
DEBLOAT = [
    ("Viewing-data tracking (Samba TV / automatic content recognition)", ["tv.samba.ssm"]),
    ("Sony usage analytics", ["com.sony.dtv.da.service", "com.qterics.da.product"]),
    ("Sony debug logging and bug-report collectors",
     ["com.sony.dtv.sonyloglevelsettingsys", "com.sony.dtv.sonyloglevelsettingvnd", "com.sony.dtv.sonybugreportsys"]),
    ("Google feedback / bug-report uploaders", ["com.google.android.feedback", "com.google.android.tv.bugreportsender"]),
    ("Sony promotional apps (Sony Select, content promos)",
     ["com.sony.dtv.sonyselect", "com.sony.dtv.smartmediaapp", "com.sony.dtv.osat.music", "com.sony.dtv.bivlinfo"]),
    ("Initial setup wizard (only needed on first boot)", ["com.google.android.tungsten.setupwraith"]),
]
# Never offered, whatever happens: disabling these can brick navigation or the app store.
PROTECTED = {"com.android.vending", "com.google.android.tvlauncher", "com.android.tv.settings", "com.android.settings",
             "com.google.android.gms", "com.google.android.gsf", LAUNCHER, TORRENT, "com.android.systemui"}

YES = False
DRY = False


# ----------------------------------------------------------------------------- helpers

def say(msg=""):
    print(msg, flush=True)


def ask(question, default=""):
    if YES:
        return default
    try:
        a = input(f"{question} " + (f"[{default}] " if default else "")).strip()
    except EOFError:
        a = ""
    return a or default


def confirm(question, default=True):
    if YES:
        return default
    a = ask(f"{question} ({'Y/n' if default else 'y/N'})", "y" if default else "n").lower()
    return a.startswith("y") or a.startswith("s")


def die(msg, code=1):
    say(f"\nERROR: {msg}")
    sys.exit(code)


class Adb:
    def __init__(self, exe):
        self.exe = exe
        self.serial = None

    def raw(self, *args, timeout=60):
        cmd = [self.exe] + ([] if self.serial is None or args[:1] in (("connect",), ("devices",), ("disconnect",)) else ["-s", self.serial]) + list(args)
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            return p.returncode, (p.stdout + p.stderr).strip()
        except subprocess.TimeoutExpired:
            return 124, "timeout"

    def sh(self, command, timeout=60):
        return self.raw("shell", command, timeout=timeout)[1]


def find_adb(explicit=None):
    cands = [explicit, shutil.which("adb"), os.environ.get("ADB")]
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            cands.append(str(Path(os.environ[env]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    cands.append(str(TOOLS / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    for c in cands:
        if c and Path(c).exists():
            return c
    say("ADB (Android platform-tools) was not found.")
    if not confirm("Download Google's official platform-tools into ./.tools now?"):
        die("Install ADB (https://developer.android.com/tools/releases/platform-tools) or pass --adb /path/to/adb.")
    system = {"Darwin": "darwin", "Windows": "windows"}.get(platform.system(), "linux")
    url = f"https://dl.google.com/android/repository/platform-tools-latest-{system}.zip"
    TOOLS.mkdir(exist_ok=True)
    zpath = TOOLS / "platform-tools.zip"
    say(f"Downloading {url} ...")
    urllib.request.urlretrieve(url, zpath)
    with zipfile.ZipFile(zpath) as z:
        z.extractall(TOOLS)
    zpath.unlink()
    exe = TOOLS / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    if os.name != "nt":
        exe.chmod(0o755)
    return str(exe)


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return None


def scan_for_adb():
    ip = local_ip()
    if not ip:
        return []
    base = ip.rsplit(".", 1)[0]
    say(f"Scanning {base}.0/24 for devices with network debugging (port 5555)...")

    def probe(h):
        s = socket.socket()
        s.settimeout(0.4)
        ok = s.connect_ex((f"{base}.{h}", 5555)) == 0
        s.close()
        return f"{base}.{h}" if ok else None

    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        return [r for r in ex.map(probe, range(1, 255)) if r]


def pair(adb, addr, code):
    """Android 11+ (Google TV, newer Android TV): wireless debugging needs a one-time pairing."""
    if not addr:
        say("\nThis TV uses Android 11+ wireless debugging. On the TV: Developer options → Wireless debugging →")
        say("'Pair device with pairing code'. It shows an IP:port and a 6-digit code.")
        addr = ask("Pairing address (IP:port):")
        code = code or ask("Pairing code:")
    if not addr:
        return
    code = code or ask("Pairing code:")
    rc, out = adb.raw("pair", addr, code, timeout=30)
    say(out)
    if "Successfully paired" not in out:
        die("Pairing failed. The code expires quickly: open the pairing dialog again and retry.")


def connect(adb, tv, pair_addr=None, pair_code=None):
    if pair_addr:
        pair(adb, pair_addr, pair_code)
    if not tv:
        _, out = adb.raw("devices")
        ready = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
        if len(ready) == 1:
            adb.serial = ready[0]
            return
        found = scan_for_adb()
        if found:
            say("Found: " + ", ".join(found))
            tv = found[0] if len(found) == 1 else ask("Which one is your TV? (IP)", found[0])
        else:
            say("\nNo TV found automatically. On the TV: Settings → Device Preferences → About → tap 'Build' 7 times,")
            say("then Developer options → enable 'USB debugging' / 'Network debugging' (wording varies by brand).")
            tv = ask("TV address (IP, or IP:port shown under 'Wireless debugging' on Android 11+):")
    if not tv:
        die("No TV address given.")
    if ":" not in tv:
        tv += ":5555"
    rc, out = adb.raw("connect", tv, timeout=20)
    say(out)
    adb.serial = tv
    for _ in range(3):
        _, state = adb.raw("get-state", timeout=15)
        if state.strip() == "device":
            return
        if "unauthorized" in state or "unauthorized" in out:
            say("\nA prompt should be on the TV screen: 'Allow USB debugging?'. Tick 'Always allow' and press Allow.")
            ask("Press Enter when you have accepted it...", "")
            adb.raw("connect", tv, timeout=20)
        else:
            break
    _, state = adb.raw("get-state", timeout=15)
    if state.strip() != "device" and not pair_addr and ("refused" in out or "failed" in out or "unauthorized" in out or "offline" in state):
        # Android 11+ needs pairing before the first connection.
        if confirm("Could not connect. Is this an Android 11+ TV using 'Wireless debugging' (needs pairing)?", True):
            pair(adb, None, None)
            tv = ask("Now the CONNECT address shown on the main Wireless debugging screen (IP:port):", tv)
            adb.raw("connect", tv, timeout=20)
            adb.serial = tv
            _, state = adb.raw("get-state", timeout=15)
    if state.strip() != "device":
        die(f"Could not reach the TV at {tv} ({state or out}). Check that TV and computer are on the same network and "
            "network debugging is enabled.")


def device_info(adb):
    props = {k: adb.sh(f"getprop {k}").strip() for k in
             ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abilist", "ro.product.manufacturer")}
    leanback = "true" in adb.sh("pm has-feature android.software.leanback")
    return props, leanback


def packages(adb, enabled_only=False):
    out = adb.sh("pm list packages" + (" -e" if enabled_only else ""), timeout=90)
    return set(l.replace("package:", "").strip() for l in out.splitlines() if l.startswith("package:"))


# ----------------------------------------------------------------------------- APKs

def have(path):
    return all((path / n).exists() for n in APKS.values())


def find_local_apks(explicit):
    for d in [explicit, ROOT / "dist"]:
        if d and have(Path(d)):
            return Path(d)
    built = {p: ROOT / m / "build" / "outputs" / "apk" / "release" / n for p, (m, n) in
             {LAUNCHER: ("app", APKS[LAUNCHER]), TORRENT: ("torrent", APKS[TORRENT])}.items()}
    if all(b.exists() for b in built.values()):
        out = ROOT / "dist"
        out.mkdir(exist_ok=True)
        for p, b in built.items():
            shutil.copy(b, out / APKS[p])
        return out
    return None


def download_release():
    api = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
    try:
        with urllib.request.urlopen(urllib.request.Request(api, headers={"User-Agent": "tv-launcher-installer"}), timeout=20) as r:
            rel = json.load(r)
    except (urllib.error.URLError, OSError, ValueError):
        return None
    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    assets = {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}
    if not all(n in assets for n in APKS.values()):
        return None
    say(f"Downloading release {rel.get('tag_name')} ...")
    for n in APKS.values():
        urllib.request.urlretrieve(assets[n], out / n)
    return out


def build_from_source():
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    env = dict(os.environ)
    if not (env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or (ROOT / "local.properties").exists()):
        die("To build from source you need the Android SDK: set ANDROID_HOME or create local.properties with sdk.dir=...\n"
            "(or download prebuilt APKs from the GitHub Releases page into ./dist).")
    if not shutil.which("java") and not env.get("JAVA_HOME"):
        die("To build from source you need JDK 17 (set JAVA_HOME).")
    say("Building the apps (first run downloads Gradle and dependencies; it can take several minutes)...")
    rc = subprocess.call([str(gradlew), ":app:assembleRelease", ":torrent:assembleRelease"], cwd=ROOT, env=env)
    if rc != 0:
        die("The build failed.")
    return find_local_apks(None)


def get_apks(args):
    d = find_local_apks(args.apk_dir)
    if d and not args.build:
        return d
    if not args.build:
        d = download_release()
        if d:
            return d
        say("No prebuilt APKs found (./dist or GitHub Releases).")
        if not confirm("Build them from source instead?"):
            die("Put app-release.apk and torrent-release.apk in ./dist and run again.")
    return build_from_source() or die("APKs not found after the build.")


# ----------------------------------------------------------------------------- steps

def tmdb_valid(key):
    try:
        with urllib.request.urlopen(f"https://api.themoviedb.org/3/configuration?api_key={key}", timeout=15) as r:
            return r.status == 200
    except urllib.error.HTTPError as e:
        return e.code != 401
    except (urllib.error.URLError, OSError):
        return None   # offline: can't tell


def default_locale():
    try:
        loc = locale.getdefaultlocale()[0] or ""
    except Exception:
        loc = ""
    m = re.match(r"([a-z]{2})_([A-Z]{2})", loc)
    return (m.group(2), f"{m.group(1)}-{m.group(2)}") if m else ("US", "en-US")


def install_apk(adb, pkg, apk):
    args = ["install", "-r"] + (["--install-location", "1"] if pkg == TORRENT else []) + [str(apk)]
    say(f"Installing {pkg} ...")
    if DRY:
        return
    rc, out = adb.raw(*args, timeout=300)
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
        say(f"{pkg} is already installed with a different signing key.")
        if not confirm(f"Uninstall it (its settings are lost) and reinstall?", True):
            die("Cannot continue: both apps must be signed with the same key.")
        adb.raw("uninstall", pkg)
        rc, out = adb.raw(*args, timeout=300)
    if "Success" not in out:
        die(f"Installing {pkg} failed:\n{out}")


def enable_accessibility(adb):
    """Lets the launcher remap remote buttons. Appends to the list: never overwrites other services."""
    adb.raw("shell", "pm", "grant", LAUNCHER, "android.permission.WRITE_SECURE_SETTINGS")
    cur = adb.sh("settings get secure enabled_accessibility_services").strip()
    cur = "" if cur in ("null", "") else cur
    if SERVICE not in cur.split(":"):
        new = f"{cur}:{SERVICE}" if cur else SERVICE
        adb.sh(f"settings put secure enabled_accessibility_services {new}")
    adb.sh("settings put secure accessibility_enabled 1")


def step_install(adb, args):
    props, leanback = device_info(adb)
    say(f"\nTV: {props['ro.product.manufacturer']} {props['ro.product.model']}  ·  Android {props['ro.build.version.release']} "
        f"(API {props['ro.build.version.sdk']})  ·  {props['ro.product.cpu.abilist']}")
    sdk = int(props["ro.build.version.sdk"] or 0)
    if sdk and sdk < 28:
        die("This project needs Android 9 (API 28) or newer.")
    if not leanback:
        say("WARNING: this device does not report the Android TV (leanback) feature. Continuing anyway.")
    abis = props["ro.product.cpu.abilist"]
    if abis and not any(a in abis for a in ("armeabi-v7a", "arm64-v8a")):
        say("WARNING: the downloader's torrent engine ships ARM libraries only; it will not run on this CPU.")

    apks = get_apks(args)

    region, lang = default_locale()
    key = args.tmdb_key or os.environ.get("TMDB_KEY", "")
    if not key and not YES:
        say("\nThe launcher shows popular titles per streaming service using TMDB (free).")
        say("Get a free API key (v3): https://www.themoviedb.org/settings/api   — leave empty to skip for now.")
        key = ask("TMDB API key (v3):")
    if key:
        ok = tmdb_valid(key)
        if ok is False:
            say("TMDB rejected that key; it will not be saved.")
            key = ""
        elif ok is None:
            say("(Could not reach TMDB to check the key; saving it anyway.)")
    region = args.region or (ask("Country code for streaming catalogues (e.g. US, CO, ES, MX)", region) if key else region)
    lang = args.lang or (ask("Language (e.g. en-US, es-CO, pt-BR)", lang) if key else lang)

    for pkg in (LAUNCHER, TORRENT):
        install_apk(adb, pkg, apks / APKS[pkg])
    if DRY:
        return
    enable_accessibility(adb)
    if key:
        adb.sh(f"am start -n {LAUNCHER}/.SetupActivity --es tmdb_key {key} --es region {region} --es lang {lang}")
        say("TMDB settings sent to the TV.")
    adb.sh("input keyevent KEYCODE_HOME")
    adb.sh(f"am start -n {LAUNCHER}/.MainActivity")
    adb.sh(f"am start -n {TORRENT}/.DownloadsActivity")
    say("\nDone. What happens next:")
    say("  • The launcher is installed, but Android keeps its own home screen. Open it from Apps → 'Inicio', or map a remote")
    say("    button to it in the web manager (Buttons tab).")
    say("  • The Descargas app is open on the TV and shows the address and password of the web manager.")
    say("  • Optional: plug a USB drive into the TV, formatted as portable storage (not 'adopted'), for downloads.")
    if not key:
        say("  • No TMDB key was saved: add it later in the web manager (Settings) to get title suggestions.")


def step_debloat(adb):
    installed = packages(adb, enabled_only=True)   # already-disabled ones are not offered again
    state_file = STATE_DIR / f"{(adb.serial or 'device').replace(':', '_')}.json"
    done = json.loads(state_file.read_text()) if state_file.exists() else []
    any_offer = False
    for label, pkgs in DEBLOAT:
        pkgs = [p for p in pkgs if p in installed and p not in PROTECTED and p not in done]
        if not pkgs:
            continue
        any_offer = True
        say(f"\n{label}\n  " + "\n  ".join(pkgs))
        if confirm("Disable these (reversible with `install.py revert`)?", False):
            for p in pkgs:
                if not DRY:
                    adb.sh(f"pm disable-user --user 0 {p}")
                done.append(p)
    if not any_offer:
        say("Nothing from the known list is installed on this TV.")
    if done and not DRY:
        STATE_DIR.mkdir(exist_ok=True)
        state_file.write_text(json.dumps(sorted(set(done))))
        say(f"\nRecorded in {state_file}. Undo with: python3 install.py revert")


def step_revert(adb):
    state_file = STATE_DIR / f"{(adb.serial or 'device').replace(':', '_')}.json"
    if state_file.exists():
        todo = json.loads(state_file.read_text())
    else:
        say(f"No record for this TV ({state_file}). Re-enabling every package from the known list that is installed.")
        known = {p for _, ps in DEBLOAT for p in ps}
        todo = sorted(known & packages(adb))
    for p in todo:
        adb.sh(f"pm enable {p}")
        say(f"re-enabled {p}")
    state_file.unlink(missing_ok=True) if sys.version_info >= (3, 8) else None
    adb.sh("settings delete global window_animation_scale; settings delete global transition_animation_scale; "
           "settings delete global animator_duration_scale")
    say("Done. Reboot the TV if something does not appear.")


def step_status(adb):
    props, _ = device_info(adb)
    say(f"TV: {props['ro.product.manufacturer']} {props['ro.product.model']} · Android {props['ro.build.version.release']}")
    inst = packages(adb)
    for pkg in (LAUNCHER, TORRENT):
        if pkg in inst:
            v = re.search(r"versionName=(\S+)", adb.sh(f"dumpsys package {pkg}"))
            say(f"  {pkg}: installed ({v.group(1) if v else '?'})")
        else:
            say(f"  {pkg}: NOT installed")
    en = adb.sh("settings get secure enabled_accessibility_services")
    say(f"  remote-button service: {'enabled' if SERVICE in en else 'DISABLED (reinstalling the launcher turns it off; run the installer again)'}")
    ip = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", adb.sh("ip -f inet addr show wlan0; ip -f inet addr show eth0"))
    say(f"  web manager: http://{ip.group(1)}:8800  (user admin; password on the TV's Descargas screen)" if ip else "  web manager: address unknown")


def step_uninstall(adb):
    if confirm("Remove both apps from the TV?", False):
        for pkg in (TORRENT, LAUNCHER):
            say(adb.raw("uninstall", pkg)[1])


# ----------------------------------------------------------------------------- main

def main():
    global YES, DRY
    ap = argparse.ArgumentParser(description="Install the tv-launcher apps on an Android TV over the network.")
    ap.add_argument("command", nargs="?", default="install", choices=["install", "status", "debloat", "revert", "uninstall"])
    ap.add_argument("--tv", help="TV address, e.g. 192.168.1.50 or 192.168.1.50:5555")
    ap.add_argument("--adb", help="path to the adb executable")
    ap.add_argument("--pair", help="Android 11+ wireless debugging: pairing address shown on the TV, e.g. 192.168.1.50:37123")
    ap.add_argument("--pair-code", help="the 6-digit pairing code shown on the TV")
    ap.add_argument("--apk-dir", help="folder containing app-release.apk and torrent-release.apk")
    ap.add_argument("--build", action="store_true", help="build the APKs from source instead of using prebuilt ones")
    ap.add_argument("--tmdb-key", help="TMDB v3 API key (or env TMDB_KEY)")
    ap.add_argument("--region", help="country code for catalogues, e.g. US")
    ap.add_argument("--lang", help="language, e.g. en-US")
    ap.add_argument("-y", "--yes", action="store_true", help="non-interactive: accept defaults (never disables bloatware)")
    ap.add_argument("--dry-run", action="store_true", help="show what would be installed without changing the TV")
    args = ap.parse_args()
    YES, DRY = args.yes, args.dry_run

    adb = Adb(find_adb(args.adb))
    adb.raw("start-server")
    connect(adb, args.tv, args.pair, args.pair_code)
    try:
        {"install": lambda: step_install(adb, args), "status": lambda: step_status(adb), "debloat": lambda: step_debloat(adb),
         "revert": lambda: step_revert(adb), "uninstall": lambda: step_uninstall(adb)}[args.command]()
    except KeyboardInterrupt:
        die("Interrupted.", 130)
    if args.command == "install" and not DRY and confirm("\nDisable known telemetry/promo apps now? (optional, reversible)", False):
        step_debloat(adb)


if __name__ == "__main__":
    main()
