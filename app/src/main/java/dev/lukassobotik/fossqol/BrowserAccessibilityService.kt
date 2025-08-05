package dev.lukassobotik.fossqol

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.util.Patterns
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.lukassobotik.fossqol.utils.formatDataToBase64

class BrowserAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("BrowserExtract", "BrowserAccessibilityService onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != "org.mozilla.firefox") return

        val root = rootInActiveWindow ?: return

        val visibleText = mutableListOf<String>()
        var urlCandidate: String? = null

        fun extractFirefoxUrl(root: AccessibilityNodeInfo): String? {
            val nodes = root.findAccessibilityNodeInfosByViewId("org.mozilla.firefox:id/mozac_browser_toolbar_url_view")
            nodes?.forEach { node ->
                val text = node.text?.toString()
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

        // Clean result — top few visible paragraphs
        val result = buildString {
            appendLine("URL: ${urlCandidate ?: "N/A"}")
            appendLine("--- Visible Text Snippets ---")
            visibleText
                .filter { it.length > 10 }  // Ignore short items
                .distinct()
                .take(10)  // Only the first few
                .forEach { appendLine(it) }
        }

        val formattedResult = formatDataToBase64(urlCandidate ?: "N/A", visibleText.filter { it.length > 10 }.distinct().take(10))
        Log.i("BrowserExtract", formattedResult)

        // (For manual transfer you can copy this via logcat or save to a file)
    }

    override fun onInterrupt() {
        // No-op
    }
}
