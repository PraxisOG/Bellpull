# Bellpull GPU telemetry for Hermes Agent

This Hermes plugin reads **local** `amd-smi` or `nvidia-smi` temperatures. Bellpull
connects through its saved Hermes URL and API key. There is no SSH connection,
second port, model invocation, or background polling task.

Copy this directory to `$HERMES_HOME/plugins/bellpull_gpu` on the **Hermes host**,
enable `bellpull_gpu` with Hermes' plugin manager, and restart the gateway when
idle. This development host maps `$HERMES_HOME` to `~/hermes-data`. The CLI and
GPU device access must be available inside the gateway's environment/container.
The plugin does not install drivers, change container privileges, or expose a GPU
on a different machine. Install it beside the GPUs that should be measured.

The API server must support `register_platform_handler("api_server", factory)`.
`GET /api/bellpull/plugins/gpu` (also `/p/{profile}/api/bellpull/plugins/gpu` when
Hermes enables profiles) uses the native bearer check and fails closed without a
configured key. An absent plugin returns 404; installed but unavailable hardware
returns an empty card list and a readable error. Responses prohibit HTTP caching.

On-demand sampling has a 15-second shared cache and serialized refresh. Each CLI
call has a six-second timeout. NVIDIA uses UUIDs; AMD uses UUIDs or PCI bus IDs.
Four detected cards produce four stable rows; no synthetic cards are added.
Sensor failures are `null`, not zero. A failed command retains the last known
card rows with their original timestamps and `stale: true`.

The fixed display ranges are 0–110°C for AMD edge/NVIDIA GPU and 0–120°C for AMD
hotspot/memory. These are display ranges, **not device safety thresholds**. When
supported, `amd-smi static --limit --json` supplies separate driver slowdown
thresholds; otherwise no threshold is invented. NVIDIA's `temperature.gpu` is
labelled GPU, not mislabelled as hotspot. Original Celsius readings are preserved
even if a marker reaches the display boundary.

The commands follow [AMD's CLI documentation](https://rocm.docs.amd.com/projects/amdsmi/en/latest/how-to/amdsmi-cli-tool.html)
and [NVIDIA's SMI query documentation](https://docs.nvidia.com/deploy/nvidia-smi/index.html).
AMD numeric and `{value, unit}` temperature records are supported. CLI JSON is
version dependent; unsupported formats report unavailable instead of guessing.

Run parser/cache tests from the repository root:

```sh
python3 -m unittest discover -s plugins/bellpull_gpu/tests -v
```

Tests use explicitly synthetic four-card AMD and NVIDIA samples. Real multi-GPU
hardware validation remains separate; it cannot be claimed from those fixtures.
