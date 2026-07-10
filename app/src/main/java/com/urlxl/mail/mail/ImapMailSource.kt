package com.urlxl.mail.mail

import com.urlxl.mail.MailGateway

private const val FOLDER_MANAGEMENT_UNSUPPORTED = "Folder management not supported in Manual IMAP mode"

/** Thin wrapper over the existing, unmodified [MailGateway] — no IMAP behavior changes here. */
class ImapMailSource(private val gateway: MailGateway) : MailSource {

    override fun fetchInbox(mailbox: String, limit: Int, forceFullResync: Boolean): MailOutcome<MailFetchResult> {
        // No delta/cursor concept for direct IMAP mode — every fetch is already a live full fetch.
        val emails = gateway.fetchEmails(mailbox, limit)
        val error = gateway.lastError
        return if (error != null) {
            MailOutcome.UpstreamFailure(error)
        } else {
            MailOutcome.Success(MailFetchResult(tabs = emptyList(), messages = emails))
        }
    }

    override fun listFolders(parent: String?): MailOutcome<FolderListResult> =
        MailOutcome.BadRequest(FOLDER_MANAGEMENT_UNSUPPORTED)

    override fun createFolder(parent: String, name: String): MailOutcome<Unit> =
        MailOutcome.BadRequest(FOLDER_MANAGEMENT_UNSUPPORTED)

    override fun renameFolder(folder: String, name: String): MailOutcome<Unit> =
        MailOutcome.BadRequest(FOLDER_MANAGEMENT_UNSUPPORTED)

    override fun deleteFolder(folder: String): MailOutcome<Unit> =
        MailOutcome.BadRequest(FOLDER_MANAGEMENT_UNSUPPORTED)

    override fun performAction(
        action: MailAction,
        messageIds: List<String>,
        mailbox: String,
        targetMailbox: String?,
    ): MailOutcome<MailActionOutcome> {
        // MailGateway's mutating calls are fire-and-forget (log-only on failure, no return value) —
        // this wrapper preserves that exact behavior rather than inventing new failure reporting.
        messageIds.forEach { id ->
            when (action) {
                MailAction.DELETE -> gateway.deleteEmail(id, mailbox)
                MailAction.ARCHIVE -> gateway.moveEmail(id, "[Gmail]/All Mail", mailbox)
                MailAction.SPAM -> gateway.moveEmail(id, "Spam", mailbox)
                MailAction.READ -> gateway.markAsRead(id, mailbox)
                MailAction.MOVE -> gateway.moveEmail(id, targetMailbox.orEmpty(), mailbox)
            }
        }
        return MailOutcome.Success(MailActionOutcome(processed = messageIds.size, failed = emptyList()))
    }

    override fun saveDraft(draft: MailDraft): MailOutcome<Unit> =
        MailOutcome.BadRequest("Drafts are not supported in Manual IMAP mode")

    override fun sendMail(draft: MailDraft): MailOutcome<MailSendOutcome> {
        gateway.sendEmail(draft.to, draft.subject, draft.body, isHtml = draft.mode == "html")
        return MailOutcome.Success(MailSendOutcome(sentSaved = true, warning = ""))
    }

    override fun fetchMessageBody(messageId: String, folder: String): MailOutcome<MailMessageBody> {
        val content = gateway.fetchEmailContent(messageId, folder)
            ?: return MailOutcome.BadRequest("Message not found")
        return MailOutcome.Success(
            MailMessageBody(html = content.html, toAddresses = content.toAddresses, ccAddresses = content.ccAddresses),
        )
    }
}
