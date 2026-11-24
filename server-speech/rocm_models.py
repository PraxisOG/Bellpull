"""Pinned Moonshine Medium / Kokoro on AMD PyTorch. No CPU fallback."""
import time

import numpy as np

MOONSHINE = 'moonshine-ai/moonshine-streaming-medium'
MOONSHINE_REVISION = '57b843633a8c183cadf6699ffa761377a933a866'
KOKORO = 'hexgrad/Kokoro-82M'
KOKORO_REVISION = 'f3ff3571791e39611d31c381e3a41a3af07b4987'


def configure():
    import torch
    if not torch.version.hip or not torch.cuda.is_available():
        raise RuntimeError('ROCm GPU is unavailable; refusing CPU fallback')
    torch.set_num_threads(3)
    # Variable-length convolution otherwise triggers slow per-shape MIOpen tuning.
    torch.backends.cudnn.enabled = False
    return torch


class Moonshine:
    def __init__(self):
        torch = configure()
        from transformers import AutoProcessor, MoonshineStreamingForConditionalGeneration
        self.processor = AutoProcessor.from_pretrained(MOONSHINE, revision=MOONSHINE_REVISION, local_files_only=True)
        self.model = MoonshineStreamingForConditionalGeneration.from_pretrained(
            MOONSHINE, revision=MOONSHINE_REVISION, local_files_only=True,
            dtype=torch.float32, attn_implementation='sdpa').to('cuda').eval()

    def transcribe(self, pcm):
        import torch
        samples = np.frombuffer(pcm, dtype='<i2').astype(np.float32) / 32768
        if not len(samples) or np.max(np.abs(samples)) < .001:
            return ''
        inputs = self.processor(samples, sampling_rate=16000, return_tensors='pt').to('cuda')
        with torch.inference_mode():
            return self.decode(**inputs)

    def decode(self, *, provisional_limit=None, **inputs):
        tokens = self.model.generate(**inputs, max_new_tokens=provisional_limit or 512)
        if tokens[0, -1].item() != self.model.config.eos_token_id:
            if provisional_limit is not None:
                return None
            raise RuntimeError('Recognition exceeded its output limit; record a shorter turn')
        return self.processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()

    def open_stream(self, partials):
        return MoonshineStream(self, partials)


class MoonshineStream:
    """Reuse settled encoder frames and revise the transcript as audio arrives."""
    def __init__(self, recognizer, partials):
        self.recognizer = recognizer
        self.partials = partials
        self.pcm = bytearray()
        self.text = ''
        self.encoded_bytes = 0
        self.complete = True
        self.next_update = 25600  # 800 ms of mono PCM16 at 16 kHz.
        self.encoded = None
        self.stable_frames = 0
        self.encoder = recognizer.model.get_encoder()
        embedder = self.encoder.embedder
        stride1 = embedder.conv1.stride[0]
        stride = stride1 * embedder.conv2.stride[0]
        self.frame_bytes = 2 * embedder.frame_len * stride
        # A restarted convolution needs its left history and the original stride phase.
        conv_history = embedder.conv1.left_pad + stride1 * embedder.conv2.left_pad
        windows = self.encoder.config.sliding_windows
        self.left_frames = (conv_history + stride - 1) // stride + sum(left for left, _ in windows)
        self.right_frames = 1 + sum(right for _, right in windows)

    def feed(self, data):
        self.pcm.extend(data)
        if self.partials and len(self.pcm) >= self.next_update:
            start = time.monotonic()
            self._update()
            # Leave time for a second stream and for TTS as decoder work grows.
            interval = max(0.8, 3 * (time.monotonic() - start))
            self.next_update = len(self.pcm) + round(32000 * interval)
        return self.text

    def _encode_suffix(self):
        import torch
        start_frame = max(0, self.stable_frames - self.left_frames)
        pcm = np.frombuffer(self.pcm, dtype='<i2', offset=start_frame * self.frame_bytes)
        samples = pcm.astype(np.float32) / 32768
        self.encoded_bytes = len(self.pcm)
        if self.encoded is None and (not samples.size or np.max(np.abs(samples)) < .001):
            return
        inputs = self.recognizer.processor(samples, sampling_rate=16000, return_tensors='pt').to('cuda')
        output = self.encoder(**inputs)
        if self.stable_frames:
            # Frames before the cache boundary have seen their full right context.
            cut = self.stable_frames - start_frame
            output.last_hidden_state = torch.cat((self.encoded.last_hidden_state[:, :self.stable_frames],
                                                  output.last_hidden_state[:, cut:]), dim=1)
            output.attention_mask = torch.cat((self.encoded.attention_mask[:, :self.stable_frames],
                                               output.attention_mask[:, cut:]), dim=1)
        self.encoded = output
        self.stable_frames = max(0, output.last_hidden_state.shape[1] - self.right_frames)

    def _update(self, final=False):
        import torch
        with torch.inference_mode():
            if self.encoded_bytes != len(self.pcm):
                self._encode_suffix()
            if self.encoded is None:
                return
            # The pinned decoder adds positional embeddings in place. Keep the
            # acoustic cache untouched for the next update.
            decoder_input = type(self.encoded)(last_hidden_state=self.encoded.last_hidden_state.clone(),
                                               attention_mask=self.encoded.attention_mask)
            # Brief or ambiguous audio can produce a runaway interim hypothesis.
            # Give the definitive transcript the normal full generation budget.
            limit = None if final else min(512, 16 + 12 * len(self.pcm) // 32000)
            result = self.recognizer.decode(encoder_outputs=decoder_input, provisional_limit=limit)
            self.complete = result is not None
            if self.complete:
                self.text = result

    def finish(self):
        if not self.partials:
            return self.recognizer.transcribe(self.pcm)
        if self.encoded_bytes != len(self.pcm) or not self.complete:
            self._update(final=True)
        return self.text

    def close(self):
        self.pcm[:] = bytes(len(self.pcm))
        self.pcm.clear()
        self.encoded = None
        self.text = ''


class Kokoro:
    def __init__(self):
        torch = configure()
        from huggingface_hub import hf_hub_download
        from kokoro import KModel
        from kokoro_onnx.tokenizer import Tokenizer
        from kokoro_onnx.config import EspeakConfig
        def file(name):
            return hf_hub_download(KOKORO, name, revision=KOKORO_REVISION, local_files_only=True)
        self.model = KModel(repo_id=KOKORO, config=file('config.json'), model=file('kokoro-v1_0.pth')).to('cuda').eval()
        self.voice = torch.load(file('voices/af_heart.pt'), weights_only=True).to('cuda')
        self.tokenizer = Tokenizer(EspeakConfig())

    def create(self, text, voice='af_heart', speed=1.0, lang='en-us'):
        import torch
        from kokoro_onnx import split_phonemes
        if voice != 'af_heart' or lang != 'en-us':
            raise ValueError('Only the installed English af_heart voice is supported')
        phonemes = ' '.join(self.tokenizer.phonemize(text, lang=lang).split())
        parts = []
        with torch.inference_mode():
            for batch in split_phonemes(phonemes, 350):
                count = sum(p in self.model.vocab for p in batch)
                if count:
                    parts.append(self.model(batch, self.voice[min(count, len(self.voice)) - 1], speed=speed).numpy())
        if not parts:
            raise ValueError('Text produced no speakable phonemes')
        return np.concatenate(parts), 24000


def download():
    from huggingface_hub import snapshot_download
    snapshot_download(MOONSHINE, revision=MOONSHINE_REVISION, allow_patterns=['*.json', '*.safetensors'])
    snapshot_download(KOKORO, revision=KOKORO_REVISION,
                      allow_patterns=['config.json', 'kokoro-v1_0.pth', 'voices/af_heart.pt'])


if __name__ == '__main__':
    download()
