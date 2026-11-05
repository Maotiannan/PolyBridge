"""Static guards for CI bootstrap; actual runner execution remains the acceptance test."""
from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/android.yml"


class AndroidWorkflowTest(unittest.TestCase):
    def test_sdk_bootstrap_precedes_package_installation(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        setup = re.search(r"uses: android-actions/setup-android@[a-f0-9]{40}", text)
        self.assertIsNotNone(setup, "CI must install Android CLI explicitly at a pinned revision")
        self.assertLess(text.index("uses: actions/setup-java@"), setup.start())
        self.assertLess(setup.start(), text.index("run: sdkmanager "))

    def test_runner_does_not_silently_change_operating_system(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("runs-on: ubuntu-24.04", text)
        self.assertNotIn("runs-on: ubuntu-latest", text)


if __name__ == "__main__":
    unittest.main()
