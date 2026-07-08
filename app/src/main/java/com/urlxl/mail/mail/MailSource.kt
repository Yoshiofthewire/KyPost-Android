package com.urlxl.mail.mail

import com.urlxl.mail.Email

sealed class MailOutcome<out T> {
    data class Success<T>(val value: T) : MailOutcome<T>()

    /** Relay 400 "imap configuration is required*" — direct the user to the web app, never a form. */
    data class NotConfigured(val message: String) : MailOutcome<Nothing>()

    /** Relay/pairing 401 — re-pair the device. */
    data class Unauthorized(val message: String) : MailOutcome<Nothing>()

    /** Relay 503 (either flavor: no PAIRING_SECRET, or IMAP client unavailable). */
    data class ServiceUnavailable(val message: String) : MailOutcome<Nothing>()

    /** Relay 502 upstream IMAP/SMTP failure — safe to retry with backoff. */
    data class UpstreamFailure(val message: String) : MailOutcome<Nothing>()

    /** Any other 400, or a local validation failure. */
    data class BadRequest(val message: String) : MailOutcome<Nothing>()
}

/** Wording tailored per failure kind, per Mobile_Mail_Relay.md's error table — never auto-clears
 *  pairing on 401/503, only points the user at the places that would (Settings/PushPairingActivity). */
fun MailOutcome<*>.userFacingMessage(): String? = when (this) {
    is MailOutcome.Success -> null
    is MailOutcome.NotConfigured -> "Set up your mail account on the web app first"
    is MailOutcome.Unauthorized -> "Device pairing expired or invalid — re-pair this device in Settings"
    is MailOutcome.ServiceUnavailable -> "Mail relay is unavailable: $message"
    is MailOutcome.UpstreamFailure -> "Couldn't reach the mail server: $message"
    is MailOutcome.BadRequest -> message
}

data class MailFetchResult(
    val tabs: List<String>,
    val messages: List<Email>,
    // The rest only matter when isDelta is true (Mobile_Mail_Relay.md Part 5 v2); a false/default
    // value means `messages` is a full snapshot, exactly like the pre-delta response shape.
    val isDelta: Boolean = false,
    val updatedMessageIds: Set<String> = emptySet(),
    val removedMessageIds: List<String> = emptyList(),
)
data class FolderInfo(val path: String, val deletable: Boolean)
data class FolderListResult(val parent: String, val folders: List<FolderInfo>)

enum class MailAction { DELETE, ARCHIVE, SPAM, READ, MOVE }

data class MailActionOutcome(val processed: Int, val failed: List<Pair<String, String>>)

data class MailDraft(
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    val mode: String = "plain",
)

data class MailSendOutcome(val sentSaved: Boolean, val warning: String)

data class MailMessageBody(val html: String, val toAddresses: List<String>, val ccAddresses: List<String>)

/**
 * Blocking (non-suspend) by design: callers already run on a background executor thread
 * (mirroring [com.urlxl.mail.MailGateway]'s own synchronous style), so there is no need to
 * introduce coroutines into the mail path just for this abstraction.
 */
interface MailSource {
    /** [forceFullResync] requests since=0 (full re-fetch reported in delta shape) regardless of
     *  any persisted cursor — the documented self-heal for a missed removal notification. */
    fun fetchInbox(mailbox: String, limit: Int, forceFullResync: Boolean = false): MailOutcome<MailFetchResult>
    fun listFolders(parent: String?): MailOutcome<FolderListResult>
    fun createFolder(parent: String, name: String): MailOutcome<Unit>
    fun renameFolder(folder: String, name: String): MailOutcome<Unit>
    fun deleteFolder(folder: String): MailOutcome<Unit>
    fun performAction(
        action: MailAction,
        messageIds: List<String>,
        mailbox: String,
        targetMailbox: String? = null,
    ): MailOutcome<MailActionOutcome>
    fun saveDraft(draft: MailDraft): MailOutcome<Unit>
    fun sendMail(draft: MailDraft): MailOutcome<MailSendOutcome>
    fun fetchMessageBody(messageId: String, folder: String): MailOutcome<MailMessageBody>
}
