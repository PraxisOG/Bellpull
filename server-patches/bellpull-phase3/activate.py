"""Hash-guarded upgrade/rollback of the API extension; refuses to interrupt agent work."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import urllib.request

root = Path(__file__).resolve().parent
mode = sys.argv[1] if len(sys.argv) > 1 else "check"
assert mode in ("check", "apply", "rollback")
container = "hermes-gateway"
changed = ["gateway/platforms/api_server.py", "gateway/platforms/api_server_runs.py"]
added = ["gateway/platforms/api_server_bellpull_artifacts.py", "gateway/platforms/api_server_bellpull_phase3.py"]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
for name in changed:
    current = subprocess.check_output(["docker", "exec", container, "sha256sum", "/opt/hermes/" + name], text=True).split()[0]
    allowed = [digest(root / folder / name) for folder in ("original", "patched")] if mode == "check" else [digest(root / ("patched" if mode == "rollback" else "original") / name)]
    assert current in allowed, name + " changed; no files copied"
for name in added:
    exists = subprocess.run(["docker", "exec", container, "test", "-f", "/opt/hermes/" + name]).returncode == 0
    if exists:
        current = subprocess.check_output(["docker", "exec", container, "sha256sum", "/opt/hermes/" + name], text=True).split()[0]
        assert current == digest(root / "patched" / name), name + " changed; no files copied"
    elif mode == "rollback":
        raise AssertionError(name + " missing; no files copied")
if mode == "check":
    print("All module hashes recognized.")
    raise SystemExit
key = Path("/home/praxisog/.config/hermes-android/dev-api-key").read_text().strip()
request = urllib.request.Request("http://127.0.0.1:8642/health/detailed", headers={"Authorization": "Bearer " + key})
with urllib.request.urlopen(request, timeout=10) as response:
    health = json.load(response)
assert health.get("active_agents") == 0 and not health.get("gateway_busy"), "Gateway has active work"
queues = health["readiness"]["checks"]["background_queues"]
assert all(queues.get(field) == 0 for field in ("active_api_runs", "process_completions", "active_delegations")), "Background work exists"
for name in changed + (added if mode == "apply" else []):
    subprocess.run(["docker", "cp", str(root / ("patched" if mode == "apply" else "original") / name), container + ":/opt/hermes/" + name], check=True)
if mode == "rollback":
    for name in added:
        subprocess.run(["docker", "exec", container, "rm", "/opt/hermes/" + name], check=True)
subprocess.run(["docker", "restart", container], check=True)
print(mode + " completed; verify authenticated capabilities, model catalog and artifacts.")
