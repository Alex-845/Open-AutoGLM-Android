package com.example.open_autoglm_android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotCapturePlanTest {

    private val ownPackage = "com.example.open_autoglm_android"

    @Test
    fun selectCaptureTarget_prefersActiveForeignApplicationOnItsDisplay() {
        val target = ScreenshotCapturePlan.selectCaptureTarget(
            listOf(
                window(
                    id = 1,
                    displayId = 0,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    active = true,
                    pkg = ownPackage,
                    width = 1080,
                    height = 2400
                ),
                window(
                    id = 7,
                    displayId = 2,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    active = true,
                    pkg = "com.sec.android.app.popupcalculator",
                    width = 1080,
                    height = 2640
                )
            ),
            ownPackage
        )

        assertEquals(7, target?.windowId)
        assertEquals(2, target?.displayId)
        assertEquals("com.sec.android.app.popupcalculator", target?.packageName)
    }

    @Test
    fun selectCaptureTarget_skipsInvalidDisplayAndPrefersFocusedForeignApp() {
        val target = ScreenshotCapturePlan.selectCaptureTarget(
            listOf(
                window(
                    id = 3,
                    displayId = ScreenshotCapturePlan.DISPLAY_INVALID,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    active = true,
                    pkg = "com.sec.android.app.popupcalculator"
                ),
                window(
                    id = 4,
                    displayId = 5,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    active = false,
                    focused = true,
                    pkg = "com.sec.android.app.popupcalculator",
                    width = 100,
                    height = 100
                ),
                window(
                    id = 9,
                    displayId = 5,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    active = false,
                    pkg = "com.sec.android.app.popupcalculator",
                    width = 800,
                    height = 1200
                )
            ),
            ownPackage
        )

        assertEquals(4, target?.windowId)
    }

    @Test
    fun selectCaptureTarget_usesLargestForeignAppWhenNoneActive() {
        val target = ScreenshotCapturePlan.selectCaptureTarget(
            listOf(
                window(
                    id = 1,
                    displayId = 0,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    pkg = ownPackage,
                    width = 2000,
                    height = 2000
                ),
                window(
                    id = 2,
                    displayId = 1,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    pkg = "com.sec.android.app.popupcalculator",
                    width = 400,
                    height = 400
                ),
                window(
                    id = 3,
                    displayId = 1,
                    type = ScreenshotCapturePlan.TYPE_APPLICATION,
                    pkg = "com.sec.android.app.popupcalculator",
                    width = 1000,
                    height = 1800
                )
            ),
            ownPackage
        )

        assertEquals(3, target?.windowId)
        assertEquals(1, target?.displayId)
    }

    @Test
    fun selectCaptureTarget_emptyListReturnsNull() {
        assertNull(ScreenshotCapturePlan.selectCaptureTarget(emptyList(), ownPackage))
    }

    @Test
    fun retryPolicy_retriesTransientPlatformErrorsAndStopsOnCapabilityOrSecure() {
        assertTrue(ScreenshotCapturePlan.shouldRetryPlatform(ScreenshotCapturePlan.ERROR_INTERNAL, 0))
        assertTrue(
            ScreenshotCapturePlan.shouldRetryPlatform(
                ScreenshotCapturePlan.ERROR_INTERVAL_TIME_SHORT,
                1
            )
        )
        assertTrue(
            ScreenshotCapturePlan.shouldRetryPlatform(ScreenshotCapturePlan.ERROR_INVALID_DISPLAY, 0)
        )
        assertFalse(
            ScreenshotCapturePlan.shouldRetryPlatform(ScreenshotCapturePlan.ERROR_INVALID_DISPLAY, 2)
        )
        assertFalse(
            ScreenshotCapturePlan.shouldRetryPlatform(
                ScreenshotCapturePlan.ERROR_NO_ACCESSIBILITY_ACCESS,
                0
            )
        )
        assertFalse(
            ScreenshotCapturePlan.shouldRetryPlatform(ScreenshotCapturePlan.ERROR_SECURE_WINDOW, 0)
        )
        assertTrue(ScreenshotCapturePlan.shouldRetryDecode(0))
        assertFalse(ScreenshotCapturePlan.shouldRetryDecode(2))
    }

    @Test
    fun windowFallback_onlyForDisplayFailuresWithARealWindow() {
        assertTrue(
            ScreenshotCapturePlan.shouldTryWindowFallback(ScreenshotCapturePlan.ERROR_INVALID_DISPLAY, 4)
        )
        assertTrue(
            ScreenshotCapturePlan.shouldTryWindowFallback(ScreenshotCapturePlan.ERROR_INTERNAL, 4)
        )
        assertFalse(
            ScreenshotCapturePlan.shouldTryWindowFallback(
                ScreenshotCapturePlan.ERROR_NO_ACCESSIBILITY_ACCESS,
                4
            )
        )
        assertFalse(
            ScreenshotCapturePlan.shouldTryWindowFallback(ScreenshotCapturePlan.ERROR_INVALID_DISPLAY, -1)
        )
    }

    @Test
    fun overlayDetach_onlyAfterInternalOrInvalidDisplay() {
        assertTrue(ScreenshotCapturePlan.shouldDetachOverlayOnRetry(ScreenshotCapturePlan.ERROR_INTERNAL))
        assertTrue(
            ScreenshotCapturePlan.shouldDetachOverlayOnRetry(ScreenshotCapturePlan.ERROR_INVALID_DISPLAY)
        )
        assertFalse(
            ScreenshotCapturePlan.shouldDetachOverlayOnRetry(ScreenshotCapturePlan.ERROR_INTERVAL_TIME_SHORT)
        )
        assertFalse(ScreenshotCapturePlan.shouldDetachOverlayOnRetry(null))
    }

    @Test
    fun retryDelay_respectsPlatformIntervalFloor() {
        val delay = ScreenshotCapturePlan.retryDelayMs(ScreenshotCapturePlan.ERROR_INTERVAL_TIME_SHORT, 0)
        assertTrue(delay >= ScreenshotCapturePlan.MIN_CAPTURE_INTERVAL_MS)
    }

    @Test
    fun userMessage_includesPlatformCodeAndDoesNotClaimServiceIsOff() {
        val message = ScreenshotCapturePlan.userVisibleMessage(
            ScreenshotCapturePlan.ERROR_INVALID_DISPLAY,
            "displayId=2"
        )
        assertTrue(message.contains("错误码 4"))
        assertTrue(message.contains("displayId=2"))
        assertFalse(message.contains("请确保无障碍服务已启用"))
    }

    @Test
    fun userMessage_decodeFailureNamesBufferConversion() {
        val message = ScreenshotCapturePlan.userVisibleMessage(null, "format=43")
        assertTrue(message.contains("缓冲区"))
        assertTrue(message.contains("format=43"))
        assertFalse(message.contains("请确保无障碍服务已启用"))
    }

    @Test
    fun placement_fullDisplayBitmapIsUsedAsIs() {
        val placement = ScreenshotCapturePlan.placementForWindowBitmap(
            boundsLeft = 10,
            boundsTop = 20,
            bitmapWidth = 1080,
            bitmapHeight = 2640,
            displayWidth = 1080,
            displayHeight = 2640
        )
        assertEquals(ScreenshotCapturePlan.BitmapPlacement.UseAsIs, placement)
    }

    @Test
    fun placement_windowBitmapKeepsScreenOffset() {
        val placement = ScreenshotCapturePlan.placementForWindowBitmap(
            boundsLeft = 40,
            boundsTop = 120,
            bitmapWidth = 900,
            bitmapHeight = 1600,
            displayWidth = 1080,
            displayHeight = 2640
        )
        assertEquals(ScreenshotCapturePlan.BitmapPlacement.Offset(40, 120), placement)
    }

    @Test
    fun placement_rejectsBitmapThatDoesNotFitTheDisplay() {
        assertNull(
            ScreenshotCapturePlan.placementForWindowBitmap(
                boundsLeft = 400,
                boundsTop = 0,
                bitmapWidth = 900,
                bitmapHeight = 1600,
                displayWidth = 1080,
                displayHeight = 2640
            )
        )
    }

    @Test
    fun blankPackageNames_blocksEveryAppWhileNullDoesNot() {
        assertTrue(ScreenshotCapturePlan.packageFilterBlocksAllApps(listOf("")))
        assertFalse(ScreenshotCapturePlan.packageFilterBlocksAllApps(null))
        assertFalse(ScreenshotCapturePlan.packageFilterBlocksAllApps(emptyList()))
        assertFalse(
            ScreenshotCapturePlan.packageFilterBlocksAllApps(
                listOf("com.sec.android.app.popupcalculator")
            )
        )
    }

    @Test
    fun capabilityLog_nullServiceInfoIsNotReportedAsZero() {
        val line = ScreenshotCapturePlan.formatCapabilityLog(
            ScreenshotCapturePlan.CapabilityRead(
                sdk = 36,
                inProcessInfoPresent = false,
                enabledPresent = true,
                enabledCapabilities = 159,
                enabledFlags = 67,
                enabledPackages = listOf(""),
                enabledCanTakeScreenshot = true
            )
        )
        assertTrue(line.contains("serviceInfo=null"))
        assertTrue(line.contains("inProcessConnection=false"))
        assertTrue(line.contains("enabledCapabilities=159"))
        assertTrue(line.contains("canTakeScreenshotEnabled=true"))
        assertFalse(line.contains("capabilities=0"))
        assertEquals(
            ScreenshotCapturePlan.PreCapture.WAIT_FOR_CONNECTION,
            ScreenshotCapturePlan.preCapture(
                ScreenshotCapturePlan.CapabilityRead(
                    sdk = 36,
                    inProcessInfoPresent = false,
                    enabledPresent = true,
                    enabledCapabilities = 159,
                    enabledCanTakeScreenshot = true
                )
            )
        )
    }

    @Test
    fun capabilityLog_realZeroIsDistinctFromMissingInfo() {
        val read = ScreenshotCapturePlan.CapabilityRead(
            sdk = 36,
            inProcessInfoPresent = true,
            inProcessCapabilities = 0,
            inProcessFlags = 0,
            inProcessEventTypes = 0,
            inProcessPackages = listOf(""),
            inProcessCanTakeScreenshot = false,
            enabledPresent = true,
            enabledCapabilities = 0,
            enabledFlags = 0,
            enabledPackages = listOf(""),
            enabledCanTakeScreenshot = false
        )
        val line = ScreenshotCapturePlan.formatCapabilityLog(read)
        assertTrue(line.contains("serviceInfo=present"))
        assertTrue(line.contains("inProcessCapabilities=0"))
        assertTrue(line.contains("canTakeScreenshotInProcess=false"))
        assertTrue(line.contains("packageFilterBlocksAllApps=true"))
        assertEquals(ScreenshotCapturePlan.PreCapture.CAPABILITY_ABSENT, ScreenshotCapturePlan.preCapture(read))
        val message = ScreenshotCapturePlan.screenshotCapabilityAbsentMessage(0, true)
        assertTrue(message.contains("能力位为 0"))
        assertTrue(message.contains("packageNames"))
        assertFalse(message.contains("错误码 1"))
    }

    @Test
    fun grantedEnabledCapability_proceedsEvenIfInProcessMaskIsZero() {
        val read = ScreenshotCapturePlan.CapabilityRead(
            sdk = 36,
            inProcessInfoPresent = true,
            inProcessCapabilities = 0,
            inProcessCanTakeScreenshot = false,
            enabledPresent = true,
            enabledCapabilities = 128,
            enabledCanTakeScreenshot = true
        )
        assertEquals(ScreenshotCapturePlan.PreCapture.PROCEED, ScreenshotCapturePlan.preCapture(read))
    }

    @Test
    fun connectionMissingMessage_doesNotUseInternalErrorCode() {
        val message = ScreenshotCapturePlan.connectionMissingMessage()
        assertTrue(message.contains("连接不存在"))
        assertFalse(message.contains("错误码 1"))
    }

    @Test
    fun belowAndroid11Message_namesCurrentApi() {
        val message = ScreenshotCapturePlan.belowAndroid11Message("10", 29)
        assertTrue(message.contains("API 29"))
        assertTrue(message.contains("Android 10"))
    }

    private fun window(
        id: Int,
        displayId: Int,
        type: Int,
        active: Boolean = false,
        focused: Boolean = false,
        pkg: String?,
        width: Int = 100,
        height: Int = 100,
        left: Int = 0,
        top: Int = 0
    ): ScreenshotCapturePlan.WindowCandidate {
        return ScreenshotCapturePlan.WindowCandidate(
            windowId = id,
            displayId = displayId,
            type = type,
            isActive = active,
            isFocused = focused,
            boundsLeft = left,
            boundsTop = top,
            boundsWidth = width,
            boundsHeight = height,
            packageName = pkg
        )
    }
}
