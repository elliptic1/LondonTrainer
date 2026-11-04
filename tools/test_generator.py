"""Regression tests for resumable engine analysis and plan-move scoring."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import chess
import chess.engine

spec = importlib.util.spec_from_file_location('generator', Path(__file__).with_name('gen_repertoire.py'))
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)


class GeneratorTest(unittest.TestCase):
    def test_analysis_survives_an_engine_restart_and_separates_restricted_searches(self):
        board = chess.Board()
        move = chess.Move.from_uci('d2d4')
        engine = MagicMock()
        engine.id = {'name': 'test-engine'}
        engine.analyse.return_value = [{'pv': [move], 'score': chess.engine.PovScore(chess.engine.Cp(20), chess.WHITE)}]
        with tempfile.TemporaryDirectory() as directory, patch.object(chess.engine.SimpleEngine, 'popen_uci', return_value=engine):
            cache = str(Path(directory) / 'cache.sqlite')
            first = gen.Sf(60000, cache)
            expected = first.top(board, 1)
            first.close()
            resumed = gen.Sf(60000, cache)
            self.assertEqual(expected, resumed.top(board, 1))
            self.assertEqual(0, resumed.calls)
            self.assertEqual(1, resumed.hits)
            resumed.top(board, 1, [move])
            self.assertEqual(1, resumed.calls)
            resumed.close()
            self.assertEqual(2, engine.analyse.call_count)

    def test_missing_plan_candidates_are_analyzed_together(self):
        board = chess.Board()
        board.push_uci('d2d4'); board.push_uci('d7d5')
        engine = MagicMock()
        best = chess.Move.from_uci('b1c3')
        def top(position, n, root_moves=None):
            if root_moves is None: return [(best, 200, [best])]
            return [(m, 180, [m]) for m in root_moves]
        engine.top.side_effect = top
        engine.threat.return_value = None
        move, tag, *_ = gen.choose_white(board, engine)
        self.assertEqual('c1f4', move.uci())
        self.assertEqual('plan', tag)
        restricted = [call for call in engine.top.call_args_list if len(call.args) == 3]
        self.assertEqual(1, len(restricted))
        self.assertGreaterEqual(len(restricted[0].args[2]), 3)

    def test_practical_setup_tolerance_keeps_near_equal_moves_but_rejects_real_losses(self):
        self.assertTrue(gen.sound(90, -40))
        self.assertFalse(gen.sound(140, -10))
        self.assertFalse(gen.sound(90, -100))

    def test_required_opening_is_kept_below_the_frequency_cutoff(self):
        board = chess.Board(); board.push_uci('d2d4')
        with patch.object(gen, 'real_replies', return_value=[(chess.Move.from_uci('d7d5'), 90)]):
            replies = gen.black_replies(board, MagicMock(), 1, required={'c7c5'})
        self.assertTrue(any(m.uci() == 'c7c5' and weight > 0 for m, weight in replies))

    def test_reactive_retreat_records_a_wrong_plan_punishment(self):
        board = chess.Board()
        for u in ['d2d4', 'g8f6', 'c1f4', 'f6h5']: board.push_uci(u)
        retreat = chess.Move.from_uci('f4g3'); take = chess.Move.from_uci('h5f4')
        engine = MagicMock()
        def top(position, n, root_moves=None):
            if root_moves is None: return [(retreat, 20, [retreat])]
            return [(m, -200, [m, take]) for m in root_moves]
        engine.top.side_effect = top
        engine.threat.return_value = ('Nxf4', 'h5f4')
        move, tag, why, lines, rejected, threat = gen.choose_white(board, engine)
        self.assertEqual('f4g3', move.uci())
        self.assertEqual('react', tag)
        self.assertEqual('h5f4', rejected['g1f3'][2])


if __name__ == '__main__': unittest.main()
