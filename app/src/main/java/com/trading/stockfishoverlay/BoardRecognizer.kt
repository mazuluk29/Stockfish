package com.trading.stockfishoverlay

import android.graphics.Bitmap
import android.util.Base64
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

    /*
     * Kolory Twojego motywu Chess.com.
     */
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
     * Średnia różnica jasności potrzebna,
     * żeby uznać pole za zajęte.
     *
     * Na Twoich screenach:
     * puste pola ≈ 0-5
     * figury ≈ 15-70+
     */
    private val occupiedThreshold =
        10.0

    /*
     * Próg określający kolor figury.
     *
     * Czarne figury mają zwykle medianę
     * około 45-60.
     *
     * Białe około 100-210.
     */
    private val whitePieceBrightnessThreshold =
        95.0

    /*
     * Każdy wzorzec ma 20×20 = 400 wartości.
     *
     * Osobne wzorce białych i czarnych są używane
     * do rozpoznania KSZTAŁTU.
     *
     * Kolor figury ustalamy potem niezależnie.
     */
    private data class PieceTemplate(
        val piece: Char,
        val scale: Double,
        val encoded: String
    )

    private val templateDefinitions =
        listOf(

            PieceTemplate(
                'R',
                721.7110998680872,
                "/////v////////////7+/v/+/v4PDA4NDQ4ODQ4NDA4NDAwNDgwMEA8MDg4LDQ0MDgoKDgsKCgsNDQsQDwsNDunEyOXvwb/i7LKu3AsODA8PCw0N2iM1v74YHbOy09TECwwPEA8MDAvaMGY8N1NIC+v22McNCwwQEA4LC9nG/Pr38OXOuq6OxQ4MCxAQDwsKAbfF/wMA8dTBnqX6DA4MEA8ODQoLCsXS8+3fvpavBwsLDQ4QDwwOCwwO3yxpYU8e0swOCwoLDxAQCw4LDA7fKWZhUiXVzQ4LDQsOEBALDgwLDt8qaWNVKtnODgwPDQwQDwsODAsNzuMLBvveqLcLDA8ODBAPCw4MCt7ZLzApGf3WnMsKDw8MEBALDgwEsQYOCAD24b+iiP0ODgwQDwsOBefCRUM/Ni4Z9NOX4AQNDBAPDA7m1PgLCwgB+OvUwLCh0goNDxAMDeP5NTMyLSUcDfru6b/PDxAPEA4N/+Tp5+bl5eTl4eDi3fsUEhAQEA8PEBAPDw8PEBAODxAPEBEQEA=="
            ),

            PieceTemplate(
                'N',
                759.3603686503277,
                "9vb29vb29vb29vb29vb29vb29vcJBwgHBgcICAgHBggHBgYHCAYGCAkGBwjiuO3nzwAFCAYFBQUHBgUICQYGCNzRwLPIxfP+BQYFBQUHBwgJBgYG8uIR/QiRsa655QMGBQUICQkGBgXg7Uc4J/EdHOqvvP4GBQcJCggG/cku2g8nO0A8KgfAswEHBggJCQbw5e+IESk/Pjw7Jf6xzAcHCAkIBtMOKBsoPT4lOz02G+2j9ggJCQf00jkqJDpGK9E4QT0nBL7ICQoJAsQZPDM3OCjWtTI+OCQP46kECgnn0eYiMhfWuKHNNjw3IhX3o/UKCd7T5BMIvdb3ugo7PjkmF/qx6gkJ+Lbuurq+As3wNz0+OisW96/YCQgF8sOuvPbQ9DY5Ozw4KxLsq9MJCAYIBgUF4+4+NzU3ODUoCuOozwkJBwcFBQXLPD40NTk6NSQB3KjOCQgHBgUFBMweEgcFBQUC9tm+ls4JCQgGBQUI59/e3t/f3t7e2tbS+AoKCQgICQkICAgJCgkICQoJCg8PCg=="
            ),

            PieceTemplate(
                'B',
                599.1827444634404,
                "/////v7///////////7///////8NCw4MCwwMDA0GBQ0LCgoLDAoLDg0KCwwKDAwL+s7H+QoJCQkMCwoODQoKDQoLDgvoEvvhCQkJCQoMDA4OCgoLDAsNC/W0rfMJCQkKCQoODg4KCgoMCw0C1REEzwIKCg0KCQsPDgwJCgsM+9YnVEUCxvkLCgwKCg4NDQoKCgPYQFsaEzcIwwMJCgwKDg0MDAoK6hliHaCeAinn6AkJCw0ODQoNCgrjNVsgpacKLgXgCgkKDg4NCg0KCuoJU0wiHzkt5OoKCwkNDw4KDAsKBdAMJBsUDfDHBQoOCgsPDQoMCwoNALr5CwXkswEMCg0MCw8NCgwLCQz93D0mHh7J+wwKDQwLDg0KDQoJCtuIrL3Dr43cCwoNDAoODQn24N/i58iarbWfyOnh3N/1CA4N+8saFv7t9BvX0wvi3+4D/rj3Dg0I0ODW0+sF6czL3/nm1NbWwwcODQ376/z15uLsBgfw4eb1/e3/Dw8ODg4NDg4ODg4ODg4NDQ4ODg8PDg=="
            ),

            PieceTemplate(
                'Q',
                927.5746482680175,
                "EBMUExMTExITExMTExMTExMUFBMrLy8vLy8vLy8rKS8uLy8vLy8vLysvLy8tC/UeId7UEib5ACwvLy8vKywZJB3jAt8C9v3o9t/QBicYKC8q9t/VE93q4R/EsA76zsoH3dPdKyrfD9gUC5n6L9u7KxSd8iTK/sokKxHIqSEfw98r3dEe8bgJLL6sAC0rLwu88Rvl3g/f6AjT0gQIs+4uLysvHdrdAvgC3Pf/2tnh+tTRCC8vKy8r3xDI9yeuCRKn/ujF8dMcLi8rLy/pIeHlMtoWHtAc4LwPzysuLysvL/v8DOzU08W9y7nF8ufdLi4vKy8uDaji8BP65tzNza6miPIvLi8rLy8n0Ovi6enYyMi5n5qhGi8uLysvLy713Pjl39K/vLKllc0uLi4vKy8vLvbxGx0XC/bt48+szC0uLS8rLy8swse2q6qnpqafn6GdHycdLisvLyvUn52mpqipp62kor4cHRwtKy8vLy0jEAP6+Pj6AQ0fLCsdHS4rLy8uLy8uLi4uLi4vLy8vLisrLw=="
            ),

            PieceTemplate(
                'K',
                707.4074611352413,
                "/Pn6+vr6+fr6+vr5+fj4+Pj4+PkA+fv6+fv7+vv49vr6+Pj6+/n4/AD4+fr3+/v5+tXJ+vj29vb6+vb8APj2+vj6/fngkYjS9vb29vb6+fwA9/b4+vn9+PS3qfD29vb29vf7/QD49vTq5Obr6drW4O3h4ej09vj9APrt3AggFfHMQTvI7AoP+9fp9vwA+OA7WVRNQwAtK/5OS0M+IdLw/ADoFFxWUElAHwAMMVdQRTsx9d38AOEtWlZQRjwu6PBOVFBFNCP91/4A4xdXUEpANCnxBVRSSjojFO3b/gDx4j1IQjcrG/UMQUM4JRQFzO3+APjp3wcH+Pby8PP08eTk483h+P0A+PrwzS5FRT40LCMXCfO77vv4/QD3+vjZBw4C/vvz5NnW0cf6+/j8APj799wsSEM5LyYbEQfuyPr6+PwA+Pr0xfjk2+Hh3t7TztGz8P/+/AD5+vTCu8rX3OLk397QwLv0DQz+APr69vb059/a3N3e3+Xx+PsKB/8B/f37/f37+/v7/f37/f38/f8A/g=="
            ),

            PieceTemplate(
                'P',
                743.4593470851283,
                "CgoKCgoKCgoKCgoKCgoKCgoKCgoKBwkHBwkJCAkIBwkIBwYHCQcICgsGCAgGCQkICQYGCgYGBgYICAcKCwYGCAcICgLXx8TL/QYGBgYJCAoLBwYHCQgJzA1bPdCuAgYGBQYKCwsHBgUICPffc146+5TnCAkHBQgLCwkFBQcJ5/xrTijroNgICAkHBgoLCgYFBwj20U8zBNaQ7AkGBgkHCgoJCAUHCQTBwvTRjKYCCQYFBwkLCggJBQYI18vpAOKljr8IBQYGCgsKBwkGBwS3KCQT8sqwiP0HCAYJDAoHCQcHB+TFv//VjLLcBgYKBwgLCwcIBwcJBckPRRPCsQUJBgoJBwsLBwkHBgjf109MKfaczAcGCQkHCwoHCQcF3dpQV0o1EOCWzAYJCQcKCgcJB/PRXWJWTD4i/NiV6QgIBwoKBwkG0ClwYFlRQScH6bu5CAgICgoICQbAHTYoIBoN++LEsZ0FBwkKCgkIBtzDw8G/v7++ubW10AYHCgsLCwoKCgsJCgoKCwsKCgsKCwoLCw=="
            ),

            PieceTemplate(
                'r',
                1200.1991622420144,
                "JSUlJSUlJSUlJSUlJSUlJSUlJCUwLS8uLi8vLi8uLS8uLS0uLy0tMDAuMC8sLi4tLywrLywrKywuLiwwMC8xMBTk4hAY2dQLFtTPCSwvLTAwLzAvBdjP3eGso8/alY/wLC0vMTAuLi0F3dbKvK2kmZKNjvItLC0xMS4sLAfCvreqoJiSjYuP9y8tLDAwLywsJuimoJ6alIyIk9khLC8tMTAuLiwtLPO8sqWZjYreKiwsLi4wMC0vLC0uCtzFtqaTjPQvLCwsLzEwLC8sLS4J1b+2qJWN9C4sLiwvMTAsLy0sLgjSwbenlI30LiwvLi0xMCwuLSwu+raxqJ2RjOQsLS8uLTAwLC4tLAva0sOvoJWNnPosLy8tMTAsLy0o1dLGuaiflo2JriMuLy0wMCwvKRTP1cm7q6Oaj4ijDikwLTAwLS8R1MXBuaqhm5WNiY2gBDQvMDAtLg7TzsfCtKqknpWSkpT+MTAwMC8uJAoKBwYFAwQEAgIDAyEsMDEwMC8vMDAvLy8vMDAvLzAvMC4wMQ=="
            ),

            PieceTemplate(
                'n',
                1650.4747876220222,
                "RkVGRkZGRkZGRkVGRkZFRUVFRkdUUVNQUVFSUlJRUFNRUFBRUlFQU1RPUVExADk0G0tQUlBPT09RUU9TVE9PUii2usyzDkBLTk9PT05RUVNUT09RPtazq56dr8T0MU1PTk9TVFRQT08s6dqwoZeYk46n/klQT1FUVFFPSPHctqegpKKeloyS70xRT1NTUlA83riXpJ+noZ+blIyXFFJQU1RSUR3cqZyerK+koJ6akIm7QlJUVFBA79Wln6+9rpadnpuQiZEQUlRUTf3RtqSkramgjpqbmpKJiNZOVFQzvq2loZ6py+SYnJybkYmIsUJUVCmlmJqXsSFE65menJqSioihNlRTRNGhlJX7TgysoJ+dmpGJiJQkVFNPPQfy+UIWxaiinpyajoiIkB1UU1BTUE5OL/XEopubm5SLiIiQGlNTUFJPTk4NDb2fmJmVjYmIiJAaU1RRUU5OTgX5v6Wen5qTkI+QlyBUVFNQTk9SMyQfHB0eHBsdGx0fRlRUVFNSU1RSU1NTVFRTU1RTU09OVA=="
            ),

            PieceTemplate(
                'b',
                1066.2377287786103,
                "HBwcGxsbHBwcHBwcHBsbHBwcGxwlJCYkJCUlJCUgHyUkIyMkJSMjJiUjIyQiJSUkF9bJFSMiIiIlJCMmJSMiJSMkJiMEvJv6IiIiIiIlJCYlIyMjJSQmIxKwqBEiIiIiIiMmJiYjIyMlJCYd7buk4BwiIyUjIiQnJiQiIyQkF+rhuJ+czhUkIyUjIyYlJSMiIx3p7sigmpiUxh4iIyUjJiUkJCIjCObcqY+Rk5OZBSIiIyUmJSMlIyP+3cWhj5WUkZP3IiIjJiYlIyUjIwjFtZ+WmJORmQYjJCIlJyUjJSMjH9mpm5aUkZbTICMmIyQnJSMlIyMkG82qmpKPxBwkIyUlJCclIyQjIiQZ3Naql5OvFyQjJSUjJiUjJSMiIwCrmYyJipoAJCMlJCMmJSIU//oBCO2kiImk7Qr/9/0UJSYlGNjPta64u62fmpyqpZ2os8IeJiUi4rGzpqKoseHgppuXorOm2CcmJSUYCxkTBPwKHyEM+wMTGQsWJiYmJiUlJiYmJSUlJiYlJiYlJiUmJw=="
            ),

            PieceTemplate(
                'q',
                1173.9395655375902,
                "NzQ1NTU1NDU1NTU1NDMyNDQyMjQ9NDc1NTc2NjYyLzY1MzQ1NzQ0OT0zNDYxHQkpLfXdIS0LDy81NDI4PTAkLyjtz+4Z4r38C9S8FC0mMDg8DOLeJOa/8CzXvx4MvboW88fvNz3748EkHL8NNvHVMyC9CDDbtcszPSPgySwr0u405sssBrsbMuDBDzg9NxrLAynk1CLiyBzkuxgXwgQzOD02K9zQF/DO+OfB99K2D+TBGDY4PDQ07c3Z7c+/4rrBz67k08MmNzk8Mzb83LLSz6/Yt7XKob7M0C81OjwyNg/bt8G1qquZqqSewrPtNDQ5PDI2Hb7Cw8iypZ2Zko6VlQk2NDk8MjYv5NfJvLCgmZeSi4izKTczOTwyNjQL6+LAq6CZl5KMieY2NjQ4PDM2NAwA/eDCsqSfmZOO4zU2Nzg9MzYv6Nu5o5iSk5WVk5C3Kz5FOD00Ni/zuaKbl5eXmqWot+IwREg5PTY0MjIwHxQKBwgOEhssMzpFRzo9OTk3OTk3Nzc3OTk3ODk3OT08Og=="
            ),

            PieceTemplate(
                'k',
                1831.3382027997973,
                "Oz8/Pz8/Pz4/Pz8/Pz8/P0BAQD9UWFhYWFhYWFhVVVhYWFhYWFhYWFRXV1dXV1dXViMUVVdXV1dXV1dYVFdXV1dXV1ctxbocVldXV1dXV1dUV1dXV1dXV0345UhXV1dXV1dXV1RXV1M6KixBM8auIUUvKjVQV1dXVFhH9L2il626ooqtxaCWnNc8WFhUUfrDpY6IiIqKiJuoioiIis1MWFQ227ybioiIiIiKmo6IiIiIkyFYVCXGq5KIiIiIiIuOiIiIiIiKB1hUMLeZioiIiIiIioiIiIiIiI8YWFRN1IyIiIiIiIiJiIiIiIiIuUNYVFg3yJCIiIiIiIiIiIiIjbsqV1hUWFhK4aGPiIiIiIiIiIjCQldXWFRYWFgTupyKiIiIiIiIiO5XV1dYVFhYVwzEpY2IiIiIiIiI4VVXV1hUWFhT4LSTioiIiIiIiIi1SFFSWFRYWFQAvaGXkY+PkZWete5JQ0VWVFhYWFZMOy4kISEjKzhJVVVFS1ZTWFhYWFhYV1hXWFhYWFhXWFVUWA=="
            ),

            PieceTemplate(
                'p',
                1067.9906207380957,
                "JCQkJCQkJCQkJCQkJCQkJCQkJCQjISIhISIiISIhICIhICAhIiAhIyMgIiIgIiIhIiAgIiAgICAhISAjIyAgIiAiIx4A4dn1GyAgICAiISMjICAgIiEi997Hq57eHiAgHyAiJCMgICAiIhfg1aealKIOISIhHyEkJCIgHyEiDtjBo5iQlgQiISIhICMkIiAfICIXzbOekoukECIgICIgIyMiISAgIh/uo5SLj94eIiAgISIjIyEiHyAiANawnpSQqe8hICAgIiQjICIgIB/g2K+fk42PthsgISAiJCMgIiAgIQvyqJqNk+YFICAjISEkIyAiISAiH/Kpn5GN4h8iICMiISQjICIhICEGu6qflYyd+SEgIiIgIyMgIiEgBc67qJyVjomf+SAiIiAjIyAiIBXZ2rmjmJOPioinDiIiISMjICIg+t7UsKCXko+KiIvnISEhIyMhIiDr1MKonJSQjoyKic0fISIjIyIhIAfv7Onn5+fo5ubn/yAgIiQkIyIiIyMiIiIiIyMiIiMiIyIjJA=="
            )
        )

    private data class DecodedTemplate(
        val piece: Char,
        val values: FloatArray
    )

    private val templates: List<DecodedTemplate> =
        templateDefinitions.map {

            DecodedTemplate(
                piece = it.piece,
                values = decodeTemplate(
                    it.encoded,
                    it.scale
                )
            )
        }

    fun recognize(
        bitmap: Bitmap
    ): Result? {

        val area =
            findBoard(bitmap)
                ?: return null

        /*
         * Najpierw rozpoznajemy pola w orientacji
         * ekranowej.
         */
        val screenBoard =
            Array(8) {
                CharArray(8) {
                    '.'
                }
            }

        var confidenceSum =
            0.0

        var pieces =
            0

        for (
            row in 0..7
        ) {

            for (
                col in 0..7
            ) {

                val result =
                    recognizeSquare(
                        bitmap,
                        area,
                        row,
                        col
                    )

                screenBoard[row][col] =
                    result.first

                if (
                    result.first != '.'
                ) {

                    pieces++

                    confidenceSum +=
                        result.second
                }
            }
        }

        /*
         * Menu / ekran bez planszy.
         */
        if (
            pieces < 2
        ) {

            return null
        }

        val whiteAtBottom =
            detectOrientation(
                screenBoard
            )

        val logicalBoard =
            Array(8) {
                CharArray(8) {
                    '.'
                }
            }

        for (
            row in 0..7
        ) {

            for (
                col in 0..7
            ) {

                val piece =
                    screenBoard[row][col]

                if (
                    whiteAtBottom
                ) {

                    logicalBoard[row][col] =
                        piece

                } else {

                    logicalBoard[
                        7 - row
                    ][
                        7 - col
                    ] =
                        piece
                }
            }
        }

        return Result(
            area =
                area,

            boardFen =
                boardToFen(
                    logicalBoard
                ),

            whiteAtBottom =
                whiteAtBottom,

            confidence =
                if (
                    pieces > 0
                ) {

                    confidenceSum /
                        pieces

                } else {

                    0.0
                }
        )
    }

    private fun recognizeSquare(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Pair<Char, Double> {

        val square =
            extractSquare(
                bitmap,
                area,
                row,
                col
            )
                ?: return Pair(
                    '.',
                    0.0
                )

        val feature =
            createFeature(
                square
            )

        /*
         * NAJWAŻNIEJSZA ZMIANA:
         *
         * zanim zaczniemy porównywać z figurami,
         * ustalamy czy w ogóle jest tam figura.
         */
        if (
            feature.occupancy <
            occupiedThreshold
        ) {

            square.recycle()

            return Pair(
                '.',
                1.0
            )
        }

        /*
         * Typ figury ustalamy jako maksimum
         * spośród wzorca białego i czarnego.
         *
         * Dzięki temu np. biały król nie jest
         * mylony z czarnym tylko dlatego,
         * że stoi na innym kolorze pola.
         */
        val typeScores =
            mutableMapOf<Char, Double>()

        val types =
            charArrayOf(
                'R',
                'N',
                'B',
                'Q',
                'K',
                'P'
            )

        for (
            type in types
        ) {

            val white =
                templates.first {
                    it.piece ==
                        type
                }

            val black =
                templates.first {
                    it.piece ==
                        type.lowercaseChar()
                }

            val whiteScore =
                dot(
                    feature.values,
                    white.values
                )

            val blackScore =
                dot(
                    feature.values,
                    black.values
                )

            typeScores[type] =
                maxOf(
                    whiteScore,
                    blackScore
                )
        }

        val best =
            typeScores
                .maxByOrNull {
                    it.value
                }

        if (
            best == null ||
            best.value < 0.48
        ) {

            square.recycle()

            return Pair(
                '.',
                best?.value
                    ?: 0.0
            )
        }

        /*
         * Typ już znamy.
         *
         * Teraz niezależnie określamy kolor.
         */
        val whitePiece =
            feature.foregroundBrightness >
                whitePieceBrightnessThreshold

        val result =
            if (
                whitePiece
            ) {

                best.key

            } else {

                best.key
                    .lowercaseChar()
            }

        square.recycle()

        return Pair(
            result,
            best.value
        )
    }

    private data class Feature(
        val values: FloatArray,
        val occupancy: Double,
        val foregroundBrightness: Double
    )

    private fun createFeature(
        bitmap: Bitmap
    ): Feature {

        val width =
            bitmap.width

        val height =
            bitmap.height

        /*
         * Kolor tła liczymy z czterech rogów.
         *
         * Dzięki temu działa zarówno na jasnym,
         * ciemnym, jak i zaznaczonym na zielono polu.
         */
        val patch =
            maxOf(
                3,
                (
                    minOf(
                        width,
                        height
                    ) *
                        0.12f
                    ).toInt()
            )

        val backgroundPixels =
            mutableListOf<Int>()

        fun addCorner(
            startX: Int,
            startY: Int
        ) {

            for (
                y in startY until
                    minOf(
                        height,
                        startY + patch
                    )
            ) {

                for (
                    x in startX until
                        minOf(
                            width,
                            startX + patch
                        )
                ) {

                    if (
                        x in 0 until width &&
                        y in 0 until height
                    ) {

                        backgroundPixels +=
                            bitmap.getPixel(
                                x,
                                y
                            )
                    }
                }
            }
        }

        addCorner(
            0,
            0
        )

        addCorner(
            width -
                patch,
            0
        )

        addCorner(
            0,
            height -
                patch
        )

        addCorner(
            width -
                patch,
            height -
                patch
        )

        val bgR =
            medianChannel(
                backgroundPixels,
                16
            )

        val bgG =
            medianChannel(
                backgroundPixels,
                8
            )

        val bgB =
            medianChannel(
                backgroundPixels,
                0
            )

        val residual =
            FloatArray(
                width *
                    height
            )

        var absoluteSum =
            0.0

        val foregroundLuminance =
            mutableListOf<Double>()

        var index =
            0

        for (
            y in 0 until
                height
        ) {

            for (
                x in 0 until
                    width
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r =
                    (
                        pixel shr
                            16
                        ) and 255

                val g =
                    (
                        pixel shr
                            8
                        ) and 255

                val b =
                    pixel and 255

                val dr =
                    r -
                        bgR

                val dg =
                    g -
                        bgG

                val db =
                    b -
                        bgB

                val signedLuminance =
                    (
                        dr *
                            0.299 +
                            dg *
                            0.587 +
                            db *
                            0.114
                        )

                residual[index++] =
                    signedLuminance
                        .toFloat()

                absoluteSum +=
                    abs(
                        signedLuminance
                    )

                /*
                 * Kolor figury sprawdzamy tylko
                 * dla pikseli wyraźnie różnych
                 * od tła.
                 */
                val distance =
                    sqrt(
                        (
                            dr *
                                dr +
                                dg *
                                dg +
                                db *
                                db
                            ).toDouble()
                    )

                val inside =
                    x >
                        width *
                            0.08 &&
                        x <
                        width *
                            0.92 &&
                        y >
                        height *
                            0.05 &&
                        y <
                        height *
                            0.95

                if (
                    distance >
                    35.0 &&
                    inside
                ) {

                    val luminance =
                        r *
                            0.299 +
                            g *
                            0.587 +
                            b *
                            0.114

                    foregroundLuminance +=
                        luminance
                }
            }
        }

        val occupancy =
            absoluteSum /
                (
                    width *
                        height
                    )

        /*
         * Z residualu robimy obraz 20×20.
         */
        val residualBitmap =
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

        index =
            0

        for (
            y in 0 until
                height
        ) {

            for (
                x in 0 until
                    width
            ) {

                val value =
                    (
                        residual[index++] +
                            128f
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            255
                        )

                residualBitmap.setPixel(
                    x,
                    y,
                    android.graphics.Color.rgb(
                        value,
                        value,
                        value
                    )
                )
            }
        }

        val scaled =
            Bitmap.createScaledBitmap(
                residualBitmap,
                20,
                20,
                true
            )

        residualBitmap.recycle()

        val values =
            FloatArray(
                400
            )

        var sum =
            0.0

        index =
            0

        for (
            y in 0 until
                20
        ) {

            for (
                x in 0 until
                    20
            ) {

                val pixel =
                    scaled.getPixel(
                        x,
                        y
                    )

                val value =
                    (
                        (
                            pixel shr
                                16
                            ) and
                            255
                        ) -
                        128

                values[index++] =
                    value.toFloat()

                sum +=
                    value
            }
        }

        scaled.recycle()

        val mean =
            sum /
                values.size

        var norm =
            0.0

        for (
            i in values.indices
        ) {

            values[i] =
                (
                    values[i] -
                        mean
                    )
                    .toFloat()

            norm +=
                values[i] *
                    values[i]
        }

        norm =
            sqrt(
                norm
            )

        if (
            norm >
            0.00001
        ) {

            for (
                i in values.indices
            ) {

                values[i] =
                    (
                        values[i] /
                            norm
                        )
                        .toFloat()
            }
        }

        val foregroundBrightness =
            medianDouble(
                foregroundLuminance
            )

        return Feature(
            values =
                values,

            occupancy =
                occupancy,

            foregroundBrightness =
                foregroundBrightness
        )
    }

    private fun extractSquare(
        bitmap: Bitmap,
        area: BoardArea,
        row: Int,
        col: Int
    ): Bitmap? {

        val cell =
            area.size /
                8f

        /*
         * Lekko odcinamy brzegi pola:
         *
         * - współrzędne a-h / 1-8,
         * - evaluation bar,
         * - krawędzie sąsiednich pól.
         */
        val marginX =
            cell *
                0.06f

        val marginY =
            cell *
                0.04f

        val left =
            (
                area.left +
                    col *
                        cell +
                    marginX
                )
                .toInt()

        val right =
            (
                area.left +
                    (
                        col +
                            1
                        ) *
                        cell -
                    marginX
                )
                .toInt()

        val top =
            (
                area.top +
                    row *
                        cell +
                    marginY
                )
                .toInt()

        val bottom =
            (
                area.top +
                    (
                        row +
                            1
                        ) *
                        cell -
                    marginY
                )
                .toInt()

        if (
            left <
            0 ||
            top <
            0 ||
            right >
            bitmap.width ||
            bottom >
            bitmap.height ||
            right <=
            left ||
            bottom <=
            top
        ) {

            return null
        }

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            right -
                left,
            bottom -
                top
        )
    }

    private fun findBoard(
        bitmap: Bitmap
    ): BoardArea? {

        val size =
            bitmap.width

        val cell =
            size /
                8f

        /*
         * Szukamy planszy pełnej szerokości.
         */
        var top =
            120

        val maxTop =
            bitmap.height -
                size -
                60

        while (
            top <=
            maxTop
        ) {

            var correct =
                0

            var reverse =
                0

            /*
             * Próbkujemy kilka wysokości wewnątrz
             * pierwszego rzędu pól zamiast dokładnie
             * na krawędzi planszy.
             */
            val testY =
                (
                    top +
                        cell *
                            0.12f
                    )
                    .toInt()

            for (
                col in 0..7
            ) {

                val x =
                    (
                        col *
                            cell +
                            cell *
                                0.12f
                        )
                        .toInt()

                if (
                    x !in
                    0 until
                        bitmap.width ||
                    testY !in
                    0 until
                        bitmap.height
                ) {

                    continue
                }

                val pixel =
                    bitmap.getPixel(
                        x,
                        testY
                    )

                val normalColor =
                    if (
                        col %
                        2 ==
                        0
                    ) {

                        lightSquare

                    } else {

                        darkSquare
                    }

                val reverseColor =
                    if (
                        col %
                        2 ==
                        0
                    ) {

                        darkSquare

                    } else {

                        lightSquare
                    }

                if (
                    colorDistance(
                        pixel,
                        normalColor
                    ) <
                    65.0
                ) {

                    correct++
                }

                if (
                    colorDistance(
                        pixel,
                        reverseColor
                    ) <
                    65.0
                ) {

                    reverse++
                }
            }

            if (
                correct >=
                6 ||
                reverse >=
                6
            ) {

                /*
                 * Dodatkowa kontrola:
                 * wzór ma istnieć także niżej.
                 */
                if (
                    validateBoard(
                        bitmap,
                        top,
                        size
                    )
                ) {

                    return BoardArea(
                        left =
                            0,

                        top =
                            top,

                        size =
                            size
                    )
                }
            }

            top +=
                2
        }

        return null
    }

    private fun validateBoard(
        bitmap: Bitmap,
        top: Int,
        size: Int
    ): Boolean {

        val cell =
            size /
                8f

        var alternating =
            0

        var checks =
            0

        for (
            row in
                listOf(
                    2,
                    3,
                    4,
                    5
                )
        ) {

            for (
                col in 0..6
            ) {

                val y =
                    (
                        top +
                            row *
                                cell +
                            cell *
                                0.12f
                        )
                        .toInt()

                val x1 =
                    (
                        col *
                            cell +
                            cell *
                                0.12f
                        )
                        .toInt()

                val x2 =
                    (
                        (
                            col +
                                1
                            ) *
                            cell +
                            cell *
                                0.12f
                        )
                        .toInt()

                if (
                    y !in
                    0 until
                        bitmap.height ||
                    x1 !in
                    0 until
                        bitmap.width ||
                    x2 !in
                    0 until
                        bitmap.width
                ) {

                    continue
                }

                val a =
                    bitmap.getPixel(
                        x1,
                        y
                    )

                val b =
                    bitmap.getPixel(
                        x2,
                        y
                    )

                checks++

                if (
                    rawColorDistance(
                        a,
                        b
                    ) >
                    25.0
                ) {

                    alternating++
                }
            }
        }

        return (
            checks >
            0 &&
            alternating
                .toDouble() /
                checks >
            0.55
            )
    }

    /*
     * Orientacja:
     *
     * najpierw próbujemy określić ją na podstawie
     * rozmieszczenia kolorów figur.
     *
     * W Analysis / Puzzle zwykle wystarcza.
     */
    private fun detectOrientation(
        board: Array<CharArray>
    ): Boolean {

        var whiteTop =
            0.0

        var whiteBottom =
            0.0

        var blackTop =
            0.0

        var blackBottom =
            0.0

        for (
            row in 0..7
        ) {

            val topWeight =
                (
                    7 -
                        row
                    )
                    .toDouble()

            val bottomWeight =
                row
                    .toDouble()

            for (
                col in 0..7
            ) {

                val piece =
                    board[row][col]

                if (
                    piece == '.'
                ) {

                    continue
                }

                if (
                    piece
                        .isUpperCase()
                ) {

                    whiteTop +=
                        topWeight

                    whiteBottom +=
                        bottomWeight

                } else {

                    blackTop +=
                        topWeight

                    blackBottom +=
                        bottomWeight
                }
            }
        }

        val normalScore =
            whiteBottom +
                blackTop

        val reverseScore =
            whiteTop +
                blackBottom

        return normalScore >=
            reverseScore
    }

    private fun decodeTemplate(
        encoded: String,
        scale: Double
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

        var mean =
            0.0

        for (
            i in
                bytes.indices
        ) {

            result[i] =
                (
                    bytes[i]
                        .toDouble() /
                        scale
                    )
                    .toFloat()

            mean +=
                result[i]
        }

        mean /=
            result.size

        var norm =
            0.0

        for (
            i in
                result.indices
        ) {

            result[i] =
                (
                    result[i] -
                        mean
                    )
                    .toFloat()

            norm +=
                result[i] *
                    result[i]
        }

        norm =
            sqrt(
                norm
            )

        if (
            norm >
            0.00001
        ) {

            for (
                i in
                    result.indices
            ) {

                result[i] =
                    (
                        result[i] /
                            norm
                        )
                        .toFloat()
            }
        }

        return result
    }

    private fun dot(
        a: FloatArray,
        b: FloatArray
    ): Double {

        val size =
            minOf(
                a.size,
                b.size
            )

        var result =
            0.0

        for (
            i in
                0 until
                    size
        ) {

            result +=
                a[i] *
                    b[i]
        }

        return result
    }

    private fun medianChannel(
        pixels: List<Int>,
        shift: Int
    ): Double {

        if (
            pixels.isEmpty()
        ) {

            return 0.0
        }

        val values =
            pixels
                .map {

                    (
                        it shr
                            shift
                        ) and
                        255
                }
                .sorted()

        return values[
            values.size /
                2
        ]
            .toDouble()
    }

    private fun medianDouble(
        values: List<Double>
    ): Double {

        if (
            values.isEmpty()
        ) {

            return 0.0
        }

        val sorted =
            values.sorted()

        return sorted[
            sorted.size /
                2
        ]
    }

    private fun colorDistance(
        pixel: Int,
        reference: DoubleArray
    ): Double {

        val r =
            (
                (
                    pixel shr
                        16
                    ) and
                    255
                )
                .toDouble()

        val g =
            (
                (
                    pixel shr
                        8
                    ) and
                    255
                )
                .toDouble()

        val b =
            (
                pixel and
                    255
                )
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
            dr *
                dr +
                dg *
                    dg +
                db *
                    db
        )
    }

    private fun rawColorDistance(
        a: Int,
        b: Int
    ): Double {

        val ar =
            (
                a shr
                    16
                ) and
                255

        val ag =
            (
                a shr
                    8
                ) and
                255

        val ab =
            a and
                255

        val br =
            (
                b shr
                    16
                ) and
                255

        val bg =
            (
                b shr
                    8
                ) and
                255

        val bb =
            b and
                255

        val dr =
            ar -
                br

        val dg =
            ag -
                bg

        val db =
            ab -
                bb

        return sqrt(
            (
                dr *
                    dr +
                    dg *
                        dg +
                    db *
                        db
                )
                .toDouble()
        )
    }

    private fun boardToFen(
        board: Array<CharArray>
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
                            empty >
                            0
                        ) {

                            append(
                                empty
                            )

                            empty =
                                0
                        }

                        append(
                            piece
                        )
                    }
                }

                if (
                    empty >
                    0
                ) {

                    append(
                        empty
                    )
                }

                if (
                    row <
                    7
                ) {

                    append(
                        "/"
                    )
                }
            }
        }
    }
}
