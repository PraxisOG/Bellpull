#!/usr/bin/env python3
"""Read-only ADB benchmark log; severe thermal status stops ONLY the fixture app."""
import argparse
import datetime
import json
import math
import re
import subprocess
import time
from pathlib import Path


def thermal_snapshot(raw):
    status = re.search(r"^Thermal Status: (\d+)$", raw, re.M)
    # Cached temperatures can be old peak values. Never report them as live samples.
    current = raw.split("Current temperatures from HAL:\n", 1)[-1] if "Current temperatures from HAL:\n" in raw else ""
    current = current.split("Current cooling devices", 1)[0].split("Temperature static thresholds", 1)[0]
    sensors = []
    for value, kind, name, severity in re.findall(
        r"Temperature\{mValue=([^,]+), mType=(-?\d+), mName=([^,]+), mStatus=(\d+)\}", current
    ):
        number = float(value)
        sensor_type = int(kind)
        # HAL -1 includes differences, brightness and policy hints, not just temperatures.
        is_temperature = sensor_type in {0, 1, 2, 3, 4, 5, 6, 9, 11} or name.endswith("_therm") or name.startswith("maxfg_")
        available = math.isfinite(number) and -40 <= number <= 150
        sensors.append({"label": name, "hal_type": sensor_type, "status": int(severity),
                        "value": number if available else None,
                        "unit": "C" if is_temperature else "vendor quantity (not interpreted)",
                        "available": available})
    return {"thermal_status": int(status[1]) if status else None, "sensors": sensors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=180)
    parser.add_argument("--interval", type=float, default=2)
    args = parser.parse_args()
    if args.interval < 1 or args.seconds <= 0:
        parser.error("Use an interval of at least one second and positive duration.")

    def shell(*command):
        return subprocess.run([args.adb, "-s", args.serial, "shell", *command], check=True,
                              capture_output=True, text=True, timeout=15).stdout.strip()

    conditions = {"device": shell("getprop", "ro.product.model"),
                  "build": shell("getprop", "ro.build.fingerprint"),
                  "brightness_setting": shell("settings", "get", "system", "screen_brightness"),
                  "brightness_mode": shell("settings", "get", "system", "screen_brightness_mode"),
                  "fold_state_raw": shell("dumpsys", "device_state")}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    with args.output.open("w") as output:
        output.write(json.dumps({"conditions": conditions}) + "\n")
        while time.monotonic() - started < args.seconds:
            sample = thermal_snapshot(shell("dumpsys", "thermalservice"))
            sample.update(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                          elapsed_s=time.monotonic() - started,
                          battery_raw=shell("dumpsys", "battery"))
            output.write(json.dumps(sample) + "\n")
            output.flush()
            if sample["thermal_status"] is None:
                raise RuntimeError("Thermal status unavailable; stop testing until monitoring is restored.")
            if sample["thermal_status"] >= 3:
                shell("am", "force-stop", "org.praxisog.hermes.fixture")
                raise RuntimeError("Severe thermal status: stopped fixture app. Cool before resuming.")
            time.sleep(min(args.interval, max(0, args.seconds - (time.monotonic() - started))))


if __name__ == "__main__":
    main()
