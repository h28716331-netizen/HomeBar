package com.homebar.core

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.text.TextUtils
import com.homebar.service.HomeBarAccessibilityService

/**
 * 参数持久化。用最朴素的 SharedPreferences：
 * 无障碍服务与 Activity 分属两个进程上下文，SharedPreferences 的跨上下文
 * 监听最省事，也不需要额外引入 DataStore。
 */
class BarPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        val snapshot = listeners.toList()
        for (l in snapshot) l.onSharedPreferenceChanged(sp, key)
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(spListener)
    }

    fun load(): BarConfig {
        val d = BarConfig.DEFAULT
        return BarConfig(
            barWidthDp = sp.getFloat(K_BAR_WIDTH, d.barWidthDp),
            barHeightDp = sp.getFloat(K_BAR_HEIGHT, d.barHeightDp),
            cornerRadiusDp = sp.getFloat(K_CORNER, d.cornerRadiusDp),
            bottomMarginDp = sp.getFloat(K_BOTTOM_MARGIN, d.bottomMarginDp),
            horizontalOffsetDp = sp.getFloat(K_H_OFFSET, d.horizontalOffsetDp),
            opacity = sp.getFloat(K_OPACITY, d.opacity),
            glowEnabled = sp.getBoolean(K_GLOW, d.glowEnabled),

            colorMode = enumOf(sp.getString(K_COLOR_MODE, null), d.colorMode),
            luminanceThreshold = sp.getFloat(K_LUM_THRESHOLD, d.luminanceThreshold),
            sampleIntervalMs = sp.getLong(K_SAMPLE_INTERVAL, d.sampleIntervalMs),
            hysteresis = sp.getFloat(K_HYSTERESIS, d.hysteresis),
            colorFadeMs = sp.getLong(K_COLOR_FADE, d.colorFadeMs),

            touchSenseEnabled = sp.getBoolean(K_TOUCH_SENSE, d.touchSenseEnabled),
            preciseTouchProbe = sp.getBoolean(K_TOUCH_PROBE, d.preciseTouchProbe),

            autoHideEnabled = sp.getBoolean(K_AUTO_HIDE, d.autoHideEnabled),
            idleTimeoutMs = sp.getLong(K_IDLE_TIMEOUT, d.idleTimeoutMs),
            gestureGuardEnabled = sp.getBoolean(K_GESTURE_GUARD, d.gestureGuardEnabled),
            wakeDelayMs = sp.getLong(K_WAKE_DELAY, d.wakeDelayMs),
            hideOnHome = sp.getBoolean(K_HIDE_ON_HOME, d.hideOnHome),
            hideOnShade = sp.getBoolean(K_HIDE_ON_SHADE, d.hideOnShade),
            hideOnLockScreen = sp.getBoolean(K_HIDE_ON_LOCK, d.hideOnLockScreen),

            showDurationMs = sp.getLong(K_SHOW_DURATION, d.showDurationMs),
            hideDurationMs = sp.getLong(K_HIDE_DURATION, d.hideDurationMs),
            showEasing = enumOf(sp.getString(K_SHOW_EASING, null), d.showEasing),
            hideEasing = enumOf(sp.getString(K_HIDE_EASING, null), d.hideEasing),
            animateScale = sp.getBoolean(K_ANIM_SCALE, d.animateScale),
            animateSlide = sp.getBoolean(K_ANIM_SLIDE, d.animateSlide),
            slideDistanceDp = sp.getFloat(K_SLIDE_DISTANCE, d.slideDistanceDp),
            springDamping = sp.getFloat(K_SPRING_DAMPING, d.springDamping),

            bounceOnBarTap = sp.getBoolean(K_BOUNCE_ON_TAP, d.bounceOnBarTap),
            bounceEnabled = sp.getBoolean(K_BOUNCE_ENABLED, d.bounceEnabled),
            bounceAmplitudeDp = sp.getFloat(K_BOUNCE_AMPLITUDE, d.bounceAmplitudeDp),
            bounceDurationMs = sp.getLong(K_BOUNCE_DURATION, d.bounceDurationMs),
            bounceEasing = enumOf(sp.getString(K_BOUNCE_EASING, null), d.bounceEasing),
            bounceSpringDamping = sp.getFloat(K_BOUNCE_SPRING_DAMPING, d.bounceSpringDamping),

            bezierX1 = sp.getFloat(K_BZ_X1, d.bezierX1),
            bezierY1 = sp.getFloat(K_BZ_Y1, d.bezierY1),
            bezierX2 = sp.getFloat(K_BZ_X2, d.bezierX2),
            bezierY2 = sp.getFloat(K_BZ_Y2, d.bezierY2),
        )
    }

    fun save(cfg: BarConfig) {
        sp.edit()
            .putFloat(K_BAR_WIDTH, cfg.barWidthDp)
            .putFloat(K_BAR_HEIGHT, cfg.barHeightDp)
            .putFloat(K_CORNER, cfg.cornerRadiusDp)
            .putFloat(K_BOTTOM_MARGIN, cfg.bottomMarginDp)
            .putFloat(K_H_OFFSET, cfg.horizontalOffsetDp)
            .putFloat(K_OPACITY, cfg.opacity)
            .putBoolean(K_GLOW, cfg.glowEnabled)

            .putString(K_COLOR_MODE, cfg.colorMode.name)
            .putFloat(K_LUM_THRESHOLD, cfg.luminanceThreshold)
            .putLong(K_SAMPLE_INTERVAL, cfg.sampleIntervalMs)
            .putFloat(K_HYSTERESIS, cfg.hysteresis)
            .putLong(K_COLOR_FADE, cfg.colorFadeMs)

            .putBoolean(K_TOUCH_SENSE, cfg.touchSenseEnabled)
            .putBoolean(K_TOUCH_PROBE, cfg.preciseTouchProbe)

            .putBoolean(K_AUTO_HIDE, cfg.autoHideEnabled)
            .putLong(K_IDLE_TIMEOUT, cfg.idleTimeoutMs)
            .putBoolean(K_GESTURE_GUARD, cfg.gestureGuardEnabled)
            .putLong(K_WAKE_DELAY, cfg.wakeDelayMs)
            .putBoolean(K_HIDE_ON_HOME, cfg.hideOnHome)
            .putBoolean(K_HIDE_ON_SHADE, cfg.hideOnShade)
            .putBoolean(K_HIDE_ON_LOCK, cfg.hideOnLockScreen)

            .putLong(K_SHOW_DURATION, cfg.showDurationMs)
            .putLong(K_HIDE_DURATION, cfg.hideDurationMs)
            .putString(K_SHOW_EASING, cfg.showEasing.name)
            .putString(K_HIDE_EASING, cfg.hideEasing.name)
            .putBoolean(K_ANIM_SCALE, cfg.animateScale)
            .putBoolean(K_ANIM_SLIDE, cfg.animateSlide)
            .putFloat(K_SLIDE_DISTANCE, cfg.slideDistanceDp)
            .putFloat(K_SPRING_DAMPING, cfg.springDamping)

            .putBoolean(K_BOUNCE_ON_TAP, cfg.bounceOnBarTap)
            .putBoolean(K_BOUNCE_ENABLED, cfg.bounceEnabled)
            .putFloat(K_BOUNCE_AMPLITUDE, cfg.bounceAmplitudeDp)
            .putLong(K_BOUNCE_DURATION, cfg.bounceDurationMs)
            .putString(K_BOUNCE_EASING, cfg.bounceEasing.name)
            .putFloat(K_BOUNCE_SPRING_DAMPING, cfg.bounceSpringDamping)

            .putFloat(K_BZ_X1, cfg.bezierX1)
            .putFloat(K_BZ_Y1, cfg.bezierY1)
            .putFloat(K_BZ_X2, cfg.bezierX2)
            .putFloat(K_BZ_Y2, cfg.bezierY2)
            .apply()
    }

    /**
     * 「暂停」开关：和参数分开存，避免被 save() 覆盖。
     * 暂停后服务立刻撤掉悬浮层与探测点，不需要去系统设置里关服务。
     */
    fun isPaused(): Boolean = sp.getBoolean(K_PAUSED, false)

    fun setPaused(paused: Boolean) {
        sp.edit().putBoolean(K_PAUSED, paused).apply()
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(l)
    }

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(l)
    }

    fun release() {
        listeners.clear()
        sp.unregisterOnSharedPreferenceChangeListener(spListener)
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        if (name == null) fallback
        else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    companion object {
        const val FILE = "home_bar_prefs"

        private const val K_BAR_WIDTH = "bar_width"
        private const val K_BAR_HEIGHT = "bar_height"
        private const val K_CORNER = "corner_radius"
        private const val K_BOTTOM_MARGIN = "bottom_margin"
        private const val K_H_OFFSET = "h_offset"
        private const val K_OPACITY = "opacity"
        private const val K_GLOW = "glow"

        private const val K_COLOR_MODE = "color_mode"
        private const val K_LUM_THRESHOLD = "lum_threshold"
        private const val K_SAMPLE_INTERVAL = "sample_interval"
        private const val K_HYSTERESIS = "hysteresis"
        private const val K_COLOR_FADE = "color_fade"

        private const val K_TOUCH_SENSE = "touch_sense"
        private const val K_TOUCH_PROBE = "touch_probe"

        private const val K_AUTO_HIDE = "auto_hide"
        private const val K_IDLE_TIMEOUT = "idle_timeout"
        private const val K_GESTURE_GUARD = "gesture_guard"
        private const val K_WAKE_DELAY = "wake_delay"
        private const val K_HIDE_ON_HOME = "hide_on_home"
        private const val K_HIDE_ON_SHADE = "hide_on_shade"
        private const val K_HIDE_ON_LOCK = "hide_on_lock_screen"
        private const val K_PAUSED = "paused"

        private const val K_SHOW_DURATION = "show_duration"
        private const val K_HIDE_DURATION = "hide_duration"
        private const val K_SHOW_EASING = "show_easing"
        private const val K_HIDE_EASING = "hide_easing"
        private const val K_ANIM_SCALE = "anim_scale"
        private const val K_ANIM_SLIDE = "anim_slide"
        private const val K_SLIDE_DISTANCE = "slide_distance"
        private const val K_SPRING_DAMPING = "spring_damping"

        private const val K_BOUNCE_ENABLED = "bounce_enabled"
        private const val K_BOUNCE_ON_TAP = "bounce_on_bar_tap"
        private const val K_BOUNCE_AMPLITUDE = "bounce_amplitude"
        private const val K_BOUNCE_DURATION = "bounce_duration"
        private const val K_BOUNCE_EASING = "bounce_easing"
        private const val K_BOUNCE_SPRING_DAMPING = "bounce_spring_damping"

        private const val K_BZ_X1 = "bz_x1"
        private const val K_BZ_Y1 = "bz_y1"
        private const val K_BZ_X2 = "bz_x2"
        private const val K_BZ_Y2 = "bz_y2"

        /** 判断无障碍服务是否已在系统设置里开启 */
        fun isServiceEnabled(context: Context): Boolean {
            val component = ComponentName(context, HomeBarAccessibilityService::class.java)
            val flatLong = component.flattenToString()
            val flatShort = component.flattenToShortString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            if (TextUtils.isEmpty(enabled)) return false
            return enabled.split(':').any {
                it.equals(flatLong, ignoreCase = true) || it.equals(flatShort, ignoreCase = true)
            }
        }
    }
}
