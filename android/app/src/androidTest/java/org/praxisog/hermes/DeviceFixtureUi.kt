package org.praxisog.hermes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.io.File

/** API 37-compatible real touch, scoped exclusively to the disposable application. */
internal object DeviceFixtureUi {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation get() = instrumentation.uiAutomation
    fun guard() = check(ApplicationProvider.getApplicationContext<Context>().packageName == "org.praxisog.hermes.fixture")
    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    fun await(message: String, timeout: Long = 25000, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!check()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError(message)
            Thread.sleep(50)
        }
    }
    fun awaitMain(message: String, check: () -> Boolean) = await(message) {
        var result = false
        main { result = check() }
        result
    }
    fun find(check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (check(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.let(::visit) }
    }
    fun text(value: String) = find { it.text?.toString() == value }
    fun tap(node: AccessibilityNodeInfo) {
        automation.waitForIdle(300, 3000)
        SystemClock.sleep(300) // Allow dialog/window entrance and scroll animations to settle.
        check(node.refresh()) { "Touch target disappeared" }
        val bounds = Rect().also(node::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }
    fun tapText(value: String) {
        await("Missing UI label: $value") { text(value) != null }
        repeat(10) {
            var target = checkNotNull(text(value))
            while (!target.isClickable && target.parent != null) target = target.parent
            val targetBounds = Rect().also(target::getBoundsInScreen)
            var parent = target.parent
            while (parent != null && !parent.isScrollable) parent = parent.parent
            val viewport = parent?.let { Rect().also(it::getBoundsInScreen) }
            if (viewport == null || (targetBounds.top >= viewport.top && targetBounds.bottom <= viewport.bottom)) {
                tap(target)
                return
            }
            check(parent!!.performAction(if (targetBounds.bottom > viewport.bottom)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                "Cannot reveal touch target: $value"
            }
            automation.waitForIdle(300, 3000)
            SystemClock.sleep(200)
        }
        error("Touch target remains clipped: $value")
    }
    fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            checkNotNull(automation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    fun withWindows(block: () -> Unit) {
        guard()
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try { block() } finally { automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
    }
}
