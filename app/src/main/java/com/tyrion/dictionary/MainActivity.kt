package com.tyrion.dictionary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Setup screen. Android requires the user to manually enable a new input method
 * in system settings (an app cannot silently turn itself on as the keyboard),
 * so this screen just walks through those steps and gives a field to test typing.
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

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
    }

    private fun updateOverlayStatus() {
        findViewById<TextView>(R.id.tvOverlayStatus).text = if (Settings.canDrawOverlays(this)) {
            "Overlay permission: granted (makikita ang mode badge)"
        } else {
            "Overlay permission: hindi pa naibibigay (kailangan para makita ang mode badge)"
        }
    }
}
