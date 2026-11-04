# London Trainer 2.0 validation

This records the v2 baseline before the subsequent CI/signing configuration changed the build application ID to `com.tbse.london`. See [CI verification](CI.md) for that setup.

Verified on November 1, 2026, in the isolated `v2-trainer` worktree. Version name **2.0**, version code **4**, application ID **app.londontrainer**, minSdk 31, targetSdk 36.

## Automated checks

- **52 Kotlin tests passed:** 19 chess/repertoire tests, 15 memory/scheduling tests, and 18 drill tests. These include move-generation perft, every graph move and refutation, stable keys including usable en passant, v1 card migration, whole-line readiness, review scheduling, immediate saving, teaching, hints, wrong-order feedback, punishment/rollback, and final-move review.
- **Seven Python tests passed:** streamed PGN filtering, annotated games, malformed SAN, persistent engine caching, batched candidate scoring, required opening coverage, practical move tolerance, and refutations for reactive replies.
- `lintDebug`, `assembleDebug`, and `assembleRelease` passed. Lint reports **0 errors and 12 warnings**, including dependency updates, backup configuration, icon metadata, and preference/style suggestions.
- `git diff --check` passed.
- All six original sound assets are valid mono PCM16 at 22,050 Hz, with no clipped samples. Audible playback was not checked: this emulator was launched without audio.

Evidence: [build log](../artifacts/v2/build-and-tests.log), [stricter test run](../artifacts/v2/strict-tests.log), and the three JUnit XML reports in [artifacts/v2](../artifacts/v2).

## Independent repertoire audit

The python-chess audit checked **14,586 reachable nodes**, **14,567 distinct positions**, every White move, Black reply, alternative, punishment, threat SAN/UCI, and all nine opening shortcuts. Total terminal probability is one. Source weights come from the recorded 98,059-game Lichess sample, with additional thematic tests and engine fallback.

**92.8219% of weighted lines reach castling**, on either side, compared with roughly 65% in the original repertoire. Both automated validators now require more than 90%. Of 2,260 terminal nodes, 2,095 end after castling, 15 after a decisive advantage, and 150 at the 24-White-move safety limit. Castling is not guaranteed on every tactical path.

Seven transposed positions have different engine-checked recommendations across paths. They share position cards; consistent recommendation choice and the remaining capped branches are follow-ups.

Evidence: [complete audit](../artifacts/v2/repertoire-audit.json). Reproduce it with `/tmp/lt-venv/bin/python -I tools/audit_repertoire.py`.

## Device verification

Only **emulator-5556**, Android 15 / API 35 (`t3_transcript_verify`), was used. The main checkout, emulator-5554, physical phones, and Play publishing were left alone. Its original 720×1600 phone override and default storage thresholds were restored after testing. No London Trainer process crash appeared in the emulator crash buffer.

| Check | Result and evidence |
|---|---|
| Home | Readiness, streak, due count, practice categories, and navigation: [Home](../artifacts/v2/home-trained.png) |
| Teaching | New-position arrow and explanation; an off-arrow move bounces without grading: [portrait](../artifacts/v2/portrait.png) |
| Landscape | Position survives resizing and all 64 squares remain available: [1600×720](../artifacts/v2/landscape.png) |
| Short phone | Board and controls fit; coach can scroll: [720×1136](../artifacts/v2/short-phone.png) |
| Tablet | Home and drill fit the native 2560×1600 display: [Home](../artifacts/v2/tablet-home.png), [drill](../artifacts/v2/tablet-drill.png) |
| Full line | Dragged the first move, tapped the rest, and reached O-O: [completed line](../artifacts/v2/line-complete.png), [moves](../artifacts/v2/full-line.txt) |
| Post-line review | Previous/next move changes the board and explains the final move: [review](../artifacts/v2/line-review.png) |
| Due review | Jumps to the due position, tests unaided, retries a hinted card once, and avoids a false clean verdict: [result](../artifacts/v2/review-done.png) |
| Punishment | Nf3 during a bishop chase produces ...Nxf4; the original position is restored before retrying: [playback](../artifacts/v2/punishment.png), [fixture/result](../artifacts/v2/punishment.txt) |
| Guide | Setup board responds to move selection; tests and middlegame plans scroll correctly: [setup](../artifacts/v2/guide.png), [plans](../artifacts/v2/guide-plans.png) |
| Settings | Sound, teaching, and board theme persist across force-stop/relaunch; reset/cancel and privacy-link visibility verified: [Settings](../artifacts/v2/settings.png), [persisted values](../artifacts/v2/settings-persisted.png) |
| Upgrade | Old seen/missed positions and 7 runs / 4 clean runs migrate; the test profile is restored afterward: [migration result](../artifacts/v2/migration.xml) |
| Accessibility | All 64 labeled squares and move controls appear in the accessibility tree. Spoken TalkBack remains a hardware/working-TTS follow-up. |

The privacy URL returned HTTP 200. The device checks verified its visible Settings control; they did not complete a browser journey.

## Release follow-ups

Tracked in [TBSE-110](http://localhost:8080/tbse/projects/57bbdd03-61ee-4e8e-a814-2e00c757ea3a/issues/a882dd4e-a42b-4353-bf75-bd345417a657): opt-in daily reminders, a Lichess explorer token/newer sample, Android 16 and spoken TalkBack verification, consistent recommendations at transpositions, and review of capped tactical branches.

The separate Play checkout owns `com.tbse.london`, upload signing, and AUTO version resolution. This worktree's release build is signed for local testing. Integrating that publishing configuration and any store release are separate actions. All changes remain uncommitted; nothing was pushed or published.
