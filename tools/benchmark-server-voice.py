#!/usr/bin/env python3
"""Measure the loopback helper's sentence streaming and concurrent transcription."""
import argparse
import asyncio
import io
import json
import os
from pathlib import Path
import time
import wave

import aiohttp


PHRASES = {
    'short': 'Your notes are ready.',
    'long': ('The notebook lists the parts in order. First, inspect the cable near the opening. '
             'Next, follow the printed labels from left to right. When both readings agree, '
             'enter the result on the last page. Finally, save your work and close the notebook.'),
    'punctuation': 'Dr. Green checked at 3.5 volts. “Ready?” she asked. Yes! First, check A, B, and C. Then pause — and listen.',
}


async def read_aloud(client, base, text):
    start = time.perf_counter()
    first = None
    size = 0
    async with client.post(base + '/tts', json={'text': text, 'stream': True}) as response:
        if (response.content_type, response.headers.get('X-Audio-Sample-Rate'), response.headers.get('X-Audio-Format')) != ('audio/pcm', '24000', 'pcm_s16le'):
            raise ValueError('Expected 24 kHz PCM16 audio')
        async for chunk in response.content.iter_any():
            if first is None:
                first = time.perf_counter()
            size += len(chunk)
    if not size or size % 2:
        raise ValueError('Incomplete PCM16 audio')
    elapsed = time.perf_counter() - start
    seconds = size / 48000
    return {'first_pcm_ms': round((first - start) * 1000), 'total_ms': round(elapsed * 1000),
            'audio_s': round(seconds, 3), 'rtf': round(elapsed / seconds, 3)}


async def transcribe(client, base, audio):
    form = aiohttp.FormData()
    form.add_field('audio', audio, filename='fixture.wav', content_type='audio/wav')
    start = time.perf_counter()
    async with client.post(base + '/stt', data=form) as response:
        result = await response.json()
    return round((time.perf_counter() - start) * 1000), result['text']


async def measure(args):
    base = f'http://127.0.0.1:{args.port}'
    audio = args.wav.read_bytes()
    with wave.open(io.BytesIO(audio)) as clip:
        if (clip.getframerate(), clip.getnchannels(), clip.getsampwidth()) != (16000, 1, 2):
            raise ValueError('Supply a 16 kHz mono PCM16 WAV fixture')
    key = os.environ['BELLPULL_SPEECH_KEY']
    async with aiohttp.ClientSession(headers={'Authorization': 'Bearer ' + key}, raise_for_status=True) as client:
        async with client.get(base + '/health') as response:
            print(json.dumps({'health': await response.json()}), flush=True)
        await read_aloud(client, base, PHRASES['short'])
        _, reference = await transcribe(client, base, audio)
        if not reference.strip():
            raise ValueError('The fixture must contain recognizable speech')
        for number in range(1, args.rounds + 1):
            idle, words = await transcribe(client, base, audio)
            synthesis = {name: await read_aloud(client, base, phrase) for name, phrase in PHRASES.items()}
            (busy, simultaneous_words), concurrent = await asyncio.gather(
                transcribe(client, base, audio), read_aloud(client, base, PHRASES['long']))
            print(json.dumps({'round': number, 'idle_stt_ms': idle, 'concurrent_stt_ms': busy,
                              'same_transcript': words == simultaneous_words == reference, 'tts': synthesis,
                              'concurrent_tts': concurrent}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wav', type=Path, help='Synthetic or public speech fixture')
    parser.add_argument('--port', type=int, default=8645)
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 1:
        parser.error('--rounds must be positive')
    asyncio.run(measure(args))
