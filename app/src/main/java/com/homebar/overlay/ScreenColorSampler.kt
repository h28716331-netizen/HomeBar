package com.homebar.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 背景取色器。
 *
 * 原理：用无障碍的 takeScreenshot（Android 11+）拿一帧屏幕，只采样指示条
 * 周围那几条窄带（避开指示条自身像素），算平均相对亮度，决定条该用黑还是白。
 *
 * ── v1.1.0 性能修正 ────────────────────────────────────────────
 * 旧实现对每个采样点都单独调一次 Bitmap.getPixels(…,1,1)，一次采样要发起
 * 上万次 JNI 调用，白白吃掉大量 CPU。现在改成【每个采样带整块读一次】，
 * 再把降采样放在内存数组里做，JNI 调用数从 ~10⁴ 降到 2。
 *
 * 另外默认采样间隔从 700ms 放宽到 1200ms，并且只在指示条可见时采样。
 * 取色本来就是「跟手即可」的功能，没必要为了 200ms 的响应差去烧电。
 */
class ScreenColorSampler(private val service: AccessibilityService) {

    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "homebar-color-sampler").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** 上一次成功取到的亮度，失败时沿用，避免闪烁 */
    var lastLuminance: Float = 0.5f
        private set

    /** 连续失败次数，用于退避 */
    private var consecutiveFailures = 0

    @Volatile
    private var minIntervalMs = 1200L

    private var lastRequestAt = 0L

    /** 复用同一块像素缓冲，避免每次采样都分配几万个 Int */
    private var pixelBuffer: IntArray = IntArray(0)

    fun setMinInterval(ms: Long) {
        // 系统对 takeScreenshot 本身有 333ms 的频率限制，低于它只是白失败
        minIntervalMs = ms.coerceAtLeast(500L)
    }

    /**
     * 异步采样一次。结果通过 [onResult] 回调到主线程。
     * 采样区域用屏幕像素坐标描述。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun sample(screenW: Int, screenH: Int, barRect: RectF, onResult: (Float) -> Unit) {
        val now = System.currentTimeMillis()
        if (busy.get()) return
        if (now - lastRequestAt < minIntervalMs) return
        if (!busy.compareAndSet(false, true)) return
        lastRequestAt = now

        val regions = buildSampleRegions(screenW, screenH, barRect)
        if (regions.isEmpty()) {
            busy.set(false)
            return
        }

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val luminance = runCatching {
                            computeLuminance(screenshot, screenW, screenH, regions)
                        }.getOrElse {
                            Log.w(TAG, "compute luminance failed", it)
                            -1f
                        }
                        busy.set(false)
                        if (luminance >= 0f) {
                            consecutiveFailures = 0
                            lastLuminance = luminance
                            mainHandler.post { onResult(luminance) }
                        } else {
                            backoff()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        busy.set(false)
                        backoff()
                        Log.d(TAG, "takeScreenshot failed, code=$errorCode")
                    }
                },
            )
        } catch (t: Throwable) {
            busy.set(false)
            backoff()
            Log.w(TAG, "takeScreenshot threw", t)
        }
    }

    private fun backoff() {
        consecutiveFailures++
        if (consecutiveFailures >= 2) {
            // 指数退避，最多 8 秒一次，避免在受限设备上死循环
            val backoffMs = (minIntervalMs * (1L shl consecutiveFailures.coerceAtMost(4)))
                .coerceAtMost(8_000L)
            lastRequestAt = System.currentTimeMillis() + backoffMs - minIntervalMs
        }
    }

    fun reset() {
        consecutiveFailures = 0
        busy.set(false)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun computeLuminance(
        screenshot: AccessibilityService.ScreenshotResult,
        screenW: Int,
        screenH: Int,
        regions: List<Rect>,
    ): Float {
        val hardwareBuffer = screenshot.hardwareBuffer
        val wrapped = try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        } catch (t: Throwable) {
            hardwareBuffer.close()
            throw t
        }
        if (wrapped == null) {
            hardwareBuffer.close()
            return -1f
        }

        val readable = try {
            // 硬件位图不能直接 getPixels，必须先 copy 到 ARGB_8888。
            // 这一步在采样线程上做，不占主线程。
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardwareBuffer.close()
            wrapped.recycle()
        }
        if (readable == null) return -1f

        try {
            val scaleX = readable.width.toFloat() / screenW.toFloat()
            val scaleY = readable.height.toFloat() / screenH.toFloat()

            var sum = 0.0
            var count = 0

            for (region in regions) {
                val left = (region.left * scaleX).toInt().coerceIn(0, readable.width - 1)
                val right = (region.right * scaleX).toInt().coerceIn(left + 1, readable.width)
                val top = (region.top * scaleY).toInt().coerceIn(0, readable.height - 1)
                val bottom = (region.bottom * scaleY).toInt().coerceIn(top + 1, readable.height)

                val w = right - left
                val h = bottom - top
                val size = w * h
                if (size <= 0) continue

                // 整块一次性读出来，只发起一次 JNI 调用
                if (pixelBuffer.size < size) pixelBuffer = IntArray(size)
                val buf = pixelBuffer
                val readOk = runCatching {
                    readable.getPixels(buf, 0, w, left, top, w, h)
                }.onFailure { Log.w(TAG, "getPixels failed", it) }.isSuccess
                if (!readOk) continue

                // 在内存里降采样，每 4 个像素取一个，够用且几乎零成本
                var i = 0
                while (i < size) {
                    sum += relativeLuminance(buf[i])
                    count++
                    i += 4
                }
            }
            return if (count == 0) -1f else (sum / count).toFloat()
        } finally {
            readable.recycle()
        }
    }

    /**
     * WCAG 相对亮度（线性化后的），比直接拿 RGB 平均值更符合人眼观感，
     * 判断「该配黑条还是白条」更准。
     */
    private fun relativeLuminance(color: Int): Double {
        val r = linearize(android.graphics.Color.red(color) / 255.0)
        val g = linearize(android.graphics.Color.green(color) / 255.0)
        val b = linearize(android.graphics.Color.blue(color) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    /**
     * 采样带：指示条上方一条（主），下方一条（辅）。
     * 刻意避开指示条自身的像素，否则「自己照自己」会导致颜色来回跳。
     */
    private fun buildSampleRegions(screenW: Int, screenH: Int, barRect: RectF): List<Rect> {
        val density = service.resources.displayMetrics.density
        val margin = (70 * density).toInt()
        val out = ArrayList<Rect>(2)

        val left = (barRect.left - margin).toInt().coerceIn(0, screenW)
        val right = (barRect.right + margin).toInt().coerceIn(0, screenW)

        // 上方 26dp ~ 5dp 之间
        val top1 = (barRect.top - 26 * density).toInt().coerceIn(0, screenH)
        val bottom1 = (barRect.top - 5 * density).toInt().coerceIn(0, screenH)
        if (bottom1 > top1) out.add(Rect(left, top1, right, bottom1))

        // 下方 4dp ~ 14dp 之间（贴到屏幕底就截断）
        val top2 = (barRect.bottom + 4 * density).toInt().coerceIn(0, screenH)
        val bottom2 = (barRect.bottom + 14 * density).toInt().coerceIn(0, screenH)
        if (bottom2 > top2) out.add(Rect(left, top2, right, bottom2))

        return out
    }

    fun shutdown() {
        runCatching { (executor as? java.util.concurrent.ExecutorService)?.shutdownNow() }
    }

    companion object {
        private const val TAG = "HomeBar/Sampler"
    }
}
