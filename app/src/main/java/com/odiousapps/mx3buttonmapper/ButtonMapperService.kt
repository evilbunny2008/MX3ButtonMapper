package com.odiousapps.mx3buttonmapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Listens system-wide for a set of hardware scancodes. Each one is either
 * remapped to a replacement keycode (via KeyInjector/Shizuku) or used to
 * directly launch another app -- whichever SCANCODE_TO_* map it's in.
 */
@SuppressLint("AccessibilityPolicy")
class ButtonMapperService : AccessibilityService() {

    companion object {
        private const val TAG = "ButtonMapperService"

        // scanCode -> replacement keyCode. Requires Shizuku/root (see
        // KeyInjector.kt) since it synthesises a new input event.
        private val SCANCODE_TO_KEYCODE: Map<Int, Int> = mapOf(
            108 to KeyEvent.KEYCODE_DPAD_DOWN,    // 20
            105 to KeyEvent.KEYCODE_DPAD_LEFT,     // 21
            106 to KeyEvent.KEYCODE_DPAD_RIGHT,    // 22
            103 to KeyEvent.KEYCODE_DPAD_UP,       // 19
            28 to KeyEvent.KEYCODE_DPAD_CENTER,    // 23
            // TCL-proprietary keycode, not a standard KeyEvent constant --
            // this TV expects 4001 specifically for its TV/source button,
            // confirmed by testing the TCL's own remote directly and
            // reading back its scancode/keycode via logcat. Previously
            // mapped to KeyEvent.KEYCODE_GUIDE (172), which worked with
            // the prior TV but does nothing useful on this one.
            419 to 4001,
            // MX3 Air Mouse's Menu button. Unmapped previously, which
            // meant it passed through untouched to whatever this
            // TCL TV's own default handling does for its keycode
            // (4514, another non-standard TCL-proprietary value) --
            // opens the TV's own native settings bar instead of
            // reaching MX3 Launcher's Menu-key App Info feature, which
            // specifically listens for KEYCODE_MENU.
            127 to KeyEvent.KEYCODE_MENU,          // 82
            171 to KeyEvent.KEYCODE_EISU,          // 212
            // 60 moved to SCANCODE_TO_REPEATED_KEYCODE below (sends
            // volume down x5 instead of a single KEYCODE_NOTIFICATION press)
            // 104/109 moved to SCANCODE_TO_KEYCODE_FOREGROUND_SCOPED below
            // (only remapped while a specific TV app is in the foreground)
            // add more scanCode -> keyCode pairs here as needed
        )

        // Confirmed via mCurrentFocus while live TV was actually on
        // screen (com.mediatek.wwtv.tvcenter.nav.TurnkeyUiMainActivity).
        // If this ever needs re-confirming on a different device, the
        // same command works generally:
        //   adb shell dumpsys window | grep mCurrentFocus
        // The part before the "/" in the output is the package name.
        private const val TV_APP_PACKAGE = "com.mediatek.wwtv.tvcenter" // TV Center, confirmed via mCurrentFocus

        // scanCode -> (replacement keyCode, package that must currently be
        // in the foreground for the remap to apply). Outside that
        // foreground app, the scancode passes through untouched -- same
        // as any other unmapped key. Tracked via onAccessibilityEvent()
        // below, not onKeyEvent() -- key events don't carry foreground-app
        // info themselves, window-state-change events do.
        // scanCode -> a remap that depends on which app currently has
        // foreground focus. Rather than just "pass the raw hardware
        // keycode through unmapped" outside the TV app, this explicitly
        // remaps to PAGE_UP/PAGE_DOWN too -- more robust than relying on
        // an assumption about what the raw keycode happens to be, and
        // consistent with how every other mapping in this file works
        // (explicit consume + inject, not implicit pass-through).
        private data class ForegroundScopedRemap(
            val inForegroundKeyCode: Int,
            val inForegroundRepeatTimes: Int = 1,
            val outsideForegroundKeyCode: Int,
            val outsideForegroundRepeatTimes: Int = 1,
            val requiredForegroundPackage: String,
        )

        private val SCANCODE_TO_KEYCODE_FOREGROUND_SCOPED: Map<Int, ForegroundScopedRemap> = mapOf(
            // MX3 remote sends PAGE_UP/PAGE_DOWN (keycodes 92/93) for its
            // Channel Up/Down buttons. Inside the TV app, remapped to the
            // actual channel keycodes a real TV remote sends. Outside
            // it, PAGE_UP/PAGE_DOWN themselves turned out not to be
            // useful -- most apps (including YouTube) only guarantee
            // handling D-pad/Back/Home per Android TV's own app
            // guidelines, so Page Up/Down is silently ignored everywhere
            // it was tried. Sends a burst of DPAD_UP/DOWN instead, to
            // jump multiple items in a list with one press (e.g. moving
            // through a long playlist) -- DPAD is part of the mandatory
            // baseline every TV app must support, so this works
            // anywhere, unlike Page Up/Down.
            104 to ForegroundScopedRemap(
                inForegroundKeyCode = KeyEvent.KEYCODE_CHANNEL_UP,      // 166
                outsideForegroundKeyCode = KeyEvent.KEYCODE_DPAD_UP,    // 19
                outsideForegroundRepeatTimes = 5,
                requiredForegroundPackage = TV_APP_PACKAGE,
            ),
            109 to ForegroundScopedRemap(
                inForegroundKeyCode = KeyEvent.KEYCODE_CHANNEL_DOWN,    // 167
                outsideForegroundKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,  // 20
                outsideForegroundRepeatTimes = 5,
                requiredForegroundPackage = TV_APP_PACKAGE,
            ),
        )

        // Named separately (not just inline in the map below) so it can
        // also be referenced for the boot-time auto-launch in
        // onServiceConnected(), without hardcoding the same string twice.
        private const val LAUNCHER_PACKAGE = "com.odiousapps.mx3launcher"

        // scanCode -> package to launch. No Shizuku/root needed -- this is
        // just starting an activity, not injecting synthetic input.
        private val SCANCODE_TO_APP_PACKAGE: Map<Int, String> = mapOf(
            172 to LAUNCHER_PACKAGE, // MX3 Launcher
            418 to "app.smarttube.fdroid", // SmartTube (F-Droid build)
            150 to "com.phlox.tvwebbrowser", // TV Bro browser
        )

        // scanCode -> (keyCode to inject, how many times). For buttons that
        // should fire the same keycode several times per press rather than
        // once -- e.g. a dedicated "big volume jump" button.
        private val SCANCODE_TO_REPEATED_KEYCODE: Map<Int, Pair<Int, Int>> = mapOf(
            60 to Pair(KeyEvent.KEYCODE_VOLUME_DOWN, 3),
            155 to Pair(KeyEvent.KEYCODE_VOLUME_UP, 3),
        )

        // Spacing between each injected press in a repeated-keycode burst.
        // Too fast and some apps/AudioManager's own volume UI can coalesce
        // rapid presses into fewer visible steps; this keeps each one
        // distinct.
        private const val REPEATED_SEND_INTERVAL_MS = 60L

        // Separate, longer interval specifically for the DPAD_UP/DOWN
        // burst below -- 60ms (tuned for the volume overlay, a
        // lightweight UI update) was too fast for YouTube's focus-move
        // animation between list items: injected presses arriving faster
        // than that animation finishes get silently dropped by YouTube's
        // own UI, not lost in our injection pipeline. A press sending 5
        // only registering ~2 moves is exactly that symptom. This is a
        // starting point, not a measured value -- tune against the
        // actual device/app if it still drops presses or feels sluggish.
        private const val FOCUS_NAVIGATION_BURST_INTERVAL_MS = 220L

        // Minimum time between actually launching an app, regardless of how
        // many key events arrive. App-launch is much heavier than a
        // synthetic keycode injection (new Activity, new window/surface),
        // so this gets its own, more generous debounce.
        private const val APP_LAUNCH_DEBOUNCE_MS = 800L

        // Confirmed via logcat: once this accessibility service is
        // enabled, native key-repeat generation stops reaching
        // onKeyEvent() entirely -- for EVERY key, not just mapped ones.
        // Disabling the service in Settings -> Accessibility immediately
        // restored normal repeat-on-hold for untouched keys (volume),
        // and re-enabling it broke it again just as fast. This is an
        // inherent side effect of FLAG_REQUEST_FILTER_KEY_EVENTS
        // intercepting the raw input stream before whatever stage
        // normally generates repeat pulses -- not a bug in this file's
        // own mapping logic. The fix is a synthetic repeat mechanism WE
        // drive ourselves (below), since there's nothing arriving on its
        // own for us to react to during a hold.
        //
        // These are reasonable starting values, not measured against
        // this specific device's original native repeat timing -- tune
        // if held keys feel too eager or too sluggish compared to how
        // they behaved before this service existed.
        private const val SYNTHETIC_REPEAT_INITIAL_DELAY_MS = 400L
        private const val SYNTHETIC_REPEAT_INTERVAL_MS = 80L

        // Packages whose window briefly gaining focus should NOT be
        // treated as a real foreground-app change -- confirmed via
        // logcat: pressing a button while genuinely looking at TV Center
        // showed foreground=com.android.systemui at that exact moment,
        // meaning some transient SystemUI-owned overlay (status bar,
        // volume HUD, various system popups) briefly stole window focus
        // and got treated as if it were a real app switch, overwriting
        // the tracked foreground app even though the user never actually
        // left TV Center. Extend this set if another transient-overlay
        // package is discovered causing the same symptom.
        private val TRANSIENT_OVERLAY_PACKAGES = setOf(
            "com.android.systemui",
        )
    }

    @Volatile
    private var lastAppLaunchAtMs = 0L
    private val repeatedSendHandler = Handler(Looper.getMainLooper())

    // Tracks the active synthetic-repeat Runnable per scancode, so
    // ACTION_UP can cancel the RIGHT one (more than one key could
    // theoretically be held at once) rather than cancelling everything.
    private val activeSyntheticRepeats = mutableMapOf<Int, Runnable>()

    // Updated by onAccessibilityEvent() below, read by onKeyEvent() to
    // decide whether a foreground-scoped remap should apply. Volatile
    // since accessibility events and key events aren't guaranteed to
    // arrive on the exact same thread.
    @Volatile
    private var currentForegroundPackage: String? = null

    private var autoBindAttempts = 0

    // Retries the auto-bind for a few minutes rather than trying once --
    // Shizuku's own boot sequence (wait for Wi-Fi, reconnect wireless
    // debugging, start its server) can take a while, and if the
    // accessibility service happens to start before that finishes, a
    // single attempt just fails silently with no automatic recovery.
    // 10 attempts at 20s apart covers roughly 3+ minutes post-boot.
    private val autoBindHandler = Handler(Looper.getMainLooper())
    private val maxAutoBindAttempts = 10
    private val autoBindRetryIntervalMs = 20_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // You can also set these flags programmatically instead of (or in addition to)
        // the XML config, which is useful if you want to toggle filtering at runtime.
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.i(TAG, "Accessibility service connected -- " +
            "${SCANCODE_TO_KEYCODE.size} keycode mapping(s), " +
            "${SCANCODE_TO_KEYCODE_FOREGROUND_SCOPED.size} foreground-scoped mapping(s), " +
            "${SCANCODE_TO_APP_PACKAGE.size} app-launch mapping(s)")

        // onServiceConnected() fires automatically whenever an ENABLED
        // accessibility service starts, including right after boot --
        // Android restarts every enabled a11y service on its own, no boot
        // receiver or extra permission needed. This is almost certainly
        // the same mechanism AT4K piggybacks on to relaunch itself at
        // startup. Using it here to auto-recover the Shizuku binding,
        // since that currently only happens when a person manually opens
        // MainActivity -- without this, key injection and
        // enableAccessibilityService stay dead after every reboot until
        // someone opens the app once, even though the button-remapping
        // itself (this service) already auto-starts fine on its own.
        attemptAutoBind()

        // Explicitly launch the mapped launcher app on startup too --
        // separate from (and in addition to) whatever app is set as the
        // device's default Home app. If MX3 Launcher is already the
        // default Home app, Android would show it on boot regardless of
        // this call; this exists for the case where that isn't set, or
        // just as a deliberate guarantee rather than relying on that
        // setting alone.
        //
        // IMPORTANT CAVEAT: unlike the Home-button-triggered launch (see
        // the comment inside launchApp()'s catch block), THIS call has no
        // real user-interaction backing it -- onServiceConnected() is a
        // system lifecycle callback, not something tied to a hardware
        // key event. Android's background-activity-launch restrictions
        // specifically exist to block exactly this pattern -- a
        // background context starting an activity with no user gesture
        // behind it. So this is a best-effort attempt that may simply
        // get blocked with a SecurityException on some OS versions/OEM
        // builds, not a guarantee. If it doesn't work in testing, the reliable
        // fallback is the same notification/PendingIntent pattern
        // SetupNotifier.kt already uses -- tapping a notification is
        // always permitted regardless of BAL restrictions.
        launchApp(LAUNCHER_PACKAGE)
    }

    private fun attemptAutoBind() {
        if (ShizukuUserServiceBridge.isConnected()) return // already succeeded
        if (autoBindAttempts >= maxAutoBindAttempts) {
            Log.w(TAG, "Giving up on auto-bind after $autoBindAttempts attempts -- " +
                "Shizuku still isn't up. Falling back to the normal not-ready prompt on first button press.")
            return
        }
        autoBindAttempts++
        try {
            Log.i(TAG, "Attempting to auto-bind Shizuku UserService (attempt $autoBindAttempts/$maxAutoBindAttempts)")
            ShizukuUserServiceBridge.bind()
        } catch (e: Throwable) {
            // Non-fatal -- most likely Shizuku's own server isn't running
            // yet. Scheduled retry below covers this regardless of
            // whether bind() itself throws or just silently doesn't
            // connect (checked via isConnected() at the top of the next
            // attempt).
            Log.w(TAG, "Auto-bind attempt $autoBindAttempts failed", e)
        }
        autoBindHandler.postDelayed({ attemptAutoBind() }, autoBindRetryIntervalMs)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "keyCode=${event.keyCode} scanCode=${event.scanCode} repeatCount=${event.repeatCount}")

        // event.repeatCount > 0 means this is an auto-repeat pulse from
        // holding the key down, not a fresh press. This only matters for
        // the app-launch path below. Launching a full TV-launcher window
        // repeatedly while a button is held is what caused the
        // stuck-fence GPU-hang ANR. That path skips repeats, and is also
        // debounced in launchApp() as a second line of defence. DPAD-style keycode
        // remaps are cheap synthetic input events and are expected to keep
        // firing for as long as the button is held, same as a real D-pad
        // would -- so that path intentionally does NOT filter repeats.
        val isRepeat = event.repeatCount > 0

        SCANCODE_TO_APP_PACKAGE[event.scanCode]?.let { packageName ->
            if (event.action == KeyEvent.ACTION_DOWN && !isRepeat) {
                Log.i(TAG, "Captured scancode ${event.scanCode}, launching $packageName")
                launchApp(packageName)
            }
            // Consume regardless of whether the launch actually succeeded --
            // if it failed (app not installed, etc.) we don't want the
            // original button behaviour firing on top of a failed attempt.
            return true
        }

        SCANCODE_TO_REPEATED_KEYCODE[event.scanCode]?.let { (keyCode, times) ->
            if (!KeyInjector.isReady()) {
                if (event.action == KeyEvent.ACTION_DOWN && !isRepeat) {
                    Log.w(TAG, "Injection not ready, letting scancode ${event.scanCode} pass through untouched")
                    SetupNotifier.promptIfNeeded(this)
                }
                return super.onKeyEvent(event)
            }

            // Fires once per fresh press, not on every auto-repeat pulse --
            // holding the button shouldn't rapid-fire this repeatedly on
            // top of its own internal x5 burst.
            if (event.action == KeyEvent.ACTION_DOWN && !isRepeat) {
                Log.i(TAG, "Captured scancode ${event.scanCode}, sending keycode $keyCode x$times")
                sendRepeatedKeyEvent(keyCode, times)
            }
            return true
        }

        SCANCODE_TO_KEYCODE_FOREGROUND_SCOPED[event.scanCode]?.let { remap ->
            val inForeground = currentForegroundPackage == remap.requiredForegroundPackage
            val targetKeyCode = if (inForeground) remap.inForegroundKeyCode else remap.outsideForegroundKeyCode
            val targetRepeatTimes = if (inForeground) remap.inForegroundRepeatTimes else remap.outsideForegroundRepeatTimes

            if (!KeyInjector.isReady()) {
                if (event.action == KeyEvent.ACTION_DOWN && !isRepeat) {
                    Log.w(TAG, "Injection not ready, letting scancode ${event.scanCode} pass through untouched")
                    SetupNotifier.promptIfNeeded(this)
                }
                return super.onKeyEvent(event)
            }

            // A burst (repeatTimes > 1) fires once per fresh press only --
            // re-triggering a whole burst on every auto-repeat pulse
            // while the button is held would compound rapidly (multiple
            // 5-item jumps stacking every ~50ms). A single-press remap
            // (repeatTimes == 1, e.g. CHANNEL_UP/DOWN) keeps the existing
            // continuous-fire-on-hold behaviour, matching normal D-pad
            // held-button semantics -- now via our own synthetic-repeat
            // timer specifically for that subcase, since native repeat
            // pulses never arrive once this service is enabled (see
            // SYNTHETIC_REPEAT_INITIAL_DELAY_MS).
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.i(TAG, "Captured scancode ${event.scanCode} (foreground=$currentForegroundPackage), " +
                    "remapping to keycode $targetKeyCode x$targetRepeatTimes")
                if (targetRepeatTimes > 1) {
                    sendRepeatedKeyEvent(targetKeyCode, targetRepeatTimes, FOCUS_NAVIGATION_BURST_INTERVAL_MS)
                } else {
                    KeyInjector.sendKeyEvent(targetKeyCode)
                    startSyntheticRepeat(event.scanCode, targetKeyCode)
                }
            } else if (event.action == KeyEvent.ACTION_UP && targetRepeatTimes == 1) {
                stopSyntheticRepeat(event.scanCode)
            }
            return true
        }

        val replacementKeyCode = SCANCODE_TO_KEYCODE[event.scanCode] ?: return handleUnmappedKey(event)

        if (!KeyInjector.isReady()) {
            // Injection isn't ready yet (Shizuku not connected, no root) --
            // let the ORIGINAL button behaviour happen instead of silently
            // swallowing the press with no replacement action. Consuming
            // an event you can't replace just makes the button feel dead.
            if (event.action == KeyEvent.ACTION_DOWN && !isRepeat) {
                Log.w(TAG, "Injection not ready, letting scancode ${event.scanCode} pass through untouched")
                SetupNotifier.promptIfNeeded(this)
            }
            return super.onKeyEvent(event)
        }

        // Only act on the down press to avoid double-firing on ACTION_UP too.
        // Native auto-repeat never reaches this service once it's enabled
        // (see SYNTHETIC_REPEAT_INITIAL_DELAY_MS), so continued firing
        // while held is driven by our own synthetic-repeat timer instead
        // of relying on repeat pulses that will never actually arrive.
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "Captured scancode ${event.scanCode}, remapping to keycode $replacementKeyCode")
            KeyInjector.sendKeyEvent(replacementKeyCode)
            startSyntheticRepeat(event.scanCode, replacementKeyCode)
        } else if (event.action == KeyEvent.ACTION_UP) {
            stopSyntheticRepeat(event.scanCode)
        }
        // Returning true CONSUMES the event -- it will not propagate to the
        // foreground app, launcher, etc. This is what "eats" the button press.
        return true
    }

    /**
     * Injects the same keycode multiple times with a short delay between
     * each, so it reads as N distinct presses (e.g. volume UI updating
     * once per step) rather than one instantaneous blur. Scheduled via a
     * Handler rather than Thread.sleep-ing in place -- onKeyEvent() must
     * return quickly, so the actual bursts happen asynchronously after
     * this function (and onKeyEvent) has already returned.
     */
    private fun sendRepeatedKeyEvent(keyCode: Int, times: Int, intervalMs: Long = REPEATED_SEND_INTERVAL_MS) {
        for (i in 0 until times) {
            repeatedSendHandler.postDelayed({
                KeyInjector.sendKeyEvent(keyCode)
            }, i * intervalMs)
        }
    }

    /**
     * Starts injecting keyCode repeatedly while a key is held, since
     * native repeat generation never reaches this service once it's
     * enabled (see the comment on SYNTHETIC_REPEAT_INITIAL_DELAY_MS).
     * Mirrors normal key-repeat behaviour: a longer initial delay before
     * the first repeat, then a faster steady interval after that.
     */
    private fun startSyntheticRepeat(scanCode: Int, keyCodeToInject: Int) {
        stopSyntheticRepeat(scanCode) // defensive -- avoid stacking duplicate timers
        lateinit var tick: Runnable
        tick = Runnable {
            KeyInjector.sendKeyEvent(keyCodeToInject)
            repeatedSendHandler.postDelayed(tick, SYNTHETIC_REPEAT_INTERVAL_MS)
        }
        activeSyntheticRepeats[scanCode] = tick
        repeatedSendHandler.postDelayed(tick, SYNTHETIC_REPEAT_INITIAL_DELAY_MS)
    }

    private fun stopSyntheticRepeat(scanCode: Int) {
        activeSyntheticRepeats.remove(scanCode)?.let { repeatedSendHandler.removeCallbacks(it) }
    }

    /**
     * Handles any scancode not present in ANY of the maps above. Used to
     * just be a plain `return super.onKeyEvent(event)` -- now also
     * restores synthetic repeat for these too, injecting the event's own
     * original keycode, since native repeat never reaches this service
     * once it's enabled (see SYNTHETIC_REPEAT_INITIAL_DELAY_MS) and that
     * applies to EVERY key, not just the ones this file explicitly maps.
     * The original event is still passed through unconsumed either way --
     * this only adds supplementary synthetic presses on top of it, it
     * doesn't change how the real press/release themselves are handled.
     */
    private fun handleUnmappedKey(event: KeyEvent): Boolean {
        if (KeyInjector.isReady()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                startSyntheticRepeat(event.scanCode, event.keyCode)
            } else if (event.action == KeyEvent.ACTION_UP) {
                stopSyntheticRepeat(event.scanCode)
            }
        }
        // If Shizuku isn't ready, repeat restoration silently doesn't
        // happen -- gracefully degrading to the already-known "no repeat
        // while held" state rather than anything worse.
        return super.onKeyEvent(event)
    }

    /**
     * Consumes the button press but shows an on-screen dialog rather than
     * staying silent -- previously this case just logged a warning, with
     * no indication anything happened at all from the person's
     * perspective. Same "direct user interaction" BAL reasoning as the
     * comment on launchApp()'s own startActivity() call applies here too,
     * since this also fires synchronously from within the key event
     * callback.
     */
    private fun showAppNotInstalledDialog(packageName: String) {
        val intent = Intent(this, AppNotInstalledActivity::class.java).apply {
            putExtra(AppNotInstalledActivity.EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show app-not-installed dialog for $packageName", e)
        }
    }

    private fun launchApp(packageName: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAppLaunchAtMs < APP_LAUNCH_DEBOUNCE_MS) {
            Log.d(TAG, "Skipping launch of $packageName -- inside debounce window")
            return
        }
        lastAppLaunchAtMs = now

        val intent = resolveLaunchIntent(packageName)
        if (intent == null) {
            Log.w(TAG, "Cannot launch $packageName -- no launchable activity found " +
                "(checked LAUNCHER, LEANBACK_LAUNCHER, and HOME categories)")
            showAppNotInstalledDialog(packageName)
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // On some OEM builds, starting an activity from a background
            // service context can be blocked (Android 10+ background
            // activity launch restrictions). It should be reliable here
            // since this call happens synchronously inside the hardware
            // key event callback, which is generally treated as direct
            // user interaction and exempted -- but if you see a
            // SecurityException here on a particular device, the
            // notification/PendingIntent pattern from SetupNotifier.kt is
            // the guaranteed fallback.
            Log.e(TAG, "Failed to launch $packageName", e)
        }
    }

    /**
     * Regular apps expose a CATEGORY_LAUNCHER activity, which
     * getLaunchIntentForPackage() finds. Home-screen replacement apps
     * (launchers, like AT4K) usually don't -- they register CATEGORY_HOME
     * instead, sometimes alongside CATEGORY_LEANBACK_LAUNCHER on Android
     * TV. This tries all three, in order, before giving up.
     */
    private fun resolveLaunchIntent(packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        packageManager.getLeanbackLaunchIntentForPackage(packageName)?.let {
            return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val homeQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            setPackage(packageName)
        }
        val activityInfo = packageManager.queryIntentActivities(homeQuery, 0)
            .firstOrNull()?.activityInfo ?: return null

        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            component = ComponentName(activityInfo.packageName, activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // TYPE_WINDOW_STATE_CHANGED fires whenever a new window/Activity
        // becomes foreground -- this is how we know which app is
        // currently active for SCANCODE_TO_KEYCODE_FOREGROUND_SCOPED,
        // since key events themselves don't carry that information.
        // Already covered by accessibility_service_config.xml's
        // typeAllMask, so no config change needed for this to fire.
        //
        // Ignores TRANSIENT_OVERLAY_PACKAGES rather than treating every
        // window-focus change as authoritative -- a transient system
        // overlay briefly gaining focus isn't really "the app the user
        // is using," and blindly overwriting currentForegroundPackage
        // with it is exactly what caused the wrong remap to fire.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null && packageName !in TRANSIENT_OVERLAY_PACKAGES) {
                currentForegroundPackage = packageName
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        autoBindHandler.removeCallbacksAndMessages(null)
        repeatedSendHandler.removeCallbacksAndMessages(null)
        activeSyntheticRepeats.clear()
        super.onDestroy()
    }
}

/*
 * HOW TO CONFIRM THE REAL SCANCODE FOR YOUR BUTTON:
 * The Log.d(...) line at the top of onKeyEvent above logs every key/scancode
 * that comes through. Press the physical button, watch logcat (tag
 * "ButtonMapperService"), and read off the real scanCode value.
 *
 * Scancodes are hardware/kernel-driver specific and can differ between
 * devices even for buttons that "look" the same, so don't assume any of the
 * values already in the maps above are universal -- confirm each one on
 * the actual target device.
 *
 * Remove or comment out that Log.d line once you're done confirming
 * mappings -- it fires on every single key event system-wide, which is
 * noisy and slightly wasteful to leave running permanently.
 */
