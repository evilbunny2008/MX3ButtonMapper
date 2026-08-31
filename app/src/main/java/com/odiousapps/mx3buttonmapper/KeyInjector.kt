package com.odiousapps.mx3buttonmapper

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * A normal app -- even an AccessibilityService -- cannot inject synthetic key
 * events on its own. INJECT_EVENTS is a signature/system permission Android
 * won't grant to third-party apps. To actually deliver the replacement
 * keycode you need elevated privileges, via one of:
 *
 *   1. Shizuku  - runs your injection code with adb/shell UID privileges,
 *                 granted by the user once via wireless debugging or a
 *                 one-time adb command. No root required. RECOMMENDED.
 *   2. Root     - simplest to wire up if the device is already rooted.
 *
 * This class tries Shizuku first and falls back to root if Shizuku isn't
 * available/authorized.
 */
object KeyInjector {

    private const val TAG = "KeyInjector"

    /**
     * True if a working injection path is available right now (Shizuku
     * connected, or root). Call this BEFORE consuming a key event so you
     * can let the original button behaviour through instead of silently
     * eating the press with no replacement action.
     */
    fun isReady(): Boolean {
        return ShizukuUserServiceBridge.isConnected() || isRootAvailable()
    }

    fun sendKeyEvent(keyCode: Int) {
        if (isShizukuReady()) {
            sendViaShizuku(keyCode)
        } else if (isRootAvailable()) {
            sendViaRoot(keyCode)
        } else {
            Log.w(TAG, "No injection method available -- ask the user to grant Shizuku or root access")
        }
    }

    // ---- Shizuku path -------------------------------------------------

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendViaShizuku(keyCode: Int) {
        try {
            // Shizuku's own newProcess() shell-exec convenience method was
            // deprecated in newer Shizuku versions for security reasons.
            // The current recommended pattern is a bound "UserService":
            // you implement a small AIDL service that runs with shell UID
            // and calls the hidden InputManager#injectInputEvent (or shells
            // out to `input keyevent`) from inside that privileged process.
            //
            // See Shizuku's official demo for the exact UserService wiring,
            // since the AIDL boilerplate is verbose and version-dependent:
            // https://github.com/RikkaApps/Shizuku-API
            //
            // Sketch of what the privileged side ends up doing:
            //   Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            // or, for lower latency, calling InputManager's hidden
            // injectInputEvent(KeyEvent, int) via reflection.
            ShizukuUserServiceBridge.injectKeyEvent(keyCode)
        } catch (e: Throwable) {
            Log.e(TAG, "Shizuku injection failed", e)
        }
    }

    // ---- Root fallback --------------------------------------------------

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendViaRoot(keyCode: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent $keyCode"))
        } catch (e: Throwable) {
            Log.e(TAG, "Root injection failed", e)
        }
    }
}

/**
 * Binds to KeyInjectorUserService (see KeyInjectorUserService.kt) -- the
 * process Shizuku spawns with shell UID privileges -- and forwards
 * injectKeyEvent() calls to it over the AIDL binder.
 *
 * bind() must be called once, after Shizuku permission is granted (see
 * MainActivity.kt). It's safe to call bind() again if the process dies;
 * Shizuku will respawn it.
 *
 * Binding is asynchronous -- especially on a cold start, spawning the
 * privileged process can take a noticeable moment. Calls to
 * injectKeyEvent() that arrive before onServiceConnected() has fired are
 * now queued and flushed once the connection completes, instead of being
 * silently dropped.
 *
 * The privileged process can also die unexpectedly right after connecting
 * (crash, or the OS reclaiming it under memory pressure -- more likely on
 * a weaker TV box). That surfaces as a DeadObjectException on the very
 * first call made against a binder that looked fine a moment earlier.
 * Rather than just logging and giving up, that specific failure now
 * triggers an automatic re-bind with the failed call re-queued to retry
 * once the new process connects.
 */
object ShizukuUserServiceBridge {

    private const val TAG = "ShizukuUserServiceBridge"
    private const val REBIND_DELAY_MS = 500L
    private const val MAX_CONSECUTIVE_REBIND_FAILURES = 3

    private var service: IKeyInjectorService? = null
    private var bindRequested = false
    private var rebindScheduled = false
    private var consecutiveRebindFailures = 0
    private val pendingKeyCodes = mutableListOf<Int>()
    private val pendingComponentEnables = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, KeyInjectorUserService::class.java.name)
        )
            // Was daemon(false), which reliably crash-looped the process
            // shortly after connect. daemon(true) fixed it -- the process
            // now survives, confirmed via a captured logcat showing the
            // exact prior failure (a SecurityException from an unrelated
            // Context-identity bug, since fixed separately in
            // KeyInjectorUserService.kt) no longer taking the whole
            // process down with it. Tradeoff: the privileged process now
            // stays alive in the background even when nothing is actively
            // bound to it, using a small amount of standing memory,
            // instead of being torn down between calls.
            .daemon(true)
            .processNameSuffix("keyinjector")
            .debuggable(BuildConfig.DEBUG)
            // Was a hardcoded .version(1) -- Shizuku's UserServiceArgs
            // "version" exists specifically so a client can force the old
            // process to be torn down and a fresh one spawned: if the
            // requested version is higher than whatever's currently
            // running, Shizuku kills the stale process instead of
            // reconnecting to it. A hardcoded constant defeats that
            // entirely -- every rebuild kept requesting the same "version
            // 1", so Shizuku had no signal that anything changed and just
            // reused the already-running (stale-code) process, which is
            // exactly the manual-kill problem we kept hitting. Tying this
            // to the app's own versionCode means it bumps automatically
            // every time you increment versionCode in build.gradle, which
            // you'd be doing for any real release anyway -- so this isn't
            // just a dev-loop fix, it also means a future update actually
            // takes effect for real users without requiring a reboot.
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IKeyInjectorService.Stub.asInterface(binder)
            consecutiveRebindFailures = 0
            Log.i(TAG, "KeyInjectorUserService connected")
            flushPendingKeyCodes()
            flushPendingComponentEnables()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            Log.w(TAG, "KeyInjectorUserService disconnected -- process likely died or crashed. " +
                "Check adb logcat for a stack trace tagged with process " +
                "'${BuildConfig.APPLICATION_ID}:keyinjector' or 'AndroidRuntime'.")
        }
    }

    private fun flushPendingKeyCodes() {
        synchronized(pendingKeyCodes) {
            if (pendingKeyCodes.isEmpty()) return
            Log.i(TAG, "Flushing ${pendingKeyCodes.size} queued key event(s)")
            val iterator = pendingKeyCodes.iterator()
            while (iterator.hasNext()) {
                val code = iterator.next()
                try {
                    service?.injectKeyEvent(code)
                    iterator.remove()
                } catch (_: DeadObjectException) {
                    Log.w(TAG, "Binder died mid-flush on keyCode=$code -- stopping flush, will retry on rebind")
                    onBinderDied()
                    return // leave remaining queued items in place for the next connection
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed flushing queued keyCode=$code (non-fatal, dropping this one)", e)
                    iterator.remove()
                }
            }
        }
    }

    private fun flushPendingComponentEnables() {
        synchronized(pendingComponentEnables) {
            if (pendingComponentEnables.isEmpty()) return
            val iterator = pendingComponentEnables.iterator()
            while (iterator.hasNext()) {
                val componentName = iterator.next()
                try {
                    service?.enableAccessibilityService(componentName)
                    Log.i(TAG, "Enabled accessibility service (flushed): $componentName")
                    iterator.remove()
                } catch (_: DeadObjectException) {
                    Log.w(TAG, "Binder died mid-flush on enableAccessibilityService($componentName) -- " +
                        "stopping flush, will retry on rebind")
                    onBinderDied()
                    return
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed flushing queued enableAccessibilityService for $componentName " +
                        "(non-fatal, dropping this one)", e)
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Called whenever a call against `service` throws DeadObjectException.
     * Clears the stale reference and schedules a re-bind attempt (guarded
     * so a burst of failing calls doesn't queue up a burst of redundant
     * re-binds). Gives up after MAX_CONSECUTIVE_REBIND_FAILURES in a row --
     * an unbounded respawn-every-500ms loop is its own hazard on a
     * resource-constrained device if something is persistently wrong, so
     * this is a backstop even though the specific bug that originally
     * triggered this (Runtime.exec() inside the privileged process) is
     * fixed at the source in KeyInjectorUserService.kt now.
     */
    private fun onBinderDied() {
        service = null
        consecutiveRebindFailures++
        if (consecutiveRebindFailures > MAX_CONSECUTIVE_REBIND_FAILURES) {
            Log.e(TAG, "Giving up after $consecutiveRebindFailures consecutive binder deaths -- " +
                "not auto-retrying further. Re-open the app to reset and try again.")
            return
        }
        if (rebindScheduled) return
        rebindScheduled = true
        mainHandler.postDelayed({
            rebindScheduled = false
            Log.i(TAG, "Re-binding after DeadObjectException (attempt $consecutiveRebindFailures)")
            bindInternal()
        }, REBIND_DELAY_MS)
    }

    fun bind() {
        consecutiveRebindFailures = 0
        bindInternal()
    }

    private fun bindInternal() {
        bindRequested = true
        Log.i(TAG, "Requesting bind to $userServiceArgs")
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Throwable) {
            // This is the call most likely to throw if Shizuku's own server
            // isn't running or permission was revoked between checks.
            Log.e(TAG, "Shizuku.bindUserService() threw -- is Shizuku actually running?", e)
        }
    }

    /** True once onServiceConnected has actually fired -- the real "ready" signal. */
    fun isConnected(): Boolean = service != null

    fun injectKeyEvent(keyCode: Int) {
        val current = service
        if (current != null) {
            try {
                current.injectKeyEvent(keyCode)
                return
            } catch (_: DeadObjectException) {
                Log.w(TAG, "Binder died calling injectKeyEvent($keyCode) -- queueing for retry after rebind")
                synchronized(pendingKeyCodes) { pendingKeyCodes.add(keyCode) }
                onBinderDied()
                return
            }
        }

        if (!bindRequested) {
            Log.w(TAG, "UserService not bound yet -- bind() was never called this session " +
                "(did MainActivity run and get Shizuku permission granted?)")
            return
        }

        // bind() was called but onServiceConnected hasn't fired yet -- queue
        // it rather than dropping it, in case this is just first-connection
        // latency.
        Log.w(TAG, "UserService bind in progress but not yet connected -- queueing keyCode=$keyCode")
        synchronized(pendingKeyCodes) {
            pendingKeyCodes.add(keyCode)
        }
    }

    /**
     * Writes the given (already-flattened) component name into
     * enabled_accessibility_services via the shell-UID privileged process,
     * so the user never has to manually visit Settings -> Accessibility.
     * Build the componentName with:
     *   ComponentName(context, ButtonMapperService::class.java).flattenToString()
     */
    fun enableAccessibilityService(componentName: String) {
        val current = service
        if (current != null) {
            try {
                current.enableAccessibilityService(componentName)
                Log.i(TAG, "Enabled accessibility service: $componentName")
                return
            } catch (_: DeadObjectException) {
                Log.w(TAG, "Binder died calling enableAccessibilityService($componentName) -- " +
                    "queueing for retry after rebind")
                synchronized(pendingComponentEnables) { pendingComponentEnables.add(componentName) }
                onBinderDied()
                return
            } catch (e: Throwable) {
                Log.e(TAG, "enableAccessibilityService call failed", e)
                return
            }
        }

        if (!bindRequested) {
            Log.w(TAG, "Cannot enable accessibility service yet -- bind() was never called this session")
            return
        }

        Log.w(TAG, "UserService not connected yet -- queueing enableAccessibilityService($componentName)")
        synchronized(pendingComponentEnables) {
            pendingComponentEnables.add(componentName)
        }
    }
}
