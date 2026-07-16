# Compose Email — remove list options + beauty pass

Date: 2026-07-15
Status: Approved

## Goal

`ComposeActivity`/`activity_compose.xml` reads as cluttered: one flat
`LinearLayout` holds recipients, subject, a 6-chip formatting toolbar, the
body editor, attachments, and actions with no visual grouping. Remove the
bullet/numbered-list formatting chips and give the screen a structural +
visual cleanup, reusing this app's existing design tokens
(`STYLE_GUIDE.md`, `AppTheme.kt`) rather than inventing new ones.

## Remove: list options

- Delete `composeBulletList` / `composeNumberList` chips from
  `activity_compose.xml`.
- Delete their `ComposeActivity.kt` wiring: field decls, `findViewById`,
  click listeners (`toggleUnorderedList`/`toggleOrderedList`), status-sync
  from `editorStatusesFlow`, and their entry in `applyToolbarChipsTheme()`'s
  chip list.
- Delete the now-unused `compose_format_bullets` / `compose_format_numbers`
  strings.
- `RichHtmlEditorWebView.toggleUnorderedList`/`toggleOrderedList` (the
  third-party library API) is untouched — just no longer called from this
  screen.

## Beauty pass: card-sectioned structure

Regroup the flat layout into three `applyPanelBackground`-wrapped cards
(reusing `card_corner_radius` / the Inbox keyword-bar precedent) plus the
unchanged action row:

1. **Details card** — To / Cc / Bcc / Subject stacked, separated by 1dp
   `line`-colored divider `View`s (mirrors `EmailDetailActivity`'s
   `emailDivider` pattern — manually themed in the activity's
   theme-apply function, since plain `View`s aren't auto-themed by
   `applyThemeToViewTree`).
2. **Message card** — icon-only formatting toolbar sits directly above the
   body editor with no gap, read as one control. The editor's separate
   `edit_text_background` rounded frame is dropped since the card now
   supplies that boundary. The existing 320dp fixed-height
   `NestedScrollView` workaround (documented inline, works around
   `RichHtmlEditorWebView`'s self-overriding height) is preserved verbatim.
3. **Attachments card** — same chip row as today, `GONE` when empty, now
   card-wrapped for consistency.

Action row (Cancel/Send) unchanged in behavior/position, just gets more
top margin now that it follows a card instead of a flat list.

## Icons

Add 5 new vector drawables in the project's existing `ic_*.xml` house
style (24dp viewport, `#FF000000` fill, runtime-tinted): `ic_bold.xml`,
`ic_italic.xml`, `ic_underline.xml`, `ic_link.xml`, `ic_attach.xml`.

- Bold/Italic/Underline/Link stay `Chip`s (keeps the existing
  checked/unchecked `applyPillChipTheme` toggle behavior) but go
  icon-only. `applyPillChipTheme` gains a small backward-compatible
  addition: tint `chip.chipIconTint` from the same checked/unchecked
  `ColorStateList` already built for text, so icon-only chips theme
  correctly.
- Attach becomes a non-checkable icon+"Attach" chip instead of a
  borderless text button with an emoji. `compose_attach` string changes
  from `"📎 Attach"` to `"Attach"`.

## Out of scope

- No new dimens — everything reuses `card_corner_radius` and palette
  colors already exposed via `getStoredThemePalette`.
- No changes to `RichHtmlEditorWebView` itself or its height-measurement
  workaround.
- No test changes — no existing test references the removed chips or
  `ComposeActivity`.

## Verification

Build the app and visually check the Compose screen (all 3 cards render,
dividers themed, icon chips toggle correctly, attach flow still works,
send/cancel unaffected) since there's no automated UI coverage here.
