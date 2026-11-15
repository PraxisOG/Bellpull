#!/usr/bin/env python3
"""Loopback, authenticated Moonshine Medium / Kokoro speech helper.

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
import logging
from pathlib import Path
import time
import wave

from aiohttp import web, WSMsgType
import numpy as np

MAX_AUDIO = 16000 * 2 * 60
MAX_FRAME = 64 * 1024


class Recognizer:
    streaming = True
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


class BatchRecognizer(Recognizer):
    """GPU one-shot inference, with the same serialized ownership as native STT."""
    streaming = False

    async def create(self):
        return bytearray()

    async def feed(self, state, data):
        state.extend(data)
        return ''

    async def finish(self, state):
        return await self.run(self.model.transcribe, state)

    async def release(self, state):
        if state is not None:
            # Queue after any cancelled native inference before wiping its input.
            await asyncio.shield(self.run(lambda: state.__setitem__(slice(None), bytes(len(state)))))


from tts_stream import StreamingSynthesis, stream_response


class Synthesizer(StreamingSynthesis):
    def __init__(self, folder):
        import hashlib
        import onnxruntime as ort
        from kokoro_onnx import Kokoro
        manifest = json.loads((Path(__file__).parent / 'kokoro-models.json').read_text())
        for item in manifest:
            path = folder / item['name']
            with path.open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != item['sha256']:
                    raise ValueError('Kokoro model checksum mismatch')
        options = ort.SessionOptions()
        options.intra_op_num_threads = 3
        options.inter_op_num_threads = 1
        session = ort.InferenceSession(str(folder / 'kokoro-v1.0.onnx'), options, providers=['CPUExecutionProvider'])
        self.model = Kokoro.from_session(session, str(folder / 'voices-v1.0.bin'))
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='bellpull-tts')
        self.active = False



def create_app(recognizer, key, model_name="medium", precision="fp32", synthesizer=None, backend='cpu'):
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
        return web.json_response({"ok": True, "stt": "moonshine-" + model_name + "-" + precision + '-' + backend,
            "stt_loaded": recognizer is not None, "stt_streaming": recognizer is not None and getattr(recognizer, 'streaming', True),
            "tts": "kokoro-82m-v1.0-fp32-" + backend,
            "tts_loaded": synthesizer is not None, "tts_streaming": synthesizer is not None,
            "tts_sample_rate": 24000, "tts_format": "pcm_s16le", "backend": backend})

    async def tts(request):
        if synthesizer is None:
            raise web.HTTPServiceUnavailable(text='Kokoro is not installed')
        if request.content_type != 'application/json':
            raise web.HTTPBadRequest(text='Expected JSON')
        raw = bytearray()
        async for chunk in request.content.iter_chunked(8192):
            raw.extend(chunk)
            if len(raw) > 8192:
                raise web.HTTPRequestEntityTooLarge(max_size=8192, actual_size=len(raw))
        try:
            body = json.loads(raw)
        except (ValueError, UnicodeError):
            raise web.HTTPBadRequest(text='Invalid JSON')
        if not isinstance(body, dict) or not isinstance(body.get('text'), str) or not 1 <= len(body['text']) <= 4000 or not body['text'].strip():
            raise web.HTTPBadRequest(text='text must contain 1..4000 characters')
        if body.get('voice', 'af_heart') != 'af_heart' or not isinstance(body.get('stream', False), bool):
            raise web.HTTPBadRequest(text='Expected af_heart voice and a boolean stream option')
        began = time.perf_counter()
        try:
            if body.get('stream', False):
                return await stream_response(request, synthesizer, body['text'], 'af_heart')
            audio, seconds = await synthesizer.synthesize(body['text'], 'af_heart')
        except web.HTTPException:
            raise
        except Exception:
            raise web.HTTPServiceUnavailable(text='Speech synthesis failed') from None
        return web.Response(body=audio, content_type='audio/wav', headers={
            'X-Audio-Seconds': str(round(seconds, 3)), 'X-Took-Ms': str(round((time.perf_counter() - began) * 1000))})

    def acquire():
        if recognizer is None:
            raise web.HTTPServiceUnavailable(text='Moonshine is not loaded')
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
        if recognizer is not None and not getattr(recognizer, 'streaming', True):
            raise web.HTTPServiceUnavailable(text='This GPU helper uses one-shot dictation at /stt')
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
    app.router.add_post("/tts", tts)
    app.router.add_get("/stt/stream", streaming)
    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8645)
    parser.add_argument('--backend', choices=['cpu', 'rocm'], default='cpu')
    parser.add_argument("--model", choices=["small", "medium"], default="medium")
    parser.add_argument("--precision", choices=["fp32", "int8"], default="fp32")
    parser.add_argument("--model-dir", type=Path,
                        default=Path.home() / ".local/share/bellpull-speech/fp32-medium")
    parser.add_argument('--kokoro-dir', type=Path,
                        default=Path.home() / '.local/share/bellpull-speech/kokoro')
    parser.add_argument('--stt-only', action='store_true', help='Keep recognition available without Kokoro')
    args = parser.parse_args()
    key = os.environ.get("BELLPULL_SPEECH_KEY", "")
    if not key:
        raise SystemExit("Set BELLPULL_SPEECH_KEY before starting the helper")
    model = recognizer = synthesizer = None
    if args.precision == 'fp32' and args.model != 'medium':
        parser.error('The pinned FP32 export is medium only')
    if args.backend == 'rocm':
        if args.model != 'medium' or args.precision != 'fp32':
            parser.error('ROCm requires medium FP32')
        from rocm_models import Moonshine, Kokoro
        try:
            model = Moonshine()
            recognizer = BatchRecognizer(model)
        except Exception:
            logging.exception('ROCm Moonshine could not load; recognition is unavailable.')
        if not args.stt_only:
            try:
                synthesizer = Synthesizer.__new__(Synthesizer)
                synthesizer.model = Kokoro()
                synthesizer.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='bellpull-tts')
                synthesizer.active = False
                # Compile kernels before reporting readiness; synthetic audio stays in memory.
                samples, rate = synthesizer.model.create('Speech is ready.')
            except Exception:
                if synthesizer and hasattr(synthesizer, 'executor'): synthesizer.executor.shutdown(wait=True)
                synthesizer = None
                logging.exception('ROCm Kokoro could not load; read-aloud is unavailable.')
        if recognizer and synthesizer:
            try:
                samples16 = np.interp(np.arange(0, len(samples), rate / 16000), np.arange(len(samples)), samples)
                model.transcribe((np.clip(samples16, -1, 1) * 32767).astype('<i2').tobytes())
            except Exception:
                recognizer.executor.shutdown(wait=True)
                recognizer = None
                logging.exception('ROCm Moonshine warmup failed; recognition is unavailable.')
        try:
            web.run_app(create_app(recognizer, key, args.model, args.precision, synthesizer, 'rocm'),
                        host='127.0.0.1', port=args.port, access_log=None)
        finally:
            if recognizer: recognizer.executor.shutdown(wait=True)
            if synthesizer: synthesizer.executor.shutdown(wait=True)
        return
    try:
        from moonshine_voice import Transcriber
        from moonshine_voice.download import get_model_for_language
        from moonshine_voice.moonshine_api import ModelArch
        arch = ModelArch.MEDIUM_STREAMING if args.model == 'medium' else ModelArch.SMALL_STREAMING
        if args.precision == 'fp32':
            from export_fp32 import verify_export
            verify_export(args.model_dir)
            path = str(args.model_dir)
        else:
            path, arch = get_model_for_language('en', arch, on_progress=lambda *_: None)
        model = Transcriber(path, arch, options={'ort_providers': 'CPU'})
        recognizer = Recognizer(model)
    except Exception:
        logging.error('Moonshine could not load. Check its installation; recognition remains unavailable.')
    if not args.stt_only:
        try:
            synthesizer = Synthesizer(args.kokoro_dir)
        except Exception:
            logging.error('Kokoro could not load. Check its installation; read-aloud remains unavailable.')
    try:
        web.run_app(create_app(recognizer, key, args.model, args.precision, synthesizer),
                    host="127.0.0.1", port=args.port, access_log=None)
    finally:
        if recognizer:
            recognizer.executor.shutdown(wait=True)
        if synthesizer:
            synthesizer.executor.shutdown(wait=True)
        if model:
            model.close()


if __name__ == "__main__":
    main()
