package com.example.open_autoglm_android.service

/**
 * Pure decisions for accessibility screenshot capture.
 *
 * Error codes match [android.accessibilityservice.AccessibilityService]:
 * 1 internal, 2 no screenshot capability, 3 interval too short,
 * 4 invalid display, 5 invalid window, 6 secure window.
 * Display and window type values match the platform constants used at the call site.
 */
internal object ScreenshotCapturePlan {
    const val TYPE_APPLICATION = 1
    const val DISPLAY_INVALID = -1
    const val DISPLAY_DEFAULT = 0
    const val MAX_ATTEMPTS = 3
    const val MIN_CAPTURE_INTERVAL_MS = 333L

    const val ERROR_INTERNAL = 1
    const val ERROR_NO_ACCESSIBILITY_ACCESS = 2
    const val ERROR_INTERVAL_TIME_SHORT = 3
    const val ERROR_INVALID_DISPLAY = 4
    const val ERROR_INVALID_WINDOW = 5
    const val ERROR_SECURE_WINDOW = 6

    data class WindowCandidate(
        val windowId: Int,
        val displayId: Int,
        val type: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsWidth: Int,
        val boundsHeight: Int,
        val packageName: String?
    ) {
        val boundsArea: Long get() = boundsWidth.toLong() * boundsHeight.toLong()
    }

    data class CaptureTarget(
        val windowId: Int,
        val displayId: Int,
        val packageName: String?,
        val boundsLeft: Int,
        val boundsTop: Int,
        val boundsWidth: Int,
        val boundsHeight: Int
    )

    data class PixelSize(val width: Int, val height: Int)

    sealed class BitmapPlacement {
        data object UseAsIs : BitmapPlacement()
        data class Offset(val left: Int, val top: Int) : BitmapPlacement()
    }

    fun selectCaptureTarget(windows: List<WindowCandidate>, ownPackage: String): CaptureTarget? {
        val usable = windows.filter { it.displayId != DISPLAY_INVALID }
        val applications = usable.filter { it.type == TYPE_APPLICATION }
        val foreignApps = applications.filter { pkg ->
            val name = pkg.packageName
            !name.isNullOrBlank() && name != ownPackage
        }
        val pool = when {
            foreignApps.isNotEmpty() -> foreignApps
            applications.isNotEmpty() -> applications
            else -> usable
        }
        val chosen = pool.firstOrNull { it.isActive }
            ?: pool.firstOrNull { it.isFocused }
            ?: pool.maxByOrNull { it.boundsArea }
            ?: return null
        return CaptureTarget(
            windowId = chosen.windowId,
            displayId = chosen.displayId,
            packageName = chosen.packageName,
            boundsLeft = chosen.boundsLeft,
            boundsTop = chosen.boundsTop,
            boundsWidth = chosen.boundsWidth,
            boundsHeight = chosen.boundsHeight
        )
    }

    fun shouldRetryPlatform(errorCode: Int, attempt: Int): Boolean {
        if (attempt >= MAX_ATTEMPTS - 1) return false
        return when (errorCode) {
            ERROR_INTERNAL,
            ERROR_INTERVAL_TIME_SHORT,
            ERROR_INVALID_DISPLAY,
            ERROR_INVALID_WINDOW -> true
            else -> false
        }
    }

    fun shouldRetryDecode(attempt: Int): Boolean = attempt < MAX_ATTEMPTS - 1

    fun shouldTryWindowFallback(errorCode: Int, windowId: Int): Boolean {
        if (windowId < 0) return false
        return when (errorCode) {
            ERROR_INTERNAL,
            ERROR_INTERVAL_TIME_SHORT,
            ERROR_INVALID_DISPLAY -> true
            else -> false
        }
    }

    fun shouldDetachOverlayOnRetry(errorCode: Int?): Boolean {
        return errorCode == ERROR_INTERNAL || errorCode == ERROR_INVALID_DISPLAY
    }

    fun retryDelayMs(errorCode: Int?, attempt: Int): Long {
        // Every takeScreenshot call, including a failed one, stamps the platform interval.
        val intervalPad = if (errorCode == ERROR_INTERVAL_TIME_SHORT) 40L else 0L
        return MIN_CAPTURE_INTERVAL_MS + 80L + intervalPad + attempt * 120L
    }

    /**
     * Returns a placement for a window-only screenshot so gesture coordinates stay in
     * full-display space. Null means the bitmap must not be used as a display image.
     */
    fun placementForWindowBitmap(
        boundsLeft: Int,
        boundsTop: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): BitmapPlacement? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return null
        }
        if (bitmapWidth == displayWidth && bitmapHeight == displayHeight) {
            return BitmapPlacement.UseAsIs
        }
        if (boundsLeft < 0 || boundsTop < 0) return null
        if (boundsLeft.toLong() + bitmapWidth > displayWidth) return null
        if (boundsTop.toLong() + bitmapHeight > displayHeight) return null
        return BitmapPlacement.Offset(boundsLeft, boundsTop)
    }

    fun belowAndroid11Message(release: String, api: Int): String {
        return "无法获取屏幕截图：需要 Android 11 (API 30) 及以上版本，当前版本: Android $release (API $api)"
    }

    fun userVisibleMessage(platformCode: Int?, detail: String?): String {
        if (platformCode == null) {
            val extra = detail?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            return "无法获取屏幕截图：系统返回了截图缓冲区，但无法转换成可用图片$extra。请重试任务。"
        }
        val reason = when (platformCode) {
            ERROR_INTERNAL -> "系统截图内部错误"
            ERROR_NO_ACCESSIBILITY_ACCESS ->
                "无障碍服务没有截图能力。请在系统无障碍设置中关闭并重新打开本服务"
            ERROR_INTERVAL_TIME_SHORT -> "两次截图间隔短于系统限制"
            ERROR_INVALID_DISPLAY -> "系统拒绝截取当前屏幕"
            ERROR_INVALID_WINDOW -> "目标窗口无效"
            ERROR_SECURE_WINDOW -> "当前窗口禁止截图"
            else -> "系统截图失败"
        }
        val detailSuffix = detail?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return "无法获取屏幕截图：$reason（错误码 $platformCode）$detailSuffix。请重试任务。"
    }

    /**
     * A present but blank packageNames list matches no real package.
     * Null or an empty list means the service receives events from all packages.
     */
    fun packageFilterBlocksAllApps(packageNames: List<String>?): Boolean {
        if (packageNames.isNullOrEmpty()) return false
        return packageNames.all { it.isBlank() }
    }

    data class CapabilityRead(
        val sdk: Int,
        val inProcessInfoPresent: Boolean,
        val inProcessCapabilities: Int? = null,
        val inProcessFlags: Int? = null,
        val inProcessEventTypes: Int? = null,
        val inProcessPackages: List<String>? = null,
        val inProcessCanTakeScreenshot: Boolean? = null,
        val enabledPresent: Boolean,
        val enabledCapabilities: Int? = null,
        val enabledFlags: Int? = null,
        val enabledPackages: List<String>? = null,
        val enabledCanTakeScreenshot: Boolean? = null
    )

    enum class PreCapture {
        PROCEED,
        WAIT_FOR_CONNECTION,
        CAPABILITY_ABSENT
    }

    fun preCapture(read: CapabilityRead): PreCapture {
        if (!read.inProcessInfoPresent) return PreCapture.WAIT_FOR_CONNECTION
        val granted = read.inProcessCanTakeScreenshot == true || read.enabledCanTakeScreenshot == true
        if (!granted) return PreCapture.CAPABILITY_ABSENT
        return PreCapture.PROCEED
    }

    fun formatCapabilityLog(read: CapabilityRead): String {
        return buildString {
            append("sdk=${read.sdk}")
            if (!read.inProcessInfoPresent) {
                append(" serviceInfo=null inProcessConnection=false")
            } else {
                append(" serviceInfo=present")
                append(" inProcessCapabilities=${read.inProcessCapabilities}")
                append(" inProcessFlags=${read.inProcessFlags}")
                append(" inProcessEventTypes=${read.inProcessEventTypes}")
                append(" inProcessPackages=${read.inProcessPackages}")
                append(" canTakeScreenshotInProcess=${read.inProcessCanTakeScreenshot}")
            }
            append(" enabledPresent=${read.enabledPresent}")
            append(" enabledCapabilities=${read.enabledCapabilities}")
            append(" enabledFlags=${read.enabledFlags}")
            append(" enabledPackages=${read.enabledPackages}")
            append(" canTakeScreenshotEnabled=${read.enabledCanTakeScreenshot}")
            append(" packageFilterBlocksAllApps=${packageFilterBlocksAllApps(read.enabledPackages ?: read.inProcessPackages)}")
        }
    }

    fun connectionMissingMessage(): String {
        return "无法获取屏幕截图：应用进程内的无障碍连接不存在，capabilities 未被读取。请在系统无障碍设置中关闭并重新打开 Open-AutoGLM Diag，保留应用数据，然后重试任务。"
    }

    fun screenshotCapabilityAbsentMessage(enabledCapabilities: Int?, packageFilterBlocksAllApps: Boolean): String {
        val filter = if (packageFilterBlocksAllApps) {
            " 服务配置的 packageNames 为空字符串，事件不会发给任何应用。"
        } else {
            ""
        }
        val caps = enabledCapabilities?.toString() ?: "未知"
        return "无法获取屏幕截图：系统启用记录中的能力位为 $caps，不包含截图能力。安装包已声明 canTakeScreenshot。$filter 请在系统无障碍设置中关闭并重新打开 Open-AutoGLM Diag，保留应用数据，让系统重新读取服务配置后再试。"
    }
}
