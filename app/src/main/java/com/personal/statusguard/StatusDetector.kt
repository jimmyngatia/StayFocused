package com.personal.statusguard

import android.view.accessibility.AccessibilityNodeInfo

/** Works out whether WhatsApp is currently showing status. Tuned for WhatsApp in English. */
object StatusDetector {
    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * Viewing a status (from the Updates tab, a chat or a profile picture) opens a separate
     * WhatsApp screen with "status" in its name, e.g. com.whatsapp.status.playback.StatusPlaybackActivity.
     */
    fun isStatusScreen(screenName: String?) = screenName?.contains("status", ignoreCase = true) == true

    /** True when the Updates tab (called "Status" in older WhatsApp versions) is the selected tab. */
    fun isOnUpdatesTab(root: AccessibilityNodeInfo): Boolean {
        for (label in listOf("Updates", "Status")) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            // The tab button at the bottom is marked as selected when its tab is open.
            if (nodes.any { isLabel(it, label) && (it.isSelected || it.parent?.isSelected == true) }) return true
        }
        // Backup check: on the Updates tab, "Updates" appears twice (the page title and the tab button).
        return root.findAccessibilityNodeInfosByText("Updates").count { it.text?.toString() == "Updates" } >= 2
    }

    /** WhatsApp voice/video call screens, e.g. com.whatsapp.voipcalling.VoipActivityV2. */
    fun isCallScreen(screenName: String?) =
        screenName != null && (screenName.contains("voip", ignoreCase = true) || screenName.contains("calling", ignoreCase = true))

    /** Taps the "Chats" tab. Returns false if it couldn't be found. */
    fun openChatsTab(root: AccessibilityNodeInfo): Boolean {
        val label = root.findAccessibilityNodeInfosByText("Chats").firstOrNull { isLabel(it, "Chats") } ?: return false
        // The text itself often isn't tappable; its tab button (a parent) is.
        var node: AccessibilityNodeInfo? = label
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun isLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        val words = (node.text ?: node.contentDescription)?.toString() ?: return false
        return words == label || words.startsWith("$label ") || words.startsWith("$label,")
    }
}
