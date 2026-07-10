# Visual Style Guide — aligning with the Llama Labels web app

This app is a sibling to the web app in `../llama labels/frontend` (same product, same
account, same brand). It should read as clearly related to the web app — same colors,
same shape language, same component vocabulary — while still feeling like a native
Android app, not a WebView skin of the desktop UI. When a rule below would force a
non-native pattern (e.g. a custom modal instead of `AlertDialog`), the "stay native"
column wins; see [Section 6](#6-what-to-keep-native--do-not-copy-literally).

Source of truth for colors: `frontend/src/theme.ts` (web) and `AppTheme.kt`
`themePalettes` (mobile). These two must stay numerically identical — if a theme is
added or a hex changes on one side, port it to the other the same day.

## 1. Color system (already shared — keep it that way)

Both apps already ship the same 13 named themes (`Dark Matter`, `Light Matter`,
`Tropics`, `Tropic Night`, `Ocean`, `Coffee`, `White Cliffs`, `Cyber Punk`,
`Neon Purple`, `Space`, `Sky`, `Forest`, `Sun`), default `Dark Matter`. Don't fork this
list per-platform.

Web's `ThemeVars` has more fields than mobile's `ThemePalette` (16 vs 6). Mobile
currently derives everything from `bg/panel/ink/inkStrong/accent/line`. That's fine —
don't add the extra fields (`sidebarStart/End`, `newEmailStart/End`, `glow`, etc.) unless
a specific component needs a gradient stop mobile can't otherwise produce (the avatar
gradient in §3 is the one case that does — extend `ThemePalette` with `accentSoft` only
if/when that's built, not preemptively).

**Semantic colors are theme-invariant on web** (fixed literals in `styles.css`, not part
of the per-theme palette). Mirror that: these are constants, not palette fields.

| Role | Hex / source |
|---|---|
| Danger / delete | `#ff5f5f` (swipe hint), `rgba(255,180,171,.4)` border / `.12` fill (action button) |
| Warning / archive | `#ffd64d` (swipe hint) |
| Success / active status | `#7bbf7b` border, `#a5dca5` text |
| Inactive status | uses `line`/`panel`/`ink` from the active palette, not a fixed color |

## 2. Typography

Web uses **Space Grotesk** (UI text, `--sans`) and **IBM Plex Mono** (code/log/email-body
text, `--mono`). Mobile currently renders the system default font everywhere — this is
the single biggest visual mismatch between the two apps.

Add both as Android **downloadable fonts** (`res/font/*.xml` with a Google Fonts
provider certificate, referenced via `fontFamily` in styles/TextAppearances) rather than
bundling `.ttf` files — zero APK weight added, no new dependency, matches how Android
recommends sourcing Google Fonts.

- Space Grotesk → default `TextAppearance` for all UI chrome (toolbar, buttons, labels,
  list rows).
- IBM Plex Mono → email body text, log viewers, code/monospace fields, DAV/URL/secret
  display (mirrors `.email-reader-body-block`, `.log-line`, `.contacts-dav-url` on web).
- Respect system font-scale (`sp`, never hardcode `px`-equivalent fixed text) even where
  matching a fixed web `rem` scale — accessibility wins over pixel-parity.

## 3. Shape language

| Element | Web value | Mobile target |
|---|---|---|
| Text field / input | `border-radius: 8px` | `fieldBackground` already uses 14dp — keep (it reads as "panel", matches `.panel`/modal radius, not input radius; fine to leave as-is) |
| Primary/secondary button | `border-radius: 8–10px` (`.new-email-button` is 10px) | **Change** `buttonBackground` from 18dp → **10dp**. 18dp reads as a pill on typical button heights; web buttons are a soft rectangle, not a stadium. |
| Card / panel | `border-radius: 12–14px` | 14dp (already used for fields/list rows — consistent) |
| Modal / bottom sheet | `border-radius: 14px` | 14dp on the sheet's top corners |
| Pill badge, filter tab, segmented toggle | `border-radius: 999px` (full stadium) | Use Android's stadium shape (`cornerRadius = height/2` or a `ShapeAppearance` with `50%` family) — **only** for tags/tabs/toggles, never for primary buttons (see row above) |
| Avatar | `border-radius: 50%` (circle) | Circle (`GradientDrawable.OVAL`), already the pattern for status dots — extend to a reusable avatar view (§4) |

## 4. Component patterns to port

Each maps to an existing, named web component so intent stays traceable.

- **Primary button** (`button` / `.users-create-submit`): solid `accent` fill, 10dp
  radius, text color = `readableOn(accent)` (already implemented). Touch feedback is
  Material ripple, not web's `translateY(1px)` — see §6.
- **Ghost/secondary button** (`.notifications-ghost`): transparent fill, 1dp `line`
  stroke, `inkStrong` text.
- **Danger button** (`.users-action-danger`, `.contacts-action-danger`): 1dp stroke +
  12% fill of the fixed danger red from §1, not theme-accent.
- **Pill filter tabs / segmented toggle** (`.inbox-page-tab`, `.contacts-page-tab`,
  `.notifications-delivery-toggle`): stadium chips; inactive = transparent + `line`
  stroke, active = `accent` fill + `readableOn(accent)` text. Prefer Android's native
  `Chip`/`ChipGroup` (single-select mode) over a hand-rolled view — it already renders
  this exact shape and state for free.
- **Status badge + dot** (`.users-badge`, `.contacts-status-active/inactive`): pill
  outline badge with a small leading circular dot, colored by the §1 success/inactive
  colors. Used anywhere a contact/user/device shows an active-vs-inactive state.
- **Circular gradient avatar with initials** (`.users-avatar`, `.contacts-avatar`): 34dp
  (list) / 52dp (detail header) circle, two-stop accent gradient fill, 1dp border,
  initials in `readableOn(accent)`. Worth a small reusable custom view since contacts
  (and any future users list) both want it.
- **Empty state** (`.contacts-empty`, `.inbox-empty-state`): dashed 1dp border in an
  accent-tinted line color, centered muted text, 10dp radius.
- **Section eyebrow label** (`.sidebar-section-label`, `.contact-details-section-title`):
  small uppercase, letter-spaced, ~72%-opacity `inkStrong` — use for group headers only,
  not body copy.
- **Swipe-to-archive/delete on list rows** (`.inbox-row-swipe-*`): doesn't exist yet in
  `EmailAdapter`. If/when swipe gestures are added to the inbox list, use
  `ItemTouchHelper` with the same red=delete / yellow=archive reveal coloring as web.
  Not required now — noted so it isn't accidentally built with different colors later.

## 5. Motion

Web transitions are short: 120–240ms, mostly `ease` or a soft cubic-bezier overshoot on
swipe snapback. Match the duration, not the easing curve — use Android's standard
`FastOutSlowIn`/ripple timing rather than porting web's exact bezier values, which were
tuned for CSS, not View animation.

## 6. What to keep native — do not copy literally

These are the deliberate exceptions, not oversights:

- **Navigation shape.** Don't build a literal left sidebar. Android convention is a
  toolbar + overflow menu (current approach) or bottom navigation; keep whichever the
  app already uses rather than importing the web's 240px rail.
- **Dialogs/sheets.** Don't hand-roll a backdrop+window to mimic
  `.email-reader-backdrop` or `.contact-details-backdrop`. Use `AlertDialog` /
  `BottomSheetDialog`, themed with the active palette — they already get back-button
  handling, gesture dismiss, and TalkBack support for free.
- **Press feedback.** Keep Material ripple on touch instead of web's
  `translateY(1px)` + `brightness(1.06)` hover (Android has no hover state to begin
  with).
- **Chrome theming.** The existing edge-to-edge status/nav-bar repaint in
  `applyThemeToActivity` is already a good native-parity win — don't regress it while
  making the changes above.
- **Font scale.** Always `sp`, always respects system accessibility text size, even when
  visually targeting a web `rem` value.
