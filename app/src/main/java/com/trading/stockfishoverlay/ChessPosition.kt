package com.trading.stockfishoverlay

import kotlin.math.abs

class ChessPosition {

    private val board =
        Array(8) { CharArray(8) { '.' } }

    var whiteToMove = true
        private set

    private var castling = "KQkq"
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

        for (row in 0..7) {
            for (col in 0..7) {
                board[row][col] =
                    start[row][col]
            }
        }

        whiteToMove = true
        castling = "KQkq"
        enPassant = "-"
        halfMove = 0
        fullMove = 1
    }

    fun pieceAt(square: String): Char {

        if (square.length != 2)
            return '.'

        val col =
            square[0] - 'a'

        val rank =
            square[1] - '0'

        val row =
            8 - rank

        if (
            row !in 0..7 ||
            col !in 0..7
        ) {
            return '.'
        }

        return board[row][col]
    }

    fun isOwnPiece(square: String): Boolean {

        val piece =
            pieceAt(square)

        if (piece == '.')
            return false

        return if (whiteToMove) {
            piece.isUpperCase()
        } else {
            piece.isLowerCase()
        }
    }

    fun isPseudoLegal(
        from: String,
        to: String
    ): Boolean {

        if (
            from.length != 2 ||
            to.length != 2 ||
            from == to
        ) {
            return false
        }

        val piece =
            pieceAt(from)

        if (piece == '.')
            return false

        if (!isOwnPiece(from))
            return false

        val target =
            pieceAt(to)

        if (
            target != '.' &&
            target.isUpperCase() ==
            piece.isUpperCase()
        ) {
            return false
        }

        val fc =
            from[0] - 'a'

        val fr =
            8 - (from[1] - '0')

        val tc =
            to[0] - 'a'

        val tr =
            8 - (to[1] - '0')

        val dx =
            tc - fc

        val dy =
            tr - fr

        return when (
            piece.uppercaseChar()
        ) {

            'P' -> {

                val direction =
                    if (piece.isUpperCase())
                        -1
                    else
                        1

                val startRow =
                    if (piece.isUpperCase())
                        6
                    else
                        1

                if (
                    dx == 0 &&
                    dy == direction &&
                    target == '.'
                ) {
                    true

                } else if (
                    dx == 0 &&
                    dy == direction * 2 &&
                    fr == startRow &&
                    target == '.'
                ) {

                    board[
                        fr + direction
                    ][fc] == '.'

                } else {

                    abs(dx) == 1 &&
                    dy == direction
                }
            }

            'N' -> {

                (
                    abs(dx) == 1 &&
                    abs(dy) == 2
                ) ||
                (
                    abs(dx) == 2 &&
                    abs(dy) == 1
                )
            }

            'B' -> {

                abs(dx) == abs(dy) &&
                    pathClear(
                        fr,
                        fc,
                        tr,
                        tc
                    )
            }

            'R' -> {

                (
                    dx == 0 ||
                    dy == 0
                ) &&
                pathClear(
                    fr,
                    fc,
                    tr,
                    tc
                )
            }

            'Q' -> {

                (
                    dx == 0 ||
                    dy == 0 ||
                    abs(dx) == abs(dy)
                ) &&
                pathClear(
                    fr,
                    fc,
                    tr,
                    tc
                )
            }

            'K' -> {

                (
                    abs(dx) <= 1 &&
                    abs(dy) <= 1
                ) ||
                (
                    dy == 0 &&
                    abs(dx) == 2
                )
            }

            else -> false
        }
    }

    private fun pathClear(
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int
    ): Boolean {

        val stepRow =
            when {
                toRow > fromRow -> 1
                toRow < fromRow -> -1
                else -> 0
            }

        val stepCol =
            when {
                toCol > fromCol -> 1
                toCol < fromCol -> -1
                else -> 0
            }

        var row =
            fromRow + stepRow

        var col =
            fromCol + stepCol

        while (
            row != toRow ||
            col != toCol
        ) {

            if (
                board[row][col] != '.'
            ) {
                return false
            }

            row += stepRow
            col += stepCol
        }

        return true
    }

    fun applyMove(uci: String): Boolean {

        if (uci.length < 4)
            return false

        val from =
            uci.substring(0, 2)

        val to =
            uci.substring(2, 4)

        val fc =
            from[0] - 'a'

        val fr =
            8 - (from[1] - '0')

        val tc =
            to[0] - 'a'

        val tr =
            8 - (to[1] - '0')

        if (
            fr !in 0..7 ||
            fc !in 0..7 ||
            tr !in 0..7 ||
            tc !in 0..7
        ) {
            return false
        }

        var piece =
            board[fr][fc]

        if (piece == '.')
            return false

        val captured =
            board[tr][tc]

        board[fr][fc] = '.'

        // en passant
        if (
            piece.uppercaseChar() == 'P' &&
            fc != tc &&
            captured == '.'
        ) {

            if (piece.isUpperCase()) {
                if (tr + 1 <= 7)
                    board[tr + 1][tc] = '.'
            } else {
                if (tr - 1 >= 0)
                    board[tr - 1][tc] = '.'
            }
        }

        // roszada
        if (
            piece.uppercaseChar() == 'K' &&
            abs(tc - fc) == 2
        ) {

            if (tc == 6) {

                board[tr][5] =
                    board[tr][7]

                board[tr][7] = '.'

            } else if (tc == 2) {

                board[tr][3] =
                    board[tr][0]

                board[tr][0] = '.'
            }
        }

        // promocja
        if (uci.length >= 5) {

            val promotion =
                uci[4]

            piece =
                if (piece.isUpperCase()) {
                    promotion.uppercaseChar()
                } else {
                    promotion.lowercaseChar()
                }
        }

        board[tr][tc] =
            piece

        updateCastling(
            piece,
            from,
            to,
            captured
        )

        enPassant = "-"

        if (
            piece.uppercaseChar() == 'P' &&
            abs(tr - fr) == 2
        ) {

            val middleRow =
                (fr + tr) / 2

            val middleRank =
                8 - middleRow

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

        if (!whiteToMove) {
            fullMove++
        }

        whiteToMove =
            !whiteToMove

        return true
    }

    private fun updateCastling(
        piece: Char,
        from: String,
        to: String,
        captured: Char
    ) {

        if (piece == 'K') {
            castling =
                castling
                    .replace("K", "")
                    .replace("Q", "")
        }

        if (piece == 'k') {
            castling =
                castling
                    .replace("k", "")
                    .replace("q", "")
        }

        when (from) {

            "a1" ->
                castling =
                    castling.replace("Q", "")

            "h1" ->
                castling =
                    castling.replace("K", "")

            "a8" ->
                castling =
                    castling.replace("q", "")

            "h8" ->
                castling =
                    castling.replace("k", "")
        }

        if (captured == 'R') {

            when (to) {

                "a1" ->
                    castling =
                        castling.replace("Q", "")

                "h1" ->
                    castling =
                        castling.replace("K", "")
            }
        }

        if (captured == 'r') {

            when (to) {

                "a8" ->
                    castling =
                        castling.replace("q", "")

                "h8" ->
                    castling =
                        castling.replace("k", "")
            }
        }
    }

    fun toFen(): String {

        val rows =
            mutableListOf<String>()

        for (row in 0..7) {

            val result =
                StringBuilder()

            var empty = 0

            for (col in 0..7) {

                val piece =
                    board[row][col]

                if (piece == '.') {

                    empty++

                } else {

                    if (empty > 0) {
                        result.append(empty)
                        empty = 0
                    }

                    result.append(piece)
                }
            }

            if (empty > 0)
                result.append(empty)

            rows += result.toString()
        }

        val castle =
            if (castling.isEmpty())
                "-"
            else
                castling

        return buildString {

            append(
                rows.joinToString("/")
            )

            append(
                if (whiteToMove)
                    " w "
                else
                    " b "
            )

            append(castle)
            append(" ")
            append(enPassant)
            append(" ")
            append(halfMove)
            append(" ")
            append(fullMove)
        }
    }
}
