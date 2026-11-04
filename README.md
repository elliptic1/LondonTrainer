# London Trainer

Learn one chess opening properly: the London System, as White.

You don't play a whole game. You play the opening against the moves real players actually choose, and London Trainer works out what you know, what you're forgetting, and what to show you next.

## How it works

- **Meet each position once.** The first time you see a position, an arrow shows the move and the coach explains why.
- **Then prove you know it.** Copying an arrow isn't knowing a move, so new positions come back straight away for you to find on your own.
- **Review what's fading.** Every position you learn is scheduled. Get it right and it comes back later and later: tomorrow, in three days, in a week, in a month. Get it wrong and it comes back today. Reviews skip straight to the moment that matters, so five minutes goes a long way.
- **See why a mistake fails.** Play a setup move at the wrong moment and it stays on the board long enough for Black to punish it, then it's taken back so you can find the right idea.
- **Get a nudge, not the answer.** The first hint tells you what Black is threatening and which piece to move. The second shows the square.
- **Walk back through a finished line.** Step through it move by move, with the reason for each one.
- **Practice by opening.** Struggling with the King's Indian or the Dutch? Go straight there. Each opening shows how much of it you've learned.
- **Read the guide.** The eight setup moves, Black's usual tests and your answers, and what to do once you've castled.

## What you're trained against

Black's replies are weighted using a sample of 98,059 club-level London games from the CC0 Lichess open database, with thematic tests added and Stockfish replies used where data is sparse. Every White move is checked with Stockfish. Lines aim to finish the setup; a decisive position or a long tactical detour can end a line earlier.

## Your readiness score

Your readiness score answers one question: what is your chance of knowing every White move in a typical line? It counts positions only once you've played them unaided, and common moves count for more than rare ones.

## Good to know

Works offline, with no account and no tracking. Your progress is saved locally. The app works in portrait and landscape, on phones, tablets and foldables, and supports TalkBack. Sound, teaching arrows and board colors can all be changed in Settings.

## Piece art and sound

The chess pieces and sound effects are original work created for London Trainer.

For build instructions, data provenance, and learning rules, see [the development notes](docs/DEVELOPMENT.md). Test results and screenshots are in [the validation report](docs/VALIDATION.md).

Pushes to `develop` publish to Play internal testing; see [the CI setup](docs/CI.md).
