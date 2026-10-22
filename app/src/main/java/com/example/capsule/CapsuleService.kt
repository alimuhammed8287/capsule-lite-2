package com.example.capsule

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.widget.FrameLayout
import java.io.File
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences // NEW
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a black "capsule" around the front-camera cutout.
 * - collapsed: just covers the cutout
 * - expanded: shows media info + controls, or the latest notification
 * The NotificationListenerService is also what grants access to media sessions.
 */
class CapsuleService : NotificationListenerService() {

    private lateinit var uiCtx: Context
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: View? = null

    private lateinit var icon: ImageView
    private lateinit var textCol: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var chargeText: TextView

    // Charging state (filled by the battery receiver)
    private var charging = false
    private var chargePct = 0
    private var chargeDetail = ""

    private var miniW = 0
    private var miniH = 0
    private var expW = 0
    private var expH = 0
    private var expanded = false
    private var showingNotif = false
    private var notifIntent: PendingIntent? = null

    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    // NEW: remembers the last Maps text so we don't popup again for the same text
    private var lastMapsText = ""

    // NEW: the small storage box that holds your size/position settings
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("capsule", MODE_PRIVATE)
    }

    // NEW: runs when a slider saves a value, then rebuilds the capsule
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post {
            if (root == null) return@post
            root?.let { v -> runCatching { wm.removeView(v) } }
            root = null
            expanded = false
            buildOverlay()
            msm?.let { pickController(it.getActiveSessions(ComponentName(this, CapsuleService::class.java))) }
            refreshMedia()
        }
    }

    private val collapse = Runnable {
        showingNotif = false
        notifIntent = null
        refreshMedia()
        setExpanded(false)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMedia()
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING && !showingNotif) {
                peek(3000)
            }
        }

        override fun onSessionDestroyed() = pickController(null)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    // ---------- lifecycle ----------
    override fun onListenerConnected() {
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) return
        buildOverlay()
        registerBattery()
        prefs.registerOnSharedPreferenceChangeListener(prefListener) // NEW
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = ComponentName(this, CapsuleService::class.java)
        m.addOnActiveSessionsChangedListener(sessionsListener, cn)
        pickController(m.getActiveSessions(cn))
    }

    override fun onListenerDisconnected() = teardown()

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } // NEW
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null
        expanded = false
        showingNotif = false
    }

    // ---------- overlay ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Extra invisible touch margin around the pill, so taps near its edges register
    private fun touchPad() = dp(10)

    private fun buildOverlay() {
        val pad = touchPad()
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        uiCtx = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = uiCtx.getSystemService(WindowManager::class.java)

        val screenW = uiCtx.resources.displayMetrics.widthPixels
        val cut = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }

        // Size/position of the collapsed pill follows the real cutout when available.
        // NEW: minimum 100dp x 40dp so the pill is easier to tap
        miniW = maxOf(if (cut != null) cut.width() + dp(20) else dp(96), dp(100))
        miniH = maxOf(if (cut != null) cut.height() + dp(8) else dp(34), dp(40))
        var topY = if (cut != null) (cut.top - dp(4)).coerceAtLeast(0) else dp(6) // NEW: var
        expW = minOf(screenW - dp(24), dp(340))
        expH = dp(64)

        // NEW: saved settings override the automatic sizes
        if (prefs.contains("miniW")) miniW = dp(prefs.getInt("miniW", 100))
        if (prefs.contains("miniH")) miniH = dp(prefs.getInt("miniH", 40))
        if (prefs.contains("expW")) expW = minOf(screenW - dp(24), dp(prefs.getInt("expW", 340)))
        if (prefs.contains("topY")) topY = dp(prefs.getInt("topY", 6))

        val accent = uiCtx.getColor(android.R.color.system_accent1_200)

        val r = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(40).toFloat()
            }
            setOnClickListener { onCapsuleTap() }
        }

        icon = ImageView(uiCtx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        title = TextView(uiCtx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        subtitle = TextView(uiCtx).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

        textCol = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10); marginEnd = dp(6) }
            addView(title)
            addView(subtitle)
        }

        fun control(res: Int, onClick: () -> Unit) = ImageButton(uiCtx).apply {
            setImageResource(res)
            setColorFilter(accent)
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                onClick()
                peek(4000)
            }
        }

        btnPrev = control(android.R.drawable.ic_media_previous) {
            controller?.transportControls?.skipToPrevious()
        }

        btnPlay = control(android.R.drawable.ic_media_play) {
            val c = controller ?: return@control
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }

        btnNext = control(android.R.drawable.ic_media_next) {
            controller?.transportControls?.skipToNext()
        }

        chargeText = TextView(uiCtx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        r.addView(chargeText)
        r.addView(icon)
        r.addView(textCol)
        r.addView(btnPrev)
        r.addView(btnPlay)
        r.addView(btnNext)

        lp = WindowManager.LayoutParams(
            miniW + 2 * pad, miniH + 2 * pad,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (topY - pad).coerceAtLeast(0)
            x = dp(prefs.getInt("offX", 0)) // NEW: left/right shift
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val wrap = FrameLayout(uiCtx).apply {
            setOnClickListener { onCapsuleTap() }
            setOnTouchListener { _, e ->
                // Tap anywhere outside the window while expanded: collapse
                if (e.action == MotionEvent.ACTION_OUTSIDE && expanded) {
                    handler.removeCallbacks(collapse)
                    collapse.run()
                }
                false
            }
            addView(r, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(pad, pad, pad, pad) })
        }
        root = wrap
        applyVisibility()
        wm.addView(wrap, lp)
    }

    // ---------- state / animation ----------
    private fun applyVisibility() {
        val content = if (expanded) View.VISIBLE else View.GONE
        icon.visibility = content
        textCol.visibility = content
        chargeText.visibility = if (!expanded && chargeVisibleCollapsed()) View.VISIBLE else View.GONE
        chargeText.text = "\u26A1 $chargePct%"
        val buttons = if (expanded && !showingNotif && controller != null) View.VISIBLE else View.GONE
        btnPrev.visibility = buttons
        btnPlay.visibility = buttons
        btnNext.visibility = buttons
    }

    private fun setExpanded(e: Boolean) {
        val r = root ?: return
        if (e == expanded) {
            applyVisibility()
            return
        }
        expanded = e
        if (e) {
            icon.alpha = 0f
            textCol.alpha = 0f
        }
        applyVisibility()
        if (e) {
            icon.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
            textCol.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
        }

        val fromW = lp.width
        val fromH = lp.height
        val pad = touchPad()
        val toW = (if (e) expW else miniW) + 2 * pad
        val toH = (if (e) expH else miniH) + 2 * pad

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = OvershootInterpolator(0.6f)
            addUpdateListener {
                val f = it.animatedFraction
                lp.width = (fromW + (toW - fromW) * f).toInt()
                lp.height = (fromH + (toH - fromH) * f).toInt()
                if (r.isAttachedToWindow) wm.updateViewLayout(r, lp)
            }
            start()
        }
    }

    private fun peek(ms: Long) {
        setExpanded(true)
        handler.removeCallbacks(collapse)
        handler.postDelayed(collapse, maxOf(ms, 8000L)) // NEW: at least 8 seconds
    }

    private fun onCapsuleTap() {
        if (showingNotif) {
            runCatching { notifIntent?.send() }
            handler.removeCallbacks(collapse)
            collapse.run()
        } else if (controller != null || chargeVisibleCollapsed()) {
            if (expanded) {
                handler.removeCallbacks(collapse)
                collapse.run()
            } else {
                peek(6000)
            }
        }
    }

    // ---------- media ----------
    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        val next = if (!prefs.getBoolean("showMusic", true)) null
            else list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list?.firstOrNull()
        controller = next
        next?.registerCallback(controllerCallback)
        refreshMedia()
        if (next == null && !showingNotif) setExpanded(false)
    }

    private fun refreshMedia() {
        if (root == null) return
        val c = controller
        if (!showingNotif) {
            if (c == null) {
                if (charging && showCharging()) {
                    title.text = "$chargePct% \u00B7 Charging"
                    subtitle.text = chargeDetail
                } else {
                    title.text = ""
                    subtitle.text = ""
                }
                icon.setImageDrawable(null)
            } else {
                val md = c.metadata
                title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                subtitle.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (art != null) {
                    icon.setImageBitmap(art)
                } else {
                    icon.setImageDrawable(appIcon(c.packageName))
                }
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                btnPlay.setImageResource(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            }
        }
        applyVisibility()
    }

    private fun appIcon(pkg: String) =
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    // ---------- charging ----------

    private fun showCharging() = prefs.getBoolean("showCharging", true)

    private fun chargeVisibleCollapsed() =
        charging && showCharging() && controller == null && !showingNotif

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = handleBattery(intent)
    }

    private fun registerBattery() {
        val sticky = ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        sticky?.let { handleBattery(it) }
    }

    // Current in microamps, or null if no method gave a real (non-zero) value
    private fun readCurrentUa(): Int? {
        val bm = getSystemService(BatteryManager::class.java)
        val fromApi = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (fromApi != Int.MIN_VALUE && fromApi != 0) return fromApi
        val fromFile = runCatching {
            File("/sys/class/power_supply/battery/current_now").readText().trim().toInt()
        }.getOrNull()
        return if (fromFile != null && fromFile != 0) fromFile else null
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0

        charging = plugged != 0 &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        chargePct = level * 100 / scale

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Charging"
        }

        val parts = mutableListOf(source)
        val currentUa = readCurrentUa()
        if (currentUa != null) {
            val mA = Math.abs(currentUa) / 1000
            val watts = Math.abs(voltageMv.toDouble() * currentUa.toDouble()) / 1e9
            parts.add("$mA mA")
            parts.add("%.1f W".format(watts))
        }
        parts.add("%.2f V".format(voltageMv / 1000.0))
        parts.add("%.1f\u00B0C".format(tempC))
        chargeDetail = parts.joinToString(" \u00B7 ")

        refreshMedia()
    }

    // ---------- notifications ----------
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (root == null) return
        val n = sbn.notification

        // NEW: Maps navigation is "ongoing", so let it through
        val isMaps = sbn.packageName == "com.google.android.apps.maps"
        if ((sbn.isOngoing && !isMaps) || sbn.packageName == packageName) return

        // Settings toggles
        val debugMaps = prefs.getBoolean("debugMaps", false)
        if (isMaps && !prefs.getBoolean("showMaps", true)) return
        if (!isMaps && !prefs.getBoolean("showNotifs", true)) return

        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val t = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: return
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""

        // NEW: Maps updates every few seconds; only show when the text changes
        if (isMaps) {
            val key = "$t|$body"
            if (key == lastMapsText && !debugMaps) return
            lastMapsText = key
            if (debugMaps) android.util.Log.d("CapsuleMaps", "title=[$t] body=[$body]")
        }

        handler.post {
            showingNotif = true
            notifIntent = n.contentIntent
            title.text = if (isMaps && debugMaps) "DEBUG: $t" else t
            subtitle.text = body
            icon.setImageDrawable(appIcon(sbn.packageName))
            applyVisibility()
            peek(if (isMaps && debugMaps) 10000 else 4000)
        }
    }
}
