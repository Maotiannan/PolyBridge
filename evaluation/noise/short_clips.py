"""Deterministic <=20s public-noise / bundled-speech fixtures. No cloud or playback."""
import argparse
import hashlib
import json
from pathlib import Path
import wave
import numpy as np
from benchmark import audio, rms

RATE = 16000


def schedule(turns, seconds):
    if not np.isfinite(seconds) or not 0 < seconds <= 20:
        raise ValueError("duration must be in (0, 20] seconds")
    clean = np.zeros(round(seconds * RATE), dtype=np.float32)
    spans = []
    for start, voice in turns:
        voice = np.asarray(voice, dtype=np.float32)
        if not np.isfinite(start) or voice.ndim != 1 or not len(voice) or not np.all(np.isfinite(voice)):
            raise ValueError("expected finite start and nonempty mono audio")
        a = round(start * RATE)
        b = a + len(voice)
        if a < 0 or b > len(clean) or any(a < right and b > left for left, right in spans):
            raise ValueError("turn overlaps or exceeds the clip")
        clean[a:b] = voice
        spans.append((a, b))
    return clean, spans


def mix(clean, noise, spans, snr):
    clean, noise = np.asarray(clean, dtype=np.float64), np.asarray(noise, dtype=np.float64)
    if clean.ndim != 1 or clean.shape != noise.shape or not len(clean) or not np.isfinite(snr):
        raise ValueError("invalid mixture shape or SNR")
    if not np.all(np.isfinite(clean)) or not np.all(np.isfinite(noise)):
        raise ValueError("mixture must be finite")
    mask = np.zeros(len(clean), dtype=bool)
    for a, b in spans:
        if not 0 <= a < b <= len(clean):
            raise ValueError("invalid speech span")
        mask[a:b] = True
    if not mask.any() or rms(clean[mask]) <= 0 or rms(noise[mask]) <= 0:
        raise ValueError("mixture needs nonzero speech and background")
    noise = noise * rms(clean[mask]) / rms(noise[mask]) / 10 ** (snr / 20)
    gain = min(1.0, .95 / max(float(np.max(np.abs(clean + noise))), 1e-12))
    return (clean + noise) * gain, clean * gain, noise * gain


def write_wave(path, samples):
    values = np.asarray(samples)
    if not np.all(np.isfinite(values)) or np.max(np.abs(values), initial=0) > 1:
        raise ValueError("invalid PCM samples")
    with wave.open(str(path), "wb") as writer:
        writer.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        writer.writeframes(np.rint(values * 32767).astype("<i2").tobytes())
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(root, output):
    output.mkdir(parents=True, exist_ok=True)
    catalog = {}
    for line in (root / "app/src/main/assets/phrasebook/catalog.tsv").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            parts = line.split("\t")
            if len(parts) == 4:
                catalog[parts[0]] = parts[2:]
    spec = [(1., "station", "fr"), (4.5, "tickets", "zh"), (9., "train", "fr"), (14., "thanks", "zh")]
    voices = [audio(root / f"app/src/main/assets/phrasebook/{name}-{lang}.wav") * .35 for _, name, lang in spec]
    clean, spans = schedule([(row[0], voice) for row, voice in zip(spec, voices)], 18)
    turns = [{"startSample": a, "endSample": b, "language": lang,
              "source": catalog[name][lang == "fr"], "translation": catalog[name][lang != "fr"]}
             for (_, name, lang), (a, b) in zip(spec, spans)]
    paths = {"rain": root / "app/src/androidTest/assets/rain/2-73027-A-10.wav",
             "helicopter": root / "evaluation/noise/sources/helicopter.wav",
             "clock": root / "evaluation/noise/sources/clock.wav"}
    noises = {key: np.resize(audio(path), len(clean)) for key, path in paths.items()}
    sources = [{"file": str(path.relative_to(root)).replace("\\", "/"),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in paths.values()]
    sources += [{"file": f"app/src/main/assets/phrasebook/{name}-{lang}.wav",
                 "sha256": hashlib.sha256((root / f"app/src/main/assets/phrasebook/{name}-{lang}.wav").read_bytes()).hexdigest()}
                for _, name, lang in spec]
    cases = [("clean", clean, clean, None, None)]
    for name, background, snr, level in [("rain-5db", "rain", 5, 1), ("rain-0db", "rain", 0, 1),
                                        ("machine-5db", "helicopter", 5, 1), ("clock-5db", "clock", 5, 1),
                                        ("quiet-rain-5db", "rain", 5, .15)]:
        mixed, reference, _ = mix(clean * level, noises[background], spans, snr)
        cases.append((name, mixed, reference, background, snr))
    # Speech-region global SNR is 5 dB; second half background amplitude is 6x the first.
    rising = noises["rain"].copy()
    rising[:8 * RATE] *= 1 / 6
    mixed, reference, _ = mix(clean, rising, spans, 5)
    cases.append(("rising-rain", mixed, reference, "rain (6x at 8s)", 5))
    for name, background in noises.items():
        noise = background * min(1., .8 / max(float(np.max(np.abs(background))), 1e-12))
        cases.append((f"noise-only-{name}", noise, None, name, None))
    clips = []
    for name, samples, reference, background, snr in cases:
        item = {"id": name, "file": f"{name}.wav", "sampleRate": RATE, "samples": len(samples),
                "seconds": len(samples) / RATE, "noise": background, "speechRegionSnrDb": snr,
                "turns": turns if reference is not None else [],
                "sha256": write_wave(output / f"{name}.wav", samples)}
        if reference is not None:
            item["reference"] = f"{name}.clean.wav"
            item["referenceSha256"] = write_wave(output / item["reference"], reference)
        clips.append(item)
    result = {"schemaVersion": 1, "description": "Bundled synthetic speech alternating fr/zh; public background recordings looped; not a natural conversation",
              "resampling": "linear mono 16kHz, evaluation only", "sources": sources, "clips": clips}
    (output / "manifest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "fixtures/short")
    args = parser.parse_args()
    result = generate(Path(__file__).resolve().parents[2], args.output)
    print(json.dumps({"clips": len(result["clips"]), "maxSeconds": max(c["seconds"] for c in result["clips"]),
                      "output": str(args.output)}, ensure_ascii=False))
