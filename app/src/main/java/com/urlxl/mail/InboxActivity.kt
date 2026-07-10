package com.urlxl.mail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.urlxl.mail.mail.MailFetchResult
import com.urlxl.mail.mail.MailOutcome
import com.urlxl.mail.mail.MailRepository
import com.urlxl.mail.mail.MailRuntime
import com.urlxl.mail.mail.userFacingMessage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InboxActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var keywordChipScroll: View
    private lateinit var keywordChips: ChipGroup
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var inboxRoot: View
    private lateinit var inboxContent: View
    private lateinit var adapter: EmailAdapter
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mailRepository: MailRepository
    private lateinit var keywordSettings: KeywordSettings
    private var currentFolder = "INBOX"
    private var lastAppliedThemeName: String = ""

    private var selectedTab = KeywordTabs.ALL
    private var allEmails: List<Email> = emptyList()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshInbox()
            scheduleNextRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox)
        applyThemeToActivity(this)
        lastAppliedThemeName = getStoredThemeName(this)

        mailRepository = MailRuntime.graph(this).repository
        keywordSettings = KeywordSettings(this)

        initViews()
        applyFolderTitle()
        applyTopInsetWithHeader(this, inboxContent)
        applyBottomInset(bottomNav)
        applyInboxThemeChrome()
        setupRecyclerView()
        setupTabs()
        setupBottomNav()
        setupSwipeGestures()
    }

    private fun applyFolderTitle() {
        val folderLabel = when (currentFolder) {
            "Spam" -> getString(R.string.nav_spam)
            "Trash" -> getString(R.string.nav_trash)
            else -> getString(R.string.nav_inbox)
        }
        applyThemedTitle(this, getString(R.string.inbox_heading, folderLabel))
    }

    override fun onStart() {
        super.onStart()
        refreshInbox()
        scheduleNextRefresh()
    }

    override fun onResume() {
        super.onResume()

        val currentTheme = getStoredThemeName(this)
        if (currentTheme != lastAppliedThemeName) {
            recreate()
            return
        }

        applyThemeToActivity(this)
        applyInboxThemeChrome()
        adapter.notifyDataSetChanged()
        rebuildTabs(allEmails)
        renderFilteredEmails()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    private fun initViews() {
        inboxRoot = findViewById(R.id.inboxRoot)
        inboxContent = findViewById(R.id.inboxContent)
        recyclerView = findViewById(R.id.recyclerViewInbox)
        keywordChipScroll = findViewById(R.id.keywordChipScroll)
        keywordChips = findViewById(R.id.keywordChipGroup)
        bottomNav = findViewById(R.id.bottomNavigation)
        loadingSpinner = findViewById(R.id.loadingSpinner)
    }

    private fun applyInboxThemeChrome() {
        val palette = getStoredThemePalette(this)
        val bg = Color.parseColor(palette.bg)
        val panel = Color.parseColor(palette.panel)
        val ink = Color.parseColor(palette.ink)
        val inkStrong = Color.parseColor(palette.inkStrong)
        val accent = Color.parseColor(palette.accent)

        inboxRoot.setBackgroundColor(bg)
        inboxContent.setBackgroundColor(bg)
        recyclerView.setBackgroundColor(bg)

        // Rounded panel bar behind the keyword pills, matching the app's 14dp card/panel radius.
        keywordChipScroll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(panel)
        }

        // Re-style every existing chip in place so a theme switch recolors them even when
        // rebuildTabs() short-circuits because the keyword set itself hasn't changed.
        for (index in 0 until keywordChips.childCount) {
            (keywordChips.getChildAt(index) as? Chip)?.let { styleKeywordChip(it) }
        }

        bottomNav.backgroundTintList = null
        bottomNav.setBackgroundColor(panel)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val colors = intArrayOf(inkStrong, ink)
        val list = ColorStateList(states, colors)
        bottomNav.itemTextColor = list
        bottomNav.itemIconTintList = list
        bottomNav.itemRippleColor = ColorStateList.valueOf(adjustAlpha(accent, 0.20f))
        bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(adjustAlpha(accent, 0.30f))
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun setupRecyclerView() {
        adapter = EmailAdapter(emptyList()) { email ->
            val intent = Intent(this, EmailDetailActivity::class.java)
            intent.putExtra("email_id", email.id)
            intent.putExtra("email_subject", email.subject)
            intent.putExtra("email_sender", email.sender)
            intent.putExtra("email_preview", email.preview)
            intent.putExtra("email_folder", currentFolder)
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupTabs() {
        keywordChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedTab = (group.findViewById<Chip>(checkedId))?.text?.toString().orEmpty().ifBlank { KeywordTabs.ALL }
            renderFilteredEmails()
        }

        rebuildTabs(emptyList())
    }

    private fun styleKeywordChip(chip: Chip) = applyPillChipTheme(this, chip)

    private fun scheduleNextRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        // ponytail: foreground best-effort cadence; upgrade path is server push + work resumption.
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun refreshInbox() {
        // No emails held in memory yet (cold open, or a just-switched-to folder) — render the
        // Room cache immediately so the list isn't empty while the network round trip is in
        // flight, then let the fetch below overwrite it with fresh data.
        val showCacheFirst = allEmails.isEmpty()
        if (showCacheFirst) {
            loadingSpinner.visibility = android.view.View.VISIBLE
        }
        ioExecutor.execute {
            if (showCacheFirst) {
                val cached = mailRepository.cachedEmails(currentFolder)
                if (cached.isNotEmpty()) {
                    runOnUiThread {
                        loadingSpinner.visibility = android.view.View.GONE
                        allEmails = cached
                        rebuildTabs(cached)
                        renderFilteredEmails()
                    }
                }
            }
            val outcome: MailOutcome<MailFetchResult> = mailRepository.refreshFolder(currentFolder)
            val emails = (outcome as? MailOutcome.Success)?.value?.messages
                ?: mailRepository.cachedEmails(currentFolder)
            val errorMessage = outcome.userFacingMessage()
            keywordSettings.rememberKeywords(emails.flatMap { it.keywords }.toSet())
            runOnUiThread {
                loadingSpinner.visibility = android.view.View.GONE
                allEmails = emails
                rebuildTabs(emails)
                renderFilteredEmails()
                if (errorMessage != null) {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun rebuildTabs(emails: List<Email>) {
        // Always show every allowed (visible-in-Keyword-Settings) keyword the app has ever seen,
        // not just ones present in the current email batch — a keyword tab shouldn't disappear
        // just because its last matching email got archived/deleted/filtered to another folder.
        val discoveredThisBatch = KeywordTabs.buildTabs(emails).drop(1).toSet()
        keywordSettings.rememberKeywords(discoveredThisBatch)
        val allowedKeywords = keywordSettings.filterVisible(keywordSettings.getAllKeywords()).sortedBy { it.lowercase() }
        val tabs = listOf(KeywordTabs.ALL) + allowedKeywords

        val current = mutableListOf<String>()
        for (index in 0 until keywordChips.childCount) {
            current.add((keywordChips.getChildAt(index) as? Chip)?.text?.toString().orEmpty())
        }
        if (tabs != current) {
            keywordChips.removeAllViews()
            if (!tabs.contains(selectedTab)) {
                selectedTab = KeywordTabs.ALL
            }
            tabs.forEach { keyword ->
                val chip = Chip(this).apply {
                    text = keyword
                    isCheckable = true
                    isClickable = true
                    isChecked = keyword == selectedTab
                }
                styleKeywordChip(chip)
                keywordChips.addView(chip)
            }
        }

        // Unread state (bold text + a small leading accent dot, matching the same cue used on
        // inbox rows in EmailAdapter) tracks unread counts, which can change on a refresh even
        // when the keyword set itself doesn't, so refresh it unconditionally rather than folding
        // it into the rebuild check above.
        val dotSizePx = (7 * resources.displayMetrics.density).toInt()
        for (index in 0 until keywordChips.childCount) {
            val chip = keywordChips.getChildAt(index) as? Chip ?: continue
            val keyword = chip.text.toString()
            val hasUnread = emails.any {
                it.status == "unread" && (keyword == KeywordTabs.ALL || it.keywords.contains(keyword))
            }
            chip.setTypeface(chip.typeface, if (hasUnread) Typeface.BOLD else Typeface.NORMAL)
            chip.isChipIconVisible = hasUnread
            if (hasUnread) {
                chip.chipIconSize = dotSizePx.toFloat()
                chip.chipIcon = unreadDotDrawable(this, sizeDp = 7)
                // A checked chip is already accent-filled, so an accent-colored dot would
                // disappear into it — use the chip's own (contrasting) text color instead. This
                // has to be a ColorStateList (like chipBackgroundColor/chipStrokeColor already
                // are), not a one-off flat color: tapping a chip only toggles its checked state,
                // it doesn't re-run this loop, so a flat color baked in at whatever checked state
                // happened to be current here goes stale the moment the user selects a different
                // pill — which is exactly what showed as a dot stuck black in dark themes.
                val accent = Color.parseColor(getStoredThemePalette(this).accent)
                val onAccent = readableOn(accent)
                val checkedState = intArrayOf(android.R.attr.state_checked)
                val uncheckedState = intArrayOf(-android.R.attr.state_checked)
                chip.chipIconTint = ColorStateList(arrayOf(checkedState, uncheckedState), intArrayOf(onAccent, accent))
            } else {
                chip.chipIcon = null
            }
        }
    }

    private fun renderFilteredEmails() {
        val filtered = KeywordTabs.filterEmails(allEmails, selectedTab)
        adapter.updateEmails(filtered)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_KEYWORDS, 0, R.string.menu_keywords)
        menu?.add(0, MENU_CONTACTS, 1, R.string.menu_contacts)
        menu?.add(0, MENU_SETTINGS, 2, R.string.menu_settings)
        menu?.add(0, MENU_THEMES, 3, R.string.menu_themes)
        menu?.add(0, MENU_PUSH_PAIRING, 4, R.string.menu_push_pairing)
        menu?.add(0, MENU_ABOUT, 5, R.string.menu_about)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_KEYWORDS -> {
                startActivity(Intent(this, KeywordSettingsActivity::class.java))
                true
            }
            MENU_CONTACTS -> {
                startActivity(Intent(this, com.urlxl.mail.contacts.ContactsListActivity::class.java))
                true
            }
            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            MENU_THEMES -> {
                startActivity(Intent(this, ThemesActivity::class.java))
                true
            }
            MENU_PUSH_PAIRING -> {
                startActivity(Intent(this, com.urlxl.mail.push.PushPairingActivity::class.java))
                true
            }
            MENU_ABOUT -> {
                showAboutDialog(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_compose -> {
                    startActivity(Intent(this, ComposeActivity::class.java))
                    true
                }
                R.id.nav_spam -> {
                    currentFolder = "Spam"
                    selectedTab = KeywordTabs.ALL
                    applyFolderTitle()
                    refreshInbox()
                    true
                }
                R.id.nav_trash -> {
                    currentFolder = "Trash"
                    selectedTab = KeywordTabs.ALL
                    applyFolderTitle()
                    refreshInbox()
                    true
                }
                R.id.nav_inbox -> {
                    currentFolder = "INBOX"
                    selectedTab = KeywordTabs.ALL
                    applyFolderTitle()
                    refreshInbox()
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_inbox
    }

    private fun setupSwipeGestures() {
        val iconSize = (24 * resources.displayMetrics.density).toInt()
        val iconMargin = (16 * resources.displayMetrics.density).toInt()
        val archiveIcon = ContextCompat.getDrawable(this, R.drawable.ic_archive)?.mutate()?.apply {
            setTint(readableOn(SWIPE_ARCHIVE_COLOR))
        }
        val deleteIcon = ContextCompat.getDrawable(this, R.drawable.ic_delete)?.mutate()?.apply {
            setTint(readableOn(SWIPE_DELETE_COLOR))
        }
        // Rounded on the same side as the row's own corners (item_email.xml's 14dp
        // cardCornerRadius) so the reveal doesn't show a sharp corner poking out from behind the
        // rounded card as it slides away.
        val cardRadius = 14f * resources.displayMetrics.density
        val deleteBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(SWIPE_DELETE_COLOR)
            cornerRadii = floatArrayOf(cardRadius, cardRadius, 0f, 0f, 0f, 0f, cardRadius, cardRadius)
        }
        val archiveBackground = android.graphics.drawable.GradientDrawable().apply {
            setColor(SWIPE_ARCHIVE_COLOR)
            cornerRadii = floatArrayOf(0f, 0f, cardRadius, cardRadius, cardRadius, cardRadius, 0f, 0f)
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val iconTop = itemView.top + (itemView.height - iconSize) / 2
                    val iconBottom = iconTop + iconSize

                    when {
                        dX > 0 -> {
                            deleteBackground.setBounds(
                                itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom
                            )
                            deleteBackground.draw(c)
                            if (dX > iconSize + iconMargin * 2) {
                                val iconLeft = itemView.left + iconMargin
                                deleteIcon?.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconBottom)
                                deleteIcon?.draw(c)
                            }
                        }
                        dX < 0 -> {
                            archiveBackground.setBounds(
                                itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom
                            )
                            archiveBackground.draw(c)
                            if (-dX > iconSize + iconMargin * 2) {
                                val iconRight = itemView.right - iconMargin
                                archiveIcon?.setBounds(iconRight - iconSize, iconTop, iconRight, iconBottom)
                                archiveIcon?.draw(c)
                            }
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position < 0 || position >= adapter.itemCount) return
                val email = adapter.getEmailAt(position)
                // Remove the row immediately and let the IMAP call finish on its own; waiting for
                // the network round trip before updating the list is what made swipes feel slow.
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        allEmails = allEmails.filter { it.id != email.id }
                        renderFilteredEmails()
                        MailBackgroundExecutor.submit {
                            mailRepository.archive(email.id, currentFolder)
                        }
                    }
                    ItemTouchHelper.RIGHT -> {
                        allEmails = allEmails.filter { it.id != email.id }
                        renderFilteredEmails()
                        MailBackgroundExecutor.submit {
                            mailRepository.delete(email.id, currentFolder)
                        }
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 90_000L
        private const val MENU_KEYWORDS = 0
        private const val MENU_CONTACTS = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_THEMES = 3
        private const val MENU_PUSH_PAIRING = 4
        private const val MENU_ABOUT = 5
        private val SWIPE_ARCHIVE_COLOR = Color.parseColor(COLOR_WARNING)
        private val SWIPE_DELETE_COLOR = Color.parseColor(COLOR_DANGER)
    }
}