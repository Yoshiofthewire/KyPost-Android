package com.urlxl.mail.contacts.device

import com.urlxl.mail.contacts.ContactDto

object DeviceContactMatcher {
    fun normalizeEmail(email: String): String {
        return email.trim().lowercase()
    }

    fun normalizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9]"), "")
    }

    fun findMatch(
        candidateEmails: List<String>,
        candidatePhones: List<String>,
        existing: List<ContactDto>,
    ): String? {
        val normalizedCandidateEmails = candidateEmails.map { normalizeEmail(it) }.toSet()
        val normalizedCandidatePhones = candidatePhones.map { normalizePhone(it) }.toSet()

        for (contact in existing) {
            val contactEmails = contact.emails.map { normalizeEmail(it.value) }.toSet()
            val contactPhones = contact.phones.map { normalizePhone(it.value) }.toSet()

            if ((normalizedCandidateEmails intersect contactEmails).isNotEmpty()) {
                return contact.uid
            }
            if ((normalizedCandidatePhones intersect contactPhones).isNotEmpty()) {
                return contact.uid
            }
        }

        return null
    }
}
