package com.homebar.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * 小白条的「点击感应层」。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要单独一层窗口
 * ══════════════════════════════════════════════════════════════
 *  绘制窗口（[BarView]）永远是 FLAG_NOT_TOUCHABLE —— 它比屏幕上的任何窗口都高，
 *  一旦可触摸，会把底部的上滑手势整片吃掉。
 *
 *  但「点击小白条才弹跳」又必须真的收到触摸，于是拆成两层：
 *
 *    · 绘制层：大窗口（含弹跳余量、柔光余量），不可触摸，只负责画
 *    · 感应层：**只覆盖条本身**的小窗口（条 + 少量外扩），可触摸，透明，只负责收点击
 *
 *  这样「不可触摸的面积」就只剩下条自己那 140×5dp 一小块，而不是绘制窗口那
 *  一整条 150×50dp 的隐形区域 —— 后者会让条上方一大片区域变成点不动的死区。
 *
 *  安全边界：
 *    · 只在条「可见且未被抑制」时才把 FLAG_NOT_TOUCHABLE 摘掉，隐藏时立刻恢复，
 *      不会在屏幕底部长期留一块看不见的触摸区
 *    · 带 FLAG_NOT_TOUCH_MODAL，窗口外的触摸照常送给下面的应用
 *    · 带 FLAG_NOT_FOCUSABLE，不抢焦点、不影响输入法
 *    · 只把「按下后没移动、且 600ms 内抬起」判定为点击；滑动一律不算，
 *      手势导航的上滑由系统优先接管
 */
class BarTouchTarget(private val service: AccessibilityService) {

    /** 点中条时回调（主线程） */
    var onTap: (() -> Unit)? = null

    private val wm: WindowManager? = service.getSystemService(WindowManager::class.java)
    private var view: TapView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    // 上一帧的布局参数。update() 每次触摸都会被调用一次，
    // 没有变化就不必再走一次 updateViewLayout 的 binder 调用。
    private var lastW = -1
    private var lastH = -1
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastActive = false

    fun attach() {
        if (attached) return
        val manager = wm ?: run {
            Log.e(TAG, "WindowManager unavailable")
            return
        }

        val v = TapView(service).apply {
            onTap = { this@BarTouchTarget.onTap?.invoke() }
        }
        val p = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            // 先给个 1×1，紧接着 update() 会改成条的实际位置
            width = 1
            height = 1
            setFitInsetsTypes(0)
            setTitle(WINDOW_TITLE)
        }

        val ok = runCatching { manager.addView(v, p) }.isSuccess
        if (!ok) {
            Log.e(TAG, "addView failed")
            return
        }
        view = v
        params = p
        attached = true
        resetCache()
    }

    fun detach() {
        if (!attached) return
        val v = view
        val manager = wm
        if (v != null && manager != null) {
            runCatching { manager.removeView(v) }
        }
        view = null
        params = null
        attached = false
        resetCache()
    }

    private fun resetCache() {
        lastW = -1
        lastH = -1
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastActive = false
    }

    /**
     * 更新感应区的位置与大小，并决定这一帧是否可触摸。
     *
     * @param active false 时立刻打回 FLAG_NOT_TOUCHABLE —— 条看不见的时候，
     *               屏幕底部不能留一块「点下去没反应」的死区。
     */
    fun update(width: Int, height: Int, x: Int, y: Int, active: Boolean) {
        if (!attached) return
        val v = view ?: return
        val p = params ?: return
        val manager = wm ?: return

        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w == lastW && h == lastH && x == lastX && y == lastY && active == lastActive) return
        lastW = w
        lastH = h
        lastX = x
        lastY = y
        lastActive = active

        p.width = w
        p.height = h
        p.x = x
        p.y = y
        p.flags = if (active) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        runCatching { manager.updateViewLayout(v, p) }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    // ─────────────────────────────────────────────────────────────

    private class TapView(context: android.content.Context) : View(context) {

        var onTap: (() -> Unit)? = null

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var moved = false

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        moved = true
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val duration = SystemClock.uptimeMillis() - downAt
                    if (!moved && duration <= TAP_TIMEOUT_MS) onTap?.invoke()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = true
                    return true
                }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "HomeBar/TouchTarget"
        private const val WINDOW_TITLE = "HomeBarTapTarget"

        /** 一次点击的最长按压时间，超过就算长按，不弹 */
        private const val TAP_TIMEOUT_MS = 600L
    }
}
