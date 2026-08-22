package com.trading.stockfishoverlay

import android.graphics.Bitmap
import kotlin.math.abs

class BoardTracker {

    data class BoardArea(
        val left: Int,
        val top: Int,
        val size: Int
    )

    private var previous:
        Array<DoubleArray>? = null

    private var boardArea:
        BoardArea? = null

    var whiteAtBottom = true

    fun reset() {
        previous = null
        boardArea = null
    }

    fun getBoardArea(): BoardArea? =
        boardArea

    fun process(
        bitmap: Bitmap
    ): List<String>? {

        if (boardArea == null) {
            boardArea = detectBoard(bitmap)
        }

        val area =
            boardArea ?: return null

        val current =
            signatures(
                bitmap,
                area
            )

        val old =
            previous

        previous = current

        if (old == null)
            return emptyList()

        val changed =
            mutableListOf<Pair<Int, Double>>()

        for (i in 0 until 64) {

            var d = 0.0

            for (k in 0..2) {
                d += abs(
                    current[i][k] -
                    old[i][k]
                )
            }

            d /= 3.0

            if (d > 18.0) {
                changed += i to d
            }
        }

        return changed
            .sortedByDescending { it.second }
            .take(6)
            .map {
                screenIndexToSquare(
                    it.first
                )
            }
    }

    private fun signatures(
        bitmap: Bitmap,
        area: BoardArea
    ): Array<DoubleArray> {

        val result =
            Array(64) {
                DoubleArray(3)
            }

        val cell =
            area.size / 8f

        for (row in 0..7) {
            for (col in 0..7) {

                val idx =
                    row * 8 + col

                var rs = 0L
                var gs = 0L
                var bs = 0L
                var count = 0

                val x0 =
                    (area.left + col * cell + cell * 0.25f)
                        .toInt()

                val x1 =
                    (area.left + (col + 1) * cell - cell * 0.25f)
                        .toInt()

                val y0 =
                    (area.top + row * cell + cell * 0.25f)
                        .toInt()

                val y1 =
                    (area.top + (row + 1) * cell - cell * 0.25f)
                        .toInt()

                var y = y0

                while (y < y1) {

                    var x = x0

                    while (x < x1) {

                        if (
                            x in 0 until bitmap.width &&
                            y in 0 until bitmap.height
                        ) {

                            val p =
                                bitmap.getPixel(
                                    x,
                                    y
                                )

                            rs +=
                                (p shr 16) and 255

                            gs +=
                                (p shr 8) and 255

                            bs +=
                                p and 255

                            count++
                        }

                        x += 4
                    }

                    y += 4
                }

                if (count > 0) {

                    result[idx][0] =
                        rs.toDouble() / count

                    result[idx][1] =
                        gs.toDouble() / count

                    result[idx][2] =
                        bs.toDouble() / count
                }
            }
        }

        return result
    }

    private fun detectBoard(
        bitmap: Bitmap
    ): BoardArea? {

        /*
         * Pierwsza wersja:
         * Chess.com na telefonie zwykle pokazuje
         * planszę na całą szerokość ekranu.
         *
         * Szukamy najlepszego pionowego położenia.
         */

        val size =
            bitmap.width

        if (bitmap.height < size)
            return null

        var bestTop = 0
        var bestScore =
            Double.NEGATIVE_INFINITY

        var top = 0

        while (
            top + size <= bitmap.height
        ) {

            val score =
                checkerScore(
                    bitmap,
                    top,
                    size
                )

            if (score > bestScore) {
                bestScore = score
                bestTop = top
            }

            top += 12
        }

        return BoardArea(
            0,
            bestTop,
            size
        )
    }

    private fun checkerScore(
        bitmap: Bitmap,
        top: Int,
        size: Int
    ): Double {

        val cell =
            size / 8f

        val parity0 =
            DoubleArray(3)

        val parity1 =
            DoubleArray(3)

        var n0 = 0
        var n1 = 0

        for (r in 0..7) {
            for (c in 0..7) {

                val x =
                    (c * cell + cell * 0.12f)
                        .toInt()

                val y =
                    (
                        top +
                        r * cell +
                        cell * 0.12f
                    ).toInt()

                if (
                    x !in 0 until bitmap.width ||
                    y !in 0 until bitmap.height
                ) continue

                val p =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val rgb =
                    doubleArrayOf(
                        ((p shr 16) and 255).toDouble(),
                        ((p shr 8) and 255).toDouble(),
                        (p and 255).toDouble()
                    )

                val target =
                    if ((r + c) % 2 == 0)
                        parity0
                    else
                        parity1

                for (k in 0..2)
                    target[k] += rgb[k]

                if ((r + c) % 2 == 0)
                    n0++
                else
                    n1++
            }
        }

        if (n0 == 0 || n1 == 0)
            return -9999.0

        for (k in 0..2) {
            parity0[k] /= n0
            parity1[k] /= n1
        }

        var difference = 0.0

        for (k in 0..2) {
            difference +=
                abs(
                    parity0[k] -
                    parity1[k]
                )
        }

        return difference
    }

    private fun screenIndexToSquare(
        index: Int
    ): String {

        val row = index / 8
        val col = index % 8

        val file:
            Int

        val rank:
            Int

        if (whiteAtBottom) {

            file = col
            rank = 8 - row

        } else {

            file = 7 - col
            rank = row + 1
        }

        return "${('a'.code + file).toChar()}$rank"
    }
}
