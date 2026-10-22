package com.homebar.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import com.homebar.core.BarConfig
import com.homebar.core.ColorMode
import com.homebar.core.Easing

/**
 * 悬浮指示条的窗口控制器。
 *
 * 设计要点（与 EdgeGesture 那类项目的本质区别）：
 *
 * 1. 窗口类型用 TYPE_ACCESSIBILITY_OVERLAY，而不是 TYPE_APPLICATION_OVERLAY。
 *    前者不需要「显示在其他应用上层」权限，也不会进入 WindowManager 的
 *    应用悬浮窗管控路径。
 *
 * 2. 窗口永远带 FLAG_NOT_TOUCHABLE。
 *    这是「不改动系统原有动画」的关键：不可触摸的窗口会被输入系统直接跳过，
 *    返回手势、上滑回桌面、预测性返回动画全部由 SystemUI / Launcher 原样处理，
 *    我们连一个 MotionEvent 都收不到，更谈不上消费或重放。
 *
 * 3. 不调用 setGestureDetectionPassthroughRegion、不注入手势、不改动画缩放。
 *
 * 4. 出现/消失/弹跳全部在 View 内部一次性绘制，避免多层 View 属性动画
 *    互相打断造成的「僵硬」观感。
 */
class BarOverlayController(
    private val service: AccessibilityService,
    initialConfig: BarConfig,
) {

    private val wm: WindowManager? = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density: Float get() = service.resources.displayMetrics.density
    private val argbEvaluator = ArgbEvaluator()
    private val sampler = ScreenColorSampler(service)

    /**
     * 点击感应层：只覆盖条本身的小窗口。
     * 绘制窗口永远不可触摸，见 [BarTouchTarget] 的说明。
     */
    private val touchTarget = BarTouchTarget(service)

    var config: BarConfig = initialConfig
        private set

    private var view: BarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    /**
     * 系统界面抑制：为 true 时指示条被强制收起，且期间不响应任何「该显示了」的请求。
     *
     * 覆盖「桌面 / 最近任务」和「通知中心 / 控制中心」两类场景。
     *
     * 为什么不能只调一次 hide()：桌面上的每一次触摸都会走 onTouchDown() → show()，
     * 如果只 hide 而不锁住，用户一碰桌面它又冒出来了。所以这里是一个「抑制态」，
     * 由服务根据前台窗口包名与窗口列表在 true / false 之间切换。
     */
    private var suppressed = false

    /** 逻辑上的可见状态（动画进行中仍算可见） */
    var isVisible: Boolean = false
        private set

    private var currentBarColor: Int = Color.WHITE

    private var showAnimator: ValueAnimator? = null
    private var bounceAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    private var lastBounceRequestAt = 0L

    private val hideRunnable = Runnable { hide() }

    /**
     * 「待唤醒」。
     *
     * 手势保护开启时，触摸不会立刻把条显示出来，而是先挂一个延迟任务：
     * 这段时间里如果前台切到了桌面（上滑退出的手势结束），就把任务撤掉，
     * 条自始至终没出现过。
     */
    private var wakeWithBounce = false

    private val wakeRunnable = Runnable {
        val withBounce = wakeWithBounce
        wakeWithBounce = false
        show()
        if (withBounce) bounce(0L)
    }

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!isVisible) return
            val cfg = config
            if (cfg.colorMode != ColorMode.AUTO) return
            val v = view ?: return
            if (!v.isAttachedToWindow || v.width == 0) {
                handler.postDelayed(this, 150L)
                return
            }
            val bounds = screenBounds()
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val local = RectF()
            v.barRectOnScreen(local)
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            local.offset(loc[0].toFloat(), loc[1].toFloat())

            sampler.setMinInterval(cfg.sampleIntervalMs)
            sampler.sample(bounds.width(), bounds.height(), local) { luminance ->
                if (isVisible && config.colorMode == ColorMode.AUTO) {
                    setBarColor(decideColor(luminance), animated = true)
                }
            }
            handler.postDelayed(this, cfg.sampleIntervalMs)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────

    fun attach() {
        if (attached) return
        val manager = wm ?: run {
            Log.e(TAG, "WindowManager unavailable")
            return
        }
        val v = BarView(service)
        val p = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            setFitInsetsTypes(0)
            setTitle(WINDOW_TITLE)
        }

        view = v
        params = p
        applyLayout(initial = true)

        val ok = runCatching { manager.addView(v, p) }.isSuccess
        if (!ok) {
            Log.e(TAG, "addView failed")
            view = null
            params = null
            return
        }
        attached = true
        v.showProgress = 0f
        currentBarColor = initialColor()
        v.barColor = currentBarColor

        // 点击感应层：只有「点击小白条才弹跳」模式才需要它
        touchTarget.onTap = { onBarTapped() }
        touchTarget.attach()
        updateTouchTarget()

        // 如果挂载时正好处于需要隐藏的界面，直接以收起状态上去，避免闪一下再消失
        if (suppressed) {
            isVisible = false
            stopSampling()
        } else {
            show()
        }
    }

    fun detach() {
        if (!attached) return
        handler.removeCallbacksAndMessages(null)
        cancelAnimators()
        sampler.shutdown()
        touchTarget.detach()
        val v = view
        val manager = wm
        if (v != null && manager != null) {
            runCatching { manager.removeView(v) }
        }
        view = null
        params = null
        attached = false
        isVisible = false
    }

    fun onConfigChanged(newConfig: BarConfig) {
        val geometryChanged = geometryDiffers(config, newConfig)
        val colorModeChanged = config.colorMode != newConfig.colorMode
        val idleChanged = config.autoHideEnabled != newConfig.autoHideEnabled ||
            config.idleTimeoutMs != newConfig.idleTimeoutMs
        config = newConfig

        // 两个隐藏开关都关掉时立刻解除抑制，不用等服务再走一轮
        if (!newConfig.hideOnHome && !newConfig.hideOnShade) setSuppressed(false)

        if (geometryChanged) applyLayout()
        if (colorModeChanged) {
            setBarColor(initialColor(), animated = true)
            if (newConfig.colorMode == ColorMode.AUTO) startSampling() else stopSampling()
        }
        if (idleChanged && isVisible) scheduleHide()
        applyVisualTuning()
        updateTouchTarget()
    }

    /** 屏幕旋转 / 尺寸变化 */
    fun onDisplayChanged() {
        applyLayout()
    }

    fun onScreenInteractive(interactive: Boolean) {
        if (!interactive) {
            stopSampling()
        } else if (isVisible) {
            startSampling()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 对外动作
    // ─────────────────────────────────────────────────────────────

    /**
     * 用户触碰了屏幕。
     *
     * 【重要】坐标是可选信息，不要把它当成判断依据。
     * 见 TouchProbe 的注释：Android 在输入分发层对跨应用触摸做了隐私保护，
     * 非同一 UID 的窗口拿到的坐标会被强制清零。所以本应用在【别的应用里】
     * 永远只知道「有人碰了屏幕」，不知道碰在哪 —— 这也是「仅屏幕底部弹跳」
     * 无法实现的原因。这里只按「时机」决定弹不弹。
     *
     * @param x 屏幕绝对坐标（仅当触摸发生在小白条自己的界面上时才有效）
     * @param y 屏幕绝对坐标
     */
    fun onUserTouch(x: Float, y: Float) {
        onTouchDown()
    }

    /** 知道有人碰了屏幕，但坐标被系统隐藏（跨应用触摸的常态） */
    fun onTouchWithoutCoordinates() {
        onTouchDown()
    }

    private fun onTouchDown() {
        if (!config.touchSenseEnabled) return
        // 桌面 / 通知栏等界面处于抑制态时，碰屏幕不该把它唤出来
        if (suppressed) return
        val cfg = config

        // ── 手势保护（v1.6.0）──
        // 上滑退出 / 回桌面也是从「一次触摸」开始的，按下的那一刻无法区分。
        // 所以先不显示，等 wakeDelayMs 之后再显示；期间一旦判定为「回桌面」，
        // onHomeCandidate() 会把这个延迟任务撤掉 —— 条压根不会出现。
        if (cfg.gestureGuardEnabled) {
            wakeWithBounce = !cfg.bounceOnBarTap && cfg.bounceEnabled
            scheduleWake(cfg.wakeDelayMs)
            return
        }

        val wasFullyVisible = isVisible && (view?.showProgress ?: 0f) >= 0.985f
        show()
        // v1.5.0：默认只在点击小白条时弹跳。
        // 屏幕上任何一次触摸都弹，在打字、滑列表时太吵，而且那种「碰一下就弹」
        // 和手指位置完全无关，观感上更像 bug。
        if (cfg.bounceOnBarTap) return
        if (!cfg.bounceEnabled) return
        // 刚被唤起的条先淡入一点点再弹，两个动画叠在一起更自然
        bounce(if (wasFullyVisible) 0L else 70L)
    }

    private fun scheduleWake(delayMs: Long) {
        handler.removeCallbacks(wakeRunnable)
        if (delayMs <= 0L) {
            wakeRunnable.run()
        } else {
            handler.postDelayed(wakeRunnable, delayMs)
        }
    }

    /** 撤掉还没执行的唤醒（手势保护的核心：让条「自始至终没出现过」） */
    private fun cancelPendingWake() {
        handler.removeCallbacks(wakeRunnable)
        wakeWithBounce = false
    }

    /**
     * 服务发现「前台正在回到桌面」时立刻调用（不等 120ms 确认）。
     *
     * 两件事：
     *   1. 撤掉还没执行的唤醒任务
     *   2. 如果条已经被唤醒、但只淡入了不到 [SNAP_HIDE_PROGRESS]，
     *      直接归零而不是走淡出 —— 上滑手势里那一闪就是这么消掉的
     */
    fun onHomeCandidate() {
        cancelPendingWake()
        val v = view ?: return
        if (!isVisible) return
        if (v.showProgress >= SNAP_HIDE_PROGRESS) return
        isVisible = false
        stopSampling()
        showAnimator?.cancel()
        showAnimator = null
        v.showProgress = 0f
        updateTouchTarget()
    }

    /**
     * 点中了小白条（由 [BarTouchTarget] 回调）。
     *
     * 这是「只在点条时弹跳」的唯一触发点：感应窗口只覆盖条本身，
     * 条以外的触摸根本不会走到这里。
     */
    private fun onBarTapped() {
        if (suppressed) return
        val cfg = config
        if (!cfg.bounceEnabled || !cfg.bounceOnBarTap) return
        // 点它通常是想「把它叫出来」，顺带重置自动隐藏计时
        show()
        bounce(0L)
    }

    /** 只有「发生了交互」这一事实，不触发弹跳（窗口变化等） */
    fun onUserInteraction() {
        if (!config.touchSenseEnabled) return
        show()
    }

    /**
     * 系统界面抑制开关（由服务根据前台窗口包名 / 窗口列表切换）。
     *
     * 覆盖桌面、最近任务、通知中心、控制中心。
     * 置 true 时走正常的消失动画收起 —— 不是瞬隐，所以「从应用回到桌面」
     * 的观感和「无操作自动隐藏」完全一致，不会突兀。
     */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) {
            cancelHide()
            cancelPendingWake()
            hide()
        } else {
            show()
        }
    }

    fun show() {
        if (suppressed) return
        val cfg = config
        cancelHide()
        // 已经被明确要求显示了，就不需要那个延迟唤醒
        handler.removeCallbacks(wakeRunnable)
        wakeWithBounce = false
        val v = view ?: return
        isVisible = true
        val from = v.showProgress
        if (from >= 1f) {
            v.showProgress = 1f
        } else {
            animateShowProgress(from, 1f, cfg.showDurationMs, Easing.forShow(cfg))
        }
        scheduleHide()
        startSampling()
        if (cfg.colorMode != ColorMode.AUTO) {
            setBarColor(initialColor(), animated = true)
        }
        updateTouchTarget()
    }

    fun hide() {
        val cfg = config
        val v = view ?: return
        isVisible = false
        stopSampling()
        // 条一开始淡出就撤掉触摸感应，避免「看不见但能点」的死区
        updateTouchTarget()
        val from = v.showProgress
        if (from <= 0f) {
            v.showProgress = 0f
        } else if (from < SNAP_HIDE_PROGRESS) {
            // 只淡入了这么一点点就要求收起 —— 大概率是「上滑退出的手势」，
            // 这时候走淡出反而会看见一条尾巴，直接归零更干净
            showAnimator?.cancel()
            showAnimator = null
            v.showProgress = 0f
        } else {
            animateShowProgress(from, 0f, cfg.hideDurationMs, Easing.forHide(cfg))
        }
    }

    fun bounce(delayMs: Long = 0L) {
        if (!config.bounceEnabled) return
        // 一次触摸可能同时从「无障碍事件」和「坐标探测点」两条路径到达，
        // 这里做一次去重，避免同一根手指弹出两次。
        val now = System.currentTimeMillis()
        if (now - lastBounceRequestAt < BOUNCE_DEBOUNCE_MS) return
        lastBounceRequestAt = now
        val action = Runnable { startBounce() }
        if (delayMs > 0L) handler.postDelayed(action, delayMs) else action.run()
    }

    // ─────────────────────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────────────────────

    private fun cancelHide() {
        handler.removeCallbacks(hideRunnable)
    }

    private fun scheduleHide() {
        cancelHide()
        val cfg = config
        if (!cfg.autoHideEnabled) return
        handler.postDelayed(hideRunnable, cfg.idleTimeoutMs.coerceAtLeast(300L))
    }

    private fun startSampling() {
        if (config.colorMode != ColorMode.AUTO) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // 已经在采样就不要重置节奏，否则手指在屏幕上滑动时会被反复打断
        if (handler.hasCallbacks(sampleRunnable)) return
        handler.postDelayed(sampleRunnable, 120L)
    }

    private fun stopSampling() {
        handler.removeCallbacks(sampleRunnable)
    }

    private fun animateShowProgress(
        from: Float,
        target: Float,
        durationMs: Long,
        interpolator: Interpolator,
    ) {
        val v = view ?: return
        showAnimator?.cancel()
        if (durationMs <= 0L || kotlin.math.abs(from - target) < 0.001f) {
            v.showProgress = target
            return
        }
        val delta = target - from
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                v.showProgress = from + delta * interpolator.getInterpolation(t)
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (v.showProgress != target) v.showProgress = target
            }
        })
        showAnimator = animator
        animator.start()
    }

    private fun startBounce() {
        val cfg = config
        val v = view ?: return
        if (!cfg.bounceEnabled) return
        bounceAnimator?.cancel()
        val amplitude = cfg.bounceAmplitudeDp * density
        val interpolator = Easing.forBounce(cfg)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cfg.bounceDurationMs.coerceAtLeast(90L)
            this.interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val shape = Easing.bounceShape(anim.animatedFraction, interpolator)
                v.bounceOffset = (amplitude * shape).coerceAtLeast(-amplitude * 0.25f)
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                v.bounceOffset = 0f
            }
        })
        bounceAnimator = animator
        animator.start()
    }

    private fun setBarColor(color: Int, animated: Boolean) {
        val v = view ?: return
        if (color == currentBarColor && v.barColor == color) return
        val cfg = config
        colorAnimator?.cancel()
        if (!animated || cfg.colorFadeMs <= 0L) {
            currentBarColor = color
            v.barColor = color
            return
        }
        val from = currentBarColor
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cfg.colorFadeMs
            this.interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                @Suppress("UNCHECKED_CAST")
                val c = argbEvaluator.evaluate(anim.animatedFraction, from, color) as Int
                v.barColor = c
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                currentBarColor = color
                v.barColor = color
            }
        })
        colorAnimator = animator
        animator.start()
    }

    private fun initialColor(): Int = when (config.colorMode) {
        ColorMode.WHITE -> Color.WHITE
        ColorMode.BLACK -> Color.BLACK
        ColorMode.AUTO -> if (isBlack(currentBarColor)) Color.BLACK else Color.WHITE
    }

    /**
     * 迟滞判断：背景亮度在阈值附近抖动时不会来回切换颜色。
     */
    private fun decideColor(luminance: Float): Int {
        val cfg = config
        return when (cfg.colorMode) {
            ColorMode.WHITE -> Color.WHITE
            ColorMode.BLACK -> Color.BLACK
            ColorMode.AUTO -> {
                val th = cfg.luminanceThreshold
                val h = cfg.hysteresis
                val currentlyBlack = isBlack(currentBarColor)
                val shouldBeBlack =
                    if (currentlyBlack) luminance > th - h else luminance > th + h
                if (shouldBeBlack) Color.BLACK else Color.WHITE
            }
        }
    }

    private fun isBlack(color: Int): Boolean {
        val lum = (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
        return lum < 0.5f
    }

    private fun screenBounds(): Rect {
        val manager = wm ?: return Rect()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds
        } else {
            val metrics = service.resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun applyVisualTuning() {
        val v = view ?: return
        v.barOpacity = config.opacity
        v.scaleEnabled = config.animateScale
        v.slideEnabled = config.animateSlide
        v.slideDistance = if (config.animateSlide) config.slideDistanceDp * density else 0f
        v.glowEnabled = config.glowEnabled
    }

    private fun applyLayout(initial: Boolean = false) {
        val v = view ?: return
        val p = params ?: return
        val cfg = config

        val barW = (cfg.barWidthDp * density).coerceAtLeast(8f)
        val barH = (cfg.barHeightDp * density).coerceAtLeast(1f)
        val glow = if (cfg.glowEnabled) GLOW_RADIUS_DP * density else 0f
        val bounceRoom =
            if (cfg.bounceEnabled) cfg.bounceAmplitudeDp * density + 4f * density else 4f * density
        val slideRoom = if (cfg.animateSlide) cfg.slideDistanceDp * density else 0f

        val padH = glow + 2f * density
        val padTop = bounceRoom + slideRoom + glow
        val padBottom = glow + 2f * density

        p.width = (barW + padH * 2f).toInt().coerceAtLeast(1)
        p.height = (padTop + barH + padBottom).toInt().coerceAtLeast(1)
        p.x = (cfg.horizontalOffsetDp * density).toInt()
        // gravity = BOTTOM：窗口底边 = 屏幕底边 + y
        // 想让「条的下边缘」距屏幕底边 bottomMargin，则 y = padBottom - bottomMargin
        p.y = (padBottom - cfg.bottomMarginDp * density).toInt()

        v.setGeometry(padH, padTop, barW, barH, cfg.cornerRadiusDp * density, glow)
        applyVisualTuning()

        if (!initial && v.isAttachedToWindow) {
            runCatching { wm?.updateViewLayout(v, p) }
                .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
        }
        updateTouchTarget()
    }

    /**
     * 把点击感应层对齐到条本身。
     *
     * 绘制窗口比条大一圈（上方要留弹跳 + 滑入 + 柔光的余量，约 30dp），
     * 如果直接让绘制窗口可触摸，条上方那 30dp 就成了「点了没反应」的死区。
     * 所以感应层单独用一个小窗口，只覆盖条 + 少量外扩。
     */
    private fun updateTouchTarget() {
        if (!attached) return
        val cfg = config
        val barW = (cfg.barWidthDp * density).coerceAtLeast(8f)
        val barH = (cfg.barHeightDp * density).coerceAtLeast(1f)
        val slopX = TOUCH_SLOP_X_DP * density
        val slopY = TOUCH_SLOP_Y_DP * density

        val w = (barW + slopX * 2f).toInt()
        val h = (barH + slopY * 2f).toInt()
        val x = (cfg.horizontalOffsetDp * density).toInt()
        // 与绘制窗口同一套坐标约定：窗口底边 = 屏幕底边 + y
        // 这里条的下边缘距窗口底边正好 slopY，所以 y = slopY - bottomMargin
        val y = (slopY - cfg.bottomMarginDp * density).toInt()

        val active = isVisible && !suppressed && cfg.bounceEnabled && cfg.bounceOnBarTap
        touchTarget.update(w, h, x, y, active)
    }

    private fun geometryDiffers(a: BarConfig, b: BarConfig): Boolean =
        a.barWidthDp != b.barWidthDp ||
            a.barHeightDp != b.barHeightDp ||
            a.cornerRadiusDp != b.cornerRadiusDp ||
            a.bottomMarginDp != b.bottomMarginDp ||
            a.horizontalOffsetDp != b.horizontalOffsetDp ||
            a.glowEnabled != b.glowEnabled ||
            a.animateSlide != b.animateSlide ||
            a.slideDistanceDp != b.slideDistanceDp ||
            a.bounceEnabled != b.bounceEnabled ||
            a.bounceAmplitudeDp != b.bounceAmplitudeDp

    private fun cancelAnimators() {
        showAnimator?.cancel(); showAnimator = null
        bounceAnimator?.cancel(); bounceAnimator = null
        colorAnimator?.cancel(); colorAnimator = null
    }

    companion object {
        private const val TAG = "HomeBar/Overlay"
        private const val WINDOW_TITLE = "HomeBarIndicator"
        private const val GLOW_RADIUS_DP = 3f
        private const val BOUNCE_DEBOUNCE_MS = 300L

        /**
         * 淡入进度低于这个值时，收起改为「直接归零」。
         * 用来消掉上滑手势里那一下「刚冒出一点又消失」的尾巴。
         */
        private const val SNAP_HIDE_PROGRESS = 0.45f

        /** 点击感应区相对条向外扩的尺寸（dp）：横向放宽一点，纵向只放一点 */
        private const val TOUCH_SLOP_X_DP = 10f
        private const val TOUCH_SLOP_Y_DP = 7f
    }
}
