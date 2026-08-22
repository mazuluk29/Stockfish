package com.trading.stockfishoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

enum class MoveQuality(
    val label: String,
    val symbol: String,
    val arrowColor: Int
) {

    BRILLIANT(
        "Brilliant",
        "!!",
        Color.rgb(27, 172, 166)
    ),

    BEST(
        "Best",
        "★",
        Color.rgb(79, 121, 66)
    ),

    EXCELLENT(
        "Excellent",
        "✓",
        Color.rgb(96, 143, 78)
    ),

    GOOD(
        "Good",
        "✓",
        Color.rgb(126, 150, 99)
    ),

    BOOK(
        "Book",
        "▣",
        Color.rgb(166, 126, 91)
    ),

    INACCURACY(
        "Inaccuracy",
        "?!",
        Color.rgb(241, 196, 78)
    ),

    MISTAKE(
        "Mistake",
        "?",
        Color.rgb(230, 151, 55)
    ),

    MISS(
        "Miss",
        "✕",
        Color.rgb(201, 111, 154)
    ),

    BLUNDER(
        "Blunder",
        "??",
        Color.rgb(202, 71, 71)
    )
}

data class OverlayMove(
    val rank: Int,
    val move: String,
    val evaluation: String,
    val quality: MoveQuality
)

class BoardOverlayView(
    context: Context
) : View(context) {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private var evaluation =
        0.0

    private var moves:
        List<OverlayMove> =
        emptyList()

    var whiteAtBottom =
        true

    fun update(
        evaluation: Double?,
        moves: List<OverlayMove>
    ) {

        if (evaluation != null) {

            this.evaluation =
                evaluation.coerceIn(
                    -15.0,
                    15.0
                )
        }

        this.moves =
            moves.take(5)

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        drawEvaluationBar(
            canvas
        )

        moves
            .asReversed()
            .forEach {

                drawArrow(
                    canvas,
                    it
                )
            }
    }

    private fun drawEvaluationBar(
        canvas: Canvas
    ) {

        val barWidth =
            18f

        val h =
            height.toFloat()

        val whiteShare =
            1.0 /
                (
                    1.0 +
                        exp(
                            -evaluation /
                                2.2
                        )
                    )

        val blackHeight =
            (
                h *
                    (1.0 - whiteShare)
                ).toFloat()

        paint.style =
            Paint.Style.FILL

        paint.color =
            Color.rgb(
                35,
                35,
                35
            )

        canvas.drawRect(
            0f,
            0f,
            barWidth,
            blackHeight,
            paint
        )

        paint.color =
            Color.rgb(
                240,
                240,
                240
            )

        canvas.drawRect(
            0f,
            blackHeight,
            barWidth,
            h,
            paint
        )
    }

    private fun drawArrow(
        canvas: Canvas,
        item: OverlayMove
    ) {

        val move =
            item.move

        if (move.length < 4) {
            return
        }

        val from =
            move.substring(
                0,
                2
            )

        val to =
            move.substring(
                2,
                4
            )

        val start =
            squareCenter(
                from
            )

        val end =
            squareCenter(
                to
            )

        val cell =
            width / 8f

        val alpha =
            when (item.rank) {

                1 -> 225
                2 -> 195
                3 -> 170
                4 -> 145
                else -> 125
            }

        val baseColor =
            item.quality.arrowColor

        val color =
            Color.argb(
                alpha,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )

        paint.style =
            Paint.Style.STROKE

        paint.strokeCap =
            Paint.Cap.ROUND

        paint.strokeWidth =
            when (item.rank) {

                1 ->
                    cell * 0.135f

                2 ->
                    cell * 0.115f

                3 ->
                    cell * 0.095f

                4 ->
                    cell * 0.080f

                else ->
                    cell * 0.070f
            }

        paint.color =
            color

        canvas.drawLine(
            start.first,
            start.second,
            end.first,
            end.second,
            paint
        )

        drawArrowHead(
            canvas,
            start.first,
            start.second,
            end.first,
            end.second,
            color,
            cell
        )

        drawBadge(
            canvas,
            end.first,
            end.second,
            item,
            cell
        )
    }

    private fun drawBadge(
        canvas: Canvas,
        x: Float,
        y: Float,
        item: OverlayMove,
        cell: Float
    ) {

        val radius =
            cell * 0.18f

        val badgeX =
            x + cell * 0.22f

        val badgeY =
            y - cell * 0.22f

        paint.style =
            Paint.Style.FILL

        paint.color =
            item.quality.arrowColor

        canvas.drawCircle(
            badgeX,
            badgeY,
            radius,
            paint
        )

        paint.color =
            Color.WHITE

        paint.textAlign =
            Paint.Align.CENTER

        paint.textSize =
            radius * 1.05f

        paint.isFakeBoldText =
            true

        canvas.drawText(
            item.quality.symbol,
            badgeX,
            badgeY +
                paint.textSize * 0.35f,
            paint
        )

        /*
         * #1, #2, #3...
         */
        val rankWidth =
            cell * 0.36f

        val rankHeight =
            cell * 0.22f

        val left =
            badgeX -
                rankWidth / 2f

        val top =
            badgeY +
                radius +
                cell * 0.04f

        val rect =
            RectF(
                left,
                top,
                left + rankWidth,
                top + rankHeight
            )

        paint.color =
            Color.argb(
                220,
                25,
                25,
                25
            )

        canvas.drawRoundRect(
            rect,
            cell * 0.04f,
            cell * 0.04f,
            paint
        )

        paint.color =
            Color.WHITE

        paint.textSize =
            cell * 0.14f

        canvas.drawText(
            "#${item.rank}",
            rect.centerX(),
            rect.centerY() +
                paint.textSize * 0.34f,
            paint
        )

        paint.isFakeBoldText =
            false
    }

    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        color: Int,
        cell: Float
    ) {

        val angle =
            atan2(
                endY - startY,
                endX - startX
            )

        val length =
            cell * 0.28f

        val spread =
            0.55f

        val x1 =
            endX -
                length *
                    cos(
                        angle -
                            spread
                    )

        val y1 =
            endY -
                length *
                    sin(
                        angle -
                            spread
                    )

        val x2 =
            endX -
                length *
                    cos(
                        angle +
                            spread
                    )

        val y2 =
            endY -
                length *
                    sin(
                        angle +
                            spread
                    )

        val path =
            Path()

        path.moveTo(
            endX,
            endY
        )

        path.lineTo(
            x1,
            y1
        )

        path.lineTo(
            x2,
            y2
        )

        path.close()

        paint.style =
            Paint.Style.FILL

        paint.color =
            color

        canvas.drawPath(
            path,
            paint
        )
    }

    private fun squareCenter(
        square: String
    ): Pair<Float, Float> {

        val file =
            square[0] - 'a'

        val rank =
            square[1] - '0'

        val row: Int
        val col: Int

        if (
            whiteAtBottom
        ) {

            col =
                file

            row =
                8 - rank

        } else {

            col =
                7 - file

            row =
                rank - 1
        }

        val cell =
            width / 8f

        return Pair(
            col * cell +
                cell / 2f,

            row * cell +
                cell / 2f
        )
    }
}
