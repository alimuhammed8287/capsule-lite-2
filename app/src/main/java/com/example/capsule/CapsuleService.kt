package com.example.capsule

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.abs

class CapsuleService : NotificationListenerService() {

    private lateinit var uiCtx: Context
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams

    private var root: View? = null          // transparent window view (includes the tap margin)
    private var pill: LinearLayout? = null  // the black pill inside it

    private lateinit var miniText: TextView
    private lateinit var icon: ImageView
    private lateinit var textCol: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnAnswer: ImageButton
    private lateinit var btnDecline: ImageButton
    private var chips: List<TextView> = emptyList()

    private var miniW = 0
    private var miniH = 0
    private var expW = 0
    private var expH = 0

    private var expanded = false
    private var showingNotif = false
    private var notifIntent: PendingIntent? = null
    private var notifKey: String? = null
    private var replyAction: Notification.Action? = null

    // Incoming or ongoing call (notification based)
    private var callActive = false
    private var callKey: String? = null
    private var callAnswer: Notification.Action? = null
    private var callHangup: Notification.Action? = null
    private var callTitle = ""
    private var callSubtitle = ""

    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastMapsText = ""
    private var torchOn = false

    // Charging state, filled by the battery receiver
    private var charging = false
    private var chargePct = 0
    private var chargeDetail = ""

    private val prefs by lazy { getSharedPreferences("capsule", MODE_PRIVATE) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post {
            if (root == null) return@post
            root?.let { v -> runCatching { wm.removeView(v) } }
            root = null
            pill = null
            expanded = callActive
            buildOverlay()
            msm?.let { pickController(it.getActiveSessions(ComponentName(this, CapsuleService::class.java))) }
            refreshMedia()
        }
    }

    private val collapse = Runnable {
        if (callActive) {
            refreshMedia()   // a call keeps the pill open
        } else {
            showingNotif = false
            notifIntent = null
            notifKey = null
            replyAction = null
            refreshMedia()
            setExpanded(false)
        }
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

    // Gestures: tap, double tap, long press, and swipes. Each one runs the action chosen in settings.
    private val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // A tap on a notification opens it, as before
            if (showingNotif && notifIntent != null && !callActive) {
                runCatching { notifIntent?.send() }
                handler.removeCallbacks(collapse)
                collapse.run()
            } else {
                dispatchGesture("g_tap")
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            dispatchGesture("g_double")
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dispatchGesture("g_long")
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            val dy = e2.y - start.y
            if (abs(dx) < dp(30) && abs(dy) < dp(30)) return false
            val key = if (abs(dx) > abs(dy)) {
                if (dx > 0) "g_right" else "g_left"
            } else {
                if (dy > 0) "g_down" else "g_up"
            }
            dispatchGesture(key)
            return true
        }
    })

    // ---------- lifecycle ----------

    override fun onListenerConnected() {
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) return
        buildOverlay()
        registerBattery()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = ComponentName(this, CapsuleService::class.java)
        m.addOnActiveSessionsChangedListener(sessionsListener, cn)
        pickController(m.getActiveSessions(cn))
    }

    override fun onListenerDisconnected() {
        teardown()
        // Ask Android to bring the listener back, so the pill doesn't stay gone
        NotificationListenerService.requestRebind(ComponentName(this, CapsuleService::class.java))
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        root?.let { v -> runCatching { wm.removeView(v) } }
        root = null
        pill = null
        expanded = false
        showingNotif = false
        callActive = false
        callKey = null
    }

    // ---------- sizes and overlay ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Extra invisible touch margin around the pill, so taps near its edges register
    private fun touchPad() = dp(10)

    private fun buildOverlay() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        uiCtx = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        wm = uiCtx.getSystemService(WindowManager::class.java)

        val pad = touchPad()
        val screenW = uiCtx.resources.displayMetrics.widthPixels
        val cut = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }

        miniW = if (cut != null) cut.width() + dp(20) else dp(96)
        miniH = maxOf(if (cut != null) cut.height() + dp(8) else dp(34), dp(40))
        var topY = if (cut != null) (cut.top - dp(4)).coerceAtLeast(0) else dp(6)
        expW = minOf(screenW - dp(24), dp(340))
        expH = dp(64)

        // Saved settings override the automatic sizes
        if (prefs.contains("miniW")) miniW = dp(prefs.getInt("miniW", 100))
        if (prefs.contains("miniH")) miniH = dp(prefs.getInt("miniH", 40))
        if (prefs.contains("expW")) expW = minOf(screenW - dp(24), dp(prefs.getInt("expW", 340)))
        if (prefs.contains("topY")) topY = dp(prefs.getInt("topY", 6))
        val offX = dp(prefs.getInt("offX", 0))

        val accent = uiCtx.getColor(android.R.color.system_accent1_200)

        val row = LinearLayout(uiCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(40).toFloat()
            }
            setOnTouchListener { _, e -> onPillTouch(e) }
        }

        miniText = TextView(uiCtx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        icon = ImageView(uiCtx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(12) }
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

        btnPrev = control(android.R.drawable.ic_media_previous, accent) {
            controller?.transportControls?.skipToPrevious()
        }
        btnPlay = control(android.R.drawable.ic_media_play, accent) { togglePlay() }
        btnNext = control(android.R.drawable.ic_media_next, accent) {
            controller?.transportControls?.skipToNext()
        }
        btnAnswer = control(android.R.drawable.ic_menu_call, Color.parseColor("#34C759")) {
            runCatching { callAnswer?.actionIntent?.send() }
        }
        btnDecline = control(android.R.drawable.ic_menu_close_clear_cancel, Color.parseColor("#FF3B30")) {
            runCatching { callHangup?.actionIntent?.send() }
        }

        // Quick reply chips for message notifications that accept a reply
        chips = listOf("Yes", "Later", "Busy").map { text ->
            TextView(uiCtx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                isSingleLine = true
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    setColor(0xFF333333.toInt())
                    cornerRadius = dp(14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
                setOnClickListener { sendReply(text) }
            }
        }

        row.addView(miniText)
        row.addView(icon)
        row.addView(textCol)
        row.addView(btnPrev)
        row.addView(btnPlay)
        row.addView(btnNext)
        chips.forEach { row.addView(it) }
        row.addView(btnDecline)
        row.addView(btnAnswer)

        val wrap = FrameLayout(uiCtx).apply {
            setOnTouchListener { _, e -> onPillTouch(e) }
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { setMargins(pad, pad, pad, pad) }
            )
        }

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
            x = offX
            y = (topY - pad).coerceAtLeast(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (expanded) {
            lp.width = expW + 2 * pad
            lp.height = expH + 2 * pad
        }

        pill = row
        root = wrap
        applyVisibility()
        wm.addView(wrap, lp)
    }

    private fun control(res: Int, tint: Int, onClick: () -> Unit) = ImageButton(uiCtx).apply {
        setImageResource(res)
        setColorFilter(tint)
        background = null
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener {
            onClick()
            peek(4000)
        }
    }

    // ---------- touch and gestures ----------

    private fun onPillTouch(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_OUTSIDE) {
            // Tap anywhere outside the window while expanded: collapse
            if (expanded && !callActive) {
                handler.removeCallbacks(collapse)
                collapse.run()
            }
            return false
        }
        return gestures.onTouchEvent(e)
    }

    private fun dispatchGesture(key: String) {
        val action = prefs.getString(key, CapsulePrefs.defaultFor(key)) ?: "none"
        runAction(action)
    }

    private fun runAction(action: String) {
        when (action) {
            "expand" -> if (expanded) {
                handler.removeCallbacks(collapse)
                collapse.run()
            } else {
                peek(6000)
            }
            "play_pause" -> togglePlay()
            "next" -> controller?.transportControls?.skipToNext()
            "previous" -> controller?.transportControls?.skipToPrevious()
            "flashlight" -> toggleTorch()
            "volume_up" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "brightness_up" -> adjustBrightness(25)
            "brightness_down" -> adjustBrightness(-25)
            "open_maps" -> launch(packageManager.getLaunchIntentForPackage(CapsulePrefs.MAPS_PKG))
            "open_camera" -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "open_dialer" -> launch(Intent(Intent.ACTION_DIAL))
        }
    }

    private fun audio() = getSystemService(AudioManager::class.java)

    private fun launch(i: Intent?) {
        if (i == null) return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    private fun toggleTorch() {
        val cam = getSystemService(CameraManager::class.java)
        runCatching {
            val id = cam.cameraIdList.firstOrNull {
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cam.setTorchMode(id, torchOn)
        }
    }

    private fun adjustBrightness(delta: Int) {
        if (!Settings.System.canWrite(this)) return   // needs the brightness permission in settings
        runCatching {
            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (cur + delta).coerceIn(0, 255))
        }
    }

    // ---------- expand / collapse ----------

    private fun peek(ms: Long) {
        setExpanded(true)
        handler.removeCallbacks(collapse)
        if (!callActive) handler.postDelayed(collapse, ms)
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

        val pad = touchPad()
        val fromW = lp.width
        val fromH = lp.height
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

    // ---------- what the pill shows ----------

    private fun showCharging() = prefs.getBoolean("showCharging", true)
    private fun showMusic() = prefs.getBoolean("showMusic", true)

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun applyVisibility() {
        if (pill == null) return
        icon.visibility = vis(expanded)
        textCol.visibility = vis(expanded)

        val chargeMini = !callActive && !showingNotif && controller == null && charging && showCharging()
        miniText.visibility = vis(!expanded && (callActive || chargeMini))
        miniText.text = if (callActive) "Incoming" else "\u26A1 $chargePct%"

        val mediaOn = expanded && !callActive && !showingNotif && controller != null && showMusic()
        btnPrev.visibility = vis(mediaOn)
        btnPlay.visibility = vis(mediaOn)
        btnNext.visibility = vis(mediaOn)

        val replyOn = expanded && !callActive && showingNotif && replyAction != null
        chips.forEach { it.visibility = vis(replyOn) }

        btnAnswer.visibility = vis(expanded && callActive && callAnswer != null)
        btnDecline.visibility = vis(expanded && callActive && callHangup != null)
    }

    private fun refreshMedia() {
        if (pill == null) return
        if (callActive) {
            title.text = callTitle
            subtitle.text = callSubtitle
        } else if (!showingNotif) {
            renderMedia()
        }
        applyVisibility()
    }

    private fun renderMedia() {
        val c = if (showMusic()) controller else null
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

    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        val next = if (!showMusic()) null else (
            list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list?.firstOrNull()
            )
        controller = next
        next?.registerCallback(controllerCallback)
        refreshMedia()
        if (next == null && !showingNotif && !callActive) setExpanded(false)
    }

    private fun appIcon(pkg: String) =
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()

    // ---------- calls ----------

    private fun showCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val actions: Array<Notification.Action> = n.actions ?: emptyArray()
        callKey = sbn.key
        callActive = true
        if (actions.size >= 2) {
            callAnswer = actions[0]
            callHangup = actions[1]
        } else {
            callAnswer = null
            callHangup = actions.firstOrNull()
        }
        callTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Call"
        callSubtitle = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "Incoming call"
        notifIntent = n.contentIntent
        showingNotif = false
        icon.setImageDrawable(appIcon(sbn.packageName))
        handler.removeCallbacks(collapse)
        setExpanded(true)
        refreshMedia()
    }

    // ---------- notifications ----------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (root == null) return
        if (sbn.packageName == packageName) return
        val n = sbn.notification

        // Calls come first and always show
        if (n.category == Notification.CATEGORY_CALL) {
            handler.post { showCall(sbn) }
            return
        }

        val isMaps = sbn.packageName == CapsulePrefs.MAPS_PKG
        if (sbn.isOngoing && !isMaps) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (isMaps && !prefs.getBoolean("showMaps", true)) return
        if (!isMaps && !prefs.getBoolean("showNotifs", true)) return

        val t = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: return
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""

        val debugMaps = prefs.getBoolean("debugMaps", false)
        if (isMaps) {
            val key = "$t|$body"
            if (key == lastMapsText && !debugMaps) return
            lastMapsText = key
            if (debugMaps) android.util.Log.d("CapsuleMaps", "title=[$t] body=[$body]")
        }

        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }

        handler.post {
            if (callActive) return@post   // a call always stays on top
            showingNotif = true
            notifIntent = n.contentIntent
            notifKey = sbn.key
            replyAction = reply
            title.text = if (isMaps && debugMaps) "DEBUG: $t" else t
            subtitle.text = body
            icon.setImageDrawable(appIcon(sbn.packageName))
            refreshMedia()
            val ms = when {
                isMaps && debugMaps -> 10000L
                reply != null -> 8000L
                else -> 4000L
            }
            peek(ms)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        handler.post {
            if (sbn.key == callKey) {
                callActive = false
                callKey = null
                callAnswer = null
                callHangup = null
                handler.removeCallbacks(collapse)
                collapse.run()
            }
            if (sbn.key == notifKey) {
                notifKey = null
                replyAction = null
                if (!callActive && showingNotif) {
                    handler.removeCallbacks(collapse)
                    collapse.run()
                }
            }
        }
    }

    private fun sendReply(text: String) {
        val action = replyAction ?: return
        val ri = action.remoteInputs?.firstOrNull() ?: return
        val intent = Intent()
        val bundle = Bundle().apply { putCharSequence(ri.resultKey, text) }
        RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
        runCatching { action.actionIntent.send(this, 0, intent) }
        handler.removeCallbacks(collapse)
        collapse.run()
    }

    // ---------- charging ----------

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
}
