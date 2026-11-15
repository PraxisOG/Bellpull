#!/usr/bin/env python3
"""Run the AMD helper with device access confined to its non-root container."""
import argparse
import json
import os
from pathlib import Path
import subprocess

IMAGE = 'python@sha256:78387bc3881b8273120a12ebe6c1ab22b018ccc2c9adf565ae1ac9b536e184ea'


def container_command(source, runtime, render, offline=True, groups=None):
    if not str(render).startswith('/dev/dri/renderD') or not Path(render).exists():
        raise ValueError('The configured AMD render device is unavailable')
    hf = Path.home() / '.cache/huggingface'
    hf.mkdir(parents=True, exist_ok=True)
    (runtime / 'kernels').mkdir(parents=True, exist_ok=True)
    # systemd user service namespaces may display unmapped host GIDs as 65534.
    # Install records the real device groups outside that namespace.
    groups = groups if groups is not None else [os.stat('/dev/kfd').st_gid, os.stat(render).st_gid]
    if len(groups) != 2 or not all(isinstance(group, int) and 0 <= group < 65534 for group in groups):
        raise ValueError('Reinstall ROCm speech from the desktop session to record its GPU device groups')
    command = ['docker', 'run', '--rm', '--init', '--network', 'host',
        '--user', f'{os.getuid()}:{os.getgid()}', '--group-add', str(groups[0]),
        '--group-add', str(groups[1]), '--device', '/dev/kfd', '--device', str(render),
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=256m',
        '--mount', f'type=bind,src={source},dst=/app,readonly',
        '--mount', f'type=bind,src={runtime},dst=/runtime',
        '--mount', f'type=bind,src={hf},dst=/hf' + (',readonly' if offline else ''),
        '--env', 'HF_HOME=/hf', '--env', 'HF_HUB_DISABLE_TELEMETRY=1',
        '--env', 'HF_HUB_OFFLINE=' + ('1' if offline else '0'),
        '--env', 'TORCH_BLAS_PREFER_HIPBLASLT=0', '--env', 'TORCH_KERNEL_CACHE_PATH=/runtime/kernels',
        '--env', 'XDG_CACHE_HOME=/runtime/cache-system', '--env', 'PYTHONUNBUFFERED=1',
        '--env', 'PYTHONDONTWRITEBYTECODE=1']
    return command


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8645)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    runtime = Path.home() / '.local/share/bellpull-speech-rocm'
    device = json.loads((runtime / 'device.json').read_text())
    command = container_command(source, runtime, device['render'], groups=device['groups'])
    command += ['--name', 'bellpull-speech-rocm-' + str(args.port), '--env', 'BELLPULL_SPEECH_KEY', IMAGE, '/runtime/venv/bin/python',
                '/app/server.py', '--backend', 'rocm', '--port', str(args.port)]
    os.execvp(command[0], command)


if __name__ == '__main__':
    main()
