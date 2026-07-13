package com.urlxl.mail

data class Email(
    val id: String,
    val subject: String,
    val sender: String,
    val preview: String,
    val keywords: Set<String> = emptySet(),
    val folder: String = "",
    val sentTo: String = "",
    val cc: String = "",
    val bcc: String = "",
    val body: String? = null,
    val label: String = "",
    val status: String = "unread",
    val atUtc: String? = null,
    val hasAttachments: Boolean = false,
    val sourceMode: String = "relay",
)