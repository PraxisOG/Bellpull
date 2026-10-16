"""Build-only, checked adaptation of pinned Sherpa source; never edit the submodule.

Pocket does not need eSpeak, piper, FST normalization or Eigen. Exclude those paths,
replace process exit with exceptions, suppress content logs, and add cancellation
checks between inference steps. The numerical inference code is otherwise upstream.
"""
import pathlib
import sys

source, target = map(pathlib.Path, sys.argv[1:])
base = source / 'sherpa-onnx/csrc'
out = target / 'sherpa-onnx/csrc'
out.mkdir(parents=True, exist_ok=True)

def replace(text, old, new, count=1):
    if text.count(old) != count:
        raise SystemExit(f'Pinned Pocket source changed: {old[:80]}')
    return text.replace(old, new)

def remove_block(text, start, count):
    if text.count(start) != count:
        raise SystemExit(f'Pinned Pocket source changed: {start}')
    for _ in range(count):
        at = text.index(start)
        opening = text.index('{', at)
        depth, end = 1, opening + 1
        while depth:
            if text[end] == '{': depth += 1
            if text[end] == '}': depth -= 1
            end += 1
        text = text[:at] + text[end:]
    return text

text = (base / 'offline-tts-pocket-impl.h').read_text()
for header in ['fst/extensions/far/far.h', 'kaldifst/csrc/kaldi-fst-io.h',
               'kaldifst/csrc/text-normalizer.h', 'sherpa-onnx/csrc/fst-utils.h']:
    text = replace(text, f'#include "{header}"\n', '')
for start, count in [('    if (!config.rule_fsts.empty()) {', 2),
                     ('    if (!config.rule_fars.empty()) {', 2),
                     ('    if (!tn_list_.empty()) {', 1),
                     ('    if (silence_scale != 1) {', 1)]:
    text = remove_block(text, start, count)
text = replace(text, '  std::vector<std::unique_ptr<kaldifst::TextNormalizer>> tn_list_;\n', '')
text = replace(text, 'namespace sherpa_onnx {', '''namespace sherpa_onnx {
// Bellpull JNI owns this thread-local probe for the duration of Generate.
extern thread_local std::function<bool()> bellpull_cancelled;
inline void BellpullCheckCancelled() {
  if (bellpull_cancelled && bellpull_cancelled()) throw std::runtime_error("cancelled");
}''')
for anchor in ['    Ort::Value voice_embedding = GetVoiceEmbedding(gen_config);',
               '      GeneratedAudio cur = GenerateSingleSentence',
               '      Ort::Value cur_tensor =',
               '      Ort::Value out = model_->RunLmFlow',
               '      Ort::Value out = RunMimiDecoder']:
    text = replace(text, anchor, '      BellpullCheckCancelled();\n' + anchor)
# Eigen is only needed by other models; Pocket uses ScaleAdd alone.
text = replace(text, '#include "sherpa-onnx/csrc/math.h"', '''namespace sherpa_onnx {
inline void ScaleAdd(const float *src, float scale, int32_t n, float *out) {
  for (int32_t i = 0; i < n; ++i) out[i] += src[i] * scale;
}
}''')
(out / 'offline-tts-pocket-impl.h').write_text(text)
text = (base / 'macros.h').read_text()
start = text.index('#if __ANDROID_API__ >= 8')
end = text.index('// Read an integer', start)
text = text[:start] + '''#include <stdexcept>
#include <android/log.h>
// No utterances, reference paths or tokenizer dumps in production logs.
#define SHERPA_ONNX_LOGE(...) do {} while (0)
#define SHERPA_ONNX_EXIT(code) throw std::runtime_error("Pocket model error")

''' + text[end:]
(out / 'macros.h').write_text(text)
# Compile just the POD generation accessors, without all-model factories.
text = (base / 'offline-tts.cc').read_text()
start = text.index('std::string GenerationConfig::GetExtraString(')
end = text.index('void OfflineTtsConfig::Register(', start)
(target / 'pocket-generation.cc').write_text('''// Copyright (c) 2023 Xiaomi Corporation. Apache-2.0.
// Selected unchanged GenerationConfig accessors from pinned Sherpa offline-tts.cc.
#include <sstream>
#include <map>
#include "sherpa-onnx/csrc/offline-tts.h"
#include "sherpa-onnx/csrc/text-utils.h"
namespace sherpa_onnx {
''' + text[start:end] + '\n}\n')
