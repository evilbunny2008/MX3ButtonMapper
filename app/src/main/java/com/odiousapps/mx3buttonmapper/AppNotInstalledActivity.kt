package com.odiousapps.mx3buttonmapper

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Shown when a button mapped to launch a specific app is pressed, but
 * that app isn't installed. Previously this case was silent -- the
 * button press was consumed with only a logcat warning, giving no
 * on-screen indication anything happened at all.
 *
 * A dedicated dialog-themed Activity (matching MainActivity's plain
 * Views + AppCompat convention, not Compose, which is a declared
 * dependency but not actually used anywhere else in this app) rather
 * than a WindowManager/TYPE_ACCESSIBILITY_OVERLAY approach -- simpler
 * and more predictable across OEM builds than an accessibility overlay,
 * at the cost of a brief window-transition animation an overlay
 * wouldn't have.
 */
class AppNotInstalledActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "that app"

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_not_installed_title))
            .setMessage(getString(R.string.app_not_installed_message, packageName))
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .setCancelable(true)
            .show()
    }
}
