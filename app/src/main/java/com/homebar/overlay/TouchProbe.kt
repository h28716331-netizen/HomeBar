package com.homebar.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.homebar.core.TouchDiagnostics

/**
 * 触摸探测点：本应用唯一的触摸感知通道。
 *
 * ── 原理 ────────────────────────────────────────────────────
 * 挂一个 **1×1 像素**、不可聚焦、非模态的悬浮窗口，并打开
 * FLAG_WATCH_OUTSIDE_TOUCH。该 flag 的语义是：
 *
 *   「落在窗口外面」的触摸，会把按下点以 ACTION_OUTSIDE 通知给本窗口，
 *   同时事件【照常送给它下面的窗口/应用】。
 *
 * 窗口类型是 TYPE_ACCESSIBILITY_OVERLAY。查 AOSP
 * `WindowManagerPolicy.getWindowLayerFromTypeLw()` 可知它的层级是 31，
 * 而 TYPE_NAVIGATION_BAR 是 24 —— 也就是说本窗口【压在导航栏之上】，
 * 所以任何位置的触摸都会经过它，连点在最底部的导航栏/手势区也能收到。
 *
 * 窗口只有 1 个像素大，所以就算某个 ROM 把落在窗口内的触摸判定成被本窗口
 * 消费，最多也只影响屏幕上那 1 个像素。
 *
 * ── 关于坐标（重要）────────────────────────────────────────
 * AOSP `InputDispatcher` 里有一段明确的隐私保护：
 *
 *     // Check whether windows listening for outside touches are owned by the same UID.
 *     // If the owner has a different UID, then we will not reveal coordinate
 *     // information to this window.
 *     if (targetWindow->getInfo()->ownerUid != foregroundWindowUid) {
 *         target.flags |= InputTarget::Flags::ZERO_COORDS;
 *     }
 *
 * 而 InputTargetFlags.h 对 ZERO_COORDS 的注释是：
 *
 *     "will be zeroed out to avoid revealing information to an application. This is
 *      used in conjunction with FLAG_DISPATCH_AS_OUTSIDE to prevent apps not sharing
 *      the same UID from watching all touches."
 *
 * 结论：
 *   · 事件【一定会送到】—— 所以「用户碰了屏幕」这件事在任何应用里都拿得到；
 *   · 但坐标只有在【触摸落在本应用自己的窗口上】时才是真实的，
 *     其他任何应用里坐标一律被清零成 (0, 0)。
 *
 * 所以本类的职责是「知道有人碰了屏幕」，坐标只当作附赠信息。
 * 收到 (0,0) 时不要当成「点了左上角」，而要当成「坐标被系统隐藏了」。
 *
 * ── 兜底 ────────────────────────────────────────────────────
 * 若个别 ROM 连 ACTION_OUTSIDE 都不派发，本类不会报错也不会卡住，
 * 只是收不到任何信号，上层退化成「常显 + 不弹跳」。
 * 设置页的「触摸信号自检」会明确显示当前状态。
 */
class TouchProbe(private val service: AccessibilityService) {

    private val wm: WindowManager? = service.getSystemService(WindowManager::class.java)

    private var view: ProbeView? = null
    private var params: WindowManager.LayoutParams? = null

    var attached: Boolean = false
        private set

    /** 拿到【真实】坐标时回调（屏幕绝对坐标） */
    var onTouchWithCoords: ((Float, Float) -> Unit)? = null

    /** 知道有人碰了屏幕，但系统隐藏了坐标时回调 */
    var onTouchWithoutCoords: (() -> Unit)? = null

    fun attach() {
        if (attached) return
        val manager = wm ?: return

        val v = ProbeView(service).apply {
            onOutside = { rawX, rawY ->
                // 坐标被清零 —— 这是跨应用触摸时的正常现象，不是「点了左上角」
                if (rawX == 0f && rawY == 0f) {
                    TouchDiagnostics.onProbeTouch(coordsHidden = true, x = 0f, y = 0f)
                    onTouchWithoutCoords?.invoke()
                } else {
                    TouchDiagnostics.onProbeTouch(coordsHidden = false, x = rawX, y = rawY)
                    onTouchWithCoords?.invoke(rawX, rawY)
                }
            }
        }
        val p = WindowManager.LayoutParams(
            PROBE_SIZE,
            PROBE_SIZE,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            setTitle(WINDOW_TITLE)
        }

        val ok = runCatching { manager.addView(v, p) }
            .onFailure { Log.w(TAG, "addView probe failed", it) }
            .isSuccess
        if (!ok) {
            TouchDiagnostics.probeAttached = false
            return
        }
        view = v
        params = p
        attached = true
        TouchDiagnostics.probeAttached = true
        Log.i(TAG, "touch probe attached (1x1 px, layer 31, above nav bar)")
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
        TouchDiagnostics.probeAttached = false
    }

    /**
     * 探测视图。
     *
     * 只关心 ACTION_OUTSIDE（落在窗口外的按下点）。窗口自身只有 1 像素，
     * 真被点中也无所谓；这里对所有事件都返回 false，尽量不做任何消费动作。
     */
    private class ProbeView(context: Context) : View(context) {

        var onOutside: ((Float, Float) -> Unit)? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                runCatching { onOutside?.invoke(event.rawX, event.rawY) }
                return true
            }
            return false
        }
    }

    companion object {
        private const val TAG = "HomeBar/Probe"
        private const val WINDOW_TITLE = "HomeBarTouchProbe"

        /** 1 个像素。这不是「调小」，而是安全上界的来源，请勿放大。 */
        private const val PROBE_SIZE = 1
    }
}
