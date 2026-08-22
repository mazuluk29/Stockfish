package com.trading.stockfishoverlay

import android.graphics.Bitmap
import android.util.Base64
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

    /*
     * Wzorce wygenerowane z dwóch screenów,
     * które wysłałeś.
     *
     * Wielkie litery = białe.
     * Małe litery = czarne.
     */
    private val encodedTemplates =
        mapOf(

            'R' to
                "1uDY2NfW1tjW1tjW1tjW1tfs/Q4CHAMLARwPGh0R4tbk8g8SOxcIHycSCw4CIwbY4u//MvQ3DA0TN/zZCQIG1tnnGhd49+cOJAL4LA5AB9jZ2g06EyTc4vX7C/hTOeXW1tnaFyIKOSsxN/8yR+PY2NfY1+sFPzsgLUwtCwnY2NfW2NbrAzUm9gc5JgEK1tnX1tfW7QcWMxsjOxQbEtfX2NbX3CEYEAQGDhUOEF/u2NbW2BIoMyL9/QQQIRVKRN/X1uYtFkvk3uPsAg4tClAG6tcUEDju8vT09vn3+RsQNPXWCh0gAQECAgICA/8cFS/n1u3r2tjW1tbX2NjY1uwC1g==",

            'N' to
                "x8neyd3MydDIyMnIyMnIx8jOKA8JDAJPGwPtz8fJysnHywQENytHJREJFiPtycnJx8sUEy4IF2VLQTMOPQTKyMnwESJ4DPYkAgVAagVBAMnKBAMo8HIU3+PK5RllBUXKzBQuFXIT6uk55s7uIFIU7fcOUdXe3vx2NzzO4vw9BA8WJC7P60lnUz5Vy+DqEy8D3O4eLWod+OtyI8rg5fw95QwS/WEZDvQ7Wd/K2+r6QNgKKAkNKPAXY+XLzdjtAkXT1gkmKPgGZdzMysrY8wc/zMjKyu4EM/wCAgICBhQYPczIysjy/gUiFxMVGBURCRYex8rJ0efbyMjKyMjJytTrxw==",

            'B' to
                "3+Df4N/g9/zz/OHg4OHg3+Dg4eHh8AMGAw374N/g4eDf4eDg4O8OAfUO+9/g4eDh3+Dh4eULDzgxExjo4eDh4ODg4eMUDkchHUQTI+rh4OHh4OEHDln6bHDjQhIW5OHg4OHrAicTb2o7YfwXDPbh4eDh8AEkCER4bUfzEwX74eDg4eULDl4V/goJSQwW7+Lg4ODf8iAvAeHqASkm/+Hg4N/g3+ELFyr4+SwPFeTg4ODk9Pn7Ow1NRT1DBzcF+/jqEQYEBAs5FfjsECwQAQMHJvMg9AgBCxYeCg0R/QbxJAUQBQIBB/oIEBkI+gUBA/0k3+Pq6vDg8/Do9Obt7+Tz3w==",

            'Q' to
                "xsbHz8fO4vTd9cnKz8rGxhAZAT7iNOQV+wITEEQiDSIiBxDf99/dCBYAzw7r6jL0zwfuLiFN5FYJN2EOV+IS4B//WuQ/GvchI/tDET9CMtIZQxsPBhod5AsT/Bj0Qv0xySH4BvsYHAAALv7jIfQg5sYG6BMc1A0mGd0QF+b3/9HGFP3i4Rr/3g/tE/zqGAzHxi8ZD+Tq1/MC7wjvAjdTxsYeOOXf2O3l2MfW3f5HX8bG3En0++L5B/zx7P78eBTHxtYx4xX++vDl4uwE7lwF2Mb/RfseIhwL/Pfx4dZNMtzG6k5hIyEeGx4hKjdWav/SxsbH0uDcz8nHy9bi2c7kxg==",

            'K' to
                "0tTU1NLU4OXg49bU1dbV0tXX1tXW3R9IPy/f0tLW2NbS2dbg4OYxNCA86ODg1tfW1Onl6uPgCXB4D+Lg4enu2ujsPkM7PBhcbBxCODEi4vfnd1YXFEU0EwlQWSEXQFL22WLU4ub2JxgpRujn6PNA2dlE3+Xp7igUPjvd6/sELN/VdPr5Awkh8SsxEhkXDEDU70dlIwT1AAcKGxooMDwHCeX+ISsB49zy5wcZFhwAGfPU7OoY9Prm4+rp1OLgAvzV1ejnLicB+ff28e0M9wL349X94h9KQTUkGA4IBe0HIfvU8h7v5+Lj3dzk6PgFNSjl0tbV2ufg1dXX0tno3t/30g==",

            'P' to
                "3uHf4N7e3+Df3+Df3+De3uDi4uDf8xAMCxwG3t7f4d/f5eDf8BwdPDoaOgXf4N/g4uHh4Q0fbQUgVRsv7d/h3+Hf4OMHITQMITwGI/ng3+Dg4ODgDxB4LDROEDDs4OHf3+Hg3w4rCgQgAzUw4+Dg4N/g3/sUCg/uExsAPRjg4N/f4N4HCwUR8RAABDYk4uHf3t/e4AsSLSxIKBUm5N/f4N7f3ukgE2DuIk4UQ/nf4N/f3+geFGwP8wYnRBdG9eDf3+EOF3IY7O74CR42GjHp39/wCjAvFRMQEhIOGgMpBd/e+wUMRzs2MSkeFREJJRXg3uj/+eDg39/g4OHf9Abw3g==",

            'r' to
                "3OHe3t3c3d7d3d7d3d7c3OHm8B0WGP8xNxoKRUkJ5t3h4xQV9REhLBxASUdJSv7e4+cQAez7EPPzFSfwK03+3eHmIB0CAf/79/Tx4kdU+97e3vtHL+rp6+3t5j54GuTd3d7fAT8fBgcE+RtpJ+Le3t3e3ukSC/H7AwAcUgTe3t3d3t3pFAzt9QH+G1EF3d/d3d3d7CYS6fb/+B1cCt7d3tzd4hcr9+/6+fPvXlbv3t3d3QYv+eXy8/Hy7g1uMuPd3eglDOjo8/Xx9PPrSmT63d0SJOvp7vTu6+/w8PNbQt7dFh0IDhEeJy02PkNBT0Ld3Ovr4N7d3d3e3d7d3uvt3A==",

            'n' to
                "29zq3ejf3OLc3N3c3N3c29zfISszPTZXKRX44dvd3d3b3RI2CgD1NlVZWTf33d3d290SEf4G/ez2CTJtagrd3Nz1IxIh++Dv7O7y9Vp3Bt3dCCcnBOzo6OLi6fLjY1/d3SEiEfjv+Of14eLs7wp49PwxDQbh8eQF9uvf6e7eThIeJxL24uUFEOnq3OXv5yEY6xD68/NINSUa4t/n7ecECRoxBvRcQyRA9N7f5+7n8vgLVk1VSwk4/+Xi4uzv5Ojp4gghLQcbDgLp4ubw6+Hn3tzd3PIEBxwF5eLo6ujn7Ofb3dz2EA0eMTU1OEFHSUo/29zd4e7n3Nzc3Nzc29zd2w==",

            'b' to
                "19nX2dfZ8fjt+dnY2NnX19nZ2drZ6CJIMUb719fZ2tnX2tjZ1+QxQRJL9NfZ2dnZ19nZ2d4IMij6Wyzj2tna2dnY2dwRFgkRACJ0ROTZ2Nra2dkBFvIkL//nFXgu3drY2NrhDf8IPw3p7N8/UfHZ2tnZ5xMMEBf+8t/bNVL32tjY2dwaNAT55N3m8WtF5trZ19nX5z9DFvTh8Ehp9dnY2dfZ19oKGP8M+e5ONdzY2dne8PT3OTsUE/XkTVf/9/TjCRwqNygxOP70LzwuSjo2KuccGBcmBvw4PfkLLh0RIPwIKig3KRcwTFVWLTlJODIl19ng3unb6eTd693o4t3a1w==",

            'q' to
                "zM3N0s3U3+/d7dLR1NPNzP0P9CkbN9syGxsDMkYaBBUVLgUQ8xPgL/YZDBUfAzdCzfoeEQUr4C78FjveUgA63hjmPOEqC/Iq/AQ47i8tHy8CNBgKHegWEvIIIwkIQO4kzTPZNO3pKOfSJPYDFRQi288fHfQP9Pnt9/ve5A3iMc/OFh/z7evU/PHlBeb6BkHOzBo97+Dc5fTg6vXrCzNUz8wCPP/66Ojg1dXZ2wF3QM7N2SUK/gTx6N3Y3Nk1eAHNztgN8PYL++rf2dnZMWj8zc3yKBYXA+jb0dPl8h9hJs7N4yNMO0RFREVKT1hySPfOzM/Oz9na2M/S1NXb1M3NzA==",

            'k' to
                "19fX19fX4+fj59fX19fX19fX19fX4iA6My3o19fX19fX193o6OcmJRMy7Ofo4NjX2fkhLDY3OyoUTiQzOzsM3Pc6LSMuQUwH+DYmMzdQchIYDuz09foBA+f/7/fs8jpIBub39/Tv5e7+9Ozk5efhHtrw9/jz5uLfAevh4+rt4uMU+vX27eLg5/fu6u/x7PY5F04F6+no5enr8fL07wR1QelFXRH87ufi4eru7Ah0afjX7jUoAPrs4t/f39s/bATY1+soGfj17uzn4+HbPGoB19cDOyP87eXg2Ob1ADFqI9fX7S9XT1NOS0xQWmN4R/rX19fY3ODi493b4uPh3djX1w==",

            'p' to
                "4+Xk5OPj4+Tj4+Tj4+Tj4+Tm5uTk8BMbKzcC4+Pk5ePk6OTk7h4aIC5IWAHk5OTk5uTl5AoZBx8I/lo97eTl4+Tj5eYTFBES/fI7TPXk4+Tk5OTkEikbCwHyWUHr5OXj4+Xk4wQ4MP/uIlMl5uTk5OTk4/YaFgsG++csWRTk5OTk5OP/GSIV/vT2PEke5eXk4+Pj5AEvLQP7LFAe5+Pj5OPj4+oiPQv5/fVlVvbj5OPj4+kaKwj9/fbzDXVV8+Tj5OUJIP0GC/rv7u0VeDjq4+TuFQT4FQT27e3v6kBbAuTj9hkSABkiKzE1OT1RUw/k4+n39ufl5OPk4+Tk8/3t4w=="
        )

    private val templates: Map<Char, FloatArray> =
        encodedTemplates.mapValues {
            decodeTemplate(it.value)
        }

    fun recognize(
        bitmap: Bitmap
    ): Result? {

        val area =
            findBoard(bitmap)
                ?: return null

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

        var confidenceSum = 0.0
        var pieces = 0

        for (screenRow in 0..7) {

            for (screenCol in 0..7) {

                val result =
                    recognizeSquare(
                        bitmap,
                        area,
                        screenRow,
                        screenCol
                    )

                val piece =
                    result.first

                if (piece == '.') {
                    continue
                }

                pieces++

                confidenceSum +=
                    result.second

                val rankIndex: Int
                val fileIndex: Int

                if (whiteAtBottom) {

                    rankIndex =
                        screenRow

                    fileIndex =
                        screenCol

                } else {

                    rankIndex =
                        7 - screenRow

                    fileIndex =
                        7 - screenCol
                }

                board[rankIndex][fileIndex] =
                    piece
            }
        }

        /*
         * Musi być przynajmniej sensowna liczba figur.
         * Chroni przed uznaniem menu za planszę.
         */
        if (pieces < 2) {
            return null
        }

        val placement =
            boardToFen(board)

        val confidence =
            if (pieces > 0)
                confidenceSum / pieces
            else
                0.0

        return Result(
            area = area,
            boardFen = placement,
            whiteAtBottom = whiteAtBottom,
            confidence = confidence
        )
    }

    private fun findBoard(
        bitmap: Bitmap
    ): BoardArea? {

        val size =
            bitmap.width

        val cell =
            size / 8f

        /*
         * Chess.com na Twoim telefonie pokazuje
         * planszę na pełną szerokość.
         *
         * Szukamy górnej krawędzi planszy.
         */
        var y = 120

        val end =
            bitmap.height -
                size -
                80

        while (y <= end) {

            var matching = 0

            for (col in 0..7) {

                val x =
                    (
                        col * cell +
                        cell * 0.50f
                    ).toInt()

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val expected =
                    if (col % 2 == 0)
                        lightSquare
                    else
                        darkSquare

                val distance =
                    colorDistance(
                        pixel,
                        expected
                    )

                if (distance < 50.0) {
                    matching++
                }
            }

            /*
             * W Twoich screenach wszystkie 8 pól
             * jest wykrywane poprawnie.
             *
             * Dajemy tolerancję 6/8.
             */
            if (matching >= 6) {

                return BoardArea(
                    left = 0,
                    top = y,
                    size = size
                )
            }

            y += 2
        }

        return null
    }

    private fun detectOrientation(
        bitmap: Bitmap,
        area: BoardArea
    ): Boolean {

        /*
         * Najpewniejsza pierwsza wersja bez OCR:
         * sprawdzamy kolor figur w pierwszym
         * i ostatnim rzędzie.
         *
         * Przy typowej pozycji Analysis / Game Review
         * wystarcza.
         */

        val topBrightness =
            rowPieceBrightness(
                bitmap,
                area,
                0
            )

        val bottomBrightness =
            rowPieceBrightness(
                bitmap,
                area,
                7
            )

        /*
         * Białe figury są wyraźnie jaśniejsze.
         */
        return bottomBrightness >
            topBrightness
    }

    private fun rowPieceBrightness(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int
    ): Double {

        val cell =
            area.size / 8f

        var total = 0.0
        var count = 0

        for (col in 0..7) {

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
                    cell * 0.55f
                ).toInt()

            val pixel =
                bitmap.getPixel(
                    x,
                    y
                )

            val r =
                (pixel shr 16) and 255

            val g =
                (pixel shr 8) and 255

            val b =
                pixel and 255

            total +=
                (
                    r +
                    g +
                    b
                ) / 3.0

            count++
        }

        return if (count > 0)
            total / count
        else
            0.0
    }

    private fun recognizeSquare(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<Char, Double> {

        val featureResult =
            createFeature(
                bitmap,
                area,
                row,
                col
            )

        val feature =
            featureResult.first

        val edgeStrength =
            featureResult.second

        /*
         * Puste pola w Twoim motywie mają
         * bardzo mało krawędzi.
         */
        if (edgeStrength < 8.0) {

            return Pair(
                '.',
                1.0
            )
        }

        var bestPiece =
            '.'

        var bestScore =
            -999.0

        for (
            entry in templates
        ) {

            val score =
                dot(
                    feature,
                    entry.value
                )

            if (score > bestScore) {

                bestScore =
                    score

                bestPiece =
                    entry.key
            }
        }

        /*
         * Jeżeli wzorzec jest bardzo słaby,
         * uznajemy pole za puste / niepewne.
         */
        if (bestScore < 0.35) {

            return Pair(
                '.',
                bestScore
            )
        }

        return Pair(
            bestPiece,
            bestScore
        )
    }

    private fun createFeature(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<FloatArray, Double> {

        val cell =
            area.size / 8f

        val left =
            (
                area.left +
                col * cell +
                cell * 0.08f
            ).toInt()

        val right =
            (
                area.left +
                (col + 1) * cell -
                cell * 0.08f
            ).toInt()

        val top =
            (
                area.top +
                row * cell +
                cell * 0.05f
            ).toInt()

        val bottom =
            (
                area.top +
                (row + 1) * cell -
                cell * 0.05f
            ).toInt()

        val width =
            right - left

        val height =
            bottom - top

        val crop =
            Bitmap.createBitmap(
                bitmap,
                left,
                top,
                width,
                height
            )

        val scaled =
            Bitmap.createScaledBitmap(
                crop,
                16,
                16,
                true
            )

        crop.recycle()

        val gray =
            FloatArray(256)

        for (y in 0 until 16) {

            for (x in 0 until 16) {

                val pixel =
                    scaled.getPixel(
                        x,
                        y
                    )

                val r =
                    (pixel shr 16) and 255

                val g =
                    (pixel shr 8) and 255

                val b =
                    pixel and 255

                gray[y * 16 + x] =
                    (
                        r * 0.299f +
                        g * 0.587f +
                        b * 0.114f
                    )
            }
        }

        scaled.recycle()

        val gradient =
            FloatArray(256)

        var sum =
            0.0

        for (y in 1 until 15) {

            for (x in 1 until 15) {

                val gx =
                    gray[
                        y * 16 +
                            x +
                            1
                    ] -
                    gray[
                        y * 16 +
                            x -
                            1
                    ]

                val gy =
                    gray[
                        (y + 1) * 16 +
                            x
                    ] -
                    gray[
                        (y - 1) * 16 +
                            x
                    ]

                val magnitude =
                    sqrt(
                        (
                            gx * gx +
                            gy * gy
                        ).toDouble()
                    ).toFloat()

                gradient[
                    y * 16 + x
                ] = magnitude

                sum += magnitude
            }
        }

        val average =
            sum / 256.0

        var norm = 0.0

        for (i in gradient.indices) {

            gradient[i] =
                (
                    gradient[i] -
                    average
                ).toFloat()

            norm +=
                gradient[i] *
                    gradient[i]
        }

        norm =
            sqrt(norm)

        if (norm > 0.0001) {

            for (i in gradient.indices) {

                gradient[i] =
                    (
                        gradient[i] /
                        norm
                    ).toFloat()
            }
        }

        return Pair(
            gradient,
            average
        )
    }

    private fun decodeTemplate(
        encoded: String
    ): FloatArray {

        val bytes =
            Base64.decode(
                encoded,
                Base64.DEFAULT
            )

        val result =
            FloatArray(
                bytes.size
            )

        var norm = 0.0

        for (
            i in bytes.indices
        ) {

            result[i] =
                bytes[i]
                    .toFloat()

            norm +=
                result[i] *
                    result[i]
        }

        norm =
            sqrt(norm)

        if (norm > 0.0001) {

            for (
                i in result.indices
            ) {

                result[i] =
                    (
                        result[i] /
                            norm
                    ).toFloat()
            }
        }

        return result
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray
    ): Double {

        val count =
            minOf(
                a.size,
                b.size
            )

        var result =
            0.0

        for (i in 0 until count) {

            result +=
                a[i] *
                    b[i]
        }

        return result
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

    private fun boardToFen(
        board:
            Array<CharArray>
    ): String {

        val result =
            StringBuilder()

        for (row in 0..7) {

            var empty =
                0

            for (col in 0..7) {

                val piece =
                    board[row][col]

                if (piece == '.') {

                    empty++

                } else {

                    if (empty > 0) {

                        result.append(
                            empty
                        )

                        empty = 0
                    }

                    result.append(
                        piece
                    )
                }
            }

            if (empty > 0) {

                result.append(
                    empty
                )
            }

            if (row != 7) {

                result.append(
                    "/"
                )
            }
        }

        return result.toString()
    }
}
