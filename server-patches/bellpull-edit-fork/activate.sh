#!/usr/bin/env bash
set -euo pipefail
patch_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mode="${1:-check}"
case "$mode" in check|apply|rollback) ;; *) echo 'Usage: activate.sh [check|apply|rollback]' >&2; exit 2 ;; esac
container=hermes-gateway
module=/opt/hermes/gateway/platforms/api_server.py
expected_image=sha256:63bfb6d732f49a55d453e801057273785cc61e0f6ee43db3fa2f2a79846301b7
actual_image="$(docker inspect "$container" --format '{{.Image}}')"
if [[ "$actual_image" != "$expected_image" ]]; then
  echo 'Gateway image changed; rebase and retest this patch before activation.' >&2
  exit 1
fi
original_hash="$(sha256sum "$patch_dir/original/gateway/platforms/api_server.py" | cut -d ' ' -f 1)"
patched_hash="$(sha256sum "$patch_dir/patched/gateway/platforms/api_server.py" | cut -d ' ' -f 1)"
current_hash="$(docker exec "$container" sha256sum "$module" | cut -d ' ' -f 1)"
if [[ "$mode" == check ]]; then
  if [[ "$current_hash" == "$original_hash" ]]; then echo 'Original module verified; patch is ready to apply.'
  elif [[ "$current_hash" == "$patched_hash" ]]; then echo 'Patched module is already installed.'
  else echo 'Unrecognized API module; stop and review its changes.' >&2; exit 1; fi
  exit 0
fi
if [[ "$mode" == apply ]]; then
  expected_hash="$original_hash"
  replacement="$patch_dir/patched/gateway/platforms/api_server.py"
  if [[ -f "$patch_dir/revisions/initial-api_server.py" ]]; then
    initial_hash="$(sha256sum "$patch_dir/revisions/initial-api_server.py" | cut -d ' ' -f 1)"
    if [[ "$current_hash" == "$initial_hash" ]]; then expected_hash="$initial_hash"; fi
  fi
else
  expected_hash="$patched_hash"
  replacement="$patch_dir/original/gateway/platforms/api_server.py"
fi
if [[ "$current_hash" != "$expected_hash" ]]; then
  echo 'Live API module does not match the expected version; no changes made.' >&2
  exit 1
fi
docker cp "$replacement" "$container:$module"
docker restart "$container"
echo "$mode complete. Verify capabilities and a disposable branch before declaring activation successful."
