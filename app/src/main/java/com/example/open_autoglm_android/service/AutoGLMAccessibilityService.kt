package com.example.open_autoglm_android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.open_autoglm_android.data.InputMode
import com.example.open_autoglm_android.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class ScreenCapture(
    val bitmap: Bitmap?,
    val failureMessage: String? = null
)

class AutoGLMAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AutoGLMScreenshot"
        private const val SCREENSHOT_CALLBACK_TIMEOUT_MS = 4_000L
        private var instance: AutoGLMAccessibilityService? = null
        
        fun getInstance(): AutoGLMAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preferencesRepository: PreferencesRepository
    var currentInputMode = InputMode.SET_TEXT
        private set
        
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()
    
    val safeCurrentApp: String?
        get() {
            val fromEvent = _currentApp.value
            if (!fromEvent.isNullOrBlank()) return fromEvent
            val fromRoot = rootInActiveWindow?.packageName?.toString()
            return fromRoot
        }
    
    private val _latestScreenshot = MutableStateFlow<Bitmap?>(null)
    val latestScreenshot: StateFlow<Bitmap?> = _latestScreenshot.asStateFlow()
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        preferencesRepository = PreferencesRepository(this)
        
        serviceScope.launch {
            preferencesRepository.inputMode.collect { mode ->
                currentInputMode = mode
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                _currentApp.value = it.packageName?.toString()
            }
        }
    }
    
    override fun onInterrupt() {}
    
    suspend fun takeScreenshotSuspend(): Bitmap? = captureForModel().bitmap

    /**
     * Captures the active display for the model.
     * Waits until the task overlay is offscreen, uses that display's id, and copies the
     * hardware buffer to a software bitmap before closing it.
     */
    suspend fun captureForModel(): ScreenCapture {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenCapture(
                bitmap = null,
                failureMessage = ScreenshotCapturePlan.belowAndroid11Message(
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT
                )
            )
        }
        logScreenshotCapability()
        val overlay = FloatingWindowService.getInstance()
        var detached = false
        try {
            overlay?.hideForCapture(detachWindow = false)
            var lastCode: Int? = null
            var lastDetail: String? = null
            for (attempt in 0 until ScreenshotCapturePlan.MAX_ATTEMPTS) {
                if (!detached && attempt > 0 &&
                    ScreenshotCapturePlan.shouldDetachOverlayOnRetry(lastCode)
                ) {
                    overlay?.hideForCapture(detachWindow = true)
                    detached = true
                }
                val target = snapshotCaptureTarget()
                val displayId = target?.displayId?.takeIf { it != ScreenshotCapturePlan.DISPLAY_INVALID }
                    ?: Display.DEFAULT_DISPLAY
                Log.i(
                    TAG,
                    "capture attempt=$attempt displayId=$displayId windowId=${target?.windowId} pkg=${target?.packageName}"
                )
                var shot = requestDisplayShot(displayId)
                if (shot is Shot.Failed &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    target != null &&
                    ScreenshotCapturePlan.shouldTryWindowFallback(shot.errorCode, target.windowId)
                ) {
                    val windowShot = requestWindowShot(target.windowId)
                    shot = when (windowShot) {
                        is Shot.Ok -> {
                            val placed = placeWindowBitmapOnDisplay(windowShot.bitmap, target)
                            if (placed != null) {
                                Shot.Ok(placed)
                            } else {
                                if (!windowShot.bitmap.isRecycled) windowShot.bitmap.recycle()
                                shot
                            }
                        }
                        else -> shot
                    }
                }
                when (shot) {
                    is Shot.Ok -> {
                        _latestScreenshot.value = shot.bitmap
                        return ScreenCapture(shot.bitmap, null)
                    }
                    is Shot.Failed -> {
                        lastCode = shot.errorCode
                        lastDetail = shot.detail
                        if (!ScreenshotCapturePlan.shouldRetryPlatform(shot.errorCode, attempt)) break
                    }
                    is Shot.DecodeFailed -> {
                        lastCode = null
                        lastDetail = shot.detail
                        if (!ScreenshotCapturePlan.shouldRetryDecode(attempt)) break
                    }
                }
                delay(ScreenshotCapturePlan.retryDelayMs(lastCode, attempt))
            }
            return ScreenCapture(
                bitmap = null,
                failureMessage = ScreenshotCapturePlan.userVisibleMessage(lastCode, lastDetail)
            )
        } finally {
            overlay?.restoreAfterCapture()
        }
    }

    private fun logScreenshotCapability() {
        try {
            val caps = serviceInfo?.capabilities ?: 0
            val canTakeScreenshot =
                (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
            Log.i(TAG, "sdk=${Build.VERSION.SDK_INT} capabilities=$caps canTakeScreenshot=$canTakeScreenshot")
        } catch (t: RuntimeException) {
            Log.w(TAG, "serviceInfo unavailable: ${t.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun snapshotCaptureTarget(): ScreenshotCapturePlan.CaptureTarget? {
        val list = try {
            windows
        } catch (t: RuntimeException) {
            Log.w(TAG, "windows unavailable: ${t.message}")
            null
        } ?: return null
        val candidates = ArrayList<ScreenshotCapturePlan.WindowCandidate>(list.size)
        try {
            for (window in list) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                val root = window.root
                val pkg = root?.packageName?.toString()
                root?.recycle()
                candidates.add(
                    ScreenshotCapturePlan.WindowCandidate(
                        windowId = window.id,
                        displayId = window.displayId,
                        type = window.type,
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        boundsLeft = bounds.left,
                        boundsTop = bounds.top,
                        boundsWidth = bounds.width(),
                        boundsHeight = bounds.height(),
                        packageName = pkg
                    )
                )
            }
        } finally {
            list.forEach { it.recycle() }
        }
        return ScreenshotCapturePlan.selectCaptureTarget(candidates, packageName)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun requestDisplayShot(displayId: Int): Shot {
        return requestShot("display:$displayId") { resume ->
            takeScreenshot(
                displayId,
                mainExecutor,
                shotCallback(resume, "display:$displayId")
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun requestWindowShot(windowId: Int): Shot {
        return requestShot("window:$windowId") { resume ->
            takeScreenshotOfWindow(
                windowId,
                mainExecutor,
                shotCallback(resume, "window:$windowId")
            )
        }
    }

    private suspend fun requestShot(source: String, invoke: (resume: (Shot) -> Unit) -> Unit): Shot {
        return try {
            withTimeout(SCREENSHOT_CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    fun resume(shot: Shot) {
                        if (!resumed.compareAndSet(false, true)) return
                        if (!continuation.isActive) {
                            if (shot is Shot.Ok && !shot.bitmap.isRecycled) shot.bitmap.recycle()
                            return
                        }
                        continuation.resume(shot)
                    }
                    mainExecutor.execute {
                        try {
                            invoke(::resume)
                        } catch (t: Throwable) {
                            Log.e(TAG, "screenshot request threw for $source", t)
                            val code = if (t is SecurityException) {
                                ScreenshotCapturePlan.ERROR_NO_ACCESSIBILITY_ACCESS
                            } else {
                                ScreenshotCapturePlan.ERROR_INTERNAL
                            }
                            resume(Shot.Failed(code, t.javaClass.simpleName))
                        }
                    }
                }
            }
        } catch (t: TimeoutCancellationException) {
            Log.w(TAG, "screenshot callback timed out for $source")
            Shot.Failed(ScreenshotCapturePlan.ERROR_INTERNAL, "timeout source=$source")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun shotCallback(
        resume: (Shot) -> Unit,
        source: String
    ): AccessibilityService.TakeScreenshotCallback {
        return object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                resume(decodeScreenshot(result, source))
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed source=$source code=$errorCode")
                resume(Shot.Failed(errorCode, "source=$source"))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun decodeScreenshot(
        result: AccessibilityService.ScreenshotResult,
        source: String
    ): Shot {
        val buffer = result.hardwareBuffer
        var wrapped: Bitmap? = null
        var software: Bitmap? = null
        return try {
            wrapped = wrapHardwareBuffer(buffer, result.colorSpace)
            val hardware = wrapped
            if (hardware == null) {
                Shot.DecodeFailed("wrapHardwareBuffer=null format=${buffer.format} usage=${buffer.usage} source=$source")
            } else {
                software = copyToSoftwareBitmap(hardware)
                if (software == null) {
                    Shot.DecodeFailed(
                        "softwareCopy=null format=${buffer.format} config=${hardware.config} ${hardware.width}x${hardware.height} source=$source"
                    )
                } else {
                    Log.i(TAG, "decoded $source ${software.width}x${software.height} format=${buffer.format}")
                    Shot.Ok(software)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed for $source", t)
            Shot.DecodeFailed("${t.javaClass.simpleName} source=$source")
        } finally {
            if (wrapped != null && wrapped !== software && !wrapped.isRecycled) {
                wrapped.recycle()
            }
            try {
                buffer.close()
            } catch (t: RuntimeException) {
                Log.w(TAG, "hardwareBuffer.close failed: ${t.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun wrapHardwareBuffer(buffer: HardwareBuffer, colorSpace: ColorSpace?): Bitmap? {
        val spaces = ArrayList<ColorSpace?>(2)
        if (colorSpace is ColorSpace.Rgb) spaces.add(colorSpace)
        spaces.add(null)
        var lastError: IllegalArgumentException? = null
        for (space in spaces) {
            try {
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, space)
                if (bitmap != null) return bitmap
            } catch (e: IllegalArgumentException) {
                lastError = e
                Log.w(TAG, "wrapHardwareBuffer rejected colorSpace=$space: ${e.message}")
            }
        }
        if (lastError != null) Log.w(TAG, "wrapHardwareBuffer failed", lastError)
        return null
    }

    private fun copyToSoftwareBitmap(source: Bitmap): Bitmap? {
        try {
            val copied = source.copy(Bitmap.Config.ARGB_8888, false)
            if (copied != null && copied !== source) return copied
        } catch (t: Throwable) {
            Log.w(TAG, "Bitmap.copy failed: ${t.message}")
        }
        return try {
            val software = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(software).drawBitmap(source, 0f, 0f, null)
            software
        } catch (t: Throwable) {
            Log.w(TAG, "Canvas software copy failed: ${t.message}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun placeWindowBitmapOnDisplay(
        bitmap: Bitmap,
        target: ScreenshotCapturePlan.CaptureTarget
    ): Bitmap? {
        val display = displaySize(target.displayId) ?: return null
        return when (
            val placement = ScreenshotCapturePlan.placementForWindowBitmap(
                boundsLeft = target.boundsLeft,
                boundsTop = target.boundsTop,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                displayWidth = display.width,
                displayHeight = display.height
            )
        ) {
            null -> null
            ScreenshotCapturePlan.BitmapPlacement.UseAsIs -> bitmap
            is ScreenshotCapturePlan.BitmapPlacement.Offset -> {
                val full = Bitmap.createBitmap(display.width, display.height, Bitmap.Config.ARGB_8888)
                Canvas(full).drawBitmap(bitmap, placement.left.toFloat(), placement.top.toFloat(), null)
                if (!bitmap.isRecycled) bitmap.recycle()
                full
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun displaySize(displayId: Int): ScreenshotCapturePlan.PixelSize? {
        val displayManager = getSystemService(DisplayManager::class.java) ?: return null
        val display = displayManager.getDisplay(displayId) ?: return null
        val displayContext = createDisplayContext(display)
        val windowManager = displayContext.getSystemService(WindowManager::class.java) ?: return null
        val bounds = windowManager.maximumWindowMetrics.bounds
        return ScreenshotCapturePlan.PixelSize(bounds.width(), bounds.height())
    }

    private sealed class Shot {
        data class Ok(val bitmap: Bitmap) : Shot()
        data class Failed(val errorCode: Int, val detail: String) : Shot()
        data class DecodeFailed(val detail: String) : Shot()
    }
    
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow
    
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }
    
    fun tap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }

    fun longPress(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }
    
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            dispatchGesture(gesture, null, null)
        }
    }
    
    fun performBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    
    fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.parent?.let { return performClick(it) }
        return false
    }
    
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d("AutoGLMService", "准备输入文本, 模式: $currentInputMode")
        
        if (currentInputMode != InputMode.SET_TEXT) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            Log.d("AutoGLMService", "执行物理点击激活: ($centerX, $centerY)")
            tap(centerX, centerY)
        }

        return when (currentInputMode) {
            InputMode.SET_TEXT -> {
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            InputMode.PASTE -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("auto_glm", text))
                mainHandler.postDelayed({
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    Log.d("AutoGLMService", "粘贴结果: $success")
                }, 400)
                true
            }
            InputMode.IME -> {
                if (MyInputMethodService.isEnabled()) {
                    var attempts = 0
                    val maxAttempts = 5
                    
                    fun tryTypeText() {
                        if (attempts >= maxAttempts) {
                            Log.e("AutoGLMService", "IME输入重试多次后依然失败")
                            return
                        }
                        
                        val success = MyInputMethodService.typeText(text)
                        Log.d("AutoGLMService", "IME输入尝试 ${attempts + 1}, 结果: $success")
                        
                        if (!success) {
                            attempts++
                            // 如果失败，可能是键盘还没准备好，500ms后重试
                            mainHandler.postDelayed({ tryTypeText() }, 500)
                        }
                    }
                    
                    // 第一次尝试放在 800ms 后，确保键盘动画基本完成
                    mainHandler.postDelayed({ tryTypeText() }, 800)
                    true
                } else {
                    val arguments = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
            }
        }
    }
}
