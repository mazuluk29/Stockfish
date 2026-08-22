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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cp = 0.0

    fun setEvaluation(pawns: Double) {
        cp = pawns
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val h = height.toFloat()
        val w = width.toFloat()

        val whiteShare =
            1.0 / (1.0 + exp(-cp / 2.5))

        val split =
            (h * (1.0 - whiteShare)).toFloat()

        paint.color = Color.BLACK
        canvas.drawRect(
            0f,
            0f,
            w,
            split,
            paint
        )

        paint.color = Color.WHITE
        canvas.drawRect(
            0f,
            split,
            w,
            h,
            paint
        )
    }
}
