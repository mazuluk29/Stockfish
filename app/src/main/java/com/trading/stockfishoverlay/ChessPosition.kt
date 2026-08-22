package com.trading.stockfishoverlay

class ChessPosition {

    private val board =
        Array(8) { CharArray(8) { '.' } }

    var whiteToMove = true
        private set

    private var castle = "KQkq"
    private var enPassant = "-"
    private var halfMove = 0
    private var fullMove = 1

    init {
        reset()
    }

    fun reset() {

        val start = arrayOf(
            "rnbqkbnr",
            "pppppppp",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "RNBQKBNR"
        )

        for (r in 0..7) {
            for (c in 0..7) {
                board[r][c] = start[r][c]
            }
        }

        whiteToMove = true
        castle = "KQkq"
        enPassant = "-"
        halfMove = 0
        fullMove = 1
    }

    fun pieceAt(square: String): Char {

        val c = square[0] - 'a'
        val rank = square[1] - '0'
        val r = 8 - rank

        return board[r][c]
    }

    fun applyMove(uci: String): Boolean {

        if (uci.length < 4)
            return false

        val from = uci.substring(0, 2)
        val to = uci.substring(2, 4)

        val fc = from[0] - 'a'
        val fr = 8 - (from[1] - '0')

        val tc = to[0] - 'a'
        val tr = 8 - (to[1] - '0')

        if (
            fr !in 0..7 ||
            fc !in 0..7 ||
            tr !in 0..7 ||
            tc !in 0..7
        ) return false

        var piece = board[fr][fc]

        if (piece == '.')
            return false

        val captured = board[tr][tc]

        board[fr][fc] = '.'

        // Roszada
        if (
            piece.uppercaseChar() == 'K' &&
            kotlin.math.abs(tc - fc) == 2
        ) {

            if (tc == 6) {
                board[tr][5] = board[tr][7]
                board[tr][7] = '.'
            } else if (tc == 2) {
                board[tr][3] = board[tr][0]
                board[tr][0] = '.'
            }
        }

        // En passant
        if (
            piece.uppercaseChar() == 'P' &&
            captured == '.' &&
            fc != tc
        ) {

            if (piece.isUpperCase()) {
                board[tr + 1][tc] = '.'
            } else {
                board[tr - 1][tc] = '.'
            }
        }

        // Promocja
        if (uci.length >= 5) {

            val promotion =
                uci[4]

            piece =
                if (piece.isUpperCase())
                    promotion.uppercaseChar()
                else
                    promotion.lowercaseChar()
        }

        board[tr][tc] = piece

        updateCastling(piece, from)

        enPassant = "-"

        if (
            piece.uppercaseChar() == 'P' &&
            kotlin.math.abs(tr - fr) == 2
        ) {

            val middleRank =
                if (piece.isUpperCase())
                    from[1] + 1
                else
                    from[1] - 1

            enPassant =
                "${from[0]}$middleRank"
        }

        if (
            piece.uppercaseChar() == 'P' ||
            captured != '.'
        ) {
            halfMove = 0
        } else {
            halfMove++
        }

        if (!whiteToMove)
            fullMove++

        whiteToMove = !whiteToMove

        return true
    }

    private fun updateCastling(
        piece: Char,
        from: String
    ) {

        if (piece == 'K') {
            castle =
                castle
                    .replace("K", "")
                    .replace("Q", "")
        }

        if (piece == 'k') {
            castle =
                castle
                    .replace("k", "")
                    .replace("q", "")
        }

        when (from) {
            "a1" -> castle = castle.replace("Q", "")
            "h1" -> castle = castle.replace("K", "")
            "a8" -> castle = castle.replace("q", "")
            "h8" -> castle = castle.replace("k", "")
        }

        if (castle.isEmpty())
            castle = "-"
    }

    fun toFen(): String {

        val rows =
            mutableListOf<String>()

        for (r in 0..7) {

            val sb = StringBuilder()
            var empty = 0

            for (c in 0..7) {

                val p = board[r][c]

                if (p == '.') {
                    empty++
                } else {

                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }

                    sb.append(p)
                }
            }

            if (empty > 0)
                sb.append(empty)

            rows += sb.toString()
        }

        return buildString {

            append(rows.joinToString("/"))
            append(" ")

            append(
                if (whiteToMove)
                    "w"
                else
                    "b"
            )

            append(" ")
            append(castle)
            append(" ")
            append(enPassant)
            append(" ")
            append(halfMove)
            append(" ")
            append(fullMove)
        }
    }

    fun isOwnPiece(square: String): Boolean {

        val p = pieceAt(square)

        if (p == '.')
            return false

        return if (whiteToMove)
            p.isUpperCase()
        else
            p.isLowerCase()
    }
}
