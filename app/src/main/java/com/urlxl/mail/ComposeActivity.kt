package com.urlxl.mail

import android.os.Bundle
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.UnderlineSpan
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.infomaniak.lib.richhtmleditor.RichHtmlEditorWebView
import com.urlxl.mail.mail.MailDraft
import com.urlxl.mail.mail.MailOutcome
import com.urlxl.mail.mail.MailRuntime
import com.urlxl.mail.mail.userFacingMessage
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ComposeActivity : AppCompatActivity() {

    private lateinit var toField: EditText
    private lateinit var subjectField: EditText
    private lateinit var bodyEditor: RichHtmlEditorWebView
    private lateinit var bodyPlaceholder: android.view.View
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button
    private lateinit var boldChip: Chip
    private lateinit var italicChip: Chip
    private lateinit var underlineChip: Chip
    private lateinit var bulletChip: Chip
    private lateinit var numberChip: Chip
    private lateinit var linkChip: Chip
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)
        applyThemeToActivity(this)

        val root = findViewById<android.view.View>(R.id.composeRoot)
        applyTopInsetWithHeader(this, root)

        setTitle(R.string.compose_email)

        toField = findViewById(R.id.composeToField)
        subjectField = findViewById(R.id.composeSubjectField)
        bodyEditor = findViewById(R.id.composeBodyEditor)
        bodyPlaceholder = findViewById(R.id.composeBodyPlaceholder)
        sendButton = findViewById(R.id.composeSendButton)
        cancelButton = findViewById(R.id.composeCancelButton)
        boldChip = findViewById(R.id.composeBold)
        italicChip = findViewById(R.id.composeItalic)
        underlineChip = findViewById(R.id.composeUnderline)
        underlineChip.text = SpannableString(underlineChip.text).apply {
            setSpan(UnderlineSpan(), 0, length, 0)
        }
        bulletChip = findViewById(R.id.composeBulletList)
        numberChip = findViewById(R.id.composeNumberList)
        linkChip = findViewById(R.id.composeLink)

        toField.setText(intent.getStringExtra(EXTRA_TO).orEmpty())
        subjectField.setText(intent.getStringExtra(EXTRA_SUBJECT).orEmpty())
        val prefillBody = intent.getStringExtra(EXTRA_BODY).orEmpty()
        bodyEditor.setHtml(plainTextToHtml(prefillBody))

        boldChip.setOnClickListener { bodyEditor.toggleBold() }
        italicChip.setOnClickListener { bodyEditor.toggleItalic() }
        underlineChip.setOnClickListener { bodyEditor.toggleUnderline() }
        bulletChip.setOnClickListener { bodyEditor.toggleUnorderedList() }
        numberChip.setOnClickListener { bodyEditor.toggleOrderedList() }
        linkChip.setOnClickListener {
            if (linkChip.isChecked) {
                bodyEditor.unlink()
            } else {
                showCreateLinkDialog()
            }
        }

        lifecycleScope.launch {
            bodyEditor.editorStatusesFlow.collect { statuses ->
                boldChip.isChecked = statuses.isBold
                italicChip.isChecked = statuses.isItalic
                underlineChip.isChecked = statuses.isUnderlined
                bulletChip.isChecked = statuses.isUnorderedListSelected
                numberChip.isChecked = statuses.isOrderedListSelected
                linkChip.isChecked = statuses.isLinkSelected
            }
        }
        bodyEditor.isEmptyFlow
            .onEach { isEmpty -> bodyPlaceholder.visibility = if (isEmpty != false) android.view.View.VISIBLE else android.view.View.GONE }
            .launchIn(lifecycleScope)

        sendButton.setOnClickListener { sendEmail() }
        cancelButton.setOnClickListener { finish() }
        applyPrimaryButtonTheme(this, sendButton)
        applyGhostButtonTheme(this, cancelButton)
        applyToolbarChipsTheme()
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyPrimaryButtonTheme(this, sendButton)
        applyGhostButtonTheme(this, cancelButton)
        applyToolbarChipsTheme()
        applyEditorThemeCss()
    }

    private fun applyToolbarChipsTheme() {
        listOf(boldChip, italicChip, underlineChip, bulletChip, numberChip, linkChip).forEach {
            applyPillChipTheme(this, it)
        }
    }

    /** Injects the active palette into the editor's WebView content so it doesn't render as a
     *  fixed light/dark WebView default regardless of the in-app theme. Passing the same [id] on
     *  every call replaces the previous tag rather than accumulating one per theme switch.
     *
     *  Also sets a floor on the document's own height: the editor watches
     *  `document.documentElement`'s resize and reports that height back to Android, which then
     *  becomes the WebView's *explicit* height (see the library's define_listeners.js /
     *  updateWebViewHeight) — overriding any Android-side match_parent/minHeight. Without a
     *  min-height here, an empty document reports only ~1rem, and the WebView shrinks to a single
     *  line no matter how much space its parent layout gives it. */
    private fun applyEditorThemeCss() {
        val palette = getStoredThemePalette(this)
        val css = """
            html, body {
                min-height: 500px;
            }
            body {
                background-color: ${palette.bg};
                color: ${palette.inkStrong};
                font-family: sans-serif;
                font-size: 16px;
            }
            a { color: ${palette.accent}; }
        """.trimIndent()
        bodyEditor.addCss(css, id = "llama-theme")
    }

    private fun showCreateLinkDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val urlField = EditText(this).apply { hint = getString(R.string.compose_link_dialog_url_hint) }
        val textField = EditText(this).apply { hint = getString(R.string.compose_link_dialog_text_hint) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(urlField)
            addView(textField)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compose_link_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.compose_link_dialog_add) { _, _ ->
                val url = urlField.text.toString().trim()
                if (url.isNotBlank()) bodyEditor.createLink(textField.text.toString().trim(), url)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun plainTextToHtml(text: String): String {
        if (text.isEmpty()) return ""
        return TextUtils.htmlEncode(text).replace("\n", "<br>")
    }

    private fun sendEmail() {
        val to = toField.text.toString().trim()
        val subject = subjectField.text.toString().trim()
        val isBodyEmpty = bodyEditor.isEmptyFlow.value != false

        if (to.isBlank() || subject.isBlank() || isBodyEmpty) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }

        sendButton.isEnabled = false
        sendButton.text = "Sending..."

        bodyEditor.exportHtml { html ->
            ioExecutor.execute {
                val outcome = MailRuntime.graph(this).repository.send(
                    MailDraft(to = to, subject = subject, body = html, mode = "html"),
                )
                runOnUiThread {
                    when (outcome) {
                        is MailOutcome.Success -> {
                            val warning = outcome.value.warning
                            // The send already succeeded even when sentSaved is false — surface the
                            // warning as a non-blocking notice, not a failure.
                            val message = warning.ifBlank { "Email sent successfully" }
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        else -> {
                            sendButton.isEnabled = true
                            sendButton.text = getString(R.string.compose_send)
                            Toast.makeText(this, outcome.userFacingMessage(), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_TO = "compose_to"
        const val EXTRA_SUBJECT = "compose_subject"
        const val EXTRA_BODY = "compose_body"
    }
}
