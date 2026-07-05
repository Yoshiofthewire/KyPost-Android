package com.urlxl.mail

import android.content.Context
import android.util.Log
import jakarta.mail.Authenticator
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import androidx.core.text.HtmlCompat
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties

data class MailAccountConfig(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val username: String,
    val password: String,
    val folderName: String,
)

data class EmailContent(
    val html: String,
    val toAddresses: List<String>,
    val ccAddresses: List<String>,
)

class MailGateway(private val config: MailAccountConfig) {

    var lastError: String? = null
        private set

    // The UI addresses folders by logical role ("Spam", "Trash", archive) but servers name those
    // folders inconsistently (Gmail's "[Gmail]/Spam", dovecot's "INBOX.Spam", "Junk", ...). Resolve
    // each role to a real folder name once per store and cache it so we don't relist every action.
    private val resolvedFolderCache = mutableMapOf<String, String>()

    fun isConfigured(): Boolean {
        return config.imapHost.isNotBlank() && config.smtpHost.isNotBlank() && config.username.isNotBlank()
    }

    fun moveEmail(messageId: String, targetFolder: String, sourceFolderName: String = config.folderName) {
        if (!isConfigured()) return

        runCatching {
            val store = connectStore()

            val sourceFolder = store.getFolder(resolveFolder(store, sourceFolderName))
            sourceFolder.open(Folder.READ_WRITE)
            val targetFld = store.getFolder(resolveFolder(store, targetFolder))
            targetFld.open(Folder.READ_WRITE)

            for (msg in sourceFolder.messages) {
                if (msg.getHeader("Message-ID")?.firstOrNull() == messageId) {
                    sourceFolder.copyMessages(arrayOf(msg), targetFld)
                    msg.setFlag(Flags.Flag.DELETED, true)
                    sourceFolder.expunge()
                    break
                }
            }

            sourceFolder.close(true)
            targetFld.close(true)
            store.close()
        }.onFailure {
            Log.w(TAG, "Failed to move email to $targetFolder", it)
        }
    }

    fun deleteEmail(messageId: String, sourceFolderName: String = config.folderName) {
        if (!isConfigured()) return

        runCatching {
            val store = connectStore()

            val folder = store.getFolder(resolveFolder(store, sourceFolderName))
            folder.open(Folder.READ_WRITE)

            for (msg in folder.messages) {
                if (msg.getHeader("Message-ID")?.firstOrNull() == messageId) {
                    msg.setFlag(Flags.Flag.DELETED, true)
                    break
                }
            }

            folder.expunge()
            folder.close(true)
            store.close()
        }.onFailure {
            Log.w(TAG, "Failed to delete email", it)
        }
    }

    fun markAsRead(messageId: String, sourceFolderName: String = config.folderName) {
        if (!isConfigured()) return

        runCatching {
            val store = connectStore()

            val folder = store.getFolder(resolveFolder(store, sourceFolderName))
            folder.open(Folder.READ_WRITE)

            for (msg in folder.messages) {
                if (msg.getHeader("Message-ID")?.firstOrNull() == messageId) {
                    msg.setFlag(Flags.Flag.SEEN, true)
                    break
                }
            }

            folder.close(true)
            store.close()
        }.onFailure {
            Log.w(TAG, "Failed to mark email as read", it)
        }
    }

    fun fetchEmails(folderName: String, limit: Int = DEFAULT_FETCH_LIMIT): List<Email> {
        if (!isConfigured()) {
            lastError = "Mail account is not configured"
            return emptyList()
        }

        var store: Store? = null
        var folder: Folder? = null

        return try {
            store = connectStore()
            folder = store.getFolder(resolveFolder(store, folderName))
            folder.open(Folder.READ_ONLY)

            val total = folder.messageCount
            if (total <= 0) {
                lastError = null
                return emptyList()
            }

            // Fetch only the newest `limit` messages by range instead of enumerating the whole
            // folder. A large Trash/All-Mail could stall the previous folder.messages walk; this
            // keeps the work bounded no matter how many messages the folder holds. getMessages is
            // 1-indexed and inclusive.
            val start = (total - limit + 1).coerceAtLeast(1)
            val messages = folder.getMessages(start, total)

            // Prefetch envelope + flags for the whole batch in one round trip so per-message
            // subject/sender/keyword access below doesn't issue a request each.
            val profile = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
            }
            folder.fetch(messages, profile)

            val result = messages
                .toList()
                .asReversed()
                .map(::toEmail)
            lastError = null
            result
        } catch (ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            Log.w(TAG, "Failed to fetch emails for folder=$folderName", ex)
            emptyList()
        } finally {
            runCatching {
                if (folder?.isOpen == true) {
                    folder.close(false)
                }
            }
            runCatching {
                store?.close()
            }
        }
    }

    fun sendEmail(to: String, subject: String, body: String) {
        if (!isConfigured()) {
            return
        }

        val props = Properties().apply {
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
        }

        runCatching {
            val session = Session.getInstance(props, authenticator())
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.username))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                sentDate = Date()
                setSubject(subject)
                setText(body)
            }
            Transport.send(message)
        }.onFailure {
            Log.w(TAG, "Failed to send email", it)
        }
    }

    fun fetchEmailContent(messageId: String, folderName: String): EmailContent? {
        if (!isConfigured() || messageId.isBlank()) {
            return null
        }

        var store: Store? = null
        var folder: Folder? = null

        return try {
            store = connectStore()
            folder = store.getFolder(resolveFolder(store, folderName))
            folder.open(Folder.READ_ONLY)

            folder.messages.firstNotNullOfOrNull { msg ->
                val id = msg.getHeader("Message-ID")?.firstOrNull()?.ifBlank { null } ?: "${msg.messageNumber}"
                if (id == messageId) {
                    EmailContent(
                        html = extractHtmlBody(msg),
                        toAddresses = msg.getRecipients(Message.RecipientType.TO)?.map { it.toString() }.orEmpty(),
                        ccAddresses = msg.getRecipients(Message.RecipientType.CC)?.map { it.toString() }.orEmpty(),
                    )
                } else {
                    null
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to fetch full email body", ex)
            null
        } finally {
            runCatching {
                if (folder?.isOpen == true) {
                    folder.close(false)
                }
            }
            runCatching {
                store?.close()
            }
        }
    }

    private fun resolveFolder(store: Store, requested: String): String {
        if (requested.equals("INBOX", ignoreCase = true)) return "INBOX"
        resolvedFolderCache[requested]?.let { return it }

        // 1. Trust an exact/known name that actually exists on the server.
        for (candidate in folderCandidates(requested)) {
            if (runCatching { store.getFolder(candidate).exists() }.getOrDefault(false)) {
                resolvedFolderCache[requested] = candidate
                return candidate
            }
        }

        // 2. Fall back to the IMAP SPECIAL-USE attribute (\Junk, \Trash, \All), which is how modern
        // servers advertise these folders regardless of their display name.
        specialUseAttribute(requested)?.let { attribute ->
            val match = runCatching {
                store.defaultFolder.list("*").firstOrNull { folder ->
                    (folder as? IMAPFolder)?.attributes?.any { it.equals(attribute, ignoreCase = true) } == true
                }?.fullName
            }.getOrNull()
            if (match != null) {
                resolvedFolderCache[requested] = match
                return match
            }
        }

        // 3. Give up and use the requested name as-is; the caller's error handling reports a miss.
        resolvedFolderCache[requested] = requested
        return requested
    }

    private fun folderCandidates(requested: String): List<String> = when (requested.lowercase()) {
        "spam", "junk" -> listOf(
            "Spam", "Junk", "[Gmail]/Spam", "INBOX.Spam", "INBOX.Junk", "Junk E-mail", "Junk Email",
        )
        "trash", "deleted", "bin" -> listOf(
            "Trash", "[Gmail]/Trash", "INBOX.Trash", "Deleted Items", "Deleted Messages", "Bin",
        )
        "archive", "all mail", "[gmail]/all mail" -> listOf(
            "[Gmail]/All Mail", "Archive", "All Mail", "INBOX.Archive",
        )
        else -> listOf(requested)
    }

    private fun specialUseAttribute(requested: String): String? = when (requested.lowercase()) {
        "spam", "junk" -> "\\Junk"
        "trash", "deleted", "bin" -> "\\Trash"
        "archive", "all mail", "[gmail]/all mail" -> "\\All"
        else -> null
    }

    private fun connectStore(): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", config.imapHost)
            put("mail.imaps.port", config.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "10000")
            put("mail.imaps.connectiontimeout", "10000")
        }
        val session = Session.getInstance(props, authenticator())
        val store = session.getStore("imaps")
        store.connect(config.imapHost, config.imapPort, config.username, config.password)
        return store
    }

    private fun authenticator(): Authenticator {
        return object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.username, config.password)
            }
        }
    }

    private fun toEmail(message: Message): Email {
        val messageId = message.getHeader("Message-ID")?.firstOrNull()?.ifBlank { null }
            ?: "${message.messageNumber}"

        val subject = message.subject?.ifBlank { "(No subject)" } ?: "(No subject)"
        val sender = message.from?.firstOrNull()?.toString()?.ifBlank { "Unknown sender" } ?: "Unknown sender"
        val preview = extractPreview(message)
        val keywords = message.flags.userFlags.toSet()

        return Email(
            id = messageId,
            subject = subject,
            sender = sender,
            preview = preview,
            keywords = keywords,
        )
    }

    private fun extractPreview(message: Message): String {
        // Building the preview downloads the message body, which for a large message pulls the
        // whole MIME payload (attachments and all) just to show a snippet. That per-message cost is
        // what made attachment-heavy folders like Trash crawl, so skip the body fetch above a size
        // threshold. RFC822.SIZE is already prefetched with the envelope, so this check is free.
        val size = runCatching { message.size }.getOrDefault(-1)
        if (size > MAX_PREVIEW_BYTES) {
            return "(No preview)"
        }
        return runCatching {
            val contentType = message.contentType?.lowercase().orEmpty()
            when (val content = message.content) {
                is String -> if (contentType.contains("text/html")) htmlToPlainText(content) else content
                is Multipart -> extractPreviewFromMultipart(content)
                else -> ""
            }
        }.getOrDefault("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "(No preview)" }
            .take(140)
    }

    private fun extractPreviewFromMultipart(multipart: Multipart): String {
        // Prefer the plain-text alternative; only fall back to HTML (stripped to text) when that's
        // all the message carries, so previews never leak raw markup into the inbox list.
        var htmlFallback = ""
        for (index in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(index)
            val contentType = bodyPart.contentType?.lowercase().orEmpty()
            val content = bodyPart.content

            if (content is String && contentType.contains("text/plain")) {
                return content
            }
            if (content is String && contentType.contains("text/html") && htmlFallback.isBlank()) {
                htmlFallback = htmlToPlainText(content)
            }
            if (content is Multipart) {
                val nested = extractPreviewFromMultipart(content)
                if (nested.isNotBlank()) {
                    return nested
                }
            }
        }
        return htmlFallback
    }

    private fun htmlToPlainText(html: String): String {
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    }

    private fun extractHtmlBody(message: Message): String {
        return runCatching {
            when (val content = message.content) {
                is String -> content
                is Multipart -> extractHtmlFromMultipart(content)
                else -> ""
            }
        }.getOrDefault("")
    }

    private fun extractHtmlFromMultipart(multipart: Multipart): String {
        var plainTextFallback = ""
        for (index in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(index)
            val contentType = bodyPart.contentType?.lowercase().orEmpty()
            val content = bodyPart.content

            if (content is String && contentType.contains("text/html")) {
                return content
            }

            if (content is String && contentType.contains("text/plain") && plainTextFallback.isBlank()) {
                plainTextFallback = content
            }

            if (content is Multipart) {
                val nested = extractHtmlFromMultipart(content)
                if (nested.isNotBlank()) {
                    return nested
                }
            }
        }
        return plainTextFallback
    }

    companion object {
        private const val TAG = "MailGateway"
        private const val DEFAULT_FETCH_LIMIT = 50
        private const val MAX_PREVIEW_BYTES = 256 * 1024

        fun fromSettings(context: Context): MailGateway {
            val settings = MailSettings(context)
            return MailGateway(settings.getConfig())
        }
    }
}
