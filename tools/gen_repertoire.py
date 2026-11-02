#!/usr/bin/env python3
"""Generates app/src/main/assets/repertoire.json: a closed graph of London positions.

Every Black reply leads to a node with a White answer, so the app can't reach a
position it has no answer for. White plays the next London plan move unless
Stockfish says it fails; Black plays a curated spread plus engine and test moves.

Usage: gen_repertoire.py [--nodes N] [--limit N]   (needs `chess`, `stockfish` on PATH)
"""
import argparse, collections, json, os, sys
import chess, chess.engine

OUT = os.path.join(os.path.dirname(__file__), "..", "app/src/main/assets/repertoire.json")

TOLERANCE = 45    # cp a plan move may always trail the best move
LOOSE = 100       # ...or trail by this much if White still stands better: staying in the London beats engine points
BLACK_TOL = 60    # how far from Black's best an engine reply may be
TEST_TOL = 130    # same, for the thematic test moves
WON = 500         # stop drilling: the point is the setup, not converting
MAX_WHITE_MOVES = 12

# The recipe, in order. A move stays pending while it's still legal.
PLAN = ["d2d4", "c1f4", "e2e3", "g1f3", "c2c3", "b1d2", "f1d3", "e1g1"]

PLAN_WHY = {
    "d2d4": "Claim the center. Every London starts with this pawn.",
    "c1f4": "The signature move: get the dark-squared bishop out before e3 shuts it in.",
    "e2e3": "Back up d4 and open a path for the f1 bishop. The f4 bishop is already outside the chain, so nothing gets trapped.",
    "g1f3": "Develop toward the center. The knight eyes e5 and gets you closer to castling.",
    "c2c3": "Brace d4. With c3, d4 and e3 in place, a ...c5 break can't shake your center.",
    "b1d2": "The knight goes to d2, not c3: c3 belongs to the pawn, and from d2 it supports e4 and fights for e4 and c4.",
    "f1d3": "Put the bishop on its best diagonal, aiming at h7 and contesting e4.",
    "e1g1": "King safe, rook connected. The setup is complete.",
}

# Black's first moves, weighted by a rough sense of club play (no game data behind these).
FIRST_REPLIES = [("d7d5", 8), ("g8f6", 7), ("e7e6", 2), ("d7d6", 2), ("g7g6", 2), ("f7f5", 1), ("c7c5", 1)]

# Moves that test a London. Offered whenever they're legal and not a blunder.
TEST_MOVES = {"d8b6": "Qb6", "f6h5": "Nh5", "c7c5": "c5", "c6c5": "c5", "e7e5": "e5", "e6e5": "e5",
              "f8d6": "Bd6", "f6e4": "Ne4", "c8f5": "Bf5", "c8g4": "Bg4", "c5d4": "cxd4"}


def width(white_moves):
    """Black replies explored after White's Nth move."""
    return 3 if white_moves <= 3 else 2 if white_moves <= 5 else 1


class Sf:
    def __init__(self, nodes):
        self.e = chess.engine.SimpleEngine.popen_uci("stockfish")
        self.e.configure({"Threads": max(2, (os.cpu_count() or 4) - 2), "Hash": 512})
        self.limit = chess.engine.Limit(nodes=nodes)
        self.calls = 0

    def top(self, board, n):
        """[(move, white-POV cp, pv)], best first for the side to move."""
        self.calls += 1
        infos = self.e.analyse(board, self.limit, multipv=n)
        return [(i["pv"][0], i["score"].white().score(mate_score=10000), i["pv"]) for i in infos if "pv" in i]

    def one(self, board, move):
        self.calls += 1
        i = self.e.analyse(board, self.limit, root_moves=[move])
        return i["score"].white().score(mate_score=10000), i.get("pv", [move])

    def close(self):
        self.e.quit()


def sound(loss, cp):
    return loss <= TOLERANCE or (loss <= LOOSE and cp >= 10)


def piece_name(board, square):
    return chess.piece_name(board.piece_type_at(square))


def attacked_by_cheaper(board, square):
    """A black attacker worth less than the white piece on `square`, or None."""
    vals = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 99}
    mine = board.piece_type_at(square)
    cheapest = [s for s in board.attackers(chess.BLACK, square) if vals[board.piece_type_at(s)] < vals[mine]]
    return cheapest[0] if cheapest else None


def reactive(board):
    """Standard London responses to Black's usual tests: [(uci, why)] in preference order."""
    out = []
    last = board.peek() if board.move_stack else None
    bishop_on_f4 = board.piece_at(chess.F4) == chess.Piece.from_symbol("B")
    if bishop_on_f4 and board.attackers(chess.BLACK, chess.F4):
        attacker = piece_name(board, next(iter(board.attackers(chess.BLACK, chess.F4))))
        out.append(("f4g3", f"Black's {attacker} is after your bishop. Step back to g3: it stays on the same diagonal and stays your best piece."))
    if last and last.to_square == chess.D4 and board.piece_at(chess.D4).color == chess.BLACK:
        why = "Black traded on d4. Recapture with a pawn so you keep a pawn in the center."
        out += [("e3d4", why), ("c3d4", why)]
    if board.piece_at(chess.C5) == chess.Piece.from_symbol("p") and board.piece_at(chess.C2) == chess.Piece.from_symbol("P") and board.piece_at(chess.E2):
        out.append(("c2c3", "Black is hitting d4 early, so brace it now with c3. Bf4 still comes next."))
    if board.piece_at(chess.G3) == chess.Piece.from_symbol("B") and board.piece_at(chess.H5) == chess.Piece.from_symbol("p"):
        out.append(("h2h3", "Black's h-pawn wants to kick or trap your bishop. h3 keeps a retreat open on h2."))
    if last and last.uci() == "d8b6" and not board.is_attacked_by(chess.WHITE, chess.B2):
        why = "Black's queen is hitting b2, which your bishop stopped guarding when it left c1. "
        out += [("d1b3", why + "Qb3 offers a queen trade that leaves Black with doubled pawns if they take."),
                ("d1c1", why + "Qc1 defends quietly and keeps your queen on the board."),
                ("b2b3", why + "b3 removes the target altogether."),
                ("b1c3", why + "Nc3 lets the pawn go only if Black wants to get their queen in trouble.")]
    return [(u, w) for u, w in out if chess.Move.from_uci(u) in board.legal_moves]


def explain_engine(board, move, rejected):
    """Why White left the plan, from what the move actually does."""
    san = board.san(move)
    last = board.peek() if board.move_stack else None
    if board.is_capture(move) and last and last.to_square == move.to_square:
        return f"Recapture with {san}. Take back first, then return to the plan.", "recapture"
    if board.is_capture(move):
        return f"{san} wins something here. Take what Black offers; the setup can wait a move.", "capture"
    frm = move.from_square
    if board.is_attacked_by(chess.BLACK, frm) and attacked_by_cheaper(board, frm) is not None:
        return f"Your {piece_name(board, frm)} is under attack, so move it first with {san}. The plan resumes next move.", "escape"
    for uci, (refute, _) in rejected.items():
        if refute:
            return f"The next plan move, {board.san(chess.Move.from_uci(uci))}, runs into ...{refute}. Play {san} first, then return to the plan.", "engine"
    return f"{san} is the accurate move here. The plan moves are worse right now, so handle this first.", "engine"


def choose_white(board, sf):
    """Returns (move, tag, why, top lines, rejected plan moves)."""
    lines = sf.top(board, 6)
    best_move, best_cp, _ = lines[0]
    rejected = {}

    def score(move):
        for m, cp, pv in lines:
            if m == move:
                return cp, pv
        return sf.one(board, move)

    for uci, why in reactive(board):
        m = chess.Move.from_uci(uci)
        cp, _ = score(m)
        if sound(best_cp - cp, cp):
            return m, "react", why, lines, rejected

    for uci in PLAN:
        m = chess.Move.from_uci(uci)
        if m not in board.legal_moves:
            continue
        cp, pv = score(m)
        if sound(best_cp - cp, cp):
            return m, "plan", PLAN_WHY[uci], lines, rejected
        refute = None
        if len(pv) > 1:
            after = board.copy(); after.push(m)
            # Name it only if it's a real threat: a quiet move or a plain trade on the same square isn't.
            if after.gives_check(pv[1]) or (after.is_capture(pv[1]) and pv[1].to_square != m.to_square):
                refute = after.san(pv[1])
        rejected[uci] = [refute, best_cp - cp]

    why, tag = explain_engine(board, best_move, rejected)
    return best_move, tag, why, lines, rejected


def black_replies(board, sf, white_moves):
    if len(board.move_stack) == 1:
        return [(chess.Move.from_uci(u), w) for u, w in FIRST_REPLIES]
    lines = sf.top(board, 8)
    best = lines[0][1]

    # cp is White POV, so bigger means worse for Black.
    picks = collections.OrderedDict()
    for rank, (m, cp, _) in enumerate(lines[:width(white_moves)]):
        if cp - best <= BLACK_TOL:
            picks[m] = max(1, 5 - rank)
    tests = [m for m in board.legal_moves if m.uci() in TEST_MOVES and m not in picks]
    added = 0
    for m in tests:
        if added == (1 if white_moves <= 5 else 0):
            break
        cp, _ = next(((c, p) for mv, c, p in lines if mv == m), (None, None))
        if cp is None:
            cp, _ = sf.one(board, m)
        if cp - best <= TEST_TOL:
            picks[m] = 3
            added += 1
    return list(picks.items())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nodes", type=int, default=60_000)
    ap.add_argument("--limit", type=int, default=0, help="stop after N positions (smoke test)")
    args = ap.parse_args()

    sf = Sf(args.nodes)
    try:
        build(sf, args)
    finally:
        sf.close()


def build(sf, args):
    board = chess.Board()
    ids = {(board._transposition_key(), 0): 0}  # keyed by move number too, so lines can never loop
    queue = collections.deque([(board, 0)])
    nodes = []
    tags = collections.Counter()
    review = []

    while queue:
        board, white_moves = queue.popleft()
        nid = ids[(board._transposition_key(), white_moves)]
        move, tag, why, lines, rejected = choose_white(board, sf)
        tags[tag] += 1
        node = {"u": move.uci(), "s": board.san(move), "t": tag, "w": why, "b": []}
        chosen_cp = next((cp for m, cp, _ in lines if m == move), lines[0][1])
        alts = [m.uci() for m, cp, _ in lines if m != move and chosen_cp - cp <= TOLERANCE]
        if alts:
            node["a"] = alts
        if rejected:
            node["r"] = rejected
        if tag not in ("plan", "react"):
            review.append((tag, " ".join(b.uci() for b in board.move_stack), node["s"], why))

        after = board.copy()
        after.push(move)
        white_moves += 1
        castled = any(m.uci() == "e1g1" for m in after.move_stack)
        over = after.is_game_over() or white_moves >= MAX_WHITE_MOVES or castled
        if not over:
            top_cp = sf.top(after, 1)[0][1]
            over = top_cp >= WON
        if not over:
            for reply, weight in black_replies(after, sf, white_moves):
                nxt = after.copy()
                nxt.push(reply)
                if nxt.is_game_over():
                    continue
                k = (nxt._transposition_key(), white_moves)
                if k not in ids:
                    if args.limit and len(ids) >= args.limit:
                        continue
                    ids[k] = len(ids)
                    queue.append((nxt, white_moves))
                node["b"].append([reply.uci(), after.san(reply), weight, ids[k]])
        while len(nodes) <= nid:
            nodes.append(None)
        nodes[nid] = node
        if len(ids) % 50 == 0:
            print(f"{len(nodes)} done, {len(ids)} seen, queue {len(queue)}, {dict(tags)}", file=sys.stderr, flush=True)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump({"nodes": nodes}, open(OUT, "w"), separators=(",", ":"))
    json.dump(review, open(os.path.join(os.path.dirname(__file__), "review.json"), "w"), indent=0)
    ends = sum(1 for n in nodes if not n["b"])
    print(f"{len(nodes)} positions, {ends} ends, {os.path.getsize(OUT)//1024} KB, {dict(tags)}, {sf.calls} engine calls")


if __name__ == "__main__":
    main()
