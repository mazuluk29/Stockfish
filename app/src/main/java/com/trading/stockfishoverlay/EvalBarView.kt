package com.trading.stockfishoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp

class EvalBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var evaluation = 0.0

    fun setEvaluation(value: Double) {
        evaluation = value.coerceIn(-15.0, 15.0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        /*
         * 0.0  -> 50/50
         * + eval -> więcej białego
         * - eval -> więcej czarnego
         */
        val whiteShare =
            1.0 / (1.0 + exp(-evaluation / 2.2))

        val blackHeight =
            (h * (1.0 - whiteShare)).toFloat()

        val radius = 8f

        canvas.save()

        val rect = RectF(
            0f,
            0f,
            w,
            h
        )

        canvas.clipRoundRect(
            rect,
            radius,
            radius
        )

        // czarna część
        paint.color = Color.rgb(
            35,
            35,
            35
        )

        canvas.drawRect(
            0f,
            0f,
            w,
            blackHeight,
            paint
        )

        // biała część
        paint.color = Color.rgb(
            235,
            235,
            235
        )

        canvas.drawRect(
            0f,
            blackHeight,
            w,
            h,
            paint
        )

        canvas.restore()

        // obramowanie
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(
            80,
            80,
            80
        )

        canvas.drawRoundRect(
            rect,
            radius,
            radius,
            paint
        )

        paint.style = Paint.Style.FILL

        // liczba oceny
        val evalText =
            if (evaluation >= 0) {
                "+%.2f".format(evaluation)
            } else {
                "%.2f".format(evaluation)
            }

        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        /*
         * Dodatnia ocena pokazana na białej części,
         * ujemna na czarnej.
         */
        if (evaluation >= 0) {

            textPaint.color = Color.BLACK

            canvas.drawText(
                evalText,
                w / 2,
                h - 12f,
                textPaint
            )

        } else {

            textPaint.color = Color.WHITE

            canvas.drawText(
                evalText,
                w / 2,
                26f,
                textPaint
            )
        }
    }
}
