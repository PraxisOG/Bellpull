import unittest
from speech_thermal_monitor import thermal_snapshot


class ThermalMonitorTest(unittest.TestCase):
    def test_ignores_stale_cache_and_preserves_sensor_identity(self):
        result = thermal_snapshot("""Thermal Status: 0
Cached temperatures:
 Temperature{mValue=90.0, mType=0, mName=BIG, mStatus=0}
Current temperatures from HAL:
 Temperature{mValue=32.0, mType=0, mName=BIG, mStatus=0}
 Temperature{mValue=29.5, mType=3, mName=VIRTUAL-SKIN, mStatus=0}
 Temperature{mValue=-3.4028235E38, mType=9, mName=TPU, mStatus=0}
 Temperature{mValue=1103.0, mType=-1, mName=inner_brightness, mStatus=0}
""")
        self.assertEqual(0, result["thermal_status"])
        sensors = {s["label"]: s for s in result["sensors"]}
        self.assertEqual(32, sensors["BIG"]["value"])
        self.assertEqual("VIRTUAL-SKIN", sensors["VIRTUAL-SKIN"]["label"])
        self.assertIsNone(sensors["TPU"]["value"])
        self.assertNotEqual("C", sensors["inner_brightness"]["unit"])

    def test_missing_live_section_does_not_substitute_cached_readings(self):
        self.assertEqual([], thermal_snapshot("Thermal Status: 3\nCached temperatures:\nTemperature{mValue=90, mType=0, mName=BIG, mStatus=3}")["sensors"])
        self.assertIsNone(thermal_snapshot("")["thermal_status"])


if __name__ == "__main__":
    unittest.main()
