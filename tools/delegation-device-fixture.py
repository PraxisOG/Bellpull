#!/usr/bin/env python3
"""Disposable native HTTP/SQLite background-delivery fixture; no model or personal data.

Run in a clean Hermes image with this plugin and tools mounted. Port 18663 is
loopback-bound and intended for adb reverse into org.praxisog.hermes.fixture.
"""
import asyncio
from functools import wraps
import inspect
import json
import signal
import threading
import uuid
from plugins.bellpull.tests.test_native_attachments import NativeAttachmentsTest


async def main():
    fixture = NativeAttachmentsTest()
    fixture.port = 18663
    await fixture.asyncSetUp()
    signature = inspect.signature(fixture.adapter._create_agent)
    timers = []
    admitted = []

    @wraps(fixture.adapter._create_agent)
    def create(*args, **kwargs):
        arguments = signature.bind_partial(*args, **kwargs).arguments
        class Agent:
            session_id = arguments.get("session_id")
            model = arguments.get("requested_model")
            provider = arguments.get("requested_provider")
            def run_conversation(self, **values):
                admitted.append(self.session_id)
                db = fixture.adapter._ensure_session_db()
                unit = "deleg_fixture_" + uuid.uuid4().hex
                db.append_message(self.session_id, "user", values.get("persist_user_message", values["user_message"]))
                db.append_message(self.session_id, "tool", json.dumps({
                    "status": "dispatched", "mode": "background", "delegation_id": unit}))
                db.append_message(self.session_id, "assistant", "Background work started.")
                def complete():
                    metadata = {"delegation_id": unit, "task_count": 1, "completed_count": 1}
                    text = "The synthetic background task finished successfully."
                    first = db.append_delegation_delivery(self.session_id, text, metadata)
                    assert first == db.append_delegation_delivery(self.session_id, text, metadata)
                    print("DELIVERED one deduplicated completion", flush=True)
                timer = threading.Timer(20, complete)
                timer.start(); timers.append(timer)
                return {"final_response": "Background work started.", "completed": True}
        return Agent()

    fixture.adapter._create_agent = create
    done = asyncio.Event()
    for name in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(name, done.set)
    print("READY synthetic delegation fixture on 18663", flush=True)
    try:
        await done.wait()
    finally:
        for timer in timers:
            timer.cancel(); timer.join()
        print(f"ADMITTED {len(admitted)} runs (one per explicit fixture submission)", flush=True)
        await fixture.asyncTearDown()


if __name__ == "__main__":
    asyncio.run(main())
