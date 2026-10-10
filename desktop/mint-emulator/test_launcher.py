"""Safety boundaries for a launcher that shares ADB with a real phone."""
import importlib.util
import json
from pathlib import Path
import subprocess
import socket
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("launcher", Path(__file__).with_name("bellpull-desktop.py"))
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)


class TargetIsolationTests(unittest.TestCase):
    def test_recently_closed_console_port_can_be_reused(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with socket.create_connection(("127.0.0.1", port), timeout=1) as client:
                connection, _ = listener.accept()
                with connection:
                    connection.shutdown(socket.SHUT_WR)
                    self.assertEqual(client.recv(1), b"")
        launcher.port_available(port)

    def test_busy_port_is_rejected_without_disturbing_listener(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with self.assertRaises(launcher.PreviewError):
                launcher.port_available(port)
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                connection, _ = listener.accept()
                connection.close()

    def test_phone_serial_cannot_reach_adb(self):
        with patch.object(launcher, "call") as command:
            with self.assertRaises(launcher.PreviewError):
                launcher.adb(Path("/sdk"), "physical-phone", "install", "app.apk")
            command.assert_not_called()

    def test_other_avd_cannot_be_installed_or_stopped(self):
        with patch.object(launcher, "adb", return_value=subprocess.CompletedProcess([], 0, "Other_AVD\nOK\n")) as adb:
            with self.assertRaises(launcher.PreviewError):
                launcher.verify_target(Path("/sdk"), "emulator-5580", "Expected_AVD")
            self.assertEqual(adb.call_count, 1)
            self.assertEqual(adb.call_args.args[2:], ("emu", "avd", "name"))

    def test_matching_name_still_requires_emulator_property(self):
        replies = [subprocess.CompletedProcess([], 0, "Expected_AVD\nOK\n"),
                   subprocess.CompletedProcess([], 0, "0\n")]
        with patch.object(launcher, "adb", side_effect=replies):
            with self.assertRaises(launcher.PreviewError):
                launcher.verify_target(Path("/sdk"), "emulator-5580", "Expected_AVD")

    def test_profiles_have_distinct_stable_names(self):
        first, second = Path("/home/example/app-data"), Path("/home/example/test-data")
        self.assertNotEqual(launcher.avd_name(first), launcher.avd_name(second))
        self.assertEqual(launcher.avd_name(first), launcher.avd_name(first / "nested/.."))

    def test_bad_apk_fails_before_any_device_command(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            (app / "bellpull.apk").write_bytes(b"changed APK")
            (app / "build-info.json").write_text(json.dumps({"apk_sha256": "unmatched"}))
            with patch.object(launcher, "APP", app), patch.object(launcher, "adb") as adb:
                with self.assertRaisesRegex(launcher.PreviewError, "checksum"):
                    launcher.launch(app, Path("/sdk"), 5580, True)
                adb.assert_not_called()

    def test_setup_retains_existing_device_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            avd = state / "avd" / f"{launcher.avd_name(state)}.avd"
            avd.mkdir(parents=True)
            config = avd / "config.ini"
            original = "hw.ramSize=3072\ncustom.user.preference=keep\n"
            config.write_text(original)
            with patch.object(launcher, "require_host"), patch.object(launcher, "find_sdk", return_value=Path("/sdk")), \
                    patch.object(launcher, "installed", return_value=True), \
                    patch.object(launcher, "call", return_value=subprocess.CompletedProcess([], 0, "KVM available")) as call:
                launcher.setup(state)
            self.assertEqual(config.read_text(), original)
            self.assertEqual(call.call_count, 1)  # Acceleration check; no avdmanager recreate.


if __name__ == "__main__":
    unittest.main()
