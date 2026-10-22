package com.homebar.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.homebar.core.BarConfig
import com.homebar.core.BarPrefs
import com.homebar.core.ColorMode
import com.homebar.core.Easing
import com.homebar.core.EasingPreset
import com.homebar.core.TouchDiagnostics
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeBarApp() {
    val context = LocalContext.current
    val prefs = remember { BarPrefs(context) }

    var cfg by remember { mutableStateOf(prefs.load()) }
    var serviceEnabled by remember { mutableStateOf(BarPrefs.isServiceEnabled(context)) }
    var paused by remember { mutableStateOf(prefs.isPaused()) }

    // 触摸信号自检需要周期性刷新（信号本身由服务写进 TouchDiagnostics）
    var diagTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            diagTick++
            val p = prefs.isPaused()
            if (p != paused) paused = p
        }
    }

    // 从系统设置页返回时刷新开关状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = BarPrefs.isServiceEnabled(context)
                // 服务可能在别处被改过参数，回到前台时同步一次
                cfg = prefs.load()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun live(new: BarConfig) {
        cfg = new
    }

    fun commit() {
        prefs.save(cfg)
    }

    fun set(new: BarConfig) {
        cfg = new
        prefs.save(new)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("小白条") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
        ) {
            // ── 状态 ──
            item {
                SectionCard(
                    title = "服务状态",
                    subtitle = if (serviceEnabled) {
                        "已开启，指示条正在屏幕底部显示"
                    } else {
                        "需要开启无障碍服务后才能绘制指示条"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (serviceEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (serviceEnabled) "运行中" else "未开启",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (serviceEnabled) "去关闭 / 调整无障碍服务" else "去开启无障碍服务")
                    }
                    HintText(
                        "在列表里找到「小白条 · 手势指示条」并打开即可。" +
                            "本服务不申请触摸探索、不声明任何触摸事件来源，悬浮层永久不可触摸，" +
                            "因此不会拦截任何手势。"
                    )
                }
            }

            // ── 安全与急救 ──
            item {
                val now = System.currentTimeMillis()
                val evAgo = if (TouchDiagnostics.lastEventTouchAt == 0L) -1L
                else now - TouchDiagnostics.lastEventTouchAt
                val probeAgo = if (TouchDiagnostics.lastProbeTouchAt == 0L) -1L
                else now - TouchDiagnostics.lastProbeTouchAt

                SectionCard(
                    title = "安全与急救",
                    subtitle = "先看这里：随时能把小白条收起来，也能确认触摸感知是不是正常",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                prefs.setPaused(true)
                                paused = true
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !paused,
                        ) { Text("立即暂停") }
                        OutlinedButton(
                            onClick = {
                                prefs.setPaused(false)
                                paused = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = paused,
                        ) { Text("恢复运行") }
                    }
                    HintText(
                        "「立即暂停」会立刻撤掉指示条与触摸探测点，不用去系统设置里关无障碍服务。" +
                            "通知栏里也常驻着同样的「暂停 / 彻底关闭」按钮 —— 屏幕不好操作时用那个更稳。"
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "触摸信号自检",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    SignalLine(
                        label = "触摸探测点（主通道）",
                        ok = TouchDiagnostics.probeConfirmed,
                        detail = when {
                            !cfg.preciseTouchProbe -> "已在下面关闭"
                            !TouchDiagnostics.probeAttached -> "挂载失败，触摸感知不可用"
                            probeAgo < 0L -> "已挂载，去屏幕上点一下试试"
                            else -> "${probeAgo / 1000f} 秒前收到过触摸"
                        },
                    )
                    SignalLine(
                        label = "触摸坐标（系统是否公开）",
                        ok = TouchDiagnostics.lastRealCoordsAt > 0L,
                        detail = when {
                            !TouchDiagnostics.probeConfirmed -> "还没有触摸样本"
                            TouchDiagnostics.lastRealCoordsAt == 0L ->
                                "系统已隐藏 —— 这是跨应用触摸的正常行为，不是故障"
                            else -> "拿到过真实坐标 " +
                                "(${TouchDiagnostics.lastProbeX.roundToInt()}, " +
                                "${TouchDiagnostics.lastProbeY.roundToInt()})"
                        },
                    )
                    SignalLine(
                        label = "无障碍触摸事件（备用通道）",
                        ok = TouchDiagnostics.eventsConfirmed,
                        detail = when {
                            !cfg.touchSenseEnabled -> "已在下面关闭"
                            evAgo < 0L -> "系统未开启触摸探索，正常情况下收不到"
                            else -> "${evAgo / 1000f} 秒前收到过"
                        },
                    )
                    HintText(
                        "主通道不通时，指示条会退化成「常显 + 不弹跳」，功能少一点，" +
                            "但绝不会影响手机正常使用。"
                    )
                }
            }

            // ── 触摸感知 ──
            item {
                SectionCard(
                    title = "触摸感知",
                    subtitle = "用于「碰屏幕就出现、无操作就隐藏」。这条路径在结构上不可能拦截输入",
                ) {
                    SwitchRow(
                        title = "触摸探测点（1×1 像素）",
                        subtitle = "窗口只有 1 个像素大，压在导航栏之上，所以任何位置的触摸都能感知到；" +
                            "最坏情况也只影响屏幕上 1 个像素",
                        checked = cfg.preciseTouchProbe,
                        onCheckedChange = { set(cfg.copy(preciseTouchProbe = it)) },
                    )
                    SwitchRow(
                        title = "订阅无障碍触摸事件（备用）",
                        subtitle = "系统开启触摸探索时才会派发，本应用不开启触摸探索，所以一般收不到；" +
                            "留着只是个别 ROM 的兜底",
                        checked = cfg.touchSenseEnabled,
                        onCheckedChange = { set(cfg.copy(touchSenseEnabled = it)) },
                    )
                    HintText(
                        "为什么不用系统给无障碍的「原始触摸观察」接口？" +
                            "因为它会把触摸事件从输入链路里摘走、不再送给任何应用，" +
                            "等于把整块屏幕的触摸关掉。这一版已彻底移除该调用。"
                    )
                }
            }

            // ── 预览 ──
            item {
                SectionCard(title = "实时预览", subtitle = "参数改完立刻能在这里看到效果") {
                    BarPreview(config = cfg)
                }
            }

            // ── 尺寸与位置 ──
            item {
                SectionCard(title = "尺寸与位置") {
                    SliderRow(
                        label = "宽度",
                        value = cfg.barWidthDp,
                        valueRange = 60f..320f,
                        valueFormatter = { "${it.roundToInt()} dp" },
                        onChange = { live(cfg.copy(barWidthDp = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "高度（粗细）",
                        value = cfg.barHeightDp,
                        valueRange = 2f..16f,
                        valueFormatter = { String.format("%.1f dp", it) },
                        onChange = { live(cfg.copy(barHeightDp = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "圆角",
                        value = cfg.cornerRadiusDp,
                        valueRange = 0f..8f,
                        valueFormatter = { String.format("%.1f dp", it) },
                        onChange = { live(cfg.copy(cornerRadiusDp = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "距屏幕底部",
                        value = cfg.bottomMarginDp,
                        valueRange = 0f..60f,
                        valueFormatter = { "${it.roundToInt()} dp" },
                        onChange = { live(cfg.copy(bottomMarginDp = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "水平位置（负左正右）",
                        value = cfg.horizontalOffsetDp,
                        valueRange = -200f..200f,
                        valueFormatter = { "${it.roundToInt()} dp" },
                        onChange = { live(cfg.copy(horizontalOffsetDp = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "不透明度",
                        value = cfg.opacity,
                        valueRange = 0.2f..1f,
                        valueFormatter = { "${(it * 100).roundToInt()}%" },
                        onChange = { live(cfg.copy(opacity = it)) },
                        onCommit = { commit() },
                    )
                    SwitchRow(
                        title = "柔光描边",
                        subtitle = "在花纹/图片背景上更清晰，也会略微增加一点绘制开销",
                        checked = cfg.glowEnabled,
                        onCheckedChange = { set(cfg.copy(glowEnabled = it)) },
                    )
                }
            }

            // ── 颜色 ──
            item {
                SectionCard(
                    title = "颜色",
                    subtitle = "默认跟随背景明暗自动切换黑白，和 iOS 18 一致",
                ) {
                    PickerRow(
                        label = "取色方式",
                        options = ColorMode.entries.toList(),
                        selected = cfg.colorMode,
                        labelOf = { it.label },
                        onSelect = { set(cfg.copy(colorMode = it)) },
                    )
                    if (cfg.colorMode == ColorMode.AUTO) {
                        Spacer(Modifier.height(8.dp))
                        SliderRow(
                            label = "亮度阈值",
                            value = cfg.luminanceThreshold,
                            valueRange = 0.2f..0.85f,
                            valueFormatter = { String.format("%.2f", it) },
                            onChange = { live(cfg.copy(luminanceThreshold = it)) },
                            onCommit = { commit() },
                        )
                        SliderRow(
                            label = "采样间隔",
                            value = cfg.sampleIntervalMs.toFloat(),
                            valueRange = 400f..3000f,
                            valueFormatter = { "${(it / 100f).roundToInt() / 10f} s" },
                            onChange = { live(cfg.copy(sampleIntervalMs = it.toLong())) },
                            onCommit = { commit() },
                        )
                        SliderRow(
                            label = "防抖迟滞",
                            value = cfg.hysteresis,
                            valueRange = 0f..0.2f,
                            valueFormatter = { String.format("%.2f", it) },
                            onChange = { live(cfg.copy(hysteresis = it)) },
                            onCommit = { commit() },
                        )
                        SliderRow(
                            label = "黑白渐变时长",
                            value = cfg.colorFadeMs.toFloat(),
                            valueRange = 0f..800f,
                            valueFormatter = { "${it.roundToInt()} ms" },
                            onChange = { live(cfg.copy(colorFadeMs = it.toLong())) },
                            onCommit = { commit() },
                        )
                        HintText(
                            "取色靠无障碍截图实现，间隔越小越跟手，但耗电也越高。" +
                                "系统本身对截图有频率限制，低于 400ms 不会更快。"
                        )
                    }
                }
            }

            // ── 沉浸与隐藏 ──
            item {
                SectionCard(
                    title = "沉浸与隐藏",
                    subtitle = "在系统界面上收起、进入应用时淡入，以及无操作后自动隐藏",
                ) {
                    SwitchRow(
                        title = "桌面与最近任务",
                        subtitle = "在桌面、最近任务（多任务）界面上不显示，打开任意应用后自动淡入。" +
                            "靠无障碍窗口事件判断前台应用，不读取窗口内容、不拦截触摸",
                        checked = cfg.hideOnHome,
                        onCheckedChange = { set(cfg.copy(hideOnHome = it)) },
                    )
                    SwitchRow(
                        title = "通知中心与控制中心",
                        subtitle = "下拉通知栏、展开控制中心时收起，面板收回去后自动淡入。" +
                            "靠无障碍窗口列表判断，只看窗口大小与类型，不读内容",
                        checked = cfg.hideOnShade,
                        onCheckedChange = { set(cfg.copy(hideOnShade = it)) },
                    )
                    SwitchRow(
                        title = "锁屏",
                        subtitle = "熄屏、锁屏界面、锁屏上的通知面板都不显示，解锁后自动淡入。" +
                            "靠 KeyguardManager 判断，不需要任何权限，也读不到任何内容",
                        checked = cfg.hideOnLockScreen,
                        onCheckedChange = { set(cfg.copy(hideOnLockScreen = it)) },
                    )
                    if (cfg.hideOnHome || cfg.hideOnShade || cfg.hideOnLockScreen) {
                        HintText(
                            "三条判定都不申请「使用情况访问」权限，也不读取窗口内容：\n" +
                                "· 桌面 / 最近任务：看 TYPE_WINDOW_STATE_CHANGED 事件里自带的包名\n" +
                                "· 通知中心 / 控制中心：看窗口列表里有没有铺满屏幕、或者拿到了输入焦点的" +
                                "系统面板窗口（状态栏、导航栏都不可聚焦，不会误判），" +
                                "同时每 250ms 主动复查一次，避免个别 ROM 不派发窗口事件\n" +
                                "· 锁屏：熄屏时直接视为锁定，亮屏时问一次 KeyguardManager，" +
                                "收到「用户已解锁」广播才恢复 —— 亮屏不等于解锁，" +
                                "很多 ROM 亮屏后停在锁屏界面\n" +
                                "个别 ROM 若限制包可见性，桌面判定会自动失效 —— " +
                                "宁可不隐藏，也不会在应用里乱隐藏。"
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SwitchRow(
                        title = "上滑退出 / 回桌面时不唤出",
                        subtitle = "从底部上滑回桌面、上滑退出应用的手势，不会把条带出来",
                        checked = cfg.gestureGuardEnabled,
                        onCheckedChange = { set(cfg.copy(gestureGuardEnabled = it)) },
                    )
                    if (cfg.gestureGuardEnabled) {
                        SliderRow(
                            label = "唤醒延迟",
                            value = cfg.wakeDelayMs.toFloat(),
                            valueRange = 0f..700f,
                            valueFormatter = {
                                if (it <= 0f) "关闭（立即显示）" else "${it.roundToInt()} ms"
                            },
                            onChange = { live(cfg.copy(wakeDelayMs = it.toLong())) },
                            onCommit = { commit() },
                        )
                        HintText(
                            "上滑手势和普通触摸在按下的那一刻是分不出来的 —— " +
                                "跨应用拿不到坐标，只能等「松手之后前台变成了谁」。\n" +
                                "所以做法是：触摸后先等这么久，期间一旦判定为回到桌面，" +
                                "就把这次唤醒整个取消；如果条已经淡入了一点点，直接归零而不是淡出。\n" +
                                "延迟越大越干净，但碰屏幕后条出现得越慢。手势动画大约 300ms，默认 300ms。"
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SwitchRow(
                        title = "无操作自动隐藏",
                        checked = cfg.autoHideEnabled,
                        onCheckedChange = { set(cfg.copy(autoHideEnabled = it)) },
                    )
                    if (cfg.autoHideEnabled) {
                        SliderRow(
                            label = "无操作多久后隐藏",
                            value = cfg.idleTimeoutMs.toFloat(),
                            valueRange = 500f..15000f,
                            valueFormatter = {
                                if (it >= 1000f) "${(it / 100f).roundToInt() / 10f} s"
                                else "${it.roundToInt()} ms"
                            },
                            onChange = { live(cfg.copy(idleTimeoutMs = it.toLong())) },
                            onCommit = { commit() },
                        )
                        HintText(
                            "滑动屏幕时计时会持续重置，所以浏览内容期间条会一直待着；" +
                                "停手后才开始倒计时。"
                        )
                    }
                }
            }

            // ── 出现 / 消失动画 ──
            item {
                SectionCard(
                    title = "出现 / 消失动画",
                    subtitle = "把原来生硬的瞬现瞬隐换成带缓动的淡入淡出",
                ) {
                    SliderRow(
                        label = "出现时长",
                        value = cfg.showDurationMs.toFloat(),
                        valueRange = 60f..900f,
                        valueFormatter = { "${it.roundToInt()} ms" },
                        onChange = { live(cfg.copy(showDurationMs = it.toLong())) },
                        onCommit = { commit() },
                    )
                    PickerRow(
                        label = "出现曲线",
                        options = EasingPreset.entries.toList(),
                        selected = cfg.showEasing,
                        labelOf = { it.label },
                        onSelect = { set(cfg.copy(showEasing = it)) },
                    )
                    if (cfg.showEasing == EasingPreset.SPRING) {
                        SliderRow(
                            label = "弹簧阻尼（越小越 Q 弹）",
                            value = cfg.springDamping,
                            valueRange = 0.15f..1f,
                            valueFormatter = { String.format("%.2f", it) },
                            onChange = { live(cfg.copy(springDamping = it)) },
                            onCommit = { commit() },
                        )
                    }
                    EasingCurveGraph(
                        interpolator = Easing.forShow(cfg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(14.dp))

                    SliderRow(
                        label = "消失时长",
                        value = cfg.hideDurationMs.toFloat(),
                        valueRange = 60f..900f,
                        valueFormatter = { "${it.roundToInt()} ms" },
                        onChange = { live(cfg.copy(hideDurationMs = it.toLong())) },
                        onCommit = { commit() },
                    )
                    PickerRow(
                        label = "消失曲线",
                        options = EasingPreset.entries.toList(),
                        selected = cfg.hideEasing,
                        labelOf = { it.label },
                        onSelect = { set(cfg.copy(hideEasing = it)) },
                    )
                    EasingCurveGraph(
                        interpolator = Easing.forHide(cfg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(14.dp))

                    SwitchRow(
                        title = "缩放过渡",
                        subtitle = "淡入淡出时同时做一点点缩放，观感更柔和",
                        checked = cfg.animateScale,
                        onCheckedChange = { set(cfg.copy(animateScale = it)) },
                    )
                    SwitchRow(
                        title = "位移过渡",
                        subtitle = "从下方轻轻滑入 / 滑出",
                        checked = cfg.animateSlide,
                        onCheckedChange = { set(cfg.copy(animateSlide = it)) },
                    )
                    if (cfg.animateSlide) {
                        SliderRow(
                            label = "滑入距离",
                            value = cfg.slideDistanceDp,
                            valueRange = 0f..24f,
                            valueFormatter = { String.format("%.1f dp", it) },
                            onChange = { live(cfg.copy(slideDistanceDp = it)) },
                            onCommit = { commit() },
                        )
                    }
                }
            }

            // ── 弹跳 ──
            item {
                SectionCard(
                    title = "触摸弹跳",
                    subtitle = "点一下小白条，它向上轻轻弹一下再落回原位",
                ) {
                    SwitchRow(
                        title = "启用弹跳",
                        checked = cfg.bounceEnabled,
                        onCheckedChange = { set(cfg.copy(bounceEnabled = it)) },
                    )
                    if (cfg.bounceEnabled) {
                        SwitchRow(
                            title = "只在点击小白条时弹跳",
                            subtitle = "点条本身才弹一下；打字、滑列表、点按钮都不会再乱弹",
                            checked = cfg.bounceOnBarTap,
                            onCheckedChange = { set(cfg.copy(bounceOnBarTap = it)) },
                        )
                        HintText(
                            if (cfg.bounceOnBarTap) {
                                "开启时，条上会盖一层和条一样大的透明感应窗口（横向外扩 10dp、" +
                                    "纵向外扩 7dp），只有点在这里才弹。条以外的区域完全不受影响，" +
                                    "条淡出时会立刻把这层感应撤掉，不会在底部留一块点不动的死区。\n" +
                                    "代价：点在条上那一下不会传给下面的应用 —— 这一小块本来就是指示条，" +
                                    "iOS 上点它也是没有别的响应的。\n" +
                                    "如果个别机型上发现底部上滑手势变迟钝，关掉这个开关即可回到" +
                                    "「完全不碰触摸」的结构。"
                            } else {
                                "关闭后回到旧行为：结构上完全不可触摸（最安全），" +
                                    "但屏幕上任何一次触摸都会弹。\n" +
                                    "原因是 Android 在输入分发层对跨应用触摸做了隐私保护：" +
                                    "AOSP 里如果监听触摸的窗口和当前触摸的窗口不属于同一个 UID，" +
                                    "就会打上 ZERO_COORDS 把坐标强制清零。所以除了条自己的界面，" +
                                    "我们只能知道「有人碰了屏幕」，不可能知道碰在哪。"
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        SliderRow(
                            label = "弹跳幅度",
                            value = cfg.bounceAmplitudeDp,
                            valueRange = 1f..32f,
                            valueFormatter = { String.format("%.1f dp", it) },
                            onChange = { live(cfg.copy(bounceAmplitudeDp = it)) },
                            onCommit = { commit() },
                        )
                        SliderRow(
                            label = "弹跳时长（速度）",
                            value = cfg.bounceDurationMs.toFloat(),
                            valueRange = 90f..1200f,
                            valueFormatter = { "${it.roundToInt()} ms" },
                            onChange = { live(cfg.copy(bounceDurationMs = it.toLong())) },
                            onCommit = { commit() },
                        )
                        PickerRow(
                            label = "弹跳速度曲线",
                            options = EasingPreset.entries.toList(),
                            selected = cfg.bounceEasing,
                            labelOf = { it.label },
                            onSelect = { set(cfg.copy(bounceEasing = it)) },
                        )
                        if (cfg.bounceEasing == EasingPreset.SPRING) {
                            SliderRow(
                                label = "弹簧阻尼（越小越 Q 弹）",
                                value = cfg.bounceSpringDamping,
                                valueRange = 0.15f..1f,
                                valueFormatter = { String.format("%.2f", it) },
                                onChange = { live(cfg.copy(bounceSpringDamping = it)) },
                                onCommit = { commit() },
                            )
                        }
                        EasingCurveGraph(
                            interpolator = Easing.forBounce(cfg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── 自定义贝塞尔 ──
            item {
                SectionCard(
                    title = "自定义贝塞尔曲线",
                    subtitle = "把上面任意一条曲线选成「自定义贝塞尔曲线」后，这里才真正生效",
                ) {
                    SliderRow(
                        label = "P1 横向 (x1)",
                        value = cfg.bezierX1,
                        valueRange = 0f..1f,
                        valueFormatter = { String.format("%.2f", it) },
                        onChange = { live(cfg.copy(bezierX1 = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "P1 纵向 (y1，>1 会冲过头)",
                        value = cfg.bezierY1,
                        valueRange = -0.5f..2f,
                        valueFormatter = { String.format("%.2f", it) },
                        onChange = { live(cfg.copy(bezierY1 = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "P2 横向 (x2)",
                        value = cfg.bezierX2,
                        valueRange = 0f..1f,
                        valueFormatter = { String.format("%.2f", it) },
                        onChange = { live(cfg.copy(bezierX2 = it)) },
                        onCommit = { commit() },
                    )
                    SliderRow(
                        label = "P2 纵向 (y2)",
                        value = cfg.bezierY2,
                        valueRange = -0.5f..2f,
                        valueFormatter = { String.format("%.2f", it) },
                        onChange = { live(cfg.copy(bezierY2 = it)) },
                        onCommit = { commit() },
                    )
                    EasingCurveGraph(
                        interpolator = Easing.of(
                            EasingPreset.CUSTOM_BEZIER,
                            cfg.springDamping,
                            cfg.bezierX1, cfg.bezierY1, cfg.bezierX2, cfg.bezierY2,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(top = 4.dp),
                    )
                }
            }

            // ── 预设 ──
            item {
                SectionCard(title = "一键预设") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { set(BarConfig.IOS_STYLE) },
                            modifier = Modifier.weight(1f),
                        ) { Text("iOS 风格") }
                        Button(
                            onClick = { set(BarConfig.PIXEL_STYLE) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Pixel 风格") }
                        Button(
                            onClick = { set(BarConfig.BOLD_STYLE) },
                            modifier = Modifier.weight(1f),
                        ) { Text("醒目风格") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { set(BarConfig.DEFAULT) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("恢复默认") }
                }
            }

            // ── 说明 ──
            item {
                SectionCard(
                    title = "关于 · 与 EdgeGesture 的区别",
                    subtitle = "为什么这个版本不会改掉系统的动画，也不会吃掉你的触摸",
                ) {
                    HintText(
                        "1. 悬浮层用 TYPE_ACCESSIBILITY_OVERLAY 并永久带 FLAG_NOT_TOUCHABLE。" +
                            "不可触摸的窗口会被输入系统直接跳过，返回手势、上滑回桌面、预测性返回" +
                            "动画全部由 SystemUI / Launcher 原样处理。\n\n" +
                            "2. 从不调用 setGestureDetectionPassthroughRegion、不注入手势、" +
                            "不改动画缩放、不隐藏或替换系统导航栏。\n\n" +
                            "3. 从不调用 setMotionEventSources。" +
                            "该接口会把指定来源的触摸事件从输入链路里摘走、不再送给任何应用，" +
                            "等于关掉整块屏幕的触摸 —— 1.0.0 版本就是因为误用它导致手势失灵，" +
                            "1.1.0 已彻底删除。\n\n" +
                            "4. 触摸感知靠一个 1×1 像素的探测窗口（TYPE_ACCESSIBILITY_OVERLAY，" +
                            "层级 31，压在导航栏 24 层之上），用 FLAG_WATCH_OUTSIDE_TOUCH 顺带" +
                            "收到触摸通知，事件本身照常送给下面的应用，最多只影响 1 个像素。\n\n" +
                            "5. 系统自带的导航条仍然存在。若想只看到这一根，可在" +
                            "「设置 → 系统 → 手势」里关掉系统的手势提示条" +
                            "（部分机型在开发者选项里）。"
                    )
                }
            }

            // ── 万一出事怎么办 ──
            item {
                SectionCard(
                    title = "万一手机不好操作了",
                    subtitle = "按顺序试，任一条生效即可（这些操作都不依赖小白条本身）",
                ) {
                    HintText(
                        "① 通知栏里找到「小白条运行中」，点「暂停」或「彻底关闭」。\n\n" +
                            "② 长按电源键，长按「关机」进入安全模式重启 —— " +
                            "安全模式会临时停用所有第三方无障碍服务，重启后正常开机即可。\n\n" +
                            "③ 如果设过无障碍快捷键：同时按住「音量上 + 音量下」3 秒可开关无障碍服务。\n\n" +
                            "④ 电脑上用 adb（最彻底）：\n" +
                            "adb shell settings put secure enabled_accessibility_services \"\"\n" +
                            "adb shell settings put secure accessibility_enabled 0\n\n" +
                            "⑤ 直接卸载：adb uninstall com.homebar.debug"
                    )
                }
            }
        }
    }
}

/** 自检用的一行：圆点 + 名称 + 当前状态说明 */
@Composable
private fun SignalLine(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
