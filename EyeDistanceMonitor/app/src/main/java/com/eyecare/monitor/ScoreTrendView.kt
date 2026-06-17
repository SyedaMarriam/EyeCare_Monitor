package com.eyecare.monitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class ScoreTrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = mutableListOf<Float>()
    private val maxPoints = 50
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var lineColor = ContextCompat.getColor(context, R.color.accent_blue)

    fun addScore(score: Int) {
        points.add(score.toFloat())
        if (points.size > maxPoints) {
            points.removeAt(0)
        }
        
        lineColor = when {
            score > 80 -> ContextCompat.getColor(context, R.color.status_safe)
            score > 50 -> ContextCompat.getColor(context, R.color.status_warning)
            else -> ContextCompat.getColor(context, R.color.status_danger)
        }
        paint.color = lineColor
        
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val stepX = w / (maxPoints - 1)
        
        path.reset()
        
        points.forEachIndexed { index, score ->
            val x = index * stepX
            val y = h - (score / 100f * h * 0.8f) - (h * 0.1f) // Keep some margin
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                val prevX = (index - 1) * stepX
                val prevY = h - (points[index - 1] / 100f * h * 0.8f) - (h * 0.1f)
                // Use cubic bezier for smooth curves
                path.cubicTo(prevX + stepX / 2, prevY, x - stepX / 2, y, x, y)
            }
        }

        canvas.drawPath(path, paint)
        
        // Draw area gradient
        val fillPath = Path(path)
        fillPath.lineTo( (points.size - 1) * stepX, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        
        gradientPaint.shader = LinearGradient(0f, 0f, 0f, h,
            lineColor.adjustAlpha(0.3f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, gradientPaint)
    }

    private fun Int.adjustAlpha(factor: Float): Int {
        val alpha = Math.round(Color.alpha(this) * factor)
        return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }
}
