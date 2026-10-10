Bellpull for Linux Mint — Android emulator preview
=================================================

This is the existing Bellpull Android app in a dedicated Android virtual device,
with a Linux launcher. It is an experimental desktop preview, not a native Compose
Desktop port. Your physical phone is never an ADB target. Phone data and login are
not copied; enter your Hermes server URL and API key separately in this preview.

Requirements
------------
64-bit Intel/AMD Linux Mint (tested on 22.3), hardware virtualization enabled,
read/write access to /dev/kvm, Java 17+, Python 3.10+, and a desktop session.
Allow several GB of download and roughly 8–12 GB of free disk for the Android SDK,
virtual disk and snapshots. The VM is configured with 2 GB RAM and two CPU cores;
actual host memory use also includes the emulator and graphics. This preview uses
an Android 16 AOSP image without Google Play.

Install
-------
Option 1: download the .deb, then install it with Mint's package installer or:
  sudo apt install ./bellpull-desktop-preview_*_amd64.deb

Option 2: extract the portable .tar.gz and run inside the extracted directory:
  python3 install.py
This installs for your account only, without sudo. Do not install both versions.
To run directly without installing, use ./bellpull-desktop.py in that directory.

Open "Bellpull (Android preview)" from the Mint menu. First setup opens a terminal
if needed to let you review Android SDK licenses. It reuses a compatible SDK in
~/Android/Sdk when available; otherwise it downloads official tools into Bellpull's
data directory. Google's command-line-tools archive is checked against a pinned
SHA-256. Emulator and system-image installation is managed by Google's sdkmanager.
Google's SDK license terms apply; no Android runtime is redistributed in this package.

Useful commands (installed launcher):
  bellpull-desktop --setup    Prepare the separate virtual device
  bellpull-desktop --doctor   Show SDK, KVM and virtual-device information
  bellpull-desktop            Start the window or reopen the app
  bellpull-desktop --stop     Stop only this preview's emulator; retain its data

If KVM is inaccessible, check virtualization in firmware and /dev/kvm permissions.
On Mint, an administrator can add your account to the kvm group; log out and back
in for group changes to take effect. The launcher never changes system permissions.
If graphics startup fails, try:
  BELLPULL_EMULATOR_GPU=software bellpull-desktop
Logs: ~/.local/share/bellpull-desktop/emulator.log

Connection and everyday use
--------------------------
Use a Hermes URL reachable from your Mint machine. Tailscale on the host may route
the connection, but emulator DNS/HTTPS resolution still needs to work. The app only
allows plain HTTP on loopback or Tailscale addresses. Android's host-loopback alias
10.0.2.2 therefore needs HTTPS. For a local development server, explicitly forward
its port into this emulator, then use http://127.0.0.1:PORT inside Bellpull:
  ~/Android/Sdk/platform-tools/adb -s emulator-5580 reverse tcp:8642 tcp:8642
Use your SDK path, emulator serial and server port. Reapply forwarding after a cold
boot if needed. Do not omit the emulator serial when a physical phone is attached.

The virtual display is 1280x800 at 160 dpi with a hardware keyboard enabled. The
existing responsive panel UI provides conversations, chat and context side by side.
Mouse, scroll and keyboard work through Android; window resizing scales the virtual
display, rather than behaving like a native resizable desktop application. Android's
own navigation and emulator controls remain visible. Immersive mode is optional in
Bellpull Settings. Close the emulator window or use --stop when finished.

Read-aloud depends on an Android TTS engine/voice, which is not bundled. Android
notifications stay within Android rather than integrating with Mint notifications.
The backend's current live-event consumption behavior may limit simultaneous phone
and desktop streaming; use one active client per run until server fan-out is added.
The APK is the current debug build, appropriate for this private development preview.
This release has no automatic updater; reinstall a newer package to update the APK.
Local history/login persist across launches and APK updates.

Isolation and removal
---------------------
Runtime data is under ~/.local/share/bellpull-desktop (or XDG_DATA_HOME). Each data
directory gets a distinct AVD name. Every device command has an explicit emulator
serial, and installation/stop verifies the AVD identity. No adb kill-server, phone
commands, device resets, or automatic data deletion are used.

Optional environment settings:
  BELLPULL_ANDROID_SDK       Existing SDK path
  BELLPULL_DESKTOP_DATA      Separate data directory (also isolates the AVD name)
  BELLPULL_EMULATOR_PORT     Free even console port, default 5580
  BELLPULL_EMULATOR_GPU      Emulator graphics mode, default auto

Removing the .deb retains your runtime data. For a per-user portable installation,
remove ~/.local/bin/bellpull-desktop, the bellpull-desktop.desktop menu entry under
~/.local/share/applications, and ~/.local/share/bellpull-desktop/app. Keep the other
files in the data directory to preserve login/history and the virtual device. Only
delete the whole data directory if you intend to erase that preview's local data.

Source and builds
-----------------
Repository: https://github.com/PraxisOG/Bellpull (private)
Source folder: desktop/mint-emulator
Build: python3 desktop/mint-emulator/build.py
Tests: python3 -m unittest discover -s desktop/mint-emulator -p 'test_*.py'
Build output: desktop/mint-emulator/build (ignored by git)
Only the launcher, icon, APK, metadata, installer and this README enter packages.
No virtual disks, SDK files, server keys, screenshots or developer app data are bundled.

Desktop smoke testing
---------------------
From the repository root, run python3 desktop/mint-emulator/smoke-server.py, then
forward port 18643 into a dedicated test emulator with adb -s SERIAL reverse.
Connect to http://127.0.0.1:18643 using the public key mock-hermes-test-key. Open
Fixture session and send a message. The synthetic server emits a reply word by
word and completes after eight seconds. It has no LLM and no persistent storage;
it supplements the recorded HTTP fixtures with the current durable-run routes.
Use BELLPULL_DESKTOP_DATA pointing at a test directory, and stop that emulator
before launching the normal profile on the same port. Never enter real credentials
into the smoke fixture. It is source-only and is not included in release packages.

The preview was tested on Mint 22.3 with emulator 37.1.11, KVM and an existing SDK:
fresh isolated AVD, connection, three-panel chat, sending, incremental replies,
composer clearing, recovery, repeated launch, stop/restart and saved connection.
Eight launcher tests cover device isolation, APK integrity, existing configuration
preservation and port reuse. A fresh SDK installation/license flow on a clean Mint
machine has not been exercised. The SDK bootstrap archive was downloaded and its
pinned SHA-256 verified against the archive identified by Google's repository index.

Official Android documentation:
https://developer.android.com/studio/run/emulator-acceleration
https://developer.android.com/studio/run/emulator-commandline
https://developer.android.com/studio/run/emulator-networking
https://developer.android.com/tools/sdkmanager
