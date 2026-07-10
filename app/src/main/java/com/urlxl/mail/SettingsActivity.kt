package com.urlxl.mail

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.push.PushPairingActivity
import com.urlxl.mail.push.PushRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var mailSettings: MailSettings
    private lateinit var connectionModeGroup: RadioGroup
    private lateinit var relayStatusText: TextView
    private lateinit var btnPairDevice: Button
    private lateinit var imapConfigSection: View
    private lateinit var imapHostField: EditText
    private lateinit var imapPortField: EditText
    private lateinit var smtpHostField: EditText
    private lateinit var smtpPortField: EditText
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var imapFolderField: EditText
    private lateinit var btnSave: Button

    private var isPaired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        applyThemeToActivity(this)

        val root = findViewById<android.view.View>(R.id.settingsRoot)
        applyTopInsetWithHeader(this, root)

        mailSettings = MailSettings(this)
        initViews()
        loadCurrentSettings()

        connectionModeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateSectionVisibility(checkedId == R.id.radioRelay)
        }
        btnPairDevice.setOnClickListener {
            startActivity(Intent(this, PushPairingActivity::class.java))
        }

        btnSave.setOnClickListener { saveSettings() }
        applyPrimaryButtonTheme(this, btnSave)
        applyPrimaryButtonTheme(this, btnPairDevice)
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applySectionEyebrowLabel(this, findViewById(R.id.imapSettingsHeader))
        applySectionEyebrowLabel(this, findViewById(R.id.smtpSettingsHeader))
        applySectionEyebrowLabel(this, findViewById(R.id.authSettingsHeader))
        applyPrimaryButtonTheme(this, btnSave)
        applyPrimaryButtonTheme(this, btnPairDevice)
        refreshPairingStatus()
    }

    private fun initViews() {
        connectionModeGroup = findViewById(R.id.connectionModeGroup)
        relayStatusText = findViewById(R.id.relayStatusText)
        btnPairDevice = findViewById(R.id.btnPairDevice)
        imapConfigSection = findViewById(R.id.imapConfigSection)
        imapHostField = findViewById(R.id.editImapHost)
        imapPortField = findViewById(R.id.editImapPort)
        smtpHostField = findViewById(R.id.editSmtpHost)
        smtpPortField = findViewById(R.id.editSmtpPort)
        usernameField = findViewById(R.id.editUsername)
        passwordField = findViewById(R.id.editPassword)
        imapFolderField = findViewById(R.id.editImapFolder)
        btnSave = findViewById(R.id.btnSaveSettings)
    }

    private fun loadCurrentSettings() {
        imapHostField.setText(mailSettings.getImapHost())
        imapPortField.setText(mailSettings.getImapPort().toString())
        smtpHostField.setText(mailSettings.getSmtpHost())
        smtpPortField.setText(mailSettings.getSmtpPort().toString())
        usernameField.setText(mailSettings.getUsername())
        passwordField.setText(mailSettings.getPassword())
        imapFolderField.setText(mailSettings.getImapFolder())

        val relaySelected = mailSettings.getConnectionMode() == MailConnectionMode.RELAY
        connectionModeGroup.check(if (relaySelected) R.id.radioRelay else R.id.radioManualImap)
        updateSectionVisibility(relaySelected)
    }

    private fun updateSectionVisibility(relaySelected: Boolean) {
        imapConfigSection.visibility = if (relaySelected) View.GONE else View.VISIBLE
        relayStatusText.visibility = if (relaySelected) View.VISIBLE else View.GONE
        btnPairDevice.visibility = if (relaySelected && !isPaired) View.VISIBLE else View.GONE
        if (relaySelected) {
            relayStatusText.setText(
                if (isPaired) R.string.connection_mode_relay_paired else R.string.connection_mode_relay_not_paired,
            )
        }
    }

    /** Reflects the same pairing state push/pull already relies on — no separate pairing check. */
    private fun refreshPairingStatus() {
        lifecycleScope.launch {
            isPaired = PushRuntime.graph(this@SettingsActivity).repository.state.first().pairing != null
            updateSectionVisibility((connectionModeGroup.checkedRadioButtonId) == R.id.radioRelay)
        }
    }

    private fun saveSettings() {
        val relaySelected = connectionModeGroup.checkedRadioButtonId == R.id.radioRelay

        if (relaySelected) {
            if (!isPaired) {
                Toast.makeText(this, R.string.connection_mode_relay_not_paired, Toast.LENGTH_SHORT).show()
                return
            }
            mailSettings.setConnectionMode(MailConnectionMode.RELAY)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
            return
        }

        mailSettings.setConnectionMode(MailConnectionMode.MANUAL_IMAP)

        val imapHost = imapHostField.text.toString().trim()
        val imapPortStr = imapPortField.text.toString().trim()
        val smtpHost = smtpHostField.text.toString().trim()
        val smtpPortStr = smtpPortField.text.toString().trim()
        val username = usernameField.text.toString().trim()
        val password = passwordField.text.toString().trim()
        val imapFolder = imapFolderField.text.toString().trim().ifBlank { "INBOX" }

        val validationError = validateSettings(
            imapHost = imapHost,
            imapPortStr = imapPortStr,
            smtpHost = smtpHost,
            smtpPortStr = smtpPortStr,
            username = username,
            password = password,
        )

        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_SHORT).show()
            return
        }

        val config = MailAccountConfig(
            imapHost = imapHost,
            imapPort = imapPortStr.toInt(),
            smtpHost = smtpHost,
            smtpPort = smtpPortStr.toInt(),
            username = username,
            password = password,
            folderName = imapFolder,
        )

        mailSettings.saveConfig(config)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

        setResult(RESULT_OK)
        finish()
    }

    private fun validateSettings(
        imapHost: String,
        imapPortStr: String,
        smtpHost: String,
        smtpPortStr: String,
        username: String,
        password: String,
    ): String? {
        if (imapHost.isBlank()) return "IMAP host is required"
        if (smtpHost.isBlank()) return "SMTP host is required"
        if (username.isBlank()) return "Username is required"
        if (password.isBlank()) return "Password is required"

        val imapPort = imapPortStr.toIntOrNull()
        if (imapPort == null || imapPort !in 1..65535) return "IMAP port must be 1-65535"

        val smtpPort = smtpPortStr.toIntOrNull()
        if (smtpPort == null || smtpPort !in 1..65535) return "SMTP port must be 1-65535"

        return null
    }
}
