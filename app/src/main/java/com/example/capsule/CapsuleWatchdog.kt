package com.example.capsule

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent

// Keep-alive only. It listens to no events and reads no screen content.
// Being an enabled accessibility service keeps the app process alive,
// and on connect it asks Android to rebind the notification listener.
class CapsuleWatchdog : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        NotificationListenerService.requestRebind(ComponentName(this, CapsuleService::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
