package com.example.capsule

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var diagText: TextView
    private val prefs by lazy { getSharedPreferences("capsule", MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        col.addView(status)

        header(col, "Diagnostics (latest service events)")
        diagText = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        col.addView(diagText)
        button(col, "Clear diagnostics") {
            prefs.edit().remove("diag").apply()
            diagText.text = ""
        }

        header(col, "Permissions")
        button(col, "1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(col, "2. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button(col, "3. Keep-alive: turn on Capsule Lite in Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(col, "4. App info (battery: No restrictions, Autostart)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button(col, "5. Allow brightness control (for brightness gestures)") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }

        header(col, "Show in the pill")
        toggle(col, "Music", "showMusic", true)
        toggle(col, "Google Maps navigation", "showMaps", true)
        toggle(col, "Other notifications", "showNotifs", true)
        toggle(col, "Show charging status", "showCharging", true)
        toggle(col, "Debug: show raw Maps text", "debugMaps", false)

        header(col, "Gestures (tap a row to change its action)")
        for (key in CapsulePrefs.gestureKeys) gestureButton(col, key)

        header(col, "Size and position")
        slider(col, "Collapsed width", "miniW", 40, 200, 100)
        slider(col, "Collapsed height", "miniH", 20, 60, 40)
        slider(col, "Expanded width", "expW", 150, 360, 340)
        slider(col, "Vertical position", "topY", 0, 40, 6)
        slider(col, "Horizontal shift", "offX", -100, 100, 0)

        button(col, "Reset to defaults") {
            prefs.edit().clear().apply()
            recreate()
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun header(col: LinearLayout, text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(24), 0, dp(4))
        })
    }

    private fun button(col: LinearLayout, label: String, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun toggle(col: LinearLayout, label: String, key: String, default: Boolean) {
        col.addView(Switch(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        })
    }

    // Each tap moves the gesture to the next action in the list
    private fun gestureButton(col: LinearLayout, key: String) {
        fun label(action: String) =
            "${CapsulePrefs.gestureLabels[key]}: ${CapsulePrefs.actionLabels[action] ?: action}"

        val current = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
        val b = Button(this).apply { text = label(current) }
        b.setOnClickListener {
            val cur = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
            val idx = CapsulePrefs.actions.indexOf(cur)
            val next = CapsulePrefs.actions[(idx + 1) % CapsulePrefs.actions.size]
            prefs.edit().putString(key, next).apply()
            b.text = label(next)
        }
        col.addView(b)
    }

    private fun slider(col: LinearLayout, label: String, key: String, lo: Int, hi: Int, default: Int) {
        val start = prefs.getInt(key, default)
        val caption = TextView(this)
        caption.text = "$label: $start"
        col.addView(caption)
        col.addView(SeekBar(this).apply {
            max = hi - lo
            progress = start - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    caption.text = "$label: ${p + lo}"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putInt(key, sb.progress + lo).apply()
                }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val listener = Settings.Secure
            .getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val keepAlive = Settings.Secure
            .getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        val brightness = Settings.System.canWrite(this)

        status.text = "Overlay: ${if (overlay) "OK" else "missing"}\n" +
            "Notification access: ${if (listener) "OK" else "missing"}\n" +
            "Keep-alive (Accessibility): ${if (keepAlive) "OK" else "off"}\n" +
            "Brightness control: ${if (brightness) "OK" else "off"}"

        diagText.text = prefs.getString("diag", null) ?: "no service events yet"

        if (overlay && listener) {
            NotificationListenerService.requestRebind(
                ComponentName(this, CapsuleService::class.java)
            )
        }
    }
}
