package com.trading.stockfishoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.exp

class EvalBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private var evaluation = 0.0

    fun setEvaluation(value: Double) {
        evaluation =
            value.coerceIn(
                -15.0,
                15.0
            )

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val white =
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
                    (1.0 - white)
                ).toFloat()

        paint.color =
            Color.rgb(
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

        paint.color =
            Color.rgb(
                240,
                240,
                240
            )

        canvas.drawRect(
            0f,
            blackHeight,
            w,
            h,
            paint
        )
    }
}
