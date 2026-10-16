#!/usr/bin/env python3
"""Summarize preserved direct-input phone measurements, never synthetic timings."""
import argparse
import gzip
import json
import statistics
from pathlib import Path


def median(rows, key):
    values = [row[key] for row in rows if row.get(key) is not None]
    return statistics.median(values) if values else None


def number(value, digits=1):
    return 'unavailable' if value is None else f'{value:.{digits}f}'


def summarize(directory):
    conditions_file = directory / 'benchmark-conditions.json'
    conditions = json.loads(conditions_file.read_text()) if conditions_file.exists() else []
    thermals_file = directory / 'thermal-full.jsonl'
    if not thermals_file.exists():
        thermals_file = directory / 'thermal-full.jsonl.gz'
    thermal_samples = []
    if thermals_file.exists():
        opener = gzip.open if thermals_file.suffix == '.gz' else open
        with opener(thermals_file, 'rt') as stream:
            thermal_samples = [json.loads(line) for line in stream]
    phone = next((s['conditions'] for s in thermal_samples if 'conditions' in s), {})
    lines = ['# Moonshine phone benchmark results', '',
             f'Device: {phone.get("device", "unavailable")}; build: `{phone.get("build", "unavailable")}`.',
             f'Display brightness setting: {phone.get("brightness_setting", "unavailable")}; adaptive mode: {phone.get("brightness_mode", "unavailable")} (0 = manual). Fold state and battery conditions are retained in raw thermal evidence.',
             'Direct 16 kHz mono PCM16 input; no speaker/microphone loop. Three warm repeats per case.',
             'Cold means unloaded model with existing OS caches. Last-word boundaries are dataset forced-alignment estimates.',
             'First-partial and final latency are warm medians; final latency includes chunk arrival/decoding and distinguishes forced finalization.', '',
             'REALTIME elapsed time includes paced arrival; its audio/elapsed ratio is not maximum decoding throughput. Early-line and forced-final counts total the three warm repeats.',
             'Peak PSS is the sampled whole fixture process (including the test runner), not isolated model allocation.', '',
             '| Model | Input | Feed | Cold load ms | Warm process ms | Audio/process × | First partial ms | Last word → final ms | WER % | Early completed lines | Forced finals | Peak PSS MiB |',
             '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    details = []
    order = {'tiny': 0, 'small': 1, 'medium': 2}
    for file in sorted(directory.glob('results-moonshine-*.json'), key=lambda p: order.get(p.stem.split('-')[-1], 99)):
        rows = json.loads(file.read_text())
        if not rows:
            continue
        for feed in ['THROUGHPUT', 'REALTIME']:
            for fixture in ['clean', 'road-noise', 'numbers-measurements', 'natural-pauses']:
                selected = [r for r in rows if r['feed'] == feed and r['fixture'] == fixture and r['repetition'] < 4]
                cold = [r for r in selected if r['repetition'] == 0]
                warm = [r for r in selected if 1 <= r['repetition'] <= 3]
                if len(cold) != 1 or len(warm) != 3:
                    lines.append(f'| {rows[0]["model"]} | {fixture} | {feed} | INCOMPLETE: {len(selected)}/4 rows | | | | | | | | |')
                    continue
                elapsed = median(warm, 'elapsed_ms')
                wer = sum(r['word_edits'] for r in warm) / sum(r['reference_words'] for r in warm) * 100
                lines.append('| ' + ' | '.join([
                    rows[0]['model'], fixture, feed, number(cold[0]['load_ms']), number(elapsed),
                    number(warm[0]['audio_seconds'] * 1000 / elapsed, 2), number(median(warm, 'first_partial_ms')),
                    number(median(warm, 'last_word_to_final_ms')), number(wer, 2),
                    str(sum(r['premature_endings'] for r in warm)), str(sum(r['forced_finalization'] for r in warm)),
                    number(max(r['peak_pss_kb'] for r in selected) / 1024)
                ]) + ' |')
        details += ['', f'## {rows[0]["model"]}', '', f'- Checkpoint: `{rows[0]["checkpoint"]}`; quantization: `{rows[0]["quantization"]}`.',
                  f'- Runtime: `{rows[0]["runtime"]}`; backend: `{rows[0]["actual_backend"]}`.']
        native = [r['native_initialization_ms'] for r in rows if r.get('native_initialization_ms') is not None]
        details.append(f'- First JNI library initialization: {number(native[0] if native else None)} ms (included in that cold load).')
        sustained = [r for r in rows if r['repetition'] >= 4]
        if sustained:
            details.append(f'- Sustained test: at least 180 s wall time; {len(sustained)} runs, {sum(r["elapsed_ms"] for r in sustained) / 1000:.1f} s native processing (the remainder includes sampling, saving and test-runner overhead); peak PSS {max(r["peak_pss_kb"] for r in sustained) / 1024:.1f} MiB, peak RSS {max(r["peak_rss_kb"] for r in sustained) / 1024:.1f} MiB.')
            first = statistics.median(r['elapsed_ms'] for r in sustained[:10])
            last = statistics.median(r['elapsed_ms'] for r in sustained[-10:])
            details.append(f'- Sustained clean-clip processing, first/last ten-run medians: {first:.1f} / {last:.1f} ms. This is observed drift, not an isolated throttling measurement.')
        headrooms = []
        for row in rows:
            for key in ['thermal_start', 'thermal_end']:
                values = dict(part.split('=', 1) for part in row[key].split(';'))
                if values.get('headroom') not in [None, 'unavailable']:
                    headrooms.append(float(values['headroom']))
        details.append(f'- Android thermal headroom range: {min(headrooms):.3f}–{max(headrooms):.3f} (0 forecast seconds, 1.0 is the severe threshold; sample ages retained in raw rows).' if headrooms else '- Android thermal headroom: unavailable.')
        match = next((c for c in conditions if c['model'] == rows[0]['model']), None)
        if match and match.get('end'):
            baseline = {v['label']: v['value'] for v in match['baseline']['sensors'] if v['available']}
            details.append(f'- Cooled baseline: VIRTUAL-SKIN {number(baseline.get("VIRTUAL-SKIN"), 2)}°C; battery {number(baseline.get("battery"), 2)}°C; BIG {number(baseline.get("BIG"), 2)}°C; Android status {match["baseline"]["thermal_status"]}.')
            samples = [s for s in thermal_samples if s.get('utc') and match['start'] <= s['utc'] <= match['end']]
            statuses = [s['thermal_status'] for s in samples if s.get('thermal_status') is not None]
            details.append(f'- Maximum Android thermal status: {max(statuses) if statuses else "unavailable"}.')
            for sensor in ['battery', 'VIRTUAL-SKIN', 'BIG', 'LITTLE', 'GPU', 'TPU']:
                values = [v['value'] for s in samples for v in s.get('sensors', []) if v['label'] == sensor and v['available'] and v['unit'] == 'C']
                details.append(f'- Peak `{sensor}` (HAL label): {number(max(values) if values else None, 2)}' + (' °C.' if values else '.'))
        details += ['']
    lines += [''] + details
    lines += ['## Raw evidence', '', 'The JSON files retain every transcript, audio SHA-256, actual backend, runtime settings, PSS/RSS and thermal/headroom snapshots.',
              'Headroom interpretation follows [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager#getThermalHeadroom(int)); sensor labels retain their HAL names.',
              'A completed recognizer line before the annotated last word is a candidate early boundary, not a measured premature assistant turn.',
              'These short excerpts do not establish conversational or general recognition quality. Acoustic echo, barge-in and microphone quality need separate tests.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = summarize(args.directory)
    if args.output:
        args.output.write_text(report)
    else:
        print(report)


if __name__ == '__main__':
    main()
