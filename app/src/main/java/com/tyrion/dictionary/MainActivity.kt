package com.tyrion.dictionary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Setup screen. Android requires the user to manually enable a new input method
 * in system settings (an app cannot silently turn itself on as the keyboard),
 * so this screen just walks through those steps and gives a field to test typing.
 *
 * Status is shown via a status bar notification (not a floating overlay) — this is
 * the same approach sspanak's TT9 uses, and is specifically documented to work
 * reliably on Qin F21/F22 Pro-family phones.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnPickKeyboard).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnEnableNotifications).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                // Permission already granted, or not needed on this Android version —
                // just jump to the app's notification settings in case they were disabled
                // manually at the OS level.
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateNotificationStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateNotificationStatus()
    }

    private fun updateNotificationStatus() {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        findViewById<TextView>(R.id.tvNotificationStatus).text = if (enabled) {
            "Notification status: pinapayagan (makikita ang mode indicator)"
        } else {
            "Notification status: naka-disable — pindutin ang button sa itaas"
        }
    }
}
