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

    // ১. টিকটক বা রিলস সোয়াইপ/স্ক্রল করার ফাংশন
    fun scrollDown() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val swipePath = Path().apply {
            moveTo(width / 2, height * 0.75f) // স্ক্রিনের নিচের দিকে শুরু
            lineTo(width / 2, height * 0.25f) // উপরের দিকে টানবে
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // ২. মেসেঞ্জারে মেসেজ টাইপ ও সেন্ড করার ফাংশন
    fun typeAndSendMessage(message: String) {
        val rootNode = rootInActiveWindow ?: return

        // মেসেঞ্জারের ইনপুট বক্স খুঁজে বের করা
        val editTexts = rootNode.findAccessibilityNodeInfosByViewId("com.facebook.orca:id/text_input_bar")
        var inputNode: AccessibilityNodeInfo? = if (editTexts.isNotEmpty()) editTexts[0] else null

        if (inputNode == null) {
            // আইডি না পেলে ক্লাস নাম দিয়ে খোঁজা
            inputNode = findNodeByClassName(rootNode, "android.widget.EditText")
        }

        inputNode?.let {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            // কিছুক্ষণ পর সেন্ড বাটনে ক্লিক করা
            Thread.sleep(600)
            findAndClickSendButton(rootNode)
        }
    }

    private fun findAndClickSendButton(rootNode: AccessibilityNodeInfo) {
        // সেন্ড বাটন খোঁজা
        val sendNodes = rootNode.findAccessibilityNodeInfosByText("Send")
        for (node in sendNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        // আইকন বা ডেসক্রিপশন দিয়ে খোঁজা
        findClickableSend(rootNode)
    }

    private fun findClickableSend(node: AccessibilityNodeInfo): Boolean {
        if (node.contentDescription?.toString()?.contains("Send", ignoreCase = true) == true) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findClickableSend(child)) return true
        }
        return false
    }

    private fun findNodeByClassName(node: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (node.className?.toString() == className) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByClassName(child, className)
            if (found != null) return found
        }
        return null
    }
}
