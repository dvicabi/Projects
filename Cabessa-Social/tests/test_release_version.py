"""Regression: the removed updater is not reintroduced by an old upstream test."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseVersionTests(unittest.TestCase):
    def test_external_updater_remains_removed(self):
        source = ROOT / 'extensions/extension/src/main/java/com/feurstagram/extension'
        self.assertFalse((source / 'UpdateChecker.java').exists())
        self.assertFalse(any('UpdateChecker' in file.read_text(encoding='utf-8')
                             for file in source.glob('*.java')))


if __name__ == '__main__':
    unittest.main()
