package com.odiousapps.mx3buttonmapper

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val AUTO_CLOSE_DELAY_MS = 1500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Shizuku permission granted, binding user service")
                    ShizukuUserServiceBridge.bind()
                    enableAccessibilityServiceViaShizuku()
                    SetupNotifier.cancel(this)
                    scheduleAutoClose()
                } else {
                    Log.w(TAG, "Shizuku permission denied -- key injection won't work until granted")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        requestShizukuIfNeeded()
        requestNotificationPermissionIfNeeded()

        findViewById<Button>(R.id.openAccessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /**
     * Uses the same Shizuku privileged process that injects key events to
     * also flip on our own accessibility service, so the user never has to
     * manually visit Settings -> Accessibility and toggle it.
     *
     * Safe to call more than once -- KeyInjectorUserService merges into the
     * existing enabled_accessibility_services list rather than overwriting
     * it, so other services (TalkBack, etc.) the user already has enabled
     * are preserved.
     */
    private fun enableAccessibilityServiceViaShizuku() {
        val flattenedComponentName =
            ComponentName(this, ButtonMapperService::class.java).flattenToString()
        ShizukuUserServiceBridge.enableAccessibilityService(flattenedComponentName)
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Only needed on API 33+ -- SetupNotifier's "grant Shizuku permission"
        // nudge won't show without this on newer Android/Google TV builds.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestShizukuIfNeeded() {
        try {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    Log.i(TAG, "Shizuku already authorized, binding user service")
                    ShizukuUserServiceBridge.bind()
                    enableAccessibilityServiceViaShizuku()
                    SetupNotifier.cancel(this)
                    scheduleAutoClose()
                }
                else -> {
                    // On Google TV there's no touch UI concern here -- this just
                    // triggers Shizuku's own permission dialog.
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                }
            }
        } catch (e: IllegalStateException) {
            // Thrown if the Shizuku service isn't running yet -- e.g. user
            // hasn't started it via wireless debugging this session.
            Log.e(TAG, "Shizuku not running -- start it first (see README)", e)
        }
    }

    /**
     * Once setup has succeeded there's no reason for this window to stay
     * open and rendered -- it's just an empty confirmation screen at that
     * point. On a device that's already under heavy background load
     * (chronic ~65 load average observed on this particular TV box,
     * independent of anything this app does), an extra idle window sitting
     * around is one more thing competing for the GPU/compositor, and this
     * app has been the one catching stuck-fence ANRs during exactly that
     * kind of contention. Closing promptly once there's nothing left to do
     * here removes us from that picture as much as we reasonably can.
     *
     * Delayed slightly rather than closing instantly so the bind/enable
     * calls above have a moment to actually go out before the window (and
     * this Activity's Context) disappears.
     */
    private fun scheduleAutoClose() {
        mainHandler.postDelayed({
            if (!isFinishing) {
                Log.i(TAG, "Setup complete, auto-closing")
                finish()
            }
        }, AUTO_CLOSE_DELAY_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
