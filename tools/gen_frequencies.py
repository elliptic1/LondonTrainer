#!/usr/bin/env python3
"""Counts what club players really answer against the London, from the Lichess database (CC0).

Reads PGN on stdin and writes tools/freq.json: {position: {black move: games}}. Nothing is stored
but the counts, so a month of games can be streamed straight from database.lichess.org:

    curl -s https://database.lichess.org/standard/lichess_db_standard_rated_2017-01.pgn.zst \
      | zstd -dc | python gen_frequencies.py     (needs `chess`)
"""
import argparse, collections, gzip, hashlib, json, os, re, sys
import chess

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "freq.json")

ELO = range(1000, 2201)   # club players, not beginners or masters
MIN_SECONDS = 180         # no bullet: reflexes aren't preparation
MAX_PLY = 32              # room for a full setup with detours
PRUNE_EVERY = 40_000      # one-off positions would fill memory and say nothing

COMMENT = re.compile(rb"\{[^}]*\}")
BLACK_NUMBER = re.compile(rb"\d+\.\.\.")
NUMBER = re.compile(rb"\d+\.")
LONDON = re.compile(rb"^1\. d4 \S+ (?:2\. \S+ \S+ ){0,3}\d\. Bf4")


def num(header):
    try:
        return int(header.split(b'"')[1])
    except (IndexError, ValueError):
        return -1


def count(moves, counts):
    board = chess.Board()
    for san in moves[:MAX_PLY]:
        try:
            move = board.parse_san(san)
        except ValueError:
            return
        if not board.turn:
            counts[board.epd()][move.uci()] += 1
        board.push(move)


def prune(counts):
    for k in [k for k, v in counts.items() if sum(v.values()) < 2]:
        del counts[k]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-london", type=int, default=100_000, help="stop after this many eligible London games; 0 reads to EOF")
    ap.add_argument("--source", default="https://database.lichess.org/standard/lichess_db_standard_rated_2017-01.pgn.zst")
    args = ap.parse_args()
    counts = collections.defaultdict(collections.Counter)
    games = used = 0
    white = black = base = -1
    for line in sys.stdin.buffer:
        if line.startswith(b"[WhiteElo"):
            white = num(line)
        elif line.startswith(b"[BlackElo"):
            black = num(line)
        elif line.startswith(b"[TimeControl"):
            base = num(line.replace(b"+", b'"'))
        elif line.startswith(b"1. "):
            games += 1
            if white in ELO and black in ELO and base >= MIN_SECONDS and line.startswith(b"1. d4"):
                text = NUMBER.sub(b"", BLACK_NUMBER.sub(b"", COMMENT.sub(b"", line))) if b"{" in line else None
                # Clean the text only if needed; the prefilter works on the plain form.
                plain = line if text is None else b" ".join(_renumber(text))
                if LONDON.match(plain):
                    moves = (NUMBER.sub(b"", plain).split() if text is None else text.split())
                    count([m.decode() for m in moves if m not in (b"1-0", b"0-1", b"1/2-1/2", b"*")], counts)
                    used += 1
                    if used % PRUNE_EVERY == 0:
                        prune(counts)
                        print(f"{games} games read, {used} London, {len(counts)} positions", file=sys.stderr, flush=True)
            white = black = base = -1
            if args.max_london and used >= args.max_london:
                break
    prune(counts)
    payload = json.dumps(counts, separators=(",", ":")).encode()
    with open(OUT, "wb") as out:
        out.write(payload)
    with open(os.path.join(os.path.dirname(OUT), "frequencies.json.gz"), "wb") as out:
        out.write(gzip.compress(payload, mtime=0))
    metadata = {"source": args.source, "license": "CC0", "games_read": games,
                "london_games": used, "rating_min": ELO.start, "rating_max": ELO.stop - 1,
                "base_seconds_min": MIN_SECONDS, "max_ply": MAX_PLY,
                "positions_retained": len(counts), "sha256": hashlib.sha256(payload).hexdigest(),
                "sample": "stream prefix" if args.max_london and used >= args.max_london else "through supplied EOF"}
    with open(os.path.join(os.path.dirname(OUT), "frequency-source.json"), "w") as out:
        json.dump(metadata, out, indent=2)
        out.write("\n")
    print(f"{games} games read, {used} London, {len(counts)} positions, {os.path.getsize(OUT)//1024} KB")


def _renumber(text):
    """Puts annotated movetext back into the plain `1. d4 d5 2. Bf4` form."""
    moves = text.split()
    for i in range(0, len(moves), 2):
        yield b"%d." % (i // 2 + 1)
        yield from moves[i:i + 2]


if __name__ == "__main__":
    main()
