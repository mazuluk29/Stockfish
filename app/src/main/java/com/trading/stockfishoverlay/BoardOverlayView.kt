package com.trading.stockfishoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class BoardOverlayView(
    context: Context
) : View(context) {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private var evaluation =
        0.0

    private var moves:
        List<String> =
        emptyList()

    var whiteAtBottom =
        true

    fun update(
        eval: Double?,
        bestMoves: List<String>
    ) {

        if (eval != null) {
            evaluation =
                eval.coerceIn(
                    -15.0,
                    15.0
                )
        }

        moves =
            bestMoves.take(3)

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        drawEvaluationBar(
            canvas
        )

        moves.forEachIndexed {
                index,
                move ->

            drawMove(
                canvas,
                move,
                index
            )
        }
    }

    private fun drawEvaluationBar(
        canvas: Canvas
    ) {

        val barWidth =
            20f

        val height =
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
                height *
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
            height,
            paint
        )
    }

    private fun drawMove(
        canvas: Canvas,
        move: String,
        index: Int
    ) {

        if (move.length < 4)
            return

        val from =
            move.substring(0, 2)

        val to =
            move.substring(2, 4)

        val start =
            squareCenter(from)

        val end =
            squareCenter(to)

        val cell =
            width / 8f

        paint.style =
            Paint.Style.STROKE

        paint.strokeCap =
            Paint.Cap.ROUND

        paint.strokeWidth =
            when (index) {
                0 -> cell * 0.14f
                1 -> cell * 0.10f
                else -> cell * 0.075f
            }

        paint.color =
            when (index) {
                0 ->
                    Color.argb(
                        210,
                        70,
                        190,
                        90
                    )

                1 ->
                    Color.argb(
                        170,
                        240,
                        180,
                        50
                    )

                else ->
                    Color.argb(
                        145,
                        80,
                        160,
                        230
                    )
            }

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
            paint.color,
            cell
        )
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

        if (whiteAtBottom) {

            col = file
            row = 8 - rank

        } else {

            col = 7 - file
            row = rank - 1
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
