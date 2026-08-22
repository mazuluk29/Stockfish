package com.trading.stockfishoverlay

import android.graphics.Bitmap
import kotlin.math.abs

class BoardTracker {

    data class BoardArea(
        val left: Int,
        val top: Int,
        val size: Int
    )

    data class ChangedSquare(
        val square: String,
        val difference: Double
    )

    var whiteAtBottom = true

    private var area:
        BoardArea? = null

    private var previous:
        Array<DoubleArray>? = null

    fun reset() {
        area = null
        previous = null
    }

    fun boardArea():
        BoardArea? = area

    fun process(
        bitmap: Bitmap
    ): List<ChangedSquare>? {

        if (area == null) {

            area =
                detectBoard(bitmap)

            previous = null
        }

        val board =
            area
                ?: return null

        val current =
            createSignatures(
                bitmap,
                board
            )

        val old =
            previous

        previous =
            current

        if (old == null) {
            return emptyList()
        }

        val changed =
            mutableListOf<ChangedSquare>()

        for (index in 0 until 64) {

            var difference = 0.0

            for (channel in 0..2) {

                difference +=
                    abs(
                        current[index][channel] -
                        old[index][channel]
                    )
            }

            difference /= 3.0

            if (difference > 11.0) {

                changed +=
                    ChangedSquare(
                        indexToSquare(index),
                        difference
                    )
            }
        }

        return changed
            .sortedByDescending {
                it.difference
            }
            .take(8)
    }

    private fun detectBoard(
        bitmap: Bitmap
    ): BoardArea? {

        val width =
            bitmap.width

        val height =
            bitmap.height

        var best:
            BoardArea? = null

        var bestScore =
            -999999.0

        val maxLeft =
            minOf(
                80,
                width / 5
            )

        var left = 0

        while (left <= maxLeft) {

            val size =
                width - left

            if (size <= 0) {
                left += 8
                continue
            }

            var top =
                maxOf(
                    120,
                    height / 6
                )

            val lastTop =
                height - size - 120

            while (top <= lastTop) {

                val score =
                    checkerScore(
                        bitmap,
                        left,
                        top,
                        size
                    )

                if (score > bestScore) {

                    bestScore =
                        score

                    best =
                        BoardArea(
                            left,
                            top,
                            size
                        )
                }

                top += 8
            }

            left += 8
        }

        return best
    }

    private fun checkerScore(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        size: Int
    ): Double {

        val cell =
            size / 8f

        val group0 =
            DoubleArray(3)

        val group1 =
            DoubleArray(3)

        var count0 = 0
        var count1 = 0

        for (row in 0..7) {

            for (col in 0..7) {

                /*
                 * Próbka blisko rogu pola,
                 * żeby rzadziej trafiać w figurę.
                 */
                val x =
                    (
                        left +
                        col * cell +
                        cell * 0.14f
                    ).toInt()

                val y =
                    (
                        top +
                        row * cell +
                        cell * 0.14f
                    ).toInt()

                if (
                    x !in 0 until bitmap.width ||
                    y !in 0 until bitmap.height
                ) {
                    continue
                }

                val pixel =
                    bitmap.getPixel(x, y)

                val values =
                    doubleArrayOf(
                        ((pixel shr 16) and 255)
                            .toDouble(),

                        ((pixel shr 8) and 255)
                            .toDouble(),

                        (pixel and 255)
                            .toDouble()
                    )

                val first =
                    (row + col) % 2 == 0

                val target =
                    if (first)
                        group0
                    else
                        group1

                for (i in 0..2) {
                    target[i] += values[i]
                }

                if (first)
                    count0++
                else
                    count1++
            }
        }

        if (
            count0 == 0 ||
            count1 == 0
        ) {
            return -999999.0
        }

        for (i in 0..2) {

            group0[i] /= count0
            group1[i] /= count1
        }

        var difference = 0.0

        for (i in 0..2) {

            difference +=
                abs(
                    group0[i] -
                    group1[i]
                )
        }

        return difference
    }

    private fun createSignatures(
        bitmap: Bitmap,
        board: BoardArea
    ): Array<DoubleArray> {

        val result =
            Array(64) {
                DoubleArray(3)
            }

        val cell =
            board.size / 8f

        for (row in 0..7) {

            for (col in 0..7) {

                val index =
                    row * 8 + col

                var red = 0L
                var green = 0L
                var blue = 0L
                var count = 0

                /*
                 * Środkowa część pola.
                 */
                val startX =
                    (
                        board.left +
                        col * cell +
                        cell * 0.18f
                    ).toInt()

                val endX =
                    (
                        board.left +
                        (col + 1) * cell -
                        cell * 0.18f
                    ).toInt()

                val startY =
                    (
                        board.top +
                        row * cell +
                        cell * 0.18f
                    ).toInt()

                val endY =
                    (
                        board.top +
                        (row + 1) * cell -
                        cell * 0.18f
                    ).toInt()

                var y =
                    startY

                while (y < endY) {

                    var x =
                        startX

                    while (x < endX) {

                        if (
                            x in 0 until bitmap.width &&
                            y in 0 until bitmap.height
                        ) {

                            val pixel =
                                bitmap.getPixel(
                                    x,
                                    y
                                )

                            red +=
                                (pixel shr 16) and 255

                            green +=
                                (pixel shr 8) and 255

                            blue +=
                                pixel and 255

                            count++
                        }

                        x += 5
                    }

                    y += 5
                }

                if (count > 0) {

                    result[index][0] =
                        red.toDouble() /
                            count

                    result[index][1] =
                        green.toDouble() /
                            count

                    result[index][2] =
                        blue.toDouble() /
                            count
                }
            }
        }

        return result
    }

    private fun indexToSquare(
        index: Int
    ): String {

        val row =
            index / 8

        val col =
            index % 8

        val file: Int
        val rank: Int

        if (whiteAtBottom) {

            file = col
            rank = 8 - row

        } else {

            file = 7 - col
            rank = row + 1
        }

        return buildString {
            append(
                ('a'.code + file)
                    .toChar()
            )

            append(rank)
        }
    }
}
