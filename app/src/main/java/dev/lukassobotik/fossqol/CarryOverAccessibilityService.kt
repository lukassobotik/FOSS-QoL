package dev.lukassobotik.fossqol

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import dev.lukassobotik.fossqol.utils.formatDataToBase64

class CarryOverAccessibilityService : AccessibilityService() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private var lastRootNode: AccessibilityNodeInfo? = null
    private var lastKnownFirefoxRoot: AccessibilityNodeInfo? = null
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        Log.i("CarryOverService", "Service connected.")

        // TODO: Address the unsupported versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setupOverlay()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != null) {
            lastPackage = event.packageName.toString()
        }

        rootInActiveWindow?.let {
            if (lastPackage == "org.mozilla.firefox") {
                lastKnownFirefoxRoot = AccessibilityNodeInfo.obtain(it)
            }
        }
    }

    override fun onInterrupt() {}

    @RequiresApi(Build.VERSION_CODES.P)
    private fun setupOverlay() {
        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val overlay = object : FrameLayout(this) {
            init {
                isClickable = true
                setBackgroundColor(Color.argb(128, 255, 0, 0))
                setOnClickListener { notchClicked() }
                setOnApplyWindowInsetsListener { v, insets ->
                    insets.displayCutout?.boundingRects?.firstOrNull()?.also { rect ->
                        with(params) {
                            width = rect.width()
                            height = rect.height()
                            x = 0
                            y = -resources.displayMetrics.heightPixels
                            windowManager.updateViewLayout(v, this)
                        }
                    }
                    insets
                }
            }
        }

        windowManager.addView(overlay, params)
    }

    private fun notchClicked() {
        Log.i("CarryOverService", "Tap detected. Attempting to scrape Firefox content.")

        val root = if (lastPackage == "org.mozilla.firefox") {
            rootInActiveWindow ?: lastKnownFirefoxRoot
        } else {
            Log.w("CarryOverService", "Not in Firefox; skipping scrape.")
            return
        }

        if (root == null) {
            Log.w("CarryOverService", "No valid root node; can't scrape.")
            return
        }

        val result = extractBrowserData(root)
        Log.i("CarryOverService", result)
    }

    private fun extractBrowserData(root: AccessibilityNodeInfo): String {
        val visibleText = mutableListOf<String>()
        var urlCandidate: String? = null

        fun extractFirefoxUrl(node: AccessibilityNodeInfo): String? {
            val nodes = node.findAccessibilityNodeInfosByViewId(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view"
            )
            nodes?.forEach { n ->
                val text = n.text?.toString()
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
            return null
        }

        urlCandidate = extractFirefoxUrl(root)

        fun traverse(node: AccessibilityNodeInfo) {
            if (node.text != null && node.isVisibleToUser) {
                val textStr = node.text.toString()
                visibleText.add(textStr)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(root)

        return formatDataToBase64(
            urlCandidate ?: "N/A",
            visibleText.filter { it.length > 10 }.distinct().take(10)
        )
    }
}