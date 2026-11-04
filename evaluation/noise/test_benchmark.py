"""Tests of the offline audio adapter, not proof of Android or ASR accuracy."""
import unittest
import numpy as np
from benchmark import StreamingEnhancer, si_sdr


class IdentitySpectrum:
    def __call__(self, spectrum):
        return spectrum


class StreamingAdapterTest(unittest.TestCase):
    def render(self, audio, block):
        enhancer = StreamingEnhancer(IdentitySpectrum())
        output = [enhancer.send(audio[i:i + block]) for i in range(0, len(audio), block)]
        output.append(enhancer.finish())
        self.assertEqual(0, len(enhancer.finish()))
        return np.concatenate(output)

    def test_overlap_add_keeps_first_and_last_sample_and_exact_length(self):
        for length in (1, 255, 256, 257, 511, 512, 16013):
            audio = np.random.default_rng(31).uniform(-0.5, 0.5, length).astype(np.float32)
            output = self.render(audio, 320)
            self.assertEqual(length, len(output))
            np.testing.assert_allclose(output, audio, atol=2e-7)

    def test_arbitrary_input_chunks_do_not_change_output(self):
        audio = np.random.default_rng(12).uniform(-0.5, 0.5, 16013).astype(np.float32)
        np.testing.assert_array_equal(self.render(audio, 137), self.render(audio, 960))

    def test_stream_emits_before_finish_with_only_one_hop_lookahead(self):
        enhancer = StreamingEnhancer(IdentitySpectrum())
        self.assertEqual(0, len(enhancer.send(np.ones(256, dtype=np.float32))))
        self.assertEqual(256, len(enhancer.send(np.ones(256, dtype=np.float32))))
        self.assertEqual(256, len(enhancer.finish()))
        with self.assertRaises(ValueError):
            enhancer.send(np.zeros(1, dtype=np.float32))

    def test_empty_stream_stays_empty(self):
        enhancer = StreamingEnhancer(IdentitySpectrum())
        self.assertEqual(0, len(enhancer.finish()))

    def test_frequency_processor_is_applied_not_bypassed(self):
        enhancer = StreamingEnhancer(lambda spectrum: np.zeros_like(spectrum))
        output = np.concatenate([enhancer.send(np.ones(800, dtype=np.float32)), enhancer.finish()])
        self.assertEqual(800, len(output))
        self.assertFalse(np.any(output))

    def test_scale_invariant_metric_is_not_mistaken_for_volume_retention(self):
        clean = np.sin(np.arange(1600) * 0.17)
        self.assertGreater(si_sdr(clean, clean * 0.01), 60)


if __name__ == "__main__":
    unittest.main()
