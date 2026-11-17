package com.example.campusagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

object AutoActionManager {
    @Volatile
    var pendingAutoFollow: Boolean = false

    @Volatile
    var targetUsername: String = ""

    fun triggerAutoFollow(username: String) {
        targetUsername = username.lowercase().trim()
        pendingAutoFollow = true
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        var accessibilityEnabled = 0
        val service = context.packageName + "/" + CampusAccessibilityService::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
            )
        } catch (_: Exception) {}

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val accessibilityService = splitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}

/**
 * Accessibility Service enabling hands-free auto-following on Instagram
 * and automatic UI button interactions when requested by voice.
 */
class CampusAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoActionManager.pendingAutoFollow) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val followNodes = rootNode.findAccessibilityNodeInfosByText("Follow")
            if (followNodes.isNullOrEmpty()) {
                val followBackNodes = rootNode.findAccessibilityNodeInfosByText("Follow Back")
                performClickOnFirst(followBackNodes)
            } else {
                performClickOnFirst(followNodes)
            }
        } catch (_: Exception) {
        }
    }

    private fun performClickOnFirst(nodes: List<AccessibilityNodeInfo>?): Boolean {
        if (nodes.isNullOrEmpty()) return false
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                AutoActionManager.pendingAutoFollow = false
                return true
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    AutoActionManager.pendingAutoFollow = false
                    return true
                }
                parent = parent.parent
            }
        }
        return false
    }

    override fun onInterrupt() {}
}
