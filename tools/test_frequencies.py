"""Parser regressions: run with the same isolated venv used for repertoire generation."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("frequencies", Path(__file__).with_name("gen_frequencies.py"))
freq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(freq)


class FrequencyTest(unittest.TestCase):
    def test_annotated_and_plain_games_count_but_bullet_and_outside_ratings_do_not(self):
        def game(moves, white=1500, black=1800, time="600+5"):
            return f'[WhiteElo "{white}"]\n[BlackElo "{black}"]\n[TimeControl "{time}"]\n\n{moves}\n\n'
        plain = '1. d4 d5 2. Bf4 Nf6 3. e3 e6 1-0'
        annotated = '1. d4 { [%eval 0.1] } 1... d5 2. Bf4 Nf6 3. e3 e6 1-0'
        pgn = (game(plain) + game(annotated) + game(plain, time="60+0") + game(plain, white=2500)).encode()
        with tempfile.TemporaryDirectory() as directory:
            out = str(Path(directory) / "freq.json")
            with patch.object(freq, "OUT", out), patch.object(sys, "stdin", io.TextIOWrapper(io.BytesIO(pgn))), patch.object(sys, "argv", ["test", "--max-london", "0"]), contextlib.redirect_stdout(io.StringIO()):
                freq.main()
            counts = json.loads(Path(out).read_text())
            b = freq.chess.Board()
            b.push_uci("d2d4")
            self.assertEqual({"d7d5": 2}, counts[b.epd()])
            metadata = json.loads((Path(directory) / "frequency-source.json").read_text())
            self.assertEqual(4, metadata["games_read"])
            self.assertEqual(2, metadata["london_games"])
            self.assertTrue((Path(directory) / "frequencies.json.gz").exists())

    def test_bad_san_does_not_crash_the_stream(self):
        counts = freq.collections.defaultdict(freq.collections.Counter)
        freq.count(["d4", "d5", "nonsense"], counts)
        self.assertEqual(1, sum(sum(c.values()) for c in counts.values()))


if __name__ == "__main__":
    unittest.main()
