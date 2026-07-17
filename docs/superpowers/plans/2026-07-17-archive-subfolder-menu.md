# Archive Subfolders in Folder Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Archive" item to the Inbox tab's folder-picker popup that opens a second popup listing the server's Archive subfolders (fetched via the existing, currently-unused `listFolders` relay API), letting the user switch to one.

**Architecture:** `MailRepository` gains a one-line `listFolders` passthrough to `RelayMailSource` (already fully implemented server-side). `InboxActivity.showFolderPickerPopup` gains a 4th "Archive" item; picking it fetches subfolders on the existing `ioExecutor` background executor and shows a second `PopupMenu` anchored to the same view. A shared `switchFolder(folder)` helper (extracted from the three duplicated inline blocks) is reused by both popups and by the new Archive-subfolder picks.

**Tech Stack:** Kotlin, `android.widget.PopupMenu`, existing `ioExecutor`/`runOnUiThread` threading pattern.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-17-archive-subfolder-menu-design.md`.
- No changes to `RelayMailSource`, the server, or `FolderEntity`/`FolderDao` — this feature calls the relay directly each time, bypassing the currently-unused Room folder cache.
- No caching/prefetching of the Archive subfolder list — fetch fresh every time "Archive" is tapped.
- No `MailRepository.listFolders` unit test — none of the repository's other trivial passthroughs (`send`, `archive`, `spam`, `delete`, `markRead`, `move`, `listAttachments`, `downloadAttachment`) have one either; `MailRepositoryTest.kt` only covers `reconcileFetchResult`.
- New strings: `nav_archive` = "Archive", `no_archive_folders` = "No archive folders found". Do not reuse `action_archive` (that string is scoped to the per-email swipe/detail archive action, a different concept).
- Build command: `./gradlew assembleDebug` from the repo root.

---

### Task 1: Archive item, subfolder fetch, and second popup

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/mail/MailRepository.kt`
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MailSource.listFolders(parent: String?): MailOutcome<FolderListResult>` (`MailSource.kt:87`, already implemented by `RelayMailSource.kt:78-93`) — unchanged, just newly wired up.
- Produces: `MailRepository.listFolders(parent: String?): MailOutcome<FolderListResult>` — a new passthrough Task 1 (of the *prior* plan) didn't need and this task adds; no other task in this plan depends on it beyond this one.

- [ ] **Step 1: Add the `MailRepository.listFolders` passthrough**

  In `app/src/main/java/com/urlxl/mail/mail/MailRepository.kt`, add this method (placement: anywhere among the other one-line passthroughs, e.g. directly after `send`):

  ```kotlin
      fun listFolders(parent: String?): MailOutcome<FolderListResult> = relaySource.listFolders(parent)
  ```

- [ ] **Step 2: Add the two new strings**

  In `app/src/main/res/values/strings.xml`, add `nav_archive` directly after the existing `nav_trash` line (currently `app/src/main/res/values/strings.xml:28`):

  ```xml
      <string name="nav_trash">Trash</string>
      <string name="nav_archive">Archive</string>
  ```

  And add `no_archive_folders` directly after the existing `finding_email` line (currently `app/src/main/res/values/strings.xml:119`):

  ```xml
      <string name="finding_email">Finding new email…</string>
      <string name="no_archive_folders">No archive folders found</string>
  ```

- [ ] **Step 3: Add the `FolderInfo` import**

  In `app/src/main/java/com/urlxl/mail/InboxActivity.kt`, the import block currently reads (around line 26-30):

  ```kotlin
  import com.urlxl.mail.mail.MailFetchResult
  import com.urlxl.mail.mail.MailOutcome
  import com.urlxl.mail.mail.MailRepository
  import com.urlxl.mail.mail.MailRuntime
  import com.urlxl.mail.mail.userFacingMessage
  ```

  Add one import so it reads:

  ```kotlin
  import com.urlxl.mail.mail.FolderInfo
  import com.urlxl.mail.mail.MailFetchResult
  import com.urlxl.mail.mail.MailOutcome
  import com.urlxl.mail.mail.MailRepository
  import com.urlxl.mail.mail.MailRuntime
  import com.urlxl.mail.mail.userFacingMessage
  ```

- [ ] **Step 4: Extract `switchFolder` and add the Archive item + subfolder popups**

  In `app/src/main/java/com/urlxl/mail/InboxActivity.kt`, replace the existing `showFolderPickerPopup` function (currently `InboxActivity.kt:479-499`):

  ```kotlin
      private fun showFolderPickerPopup(anchor: View) {
          val popupMenu = PopupMenu(this, anchor)
          popupMenu.menu.add(0, 0, 0, getString(R.string.nav_inbox))
          popupMenu.menu.add(0, 1, 1, getString(R.string.nav_junk))
          popupMenu.menu.add(0, 2, 2, getString(R.string.nav_trash))

          popupMenu.setOnMenuItemClickListener { menuItem ->
              val folder = when (menuItem.itemId) {
                  0 -> "INBOX"
                  1 -> "Junk"
                  2 -> "Trash"
                  else -> return@setOnMenuItemClickListener false
              }
              currentFolder = folder
              selectedTab = KeywordTabs.ALL
              applyFolderTitle()
              refreshInbox()
              true
          }
          popupMenu.show()
      }
  ```

  with this (extracts the repeated switch-folder block into `switchFolder`, adds the Archive item, and adds the fetch + second-popup functions right after it):

  ```kotlin
      private fun switchFolder(folder: String) {
          currentFolder = folder
          selectedTab = KeywordTabs.ALL
          applyFolderTitle()
          refreshInbox()
      }

      private fun showFolderPickerPopup(anchor: View) {
          val popupMenu = PopupMenu(this, anchor)
          popupMenu.menu.add(0, 0, 0, getString(R.string.nav_inbox))
          popupMenu.menu.add(0, 1, 1, getString(R.string.nav_junk))
          popupMenu.menu.add(0, 2, 2, getString(R.string.nav_trash))
          popupMenu.menu.add(0, 3, 3, getString(R.string.nav_archive))

          popupMenu.setOnMenuItemClickListener { menuItem ->
              val folder = when (menuItem.itemId) {
                  0 -> "INBOX"
                  1 -> "Junk"
                  2 -> "Trash"
                  3 -> {
                      fetchAndShowArchiveSubfolders(anchor)
                      return@setOnMenuItemClickListener true
                  }
                  else -> return@setOnMenuItemClickListener false
              }
              switchFolder(folder)
              true
          }
          popupMenu.show()
      }

      private fun fetchAndShowArchiveSubfolders(anchor: View) {
          ioExecutor.execute {
              val outcome = mailRepository.listFolders(ARCHIVE_PARENT_FOLDER)
              runOnUiThread {
                  if (outcome is MailOutcome.Success) {
                      showArchiveSubfoldersPopup(anchor, outcome.value.folders)
                  } else {
                      val errorMessage = outcome.userFacingMessage()
                      if (errorMessage != null) {
                          Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                      }
                  }
              }
          }
      }

      private fun showArchiveSubfoldersPopup(anchor: View, folders: List<FolderInfo>) {
          if (folders.isEmpty()) {
              Toast.makeText(this, R.string.no_archive_folders, Toast.LENGTH_SHORT).show()
              return
          }
          val popupMenu = PopupMenu(this, anchor)
          folders.forEachIndexed { index, folder ->
              popupMenu.menu.add(0, index, index, folder.path.substringAfterLast('/'))
          }
          popupMenu.setOnMenuItemClickListener { menuItem ->
              val folder = folders.getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
              switchFolder(folder.path)
              true
          }
          popupMenu.show()
      }
  ```

  `Toast` is already imported (`InboxActivity.kt:16`); `PopupMenu` is already imported (`InboxActivity.kt:14`). No new imports needed for this step beyond `FolderInfo` from Step 3.

- [ ] **Step 5: Make `applyFolderTitle` recognize Archive subfolder paths**

  Replace the existing `applyFolderTitle` function (currently `InboxActivity.kt:133-142`):

  ```kotlin
      private fun applyFolderTitle() {
          val folderLabel = when (currentFolder) {
              "Junk" -> getString(R.string.nav_junk)
              "Trash" -> getString(R.string.nav_trash)
              else -> getString(R.string.nav_inbox)
          }
          val title = getString(R.string.inbox_heading, folderLabel)
          applyThemedTitle(this, title)
          headerFolderTitle.text = title
      }
  ```

  with this:

  ```kotlin
      private fun applyFolderTitle() {
          val folderLabel = when {
              currentFolder == "Junk" -> getString(R.string.nav_junk)
              currentFolder == "Trash" -> getString(R.string.nav_trash)
              currentFolder.startsWith("$ARCHIVE_PARENT_FOLDER/") -> currentFolder.substringAfterLast('/')
              else -> getString(R.string.nav_inbox)
          }
          val title = getString(R.string.inbox_heading, folderLabel)
          applyThemedTitle(this, title)
          headerFolderTitle.text = title
      }
  ```

- [ ] **Step 6: Add the `ARCHIVE_PARENT_FOLDER` constant**

  In `app/src/main/java/com/urlxl/mail/InboxActivity.kt`, the companion object currently reads (around line 644-652):

  ```kotlin
      companion object {
          private const val REFRESH_INTERVAL_MS = 90_000L
          private const val PENDING_MESSAGE_POLL_INTERVAL_MS = 3_000L
          private const val PENDING_MESSAGE_TIMEOUT_MS = 30_000L
          private const val MENU_PGP_KEY = 0
          private const val MENU_KEYWORDS = 1
          private const val MENU_THEMES = 2
          private const val MENU_PUSH_PAIRING = 3
          private const val MENU_ABOUT = 4
  ```

  Add one line so it reads:

  ```kotlin
      companion object {
          private const val REFRESH_INTERVAL_MS = 90_000L
          private const val PENDING_MESSAGE_POLL_INTERVAL_MS = 3_000L
          private const val PENDING_MESSAGE_TIMEOUT_MS = 30_000L
          private const val ARCHIVE_PARENT_FOLDER = "Archive"
          private const val MENU_PGP_KEY = 0
          private const val MENU_KEYWORDS = 1
          private const val MENU_THEMES = 2
          private const val MENU_PUSH_PAIRING = 3
          private const val MENU_ABOUT = 4
  ```

- [ ] **Step 7: Build**

  Run: `./gradlew assembleDebug`
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Manually verify on a device/emulator**

  No automated test covers this navigation flow (manual-only, matching this codebase's existing convention for `InboxActivity`'s UI wiring — no androidTest references it). Install and launch the app, then check:
  - Tap the Inbox tab → popup shows Inbox / Junk / Trash / Archive (4 items now).
  - Tap Archive → popup closes; a second popup opens listing the server's Archive subfolders by leaf name (e.g. a server path `Archive/JohnDoe` shows as "JohnDoe").
  - Pick a subfolder → header shows "KyPost - <name>", list refreshes to that folder, keyword tabs reset to "All".
  - Tap the Inbox tab again → back to the top-level Inbox/Junk/Trash/Archive popup, not stuck on the subfolder list.
  - If reachable, simulate a failure (e.g. temporarily break pairing) → tapping Archive shows a Toast with the relay's error message, no popup opens.
  - If the server returns zero Archive subfolders → short "No archive folders found" Toast, no empty popup opens.

- [ ] **Step 9: Commit**

  ```bash
  git add app/src/main/java/com/urlxl/mail/mail/MailRepository.kt app/src/main/java/com/urlxl/mail/InboxActivity.kt app/src/main/res/values/strings.xml
  git commit -m "feat: add Archive subfolders to the Inbox tab folder picker"
  ```
