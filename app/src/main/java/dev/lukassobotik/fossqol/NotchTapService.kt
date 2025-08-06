package dev.lukassobotik.fossqol

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

class NotchTapService : AccessibilityService() {
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onServiceConnected() = setupOverlay()
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
        ).apply { layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }

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
        android.util.Log.d("NotchTap", "Single tap detected")
    }
}
