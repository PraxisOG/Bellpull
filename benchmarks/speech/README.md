# Speech benchmark inputs

The four prepared WAVs are generated into the ignored `build/speech-lab/fixtures/`
directory. Exact hashes, reference transcripts, source clip IDs, changes, licenses
and timing estimates are pinned in [fixtures-manifest.json](fixtures-manifest.json).
Measured phone results and raw evidence are in [pixel-10-pro-fold-2026-10-11](pixel-10-pro-fold-2026-10-11/README.md).

```sh
python3 tools/prepare_speech_fixtures.py
```

The generator needs Python 3 and the system `libsndfile` to decode FLAC, with no
third-party Python packages. It fetches only three short speech clips and the
compressed traffic channel via a checked HTTP byte range. Source references are
checked against the primary LibriSpeech dataset. Generated inputs are checked
against the committed manifest so comparisons use identical bytes.

| Case | Duration including trailing silence | Content |
| --- | ---: | --- |
| Clean | 12.555 s | Human audiobook speech |
| Road noise | 12.555 s | Same speech with recorded busy-intersection traffic at 10 dB whole-clip RMS SNR |
| Numbers/measurements | 6.740 s | “Twelve miles” and “ten o'clock” in human speech |
| Natural pauses | 16.650 s | Human speech with an approximately 880 ms internal pause |

Each file includes two seconds of added trailing silence so streaming recognition
can detect an ending before forced file-end flushing. Last-word and pause timings
are **Montreal Forced Aligner estimates**, not manually verified boundaries.
This is a small smoke corpus, not a population-wide accuracy evaluation. It does
not cover decimal measurements, every accent, conversational disfluency or a real
phone recording in a moving car. Add those as separate cases before generalizing.

## Device runner

Build the separate sandbox with `-PbellpullDeviceFixture=true`; never run this
suite against the personal app. Stage the generated WAVs and manifest under the
fixture application's `files/speech-fixtures/`, then select the benchmark method
explicitly:

```sh
adb -s PHONE_SERIAL shell am instrument -w -r \
  -e class org.praxisog.hermes.LocalSpeechDeviceTest#directCorpusColdWarmChunkedAndSustained \
  -e speechModel moonshine-tiny \
  org.praxisog.hermes.fixture.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat for `moonshine-small` and `moonshine-medium`, with a new process and the same
cooled baseline between models. `-e downloadOnly true` verifies/downloads weights
without inference; `-e sustained false` omits the sustained test. Default runs
include all four clips, both feed modes, one unloaded-model run and three warm
repeats per case, followed by at least 180 seconds of repeated clean-clip batches.
The runner atomically saves results after each run in
`no_backup/speech-benchmarks/device-MODEL_ID.json`. Export them with `adb exec-out
run-as org.praxisog.hermes.fixture cat ...` before removing the sandbox.

Use `tools/speech_thermal_monitor.py` for supplementary HAL temperatures, keep
charging, brightness and posture fixed, and stop at severe thermal status.
`tools/summarize_speech_benchmarks.py DIRECTORY` reads `results-moonshine-*.json`,
`benchmark-conditions.json` and `thermal-full.jsonl` to reproduce the tables.
Model switching, tuning, deletion and local review are a separate native test;
the microphone/offline playback lifecycle test is separate from recognizer timing.

## Attribution and licenses

Speech: **LibriSpeech ASR corpus**, Vassil Panayotov, Guoguo Chen, Daniel Povey and
Sanjeev Khudanpur; audiobook readers from LibriVox. Source:
[OpenSLR 12](https://www.openslr.org/12), mirrored in
[openslr/librispeech_asr](https://huggingface.co/datasets/openslr/librispeech_asr).
Licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Changes: PCM16 WAV conversion and two seconds of trailing silence.

Word alignments: Loren Lugosch and collaborators, formatted by Kim Gilkey in
[librispeech-alignments](https://huggingface.co/datasets/gilkeyio/librispeech-alignments),
under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Generated using
Montreal Forced Aligner; the dataset documentation warns that alignments can be
inaccurate. Only the existing words' timing annotations are used here.

Noise: **DEMAND: Diverse Environments Multichannel Acoustic Noise Database**,
Joachim Thiemann, Nobutaka Ito and Emmanuel Vincent (2013),
[doi:10.5281/zenodo.1227121](https://doi.org/10.5281/zenodo.1227121).
Source: `STRAFFIC_16k.zip`, `STRAFFIC/ch01.wav`, excerpt beginning at 30 seconds.
Licensed [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).
Changes: mix with the clean fixture at 10 dB whole-clip RMS SNR, apply a common
attenuation to avoid clipping, and append two seconds of silence. The mixed
road-noise derivative retains CC BY-SA 3.0; the underlying speech retains its
CC BY 4.0 attribution. These data licenses are separate from Bellpull's MIT code.
