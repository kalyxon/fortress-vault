package com.fortress.vault.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.fortress.vault.core.ReelsBlockerManager

class ReelsBlockerAccessibilityService : AccessibilityService() {
    private var warningView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollingRunnable: Runnable? = null
    private var presentSince = 0L
    private var absentSince = 0L
    private var dismissedUntil = 0L
    private var lastEventEvaluation = 0L
    private val viewerIds = setOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/root_clips_layout",
        "com.instagram.android:id/clips_viewer_container",
        "com.google.android.youtube:id/reel_recycler"
    )
    private val reelsLabels = setOf("reels", "shorts", "spotlight", "clips")
    private val navLabels = reelsLabels + setOf(
        "home", "search", "explore", "profile", "you", "library", "subscriptions",
        "messages", "chat", "map", "camera", "stories", "create"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        startRoutePolling()
    }

    private fun startRoutePolling() {
        pollingRunnable?.let(mainHandler::removeCallbacks)
        val poll = object : Runnable {
            override fun run() {
                evaluate()
                mainHandler.postDelayed(this, 500)
            }
        }
        pollingRunnable = poll
        mainHandler.postDelayed(poll, 250)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemClock.uptimeMillis()
        if (now - lastEventEvaluation >= 300) {
            lastEventEvaluation = now
            evaluate()
        }
    }

    override fun onInterrupt() = Unit

    private fun evaluate() {
        val context = applicationContext
        if (!ReelsBlockerManager.isEnabled(context)) {
            resetState()
            return
        }

        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        if (packageName == context.packageName) return
        if (packageName !in ReelsBlockerManager.blockedPackages(context)) {
            resetState()
            return
        }

        val now = SystemClock.uptimeMillis()
        val onRoute = containsBlockedRoute(root)
        if (onRoute) {
            absentSince = 0L
            if (presentSince == 0L) presentSince = now
            if (now >= dismissedUntil && now - presentSince >= 400) showWarning()
        } else {
            presentSince = 0L
            if (absentSince == 0L) absentSince = now
            if (now - absentSince >= 800) {
                removeWarning()
            }
        }
    }

    private fun resetState() {
        presentSince = 0L
        absentSince = 0L
        dismissedUntil = 0L
        removeWarning()
    }

    private fun containsBlockedRoute(root: AccessibilityNodeInfo): Boolean {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val rect = Rect()
        var viewerFullscreen = false
        var tabBarPresent = false
        var reelsTabSelected = false

        val stack = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val id = node.viewIdResourceName?.lowercase().orEmpty()
                val label = (
                    node.text?.toString()
                        ?: node.contentDescription?.toString()
                        ?: ""
                    ).trim().lowercase().substringBefore(",")
                val selected = node.isSelected || node.isChecked
                val inBottomBar = rect.centerY() > screenH * 0.85f

                if (id in viewerIds &&
                    rect.width() >= screenW * 0.9f &&
                    rect.height() >= screenH * 0.7f
                ) {
                    viewerFullscreen = true
                }

                if (inBottomBar &&
                    (label in navLabels || id.endsWith(":id/clips_tab") || id.contains("spotlight"))
                ) {
                    tabBarPresent = true
                    val isReelsTab = label in reelsLabels ||
                        id.endsWith(":id/clips_tab") ||
                        id.contains("spotlight")
                    if (isReelsTab && selected) reelsTabSelected = true
                }

                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(stack::add)
                }
            }
            if (node !== root && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                node.recycle()
            }
        }
        return reelsTabSelected || (viewerFullscreen && !tabBarPresent)
    }

    private fun showWarning() {
        if (warningView != null) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(72, 64, 72, 64)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF9F9F9.toInt())
        }
        layout.addView(TextView(this).apply {
            text = "Reels Are Blocked"
            textSize = 24f
            setTextColor(0xFF202020.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        layout.addView(TextView(this).apply {
            text = "Choose another part of the app"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, 16, 0, 32)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(Button(this).apply {
            text = "Go Back"
            setOnClickListener {
                dismissedUntil = SystemClock.uptimeMillis() + 1_500L
                removeWarning()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 16
        })
        actions.addView(Button(this).apply {
            text = "Choose Another Tab"
            setOnClickListener {
                dismissedUntil = SystemClock.uptimeMillis() + 1_500L
                presentSince = 0L
                absentSince = 0L
                removeWarning()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(actions)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.85f
        }
        warningView = layout
        try {
            manager.addView(layout, params)
        } catch (error: Throwable) {
            warningView = null
        }
    }

    private fun removeWarning() {
        warningView?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        warningView = null
    }

    override fun onDestroy() {
        pollingRunnable?.let(mainHandler::removeCallbacks)
        removeWarning()
        super.onDestroy()
    }
}
