package com.homebar.core

/**
 * 指示条取色方式。
 */
enum class ColorMode(val label: String) {
    /** 跟随背景明暗自动切换黑白 */
    AUTO("自动（跟随背景）"),
    WHITE("固定白色"),
    BLACK("固定黑色"),
}

/**
 * 可选的「速度曲线」。弹跳、出现、消失动画都可以独立选一条。
 */
enum class EasingPreset(val label: String) {
    LINEAR("线性 · 匀速"),
    DECELERATE("减速 · 快进慢出"),
    ACCELERATE("加速 · 慢进快出"),
    ACCELERATE_DECELERATE("缓入缓出 · 标准"),
    OVERSHOOT("回弹 · 冲过头再收回"),
    ANTICIPATE_OVERSHOOT("预备回弹 · 先退再冲"),
    SPRING("弹簧 · 阻尼可调"),
    BOUNCE("弹跳 · 落地回弹"),
    CUSTOM_BEZIER("自定义贝塞尔曲线"),
}

/**
 * 一根指示条的全部可调参数。
 *
 * 全部单位统一用 dp / ms，UI 层直接绑定即可。
 */
data class BarConfig(
    // ── 几何 ─────────────────────────────────────────────
    val barWidthDp: Float = 140f,
    val barHeightDp: Float = 5f,
    val cornerRadiusDp: Float = 2.5f,
    /** 指示条底边距离屏幕底边的距离 */
    val bottomMarginDp: Float = 10f,
    /** 水平偏移，负值向左 */
    val horizontalOffsetDp: Float = 0f,
    /** 不透明度 0.2 ~ 1.0 */
    val opacity: Float = 0.95f,
    /** 是否画一圈柔光，让它在复杂背景上更清晰 */
    val glowEnabled: Boolean = true,

    // ── 颜色 ─────────────────────────────────────────────
    val colorMode: ColorMode = ColorMode.AUTO,
    /** 背景亮度高于该阈值 → 用黑条；低于 → 用白条 */
    val luminanceThreshold: Float = 0.55f,
    /** 自动取色的采样间隔（越大越省电，越小越跟手） */
    val sampleIntervalMs: Long = 1200L,
    /** 迟滞区间，避免临界亮度反复横跳闪烁 */
    val hysteresis: Float = 0.07f,
    /** 黑白切换时的渐变时长 */
    val colorFadeMs: Long = 260L,

    // ── 触摸感知（安全相关，默认全开且都是零风险路径）────────
    /**
     * 订阅无障碍「触摸交互开始 / 结束」事件。
     * 纯事件订阅，只能「知道用户碰了屏幕」，结构上无法拦截任何输入。
     */
    val touchSenseEnabled: Boolean = true,
    /**
     * 1×1 像素的坐标探测点，用来判断「手指是不是碰到了屏幕底部」。
     * 打开后最多只会吃掉屏幕上 1 个像素的触摸，安全上界可证明。
     */
    val preciseTouchProbe: Boolean = true,

    // ── 沉浸（自动隐藏）────────────────────────────────────
    val autoHideEnabled: Boolean = true,
    /** 屏幕无操作多久后自动隐藏 */
    val idleTimeoutMs: Long = 2000L,

    /**
     * 手势保护：上滑退出应用 / 回桌面时，不要把条带出来（v1.6.0 默认开）。
     *
     * 原理：跨应用拿不到触摸坐标，所以「这一下是不是底部上滑」在按下的瞬间
     * 是**不可能**判断出来的 —— 唯一的判断依据是「松手之后前台变成了谁」。
     *
     * 于是改成「延迟唤醒 + 可撤销」：
     *   · 触摸后不立刻显示，先等 [wakeDelayMs]
     *   · 这段时间里只要发现前台切到了桌面（或进入抑制态），就把这次唤醒整个丢掉
     *   · 如果条已经开始淡入一点点，直接归零（不是淡出），肉眼基本看不到
     *
     * 代价是「碰屏幕 → 条出现」会慢 [wakeDelayMs]，所以做成了可调。
     */
    val gestureGuardEnabled: Boolean = true,
    /** 触摸唤醒的延迟。越大越不容易被手势带出来，但唤醒越迟钝。0 = 立即（等于关闭手势保护） */
    val wakeDelayMs: Long = 300L,
    /**
     * 桌面隐藏：在 Launcher（桌面 / 最近任务）上不显示指示条，
     * 进入任意应用时再淡入。
     *
     * 判定依据是前台窗口的包名（无障碍 TYPE_WINDOW_STATE_CHANGED），
     * 不涉及任何触摸拦截，也不申请「使用情况访问」这类额外权限。
     */
    val hideOnHome: Boolean = true,
    /**
     * 通知中心 / 控制中心展开时隐藏。
     *
     * 通知栏不是 Activity，下拉时【不一定】有 TYPE_WINDOW_STATE_CHANGED，
     * 所以这里改用无障碍窗口列表判断：出现一个「宽 ≥ 80% 屏幕、高 ≥ 30% 屏幕」
     * 的 TYPE_SYSTEM 窗口，就认为通知栏 / 控制中心已经展开。
     * 状态栏只有几十 dp、导航栏只有底部一条，都不会误判。
     */
    val hideOnShade: Boolean = true,
    /**
     * 锁屏隐藏：熄屏、锁屏界面、以及锁屏上的通知 / 快捷面板上都不显示。
     *
     * 判定用的是 KeyguardManager.isKeyguardLocked()，**不需要任何权限**。
     * 时机由三个系统广播驱动：
     *   · ACTION_SCREEN_OFF  → 立即视为锁定（同时避免条出现在 AOD 息屏显示上）
     *   · ACTION_SCREEN_ON   → 问一次 KeyguardManager，决定显不显示
     *   · ACTION_USER_PRESENT → 用户真正解锁，恢复显示
     *
     * 为什么不能只看 SCREEN_ON：很多 ROM 亮屏后停在锁屏界面，
     * 此时屏幕是「亮着」的，但用户还没解锁。
     */
    val hideOnLockScreen: Boolean = true,

    // ── 出现 / 消失动画 ───────────────────────────────────
    val showDurationMs: Long = 300L,
    val hideDurationMs: Long = 240L,
    val showEasing: EasingPreset = EasingPreset.SPRING,
    val hideEasing: EasingPreset = EasingPreset.ACCELERATE,
    val animateScale: Boolean = true,
    val animateSlide: Boolean = true,
    /** 出现时从下方滑入的距离 */
    val slideDistanceDp: Float = 6f,
    /** 弹簧阻尼（仅 showEasing = SPRING 时生效）0.15 很弹 ~ 1.0 不弹 */
    val springDamping: Float = 0.62f,

    // ── 触摸弹跳 ──────────────────────────────────────────
    /**
     * 只在点击小白条时弹跳（v1.5.0 默认）。
     *
     * 关掉则退回旧行为：屏幕上任何一次触摸都弹。
     *
     * 两种模式的代价不一样：
     *   · 开（默认）：只对条本身做了一次可触摸的小窗口，条以外的区域完全不受影响，
     *     打字、滑列表都不会再乱弹；代价是点在条上那一下会被我们吃掉（不传给下面的应用）。
     *   · 关：结构上完全不可触摸，最安全，但每次碰屏幕都会弹，比较吵。
     */
    val bounceOnBarTap: Boolean = true,
    /**
     * 是否启用弹跳。
     *
     * 注意：bounceOnBarTap = false 时只能做到「每次触摸屏幕时弹」，
     * 做不到「仅屏幕底部弹」。原因见 TouchProbe 注释 —— Android 在输入分发层
     * 对跨应用触摸做了隐私保护，别的应用里我们只能收到「有人碰了屏幕」，
     * 坐标会被系统强制清零（AOSP InputTarget::Flags::ZERO_COORDS）。
     */
    val bounceEnabled: Boolean = true,
    /** 弹跳幅度（向上弹起的高度） */
    val bounceAmplitudeDp: Float = 9f,
    /** 一次弹跳的总时长 */
    val bounceDurationMs: Long = 420L,
    val bounceEasing: EasingPreset = EasingPreset.OVERSHOOT,
    /** 弹跳时的弹簧阻尼（仅 bounceEasing = SPRING 时生效） */
    val bounceSpringDamping: Float = 0.35f,

    // ── 自定义贝塞尔（当某个动画选了 CUSTOM_BEZIER 时生效）──
    val bezierX1: Float = 0.34f,
    val bezierY1: Float = 1.35f,
    val bezierX2: Float = 0.64f,
    val bezierY2: Float = 1.0f,
) {
    companion object {
        val DEFAULT = BarConfig()

        /** iOS 风格：细长、贴底、纯白 */
        val IOS_STYLE = DEFAULT.copy(
            barWidthDp = 140f, barHeightDp = 5f, cornerRadiusDp = 2.5f,
            bottomMarginDp = 8f, horizontalOffsetDp = 0f, opacity = 1f,
            idleTimeoutMs = 2000L, bounceAmplitudeDp = 9f, bounceDurationMs = 420L,
        )

        /** Pixel 系统风格：更短更粗 */
        val PIXEL_STYLE = DEFAULT.copy(
            barWidthDp = 108f, barHeightDp = 4f, cornerRadiusDp = 2f,
            bottomMarginDp = 12f, opacity = 0.9f,
            idleTimeoutMs = 1500L, bounceAmplitudeDp = 6f, bounceDurationMs = 320L,
        )

        /** 显眼风格：又宽又厚 */
        val BOLD_STYLE = DEFAULT.copy(
            barWidthDp = 200f, barHeightDp = 8f, cornerRadiusDp = 4f,
            bottomMarginDp = 14f, opacity = 1f,
            bounceAmplitudeDp = 14f, bounceDurationMs = 500L,
        )
    }
}
