package com.homebar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.homebar.core.BarConfig
import com.homebar.core.ColorMode
import com.homebar.core.Easing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 应用内实时预览。
 *
 * 这里刻意用和悬浮窗完全相同的参数与曲线来驱动，所见即所得，
 * 调完不用来回切到桌面看效果。
 */
@Composable
fun BarPreview(config: BarConfig, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var darkBg by remember { mutableStateOf(false) }
    var showProgress by remember { mutableFloatStateOf(1f) }
    var bounceOffset by remember { mutableFloatStateOf(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }

    val stageBg = if (darkBg) Color(0xFF0F1115) else Color(0xFFF3F5F8)
    val fakeBlock = if (darkBg) Color(0xFF1C2027) else Color(0xFFE4E8EE)
    val fakeBlock2 = if (darkBg) Color(0xFF232833) else Color(0xFFDCE1E9)

    val barColor = when (config.colorMode) {
        ColorMode.WHITE -> Color.White
        ColorMode.BLACK -> Color.Black
        // 预览里直接模拟「浅背景配黑条、深背景配白条」
        ColorMode.AUTO -> if (darkBg) Color.White else Color.Black
    }

    fun startShow() {
        animJob?.cancel()
        animJob = scope.launch {
            driveProgress(
                from = showProgress, to = 1f,
                durationMs = config.showDurationMs,
                interpolator = Easing.forShow(config),
            ) { showProgress = it }
        }
    }

    fun startHide() {
        animJob?.cancel()
        animJob = scope.launch {
            driveProgress(
                from = showProgress, to = 0f,
                durationMs = config.hideDurationMs,
                interpolator = Easing.forHide(config),
            ) { showProgress = it }
        }
    }

    fun startBounce() {
        scope.launch {
            driveBounce(config, density) { bounceOffset = it }
        }
    }

    val alphaP = showProgress.coerceIn(0f, 1f)
    val sx = if (config.animateScale) lerp(0.70f, 1f, alphaP) else 1f
    val sy = if (config.animateScale) lerp(0.80f, 1f, alphaP) else 1f
    val slidePx = if (config.animateSlide) (1f - alphaP) * config.slideDistanceDp * density else 0f

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(onClick = { darkBg = !darkBg }) {
                Text(if (darkBg) "深色背景" else "浅色背景")
            }
            Text(
                text = "切换背景看看黑白是否跟着变",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── 预览舞台 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(stageBg),
        ) {
            // 一点假的界面内容，让背景不那么空
            Column(modifier = Modifier.padding(14.dp)) {
                Box(
                    Modifier
                        .width(120.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(fakeBlock2)
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fakeBlock)
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fakeBlock)
                )
            }

            // 小白条本体
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = config.bottomMarginDp.dp)
                    .offset(x = config.horizontalOffsetDp.dp)
                    .width(config.barWidthDp.dp)
                    .height(config.barHeightDp.dp)
                    .graphicsLayer {
                        translationY = slidePx - bounceOffset
                        scaleX = sx
                        scaleY = sy
                        alpha = alphaP * config.opacity
                    }
                    .background(
                        color = barColor,
                        shape = RoundedCornerShape(config.cornerRadiusDp.dp),
                    ),
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { startShow() }) { Text("出现") }
            FilledTonalButton(onClick = { startHide() }) { Text("隐藏") }
            FilledTonalButton(onClick = { startBounce() }) { Text("弹跳") }
            Button(
                onClick = {
                    animJob?.cancel()
                    animJob = scope.launch {
                        driveProgress(0f, 1f, config.showDurationMs, Easing.forShow(config)) {
                            showProgress = it
                        }
                        delay(config.idleTimeoutMs.coerceAtLeast(300L))
                        driveProgress(1f, 0f, config.hideDurationMs, Easing.forHide(config)) {
                            showProgress = it
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("演示沉浸")
            }
        }
    }
}

private suspend fun driveProgress(
    from: Float,
    to: Float,
    durationMs: Long,
    interpolator: android.view.animation.Interpolator,
    apply: (Float) -> Unit,
) {
    if (durationMs <= 0L || abs(to - from) < 0.001f) {
        apply(to)
        return
    }
    val start = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        val t = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
        apply(from + (to - from) * interpolator.getInterpolation(t))
        if (t >= 1f) break
    }
    apply(to)
}

private suspend fun driveBounce(config: BarConfig, density: Float, apply: (Float) -> Unit) {
    val amplitude = config.bounceAmplitudeDp * density
    val interpolator = Easing.forBounce(config)
    val duration = config.bounceDurationMs.coerceAtLeast(90L)
    val start = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        val t = ((now - start) / 1_000_000f / duration).coerceIn(0f, 1f)
        apply(amplitude * Easing.bounceShape(t, interpolator))
        if (t >= 1f) break
    }
    apply(0f)
}

private fun lerp(start: Float, stop: Float, f: Float) = start + (stop - start) * f
