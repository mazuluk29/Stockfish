package com.trading.stockfishoverlay

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

class BoardRecognizer {

    data class BoardArea(
        val left: Int,
        val top: Int,
        val size: Int
    )

    data class Result(
        val area: BoardArea,
        val boardFen: String,
        val whiteAtBottom: Boolean,
        val confidence: Double
    )

    private var cachedArea: BoardArea? = null

    private val lightSquare =
        doubleArrayOf(
            237.0,
            222.0,
            189.0
        )

    private val darkSquare =
        doubleArrayOf(
            196.0,
            142.0,
            86.0
        )

    fun reset() {
        cachedArea = null
    }

    fun getCachedArea(): BoardArea? {
        return cachedArea
    }

    /*
     * Szybkie sprawdzenie, czy obraz planszy
     * się zmienił.
     *
     * Nie rozpoznaje figur.
     * Pobiera tylko kilka próbek z każdego pola.
     */
    fun quickSignature(
        bitmap: Bitmap
    ): LongArray? {

        val area =
            cachedArea
                ?: findBoard(bitmap)
                ?: return null

        cachedArea = area

        val cell =
            area.size / 8f

        val signature =
            LongArray(64)

        for (row in 0..7) {

            for (col in 0..7) {

                val index =
                    row * 8 + col

                var totalR = 0L
                var totalG = 0L
                var totalB = 0L
                var count = 0

                val points =
                    arrayOf(
                        0.30f to 0.30f,
                        0.50f to 0.50f,
                        0.70f to 0.30f,
                        0.30f to 0.70f,
                        0.70f to 0.70f
                    )

                for (
                    point in points
                ) {

                    val x =
                        (
                            area.left +
                                col * cell +
                                cell *
                                point.first
                            ).toInt()

                    val y =
                        (
                            area.top +
                                row * cell +
                                cell *
                                point.second
                            ).toInt()

                    if (
                        x !in 0 until bitmap.width ||
                        y !in 0 until bitmap.height
                    ) {
                        continue
                    }

                    val pixel =
                        bitmap.getPixel(
                            x,
                            y
                        )

                    totalR +=
                        (pixel shr 16) and 255

                    totalG +=
                        (pixel shr 8) and 255

                    totalB +=
                        pixel and 255

                    count++
                }

                if (
                    count == 0
                ) {
                    continue
                }

                val r =
                    totalR / count

                val g =
                    totalG / count

                val b =
                    totalB / count

                signature[index] =
                    (r shl 16) or
                        (g shl 8) or
                        b
            }
        }

        return signature
    }

    fun signatureDifference(
        a: LongArray?,
        b: LongArray?
    ): Double {

        if (
            a == null ||
            b == null ||
            a.size != 64 ||
            b.size != 64
        ) {
            return 999.0
        }

        var total =
            0.0

        for (
            i in 0 until 64
        ) {

            val ar =
                ((a[i] shr 16) and 255)
                    .toDouble()

            val ag =
                ((a[i] shr 8) and 255)
                    .toDouble()

            val ab =
                (a[i] and 255)
                    .toDouble()

            val br =
                ((b[i] shr 16) and 255)
                    .toDouble()

            val bg =
                ((b[i] shr 8) and 255)
                    .toDouble()

            val bb =
                (b[i] and 255)
                    .toDouble()

            total +=
                abs(ar - br) +
                    abs(ag - bg) +
                    abs(ab - bb)
        }

        return total /
            (64.0 * 3.0)
    }

    fun recognize(
        bitmap: Bitmap
    ): Result? {

        var area =
            cachedArea

        if (
            area == null ||
            !boardStillThere(
                bitmap,
                area
            )
        ) {

            area =
                findBoard(
                    bitmap
                )

            cachedArea =
                area
        }

        if (
            area == null
        ) {
            return null
        }

        val whiteAtBottom =
            detectOrientation(
                bitmap,
                area
            )

        val board =
            Array(8) {
                CharArray(8) {
                    '.'
                }
            }

        var confidence =
            0.0

        var pieces =
            0

        for (
            screenRow in 0..7
        ) {

            for (
                screenCol in 0..7
            ) {

                val result =
                    recognizeSquare(
                        bitmap,
                        area,
                        screenRow,
                        screenCol
                    )

                val piece =
                    result.first

                if (
                    piece == '.'
                ) {
                    continue
                }

                pieces++

                confidence +=
                    result.second

                val fenRow: Int
                val fenCol: Int

                if (
                    whiteAtBottom
                ) {

                    fenRow =
                        screenRow

                    fenCol =
                        screenCol

                } else {

                    fenRow =
                        7 - screenRow

                    fenCol =
                        7 - screenCol
                }

                board[
                    fenRow
                ][fenCol] =
                    piece
            }
        }

        if (
            pieces < 2
        ) {
            return null
        }

        return Result(
            area =
                area,

            boardFen =
                boardToFen(
                    board
                ),

            whiteAtBottom =
                whiteAtBottom,

            confidence =
                confidence /
                    pieces
        )
    }

    private fun findBoard(
        bitmap: Bitmap
    ): BoardArea? {

        val width =
            bitmap.width

        val size =
            width

        val cell =
            size / 8f

        val startY =
            (bitmap.height * 0.15f)
                .toInt()

        val endY =
            bitmap.height -
                size -
                40

        var bestTop =
            -1

        var bestScore =
            -1.0

        var y =
            startY

        /*
         * Krok 4 px zamiast 2.
         * Szukanie planszy wykonujemy tylko,
         * gdy nie mamy już zapamiętanej pozycji.
         */
        while (
            y <= endY
        ) {

            var normal = 0
            var reversed = 0

            for (
                col in 0..7
            ) {

                val x =
                    (
                        col * cell +
                            cell * 0.15f
                        ).toInt()

                val pixel =
                    safePixel(
                        bitmap,
                        x,
                        y + (cell * 0.15f).toInt()
                    )
                        ?: continue

                val normalReference =
                    if (
                        col % 2 == 0
                    ) {
                        lightSquare
                    } else {
                        darkSquare
                    }

                val reverseReference =
                    if (
                        col % 2 == 0
                    ) {
                        darkSquare
                    } else {
                        lightSquare
                    }

                if (
                    colorDistance(
                        pixel,
                        normalReference
                    ) < 60.0
                ) {
                    normal++
                }

                if (
                    colorDistance(
                        pixel,
                        reverseReference
                    ) < 60.0
                ) {
                    reversed++
                }
            }

            val score =
                maxOf(
                    normal,
                    reversed
                ).toDouble()

            if (
                score > bestScore
            ) {

                bestScore =
                    score

                bestTop =
                    y
            }

            /*
             * 7/8 to już bardzo mocny sygnał.
             */
            if (
                score >= 7.0
            ) {

                return BoardArea(
                    left = 0,
                    top = y,
                    size = size
                )
            }

            y += 4
        }

        if (
            bestScore >= 6.0 &&
            bestTop >= 0
        ) {

            return BoardArea(
                left = 0,
                top = bestTop,
                size = size
            )
        }

        return null
    }

    private fun boardStillThere(
        bitmap: Bitmap,
        area: BoardArea
    ): Boolean {

        if (
            area.left < 0 ||
            area.top < 0 ||
            area.left + area.size >
            bitmap.width ||
            area.top + area.size >
            bitmap.height
        ) {
            return false
        }

        val cell =
            area.size / 8f

        var matches =
            0

        var checked =
            0

        /*
         * Sprawdzamy tylko 16 pól.
         * Jest to dużo szybsze niż ponowne
         * szukanie całej planszy.
         */
        for (
            row in 0..7 step 2
        ) {

            for (
                col in 0..7 step 2
            ) {

                val x =
                    (
                        area.left +
                            col * cell +
                            cell * 0.12f
                        ).toInt()

                val y =
                    (
                        area.top +
                            row * cell +
                            cell * 0.12f
                        ).toInt()

                val pixel =
                    safePixel(
                        bitmap,
                        x,
                        y
                    )
                        ?: continue

                checked++

                val parity =
                    (row + col) % 2

                val first =
                    if (
                        parity == 0
                    ) {
                        lightSquare
                    } else {
                        darkSquare
                    }

                val second =
                    if (
                        parity == 0
                    ) {
                        darkSquare
                    } else {
                        lightSquare
                    }

                if (
                    minOf(
                        colorDistance(
                            pixel,
                            first
                        ),
                        colorDistance(
                            pixel,
                            second
                        )
                    ) < 65.0
                ) {
                    matches++
                }
            }
        }

        return (
            checked >= 8 &&
                matches.toDouble() /
                checked >= 0.55
            )
    }

    /*
     * Klasyfikacja figur.
     *
     * Ta część opiera się na geometrii/kolorze
     * figur z obecnego motywu Chess.com.
     *
     * Zachowujemy ją lekką, bo pełna analiza
     * odpala się dopiero po zmianie planszy.
     */
    private fun recognizeSquare(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<Char, Double> {

        val cell =
            area.size / 8f

        val left =
            (
                area.left +
                    col * cell +
                    cell * 0.12f
                ).toInt()

        val top =
            (
                area.top +
                    row * cell +
                    cell * 0.06f
                ).toInt()

        val right =
            (
                area.left +
                    (col + 1) * cell -
                    cell * 0.12f
                ).toInt()

        val bottom =
            (
                area.top +
                    (row + 1) * cell -
                    cell * 0.06f
                ).toInt()

        if (
            left < 0 ||
            top < 0 ||
            right >
            bitmap.width ||
            bottom >
            bitmap.height ||
            right <= left ||
            bottom <= top
        ) {

            return Pair(
                '.',
                0.0
            )
        }

        val background =
            expectedSquareColor(
                row,
                col,
                bitmap,
                area
            )

        var nonBackground =
            0

        var darkPixels =
            0

        var lightPixels =
            0

        var total =
            0

        var minX =
            Int.MAX_VALUE

        var maxX =
            Int.MIN_VALUE

        var minY =
            Int.MAX_VALUE

        var maxY =
            Int.MIN_VALUE

        val step =
            maxOf(
                2,
                ((right - left) / 24)
            )

        var y =
            top

        while (
            y < bottom
        ) {

            var x =
                left

            while (
                x < right
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                total++

                val difference =
                    colorDistance(
                        pixel,
                        background
                    )

                if (
                    difference > 35.0
                ) {

                    nonBackground++

                    val brightness =
                        pixelBrightness(
                            pixel
                        )

                    if (
                        brightness < 125
                    ) {

                        darkPixels++

                    } else if (
                        brightness > 170
                    ) {

                        lightPixels++
                    }

                    minX =
                        minOf(
                            minX,
                            x
                        )

                    maxX =
                        maxOf(
                            maxX,
                            x
                        )

                    minY =
                        minOf(
                            minY,
                            y
                        )

                    maxY =
                        maxOf(
                            maxY,
                            y
                        )
                }

                x += step
            }

            y += step
        }

        if (
            total == 0
        ) {

            return Pair(
                '.',
                0.0
            )
        }

        val fill =
            nonBackground.toDouble() /
                total

        /*
         * Puste pole.
         */
        if (
            fill < 0.10
        ) {

            return Pair(
                '.',
                1.0
            )
        }

        val whitePiece =
            lightPixels >
                darkPixels

        val pieceHeight =
            if (
                minY <= maxY
            ) {
                (
                    maxY -
                        minY
                    ).toDouble() /
                    (bottom - top)
            } else {
                0.0
            }

        val pieceWidth =
            if (
                minX <= maxX
            ) {
                (
                    maxX -
                        minX
                    ).toDouble() /
                    (right - left)
            } else {
                0.0
            }

        val upper =
            regionOccupancy(
                bitmap,
                background,
                left,
                top,
                right,
                top +
                    (bottom - top) / 3
            )

        val middle =
            regionOccupancy(
                bitmap,
                background,
                left,
                top +
                    (bottom - top) / 3,
                right,
                top +
                    2 *
                    (bottom - top) / 3
            )

        val lower =
            regionOccupancy(
                bitmap,
                background,
                left,
                top +
                    2 *
                    (bottom - top) / 3,
                right,
                bottom
            )

        /*
         * Prosty klasyfikator kształtów.
         *
         * Jest przeznaczony konkretnie do
         * używanego przez Ciebie zestawu figur.
         */

        val type =
            when {

                /*
                 * pion
                 */
                pieceWidth < 0.55 &&
                pieceHeight < 0.90 &&
                upper < 0.38 -> {

                    'P'
                }

                /*
                 * skoczek – dużo masy w górze
                 * i asymetryczny kształt.
                 */
                upper >
                    middle * 0.90 &&
                pieceWidth >
                    0.55 -> {

                    'N'
                }

                /*
                 * wieża – szeroka góra i szeroki dół.
                 */
                upper > 0.35 &&
                lower > 0.45 &&
                abs(
                    upper -
                        lower
                ) < 0.22 -> {

                    'R'
                }

                /*
                 * hetman – bardzo szeroka figura.
                 */
                pieceWidth > 0.78 &&
                upper > 0.30 -> {

                    'Q'
                }

                /*
                 * król – wysoki i duży środek.
                 */
                pieceHeight > 0.82 &&
                middle > 0.38 -> {

                    'K'
                }

                /*
                 * pozostały wysoki,
                 * wąski kształt = goniec.
                 */
                else -> {

                    'B'
                }
            }

        val piece =
            if (
                whitePiece
            ) {

                type

            } else {

                type.lowercaseChar()
            }

        return Pair(
            piece,
            fill
        )
    }

    private fun regionOccupancy(
        bitmap: Bitmap,
        background: DoubleArray,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Double {

        if (
            right <= left ||
            bottom <= top
        ) {
            return 0.0
        }

        var active = 0
        var total = 0

        val step =
            maxOf(
                2,
                (right - left) / 18
            )

        var y = top

        while (
            y < bottom
        ) {

            var x = left

            while (
                x < right
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                if (
                    colorDistance(
                        pixel,
                        background
                    ) > 35.0
                ) {

                    active++
                }

                total++
                x += step
            }

            y += step
        }

        if (
            total == 0
        ) {
            return 0.0
        }

        return active.toDouble() /
            total
    }

    private fun expectedSquareColor(
        row: Int,
        col: Int,
        bitmap: Bitmap,
        area: BoardArea
    ): DoubleArray {

        val cell =
            area.size / 8f

        val sampleX =
            (
                area.left +
                    col * cell +
                    cell * 0.08f
                ).toInt()

        val sampleY =
            (
                area.top +
                    row * cell +
                    cell * 0.08f
                ).toInt()

        val pixel =
            safePixel(
                bitmap,
                sampleX,
                sampleY
            )

        if (
            pixel != null
        ) {

            val r =
                ((pixel shr 16) and 255)
                    .toDouble()

            val g =
                ((pixel shr 8) and 255)
                    .toDouble()

            val b =
                (pixel and 255)
                    .toDouble()

            /*
             * Jeśli próbka przypomina jeden
             * z kolorów planszy, używamy jej.
             *
             * Dzięki temu działa również
             * przy lekkich różnicach jasności.
             */
            if (
                minOf(
                    colorDistance(
                        pixel,
                        lightSquare
                    ),
                    colorDistance(
                        pixel,
                        darkSquare
                    )
                ) < 70.0
            ) {

                return doubleArrayOf(
                    r,
                    g,
                    b
                )
            }
        }

        return if (
            (row + col) % 2 == 0
        ) {

            lightSquare

        } else {

            darkSquare
        }
    }

    private fun detectOrientation(
        bitmap: Bitmap,
        area: BoardArea
    ): Boolean {

        val top =
            rowPieceBrightness(
                bitmap,
                area,
                0
            )

        val bottom =
            rowPieceBrightness(
                bitmap,
                area,
                7
            )

        return bottom > top
    }

    private fun rowPieceBrightness(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int
    ): Double {

        val cell =
            area.size / 8f

        var total =
            0.0

        var count =
            0

        for (
            col in 0..7
        ) {

            /*
             * Kilka punktów w środku figury,
             * nie tylko jeden pixel.
             */
            for (
                yFraction in
                listOf(
                    0.35f,
                    0.50f,
                    0.65f
                )
            ) {

                val x =
                    (
                        area.left +
                            col * cell +
                            cell * 0.5f
                        ).toInt()

                val y =
                    (
                        area.top +
                            row * cell +
                            cell *
                            yFraction
                        ).toInt()

                val pixel =
                    safePixel(
                        bitmap,
                        x,
                        y
                    )
                        ?: continue

                total +=
                    pixelBrightness(
                        pixel
                    )

                count++
            }
        }

        return if (
            count > 0
        ) {

            total /
                count

        } else {

            0.0
        }
    }

    private fun boardToFen(
        board:
            Array<CharArray>
    ): String {

        return buildString {

            for (
                row in 0..7
            ) {

                var empty =
                    0

                for (
                    col in 0..7
                ) {

                    val piece =
                        board[row][col]

                    if (
                        piece == '.'
                    ) {

                        empty++

                    } else {

                        if (
                            empty > 0
                        ) {

                            append(
                                empty
                            )

                            empty = 0
                        }

                        append(
                            piece
                        )
                    }
                }

                if (
                    empty > 0
                ) {

                    append(
                        empty
                    )
                }

                if (
                    row < 7
                ) {

                    append("/")
                }
            }
        }
    }

    private fun safePixel(
        bitmap: Bitmap,
        x: Int,
        y: Int
    ): Int? {

        if (
            x !in 0 until bitmap.width ||
            y !in 0 until bitmap.height
        ) {
            return null
        }

        return bitmap.getPixel(
            x,
            y
        )
    }

    private fun pixelBrightness(
        pixel: Int
    ): Double {

        val r =
            (pixel shr 16) and 255

        val g =
            (pixel shr 8) and 255

        val b =
            pixel and 255

        return (
            r * 0.299 +
                g * 0.587 +
                b * 0.114
            )
    }

    private fun colorDistance(
        pixel: Int,
        reference: DoubleArray
    ): Double {

        val r =
            ((pixel shr 16) and 255)
                .toDouble()

        val g =
            ((pixel shr 8) and 255)
                .toDouble()

        val b =
            (pixel and 255)
                .toDouble()

        val dr =
            r -
                reference[0]

        val dg =
            g -
                reference[1]

        val db =
            b -
                reference[2]

        return sqrt(
            dr * dr +
                dg * dg +
                db * db
        )
    }
}
