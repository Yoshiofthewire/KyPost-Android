package com.urlxl.mail.contacts

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.urlxl.mail.R
import com.urlxl.mail.ThemePalette
import com.urlxl.mail.bindAvatar
import com.urlxl.mail.data.ContactEntity
import com.urlxl.mail.getStoredThemePalette

class ContactAdapter(
    private var contacts: List<ContactEntity> = emptyList(),
    private val onContactClick: (ContactEntity) -> Unit,
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    class ContactViewHolder(view: View, private val onContactClick: (ContactEntity) -> Unit) :
        RecyclerView.ViewHolder(view) {
        private val cardView: CardView = view as CardView
        private val avatarView: TextView = view.findViewById(R.id.contactAvatar)
        private val nameView: TextView = view.findViewById(R.id.textViewContactName)
        private val detailView: TextView = view.findViewById(R.id.textViewContactDetail)

        fun bind(contact: ContactEntity, palette: ThemePalette) {
            nameView.text = contact.fn
            detailView.text = contact.org?.takeIf { it.isNotBlank() } ?: ""
            detailView.visibility = if (detailView.text.isBlank()) View.GONE else View.VISIBLE
            bindAvatar(itemView.context, avatarView, contact.fn, sizeDp = 34)

            val panel = Color.parseColor(palette.panel)
            cardView.setCardBackgroundColor(panel)
            nameView.setTextColor(Color.parseColor(palette.inkStrong))
            detailView.setTextColor(Color.parseColor(palette.ink))

            itemView.setOnClickListener { onContactClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view, onContactClick)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position], getStoredThemePalette(holder.itemView.context))
    }

    override fun getItemCount(): Int = contacts.size

    fun updateContacts(newContacts: List<ContactEntity>) {
        contacts = newContacts
        notifyDataSetChanged()
    }
}
