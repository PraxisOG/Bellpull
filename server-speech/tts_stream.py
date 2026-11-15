"""Bounded sentence batches shared by CPU and ROCm Kokoro backends."""
import asyncio
import io
import re
import wave
import numpy as np
from aiohttp import web

SAMPLE_RATE = 24000
MAX_PCM = 32 * 1024 * 1024


def sentence_batches(text, limit=220):
    for sentence in re.split(r"(?<=[.!?])\s+", text.strip()):
        while len(sentence) > limit:
            at = sentence.rfind(" ", 0, limit + 1)
            if at < 1:
                at = limit
            yield sentence[:at]
            sentence = sentence[at:].lstrip()
        if sentence:
            yield sentence


class StreamingSynthesis:
    """One native owner. Cancellation releases it only after in-flight compute ends."""
    async def chunks(self, text, voice):
        if self.active:
            raise web.HTTPTooManyRequests(text="Read-aloud is busy", headers={"Retry-After": "2"})
        self.active = True
        pending = None
        total = 0
        def render(batch):
            samples, rate = self.model.create(batch, voice=voice, speed=1.0, lang="en-us")
            if rate != SAMPLE_RATE or len(samples) == 0:
                raise ValueError("Kokoro returned an unsupported audio format")
            pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()
            if len(pcm) > MAX_PCM:
                raise ValueError("Generated audio exceeds its limit")
            return pcm
        def release(future):
            self.active = False
            if not future.cancelled():
                future.exception()
        try:
            for batch in sentence_batches(text):
                pending = asyncio.get_running_loop().run_in_executor(self.executor, render, batch)
                pcm = await asyncio.shield(pending)
                total += len(pcm)
                if total > MAX_PCM:
                    raise ValueError("Generated audio exceeds its limit")
                yield pcm
        finally:
            if pending is not None and not pending.done():
                pending.add_done_callback(release)
            else:
                self.active = False

    async def synthesize(self, text, voice):
        pcm = bytearray()
        async for chunk in self.chunks(text, voice):
            pcm.extend(chunk)
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setparams((1, 2, SAMPLE_RATE, 0, "NONE", "not compressed"))
            wav.writeframes(pcm)
        return output.getvalue(), len(pcm) / (2 * SAMPLE_RATE)


async def stream_response(request, synthesizer, text, voice):
    chunks = synthesizer.chunks(text, voice)
    response = None
    try:
        first = await anext(chunks)  # Busy/errors remain HTTP errors until the first PCM exists.
        if request.transport is None or request.transport.is_closing():
            raise asyncio.CancelledError()
        response = web.StreamResponse(headers={
            "Content-Type": "audio/pcm", "Cache-Control": "no-store", "X-Accel-Buffering": "no",
            "X-Audio-Sample-Rate": str(SAMPLE_RATE), "X-Audio-Channels": "1",
            "X-Audio-Format": "pcm_s16le", "X-Speech-Engine": "kokoro"})
        await response.prepare(request)
        await response.write(first)
        while True:
            if request.transport is None or request.transport.is_closing():
                raise asyncio.CancelledError()
            try:
                chunk = await anext(chunks)
            except StopAsyncIteration:
                break
            await response.write(chunk)
        await response.write_eof()
        return response
    except (ConnectionError, asyncio.CancelledError):
        raise
    except Exception:
        if response is not None and response.prepared:
            if request.transport is not None:
                request.transport.abort()
        raise
    finally:
        await chunks.aclose()
