import unittest
import hashlib
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import wave
import numpy as np
from short_clips import generate, mix, schedule


class ShortClipsTest(unittest.TestCase):
    def test_schedule_retains_every_turn_and_rejects_over_twenty_seconds(self):
        voice = np.ones(1600, dtype=np.float32) * .1
        clean, spans = schedule([(1.0, voice), (4.0, voice)], 18)
        self.assertEqual(18 * 16000, len(clean))
        np.testing.assert_array_equal(voice, clean[16000:17600])
        self.assertEqual([(16000, 17600), (64000, 65600)], spans)
        with self.assertRaises(ValueError):
            schedule([(19.95, voice)], 20)
        with self.assertRaises(ValueError):
            schedule([], 21)
        with self.assertRaises(ValueError):
            schedule([(1.0, voice), (1.05, voice)], 18)

    def test_snr_uses_speech_regions_and_prevents_clipping_without_changing_ratio(self):
        rng = np.random.default_rng(8)
        clean, spans = schedule([(1, rng.uniform(-.9, .9, 32000))], 5)
        noise = rng.uniform(-1, 1, len(clean))
        mixed, reference, background = mix(clean, noise, spans, 5)
        self.assertLessEqual(np.max(np.abs(mixed)), .95 + 1e-7)
        np.testing.assert_allclose(mixed, reference + background, atol=1e-7)
        sl = slice(*spans[0])
        ratio = 10 * np.log10(np.mean(reference[sl] ** 2) / np.mean(background[sl] ** 2))
        self.assertAlmostEqual(5, ratio, places=5)
        with self.assertRaises(ValueError):
            mix(clean, np.zeros_like(clean), spans, 5)

    def test_approved_clips_are_reproducible_and_match_the_manifest(self):
        root = Path(__file__).resolve().parents[2]
        fixtures = root / "evaluation/noise/fixtures/short"
        expected = json.loads((fixtures / "manifest.json").read_text(encoding="utf-8"))
        with TemporaryDirectory() as directory:
            actual = generate(root, Path(directory))
            self.assertEqual(expected, actual)
        self.assertEqual(10, len(expected["clips"]))
        for clip in expected["clips"]:
            for name, sha in [(clip["file"], clip["sha256"])] + (
                    [(clip["reference"], clip["referenceSha256"])] if "reference" in clip else []):
                path = fixtures / name
                self.assertEqual(sha, hashlib.sha256(path.read_bytes()).hexdigest())
                with wave.open(str(path), "rb") as reader:
                    self.assertEqual((1, 2, 16000, clip["samples"]),
                                     (reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getnframes()))
                    self.assertLessEqual(reader.getnframes() / reader.getframerate(), 20)


if __name__ == '__main__':
    unittest.main()
