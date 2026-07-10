package com.urlxl.mail.contacts

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.urlxl.mail.R
import com.urlxl.mail.applyEmptyStateBackground
import com.urlxl.mail.applyThemeToActivity
import com.urlxl.mail.applyThemedTitle
import com.urlxl.mail.applyTopInsetWithHeader
import com.urlxl.mail.data.ContactEntity
import kotlinx.coroutines.launch

class ContactsListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: View
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts_list)
        applyThemeToActivity(this)
        applyThemedTitle(this, getString(R.string.contacts_title))
        applyTopInsetWithHeader(this, findViewById(R.id.contactsRoot))

        recyclerView = findViewById(R.id.recyclerViewContacts)
        emptyText = findViewById(R.id.contactsEmptyText)
        val addButton = findViewById<FloatingActionButton>(R.id.btnAddContact)

        adapter = ContactAdapter { contact ->
            startActivity(
                Intent(this, ContactEditActivity::class.java)
                    .putExtra(ContactEditActivity.EXTRA_UID, contact.uid),
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        addButton.setOnClickListener {
            startActivity(Intent(this, ContactEditActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ContactsRuntime.graph(this@ContactsListActivity).repository.observeContacts().collect { contacts ->
                    render(contacts)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContactsRuntime.graph(this).coordinator.syncNowAsync()
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
        applyEmptyStateBackground(this, emptyText)
        adapter.notifyDataSetChanged()
    }

    private fun render(contacts: List<ContactEntity>) {
        adapter.updateContacts(contacts)
        emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_REFRESH, 0, R.string.contacts_refresh)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_REFRESH -> {
                // User-triggered, so — unlike the silent foreground/post-edit auto-sync — this one
                // reports its outcome back, matching the error table in Mobile_Contact_Sync.md.
                lifecycleScope.launch {
                    val message = when (val outcome = ContactsRuntime.graph(this@ContactsListActivity).repository.sync()) {
                        ContactSyncOutcome.Success -> getString(R.string.contacts_sync_success)
                        ContactSyncOutcome.NotPaired -> getString(R.string.connection_mode_relay_not_paired)
                        ContactSyncOutcome.Unauthorized -> getString(R.string.contacts_sync_unauthorized)
                        is ContactSyncOutcome.ServiceUnavailable -> outcome.message
                        is ContactSyncOutcome.Retry -> outcome.message
                    }
                    Toast.makeText(this@ContactsListActivity, message, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val MENU_REFRESH = 0
    }
}
