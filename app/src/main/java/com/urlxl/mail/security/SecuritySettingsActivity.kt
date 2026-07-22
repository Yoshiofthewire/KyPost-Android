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
    private lateinit var credentialGateSwitch: Switch
    private var suppressLockToggleListener = false
    private var suppressCredentialGateListener = false

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

        credentialGateSwitch = Switch(this).apply {
            text = getString(R.string.security_credential_gate_title)
            isChecked = appLockStore.isCredentialPinGateEnabled()
            isEnabled = appLockStore.isLockEnabled()
        }
        container.addView(credentialGateSwitch)
        container.addView(
            TextView(this).apply {
                text = getString(R.string.security_credential_gate_intro)
                textSize = 13f
                setPadding(0, 4, 0, 16)
            },
        )
        credentialGateSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCredentialGateListener) return@setOnCheckedChangeListener
            if (checked) confirmEnableCredentialGate() else confirmDisableCredentialGate()
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

    /**
     * Reverts [credentialGateSwitch] to [checked] without re-firing its listener — same
     * re-entrancy hazard as [revertLockSwitch], guarded the same way. Used whenever we undo the
     * user's toggle because the PIN prompt was cancelled or the PIN was wrong.
     */
    private fun revertCredentialGateSwitch(checked: Boolean) {
        suppressCredentialGateListener = true
        credentialGateSwitch.isChecked = checked
        suppressCredentialGateListener = false
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
                    credentialGateSwitch.isEnabled = true
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

    private fun confirmEnableCredentialGate() {
        AlertDialog.Builder(this)
            .setTitle(R.string.security_credential_gate_warning_title)
            .setMessage(R.string.security_credential_gate_warning_body)
            .setPositiveButton(R.string.security_credential_gate_warning_confirm) { _, _ -> promptCredentialGatePin(enabling = true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertCredentialGateSwitch(false) }
            .setCancelable(false)
            .show()
    }

    private fun confirmDisableCredentialGate() {
        promptCredentialGatePin(enabling = false)
    }

    /** Both directions need the PIN re-entered here (not just "the app happens to be unlocked
     *  right now") to guarantee a fresh PIN-derived key is available to actually re-wrap or
     *  unwrap the current pairing's deviceSecret in the same step — see this task's correctness
     *  note about why a confirm-only flow isn't sufficient. */
    private fun promptCredentialGatePin(enabling: Boolean) {
        val pinField = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.unlock_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.security_credential_gate_pin_title)
            .setView(pinField)
            .setPositiveButton(R.string.security_set_pin_confirm) { _, _ ->
                val appLockManager = SecurityRuntime.graph(this).appLockManager
                if (appLockManager.deriveAndCacheCredentialKey(pinField.text.toString())) {
                    appLockStore.setCredentialPinGateEnabled(enabling)
                    if (enabling) rewrapCurrentPairing() else unwrapCurrentPairing()
                } else {
                    revertCredentialGateSwitch(!enabling)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> revertCredentialGateSwitch(!enabling) }
            .setCancelable(false)
            .show()
    }

    /** Re-saves the currently-paired credentials wrapped behind the just-derived key — without
     *  this, turning the gate on would only take effect for pairing data saved AFTER this point
     *  (a future re-pair), leaving an existing pairing's deviceSecret unwrapped indefinitely. */
    private fun rewrapCurrentPairing() {
        lifecycleScope.launch {
            val securePairingStore = com.urlxl.mail.push.SecurePairingStore(this@SecuritySettingsActivity)
            val currentPairing = securePairingStore.pairing.value ?: return@launch
            val appLockManager = SecurityRuntime.graph(this@SecuritySettingsActivity).appLockManager
            val credentialKey = appLockManager.cachedCredentialKey() ?: return@launch
            val credentialSalt = appLockStore.credentialSalt() ?: return@launch
            securePairingStore.savePairing(currentPairing, credentialKey, credentialSalt)
        }
    }

    /** The inverse of [rewrapCurrentPairing] — without this, turning the gate back off would leave
     *  deviceSecret stored wrapped with no code path that ever unwraps it, permanently breaking
     *  authentication the next time the app locks (cachedCredentialKey() goes back to null once the
     *  gate reports disabled, and resolveDeviceSecret has no other way to read the wrapped value). */
    private fun unwrapCurrentPairing() {
        lifecycleScope.launch {
            val securePairingStore = com.urlxl.mail.push.SecurePairingStore(this@SecuritySettingsActivity)
            val appLockManager = SecurityRuntime.graph(this@SecuritySettingsActivity).appLockManager
            val credentialKey = appLockManager.cachedCredentialKey() ?: return@launch
            val currentPairing = securePairingStore.pairingSnapshot(credentialKey) ?: return@launch
            securePairingStore.savePairing(currentPairing)
        }
    }
}
