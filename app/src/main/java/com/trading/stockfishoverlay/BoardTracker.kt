package com.trading.stockfishoverlay

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

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

    private data class Candidate(
        val area: BoardArea,
        val score: Double
    )

    var whiteAtBottom = true

    private var lockedArea: BoardArea? = null
    private var candidateArea: BoardArea? = null

    private var stableFrames = 0
    private var lostFrames = 0
    private var validationCounter = 0

    private var previous:
        Array<DoubleArray>? = null

    val isLocked: Boolean
        get() = lockedArea != null

    fun reset() {
        lockedArea = null
        candidateArea = null
        stableFrames = 0
        lostFrames = 0
        validationCounter = 0
        previous = null
    }

    fun boardArea(): BoardArea? =
        lockedArea

    fun process(
        bitmap: Bitmap
    ): List<ChangedSquare>? {

        var area =
            lockedArea

        /*
         * Nie mamy jeszcze planszy:
         * szukamy jej ponownie w każdej klatce.
         */
        if (area == null) {

            val candidate =
                detectBoard(bitmap)

            if (
                candidate == null ||
                candidate.score < 70.0
            ) {
                candidateArea = null
                stableFrames = 0
                previous = null
                return null
            }

            val oldCandidate =
                candidateArea

            if (
                oldCandidate != null &&
                similar(
                    oldCandidate,
                    candidate.area
                )
            ) {

                stableFrames++

            } else {

                candidateArea =
                    candidate.area

                stableFrames = 1
            }

            /*
             * Plansza musi być w podobnym miejscu
             * przez 4 kolejne klatki.
             */
            if (stableFrames < 4) {
                return null
            }

            lockedArea =
                candidate.area

            area =
                candidate.area

            lostFrames = 0
            previous = null
        }

        /*
         * Co kilka klatek sprawdzamy,
         * czy prawdziwa plansza nadal tam jest.
         */
        validationCounter++

        if (validationCounter >= 4) {

            validationCounter = 0

            val score =
                boardScore(
                    bitmap,
                    area
                )

            if (score < 50.0) {

                lostFrames++

                if (lostFrames >= 3) {

                    lockedArea = null
                    candidateArea = null
                    stableFrames = 0
                    lostFrames = 0
                    previous = null

                    return null
                }

            } else {

                lostFrames = 0
            }
        }

        val current =
            signatures(
                bitmap,
                area
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

            if (difference > 12.0) {

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
    ): Candidate? {

        val width =
            bitmap.width

        val height =
            bitmap.height

        var best:
            Candidate? = null

        /*
         * Na telefonie Chess.com plansza zajmuje
         * prawie całą szerokość. Dzięki temu
         * nie skanujemy małych kafelków z menu.
         */
        val minSize =
            (width * 0.88f).toInt()

        val maxSize =
            width

        var size =
            maxSize

        while (size >= minSize) {

            val maxLeft =
                width - size

            var left = 0

            while (left <= maxLeft) {

                var top =
                    120

                val maxTop =
                    height - size - 80

                while (top <= maxTop) {

                    val area =
                        BoardArea(
                            left,
                            top,
                            size
                        )

                    val score =
                        boardScore(
                            bitmap,
                            area
                        )

                    if (
                        best == null ||
                        score > best.score
                    ) {

                        best =
                            Candidate(
                                area,
                                score
                            )
                    }

                    top += 8
                }

                left += 4
            }

            size -= 8
        }

        return best
    }

    /*
     * Prawdziwa plansza powinna mieć:
     * - dwa wyraźnie różne kolory pól,
     * - ten sam kolor na polach tej samej parzystości,
     * - naprzemienny wzór w wierszach i kolumnach.
     */
    private fun boardScore(
        bitmap: Bitmap,
        area: BoardArea
    ): Double {

        if (
            area.left < 0 ||
            area.top < 0 ||
            area.left + area.size > bitmap.width ||
            area.top + area.size > bitmap.height
        ) {
            return -9999.0
        }

        val cell =
            area.size / 8f

        val grid =
            Array(8) {
                Array(8) {
                    DoubleArray(3)
                }
            }

        for (row in 0..7) {

            for (col in 0..7) {

                grid[row][col] =
                    squareAverage(
                        bitmap,
                        area,
                        row,
                        col,
                        cell
                    )
            }
        }

        val light =
            mutableListOf<DoubleArray>()

        val dark =
            mutableListOf<DoubleArray>()

        for (row in 0..7) {
            for (col in 0..7) {

                if ((row + col) % 2 == 0) {
                    light += grid[row][col]
                } else {
                    dark += grid[row][col]
                }
            }
        }

        val lightMean =
            medianColor(light)

        val darkMean =
            medianColor(dark)

        val contrast =
            distance(
                lightMean,
                darkMean
            )

        val lightNoise =
            medianDistance(
                light,
                lightMean
            )

        val darkNoise =
            medianDistance(
                dark,
                darkMean
            )

        val noise =
            (
                lightNoise +
                darkNoise
            ) / 2.0

        var goodRows = 0
        var goodColumns = 0

        for (row in 0..7) {

            val a =
                mutableListOf<DoubleArray>()

            val b =
                mutableListOf<DoubleArray>()

            for (col in 0..7) {

                if (col % 2 == 0)
                    a += grid[row][col]
                else
                    b += grid[row][col]
            }

            if (
                distance(
                    meanColor(a),
                    meanColor(b)
                ) > 30.0
            ) {
                goodRows++
            }
        }

        for (col in 0..7) {

            val a =
                mutableListOf<DoubleArray>()

            val b =
                mutableListOf<DoubleArray>()

            for (row in 0..7) {

                if (row % 2 == 0)
                    a += grid[row][col]
                else
                    b += grid[row][col]
            }

            if (
                distance(
                    meanColor(a),
                    meanColor(b)
                ) > 30.0
            ) {
                goodColumns++
            }
        }

        /*
         * Kafelki z menu mają zwykle słaby wzór
         * w wielu wierszach/kolumnach.
         */
        if (
            goodRows < 6 ||
            goodColumns < 6 ||
            contrast < 45.0
        ) {
            return -999.0
        }

        return contrast -
            noise +
            (
                goodRows +
                goodColumns
            ) * 12.0
    }

    private fun squareAverage(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int,
        cell: Float
    ): DoubleArray {

        val startX =
            (
                area.left +
                col * cell +
                cell * 0.12f
            ).toInt()

        val endX =
            (
                area.left +
                (col + 1) * cell -
                cell * 0.12f
            ).toInt()

        val startY =
            (
                area.top +
                row * cell +
                cell * 0.12f
            ).toInt()

        val endY =
            (
                area.top +
                (row + 1) * cell -
                cell * 0.12f
            ).toInt()

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0

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

                x += 6
            }

            y += 6
        }

        if (count == 0) {
            return DoubleArray(3)
        }

        return doubleArrayOf(
            red.toDouble() / count,
            green.toDouble() / count,
            blue.toDouble() / count
        )
    }

    private fun signatures(
        bitmap: Bitmap,
        area: BoardArea
    ): Array<DoubleArray> {

        val cell =
            area.size / 8f

        return Array(64) { index ->

            val row =
                index / 8

            val col =
                index % 8

            squareAverage(
                bitmap,
                area,
                row,
                col,
                cell
            )
        }
    }

    private fun similar(
        a: BoardArea,
        b: BoardArea
    ): Boolean {

        return abs(a.left - b.left) <= 12 &&
            abs(a.top - b.top) <= 12 &&
            abs(a.size - b.size) <= 12
    }

    private fun meanColor(
        colors: List<DoubleArray>
    ): DoubleArray {

        val result =
            DoubleArray(3)

        if (colors.isEmpty())
            return result

        for (color in colors) {

            for (i in 0..2) {
                result[i] += color[i]
            }
        }

        for (i in 0..2) {
            result[i] /= colors.size
        }

        return result
    }

    private fun medianColor(
        colors: List<DoubleArray>
    ): DoubleArray {

        if (colors.isEmpty())
            return DoubleArray(3)

        val result =
            DoubleArray(3)

        for (channel in 0..2) {

            val values =
                colors
                    .map {
                        it[channel]
                    }
                    .sorted()

            result[channel] =
                values[
                    values.size / 2
                ]
        }

        return result
    }

    private fun medianDistance(
        colors: List<DoubleArray>,
        mean: DoubleArray
    ): Double {

        if (colors.isEmpty())
            return 999.0

        val values =
            colors
                .map {
                    distance(
                        it,
                        mean
                    )
                }
                .sorted()

        return values[
            values.size / 2
        ]
    }

    private fun distance(
        a: DoubleArray,
        b: DoubleArray
    ): Double {

        val dr =
            a[0] - b[0]

        val dg =
            a[1] - b[1]

        val db =
            a[2] - b[2]

        return sqrt(
            dr * dr +
            dg * dg +
            db * db
        )
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

        return "${('a'.code + file).toChar()}$rank"
    }
}
