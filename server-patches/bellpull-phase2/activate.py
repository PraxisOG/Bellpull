"""Hash-guarded activation/rollback; refuses to interrupt live agent work."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import urllib.request

root = Path(__file__).resolve().parent
mode = sys.argv[1] if len(sys.argv) > 1 else 'check'
assert mode in ('check', 'apply', 'rollback')
container = 'hermes-gateway'
files = ['gateway/platforms/api_server.py', 'gateway/platforms/api_server_runs.py', 'tools/approval_context.py']
added = 'gateway/platforms/api_server_bellpull.py'
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
for name in files:
    current = subprocess.check_output(['docker', 'exec', container, 'sha256sum', '/opt/hermes/' + name], text=True).split()[0]
    expected = digest(root / ('patched' if mode == 'rollback' else 'original') / name)
    if mode == 'check':
        assert current in (digest(root/'original'/name), digest(root/'patched'/name)), name + ' changed'
    else:
        assert current == expected, name + ' changed; no files copied'
added_exists = subprocess.run(['docker', 'exec', container, 'test', '-f', '/opt/hermes/' + added]).returncode == 0
if added_exists:
    current = subprocess.check_output(['docker', 'exec', container, 'sha256sum', '/opt/hermes/' + added], text=True).split()[0]
    assert current == digest(root/'patched'/added), added + ' changed; no files copied'
elif mode == 'rollback':
    raise AssertionError(added + ' missing; no files copied')
if mode == 'check':
    print('All existing module hashes recognized.')
    raise SystemExit
key = Path('/home/praxisog/.config/hermes-android/dev-api-key').read_text().strip()
req = urllib.request.Request('http://127.0.0.1:8642/health/detailed', headers={'Authorization':'Bearer '+key})
with urllib.request.urlopen(req, timeout=10) as response:
    health = json.load(response)
assert health.get('active_agents') == 0 and not health.get('gateway_busy'), 'Gateway has active work'
queues = health['readiness']['checks']['background_queues']
assert all(queues.get(field) == 0 for field in ('active_api_runs', 'process_completions', 'active_delegations')), 'Background work exists'
for name in files + ([added] if mode == 'apply' else []):
    subprocess.run(['docker', 'cp', str(root/('patched' if mode == 'apply' else 'original')/name), container+':/opt/hermes/'+name], check=True)
if mode == 'rollback':
    subprocess.run(['docker', 'exec', container, 'rm', '/opt/hermes/'+added], check=True)
subprocess.run(['docker', 'restart', container], check=True)
print(mode + ' completed; verify authenticated capabilities and status.')
