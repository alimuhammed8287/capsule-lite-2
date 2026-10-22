package com.example.capsule

object CapsulePrefs {
    const val MAPS_PKG = "com.google.android.apps.maps"

    val gestureKeys = listOf("g_tap", "g_double", "g_long", "g_up", "g_down", "g_left", "g_right")

    val gestureLabels = mapOf(
        "g_tap" to "Tap",
        "g_double" to "Double tap",
        "g_long" to "Long press",
        "g_up" to "Swipe up",
        "g_down" to "Swipe down",
        "g_left" to "Swipe left",
        "g_right" to "Swipe right"
    )

    val actions = listOf(
        "none", "expand", "play_pause", "next", "previous", "flashlight",
        "volume_up", "volume_down", "brightness_up", "brightness_down",
        "open_maps", "open_camera", "open_dialer"
    )

    val actionLabels = mapOf(
        "none" to "Nothing",
        "expand" to "Expand / collapse",
        "play_pause" to "Play / pause",
        "next" to "Next track",
        "previous" to "Previous track",
        "flashlight" to "Flashlight",
        "volume_up" to "Volume up",
        "volume_down" to "Volume down",
        "brightness_up" to "Brightness up",
        "brightness_down" to "Brightness down",
        "open_maps" to "Open Maps",
        "open_camera" to "Open camera",
        "open_dialer" to "Open dialer"
    )

    fun defaultFor(key: String): String = when (key) {
        "g_tap" -> "expand"
        "g_double" -> "play_pause"
        "g_long" -> "flashlight"
        "g_up" -> "volume_up"
        "g_down" -> "volume_down"
        "g_left" -> "previous"
        "g_right" -> "next"
        else -> "none"
    }
}
