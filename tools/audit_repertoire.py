"""Independently validate generated moves and explanations with python-chess."""
import collections
import json
from pathlib import Path
import chess

root = Path(__file__).resolve().parent.parent
rep = json.loads((root / 'app/src/main/assets/repertoire.json').read_text())
nodes = rep['nodes']
boards = [None] * len(nodes)
boards[0] = chess.Board()
odds = [0.0] * len(nodes)
odds[0] = 1.0
ends = castles = winning_ends = 0
end_reasons = collections.Counter()
answers = collections.defaultdict(set)
terminal_odds = 0.0
castle_odds = 0.0
max_white_moves = 0
for i, n in enumerate(nodes):
    b = boards[i]
    assert b is not None, ('unreachable', i)
    answers[b.epd()].add(n['u'])
    move = chess.Move.from_uci(n['u'])
    assert move in b.legal_moves, ('illegal White move', i, n['u'])
    assert b.san(move) == n['s'], ('wrong SAN', i)
    for u in n.get('a', []):
        assert chess.Move.from_uci(u) in b.legal_moves, ('illegal alternative', i, u)
    for u, (san, loss, punishment) in n.get('r', {}).items():
        attempt = chess.Move.from_uci(u)
        assert attempt in b.legal_moves, ('illegal rejected move', i, u)
        if punishment:
            after = b.copy(); after.push(attempt)
            answer = chess.Move.from_uci(punishment)
            assert answer in after.legal_moves and after.san(answer) == san, ('invalid punishment', i, u)
    if n.get('h'):
        passed = b.copy(); passed.turn = chess.BLACK; passed.ep_square = None
        threat = chess.Move.from_uci(n['h'][1])
        assert threat in passed.legal_moves and passed.san(threat) == n['h'][0], ('invalid threat', i)
    assert 'most accurate move' not in n['w'], ('filler', i)
    after = b.copy(); after.push(move)
    if not n['b']:
        ends += 1
        castled = any(m.uci() in ('e1g1', 'e1c1') for m in after.move_stack)
        castles += castled
        terminal_odds += odds[i]
        reason = 'castled' if castled else 'move limit' if (len(after.move_stack) + 1) // 2 == rep.get('engine', {}).get('max_white_moves', 24) else 'game over' if after.is_game_over() else 'decisive advantage'
        end_reasons[reason] += 1
        castle_odds += odds[i] if castled else 0
        max_white_moves = max(max_white_moves, (len(after.move_stack) + 1) // 2)
    total = sum(r[2] for r in n['b'])
    for u, san, weight, child in n['b']:
        reply = chess.Move.from_uci(u)
        assert reply in after.legal_moves and after.san(reply) == san, ('invalid Black move', i, u)
        assert child > i and weight > 0, ('bad edge', i, child, weight)
        nxt = after.copy(); nxt.push(reply)
        if boards[child] is None: boards[child] = nxt
        else: assert boards[child]._transposition_key() == nxt._transposition_key(), ('inconsistent transposition', child)
        odds[child] += odds[i] * weight / total
for group in rep['groups']:
    i = 0
    for move in group['p']:
        i = next(r[3] for r in nodes[i]['b'] if r[0] == move)
assert abs(terminal_odds - 1) < 1e-8, terminal_odds
assert castle_odds > .9, castle_odds
print(json.dumps({'positions': len(nodes), 'unique_positions': len({b.epd() for b in boards}),
                  'terminal_nodes': ends, 'castled_terminal_nodes': castles,
                  'weighted_castling_share': castle_odds, 'max_white_moves': max_white_moves,
                  'end_reasons': dict(end_reasons), 'positions_with_multiple_answers': sum(len(v)>1 for v in answers.values()),
                  'explanations': dict(collections.Counter(n['t'] for n in nodes))}, indent=2))
