#!/usr/bin/env python3
"""Run inside a clean stock Hermes image with the matching plugin. Binds loopback only.

Reverse port 18657 to the fixture emulator. Pass the printed session to
VoiceDeviceTest with bargeSession, firstWord and nextWord. Play an utterance
with each expected word into the virtual mic at the corresponding status.
The first turn returns without an assistant row when the native Stop arrives.
"""
import asyncio
from functools import wraps
import inspect
import signal
import threading

from plugins.bellpull.tests.test_native_attachments import NativeAttachmentsTest


async def main():
    fixture = NativeAttachmentsTest()
    fixture.port = 18657
    await fixture.asyncSetUp()
    db = fixture.adapter._ensure_session_db()
    db.append_message(fixture.session, "assistant", "Ready for the synthetic voice test.")
    count = 0
    stops = 0
    stopped = threading.Event()
    finished = asyncio.Event()
    signature = inspect.signature(fixture.adapter._create_agent)

    @wraps(fixture.adapter._create_agent)
    def create(*args, **kwargs):
        arguments = signature.bind_partial(*args, **kwargs).arguments

        class Agent:
            session_id = arguments.get("session_id")
            model = arguments.get("requested_model")
            provider = arguments.get("requested_provider")

            def hard_interrupt(self, message=None):
                nonlocal stops
                stops += 1
                print(f"STOP {stops}", flush=True)
                stopped.set()

            def run_conversation(self, **values):
                nonlocal count
                count += 1
                index = count
                prompt = values.get("persist_user_message", values["user_message"])
                print(f"ADMISSION {index}", flush=True)
                db.append_message(self.session_id, "user", prompt)
                if index == 1:
                    if not stopped.wait(120):
                        raise RuntimeError("No stop reached the first run")
                    return {"completed": True, "final_response": ""}
                if index != 2 or stops != 1:
                    raise AssertionError(f"Unexpected admissions or stops: {index}, {stops}")
                db.append_message(self.session_id, "assistant", "Replacement received on Hermes.")
                return {"completed": True, "final_response": "Replacement received on Hermes."}
        return Agent()

    fixture.adapter._create_agent = create
    for sig in (signal.SIGINT, signal.SIGTERM):
        asyncio.get_running_loop().add_signal_handler(sig, finished.set)
    print(f"READY {fixture.session}", flush=True)
    try:
        await finished.wait()
    finally:
        stopped.set()
        await fixture.asyncTearDown()
        print(f"TOTAL admissions={count} stops={stops}", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
