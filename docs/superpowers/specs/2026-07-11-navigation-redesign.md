# Navigation Redesign Spec

**Date:** 2026-07-11  
**Status:** Approved  
**Scope:** Reorganize primary navigation to consolidate folder access and promote Contacts

## Overview

This spec reorganizes the app's navigation structure to reduce bottom navigation clutter and make Contacts a first-class citizen in the bottom bar. Junk and Trash folders move into a header dropdown beneath the Inbox folder name.

## Navigation Changes

### Header Dropdown (Folder Switcher)

**Location:** Title bar, where "Llama Mail - Inbox" currently displays

**Behavior:**
- The folder title becomes tappable with a small dropdown chevron icon (no border/outline)
- Tapping opens a native `PopupMenu` with three options: Inbox, Junk, Trash
- Selecting an option:
  - Updates `currentFolder` to the selected folder name
  - Resets `selectedTab` to `KeywordTabs.ALL`
  - Calls `applyFolderTitle()` to update the header text
  - Calls `refreshInbox()` to load emails from the new folder
- The header continues to show the current folder name (e.g., "Llama Mail - Junk")

**Visual Design:**
- Dropdown icon: small downward-pointing chevron, unoutlined
- Icon placement: immediately after the folder name text
- Styling: inherits existing header theme colors

### Bottom Navigation

**Current items:**
- Inbox
- Compose
- Junk ✗ (removed)
- Trash ✗ (removed)

**New items (in order):**
- Inbox
- Compose
- Contacts (moved from Settings menu)

**Visual changes:**
- All items display with icon over text (stacked vertically)
- Icons selected:
  - Inbox: envelope icon
  - Compose: edit/pen icon
  - Contacts: person/contacts icon

**Behavior:**
- Inbox continues to load the INBOX folder with folder-switching delegated to the header dropdown
- Compose launches ComposeActivity (unchanged)
- Contacts launches ContactsListActivity (moved from menu)

### Settings Menu

**Changes:**
- Remove "Contacts" menu item
- Keep: Keywords, Themes, Pairing, About

## Implementation Details

### Files to Modify

1. **bottom_nav_menu.xml**
   - Remove `<item android:id="@+id/nav_junk" ... />`
   - Remove `<item android:id="@+id/nav_trash" ... />`
   - Add `<item android:id="@+id/nav_contacts" android:title="@string/nav_contacts" />`
   - Reorder to: nav_inbox, nav_compose, nav_contacts

2. **activity_inbox.xml**
   - Make the header title view (likely a TextView) clickable
   - Add padding/margin to accommodate dropdown icon if needed

3. **InboxActivity.kt**
   - **setupBottomNav():** Add handler for `R.id.nav_contacts` that launches `ContactsListActivity`
   - **onCreateOptionsMenu():** Remove `menu?.add(0, MENU_CONTACTS, 1, R.string.menu_contacts)`
   - **Header dropdown:** Add `setupHeaderFolderDropdown()` method that creates and configures a `PopupMenu` with three folder options; wire it to the header title view's click listener
   - **setupSwipeGestures():** No changes (archive/delete behavior applies to all folders)

### Data Flow

The folder-switching logic remains in `setupBottomNav()` and is reused by the header dropdown:
1. Set `currentFolder` to the selected folder
2. Set `selectedTab = KeywordTabs.ALL`
3. Call `applyFolderTitle()` to update the header
4. Call `refreshInbox()` to fetch and render emails

No changes to email fetching, caching, or keyword filtering logic.

## Testing

**Manual verification:**
- [ ] Header dropdown opens on tap and shows three folder options
- [ ] Selecting Inbox/Junk/Trash switches folders and updates header title
- [ ] Header remains "Llama Mail - Inbox" when viewing Inbox; shows "Llama Mail - Junk" when viewing Junk, etc.
- [ ] Bottom nav shows Inbox, Compose, Contacts with icons over text
- [ ] Tapping Contacts launches the contacts list
- [ ] Contacts menu item is gone from the settings menu
- [ ] Folder switching resets keyword tabs to "All"
- [ ] Email list updates when switching folders
- [ ] Swipe gestures (archive/delete) work in all three folders
- [ ] Theme switching still applies to header and bottom nav

## Open Questions

None — design is complete.
