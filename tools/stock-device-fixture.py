#!/usr/bin/env python3
"""Disposable loopback stock-Hermes fixture; inference and real profiles are blocked."""
import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile


async def serve(source, port, home):
    sys.path.insert(0, str(source))
    os.environ["HERMES_HOME"] = str(home)
    os.environ["BELLPULL_NTFY_URL"] = ""
    package = Path(__file__).resolve().parents[1] / "plugins/bellpull"
    shutil.copytree(package, home / "plugins/bellpull", ignore=shutil.ignore_patterns("__pycache__"))
    (home / "config.yaml").write_text("plugins:\n  enabled:\n    - bellpull\n")
    from gateway.config import PlatformConfig
    from gateway.platforms.api_server import APIServerAdapter
    from hermes_cli.plugins import get_plugin_manager
    from tools.registry import registry
    manager = get_plugin_manager()
    manager.discover_and_load()
    adapter = APIServerAdapter(PlatformConfig(enabled=True,
        extra={"key": "bellpull-stock-device-fixture", "host": "127.0.0.1", "port": port}))
    def no_inference(**kwargs):
        raise RuntimeError("Inference is disabled in this device fixture")
    adapter._create_agent = no_inference
    try:
        if not await adapter.connect():
            raise RuntimeError("Fixture adapter did not start")
        database = await adapter._ensure_session_db_async()
        database.create_session(session_id="phone_fixture", source="api_server", model="fixture")
        database.set_session_title("phone_fixture", "Stock file sharing fixture")
        result = json.loads(registry.dispatch("bellpull_share_files", {"action": "prepare"},
            scope=manager.scope_key, session_id="phone_fixture"))
        outbox = Path(result["outbox"])
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        for index in range(4):
            (outbox / f"verified-{index}.png").write_bytes(png)
        result = json.loads(registry.dispatch("bellpull_share_files", {"action": "publish"},
            scope=manager.scope_key, session_id="phone_fixture"))
        if len(result.get("artifacts", [])) != 4:
            raise RuntimeError(result)
        print(f"READY http://127.0.0.1:{port} PNG_SHA256={hashlib.sha256(png).hexdigest()}", flush=True)
        stopped = asyncio.Event()
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
        await stopped.wait()
    finally:
        await adapter.disconnect()
        manager.unload()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hermes-source", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18646)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="bellpull-stock-device-") as directory:
        asyncio.run(serve(args.hermes_source.resolve(), args.port, Path(directory)))
