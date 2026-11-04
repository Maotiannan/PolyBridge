"""Regenerate the independent Python reference used by Kotlin's actual-model test."""
from pathlib import Path
import numpy as np
from benchmark import audio, render, session

root = Path(__file__).resolve().parents[2]
model = root / "app/src/main/assets/noise-reduction/gtcrn.onnx"
destination = root / "audio/processing/src/test/resources"
voice = audio(root / "app/src/main/assets/phrasebook/train-fr.wav")[:16013]
source = np.clip(np.floor(voice * 32768 + .5), -32768, 32767).astype("<i2")
enhanced, _ = render(session(model), source.astype(np.float32) / 32768)
result = np.clip(np.floor(enhanced * 32768 + .5), -32768, 32767).astype("<i2")
destination.mkdir(parents=True, exist_ok=True)
source.tofile(destination / "gtcrn-input.pcm")
result.tofile(destination / "gtcrn-expected.pcm")
print(f"Wrote {len(source)} samples per file, 16kHz PCM16, fixed phrasebook recording only.")
