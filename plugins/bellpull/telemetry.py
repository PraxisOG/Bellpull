"""Local CLI adapters; every subprocess is a fixed read-only argv with a timeout.

AMD JSON supports both older numeric values and newer {value, unit} records.
Display ranges are fixed, NOT normalized to this sample and NOT safety limits.
Where AMD reports a slowdown threshold, expose it separately from the scale.
"""
from __future__ import annotations

import asyncio
import csv
import io
import json
import math
import shutil
import subprocess
import time

CACHE_SECONDS = 15
MAX_OUTPUT = 1024 * 1024
SCALES = {"edge": 110, "hotspot": 120, "memory": 120, "gpu": 110}


def number(value):
    if isinstance(value, dict):
        if value.get("unit", "C") not in ("C", "°C", "celsius"):
            return None
        value = value.get("value")
    if isinstance(value, bool):
        return None
    try:
        parsed = float(str(value).removesuffix("°C").removesuffix(" C").strip())
        return parsed if math.isfinite(parsed) and -40 <= parsed <= 250 else None
    except (TypeError, ValueError):
        return None


def clean(value):
    return str(value or "").strip()[:160]


def rows(raw):
    parsed = json.loads(raw)
    if isinstance(parsed, dict):
        # AMD's single-device JSON can be an object instead of a list.
        parsed = parsed.get("gpu_data", parsed.get("gpus", [parsed]))
    if not isinstance(parsed, list) or not all(isinstance(row, dict) for row in parsed):
        raise ValueError("Unrecognized AMD JSON")
    return [{str(k).lower(): v for k, v in row.items()} for row in parsed]


def sensor(name, value, limit=None):
    threshold = number(limit)
    return {"name": name, "celsius": number(value), "scale_min": 0,
            "scale_max": SCALES[name], "slowdown_celsius": threshold if threshold and threshold > 0 else None}


def parse_amd(identity_raw, metrics_raw, limits_raw="[]"):
    identities = {str(row.get("gpu", row.get("gpu_id"))): row for row in rows(identity_raw)}
    metrics = {str(row.get("gpu", row.get("gpu_id"))): row for row in rows(metrics_raw)}
    limits = {str(row.get("gpu", row.get("gpu_id"))): row for row in rows(limits_raw)}
    if not identities:
        raise ValueError("No AMD GPUs reported")
    cards = []
    for index, row in identities.items():
        uuid = clean(row.get("uuid", row.get("gpu_uuid")))
        bdf = clean(row.get("bdf", row.get("gpu_bdf")))
        stable = uuid if uuid and uuid.lower() not in ("n/a", "unknown") else bdf
        if not stable or stable.lower() in ("n/a", "unknown"):
            raise ValueError("AMD did not report a stable GPU identity")
        temps = metrics.get(index, {}).get("temperature", {})
        bounds = limits.get(index, {}).get("limit", {})
        temps = {str(k).lower(): v for k, v in temps.items()} if isinstance(temps, dict) else {}
        bounds = {str(k).lower(): v for k, v in bounds.items()} if isinstance(bounds, dict) else {}
        cards.append({"id": "amd:" + stable, "vendor": "AMD", "index": index,
                      "name": clean(row.get("name")) or "AMD GPU " + index, "bus_id": bdf,
                      "sensors": [sensor(name, temps.get(key), bounds.get("slowdown_" + limit + "_temperature"))
                                  for name, key, limit in (("edge", "edge", "edge"),
                                      ("hotspot", "hotspot", "hotspot"), ("memory", "mem", "vram"))]})
    return cards


def parse_nvidia(raw):
    cards = []
    for row in csv.reader(io.StringIO(raw), skipinitialspace=True):
        if not row:
            continue
        if len(row) != 5 or not row[1].strip().startswith("GPU-"):
            raise ValueError("Unrecognized NVIDIA CSV")
        index, uuid, bdf, name, temperature = map(clean, row)
        cards.append({"id": "nvidia:" + uuid, "vendor": "NVIDIA", "index": index,
                      "name": name, "bus_id": bdf, "sensors": [sensor("gpu", temperature)]})
    if not cards:
        raise ValueError("No NVIDIA GPUs reported")
    return cards


def command(argv):
    # No shell, elevated privileges, setters, environment input, or caller arguments.
    result = subprocess.run(argv, capture_output=True, timeout=6, check=True)
    if len(result.stdout) > MAX_OUTPUT:
        raise ValueError("GPU output exceeded limit")
    return result.stdout.decode("utf-8")


class Sampler:
    def __init__(self, run=command, which=shutil.which, now=time.time, monotonic=time.monotonic):
        self.run, self.which, self.now, self.monotonic = run, which, now, monotonic
        self.lock = asyncio.Lock()
        self.cached = None
        self.expires = 0
        self.previous = {}

    def sample(self):
        cards, errors = [], []
        for vendor, binary in (("AMD", "amd-smi"), ("NVIDIA", "nvidia-smi")):
            executable = self.which(binary)
            if not executable:
                if vendor in self.previous:
                    errors.append(vendor + " telemetry command unavailable")
                    cards.extend(dict(row, stale=True) for row in self.previous[vendor])
                continue
            try:
                if vendor == "AMD":
                    identities = self.run([executable, "list", "--json"])
                    metrics = self.run([executable, "metric", "--temperature", "--json"])
                    try:
                        limits = self.run([executable, "static", "--limit", "--json"])
                    except (OSError, subprocess.SubprocessError, ValueError):
                        limits = "[]"  # Thermal limits unsupported on some devices.
                    fresh = parse_amd(identities, metrics, limits)
                else:
                    fresh = parse_nvidia(self.run([executable,
                        "--query-gpu=index,uuid,pci.bus_id,name,temperature.gpu", "--format=csv,noheader,nounits"]))
                stamp = self.now()
                fresh = [dict(row, sampled_at=stamp, stale=False) for row in fresh]
                self.previous[vendor] = fresh
                cards.extend(fresh)
            except (OSError, subprocess.SubprocessError, ValueError, TypeError):
                # Never echo CLI output: diagnostics may contain local paths or host data.
                errors.append(vendor + " telemetry unavailable (driver, permissions, or unsupported output)")
                cards.extend(dict(row, stale=True) for row in self.previous.get(vendor, []))
        if not cards and not errors:
            errors.append("Install amd-smi or nvidia-smi on the Hermes plugin host to read temperatures")
        cards.sort(key=lambda card: (card["vendor"], card["bus_id"], card["id"]))
        return {"version": 1, "object": "bellpull.gpu", "checked_at": self.now(),
                "refresh_seconds": CACHE_SECONDS, "cards": cards, "errors": errors,
                "scale_note": "Fixed display ranges in °C; color is not a hardware safety rating. Slowdown limits are shown only when reported by the driver."}

    async def read(self):
        async with self.lock:
            if self.cached is None or self.monotonic() >= self.expires:
                self.cached = await asyncio.to_thread(self.sample)
                self.expires = self.monotonic() + CACHE_SECONDS
            return self.cached
