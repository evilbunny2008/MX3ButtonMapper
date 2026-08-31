package com.odiousapps.mx3buttonmapper

import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.os.SystemClock
import kotlin.system.exitProcess

/**
 * Runs in a separate process spawned by Shizuku with shell UID privileges
 * (the same UID `adb shell` commands run as). Shell already holds
 * INJECT_EVENTS, which is exactly why `adb shell input keyevent N` works --
 * this class does the same thing in-process via the hidden InputManager
 * API instead of shelling out, which is a bit faster and avoids spawning a
 * subprocess per button press.
 *
 * IMPORTANT: Shizuku instantiates this class via reflection and expects a
 * no-arg constructor (or a (Context) constructor depending on Shizuku
 * version -- check the version you added in build.gradle against Shizuku's
 * "UserService" sample if the no-arg constructor below doesn't bind).
 *
 * IMPORTANT #2: enableAccessibilityService() deliberately shells out to the
 * `settings` CLI rather than using a Context-based ContentResolver. That
 * was tried first and failed with:
 *   SecurityException: Given calling package android does not match caller's uid 2000
 * ActivityThread.getSystemContext() returns an identity for package
 * "android" (system UID 1000), but this process is actually shell (UID
 * 2000) -- a mismatch ActivityManagerService rejects before the
 * ContentProvider call even reaches Settings. Shell simply doesn't
 * legitimately own any app package identity to present to a
 * Context/ContentResolver. That's exactly why command-line tools like
 * `settings` exist as a separate code path for shell-identity callers in
 * the first place -- use that instead of Java Context APIs for this.
 */
class KeyInjectorUserService : IKeyInjectorService.Stub() {

    companion object {
        private const val TAG = "KeyInjectorUserService"
    }

    override fun injectKeyEvent(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            inject(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun enableAccessibilityService(flattenedComponentName: String) {
        Log.i(TAG, "enableAccessibilityService: start, target=$flattenedComponentName")
        try {
            // Read the current list so we don't clobber any other
            // accessibility services (e.g. TalkBack) the user already has
            // enabled.
            val current = shellOutput("settings get secure enabled_accessibility_services").trim()
            Log.i(TAG, "enableAccessibilityService: read current='$current'")
            val currentServices = current
                .split(":")
                .filter { it.isNotBlank() && it != "null" }
                .toMutableSet()

            if (currentServices.add(flattenedComponentName)) {
                val newValue = currentServices.joinToString(":")
                Log.i(TAG, "enableAccessibilityService: about to write enabled_accessibility_services='$newValue'")
                shell("settings put secure enabled_accessibility_services $newValue")
                Log.i(TAG, "enableAccessibilityService: wrote enabled_accessibility_services successfully")
            } else {
                Log.i(TAG, "enableAccessibilityService: target already present, no write needed")
            }
            // Belt-and-suspenders: also make sure accessibility itself is on.
            Log.i(TAG, "enableAccessibilityService: about to write accessibility_enabled=1")
            shell("settings put secure accessibility_enabled 1")
            Log.i(TAG, "enableAccessibilityService: wrote accessibility_enabled successfully, done")
        } catch (e: Throwable) {
            Log.e(TAG, "enableAccessibilityService: caught exception", e)
            e.printStackTrace()
        }
    }

    /**
     * Runs a shell command as this process's UID (shell, via Shizuku).
     * `adb shell settings put secure ...` works without any app ever being
     * granted WRITE_SECURE_SETTINGS specifically because the shell UID is
     * allowed to write secure settings directly through this code path --
     * this process inherits that same allowance.
     */
    private fun shell(command: String) {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        process.waitFor()
    }

    @Suppress("SameParameterValue")
    private fun shellOutput(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun inject(event: InputEvent) {
        val inputManagerClass = Class.forName("android.hardware.input.InputManager")
        val getInstance = inputManagerClass.getMethod("getInstance")
        val inputManager = getInstance.invoke(null)
        val injectInputEvent = inputManagerClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        // INJECT_INPUT_EVENT_MODE_ASYNC = 0 -- fire and forget, don't wait
        // for the event to finish being dispatched.
        injectInputEvent.invoke(inputManager, event, 0)
    }

    override fun destroy() {
        exitProcess(0)
    }
}
