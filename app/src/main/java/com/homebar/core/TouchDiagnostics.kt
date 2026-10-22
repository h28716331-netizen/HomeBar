package com.homebar.core

/**
 * 触摸信号的「体检报告」。
 *
 * 服务与界面在同一个进程里，所以用一个最朴素的单例传递状态就够了。
 * 设置页会实时显示各条信号是否活着，方便判断当前设备到底走通了哪条路径。
 *
 * 注意：这里【只记录】，不参与任何输入决策，也不可能拦截事件。
 */
object TouchDiagnostics {

    /** 探测点最近一次收到 ACTION_OUTSIDE 的时间戳（0 = 从未到达） */
    @Volatile
    var lastProbeTouchAt: Long = 0L
        private set

    /** 探测点最近一次拿到【真实坐标】的时间戳（0 = 从未拿到） */
    @Volatile
    var lastRealCoordsAt: Long = 0L
        private set

    /** 最近一次是否拿到的坐标被系统隐藏了 */
    @Volatile
    var coordsHidden: Boolean = false
        private set

    /** 探测点最近一次拿到的真实坐标 */
    @Volatile
    var lastProbeX: Float = 0f
        private set

    @Volatile
    var lastProbeY: Float = 0f
        private set

    /** 无障碍「触摸交互开始」事件最近一次到达的时间戳（0 = 从未到达） */
    @Volatile
    var lastEventTouchAt: Long = 0L
        private set

    /** 探测窗口是否成功挂上（挂上 ≠ 能收到事件） */
    @Volatile
    var probeAttached: Boolean = false

    /** 触摸存在性是否已被证实可用（收到过至少一次 ACTION_OUTSIDE） */
    val probeConfirmed: Boolean get() = lastProbeTouchAt > 0L

    /** 是否已经确认「坐标被系统隐藏」是常态（跨应用触摸都是这样） */
    val coordsAlwaysHidden: Boolean
        get() = probeConfirmed && lastRealCoordsAt == 0L

    /** 无障碍备用通道是否已被证实可用 */
    val eventsConfirmed: Boolean get() = lastEventTouchAt > 0L

    fun onProbeTouch(coordsHidden: Boolean, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        lastProbeTouchAt = now
        this.coordsHidden = coordsHidden
        if (!coordsHidden) {
            lastRealCoordsAt = now
            lastProbeX = x
            lastProbeY = y
        }
    }

    fun onEventTouch() {
        lastEventTouchAt = System.currentTimeMillis()
    }

    fun reset() {
        lastProbeTouchAt = 0L
        lastRealCoordsAt = 0L
        lastEventTouchAt = 0L
        lastProbeX = 0f
        lastProbeY = 0f
        coordsHidden = false
        probeAttached = false
    }
}
