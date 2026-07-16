# Panel Radius Token Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the six duplicated `14dp` / `14f * density` card-and-panel corner-radius literals scattered across Android XML layouts and Kotlin with a single shared token, closing STYLE_GUIDE.md §3's "no distinct radius token yet" gap for the Card/panel row.

**Architecture:** Add one dimension resource (`@dimen/card_corner_radius = 14dp`) as the single source of truth, referenced directly from XML `app:cardCornerRadius` attributes and read at runtime in Kotlin via `resources.getDimension(R.dimen.card_corner_radius)` (which already applies density scaling, so no more manual `* density` math). Add a new `AppTheme.applyPanelBackground(context, view)` helper — mirroring the existing `fieldBackground()`/`buttonBackground()` pattern in `AppTheme.kt` — for the one call site that paints an ad hoc *rounded, theme-`panel`-colored* background (`InboxActivity`'s keyword-pill bar) rather than just setting a static XML attribute.

**Tech Stack:** Kotlin, Android Views/XML, `androidx.cardview.widget.CardView`, `GradientDrawable`.

## Global Constraints

- Value must stay numerically `14` (dp) — this is STYLE_GUIDE.md §3's shared Card/panel radius, matching iOS's `Shape.panel = 14` and Linux's `shapePanel = 14`. Do not change the visual output, only where the value lives.
- This codebase has no Robolectric/unit-test coverage for any existing `AppTheme.kt` drawable-builder function (`fieldBackground`, `buttonBackground`, `ghostButtonBackground`, `dangerButtonBackground` are all untested — confirmed via grep, `app/src/test` only unit-tests pure non-Android logic like `buildMonoFontFaceCss` in `FontFaceCssTest.kt`). Follow that precedent: no new unit tests are added for the drawable-builder code in this plan; verification is compile + visual smoke check instead.
- Don't touch `fieldBackground()`'s or `buttonBackground()`'s own `cornerRadius` literals (14dp and 10dp respectively) — those are separate STYLE_GUIDE.md §3 tokens (Text field, Button) that already have their own functions and are out of scope here.

---

### Task 1: Add the `card_corner_radius` dimension resource

**Files:**
- Create: `app/src/main/res/values/dimens.xml`

- [ ] **Step 1: Create the dimens resource file**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- STYLE_GUIDE.md §3 Card/panel radius — shared with iOS Shape.panel / Linux shapePanel (both 14). -->
    <dimen name="card_corner_radius">14dp</dimen>
</resources>
```

- [ ] **Step 2: Verify the resource compiles**

Run: `./gradlew :app:compileDebugKotlin` (this task doesn't touch Kotlin yet, but a resource-only build confirms no XML syntax error)
Expected: `BUILD SUCCESSFUL`. If you prefer a resource-only check, `./gradlew :app:processDebugResources` also works and is faster.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/dimens.xml
git commit -m "feat: add card_corner_radius dimension resource"
```

---

### Task 2: Reference the dimension from all five card layouts

**Files:**
- Modify: `app/src/main/res/layout/item_contact.xml:7`
- Modify: `app/src/main/res/layout/item_email.xml:7`
- Modify: `app/src/main/res/layout/item_recipient_row.xml:7`
- Modify: `app/src/main/res/layout/item_push_history.xml:7`
- Modify: `app/src/main/res/layout/activity_push_pairing.xml:13,124`

**Interfaces:**
- Consumes: `@dimen/card_corner_radius` from Task 1.

- [ ] **Step 1: Replace the literal in `item_contact.xml`**

Change line 7 from:
```xml
    app:cardCornerRadius="14dp"
```
to:
```xml
    app:cardCornerRadius="@dimen/card_corner_radius"
```

- [ ] **Step 2: Replace the literal in `item_email.xml`**

Same change as Step 1, applied to `item_email.xml:7`.

- [ ] **Step 3: Replace the literal in `item_recipient_row.xml`**

Same change as Step 1, applied to `item_recipient_row.xml:7`.

- [ ] **Step 4: Replace the literal in `item_push_history.xml`**

Same change as Step 1, applied to `item_push_history.xml:7`.

- [ ] **Step 5: Replace both literals in `activity_push_pairing.xml`**

`activity_push_pairing.xml` has two `CardView`s (lines 9–116 and 118–158). Replace `app:cardCornerRadius="14dp"` with `app:cardCornerRadius="@dimen/card_corner_radius"` at **both** line 13 and line 124.

- [ ] **Step 6: Build and confirm no layout errors**

Run: `./gradlew :app:compileDebugKotlin :app:processDebugResources`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/item_contact.xml app/src/main/res/layout/item_email.xml app/src/main/res/layout/item_recipient_row.xml app/src/main/res/layout/item_push_history.xml app/src/main/res/layout/activity_push_pairing.xml
git commit -m "refactor: reference card_corner_radius dimen instead of hardcoded 14dp"
```

---

### Task 3: Add `AppTheme.applyPanelBackground` and use it in `InboxActivity`

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/AppTheme.kt:526-535` (near existing `fieldBackground`/`density`)
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt:204-209`
- Modify: `app/src/main/java/com/urlxl/mail/InboxActivity.kt:551`

**Interfaces:**
- Produces: `fun applyPanelBackground(context: Context, view: View)` — public, in `AppTheme.kt`, paints `view.background` as a `GradientDrawable` with `cornerRadius = card_corner_radius` and fill color = the active theme's `panel`. Callers: `InboxActivity`.
- Consumes: `@dimen/card_corner_radius` (Task 1), `getStoredThemePalette(context)` (existing, `AppTheme.kt:93`), `ThemePalette.panel` (existing field).

- [ ] **Step 1: Add `panelBackground` and `applyPanelBackground` to `AppTheme.kt`**

Insert directly above the existing `fieldBackground` function (`AppTheme.kt:528`), reusing the file's existing private `density` getter (`AppTheme.kt:526`) is no longer needed for this new function — read the dimension resource instead so XML and Kotlin share one literal:

```kotlin
private fun panelBackground(context: Context, palette: ThemePalette): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.card_corner_radius)
        setColor(Color.parseColor(palette.panel))
    }
}

/**
 * Paints [view]'s background as a rounded, theme-`panel`-colored panel using the shared
 * STYLE_GUIDE.md §3 Card/panel radius (`@dimen/card_corner_radius`). For containers that need a
 * *rounded* panel fill rather than the flat fill `applyThemeToViewTree` gives generic ViewGroups.
 */
fun applyPanelBackground(context: Context, view: View) {
    view.background = panelBackground(context, getStoredThemePalette(context))
}
```

- [ ] **Step 2: Replace the ad hoc `GradientDrawable` in `InboxActivity.applyTheme` (or equivalent)**

Change `InboxActivity.kt:204-209` from:
```kotlin
        // Rounded panel bar behind the keyword pills, matching the app's 14dp card/panel radius.
        keywordChipScroll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * resources.displayMetrics.density
            setColor(panel)
        }
```
to:
```kotlin
        // Rounded panel bar behind the keyword pills — shared STYLE_GUIDE.md §3 Card/panel radius.
        applyPanelBackground(this, keywordChipScroll)
```

- [ ] **Step 3: Replace the manual density math for the swipe reveal backgrounds**

Change `InboxActivity.kt:551` from:
```kotlin
        val cardRadius = 14f * resources.displayMetrics.density
```
to:
```kotlin
        val cardRadius = resources.getDimension(R.dimen.card_corner_radius)
```

(No other change needed at this call site — `deleteBackground`/`archiveBackground` below it already consume `cardRadius` as a `Float` in px, which `getDimension` also returns.)

- [ ] **Step 4: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. If `GradientDrawable` import in `InboxActivity.kt` becomes unused after Step 2, remove it (check: `deleteBackground`/`archiveBackground` at lines 552/556 still construct `GradientDrawable` directly, so the import stays — no removal needed).

- [ ] **Step 5: Manual smoke check**

Run the app (`./gradlew :app:installDebug` to a connected device/emulator, or use the project's `run` skill) and visually confirm, in at least one non-default theme:
- Inbox screen: the keyword-pill bar background is still rounded with the same radius as before (no visible change).
- Inbox screen: swipe a row left/right — the red delete / yellow archive reveal still has correctly rounded outer corners matching the card underneath.
- Contacts list, email list, recipient picker rows, push-pairing history rows: cards still render with the same rounded corners as before.

Expected: pixel-identical appearance to before this plan — this is a refactor, not a visual change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/AppTheme.kt app/src/main/java/com/urlxl/mail/InboxActivity.kt
git commit -m "refactor: introduce AppTheme.applyPanelBackground, drop manual 14dp density math"
```

---

### Task 4: Update STYLE_GUIDE.md §3 to record the closed gap

**Files:**
- Modify: `STYLE_GUIDE.md` (§3 shape table, Card/panel row)

**Interfaces:**
- Consumes: nothing code-level: this is documentation only, describing Tasks 1-3's result.

- [ ] **Step 1: Update the Card/panel row**

Change:
```
| Card / panel | 14 | (panel containers use flat fill, no distinct radius token yet) | `Shape.panel` 14 | `shapePanel` 14 |
```
to:
```
| Card / panel | 14 | `@dimen/card_corner_radius` / `applyPanelBackground` (`AppTheme.kt`) 14dp ✓ | `Shape.panel` 14 ✓ | `shapePanel` 14 ✓ |
```

- [ ] **Step 2: Commit**

```bash
git add STYLE_GUIDE.md
git commit -m "docs: record card_corner_radius token in STYLE_GUIDE.md §3"
```
