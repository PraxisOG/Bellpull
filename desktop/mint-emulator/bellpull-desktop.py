#!/usr/bin/env python3
"""Bellpull desktop preview: an isolated Android virtual device, never a phone target."""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile

APP = Path(__file__).resolve().parent
PACKAGE = "org.praxisog.hermes"
AVD_NAME = "Bellpull_Desktop_API_36"
IMAGE = "system-images;android-36;default;x86_64"
TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"
TOOLS_SHA256 = "0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8"


class PreviewError(RuntimeError):
    pass


def data_dir() -> Path:
    if os.environ.get("BELLPULL_DESKTOP_DATA"):
        return Path(os.environ["BELLPULL_DESKTOP_DATA"]).expanduser().resolve()
    return Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share"))) / "bellpull-desktop"


def avd_name(state: Path) -> str:
    # Two installations/test profiles must never claim each other's emulator.
    suffix = hashlib.sha256(str(state.resolve()).encode()).hexdigest()[:12]
    return f"{AVD_NAME}_{suffix}"


def call(args: list[str | Path], *, env=None, capture=True, check=True, timeout=60, **kwargs):
    result = subprocess.run([str(a) for a in args], env=env, text=True,
        stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None,
        check=False, timeout=timeout, **kwargs)
    if check and result.returncode:
        raise PreviewError(f"{Path(args[0]).name} failed:\n{(result.stdout or '').strip()}")
    return result


def sdk_manager(sdk: Path) -> Path:
    return sdk / "cmdline-tools/latest/bin/sdkmanager"


def find_sdk(state: Path) -> Path | None:
    candidates = []
    for name in ("BELLPULL_ANDROID_SDK", "ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]).expanduser())
    config = state / "sdk.json"
    if config.exists():
        candidates.append(Path(json.loads(config.read_text())["path"]))
    candidates += [Path.home() / "Android/Sdk", state / "sdk"]
    for sdk in candidates:
        if sdk_manager(sdk).is_file():
            return sdk.resolve()
    return None


def environment(state: Path, sdk: Path) -> dict[str, str]:
    return {**os.environ, "ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(sdk),
        "ANDROID_AVD_HOME": str(state / "avd")}


def require_host():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        raise PreviewError("This preview requires 64-bit Intel/AMD Linux.")
    if os.geteuid() == 0:
        raise PreviewError("Run Bellpull as your normal desktop user, not with sudo.")
    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        raise PreviewError("KVM is not accessible. Enable CPU virtualization and grant your account access to /dev/kvm (usually the kvm group), then log in again.")


def bootstrap_sdk(state: Path) -> Path:
    if not shutil.which("java"):
        raise PreviewError("Java 17 or later is needed for Android setup. On Mint: sudo apt install openjdk-17-jre-headless")
    sdk = state / "sdk"
    archive = state / "commandlinetools.zip"
    print("Downloading Android command-line tools from Google (about 181 MB)…", flush=True)
    urllib.request.urlretrieve(TOOLS_URL, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != TOOLS_SHA256:
        archive.unlink(missing_ok=True)
        raise PreviewError("Android command-line tools checksum mismatch; download rejected.")
    staging = state / "tools-unpack"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            target = (staging / entry.filename).resolve()
            if not target.is_relative_to(staging.resolve()):
                raise PreviewError("Unsafe archive path rejected.")
        z.extractall(staging)
    destination = sdk / "cmdline-tools/latest"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(staging / "cmdline-tools"), destination)
    for path in (destination / "bin").iterdir():
        path.chmod(0o755)
    shutil.rmtree(staging)
    archive.unlink()
    return sdk


def installed(sdk: Path) -> bool:
    return all(p.is_file() for p in (sdk / "emulator/emulator", sdk / "platform-tools/adb",
        sdk / "system-images/android-36/default/x86_64/system.img"))


def setup(state: Path) -> Path:
    require_host()
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    sdk = find_sdk(state) or bootstrap_sdk(state)
    env = environment(state, sdk)
    if not installed(sdk):
        print("Installing the emulator and Android 16 AOSP image. Review any SDK license prompt below; first setup downloads several GB.", flush=True)
        call([sdk_manager(sdk), f"--sdk_root={sdk}", "platform-tools", "emulator", IMAGE],
            env=env, capture=False, timeout=None)
        if not installed(sdk):
            raise PreviewError("Android setup is incomplete. Rerun --setup after accepting the required SDK terms.")
    acceleration = call([sdk / "emulator/emulator", "-accel-check"], env=env)
    print(acceleration.stdout.strip(), flush=True)
    avd_home = state / "avd"
    avd_home.mkdir(exist_ok=True)
    name = avd_name(state)
    avd = avd_home / f"{name}.avd"
    if not (avd / "config.ini").exists():
        if avd.exists():
            raise PreviewError(f"An incomplete virtual device exists at {avd}; inspect it before retrying. No data was removed.")
        call([sdk / "cmdline-tools/latest/bin/avdmanager", "create", "avd", "--name", name,
            "--package", IMAGE, "--path", avd], input="no\n", env=env, timeout=180)
        config = avd / "config.ini"
        values = dict(tuple(part.strip() for part in line.split("=", 1))
            for line in config.read_text().splitlines() if "=" in line)
        values.update({"avd.ini.displayname": "Bellpull Desktop", "hw.lcd.width": "1280",
            "hw.lcd.height": "800", "hw.lcd.density": "160", "hw.ramSize": "2048",
            "hw.cpu.ncore": "2", "hw.keyboard": "yes", "hw.mainKeys": "no",
            "hw.initialOrientation": "landscape", "hw.gpu.enabled": "yes", "hw.gpu.mode": "auto",
            "disk.dataPartition.size": "4G", "skin.name": "1280x800", "skin.path": "1280x800",
            "showDeviceFrame": "no", "fastboot.forceColdBoot": "no", "fastboot.forceFastBoot": "yes"})
        config.write_text("\n".join(f"{k}={v}" for k, v in sorted(values.items())) + "\n")
    (state / "sdk.json").write_text(json.dumps({"path": str(sdk)}, indent=2) + "\n")
    print("Bellpull's separate virtual device is ready.", flush=True)
    return sdk


def serial_for(port: int) -> str:
    if not 5554 <= port <= 5680 or port % 2:
        raise PreviewError("Emulator port must be an even number between 5554 and 5680.")
    return f"emulator-{port}"


def adb(sdk: Path, serial: str, *args: str, check=True, timeout=30):
    # All device operations have an explicit emulator serial. No adb -d or default target.
    if not serial.startswith("emulator-"):
        raise PreviewError("Refusing a non-emulator ADB target.")
    return call([sdk / "platform-tools/adb", "-s", serial, *args], check=check, timeout=timeout)


def validate_avd_reply(serial: str, reply: str, expected_name: str):
    if not serial.startswith("emulator-") or reply.splitlines()[:1] != [expected_name]:
        raise PreviewError(f"{serial} is not Bellpull's virtual device. It was left untouched.")


def verify_target(sdk: Path, serial: str, expected_name: str):
    result = adb(sdk, serial, "emu", "avd", "name")
    validate_avd_reply(serial, result.stdout.strip(), expected_name)
    qemu = adb(sdk, serial, "shell", "getprop", "ro.kernel.qemu").stdout.strip()
    if qemu != "1":
        raise PreviewError("Target did not identify itself as an emulator; refusing installation.")


def port_available(port: int):
    for p in (port, port + 1):
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("127.0.0.1", p))
            except OSError as e:
                raise PreviewError(f"Port {p} is already in use. Set BELLPULL_EMULATOR_PORT to another free even port.") from e


def launch(state: Path, sdk: Path, port: int, headless: bool):
    serial = serial_for(port)
    apk = APP / "bellpull.apk"
    if not apk.is_file():
        raise PreviewError("bellpull.apk is missing. Use the packaged preview, or run build.py first.")
    metadata = json.loads((APP / "build-info.json").read_text())
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != metadata["apk_sha256"]:
        raise PreviewError("Bundled APK checksum mismatch; refusing installation.")
    running = adb(sdk, serial, "get-state", check=False).stdout.strip() == "device"
    process = None
    if not running:
        port_available(port)
        env = environment(state, sdk)
        command = [sdk / "emulator/emulator", "-avd", avd_name(state), "-port", str(port),
            "-no-boot-anim", "-gpu", os.environ.get("BELLPULL_EMULATOR_GPU", "auto"),
            "-netdelay", "none", "-netspeed", "full"]
        if headless:
            command += ["-no-window", "-no-audio"]
        log_path = state / "emulator.log"
        with log_path.open("w") as log:
            process = subprocess.Popen([str(x) for x in command], env=env, stdout=log,
                stderr=subprocess.STDOUT, start_new_session=True)
        print(f"Starting Bellpull's Android desktop ({serial}); first boot can take a minute…", flush=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise PreviewError(f"The emulator exited. See {log_path}; try BELLPULL_EMULATOR_GPU=software if graphics initialization failed.")
            booted = adb(sdk, serial, "shell", "getprop", "sys.boot_completed", check=False, timeout=8)
            if booted.returncode == 0 and booted.stdout.strip() == "1":
                break
            time.sleep(1)
        else:
            raise PreviewError(f"The emulator did not boot in time. See {log_path}. No device was reset.")
    verify_target(sdk, serial, avd_name(state))
    stamp = state / "installed-apk.sha256"
    app_exists = adb(sdk, serial, "shell", "pm", "path", PACKAGE, check=False).stdout.startswith("package:")
    if not app_exists or not stamp.exists() or stamp.read_text().strip() != digest:
        print("Installing Bellpull into its virtual device…", flush=True)
        adb(sdk, serial, "install", "-r", str(apk), timeout=120)
        stamp.write_text(digest + "\n")
    adb(sdk, serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    print(f"Bellpull is running on {serial}. Close its emulator window to stop it; app data is retained.", flush=True)


def open_setup_terminal():
    command = [str(APP / "bellpull-desktop.py"), "--setup-and-launch"]
    if shutil.which("x-terminal-emulator"):
        subprocess.Popen(["x-terminal-emulator", "-e", *command], start_new_session=True)
        return True
    return False


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--setup", action="store_true", help="Prepare the SDK and a separate virtual device")
    actions.add_argument("--setup-and-launch", action="store_true", help=argparse.SUPPRESS)
    actions.add_argument("--stop", action="store_true", help="Stop only Bellpull's emulator, retaining its data")
    actions.add_argument("--doctor", action="store_true", help="Show SDK, KVM and state-directory information")
    parser.add_argument("--headless", action="store_true", help="Run without a window for automated checks")
    parser.add_argument("--no-dialog", action="store_true", help="Report errors to stderr without a desktop popup")
    args = parser.parse_args(argv)
    state = data_dir()
    os.umask(0o077)
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    sdk = find_sdk(state)
    port = int(os.environ.get("BELLPULL_EMULATOR_PORT", "5580"))
    serial = serial_for(port)
    if args.doctor:
        print(json.dumps({"state": str(state), "sdk": str(sdk) if sdk else None,
            "kvm_accessible": os.access("/dev/kvm", os.R_OK | os.W_OK),
            "avd": avd_name(state), "serial": serial, "system_image": IMAGE}, indent=2))
        return
    if args.stop:
        if sdk is None:
            raise PreviewError("No Bellpull Android SDK is configured.")
        verify_target(sdk, serial, avd_name(state))
        adb(sdk, serial, "emu", "kill")
        deadline = time.monotonic() + 45
        while True:
            try:
                port_available(port)
                break
            except PreviewError:
                if time.monotonic() >= deadline:
                    raise PreviewError("Shutdown was requested, but the emulator ports are still occupied. Wait before restarting; no process was forcibly killed.")
                time.sleep(0.5)
        print("Bellpull's emulator stopped. Its data was retained.")
        return
    require_host()
    needs_setup = sdk is None or not installed(sdk) or not (state / "avd" / f"{avd_name(state)}.avd/config.ini").is_file()
    if needs_setup and not args.setup and not args.setup_and_launch and not sys.stdin.isatty() and not args.headless:
        if open_setup_terminal():
            return
        raise PreviewError("First setup needs a terminal for the Android SDK license prompts. Run bellpull-desktop --setup.")
    with (state / "launch.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as e:
            raise PreviewError("Bellpull is already starting. Please wait for its window.") from e
        if needs_setup or args.setup or args.setup_and_launch:
            sdk = setup(state)
        if not args.setup:
            launch(state, sdk, port, args.headless)


if __name__ == "__main__":
    try:
        main()
    except (PreviewError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        message = str(error)
        print(f"Bellpull: {message}", file=sys.stderr)
        if not sys.stderr.isatty() and os.environ.get("DISPLAY") and shutil.which("zenity") and not any(
            a in sys.argv for a in ("--headless", "--no-dialog", "--setup", "--stop", "--doctor")):
            subprocess.run(["zenity", "--error", "--title=Bellpull desktop preview", "--text", message])
        raise SystemExit(1)
