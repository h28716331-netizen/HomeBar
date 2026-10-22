package com.homebar.core

import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 三次贝塞尔缓动，等价于 CSS cubic-bezier(x1, y1, x2, y2)。
 * 牛顿迭代 + 二分兜底求解 x(t) = progress。
 */
class CubicBezierInterpolator(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) : Interpolator {

    private val cx = 3f * x1
    private val bx = 3f * (x2 - x1) - cx
    private val ax = 1f - cx - bx

    private val cy = 3f * y1
    private val by = 3f * (y2 - y1) - cy
    private val ay = 1f - cy - by

    private fun sampleCurveX(t: Float) = ((ax * t + bx) * t + cx) * t
    private fun sampleCurveY(t: Float) = ((ay * t + by) * t + cy) * t
    private fun sampleDerivativeX(t: Float) = (3f * ax * t + 2f * bx) * t + cx

    private fun solveT(x: Float): Float {
        var t = x
        // 牛顿迭代
        repeat(8) {
            val xEst = sampleCurveX(t) - x
            if (kotlin.math.abs(xEst) < 1e-5f) return t
            val d = sampleDerivativeX(t)
            if (kotlin.math.abs(d) < 1e-6f) return@repeat
            t -= xEst / d
        }
        // 二分兜底
        var lo = 0f
        var hi = 1f
        t = x
        repeat(24) {
            val xEst = sampleCurveX(t)
            when {
                xEst < x -> lo = t
                xEst > x -> hi = t
                else -> return t
            }
            t = (lo + hi) / 2f
        }
        return t
    }

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        return sampleCurveY(solveT(input))
    }
}

/**
 * 弹簧缓动：从位移 1 释放回到 0，返回 0→1 的进度曲线。
 * damping = 0.15 时会有明显过冲（Q 弹），1.0 时几乎不弹。
 */
class SpringInterpolator(private val damping: Float) : Interpolator {

    private val zeta = damping.coerceIn(0.12f, 1.0f)
    private val omega = 15f

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        val t = input * 1f
        val decay = exp(-zeta * omega * t)
        val value = if (zeta < 1f) {
            val wd = omega * sqrt(1f - zeta * zeta)
            decay * (cos(wd * t) + (zeta * omega / wd) * sin(wd * t))
        } else {
            decay * (1f + omega * t)
        }
        // value: 1 → 0，转成 0 → 1
        return 1f - value
    }
}

/**
 * 轻微回弹的过冲缓动，比系统的 OvershootInterpolator 更柔和。
 */
class SoftOvershootInterpolator(private val tension: Float = 1.15f) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        val t = input - 1f
        return t * t * ((tension + 1f) * t + tension) + 1f
    }
}

object Easing {

    /**
     * 把预设 + 参数编译成一个 [Interpolator]。
     * 同一个预设在不同动画里用同一份参数，UI 直接复用。
     */
    fun of(
        preset: EasingPreset,
        springDamping: Float = 0.62f,
        bezierX1: Float = 0.34f,
        bezierY1: Float = 1.35f,
        bezierX2: Float = 0.64f,
        bezierY2: Float = 1.0f,
    ): Interpolator = when (preset) {
        EasingPreset.LINEAR -> LinearInterpolator()
        EasingPreset.DECELERATE -> DecelerateInterpolator(1.8f)
        EasingPreset.ACCELERATE -> AccelerateInterpolator(1.8f)
        EasingPreset.ACCELERATE_DECELERATE -> AccelerateDecelerateInterpolator()
        EasingPreset.OVERSHOOT -> SoftOvershootInterpolator(1.35f)
        EasingPreset.ANTICIPATE_OVERSHOOT -> AnticipateOvershootInterpolator(1.6f)
        EasingPreset.SPRING -> SpringInterpolator(springDamping)
        EasingPreset.BOUNCE -> BounceInterpolator()
        EasingPreset.CUSTOM_BEZIER -> CubicBezierInterpolator(
            bezierX1.coerceIn(0f, 1f), bezierY1,
            bezierX2.coerceIn(0f, 1f), bezierY2,
        )
    }

    /** 为「出现」动画取曲线 */
    fun forShow(cfg: BarConfig): Interpolator =
        of(cfg.showEasing, cfg.springDamping, cfg.bezierX1, cfg.bezierY1, cfg.bezierX2, cfg.bezierY2)

    /** 为「消失」动画取曲线 */
    fun forHide(cfg: BarConfig): Interpolator =
        of(cfg.hideEasing, cfg.springDamping, cfg.bezierX1, cfg.bezierY1, cfg.bezierX2, cfg.bezierY2)

    /** 为「弹跳」动画取曲线 */
    fun forBounce(cfg: BarConfig): Interpolator =
        of(cfg.bounceEasing, cfg.bounceSpringDamping, cfg.bezierX1, cfg.bezierY1, cfg.bezierX2, cfg.bezierY2)

    /**
     * 弹跳位移形状函数：0 → 1（最高点）→ 0。
     * 上升段占 [PEAK]，回落段占剩余部分，两段共用同一条缓动曲线，
     * 所以选「回弹 / 弹簧」时回落会自然过冲一点点，手感更轻。
     */
    fun bounceShape(progress: Float, interpolator: Interpolator): Float {
        val p = progress.coerceIn(0f, 1f)
        return if (p < PEAK) {
            interpolator.getInterpolation(p / PEAK)
        } else {
            val down = interpolator.getInterpolation((p - PEAK) / (1f - PEAK))
            1f - down
        }
    }

    private const val PEAK = 0.36f

    /** 给 UI 预览用：算出 0→1 之间任意时刻的曲线采样点 */
    fun sampleCurve(interpolator: Interpolator, steps: Int = 48): FloatArray =
        FloatArray(steps + 1) { i -> interpolator.getInterpolation(i.toFloat() / steps) }

    @Suppress("unused")
    private fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)
}
