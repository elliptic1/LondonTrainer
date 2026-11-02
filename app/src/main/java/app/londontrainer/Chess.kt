package app.londontrainer

/** What changed between two boards: pieces that slid (from, to), and squares whose piece left play. */
fun diffBoards(before: CharArray, after: CharArray): Pair<List<Pair<Int, Int>>, List<Int>> {
    val left = (0..63).filter { before[it] != '.' && before[it] != after[it] }.toMutableList()
    val slides = (0..63).filter { after[it] != '.' && after[it] != before[it] }.mapNotNull { to ->
        left.filter { before[it] == after[to] }.minByOrNull { kotlin.math.abs(it - to) }?.let { from ->
            left -= from
            from to to
        }
    }
    return slides to left
}

/** Chess rules only: squares are 0..63 with a1 = 0, moves are UCI strings ("e2e4", "a7a8q"). */
class Position(
    val board: CharArray,
    val whiteToMove: Boolean,
    val castling: String,
    val epSquare: Int,
) {
    fun play(move: String): Position {
        val from = sq(move, 0)
        val to = sq(move, 2)
        val b = board.copyOf()
        val piece = b[from]
        b[from] = '.'
        b[to] = if (move.length == 5) move[4].let { if (whiteToMove) it.uppercaseChar() else it } else piece
        if (piece.lowercaseChar() == 'p' && to == epSquare) b[to + if (whiteToMove) -8 else 8] = '.'
        if (piece.lowercaseChar() == 'k' && kotlin.math.abs(to - from) == 2) {
            val rookFrom = if (to > from) to + 1 else to - 2
            b[(from + to) / 2] = b[rookFrom]
            b[rookFrom] = '.'
        }
        val rights = castling.filterNot { it in lostRights(from) || it in lostRights(to) }
        val ep = if (piece.lowercaseChar() == 'p' && kotlin.math.abs(to - from) == 16) (from + to) / 2 else -1
        return Position(b, !whiteToMove, rights, ep)
    }

    fun legalMoves(): List<String> = pseudoMoves().filter { play(it).let { next -> !next.attacks(next.king(whiteToMove)) } }

    private fun king(white: Boolean) = board.indexOf(if (white) 'K' else 'k')

    /** True if the side to move attacks `target`. */
    private fun attacks(target: Int): Boolean {
        val pawn = if (whiteToMove) 'P' else 'p'
        val knight = if (whiteToMove) 'N' else 'n'
        val bishop = if (whiteToMove) 'B' else 'b'
        val rook = if (whiteToMove) 'R' else 'r'
        val queen = if (whiteToMove) 'Q' else 'q'
        val king = if (whiteToMove) 'K' else 'k'
        val pawnRank = if (whiteToMove) -1 else 1
        if (step(target, -1, pawnRank) { it == pawn } || step(target, 1, pawnRank) { it == pawn }) return true
        if (KNIGHT.any { (f, r) -> step(target, f, r) { it == knight } }) return true
        if (ALL.any { (f, r) -> step(target, f, r) { it == king } }) return true
        return DIAG.any { (f, r) -> slideHits(target, f, r, bishop, queen) } ||
            STRAIGHT.any { (f, r) -> slideHits(target, f, r, rook, queen) }
    }

    private inline fun step(from: Int, df: Int, dr: Int, test: (Char) -> Boolean): Boolean {
        val f = from % 8 + df
        val r = from / 8 + dr
        return f in 0..7 && r in 0..7 && test(board[r * 8 + f])
    }

    private fun slideHits(from: Int, df: Int, dr: Int, a: Char, b: Char): Boolean {
        var f = from % 8 + df
        var r = from / 8 + dr
        while (f in 0..7 && r in 0..7) {
            val p = board[r * 8 + f]
            if (p != '.') return p == a || p == b
            f += df
            r += dr
        }
        return false
    }

    private fun mine(c: Char) = c != '.' && c.isUpperCase() == whiteToMove

    private fun pseudoMoves(): List<String> {
        val out = mutableListOf<String>()
        for (s in 0..63) {
            val p = board[s]
            if (!mine(p)) continue
            when (p.lowercaseChar()) {
                'p' -> pawnMoves(s, out)
                'n' -> KNIGHT.forEach { (f, r) -> jump(s, f, r, out) }
                'k' -> { ALL.forEach { (f, r) -> jump(s, f, r, out) }; castles(s, out) }
                'b' -> DIAG.forEach { (f, r) -> slide(s, f, r, out) }
                'r' -> STRAIGHT.forEach { (f, r) -> slide(s, f, r, out) }
                'q' -> ALL.forEach { (f, r) -> slide(s, f, r, out) }
            }
        }
        return out
    }

    private fun jump(s: Int, df: Int, dr: Int, out: MutableList<String>) {
        val f = s % 8 + df
        val r = s / 8 + dr
        if (f in 0..7 && r in 0..7 && !mine(board[r * 8 + f])) out += uci(s, r * 8 + f)
    }

    private fun slide(s: Int, df: Int, dr: Int, out: MutableList<String>) {
        var f = s % 8 + df
        var r = s / 8 + dr
        while (f in 0..7 && r in 0..7) {
            val to = r * 8 + f
            if (mine(board[to])) return
            out += uci(s, to)
            if (board[to] != '.') return
            f += df
            r += dr
        }
    }

    private fun pawnMoves(s: Int, out: MutableList<String>) {
        val dir = if (whiteToMove) 8 else -8
        val promoteRank = if (whiteToMove) 7 else 0
        fun add(to: Int) {
            if (to / 8 == promoteRank) "qrbn".forEach { out += uci(s, to) + it } else out += uci(s, to)
        }
        if (board[s + dir] == '.') {
            add(s + dir)
            if (s / 8 == (if (whiteToMove) 1 else 6) && board[s + 2 * dir] == '.') add(s + 2 * dir)
        }
        for (df in intArrayOf(-1, 1)) {
            val f = s % 8 + df
            if (f !in 0..7) continue
            val to = (s + dir) / 8 * 8 + f
            if (to == epSquare || (board[to] != '.' && !mine(board[to]))) add(to)
        }
    }

    private fun castles(s: Int, out: MutableList<String>) {
        val (short, long) = if (whiteToMove) 'K' to 'Q' else 'k' to 'q'
        val enemy = Position(board, !whiteToMove, castling, -1)
        if (short in castling && board[s + 1] == '.' && board[s + 2] == '.' &&
            !enemy.attacks(s) && !enemy.attacks(s + 1)
        ) out += uci(s, s + 2)
        if (long in castling && board[s - 1] == '.' && board[s - 2] == '.' && board[s - 3] == '.' &&
            !enemy.attacks(s) && !enemy.attacks(s - 1)
        ) out += uci(s, s - 2)
    }

    companion object {
        private val DIAG = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val ALL = DIAG + STRAIGHT
        private val KNIGHT = listOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)

        fun start() = fromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        fun fromFen(fen: String): Position {
            val parts = fen.split(" ")
            val b = CharArray(64) { '.' }
            parts[0].split("/").forEachIndexed { i, rank ->
                var f = 0
                for (c in rank) {
                    if (c.isDigit()) f += c.digitToInt() else b[(7 - i) * 8 + f++] = c
                }
            }
            return Position(b, parts[1] == "w", parts[2].replace("-", ""), if (parts[3] == "-") -1 else sq(parts[3], 0))
        }

        fun sq(s: String, at: Int) = (s[at + 1] - '1') * 8 + (s[at] - 'a')

        fun uci(from: Int, to: Int) = name(from) + name(to)

        private fun name(s: Int) = "${'a' + s % 8}${'1' + s / 8}"

        private fun lostRights(square: Int) = when (square) {
            0 -> "Q"; 7 -> "K"; 4 -> "KQ"
            56 -> "q"; 63 -> "k"; 60 -> "kq"
            else -> ""
        }
    }
}
