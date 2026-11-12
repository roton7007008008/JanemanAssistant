package com.roton.janemanassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ১. ভিডিও স্ক্রল
    fun scrollDown() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val path = Path().apply {
            moveTo(w / 2, h * 0.75f)
            lineTo(w / 2, h * 0.25f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // ২. মেসেঞ্জারে যেকোনো ইনপুট বক্সে লেখা ও সেন্ড করা (বাংলা ও ইংরেজি উভয় সাপোর্ট)
    fun typeAndSendMessage(message: String) {
        val rootNode = rootInActiveWindow ?: return

        // যে কোনো লেখার বক্স খুঁজে বের করা
        val inputNode = findEditableNode(rootNode)

        inputNode?.let { node ->
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // সামান্য বিরতি দিয়ে সেন্ড বাটন টেপা
            Thread.sleep(700)
            val updatedRoot = rootInActiveWindow ?: rootNode
            clickSendButtonSmart(updatedRoot)
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findEditableNode(child)
            if (res != null) return res
        }
        return null
    }

    private fun clickSendButtonSmart(root: AccessibilityNodeInfo): Boolean {
        // 'Send', 'পাঠান' বা সেন্ড আইকন খোঁজা
        val sendWords = listOf("Send", "পাঠান", "সেন্ড", "send")
        for (word in sendWords) {
            val nodes = root.findAccessibilityNodeInfosByText(word)
            for (n in nodes) {
                if (n.isClickable) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }
        return findSendByDescription(root)
    }

    private fun findSendByDescription(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("send") || desc.contains("পাঠান")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findSendByDescription(child)) return true
        }
        return false
    }
}
