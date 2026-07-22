package com.urlxl.mail.security

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.R
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyTopInsetWithHeader
import kotlinx.coroutines.launch

/**
 * "Security" settings screen: Require Unlock to Open (this task), Hostile Location Protection
 * (Task 17), and the credential PIN-gate (Task 18) — see the 2026-07-22 security-hardening
 * spec. Toggles 2 and 3 are disabled unless toggle 1 is on; enforced here, not just documented.
 */
class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var appLockStore: AppLockStore
    private lateinit var lockSwitch: Switch
    private lateinit var biometricSwitch: Switch
    private lateinit var hostileLocationSwitch: Switch
    private lateinit var hostileLocationIntro: TextView
    private var suppressLockToggleListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockStore = AppLockStore(this)
        setTitle(R.string.security_settings_title)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        applyTopInsetWithHeader(this, scrollView)

        lockSwitch = Switch(this).apply {
            text = getString(R.string.security_require_unlock_title)
            isChecked = appLockStore.isLockEnabled()
        }
        container.addView(lockSwitch)
        container.addView(
            TextView(this).apply {
                text = getString(R.string.security_require_unlock_intro)
                textSize = 13f
                setPadding(0, 4, 0, 16)
            },
        )

        biometricSwitch = Switch(this).apply {
            text = getString(R.string.security_use_biometric_title)
            isChecked = appLockStore.isBiometricEnabled()
            isEnabled = appLockStore.isLockEnabled()
        }
        container.addView(biometricSwitch)

        val hostileLocationSettings = HostileLocationSettings(this)
        hostileLocationSwitch = Switch(this).apply {
            text = getString(R.string.security_hostile_location_title)
            isChecked = hostileLocationSettings.isEnabled()
            isEnabled = appLockStore.isLockEnabled()
        }
        container.addView(hostileLocationSwitch)
        hostileLocationIntro = TextView(this).apply {
            text = if (appLockStore.isLockEnabled()) {
                getString(R.string.security_hostile_location_intro)
            } else {
                getString(R.string.security_hostile_location_requires_lock)
            }
            textSize = 13f
            setPadding(0, 4, 0, 16)
        }
        container.addView(hostileLocationIntro)
        hostileLocationSwitch.setOnCheckedChangeListener { _, checked ->
            hostileLocationSettings.setEnabled(checked)
            AppRestart.relaunch(this)
        }

        lockSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressLockToggleListener) return@setOnCheckedChangeListener
            onLockToggle(checked)
        }
        biometricSwitch.setOnCheckedChangeListener { _, checked -> appLockStore.setBiometricEnabled(checked) }

        scrollView.addView(container)
        setContentView(scrollView)
        applyThemeToActivity(this)
    }

    private fun onLockToggle(checked: Boolean) {
        if (checked) {
            promptSetPin()
        } else {
            promptDisableLock()
        }
    }

    /**
     * Reverts [lockSwitch] to [checked] without re-firing its listener. Used whenever we undo the
     * user's toggle because the set-PIN or disable-lock flow was cancelled or failed — never for
     * the legitimate forward-progress state changes (those call appLockStore directly).
     */
    private fun revertLockSwitch(checked: Boolean) {
        suppressLockToggleListener = true
        lockSwitch.isChecked = checked
        suppressLockToggleListener = false
    }

    private fun promptSetPin() {
        val pinField = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.unlock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.security_set_pin_title)
            .setView(pinField)
            .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
                val pin = pinField.text.toString()
                if (pin.length == 6) {
                    appLockStore.setPin(pin)
                    appLockStore.setLockEnabled(true)
                    biometricSwitch.isEnabled = true
                    hostileLocationSwitch.isEnabled = true
                    hostileLocationIntro.text = getString(R.string.security_hostile_location_intro)
                } else {
                    revertLockSwitch(false)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertLockSwitch(false) }
            .setCancelable(false)
            .show()
    }

    private fun promptDisableLock() {
        val pinField = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.unlock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.security_confirm_disable_title)
            .setView(pinField)
            .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
                if (appLockStore.verifyPin(pinField.text.toString())) {
                    HostileLocationSettings(this@SecuritySettingsActivity).setEnabled(false)
                    lifecycleScope.launch {
                        SecurityWipe.wipeAndResetApp(this@SecuritySettingsActivity)
                        AppRestart.relaunch(this@SecuritySettingsActivity)
                    }
                } else {
                    revertLockSwitch(true)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertLockSwitch(true) }
            .setCancelable(false)
            .show()
    }
}
