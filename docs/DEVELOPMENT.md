# Building and refreshing London Trainer

Use JDK 17 or newer and Android SDK 36. Set `sdk.dir` in the gitignored `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Publishing now uses `com.tbse.london`, the existing private upload credentials, and AUTO version-code resolution. The v2 working tree uses version 2.0 / code 4; later uploads automatically select a higher code when needed. [The CI setup](CI.md) publishes pushes to `develop` to internal testing. The v2 feature changes remain uncommitted and should be committed before that first develop push. The separate main checkout's live working files remain untouched.

## Repertoire data

The committed `tools/frequencies.json.gz` contains reply counts, not game records. `tools/frequency-source.json` records the source URL, sample size, filters, and checksum. The sample is a prefix of January 2017's CC0 Lichess database: 4,741,479 games inspected and 98,059 eligible London games. Both players were rated 1000–2200, and the base clock was at least three minutes. Newer games would improve representativeness.

Create an isolated venv outside the checkout and install `python-chess` there. Install Stockfish and zstd separately. Scripts run with Python isolation enabled:

```sh
python3 -m venv /tmp/lt-venv
/tmp/lt-venv/bin/pip install python-chess
/tmp/lt-venv/bin/python -I tools/test_frequencies.py
/tmp/lt-venv/bin/python -I tools/test_generator.py
/tmp/lt-venv/bin/python -I tools/gen_repertoire.py --nodes 60000
/tmp/lt-venv/bin/python -I tools/audit_repertoire.py
```

Engine analyses are cached in SQLite, and graph construction checkpoints every 25 positions in the operating system temporary directory. Rerun the same command to resume after an interruption. A changed generator, data source, node budget, or limit starts a fresh graph; compatible engine analyses can still be reused. Checkpoint paths are local trusted pickle files created by this script; do not load checkpoints from external sources. Use `--checkpoint` and `--cache` to choose different local paths.

The generator uses observed reply frequencies where at least 40 games reached a position. It adds thematic tests and uses Stockfish replies when the sample is sparse. Training further favors due and unseen positions. These weights describe the training distribution, not an exact prediction of every opponent.

To collect a new sample without saving the raw database:

```sh
curl -s https://database.lichess.org/standard/lichess_db_standard_rated_2017-01.pgn.zst \
  | zstd -dc --long=31 \
  | /tmp/lt-venv/bin/python -I tools/gen_frequencies.py --max-london 100000
```

The parser stops at the requested sample size, so upstream curl/zstd may report a closed pipe. Only aggregate counts and provenance are saved. Regenerate the repertoire afterward and run both validation commands. `--limit` is only for generator smoke tests; never ship that truncated output.

`tools/gen_sounds.py` regenerates the original WAV effects. Repertoire generation supplies threat moves and concrete refutation UCI for playback. Lines continue toward castling on either side, ending earlier only at a decisive advantage, game end, or the 24-White-move safety limit.

## Saved learning data

Cards use stable hashes of board state, side to move, castling rights, and legally capturable en-passant square. `legacy-positions.json` maps all 2,551 nodes of the shipped v1 repertoire to those keys. On first v2 launch, the app migrates its known/missed cards and run totals; missed cards take precedence when old nodes transpose. Existing v2 cards take precedence over migrated data. The migration marker survives a progress reset so old data cannot reappear.

A clean, due answer advances through 1, 3, 7, 16, 35, and 90 day intervals. An early replay does not advance a card. A hint or mistake resets it for today; an order adjustment holds its stage. Teaching creates an immediately due card. Every graded answer persists independently of line completion.

Readiness is the weighted probability that every White move on a sampled line has been recalled unaided. Knowing only the first move gives no readiness for a longer line. It is a coverage estimate, not a calibrated prediction of human recall.
