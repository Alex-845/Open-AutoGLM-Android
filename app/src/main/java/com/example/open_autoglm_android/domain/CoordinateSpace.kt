package com.example.open_autoglm_android.domain

/**
 * Screen taps use a 0..1000 relative coordinate space on the screenshot.
 * The executor applies that scale. Callers reject values outside it instead of
 * guessing that they are pixels.
 */
internal object CoordinateSpace {
    const val MAX = 1000f
    const val OUT_OF_RANGE_PREFIX = "坐标超出 0-1000"
    const val REPEAT_PREFIX = "重复点击同一位置"
    const val REPEAT_TOLERANCE = 20
    private const val REPEAT_STREAK = 2

    data class ScreenPoint(val x: Float, val y: Float)

    sealed class Mapping {
        data class OnScreen(val point: ScreenPoint) : Mapping()
        data class OutOfRange(val x: Float, val y: Float) : Mapping()
    }

    fun mapToScreen(x: Float, y: Float, width: Int, height: Int): Mapping {
        if (width <= 0 || height <= 0 || x < 0f || y < 0f || x > MAX || y > MAX) {
            return Mapping.OutOfRange(x, y)
        }
        val px = (x / MAX * width).coerceIn(0f, (width - 1).toFloat())
        val py = (y / MAX * height).coerceIn(0f, (height - 1).toFloat())
        return Mapping.OnScreen(ScreenPoint(px, py))
    }

    fun outOfRangeMessage(x: Float, y: Float): String {
        return "$OUT_OF_RANGE_PREFIX：收到 [$x,$y]。请改用 0 到 1000 的相对坐标，(0,0) 是截图左上角，(1000,1000) 是右下角，不要输出像素。"
    }

    /**
     * True when this point continues a streak of already executed taps at the same place.
     * Two taps are allowed so a repeated digit can be entered. The third is blocked.
     */
    fun isRepeatedTap(executed: List<Pair<Int, Int>>, x: Int, y: Int, tolerance: Int = REPEAT_TOLERANCE): Boolean {
        if (executed.size < REPEAT_STREAK) return false
        return executed.takeLast(REPEAT_STREAK).all { (px, py) ->
            kotlin.math.abs(px - x) <= tolerance && kotlin.math.abs(py - y) <= tolerance
        }
    }

    fun repeatMessage(x: Int, y: Int): String {
        return "$REPEAT_PREFIX：[$x,$y] 已连续点击。请换一个位置或换一种操作。"
    }
}
