package com.example.eyetonode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class EyeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private var leftEyeCoordinates: Pair<Float, Float>? = null
    private var rightEyeCoordinates: Pair<Float, Float>? = null

    fun setEyeCoordinates(left: Pair<Float, Float>?, right: Pair<Float, Float>?) {
        leftEyeCoordinates = left
        rightEyeCoordinates = right
        invalidate() // Request to redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        leftEyeCoordinates?.let { (x, y) ->
            canvas.drawCircle(x, y, 20f, paint) // Draw left eye marker
        }
        rightEyeCoordinates?.let { (x, y) ->
            canvas.drawCircle(x, y, 20f, paint) // Draw right eye marker
        }
    }
}
