# Contact editor — full field coverage — design

## Problem

The backend `Contact` model (`kypost-server/backend/internal/contacts/contacts.go`)
carries ~26 fields, matching Android/iOS native contact apps. The Android
wire model and local storage already carry every one of them — `ContactDto`,
`ContactEntity`, `ContactMappers.kt`, and the Room schema (`AppDatabase`
version 6) are complete and lossless.

But `ContactEditActivity` — the *only* screen that shows a contact's detail
in this app (`ContactsListActivity` routes taps straight into it, no
separate read-only detail view) — only exposes 5 fields: `fn`, `org`, the
*first* email, the *first* phone, and `notes`. Every other field (structured
name parts, title, birthday, addresses, IMs, websites, relations, events,
phonetic names, department, custom fields, pronouns, additional
emails/phones beyond the first) is invisible: stored correctly, synced
correctly, but never shown or editable anywhere in the app.

(A related bug — editing and saving a contact through this screen used to
*wipe* every field it doesn't show, both locally and on the server — was
already fixed separately, in `ContactEditActivity.mergedContactDto()`. This
spec is about *displaying and editing* those fields, building on top of that
fix rather than re-solving it.)

## Scope

**In scope** — full editing UI for every field except the two below:

- Name: `givenName`, `familyName`, `middleName`, `prefix`, `suffix`,
  `nickname`, `phoneticGivenName`, `phoneticFamilyName`, `pronouns`
  (alongside existing `fn`)
- Work: `title`, `department` (alongside existing `org`)
- Contact: full `emails`/`phones` lists (not just the first entry)
- `addresses` (list)
- `websites`, `ims` (lists)
- `birthday`, `events` (list) — via date picker
- `relations` (list)
- `customFields` (list)

**Explicitly out of scope, deferred to later follow-ups:**

- `photoRef` — needs a camera/gallery picker, image upload, and actual
  photo rendering (today the app only ever shows generated initials,
  never a real photo, anywhere). Materially bigger and different in kind
  from the text/list fields above; tracked as a separate future project.
- `groupIDs` — needs a multi-select group picker. There is no
  group-management screen anywhere in the app yet (group *sync* exists via
  `GroupSyncRepository`, but no UI). Existing group memberships stay intact
  regardless (protected by `mergedContactDto`'s copy-forward), just not
  editable from this screen yet.
- `isSelf` — stays a **read-only badge**, not editable here, per
  `docs/superpowers/specs/2026-07-18-contact-self-flag-design.md`'s explicit
  decision: marking a contact as self is a web-only action; mobile only
  syncs and displays it.
- `pgpKey` — stays a **read-only badge**, not editable here. It's set
  exclusively through the PGP QR key-exchange flow (`PgpKeyActivity`), never
  by manual text entry; adding a manual-paste path here would be an
  inconsistent, security-relevant second way to set it.

## Section layout

The screen becomes a list of collapsible sections instead of one flat form.
A section starts expanded if it already has data, collapsed otherwise — the
common case (a contact with just a name/email/phone) still looks like
today's short form.

| Section | Fields | Starts expanded when... |
|---|---|---|
| Name *(always expanded)* | `fn` (required), `givenName`, `familyName`, `middleName`, `prefix`, `suffix`, `nickname`, `phoneticGivenName`, `phoneticFamilyName`, `pronouns` | always |
| Work | `org`, `title`, `department` | any is non-blank |
| Contact | `emails` (list), `phones` (list) | either list non-empty |
| Addresses | `addresses` (list) | non-empty |
| Online | `websites` (list), `ims` (list) | either non-empty |
| Personal | `birthday`, `events` (list), `relations` (list) | any set |
| Notes | `notes` | non-blank |
| Other | `customFields` (list) | non-empty |

`isSelf` and `pgpKey` render as small read-only badges near the top of the
screen (same visual treatment `ContactAdapter` already uses in the list row)
— no edit controls.

## Components

Two new reusable pieces, each its own file, neither aware of contact-field
semantics:

### `ExpandableSectionView`

Compound view (`contacts/ExpandableSectionView.kt` +
`res/layout/view_expandable_section.xml`): a header row (title + item-count
badge + chevron) that toggles a body container's visibility on tap.

```kotlin
class ExpandableSectionView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    val body: ViewGroup // caller populates this with the section's fields
    var isExpanded: Boolean
    fun setTitle(title: String)
    fun setItemCount(count: Int) // updates the header badge; 0 hides it
}
```

### `RepeatableFieldList<T>`

(`contacts/RepeatableFieldList.kt`) — manages the "rows + Add button"
pattern for one list-typed field inside a section body.

```kotlin
class RepeatableFieldList<T>(
    private val container: ViewGroup,
    private val addButton: View,
    private val rowLayoutRes: Int,
    private val bind: (rowView: View, item: T, onChanged: () -> Unit) -> Unit,
    private val isBlank: (T) -> Boolean,
    private val default: () -> T,
) {
    fun setItems(items: List<T>)
    fun items(): List<T> // drops rows for which isBlank(current value) is true
}
```

`bind` wires each row's `EditText`s with `TextWatcher`s that mutate that
row's backing `T` and call `onChanged` (used to keep the section's item-count
badge live). Each row also gets a `×` remove button, wired inside
`RepeatableFieldList` itself so callers never repeat that logic.

Instantiated once per list field (7 total: `emails`, `phones`, `addresses`,
`websites`, `ims`, `relations`, `events`, `customFields`), each with its own
row layout (e.g. `row_contact_address.xml` with street/city/region/postal/
country `EditText`s) since the sub-field shapes differ, but identical
add/remove/collect mechanics.

`ContactEditActivity` becomes an orchestrator: builds each
`ExpandableSectionView` + its `RepeatableFieldList` (or plain `EditText` for
scalar fields) in `onCreate`, populates them in `loadExisting`, and reads
them back out in `save`.

## Data flow

`loadExisting()` already loads a `ContactDto` into `loadedDto` (from the
data-loss fix). It now also pushes each field into its section: scalar
fields via `setText`, list fields via `RepeatableFieldList.setItems(dto.X)`,
and expands any section whose corresponding field(s) are non-empty (per the
table above).

`save()` extends `mergedContactDto()` to take every new field, still
`.copy()`-ing off `loadedDto` so `photoRef`/`groupIDs`/`isSelf`/`pgpKey`
(none of which this screen edits) stay untouched. Each list field's value
comes from its `RepeatableFieldList.items()`, which already drops
fully-blank rows (e.g. a "+Add" tapped but left empty) before `save` ever
sees them — no empty-string placeholder entries get written.

Birthday and event dates: tapping the field opens `DatePickerDialog`,
formats the result as `yyyy-MM-dd` (matches the backend's string format),
and displays it in a non-keyboard-focusable `EditText` styled like the
others (consistent look, but only the picker opens on tap — no soft
keyboard).

## Error handling / validation

Same posture as today: only `fn` is required (per `Mobile_Contact_Sync.md`'s
field table), everything else optional. No new required-field validation is
added for the newly-exposed fields.

- **List rows**: a row counts as "blank" (dropped on save) only if *every*
  one of its sub-fields is blank — e.g. an address row with only `country`
  filled in is kept as-is, not dropped.
- **Dates**: no manual text entry, so no malformed-date case to guard
  against; canceling the picker leaves the field unchanged.
- No new network/schema validation — this screen only ever writes to local
  Room via the existing queued-sync path, which already handles server-side
  rejection through `ContactSyncOutcome`.

## Testing

- **`mergedContactDto`**: extend `ContactEditActivityTest.kt`'s existing
  fully-populated-`loaded`-DTO test to cover the newly-editable fields
  (assert they now reflect edits, not just survive untouched) alongside the
  still-untouched deferred fields (`photoRef`, `groupIDs`, `isSelf`,
  `pgpKey`), which must keep surviving exactly as before.
- **`RepeatableFieldList`**: unit-testable in plain JVM if its
  add/remove/collect/blank-dropping logic can be kept independent of
  Android `View` construction (e.g. operate on a list of `T` plus row
  *indices*, with `bind`/view-inflation as a separate, thin layer); if that
  separation isn't clean, an instrumented test in `androidTest` instead
  (inflate the view, simulate add/remove, assert `items()`), following the
  same pattern as `ContactDaoOrderingTest`'s `Room.inMemoryDatabaseBuilder`
  instrumented style.
- **`ExpandableSectionView`**: instrumented test for toggle behavior and the
  starts-expanded-when-populated rule.
- **Manual verification** on-device: open a contact with data in every
  field (including one exercising every list field with 2+ entries),
  confirm each section round-trips correctly through edit → save → reload,
  and confirm `isSelf`/`pgpKey` badges still render read-only.
