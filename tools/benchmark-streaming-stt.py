#!/usr/bin/env python3
"""Replay pinned public audio at microphone speed; never reads a real microphone."""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import time
import wave

import aiohttp


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:18645/stt/stream")
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    key = os.environ["BELLPULL_BENCH_KEY"]
    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    rows = []
    async with aiohttp.ClientSession(headers={"Authorization": "Bearer " + key}) as session:
        for case in manifest["fixtures"]:
            path = args.fixtures / case["file"]
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case["sha256"]
            with wave.open(str(path)) as wav:
                assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (16000, 1, 2)
                pcm = wav.readframes(wav.getnframes())
            async with session.ws_connect(args.url, max_msg_size=65536) as ws:
                ready = await ws.receive_json(timeout=10)
                assert ready["type"] == "ready"
                start = time.perf_counter()
                first_partial = None
                partials = 0
                final = None
                finished_upload = None

                async def read():
                    nonlocal first_partial, partials, final
                    while True:
                        value = await ws.receive_json(timeout=40)
                        if value["type"] == "partial":
                            partials += 1
                            if value["text"] and first_partial is None:
                                first_partial = (time.perf_counter() - start) * 1000
                        elif value["type"] == "final":
                            final = value
                            return time.perf_counter()
                        else:
                            raise RuntimeError(value)

                receiver = asyncio.create_task(read())
                await ws.send_json({"type": "start", "sample_rate": 16000, "channels": 1, "format": "pcm_s16le"})
                for at in range(0, len(pcm), 3200):
                    # Each 100ms chunk becomes available after capture, not before.
                    due = start + min(at + 3200, len(pcm)) / 32000
                    await asyncio.sleep(max(0, due - time.perf_counter()))
                    await ws.send_bytes(pcm[at:at + 3200])
                finished_upload = time.perf_counter()
                await ws.send_json({"type": "finish"})
                final_at = await receiver
                row = {"fixture": case["id"], "sha256": case["sha256"], "audio_seconds": len(pcm) / 32000,
                       "first_partial_ms": first_partial, "partial_count": partials,
                       "final_after_last_audio_ms": (final_at - finished_upload) * 1000,
                       "final_after_annotated_last_word_ms": (final_at - start) * 1000 - case["last_word_end_ms"],
                       "text": final["text"]}
                rows.append(row)
                print(json.dumps(row), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"fixtures": rows,
        "conditions": "100ms chunks replayed at microphone speed. Includes appended fixture silence; excludes Bellpull's separate turn-end gate and Hermes/TTS latency.",
        "transport_url": args.url}, indent=2) + "\n")


if __name__ == "__main__":
    asyncio.run(main())
