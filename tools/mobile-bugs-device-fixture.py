#!/usr/bin/env python3
"""Run inside the local Hermes dev container with a disposable home and fake agent.

Reuses the native attachment regression fixture, including real run admission,
SQLite and plugin handlers. Port 18662 is loopback-only; expose it to the fixture
APK with adb reverse. No personal profiles or inference credentials are loaded.
"""
import asyncio
import logging
import signal
from plugins.bellpull.tests.test_native_attachments import NativeAttachmentsTest


async def main():
    fixture = NativeAttachmentsTest()
    fixture.port = 18662
    await fixture.asyncSetUp()
    done = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(name, done.set)
    print("READY http://127.0.0.1:18662 · synthetic attachment agent", flush=True)
    try:
        await done.wait()
    finally:
        print(f"Completed fixture executions: {len(fixture.executed)}", flush=True)
        await fixture.asyncTearDown()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
