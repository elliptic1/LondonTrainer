#!/usr/bin/env python3
"""Generates app/src/main/assets/repertoire.json: a closed graph of London positions.

Every Black reply leads to a node with a White answer, so the app can't reach a
position it has no answer for. White plays the next London plan move unless
Stockfish says it fails; Black plays what club players really play (tools/freq.json,
from gen_frequencies.py), plus the thematic tests, then engine moves once games run out.

Usage: gen_repertoire.py [--nodes N] [--limit N]   (needs `chess`, `stockfish` on PATH)
"""
import argparse, collections, gzip, hashlib, json, os, pickle, sqlite3, sys, tempfile
import chess, chess.engine

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app/src/main/assets/repertoire.json")
LOCAL_FREQ = os.path.join(HERE, "freq.json")
FREQ = json.load(open(LOCAL_FREQ) if os.path.exists(LOCAL_FREQ) else gzip.open(os.path.join(HERE, "frequencies.json.gz"), "rt"))

TOLERANCE = 45    # cp a plan move may always trail the best move
LOOSE = 100       # ...or trail by this much if White still stands near equality: staying in the London beats engine points
BLACK_TOL = 60    # how far from Black's best an engine reply may be
TEST_TOL = 130    # same, for the thematic test moves
WON = 500         # stop drilling: the point is the setup, not converting
MAX_WHITE_MOVES = 24  # room to finish the setup after detours
MIN_GAMES = 40        # below this, a position's real game counts mean little
MIN_SHARE = 0.07      # a reply rarer than this isn't worth drilling...
FIRST_SHARE = 0.01    # ...except at move one, where the whole spread of openings matters
THREAT_GAP = 110      # cp a free move must be worth to Black to count as a threat (a tempo is ~40)

# The recipe, in order. A move stays pending while it's still legal.
PLAN = ["d2d4", "c1f4", "e2e3", "g1f3", "c2c3", "b1d2", "f1d3", "e1g1"]

PLAN_WHY = {
    "d2d4": "Start the London by claiming space in the center.",
    "c1f4": "The signature move: get the dark-squared bishop out before e3 shuts it in.",
    "e2e3": "Support d4 with a pawn and give your pieces more room to develop.",
    "g1f3": "Develop toward the center. The knight eyes e5 and gets you closer to castling.",
    "c2c3": "Support d4 with the c-pawn. This helps meet Black's ...c5 break without giving up the center.",
    "b1d2": "The knight goes to d2, not c3: c3 belongs to the pawn, and from d2 it backs up the e4 break and watches c4.",
    "f1d3": "Put the bishop on its best diagonal, aiming at h7 and contesting e4.",
    "e1g1": "King safe, rook on f1. The setup is complete.",
}

# Moves that test a London. Offered whenever they're legal and not a blunder.
TEST_MOVES = {"d8b6": "Qb6", "f6h5": "Nh5", "c7c5": "c5", "c6c5": "c5", "e7e5": "e5", "e6e5": "e5",
              "f8d6": "Bd6", "f6e4": "Ne4", "c8f5": "Bf5", "c8g4": "Bg4", "c5d4": "cxd4"}


# Practice-by-opening shortcuts: Black's moves, in order, to reach each one.
GROUPS = [
    ("Queen's pawn", "1...d5 and the classical setups", ["d7d5"]),
    ("The ...c5 strike", "1...d5, then hitting d4 at once", ["d7d5", "c7c5"]),
    ("Knight first", "1...Nf6 and flexible play", ["g8f6"]),
    ("King's Indian", "1...Nf6 and 2...g6", ["g8f6", "g7g6"]),
    ("French style", "1...e6", ["e7e6"]),
    ("Modern", "1...g6", ["g7g6"]),
    ("Old Indian", "1...d6", ["d7d6"]),
    ("Sicilian style", "1...c5", ["c7c5"]),
    ("Dutch", "1...f5", ["f7f5"]),
]


def width(white_moves):
    """Black replies explored after White's Nth move."""
    return 4 if white_moves <= 3 else 3 if white_moves <= 5 else 2 if white_moves <= 7 else 1


class Sf:
    def __init__(self, nodes, cache):
        self.e = chess.engine.SimpleEngine.popen_uci("stockfish")
        self.e.configure({"Threads": max(2, (os.cpu_count() or 4) - 2), "Hash": 512})
        self.limit = chess.engine.Limit(nodes=nodes)
        self.calls = self.hits = 0
        self.cache = sqlite3.connect(cache)
        self.cache.execute("CREATE TABLE IF NOT EXISTS analysis (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

    def top(self, board, n, root_moves=None):
        """[(move, white-POV cp, pv)], persisted after each engine call for interrupted builds."""
        key = json.dumps([board.fen(), n, self.limit.nodes, self.e.id.get("name"),
                          sorted(m.uci() for m in root_moves) if root_moves is not None else None])
        cached = self.cache.execute("SELECT value FROM analysis WHERE key = ?", (key,)).fetchone()
        if cached:
            self.hits += 1
            return [(chess.Move.from_uci(m), cp, [chess.Move.from_uci(u) for u in pv])
                    for m, cp, pv in json.loads(cached[0])]
        self.calls += 1
        infos = self.e.analyse(board, self.limit, multipv=n, root_moves=root_moves)
        result = [(i["pv"][0], i["score"].white().score(mate_score=10000), i["pv"]) for i in infos if "pv" in i]
        packed = json.dumps([(m.uci(), cp, [u.uci() for u in pv]) for m, cp, pv in result])
        self.cache.execute("INSERT OR REPLACE INTO analysis VALUES (?, ?)", (key, packed))
        self.cache.commit()
        return result

    def one(self, board, move):
        _, cp, pv = self.top(board, 1, [move])[0]
        return cp, pv

    def threat(self, board, best_cp):
        """What Black would do with a free move, as (san, uci), if that's worth real points."""
        if board.is_check():
            return None
        passed = chess.Board(board.fen())  # a flipped copy: engines can't take a null move
        passed.turn = chess.BLACK
        passed.ep_square = None
        lines = self.top(passed, 1)
        if not lines or best_cp - lines[0][1] < THREAT_GAP:
            return None
        return passed.san(lines[0][0]), lines[0][0].uci()

    def close(self):
        self.e.quit()
        self.cache.close()


def sound(loss, cp):
    return loss <= TOLERANCE or (loss <= LOOSE and cp >= -50)


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
        out.append(("c2c3", "Black is hitting d4 early, so brace it now with c3. Then continue developing behind your pawn center."))
    if board.piece_at(chess.G3) == chess.Piece.from_symbol("B") and board.piece_at(chess.H5) == chess.Piece.from_symbol("p"):
        out.append(("h2h3", "Black's h-pawn wants to kick or trap your bishop. h3 keeps a retreat open on h2."))
    if last and last.uci() == "d8b6" and not board.is_attacked_by(chess.WHITE, chess.B2):
        why = "Black's queen is hitting b2, which your bishop stopped guarding when it left c1. "
        out += [("d1b3", why + "Qb3 guards it and offers a queen trade: if Black takes, axb3 doubles your b-pawns but gives your rook a half-open a-file. If you take on b6 instead, axb6 doubles Black's b-pawns."),
                ("d1c1", why + "Qc1 defends quietly and keeps your queen on the board."),
                ("b2b3", why + "b3 removes the target altogether."),
                ("b1c3", why + "Nc3 leaves b2 as bait: if the queen grabs it, Rb1 or Nb5 starts chasing her.")]
    return [(u, w) for u, w in out if chess.Move.from_uci(u) in board.legal_moves]


def material(board):
    worth = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 0}
    return sum(worth[p.piece_type] * (1 if p.color else -1) for p in board.piece_map().values())


def gain(board, pv):
    """Material White really nets from the main line, so a plain trade isn't called a win."""
    after = board.copy(stack=False)
    for m in pv[:6]:
        after.push(m)
    return material(after) - material(board)


def hit_target(board, move):
    """A black piece the move attacks that's bigger than the attacker or undefended, as a name."""
    vals = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3, chess.ROOK: 5, chess.QUEEN: 9, chess.KING: 99}
    after = board.copy(stack=False)
    after.push(move)
    mine = vals[after.piece_type_at(move.to_square)]
    for s in sorted(after.attacks(move.to_square), key=lambda s: -vals.get(after.piece_type_at(s), 0)):
        p = after.piece_at(s)
        if p and p.color == chess.BLACK and p.piece_type != chess.KING and (vals[p.piece_type] > mine or not after.attackers(chess.BLACK, s)):
            return f"{chess.piece_name(p.piece_type)} on {chess.square_name(s)}"
    return None


def explain_engine(board, move, rejected, threat, pv):
    """Why White left the plan, from what the move actually does."""
    san = board.san(move)
    if board.is_castling(move):
        return "Castle queenside: king on c1, rook on d1. Your king leaves the center and your rook joins the game. The setup is complete.", "castle"
    if board.is_check():
        return f"Your king is in check. {san} gets out of check before you continue developing.", "check"
    last = board.peek() if board.move_stack else None
    if board.is_capture(move) and last and last.to_square == move.to_square:
        return f"Recapture with {san}. Take back first, then return to the plan.", "recapture"
    if board.is_capture(move):
        won = gain(board, pv)
        if won >= 1:
            prize = "a piece" if won >= 3 else "the exchange" if won == 2 else "a pawn"
            return f"{san} wins {prize}. Take what Black offers; the setup can wait a move.", "capture"
        return f"{san} simplifies. Make the trade now rather than let Black choose when to, then return to the plan.", "capture"
    frm = move.from_square
    if board.is_attacked_by(chess.BLACK, frm) and attacked_by_cheaper(board, frm) is not None:
        return f"Your {piece_name(board, frm)} is under attack, so move it first with {san}. The plan resumes next move.", "escape"
    for uci, (refute, _, _) in rejected.items():
        if refute:
            return f"The next plan move, {board.san(chess.Move.from_uci(uci))}, runs into ...{refute}. Play {san} first, then return to the plan.", "engine"
    if threat:
        if "x" in threat[0] or "+" in threat[0]:
            return f"Black is threatening ...{threat[0]}. {san} deals with it, then the plan can continue.", "threat"
        return f"Black wants to play ...{threat[0]} next. {san} gets in first, then the plan can continue.", "threat"
    target = hit_target(board, move)
    if target:
        return f"{san} hits the {target}. Black has to respond, so you lose no time on the plan.", "pressure"
    for uci, (_, loss, _) in rejected.items():
        return f"{san} first: the plan move {board.san(chess.Move.from_uci(uci))} gives away about {loss / 100:.1f} pawns right now.", "order"
    piece = piece_name(board, move.from_square)
    destination = chess.square_name(move.to_square)
    after = board.copy(stack=False)
    after.push(move)
    defended = [chess.square_name(q) for q in after.attacks(move.to_square)
                if after.piece_at(q) and after.piece_at(q).color == chess.WHITE
                and board.is_attacked_by(chess.BLACK, q)]
    if defended:
        return f"{san} brings your {piece} to {destination}, adding protection to {', '.join(defended)}. Keep those pieces covered while you develop.", "defend"
    if piece == "pawn":
        squares = ', '.join(chess.square_name(q) for q in after.attacks(move.to_square))
        return f"{san} takes space and controls {squares}. That pawn control makes room for your pieces before you return to developing.", "space"
    attacked = list(after.attacks(move.to_square))
    center = {chess.D4, chess.E4, chess.D5, chess.E5, chess.C4, chess.F4, chess.C5, chess.F5}
    targets = sorted(attacked, key=lambda q: (q not in center, abs(chess.square_rank(q) - 3), q))[:3]
    squares = ', '.join(chess.square_name(q) for q in targets)
    return f"{san} brings your {piece} to {destination}, where it controls {squares}. Keep those squares covered as you finish developing.", "develop"


def choose_white(board, sf):
    """Returns (move, tag, why, top lines, rejected plan moves, Black's threat)."""
    lines = sf.top(board, 6)
    best_move, best_cp, best_pv = lines[0]
    rejected = {}
    threat = sf.threat(board, best_cp)

    reactions = reactive(board)
    candidates = {chess.Move.from_uci(u) for u in PLAN if chess.Move.from_uci(u) in board.legal_moves}
    candidates.update(chess.Move.from_uci(u) for u, _ in reactions)
    scored = {m: (cp, pv) for m, cp, pv in lines}

    def score(move):
        if move not in scored:
            missing = sorted(candidates - scored.keys(), key=lambda m: m.uci())
            scored.update({m: (cp, pv) for m, cp, pv in sf.top(board, len(missing), missing)})
        return scored[move]

    for uci, why in reactions:
        m = chess.Move.from_uci(uci)
        cp, _ = score(m)
        if sound(best_cp - cp, cp):
            # A reactive answer must explain why blindly continuing any legal setup move fails.
            for plan in PLAN:
                candidate = chess.Move.from_uci(plan)
                if candidate not in board.legal_moves:
                    continue
                score_cp, pv = score(candidate)
                if sound(best_cp - score_cp, score_cp):
                    continue
                after = board.copy(); after.push(candidate)
                reply = pv[1] if len(pv) > 1 else None
                rejected[plan] = [after.san(reply) if reply else None, best_cp - score_cp,
                                  reply.uci() if reply else None]
            return m, "react", why, lines, rejected, threat

    for uci in PLAN:
        m = chess.Move.from_uci(uci)
        if m not in board.legal_moves:
            continue
        cp, pv = score(m)
        if sound(best_cp - cp, cp):
            return m, "plan", PLAN_WHY[uci], lines, rejected, threat
        refute = refute_uci = None
        if len(pv) > 1:
            after = board.copy(); after.push(m)
            refute, refute_uci = after.san(pv[1]), pv[1].uci()
        rejected[uci] = [refute, best_cp - cp, refute_uci]

    why, tag = explain_engine(board, best_move, rejected, threat, best_pv)
    return best_move, tag, why, lines, rejected, threat


def real_replies(board, limit, share):
    """The moves club players chose here, as [(move, percent)], or None if too few played it."""
    seen = FREQ.get(board.epd())
    total = sum(seen.values()) if seen else 0
    if total < MIN_GAMES:
        return None
    ranked = sorted(seen.items(), key=lambda kv: -kv[1])
    return [(chess.Move.from_uci(u), max(1, round(100 * n / total))) for u, n in ranked[:limit] if n / total >= share]


def black_replies(board, sf, white_moves, lines=None, required=()):
    first = len(board.move_stack) == 1
    real = real_replies(board, 8 if first else width(white_moves), FIRST_SHARE if first else MIN_SHARE)
    def include_required(picks):
        observed = FREQ.get(board.epd(), {})
        total = sum(observed.values())
        for uci in sorted(required):
            m = chess.Move.from_uci(uci)
            if m in board.legal_moves and m not in picks:
                picks[m] = max(1, round(100 * observed.get(uci, 0) / total)) if total else 1
        return picks

    if first:
        return list(include_required(collections.OrderedDict(real or [])).items())
    lines = lines if lines is not None else sf.top(board, 8)
    best = lines[0][1]

    # cp is White POV, so bigger means worse for Black.
    picks = collections.OrderedDict(real or [])
    if not picks:
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
            picks[m] = 8 if real else 3  # shown now and then, even if the crowd rarely plays it
            added += 1
    return list(include_required(picks).items())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nodes", type=int, default=60_000)
    ap.add_argument("--limit", type=int, default=0, help="stop after N positions (smoke test)")
    token = hashlib.sha256(HERE.encode()).hexdigest()[:12]
    ap.add_argument("--checkpoint", default=os.path.join(tempfile.gettempdir(), f"londontrainer-{token}.pkl"))
    ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), f"londontrainer-{token}.sqlite"))
    args = ap.parse_args()

    sf = Sf(args.nodes, args.cache)
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
    signature = (args.nodes, args.limit, hashlib.sha256(open(__file__, "rb").read()).hexdigest(),
                 hashlib.sha256(open(os.path.join(HERE, "frequency-source.json"), "rb").read()).hexdigest())
    if os.path.exists(args.checkpoint):
        # Only this local generator writes this trusted checkpoint; never load external pickle files.
        with open(args.checkpoint, "rb") as saved:
            state = pickle.load(saved)
        if state[0] == signature:
            _, ids, queue, nodes, tags, review = state
            print(f"Resuming {len(nodes)} positions, {len(queue)} pending", file=sys.stderr, flush=True)

    def checkpoint():
        with open(args.checkpoint + ".tmp", "wb") as saved:
            pickle.dump((signature, ids, queue, nodes, tags, review), saved, protocol=pickle.HIGHEST_PROTOCOL)
        os.replace(args.checkpoint + ".tmp", args.checkpoint)

    while queue:
        board, white_moves = queue.popleft()
        nid = ids[(board._transposition_key(), white_moves)]
        move, tag, why, lines, rejected, threat = choose_white(board, sf)
        tags[tag] += 1
        node = {"u": move.uci(), "s": board.san(move), "t": tag, "w": why, "b": []}
        chosen_cp = next((cp for m, cp, _ in lines if m == move), lines[0][1])
        alts = [m.uci() for m, cp, _ in lines if m != move and chosen_cp - cp <= TOLERANCE]
        if alts:
            node["a"] = alts
        if rejected:
            node["r"] = rejected
        if threat:
            node["h"] = threat
        if tag not in ("plan", "react"):
            review.append((tag, " ".join(b.uci() for b in board.move_stack), node["s"], why))

        after = board.copy()
        after.push(move)
        white_moves += 1
        castled = any(m.uci() in ("e1g1", "e1c1") for m in after.move_stack)
        over = after.is_game_over() or white_moves >= MAX_WHITE_MOVES or castled
        black_lines = None
        if not over:
            black_lines = sf.top(after, 8)
            over = black_lines[0][1] >= WON
        if not over:
            played_black = [m.uci() for m in after.move_stack[1::2]]
            required = {prefix[len(played_black)] for _, _, prefix in GROUPS
                        if len(prefix) > len(played_black) and prefix[:len(played_black)] == played_black}
            for reply, weight in black_replies(after, sf, white_moves, black_lines, required):
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
        if len(nodes) % 25 == 0:
            checkpoint()
            print(f"{len(nodes)} done, {len(ids)} seen, queue {len(queue)}, {dict(tags)}", file=sys.stderr, flush=True)

    checkpoint()

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    groups = [{"n": n, "d": d, "p": p} for n, d, p in GROUPS]
    source = json.load(open(os.path.join(HERE, "frequency-source.json")))
    json.dump({"nodes": nodes, "groups": groups, "source": source,
               "engine": {"name": sf.e.id.get("name", "Stockfish"), "nodes_per_analysis": args.nodes, "max_white_moves": MAX_WHITE_MOVES}},
              open(OUT + ".tmp", "w"), separators=(",", ":"))
    os.replace(OUT + ".tmp", OUT)
    json.dump(review, open(os.path.join(HERE, "review.json"), "w"), indent=0)
    ends = sum(1 for n in nodes if not n["b"])
    print(f"{len(nodes)} positions, {ends} ends, {os.path.getsize(OUT)//1024} KB, {dict(tags)}, {sf.calls} engine calls")


if __name__ == "__main__":
    main()
