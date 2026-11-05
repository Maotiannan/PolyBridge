import unittest
import numpy as np
from short_clips import mix, schedule


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


if __name__ == '__main__':
    unittest.main()
