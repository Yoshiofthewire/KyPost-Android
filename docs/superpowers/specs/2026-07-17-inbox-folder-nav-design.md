# Move folder picker from header dropdown to Inbox tab

Date: 2026-07-17
Status: Approved

## Goal

`InboxActivity` currently exposes Inbox/Junk/Trash folder switching two
ways that overlap in confusing fashion: a clickable header title
(`headerFolderTitle`, `setupHeaderFolderDropdown()`) with a dropdown
arrow, and a bottom `BottomNavigationView` Inbox tab that silently jumps
straight to the INBOX folder. Consolidate: the header becomes a plain
label, and the Inbox tab becomes the single place folder switching
happens.

## Remove: header dropdown

- `activity_inbox.xml`'s `headerFolderTitle` `TextView`: drop
  `android:clickable="true"`, `android:focusable="true"`,
  `android:background="?attr/selectableItemBackground"`, and
  `android:drawableEnd="@drawable/ic_arrow_drop_down"` (plus its now-unused
  `drawablePadding`). It stays a plain `TextView` showing "KyPost - Inbox"
  / "KyPost - Junk" / "KyPost - Trash" via the existing `applyFolderTitle()`.
- Delete `setupHeaderFolderDropdown()` from `InboxActivity.kt` and its call
  site in `onCreate`.
- `ic_arrow_drop_down.xml` is left in place (may be used elsewhere; not
  confirmed unused, not worth chasing for this change).

## Add: folder picker on the Inbox tab

The three-item `PopupMenu` (Inbox / Junk / Trash) that
`setupHeaderFolderDropdown()` builds today moves as-is into
`setupBottomNav()`, anchored to the Inbox tab's own view
(`bottomNav.findViewById<View>(R.id.nav_inbox)`) instead of the header.
Selecting an item keeps the exact same effect it has today: set
`currentFolder`, reset `selectedTab` to `KeywordTabs.ALL`, call
`applyFolderTitle()`, `refreshInbox()`.

Both places that currently reset straight to `"INBOX"` on an Inbox-tab tap
show this popup instead:

- `onItemSelectedListener`'s `R.id.nav_inbox` branch (`InboxActivity.kt:501-507`)
- `onItemReselectedListener`'s `R.id.nav_inbox` branch (`InboxActivity.kt:523-529`)

Both branches exist because `nav_compose`/`nav_contacts` always return
`false` from the selected-listener (documented at `InboxActivity.kt:508-511`:
launching those screens keeps the Inbox tab marked selected), so in
practice a tap on the Inbox tab is *always* a reselect once the app is
past initial setup — the two branches must stay behaviorally identical.

## Edge case: don't pop up on launch

`setupBottomNav()` ends with `bottomNav.selectedItemId = R.id.nav_inbox`
(`InboxActivity.kt:531`), executed after the listener is registered. If
that still routes through the same branch, the popup would appear on
every cold start. Fix with a local `isInitialSelection` flag: set it
`true`, perform the existing `bottomNav.selectedItemId = R.id.nav_inbox`
assignment, set it back to `false`, and have both the selected- and
reselected-listener branches skip opening the popup (falling back to the
old silent "reset to INBOX" behavior) whenever the flag is `true`. Verify
manually (see below) since this is exactly the kind of thing that's easy
to get wrong silently.

## Out of scope

- `KeywordSettings`, `KeywordTabs`, `ComposeActivity`,
  `ContactsListActivity` — untouched.
- No new strings, drawables, or dimens — reuses `nav_inbox`/`nav_junk`/
  `nav_trash` and the existing popup construction.
- No change to which folders are offered (still just Inbox/Junk/Trash).

## Verification

No automated coverage exists for this navigation flow (manual-only, same
as the precedent in `2026-07-15-compose-email-beauty-pass-design.md`).
Build and check by hand:

1. Cold-launch the app — lands on Inbox, **no popup appears**.
2. From the Inbox screen, tap the Inbox tab — popup opens with
   Inbox/Junk/Trash; each choice switches folder and resets any active
   keyword tab to "All".
3. Navigate to Compose, back out, tap the Inbox tab — popup still opens
   (not a silent reset), confirming the reselect-vs-selected branches stay
   in sync.
4. Header title updates correctly under each folder and is no longer
   clickable (no ripple, no dropdown arrow).
