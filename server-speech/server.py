#!/usr/bin/env python3
"""Loopback, authenticated CPU STT helper. Independent stream state, shared warm model.

Native calls use a single worker: chunks from different streams can interleave,
and cancellation/cleanup cannot free a native stream while inference is using it.
"""
import argparse
import asyncio
from concurrent.futures import ThreadPoolExecutor
import hmac
import io
import json
import os
from pathlib import Path
import time
import wave

from aiohttp import web, WSMsgType
import numpy as np

MAX_AUDIO = 16000 * 2 * 60
MAX_FRAME = 64 * 1024


class Recognizer:
    def __init__(self, model):
        self.model = model
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="bellpull-stt")
        self.active = 0

    async def run(self, fn, *args):
        return await asyncio.get_running_loop().run_in_executor(self.executor, fn, *args)

    async def create(self):
        def create():
            stream = self.model.create_stream(update_interval=0.5)
            stream.start()
            lines = {}
            def changed(event):
                line = getattr(event, "line", None)
                if line is not None:
                    lines[line.line_id] = line.text
            stream.add_listener(changed)
            return stream, lines
        pending = asyncio.create_task(self.run(create))
        try:
            return await asyncio.shield(pending)
        except asyncio.CancelledError:
            # Native creation cannot be interrupted. Reclaim its result even
            # when the requester disappears before ownership reaches a handler.
            state = await asyncio.shield(pending)
            await self.release(state)
            raise

    async def feed(self, state, data):
        def feed():
            state[0].add_audio((np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768).tolist(), 16000)
            return " ".join(state[1].values()).strip()
        return await self.run(feed)

    async def finish(self, state):
        def finish():
            transcript = state[0].stop()
            if transcript is None:
                raise RuntimeError("Recognition failed to finalize")
            return " ".join(line.text for line in transcript.lines).strip()
        return await self.run(finish)

    async def release(self, state):
        if state is not None:
            await asyncio.shield(self.run(state[0].close))


def create_app(recognizer, key, model_name="medium", precision="fp32"):
    if not key:
        raise ValueError("BELLPULL_SPEECH_KEY is required")

    @web.middleware
    async def authenticate(request, handler):
        if not hmac.compare_digest(request.headers.get("Authorization", ""), "Bearer " + key):
            return web.json_response({"error": {"message": "Unauthorized"}}, status=401)
        return await handler(request)

    app = web.Application(middlewares=[authenticate], client_max_size=MAX_AUDIO + 65536)
    sockets = set()

    async def health(request):
        return web.json_response({"ok": True, "stt": "moonshine-" + model_name + "-" + precision + "-cpu",
            "stt_loaded": True, "stt_streaming": True, "tts": None, "tts_loaded": False})

    def acquire():
        if recognizer.active >= 2:
            raise web.HTTPTooManyRequests(text="Speech server is busy", headers={"Retry-After": "2"})
        recognizer.active += 1

    async def batch(request):
        acquire()
        state = None
        began = time.perf_counter()
        try:
            if request.content_type != "multipart/form-data":
                raise web.HTTPBadRequest(text="Expected multipart audio")
            reader = await request.multipart()
            part = await reader.next()
            if part is None or part.name != "audio":
                raise web.HTTPBadRequest(text="Expected audio field")
            data = bytearray()
            while chunk := await part.read_chunk():
                data.extend(chunk)
                if len(data) > MAX_AUDIO + 4096:
                    raise web.HTTPRequestEntityTooLarge(max_size=MAX_AUDIO + 4096, actual_size=len(data))
            try:
                with wave.open(io.BytesIO(data)) as wav:
                    if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getcomptype()) != (16000, 1, 2, "NONE"):
                        raise ValueError("Expected 16 kHz mono PCM16 WAV")
                    pcm = wav.readframes(wav.getnframes())
                if len(pcm) > MAX_AUDIO:
                    raise ValueError("Recording exceeds 60 seconds")
            except (wave.Error, EOFError, ValueError) as exc:
                raise web.HTTPBadRequest(text=str(exc)) from exc
            state = await recognizer.create()
            # Same decoder path as live streaming; avoid one monolithic native call
            # monopolizing the worker while another connection needs partials.
            for at in range(0, len(pcm), 3200):
                await recognizer.feed(state, pcm[at:at + 3200])
            text = await recognizer.finish(state)
            return web.json_response({"text": text, "language": "en", "seconds": len(pcm) / 32000,
                                     "took_ms": round((time.perf_counter() - began) * 1000)})
        finally:
            try:
                await recognizer.release(state)
            finally:
                recognizer.active -= 1

    async def streaming(request):
        acquire()
        state = None
        ws = web.WebSocketResponse(max_msg_size=MAX_FRAME, heartbeat=10)
        began = time.perf_counter()
        count = 0
        started = False
        previous = ""
        try:
            await ws.prepare(request)
            sockets.add(ws)
            state = await recognizer.create()
            await ws.send_json({"type": "ready", "sample_rate": 16000})
            async with asyncio.timeout(90):
                while True:
                    message = await ws.receive(timeout=30)
                    if message.type == WSMsgType.BINARY:
                        data = message.data
                        count += len(data)
                        if not started or len(data) % 2 or not data or count > MAX_AUDIO:
                            raise ValueError("Invalid or excessive PCM audio")
                        text = await recognizer.feed(state, data)
                        if text != previous:
                            previous = text
                            await ws.send_json({"type": "partial", "text": text})
                    elif message.type == WSMsgType.TEXT:
                        if len(message.data) > 1024:
                            raise ValueError("Control frame too large")
                        value = json.loads(message.data)
                        if not isinstance(value, dict):
                            raise ValueError("Invalid control frame")
                        if value.get("type") == "start" and not started:
                            if (value.get("sample_rate"), value.get("channels"), value.get("format")) != (16000, 1, "pcm_s16le"):
                                raise ValueError("Expected 16 kHz mono PCM16")
                            started = True
                        elif value.get("type") == "finish" and started:
                            text = await recognizer.finish(state)
                            await ws.send_json({"type": "final", "text": text, "seconds": count / 32000,
                                               "took_ms": round((time.perf_counter() - began) * 1000)})
                            break
                        else:
                            raise ValueError("Unexpected control frame")
                    elif message.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                        break
        except (ValueError, TimeoutError, RuntimeError):
            if not ws.closed:
                await ws.send_json({"type": "error", "message": "Speech stream failed or exceeded its limits. Retry the recording."})
        finally:
            try:
                await recognizer.release(state)
            finally:
                recognizer.active -= 1
                sockets.discard(ws)
                await ws.close()
        return ws

    async def shutdown(app):
        await asyncio.gather(*(ws.close(code=1001, message=b"Server stopping") for ws in list(sockets)))

    app.on_shutdown.append(shutdown)
    app.router.add_get("/health", health)
    app.router.add_post("/stt", batch)
    app.router.add_get("/stt/stream", streaming)
    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8645)
    parser.add_argument("--model", choices=["small", "medium"], default="medium")
    parser.add_argument("--precision", choices=["fp32", "int8"], default="fp32")
    parser.add_argument("--model-dir", type=Path,
                        default=Path.home() / ".local/share/bellpull-speech/fp32-medium")
    args = parser.parse_args()
    key = os.environ.get("BELLPULL_SPEECH_KEY", "")
    if not key:
        raise SystemExit("Set BELLPULL_SPEECH_KEY before starting the helper")
    from moonshine_voice import Transcriber
    from moonshine_voice.download import get_model_for_language
    from moonshine_voice.moonshine_api import ModelArch
    arch = ModelArch.MEDIUM_STREAMING if args.model == "medium" else ModelArch.SMALL_STREAMING
    if args.precision == "fp32":
        if args.model != "medium":
            parser.error("The pinned FP32 export is medium only")
        from export_fp32 import verify_export
        verify_export(args.model_dir)
        path = str(args.model_dir)
    else:
        path, arch = get_model_for_language("en", arch, on_progress=lambda *_: None)
    model = Transcriber(path, arch, options={"ort_providers": "CPU"})
    recognizer = Recognizer(model)
    try:
        web.run_app(create_app(recognizer, key, args.model, args.precision),
                    host="127.0.0.1", port=args.port, access_log=None)
    finally:
        recognizer.executor.shutdown(wait=True)
        model.close()


if __name__ == "__main__":
    main()
