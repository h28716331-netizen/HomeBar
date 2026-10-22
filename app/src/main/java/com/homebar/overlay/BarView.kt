package com.homebar.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 指示条本体。整个 View 就是一块透明画布，只在中间画一根圆角条。
 *
 * 之所以自绘而不是用 Drawable/ImageView：出现/消失/弹跳三个动画要同时
 * 驱动 alpha、缩放、位移，自绘可以在一帧里一次性算完，不会出现多个
 * View 属性动画互相打架导致的掉帧与「僵硬感」。
 */
class BarView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    // ── 几何（由 Controller 注入，单位 px）──
    private var padH = 0f
    private var padTop = 0f
    private var barW = 0f
    private var barH = 0f
    private var radius = 0f

    private val rect = RectF()

    // ── 动画状态 ──
    /** 0 = 完全隐藏，1 = 完全显示。弹簧曲线会短暂超过 1 */
    var showProgress: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 向上弹起的位移，px（正值向上） */
    var bounceOffset: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var barColor: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var barOpacity: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glowEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateLayer()
                invalidate()
            }
        }

    var scaleEnabled: Boolean = true
    var slideEnabled: Boolean = true
    var slideDistance = 0f

    private var glowRadius = 0f

    init {
        setWillNotDraw(false)
        // 阴影/柔光在硬件加速下对非文字图形不生效，需要软件层。
        // 这块区域很小（百来 dp），软件绘制开销可以忽略。
        updateLayer()
    }

    private fun updateLayer() {
        setLayerType(if (glowEnabled) LAYER_TYPE_SOFTWARE else LAYER_TYPE_HARDWARE, null)
    }

    fun setGeometry(
        padH: Float,
        padTop: Float,
        barWidth: Float,
        barHeight: Float,
        cornerRadius: Float,
        glow: Float,
    ) {
        this.padH = padH
        this.padTop = padTop
        this.barW = barWidth
        this.barH = barHeight
        this.radius = cornerRadius.coerceIn(0f, barHeight / 2f)
        this.glowRadius = glow
        rect.set(padH, padTop, padH + barWidth, padTop + barHeight)
        invalidate()
    }

    /** 屏幕坐标下这根条的矩形，取色时要用 */
    fun barRectOnScreen(out: RectF) {
        out.set(rect)
    }

    override fun onDraw(canvas: Canvas) {
        if (barW <= 0f || barH <= 0f) return

        val alphaP = showProgress.coerceIn(0f, 1f)
        val alpha = (alphaP * barOpacity * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val sx = if (scaleEnabled) lerp(0.70f, 1f, alphaP).coerceIn(0f, 1.06f) else 1f
        val sy = if (scaleEnabled) lerp(0.80f, 1f, alphaP).coerceIn(0f, 1.10f) else 1f
        val slide = if (slideEnabled) (1f - alphaP) * slideDistance else 0f
        val dy = slide - bounceOffset

        fillPaint.color = barColor
        fillPaint.alpha = alpha
        fillPaint.setShadowLayer(
            if (glowEnabled) glowRadius else 0f,
            0f,
            0f,
            glowColor(barColor, alphaP),
        )

        canvas.save()
        canvas.translate(0f, dy)
        val cx = padH + barW / 2f
        val cy = padTop + barH / 2f
        canvas.scale(sx, sy, cx, cy)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.restore()
    }

    /**
     * 柔光颜色：白条配暗光晕、黑条配亮光晕，避免在深色背景上糊成一团。
     */
    private fun glowColor(color: Int, p: Float): Int {
        val luminance = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
        val a = (if (luminance > 0.5f) 90 else 70) * p
        val c = if (luminance > 0.5f) 0x000000 else 0xFFFFFF
        return Color.argb(a.toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun lerp(start: Float, stop: Float, f: Float) = start + (stop - start) * f
}
