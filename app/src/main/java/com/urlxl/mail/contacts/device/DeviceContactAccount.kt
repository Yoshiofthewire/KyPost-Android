package com.urlxl.mail.contacts.device

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

object DeviceContactAccount {
    const val ACCOUNT_TYPE = "com.urlxl.mail.contacts"
    const val ACCOUNT_NAME = "Llama Mail"

    fun account(): Account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
}

class DeviceContactAccountManager(private val context: Context) {
    private val accountManager = AccountManager.get(context)

    suspend fun ensureAccount(): Boolean {
        val account = DeviceContactAccount.account()
        val existing = accountManager.getAccountsByType(DeviceContactAccount.ACCOUNT_TYPE)
        if (existing.any { it.name == account.name }) {
            return true
        }
        return try {
            accountManager.addAccountExplicitly(account, null, null)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun removeAccount(): Boolean {
        val account = DeviceContactAccount.account()
        return try {
            accountManager.removeAccountExplicitly(account)
        } catch (e: Exception) {
            false
        }
    }
}
