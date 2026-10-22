package com.homebar.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.homebar.MainActivity
import com.homebar.R
import com.homebar.core.BarPrefs
import com.homebar.core.TouchDiagnostics
import com.homebar.overlay.BarOverlayController
import com.homebar.overlay.TouchProbe

/**
 * 小白条的核心服务。
 *
 * ══════════════════════════════════════════════════════════════
 *  v1.1.0 重要修复
 * ══════════════════════════════════════════════════════════════
 *  v1.0.0 调用过 AccessibilityServiceInfo.setMotionEventSources(SOURCE_TOUCHSCREEN)，
 *  想「被动观察」触摸。但该 API 的真实语义是把该来源的事件【从输入链路摘走】，
 *  不再派发给 SystemUI / Launcher / 任何应用 —— 等于把整块屏幕的触摸关掉。
 *  这正是装上后「手势失效、屏幕近乎失控」的原因。
 *
 *  本版本已彻底删除：
 *    ✗ setMotionEventSources
 *    ✗ onMotionEvent 覆写
 *    ✗ canRequestTouchExplorationMode
 *    ✗ FLAG_REQUEST_TOUCH_EXPLORATION_MODE（从来没有，也不会有）
 *
 *  触摸感知改为下面两条「结构上就不可能拦截输入」的路径：
 *    A. 无障碍 TYPE_TOUCH_INTERACTION_START / END 事件 —— 纯订阅，只能知道，不能拦
 *    B. 1×1 像素的坐标探测窗口（TouchProbe）—— 最坏情况只影响一个像素
 *
 *  只要两条都拿不到，指示条就退化为「常显 + 不弹跳」，功能少一点，但绝不会影响手机使用。
 * ══════════════════════════════════════════════════════════════
 */
class HomeBarAccessibilityService : AccessibilityService() {

    private lateinit var prefs: BarPrefs
    private var controller: BarOverlayController? = null
    private var probe: TouchProbe? = null
    private val handler = Handler(Looper.getMainLooper())

    private var screenReceiver: BroadcastReceiver? = null
    private var actionReceiver: BroadcastReceiver? = null

    /** 通知去重：null = 还没发过 */
    private var lastNotifiedPaused: Boolean? = null

    // ── 桌面隐藏 ──────────────────────────────────────────────
    /** 所有响应 CATEGORY_HOME 的应用包名（第三方启动器、系统启动器可能同时存在） */
    private var homePackages: Set<String> = emptySet()

    /** 当前是否处于桌面 */
    private var onHome = false

    /** 通知中心 / 控制中心是否展开 */
    private var shadeExpanded = false

    /** 屏幕是否亮着（灭屏时没必要继续查窗口） */
    private var screenInteractive = true

    /** 上一次窗口检测的时间戳（用 uptimeMillis，不受系统改时间影响） */
    private var lastShadeCheckAt = 0L

    /** 一次「窗口变化突发检测」的令牌，用来取消上一轮还没跑完的补测 */
    private var shadeBurstToken: Any? = null

    /** 连续几次拿不到窗口列表时只报一次日志，避免刷屏 */
    private var windowsUnavailableLogged = false

    /** serviceInfo 是否已经在本进程内强制补齐过标志位 */
    private var serviceInfoForced = false

    /**
     * 进入桌面的确认延迟。
     *
     * 从应用回到桌面时，事件序列里偶尔会夹着一次非 Launcher 的窗口事件，
     * 若立即判定会先「显示」再「隐藏」，视觉上闪一下。留这点缓冲即可避免。
     * 反过来「离开桌面」是立即生效的 —— 进应用要马上看到条。
     */
    private val enterHomeRunnable = Runnable {
        runCatching {
            onHome = true
            applySuppression()
        }
    }

    /**
     * 窗口状态轮询。
     *
     * 为什么必须轮询：TYPE_WINDOWS_CHANGED 在部分 ROM / 部分下拉方式（例如从屏幕
     * 顶部继续下拉切到控制中心、或者手势导航的小横条上划）下压根不会派发，只靠
     * 事件会出现「面板已经展开了，但服务完全不知道」的情况 —— 小白条就露在面板上。
     *
     * 一次 getWindows() 只是读几个窗口的元数据（类型 / bounds / 焦点），代价极低，
     * 但能把「漏事件」这条路彻底堵死。
     */
    private val shadePollRunnable = object : Runnable {
        override fun run() {
            if (controller == null || !screenInteractive) return
            evaluateShade(force = true)
            handler.postDelayed(this, SHADE_POLL_MS)
        }
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            runCatching { applyState() }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            prefs = BarPrefs(this)
            prefs.registerListener(prefsListener)
            TouchDiagnostics.reset()
            forceServiceInfo()
            loadHomePackages()
            registerScreenReceiver()
            registerActionReceiver()
            applyState()
            Log.i(TAG, "service connected, paused=${prefs.isPaused()}")
        } catch (t: Throwable) {
            // 任何异常都不要留下一个「半死不活」的服务
            Log.e(TAG, "onServiceConnected failed, disabling self", t)
            runCatching { disableSelf() }
        }
    }

    /**
     * 运行时补齐 serviceInfo 的标志位。
     *
     * 这是 v1.3.0「通知中心里小白条不消失」的第二个根因：
     * 无障碍服务的配置（事件类型、flags）是系统在【开启服务那一刻】读取 XML 并缓存的。
     * 用户是从 1.2.0 直接升级上来的，而 1.2.0 的 XML 里既没有 typeWindowsChanged，
     * 也没有 flagRetrieveInteractiveWindows —— 升级 APK 并不会让系统重新读一遍配置，
     * 于是 getWindows() 一直返回空列表，判定永远是「面板没展开」。
     *
     * 在代码里显式设置一次 serviceInfo，就绕开了「必须让用户去设置里关掉再打开」的坑。
     */
    private fun forceServiceInfo() {
        if (serviceInfoForced) return
        runCatching {
            val info: AccessibilityServiceInfo = serviceInfo ?: return@runCatching
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
            serviceInfo = info
            serviceInfoForced = true
            Log.i(
                TAG,
                "serviceInfo forced: flags=0x${info.flags.toString(16)} events=0x${info.eventTypes.toString(16)}",
            )
        }.onFailure { Log.w(TAG, "forceServiceInfo failed", it) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { prefs.unregisterListener(prefsListener) }
        runCatching { stopShadeWatch() }
        runCatching { controller?.detach() }
        controller = null
        runCatching { probe?.detach() }
        probe = null
        handler.removeCallbacksAndMessages(null)
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        actionReceiver?.let { runCatching { unregisterReceiver(it) } }
        actionReceiver = null
        runCatching { cancelNotification() }
        lastNotifiedPaused = null
        TouchDiagnostics.reset()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onDisplayChanged()
    }

    override fun onInterrupt() {
        // 不需要主动打断任何反馈
    }

    // ─────────────────────────────────────────────────────────────
    // 状态应用（唯一入口，避免各处零散开关导致状态不一致）
    // ─────────────────────────────────────────────────────────────

    private fun applyState() {
        val cfg = prefs.load()
        val paused = prefs.isPaused()

        if (paused) {
            stopShadeWatch()
            controller?.detach()
            controller = null
            probe?.detach()
            probe = null
            syncNotification(paused = true)
            return
        }

        // 先把窗口状态刷新成「此刻真实的样子」，避免用上一次的旧判定去决定抑制态，
        // 否则在通知栏里点「恢复运行」会先闪一下条再收起。
        evaluateShade(force = true)

        // 指示条
        val c = controller ?: BarOverlayController(this, cfg).also { controller = it }
        c.onConfigChanged(cfg)
        // 先设定抑制态再挂窗口，避免「先显示一下再消失」的闪烁
        c.setSuppressed(
            (cfg.hideOnHome && onHome) || (cfg.hideOnShade && shadeExpanded)
        )
        c.attach()

        // 窗口状态轮询：漏事件的 ROM 上靠它兜底
        if (cfg.hideOnShade) startShadeWatch() else stopShadeWatch()

        // 触摸探测点（1×1 像素）—— 本应用唯一的触摸感知通道
        if (cfg.preciseTouchProbe) {
            val p = probe ?: TouchProbe(this).also { probe = it }
            // 触摸的瞬间先核一次窗口状态：下拉通知栏的那一下本身也是一次触摸，
            // 不先核就会「先弹出来再收回去」，视觉上闪一下。
            p.onTouchWithCoords = { x, y ->
                evaluateShade(force = true)
                controller?.onUserTouch(x, y)
            }
            p.onTouchWithoutCoords = {
                evaluateShade(force = true)
                controller?.onTouchWithoutCoordinates()
            }
            p.attach()
        } else {
            probe?.detach()
            probe = null
        }

        syncNotification(paused = false)
    }

    /** 通知只在状态真的变了时才重发，避免拖滑块时刷屏 */
    private fun syncNotification(paused: Boolean) {
        if (lastNotifiedPaused == paused) return
        lastNotifiedPaused = paused
        postNotification(paused)
    }

    // ─────────────────────────────────────────────────────────────
    // 触摸观察（备用通道：无障碍触摸交互事件）
    //
    // ⚠️ 实测 + AOSP 源码核对：AccessibilityWindowManager 里这两个回调的注释是
    //    "Callbacks from AccessibilityManagerService when touch explorer turn on
    //     and motion down detected." —— 也就是说只有【系统开启了触摸探索】时才会派发。
    //    本应用刻意不开启触摸探索（那会改变整机的触摸行为），所以正常情况下这条通道
    //    是收不到东西的。保留订阅只是为了在极少数派发策略不同的 ROM 上多一份兜底，
    //    零成本、零副作用。
    // ─────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString().orEmpty()
        val fromSystemUi = pkg.isNotEmpty() && isSystemUiPackage(pkg)

        // 这两类判定独立于 controller：暂停期间也要继续跟踪，
        // 这样恢复运行时能直接落到正确的显示 / 隐藏状态。
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isNotEmpty()) {
                    if (fromSystemUi) {
                        // 面板展开 / 收起时通常伴随 SystemUI 的窗口状态事件。
                        // 只把它当成「重新看一眼」的触发器，绝不当作判定依据 ——
                        // 否则关掉面板后没有对应事件时就会一直误判为展开。
                        // 同时【不】更新桌面状态：面板浮在桌面上时若把它当成
                        // 「离开桌面」，收起面板后桌面隐藏就失效了。
                        runCatching { requestShadeChecks() }
                    } else {
                        runCatching { updateHomeState(pkg) }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                runCatching { requestShadeChecks() }
            }
        }

        val c = controller ?: return
        runCatching {
            when (e.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    TouchDiagnostics.onEventTouch()
                    // 没有坐标，只知道「有手指按下了」
                    evaluateShade(force = true)
                    c.onTouchWithoutCoordinates()
                }

                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    c.onUserInteraction()
                }

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 前台应用变了，让取色尽快刷新一次。
                    // SystemUI 的事件要排除掉：面板正在展开时不能顺手把条叫出来。
                    if (!fromSystemUi && c.isVisible) c.onUserInteraction()
                }
            }
        }.onFailure { Log.w(TAG, "handle event failed", it) }
    }

    // ─────────────────────────────────────────────────────────────
    // 桌面隐藏
    //
    // 判定只看「前台窗口属于哪个包」，数据来自无障碍 TYPE_WINDOW_STATE_CHANGED
    // 事件里自带的 packageName。这条路：
    //   · 不需要「使用情况访问」(PACKAGE_USAGE_STATS) 那种要手动授权的权限
    //   · 不需要 canRetrieveWindowContent，因此不会去读窗口里的任何内容
    //   · 与触摸无关，完全不参与输入分发
    //
    // 如果查不到 Launcher 列表（包可见性受限），homePackages 会是空集合，
    // 判定永远为 false —— 功能自动失效，但绝不会误隐藏。
    // ─────────────────────────────────────────────────────────────

    private fun loadHomePackages() {
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val resolved = packageManager.queryIntentActivities(intent, 0)
            val set = LinkedHashSet<String>()
            for (ri in resolved) {
                val p = ri.activityInfo?.packageName ?: continue
                set.add(p)
            }
            // 个别 ROM 收紧包可见性时会返回空列表，再用默认 Home 补一次
            if (set.isEmpty()) {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
                    ?.activityInfo?.packageName?.let { set.add(it) }
            }
            homePackages = set
            Log.i(TAG, "home packages = $set")
        }.onFailure { Log.w(TAG, "loadHomePackages failed", it) }
    }

    /**
     * 收到一次窗口变化事件后更新「是否在桌面」。
     *
     * 进入桌面走 [HOME_CONFIRM_MS] 确认（防中间事件造成闪烁），
     * 离开桌面立即生效（进应用要马上看到条）。
     */
    private fun updateHomeState(pkg: String) {
        val isHome = homePackages.contains(pkg)
        if (isHome) {
            // v1.6.0：不等 120ms 确认，先把「待唤醒」撤掉。
            // 上滑退出的手势结束时前台会变成桌面，这一刻条多半还在淡入的极早期，
            // 直接归零，肉眼看不到 —— 这就是「手势不把条带出来」的实现点。
            runCatching { controller?.onHomeCandidate() }
            if (!onHome) {
                handler.removeCallbacks(enterHomeRunnable)
                handler.postDelayed(enterHomeRunnable, HOME_CONFIRM_MS)
            }
        } else {
            handler.removeCallbacks(enterHomeRunnable)
            if (onHome) {
                onHome = false
                applySuppression()
            }
        }
    }

    /**
     * 判断通知中心 / 控制中心是否展开。
     *
     * ══════════════════════════════════════════════════════════
     *  v1.4.0 修复：面板展开时小白条还在
     * ══════════════════════════════════════════════════════════
     *  v1.3.0 只有一条规则：「宽 ≥ 80% 屏幕、高 ≥ 30% 屏幕的 TYPE_SYSTEM 窗口」。
     *  实测这条规则在不少 ROM 上根本不成立，原因有三个，且互相叠加：
     *
     *  ① 事件本身可能不来。通知栏不是 Activity，下拉时是否派发
     *     TYPE_WINDOWS_CHANGED 由 ROM 决定；从屏幕顶部继续下拉切控制中心、
     *     或者用单手模式下拉时，经常一个事件都没有。
     *  ② 就算来了，80ms 节流 + 只测一次，测到的是「面板刚冒头那一帧」——
     *     那时窗口高度还不够 30%，判为未展开，之后面板铺满了也不会有新事件，
     *     状态就永远停在 false。
     *  ③ 部分 ROM 把通知栏窗口的 bounds 报成「只有触摸区域那么大」，
     *     或者类型不是 TYPE_SYSTEM，尺寸规则直接失配。
     *
     *  所以现在改成「三条规则任一命中 + 主动轮询 + 动画补测」：
     *    A. 铺满屏幕的系统面板（完全展开的通知栏 / 控制中心）
     *    B. 拿到了输入焦点的系统面板 —— 面板展开时会成为焦点窗口，
     *       而状态栏 / 导航栏都是 FLAG_NOT_FOCUSABLE，永远不会拿到焦点，
     *       所以这条规则既不依赖 bounds 也不会误判
     *    C. 从屏幕顶端铺下来的宽面板（下拉动画中途、分屏式控制中心）
     *
     *  轮询兜底见 [shadePollRunnable]：250ms 一次，成本可忽略，但绝不会漏。
     */
    private fun evaluateShade(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastShadeCheckAt < SHADE_CHECK_MIN_GAP_MS) return
        lastShadeCheckAt = now

        val list = runCatching { windows }.getOrNull()
        if (list.isNullOrEmpty()) {
            // 拿不到窗口列表说明服务缺少 FLAG_RETRIEVE_INTERACTIVE_WINDOWS（老版本升级上来
            // 的配置缓存里没有）。此时只能退回「不隐藏」，绝不能把条锁在收起状态。
            if (!windowsUnavailableLogged) {
                windowsUnavailableLogged = true
                Log.w(TAG, "窗口列表为空：服务缺少 FLAG_RETRIEVE_INTERACTIVE_WINDOWS")
            }
            if (shadeExpanded) {
                shadeExpanded = false
                applySuppression()
            }
            return
        }
        windowsUnavailableLogged = false

        val expanded = detectShadeExpanded(list)
        if (expanded == shadeExpanded) return
        shadeExpanded = expanded
        Log.i(TAG, "shade expanded = $expanded")
        applySuppression()
    }

    private fun detectShadeExpanded(list: List<AccessibilityWindowInfo>): Boolean {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        if (screenW <= 0f || screenH <= 0f) return false

        for (raw in list) {
            val w = raw ?: continue
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val b = Rect()
            runCatching { w.getBoundsInScreen(b) }.getOrNull() ?: continue
            if (b.width() <= 0 || b.height() <= 0) continue

            val wRatio = b.width() / screenW
            val hRatio = b.height() / screenH

            // A. 铺满屏幕的面板
            if (wRatio >= SHADE_FULL_WIDTH_RATIO && hRatio >= SHADE_FULL_HEIGHT_RATIO) return true

            // B. 拿到输入焦点 / 正在被交互的系统窗口 —— 展开的面板会抢走焦点
            val focused = runCatching { w.isFocused }.getOrDefault(false)
            val active = runCatching { w.isActive }.getOrDefault(false)
            if ((focused || active) &&
                wRatio >= SHADE_FOCUS_WIDTH_RATIO &&
                hRatio >= SHADE_FOCUS_HEIGHT_RATIO
            ) {
                return true
            }

            // C. 从屏幕顶端铺下来的宽面板
            if (b.top <= (screenH * SHADE_TOP_TOLERANCE_RATIO).toInt() &&
                wRatio >= SHADE_TOP_WIDTH_RATIO &&
                hRatio >= SHADE_TOP_HEIGHT_RATIO
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 面板展开 / 收起的动画大约 250~350ms，只在事件到达的那一瞬间测一次必然测到中间态。
     * 这里在事件之后连测几次，取「动画结束后的稳定值」。
     */
    private fun requestShadeChecks() {
        shadeBurstToken?.let { runCatching { handler.removeCallbacksAndMessages(it) } }
        val token = Any()
        shadeBurstToken = token
        val base = SystemClock.uptimeMillis()
        for (delay in SHADE_BURST_DELAYS_MS) {
            handler.postAtTime({ evaluateShade(force = true) }, token, base + delay)
        }
    }

    private fun startShadeWatch() {
        handler.removeCallbacks(shadePollRunnable)
        if (!screenInteractive) return
        handler.postDelayed(shadePollRunnable, SHADE_POLL_MS)
    }

    private fun stopShadeWatch() {
        handler.removeCallbacks(shadePollRunnable)
        shadeBurstToken?.let { runCatching { handler.removeCallbacksAndMessages(it) } }
        shadeBurstToken = null
    }

    private fun isSystemUiPackage(pkg: String): Boolean =
        SYSTEM_UI_PACKAGES.any { pkg.equals(it, ignoreCase = true) }

    /** 综合「桌面」与「通知栏」两个条件，决定指示条是否收起 */
    private fun applySuppression() {
        val cfg = prefs.load()
        val shouldHide = (cfg.hideOnHome && onHome) || (cfg.hideOnShade && shadeExpanded)
        controller?.setSuppressed(shouldHide)
    }

    // ─────────────────────────────────────────────────────────────
    // 常驻通知：出问题时的「紧急出口」
    // ─────────────────────────────────────────────────────────────

    private fun registerActionReceiver() {
        if (actionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_TOGGLE_PAUSE -> {
                        val now = prefs.isPaused()
                        prefs.setPaused(!now)
                    }

                    ACTION_STOP -> {
                        Log.i(TAG, "emergency stop requested from notification")
                        runCatching { disableSelf() }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PAUSE)
            addAction(ACTION_STOP)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            actionReceiver = receiver
        }.onFailure { Log.w(TAG, "register action receiver failed", it) }
    }

    private fun postNotification(paused: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            runCatching { manager.createNotificationChannel(channel) }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleLabel = if (paused) getString(R.string.notif_resume) else getString(R.string.notif_pause)
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_TOGGLE_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_homebar)
            .setContentTitle(
                if (paused) getString(R.string.notif_title_paused)
                else getString(R.string.notif_title_running)
            )
            .setContentText(
                if (paused) getString(R.string.notif_text_paused)
                else getString(R.string.notif_text_running)
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    toggleLabel,
                    toggleIntent,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notif_stop),
                    stopIntent,
                ).build()
            )
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed (通知权限可能未授予)", it) }
    }

    private fun cancelNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    // ─────────────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenInteractive = false
                        stopShadeWatch()
                        controller?.onScreenInteractive(false)
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        screenInteractive = true
                        controller?.onScreenInteractive(true)
                        if (prefs.load().hideOnShade) {
                            evaluateShade(force = true)
                            startShadeWatch()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenReceiver = receiver
        }.onFailure { Log.w(TAG, "register screen receiver failed", it) }
    }

    companion object {
        private const val TAG = "HomeBar/Service"
        private const val CHANNEL_ID = "homebar_status"
        private const val NOTIFICATION_ID = 1001

        /** 进入桌面的确认延迟，用来吃掉回桌面途中的中间窗口事件 */
        private const val HOME_CONFIRM_MS = 120L

        /** A. 铺满屏幕的系统面板：宽度 / 高度占屏幕比例下限 */
        private const val SHADE_FULL_WIDTH_RATIO = 0.80f
        private const val SHADE_FULL_HEIGHT_RATIO = 0.28f

        /** B. 拿到焦点的系统面板：放宽尺寸下限，靠「焦点」这个强信号判定 */
        private const val SHADE_FOCUS_WIDTH_RATIO = 0.55f
        private const val SHADE_FOCUS_HEIGHT_RATIO = 0.18f

        /** C. 从屏幕顶端铺下来的宽面板 */
        private const val SHADE_TOP_TOLERANCE_RATIO = 0.02f
        private const val SHADE_TOP_WIDTH_RATIO = 0.90f
        private const val SHADE_TOP_HEIGHT_RATIO = 0.15f

        /** 两次检测之间的最小间隔，防止窗口变化事件密集时反复读窗口列表 */
        private const val SHADE_CHECK_MIN_GAP_MS = 40L

        /** 轮询间隔：漏事件时的兜底节奏 */
        private const val SHADE_POLL_MS = 250L

        /** 事件之后连测的时间点（ms），用来取「展开动画结束后的稳定值」 */
        private val SHADE_BURST_DELAYS_MS = longArrayOf(0L, 60L, 130L, 220L, 340L, 500L, 700L)

        /** SystemUI 的包名：这些包名的窗口状态事件只当作「重新看一眼」的触发器 */
        private val SYSTEM_UI_PACKAGES = arrayOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.oplus.systemui",
            "com.coloros.systemui",
            "com.vivo.systemui",
        )

        const val ACTION_TOGGLE_PAUSE = "com.homebar.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.homebar.action.STOP"
    }
}
