package com.urlxl.mail

import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.View
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.urlxl.mail.mail.AttachmentInfo
import com.urlxl.mail.mail.MailOutcome
import com.urlxl.mail.mail.MailRepository
import com.urlxl.mail.mail.MailRuntime
import com.urlxl.mail.mail.userFacingMessage
import java.util.concurrent.Executors

class EmailDetailActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var mailRepository: MailRepository
    private lateinit var actionButtons: List<ImageButton>
    private lateinit var divider: View
    private lateinit var webView: WebView
    private var lastAppliedThemeName: String = ""

    private var toRecipients: List<String> = emptyList()
    private var ccRecipients: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_detail)
        applyThemeToActivity(this)
        lastAppliedThemeName = getStoredThemeName(this)

        val root = findViewById<android.view.View>(R.id.emailDetailRoot)
        applyTopInsetWithHeader(this, root)

        val emailId = intent.getStringExtra("email_id").orEmpty()
        val emailFolder = intent.getStringExtra("email_folder") ?: "INBOX"
        val emailSubject = intent.getStringExtra("email_subject") ?: "No subject"
        val emailSender = intent.getStringExtra("email_sender") ?: "Unknown sender"
        val emailPreview = intent.getStringExtra("email_preview") ?: "No content"
        val hasAttachments = intent.getBooleanExtra("email_has_attachments", false)

        setTitle(R.string.email_title)

        val subjectView = findViewById<TextView>(R.id.emailSubject)
        val fromView = findViewById<TextView>(R.id.emailFrom)
        webView = findViewById(R.id.emailWebView)
        divider = findViewById(R.id.emailDivider)
        val loading = findViewById<ProgressBar>(R.id.emailBodyLoading)

        subjectView.text = emailSubject
        fromView.text = getString(R.string.email_from) + " " + emailSender

        mailRepository = MailRuntime.graph(this).repository

        val actionArchive = findViewById<ImageButton>(R.id.actionArchive)
        val actionJunk = findViewById<ImageButton>(R.id.actionJunk)
        val actionDelete = findViewById<ImageButton>(R.id.actionDelete)
        val actionReply = findViewById<ImageButton>(R.id.actionReply)
        val actionReplyAll = findViewById<ImageButton>(R.id.actionReplyAll)
        val actionForward = findViewById<ImageButton>(R.id.actionForward)
        actionButtons = listOf(
            actionReply, actionReplyAll, actionForward,
            actionArchive, actionJunk, actionDelete,
        )
        applyDetailChrome()

        MailBackgroundExecutor.submit { mailRepository.markRead(emailId, emailFolder) }

        actionArchive.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_archive), emailId) { it.archive(emailId, emailFolder) }
        }
        actionDelete.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_delete), emailId) { it.delete(emailId, emailFolder) }
        }
        actionJunk.setOnClickListener {
            runMailActionAndFinish(getString(R.string.action_junk), emailId) { it.spam(emailId, emailFolder) }
        }
        actionReply.setOnClickListener {
            openCompose(
                to = extractAddress(emailSender),
                subject = withPrefix(emailSubject, "Re:"),
                body = quoteForReply(emailSender, emailPreview),
            )
        }
        actionReplyAll.setOnClickListener {
            val recipients = (listOf(extractAddress(emailSender)) + toRecipients.map(::extractAddress) + ccRecipients.map(::extractAddress))
                .distinct()
                .filter { it.isNotBlank() }
            openCompose(
                to = recipients.joinToString(", "),
                subject = withPrefix(emailSubject, "Re:"),
                body = quoteForReply(emailSender, emailPreview),
            )
        }
        actionForward.setOnClickListener {
            openCompose(
                to = "",
                subject = withPrefix(emailSubject, "Fwd:"),
                body = "\n\n---------- Forwarded message ----------\n" +
                    "From: $emailSender\nSubject: $emailSubject\n\n$emailPreview",
            )
        }

        if (hasAttachments) {
            loadAttachments(emailId, emailFolder)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            defaultTextEncodingName = "utf-8"
        }

        ioExecutor.execute {
            val outcome = mailRepository.fetchBody(emailId, emailFolder)
            val content = (outcome as? MailOutcome.Success)?.value
            val bodyToRender = content?.html?.takeIf { it.isNotBlank() } ?: TextUtils.htmlEncode(emailPreview)
            val palette = getStoredThemePalette(this)

            val htmlContent = """
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <style>
                        body {
                            font-family: monospace;
                            font-size: 16px;
                            line-height: 1.5;
                            color: ${palette.inkStrong};
                            background-color: ${palette.bg};
                            margin: 0;
                            padding: 8px;
                            word-break: break-word;
                        }
                        a { color: ${palette.accent}; }
                        img { max-width: 100%; height: auto; }
                        pre { white-space: pre-wrap; }
                    </style>
                </head>
                <body>$bodyToRender</body>
                </html>
            """.trimIndent()

            runOnUiThread {
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null)
                loading.visibility = android.view.View.GONE
                if (content != null) {
                    toRecipients = content.toAddresses
                    ccRecipients = content.ccAddresses
                }
            }
        }
    }

    private fun loadAttachments(emailId: String, emailFolder: String) {
        ioExecutor.execute {
            val outcome = mailRepository.listAttachments(emailId, emailFolder)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val infos = (outcome as? MailOutcome.Success)?.value.orEmpty()
                renderAttachments(emailId, emailFolder, infos)
            }
        }
    }

    private fun renderAttachments(emailId: String, emailFolder: String, infos: List<AttachmentInfo>) {
        val label = findViewById<TextView>(R.id.emailAttachmentsLabel)
        val chips = findViewById<ChipGroup>(R.id.emailAttachmentChips)
        chips.removeAllViews()
        if (infos.isEmpty()) {
            label.visibility = View.GONE
            chips.visibility = View.GONE
            return
        }
        label.visibility = View.VISIBLE
        chips.visibility = View.VISIBLE
        infos.forEach { info ->
            val chip = Chip(this).apply {
                text = "📎 ${info.name}"
                setOnClickListener { downloadAttachment(emailId, emailFolder, info) }
            }
            applyPillChipTheme(this, chip)
            chips.addView(chip)
        }
    }

    private fun downloadAttachment(emailId: String, emailFolder: String, info: AttachmentInfo) {
        Toast.makeText(this, getString(R.string.attachment_downloading, info.name), Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            val outcome = mailRepository.downloadAttachment(emailId, emailFolder, info.index)
            val saved = (outcome as? MailOutcome.Success)?.value?.let { saveToDownloads(it.name, it.mimeType, it.bytes) } ?: false
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = when {
                    saved -> getString(R.string.attachment_saved, info.name)
                    outcome is MailOutcome.Success -> getString(R.string.attachment_save_failed, info.name)
                    else -> outcome.userFacingMessage() ?: getString(R.string.attachment_save_failed, info.name)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Writes bytes into the shared Downloads collection via MediaStore (no storage permission
     *  needed on the app's minSdk 31). Returns false if the insert or stream write fails. */
    private fun saveToDownloads(name: String, mimeType: String, bytes: ByteArray): Boolean {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return runCatching {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        }.getOrDefault(false)
    }

    override fun onResume() {
        super.onResume()

        val currentTheme = getStoredThemeName(this)
        if (currentTheme != lastAppliedThemeName) {
            recreate()
            return
        }

        applyThemeToActivity(this)
        applyDetailChrome()
    }

    private fun applyDetailChrome() {
        val palette = getStoredThemePalette(this)
        divider.setBackgroundColor(Color.parseColor(palette.line))
        webView.setBackgroundColor(Color.parseColor(palette.bg))
        actionButtons.forEach { applyIconButtonTheme(this, it) }
    }

    private fun runMailActionAndFinish(actionLabel: String, emailId: String, action: (MailRepository) -> Unit) {
        Toast.makeText(this, actionLabel, Toast.LENGTH_SHORT).show()
        MailBackgroundExecutor.submit { action(mailRepository) }
        // Tell InboxActivity which row to drop immediately, mirroring its own swipe-to-archive/
        // delete optimistic removal. Without this, returning here re-triggers InboxActivity's
        // onStart refresh, which races the still-in-flight mutation above and can redraw the row
        // we just "removed" — the mutation still lands, it just looks like the button did nothing.
        setResult(RESULT_OK, Intent().putExtra(EXTRA_REMOVED_EMAIL_ID, emailId))
        finish()
    }

    private fun openCompose(to: String, subject: String, body: String) {
        val intent = Intent(this, ComposeActivity::class.java)
        intent.putExtra(ComposeActivity.EXTRA_TO, to)
        intent.putExtra(ComposeActivity.EXTRA_SUBJECT, subject)
        intent.putExtra(ComposeActivity.EXTRA_BODY, body)
        startActivity(intent)
    }

    private fun quoteForReply(sender: String, preview: String): String {
        return "\n\n$sender wrote:\n$preview"
    }

    private fun withPrefix(subject: String, prefix: String): String {
        return if (subject.trim().startsWith(prefix, ignoreCase = true)) subject else "$prefix $subject"
    }

    private fun extractAddress(raw: String): String {
        return Regex("<([^>]+)>").find(raw)?.groupValues?.get(1) ?: raw
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_REMOVED_EMAIL_ID = "removed_email_id"
    }
}
