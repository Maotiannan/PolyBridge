"""Offline evaluation only. No cloud calls, microphone, or application settings.

GTCRN tensors and sqrt-Hann STFT follow the upstream streaming example:
https://github.com/Xiaobin-Rong/gtcrn/blob/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73/stream/gtcrn_stream.py
This adapter uses zero boundary padding so it needs only 16 ms lookahead.
"""
import argparse
import hashlib
import json
from pathlib import Path
import time
import wave
import numpy as np


def si_sdr(clean, output):
    reference = clean - np.mean(clean)
    estimate = output - np.mean(output)
    projection = reference * (np.dot(estimate, reference) / max(np.dot(reference, reference), 1e-12))
    return float(10 * np.log10((np.sum(projection ** 2) + 1e-12) / (np.sum((estimate - projection) ** 2) + 1e-12)))


class StreamingEnhancer:
    HOP = 256

    def __init__(self, transform):
        self.transform = transform
        self.closed = False
        self.window = np.sqrt(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(512) / 512))
        self.analysis = np.zeros(512, dtype=np.float32)
        self.synthesis = np.zeros(512, dtype=np.float64)
        self.pending = np.array([], dtype=np.float32)
        self.skip = self.HOP
        self.received = 0
        self.emitted = 0

    def _hop(self, samples):
        self.analysis[:self.HOP] = self.analysis[self.HOP:]
        self.analysis[self.HOP:] = samples
        spectrum = np.fft.rfft(self.analysis * self.window).astype(np.complex64)
        enhanced = self.transform(spectrum)
        if enhanced.shape != spectrum.shape or not np.all(np.isfinite(enhanced)):
            raise ValueError("invalid frequency processor output")
        self.synthesis += np.fft.irfft(enhanced, n=512) * self.window
        block = self.synthesis[:self.HOP].copy()
        self.synthesis[:self.HOP] = self.synthesis[self.HOP:]
        self.synthesis[self.HOP:] = 0
        if self.skip:
            self.skip = 0
            return np.array([], dtype=np.float32)
        count = min(self.HOP, self.received - self.emitted)
        self.emitted += count
        return block[:count].astype(np.float32)

    def send(self, samples):
        if self.closed:
            raise ValueError("stream already finished")
        samples = np.asarray(samples, dtype=np.float32)
        if samples.ndim != 1 or not np.all(np.isfinite(samples)):
            raise ValueError("expected finite mono samples")
        self.received += len(samples)
        self.pending = np.concatenate([self.pending, samples])
        output = []
        consumed = 0
        while consumed + self.HOP <= len(self.pending):
            output.append(self._hop(self.pending[consumed:consumed + self.HOP]))
            consumed += self.HOP
        self.pending = self.pending[consumed:].copy()
        return np.concatenate(output) if output else np.array([], dtype=np.float32)

    def finish(self):
        if self.closed:
            return np.array([], dtype=np.float32)
        self.closed = True
        output = []
        if len(self.pending):
            output.append(self._hop(np.pad(self.pending, (0, self.HOP - len(self.pending)))))
        if self.emitted < self.received:
            output.append(self._hop(np.zeros(self.HOP, dtype=np.float32)))
        self.pending = np.array([], dtype=np.float32)
        return np.concatenate(output) if output else np.array([], dtype=np.float32)


def session(path):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


class Gtcrn:
    def __init__(self, model):
        self.model = model
        self.conv = np.zeros((2, 1, 16, 16, 33), dtype=np.float32)
        self.tra = np.zeros((2, 3, 1, 1, 16), dtype=np.float32)
        self.inter = np.zeros((2, 1, 33, 16), dtype=np.float32)
        self.times = []

    def __call__(self, spectrum):
        real_imag = np.stack([spectrum.real, spectrum.imag], axis=-1)[None, :, None, :]
        started = time.perf_counter()
        enhanced, self.conv, self.tra, self.inter = self.model.run(None, {
            "mix": real_imag, "conv_cache": self.conv, "tra_cache": self.tra, "inter_cache": self.inter})
        self.times.append((time.perf_counter() - started) * 1000)
        return enhanced[0, :, 0, 0] + 1j * enhanced[0, :, 0, 1]


def audio(path):
    with wave.open(str(path), "rb") as reader:
        if reader.getsampwidth() != 2:
            raise ValueError("fixture must be PCM16")
        rate = reader.getframerate()
        samples = np.frombuffer(reader.readframes(reader.getnframes()), dtype="<i2").astype(np.float32) / 32768
        samples = samples.reshape(-1, reader.getnchannels()).mean(axis=1)
    if rate != 16000:
        samples = np.interp(np.arange(int(len(samples) * 16000 / rate)) * rate / 16000,
                            np.arange(len(samples)), samples).astype(np.float32)
    return samples


def render(model, samples):
    transform = Gtcrn(model)
    stream = StreamingEnhancer(transform)
    output = [stream.send(samples[i:i + 320]) for i in range(0, len(samples), 320)]
    output.append(stream.finish())
    enhanced = np.concatenate(output)
    assert len(samples) == len(enhanced)
    assert np.all(np.isfinite(enhanced))
    return enhanced, transform.times


def rms(samples):
    return float(np.sqrt(np.mean(samples.astype(np.float64) ** 2)))


def voice_windows(model, samples):
    """Independent model state, same 512+64 window and .52/.32 policy as the Android gate."""
    state = np.zeros((2, 1, 128), dtype=np.float32)
    context = np.zeros(64, dtype=np.float32)
    onset = tail = passed = 0
    windows = 0
    for start in range(0, len(samples), 512):
        block = samples[start:start + 512]
        block = np.pad(block, (0, 512 - len(block)))
        probability, state = model.run(None, {"input": np.concatenate([context, block])[None, :],
                                              "state": state, "sr": np.array(16000, dtype=np.int64)})
        context = block[-64:]
        p = float(probability.flat[0])
        onset = onset + 1 if p >= .52 else 0
        if onset >= 2 or (tail > 0 and p >= .32):
            tail = 8
        if tail > 0:
            passed += 1
            tail -= 1
        windows += 1
    return {"activeWindows": passed, "windows": windows}


def evaluate(root, model_path):
    model = session(model_path)
    vad = session(root / "app/src/main/assets/speech-detection/silero-16k.onnx")
    timings = []
    noise = []
    mixtures = []
    clean_cases = []
    rains = sorted((root / "app/src/androidTest/assets/rain").glob("*-*-*-*.wav"))
    if len(rains) != 2:
        raise ValueError("expected the two versioned rain fixtures")
    for path in rains:
        samples = audio(path)
        enhanced, elapsed = render(model, samples)
        timings.extend(elapsed)
        noise.append({"fixture": path.name, "suppressionDb": 20 * np.log10((rms(samples) + 1e-12) / (rms(enhanced) + 1e-12)),
                      "beforeVad": voice_windows(vad, samples), "afterVad": voice_windows(vad, enhanced)})
    for name in ("tickets-fr", "tickets-zh", "train-fr", "train-zh"):
        voice = audio(root / f"app/src/main/assets/phrasebook/{name}.wav")
        clean, elapsed = render(model, voice)
        timings.extend(elapsed)
        clean_cases.append({"fixture": name, "siSdrDb": si_sdr(voice, clean),
                            "gainDb": 20 * np.log10((rms(clean) + 1e-12) / (rms(voice) + 1e-12))})
        for rain in rains:
            background = np.resize(audio(rain), len(voice))
            for snr in (0, 5, 10):
                scaled_voice = voice * .25
                scaled_noise = background * rms(scaled_voice) / rms(background) / 10 ** (snr / 20)
                mixture = scaled_voice + scaled_noise
                enhanced, elapsed = render(model, mixture)
                timings.extend(elapsed)
                # First 0.8 seconds with nontrivial clean energy: don't mistake global quality for preserved onset.
                active = np.flatnonzero(np.abs(scaled_voice) > max(0.001, rms(scaled_voice) * .2))
                first = int(active[0]) if len(active) else 0
                onset = slice(first, min(first + 12800, len(voice)))
                mixtures.append({"voice": name, "noise": rain.name, "snrDb": snr,
                    "beforeSiSdrDb": si_sdr(scaled_voice, mixture), "afterSiSdrDb": si_sdr(scaled_voice, enhanced),
                    "openingBeforeSiSdrDb": si_sdr(scaled_voice[onset], mixture[onset]),
                    "openingAfterSiSdrDb": si_sdr(scaled_voice[onset], enhanced[onset])})
    return {"schemaVersion": 1, "scope": "offline GTCRN candidate only; not Android, echo, or cloud recognition acceptance",
            "upstreamCommit": "502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73",
            "modelSha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
            "sampleRate": 16000, "lookaheadMs": 16, "resampling": "linear, evaluation only",
            "noise": noise, "clean": clean_cases, "mixtures": mixtures,
            "cpuInferenceMs": {"p50": float(np.percentile(timings, 50)), "p95": float(np.percentile(timings, 95)),
                               "max": max(timings)}, "cloudCalls": 0}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = evaluate(Path(__file__).resolve().parents[2], args.model)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"noise": result["noise"], "clean": result["clean"], "mixtures": len(result["mixtures"]),
                     "minimumImprovementDb": min(x["afterSiSdrDb"] - x["beforeSiSdrDb"] for x in result["mixtures"]),
                     "minimumOpeningImprovementDb": min(x["openingAfterSiSdrDb"] - x["openingBeforeSiSdrDb"] for x in result["mixtures"]),
                     "cpuInferenceMs": result["cpuInferenceMs"]}, indent=2))
