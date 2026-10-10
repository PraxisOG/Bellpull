import asyncio
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("telemetry", Path(__file__).parents[1] / "telemetry.py")
telemetry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(telemetry)


def amd_fixture(order=(0, 1, 2, 3)):
    identities = [{"gpu": i, "uuid": f"fixture-gpu-{i}", "bdf": f"0000:0{i}:00.0"} for i in order]
    metrics = [{"gpu": i, "temperature": {"edge": {"value": 40 + i, "unit": "C"},
                "hotspot": 70 + i, "mem": "N/A"}} for i in reversed(order)]
    return json.dumps(identities), json.dumps(metrics)


class TelemetryTest(unittest.IsolatedAsyncioTestCase):
    def test_four_amd_cards_join_by_identity_not_array_order(self):
        cards = telemetry.parse_amd(*amd_fixture())
        self.assertEqual(4, len(cards))
        for i, card in enumerate(cards):
            self.assertEqual("amd:fixture-gpu-" + str(i), card["id"])
            self.assertEqual(40 + i, card["sensors"][0]["celsius"])
            self.assertIsNone(card["sensors"][2]["celsius"])
            self.assertEqual(120, card["sensors"][1]["scale_max"])

    def test_amd_single_object_old_numeric_and_reported_limit(self):
        cards = telemetry.parse_amd('{"gpu":0,"bdf":"0000:01:00.0"}',
            '{"gpu":0,"temperature":{"edge":43,"hotspot":"N/A","mem":0}}',
            '[{"gpu":0,"limit":{"slowdown_edge_temperature":{"value":95,"unit":"C"}}}]')
        self.assertEqual(95, cards[0]["sensors"][0]["slowdown_celsius"])
        self.assertIsNone(cards[0]["sensors"][1]["celsius"])
        self.assertEqual(0, cards[0]["sensors"][2]["celsius"])

    def test_nvidia_missing_sensor_and_quoted_name(self):
        cards = telemetry.parse_nvidia('0, GPU-fixture-0, 00000000:01:00.0, "Fixture, GPU", 48\n'
                                       '1, GPU-fixture-1, 00000000:02:00.0, Fixture, [N/A]\n')
        self.assertEqual(48, cards[0]["sensors"][0]["celsius"])
        self.assertIsNone(cards[1]["sensors"][0]["celsius"])
        self.assertEqual("Fixture, GPU", cards[0]["name"])

    def test_non_finite_wrong_units_invalid_identity(self):
        for value in ("N/A", "NaN", "inf", 400, True, {"value": 72, "unit": "F"}):
            self.assertIsNone(telemetry.number(value))
        with self.assertRaises(ValueError):
            telemetry.parse_amd('[{"gpu":0}]', '[]')

    async def test_cache_singleflight_and_no_background_poll(self):
        calls = []
        now = [100.0]
        def run(argv):
            calls.append(argv)
            return '0, GPU-fixture, 0000:01:00.0, Fixture, 50\n'
        sampler = telemetry.Sampler(run=run, which=lambda name: name if name == "nvidia-smi" else None,
                                    now=lambda: now[0], monotonic=lambda: now[0])
        values = await asyncio.gather(*(sampler.read() for _ in range(10)))
        self.assertEqual(1, len(calls))
        self.assertEqual(100, values[0]["cards"][0]["sampled_at"])
        now[0] = 120
        await asyncio.sleep(.01)
        self.assertEqual(1, len(calls))
        await sampler.read()
        self.assertEqual(2, len(calls))

    async def test_failure_retains_identified_stale_cards_and_missing_cli_has_no_fake_rows(self):
        now = [100.0]
        def run(argv):
            if now[0] > 100:
                raise subprocess.TimeoutExpired(argv, 6)
            return '0, GPU-fixture, 0000:01:00.0, Fixture, 50\n'
        sampler = telemetry.Sampler(run=run, which=lambda name: name if name == "nvidia-smi" else None,
                                    now=lambda: now[0], monotonic=lambda: now[0])
        await sampler.read()
        now[0] = 120
        value = await sampler.read()
        self.assertTrue(value["cards"][0]["stale"])
        self.assertEqual(100, value["cards"][0]["sampled_at"])
        self.assertTrue(value["errors"])
        absent = await telemetry.Sampler(which=lambda _: None).read()
        self.assertEqual([], absent["cards"])
        self.assertTrue(absent["errors"])


if __name__ == "__main__":
    unittest.main()
