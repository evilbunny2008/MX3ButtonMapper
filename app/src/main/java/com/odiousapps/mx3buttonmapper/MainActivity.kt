package com.odiousapps.mx3buttonmapper

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.odiousapps.mx3buttonmapper.AppLog as Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
                    scheduleAutoCloseUnlessTokenMissing()
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

        setUpShizukuAuthTokenField()
        setUpTvBrandSelector()
    }

    /**
     * Populates the token field with whatever's already saved (blank on
     * first run), and wires up Paste and Save. The pasted/saved value is
     * the full line shown on Shizuku's "View intents" screen ("auth:
     * XXXX") -- stripped down to just the token itself here, so the
     * person can paste that whole line directly (e.g. via Shizuku's own
     * Copy button on that screen) rather than needing to manually edit
     * out the "auth: " prefix themselves.
     */
    private fun setUpShizukuAuthTokenField() {
        val input = findViewById<EditText>(R.id.shizukuAuthTokenInput)
        val pasteButton = findViewById<Button>(R.id.pasteShizukuAuthTokenButton)
        val saveButton = findViewById<Button>(R.id.saveShizukuAuthTokenButton)

        lifecycleScope.launch {
            input.setText(ButtonMapperPreferences.observeShizukuAuthToken(this@MainActivity).first())
        }

        // scheduleAutoCloseUnlessTokenMissing() only checks whether a
        // token has EVER been saved, not whether the person is actively
        // editing this field right now -- so re-pasting a fresh token
        // (e.g. replacing a stale/wrong one from an earlier attempt)
        // still races against the 1.5s auto-close timer armed the moment
        // Shizuku permission was granted, closing the screen out from
        // under a slow remote-control paste-and-save. Any interaction
        // with the field cancels that pending close outright, so once
        // the person has started editing they always have as long as
        // they need to finish and tap Save.
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) mainHandler.removeCallbacksAndMessages(null)
        }

        pasteButton.setOnClickListener {
            mainHandler.removeCallbacksAndMessages(null)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()

            if (clipText.isNullOrBlank()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                input.setText(stripAuthPrefix(clipText))
            }
        }

        saveButton.setOnClickListener {
            val token = stripAuthPrefix(input.text.toString())
            lifecycleScope.launch {
                ButtonMapperPreferences.setShizukuAuthToken(this@MainActivity, token)
                Log.i(TAG, "Shizuku auth token saved")
                // Closing here, after the write actually completes, not
                // immediately when tapped -- finish() would cancel this
                // coroutine (it's scoped to the activity's own
                // lifecycle) if called before the suspend call above
                // returns, risking losing the save entirely.
                finish()
            }
        }
    }

    private fun stripAuthPrefix(raw: String): String =
        raw.trim().removePrefix("auth:").trim()

    /**
     * A radio selection is itself a complete, deliberate action -- saves
     * immediately on change, unlike the token field above which needs an
     * explicit Save since free-text typing has no natural "done" moment
     * the way choosing a radio option does.
     */
    private fun setUpTvBrandSelector() {
        val radioGroup = findViewById<RadioGroup>(R.id.tvBrandRadioGroup)

        lifecycleScope.launch {
            val currentBrand = ButtonMapperPreferences.observeTvBrand(this@MainActivity).first()
            val checkedId = when (currentBrand) {
                TvBrand.TCL -> R.id.tvBrandTcl
                TvBrand.BLAUPUNKT -> R.id.tvBrandBlaupunkt
            }
            radioGroup.check(checkedId)

            radioGroup.setOnCheckedChangeListener { _, checkedButtonId ->
                val newBrand = when (checkedButtonId) {
                    R.id.tvBrandBlaupunkt -> TvBrand.BLAUPUNKT
                    else -> TvBrand.TCL
                }
                lifecycleScope.launch {
                    ButtonMapperPreferences.setTvBrand(this@MainActivity, newBrand)
                    Log.i(TAG, "TV brand set to $newBrand")
                }
            }
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
                    scheduleAutoCloseUnlessTokenMissing()
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

    /**
     * Only auto-closes if a Shizuku auth token is already saved --
     * otherwise this would close the window before there's been any
     * real chance to paste and save one, since AUTO_CLOSE_DELAY_MS is
     * only 1.5 seconds. Once a token has been saved at least once,
     * there's nothing new to configure on subsequent launches, so the
     * normal auto-close behaviour resumes as before.
     */
    private fun scheduleAutoCloseUnlessTokenMissing() {
        lifecycleScope.launch {
            val token = ButtonMapperPreferences.observeShizukuAuthToken(this@MainActivity).first()
            if (token.isNotBlank()) {
                scheduleAutoClose()
            } else {
                Log.i(TAG, "No Shizuku auth token saved yet -- staying open so it can be configured")
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
