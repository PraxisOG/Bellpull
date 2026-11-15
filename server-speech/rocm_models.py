"""Pinned Moonshine Medium / Kokoro on AMD PyTorch. No CPU fallback."""
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
            tokens = self.model.generate(**inputs, max_new_tokens=512)
        if tokens[0, -1].item() != self.model.config.eos_token_id:
            raise RuntimeError('Recognition exceeded its output limit; record a shorter turn')
        return self.processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()


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
