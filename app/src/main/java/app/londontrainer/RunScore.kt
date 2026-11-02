package app.londontrainer

/** Totals for the whole run. Correcting a move never clears earlier help or retries. */
data class RunScore(
    val mistakes: Int = 0,
    val alternatives: Int = 0,
    val hints: Int = 0,
) {
    val clean: Boolean get() = mistakes == 0 && alternatives == 0 && hints == 0

    fun record(attempt: Attempt): RunScore = when (attempt) {
        Attempt.Correct -> this
        is Attempt.Playable -> copy(alternatives = alternatives + 1)
        is Attempt.Wrong -> copy(mistakes = mistakes + 1)
    }

    fun withHint(): RunScore = copy(hints = hints + 1)

    val summary: String get() = buildList {
        if (mistakes > 0) add("$mistakes mistake${if (mistakes == 1) "" else "s"}")
        if (alternatives > 0) add("$alternatives playable alternative${if (alternatives == 1) "" else "s"}")
        if (hints > 0) add("$hints hint${if (hints == 1) "" else "s"}")
    }.joinToString(", ").replaceFirstChar { it.uppercase() }
}
