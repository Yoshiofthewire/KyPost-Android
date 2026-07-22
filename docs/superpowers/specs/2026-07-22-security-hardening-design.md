# Security hardening: app lock, Hostile Location Protection, and related mitigations

Date: 2026-07-22
Status: Approved

## Goal

Add two user-facing security settings plus a set of supporting hardening
measures:

1. **Require Unlock to Open** — an app PIN (with optional biometric
   convenience unlock) gating the app's UI.
2. **Hostile Location Protection** — a mode with no on-device cache of mail,
   contacts, groups, or attachments, for use in high-risk situations (border
   crossings, device-seizure risk).
3. **Require unlock to receive push/MFA** — an opt-in, off-by-default
   extension of #1 that also gates the server credential itself, at the cost
   of background push/MFA while locked.

Plus: FLAG_SECURE on sensitive screens, disabling backup/data extraction, and
TOFU certificate pinning for the relay connection.

This app has no on-device PGP private key: decryption happens server-side on
kypost-server, and the relay API (`/api/inbox`) returns mail bodies already
decrypted. The Android client only ever handles PGP **public** keys (for
contacts) and an identity-status check (`pgp/PgpIdentityStatus.kt`). This
removes an entire category of "protect the key" concerns other clients might
have and simplifies Hostile Location Protection to "don't cache
already-decrypted content," not "don't lose key material."

## Current architecture (relevant pieces)

- **Room DB** (`data/AppDatabase.kt`, built in `data/DataRuntime.kt`'s
  `DataGraph` as a single disk-backed instance, `kypost_mail.db`) is the one
  local cache for mail (`EmailDao`/`EmailEntity`), contacts (`ContactDao`),
  groups (`GroupDao`/`GroupLinkDao`), folders (`FolderDao`), and pending
  outbound contact edits (`PendingContactChangeDao`). All repositories read
  and write through it; there is no in-memory-only path today.
- **`SecurePairingStore`** (`push/SecurePairingStore.kt`) holds pairing
  material (`serverUrl`, `deviceId`, `deviceSecret`, `pairingToken`) in
  `EncryptedSharedPreferences` (Keystore-backed AES-256-GCM MasterKey). This
  is already encrypted at rest against casual/non-root access, but the key is
  usable by the app at any time — nothing gates it behind user presence.
- **`KyPostApp`** (top-level `Application`, already a
  `ProcessLifecycleOwner` observer) triggers push/pull sync and contact sync
  every time the app returns to the foreground (`onStart`). Background
  delivery (`push/KyPostFirebaseMessagingService.kt`,
  `push/KyPostUnifiedPushService.kt`, `push/PullWorker.kt` via WorkManager,
  and MFA-approval notifications) must be able to authenticate to the relay
  **without the user having opened the app**, which is the source of the
  credential/PIN tradeoff below.
- **Attachments** (`EmailDetailActivity.kt:204-236`) bypass Room entirely
  today: every download writes the decrypted bytes directly into the public
  `MediaStore.Downloads` collection via `saveToDownloads`, regardless of any
  app setting. This is a pre-existing gap Hostile Location Protection needs
  to close, not something introduced by this feature.
- Settings screens follow one convention (`KeywordSettingsActivity.kt`,
  `ThemesActivity.kt`): a plain `ScrollView`/`LinearLayout`, `setTitle`,
  `applyTopInsetWithHeader`, `applyThemeToActivity`, wired into
  `InboxActivity`'s options menu (`onCreateOptionsMenu`/
  `onOptionsItemSelected`).
- No biometric library (`androidx.biometric`) is present yet;
  `androidx.security.crypto` already is (used by `SecurePairingStore`).

## New package: `com.urlxl.mail.security`

Mirrors the existing per-feature package convention (`push`, `pgp`,
`contacts`).

### `AppLockStore`

`EncryptedSharedPreferences`-backed, same pattern as `SecurePairingStore`:

- `lockEnabled: Boolean`
- PIN: stored as `(salt, PBKDF2WithHmacSHA256(pin, salt, 150_000 iterations, 256-bit))`, never the raw PIN.
- `biometricEnabled: Boolean` (only meaningful when `lockEnabled`)
- `failedAttemptCount: Int`
- `lockoutUntilEpochMs: Long`
- `credentialPinGateEnabled: Boolean` (setting #3 — see below)

### `AppLockManager`

Holds in-memory lock state (`MutableStateFlow<Boolean>` — not persisted;
"locked" always means "since this process started, has the correct PIN/
biometric been presented").

Hooked into `KyPostApp`'s existing `ProcessLifecycleOwner` observer:

- `onCreate` (process init): if `AppLockStore.lockEnabled`, start locked.
- `onStop` (backgrounded): if `lockEnabled`, set locked = true immediately
  (no grace period, per the "Immediately" decision).
- `onStart` (foregrounded): if locked, launch `UnlockActivity` on top
  (`FLAG_ACTIVITY_NEW_TASK`), before the existing pull/contact-sync calls.

### `UnlockActivity`

Full-screen PIN entry (numeric keypad UI, following this app's existing
plain-View style, not a new design system). On create, if
`biometricEnabled` and biometrics are currently available
(`BiometricManager.canAuthenticate`), immediately show `BiometricPrompt`;
PIN entry is always visible as a fallback regardless. Successful PIN or
biometric match: `AppLockManager` clears the locked flag, resets
`failedAttemptCount`/`lockoutUntilEpochMs` to zero, `finish()`.

**Lockout policy** (failed PIN attempts only — a failed biometric attempt
doesn't count against this, since biometric false-rejects are normal):

- Attempts 1–2: plain error message, retry immediately.
- Attempt 3 onward: escalating delay before the PIN field re-enables —
  30s, 1min, 5min, 15min, 30min (caps at 30min for attempt 7+). The
  countdown is computed from `lockoutUntilEpochMs`, which is persisted (not
  just in-memory), so killing and restarting the app process doesn't reset
  the clock.
- Attempt 10 in a row without an intervening success (persisted in
  `failedAttemptCount`, so killing/restarting the app doesn't reset the
  count — only a correct PIN or biometric match does):
  `SecurityWipe.wipeAndResetApp()` runs, then the app returns to its
  first-run/pairing state.

### `SecuritySettingsActivity`

New settings screen, same `ScrollView`/`LinearLayout` convention as
`KeywordSettingsActivity`. Wired into `InboxActivity`'s options menu as a
new "Security" entry (`MENU_SECURITY`).

Three toggles, top to bottom:

1. **Require Unlock to Open** (`AppLockStore.lockEnabled`). Turning it on
   first requires setting a 6-digit PIN (enter + confirm, no biometric
   enrollment required at this step). A "Use biometric unlock" sub-switch
   appears once a PIN is set, greyed out if the device has no enrolled
   biometric. A "Change PIN" action appears once enabled. Turning it off
   requires re-authenticating (PIN or biometric) first, then clears
   `AppLockStore`'s PIN/lock state entirely.
2. **Hostile Location Protection** (`HostileLocationSettings.enabled`,
   plain — non-secret — `SharedPreferences`, not encrypted-at-rest storage,
   since the flag itself isn't sensitive). **Disabled/greyed out unless
   toggle 1 is currently on** — attempting to enable it while toggle 1 is
   off shows an inline explanation ("Requires Unlock to Open") instead of a
   silent no-op.
3. **Require unlock to receive push/MFA**
   (`AppLockStore.credentialPinGateEnabled`). Off by default. Disabled/
   greyed out unless toggle 1 is on (same dependency). Before it can be
   turned on, a modal explains the tradeoff in plain language: *"While this
   is on, new-mail notifications and MFA approval requests will only be
   delivered after you open the app and unlock it. If your device is
   compromised while unlocked, this does not add protection — it only
   protects the credential while the app itself is locked."*

## Feature 2 in detail: Hostile Location Protection

### Data layer: swap Room to in-memory

`DataGraph` (`data/DataRuntime.kt`) reads `HostileLocationSettings.enabled`
at construction time and picks the builder:

```kotlin
val database: AppDatabase = if (HostileLocationSettings(appContext).isEnabled()) {
    Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java).build()
} else {
    Room.databaseBuilder(appContext, AppDatabase::class.java, "kypost_mail.db")
        .addMigrations(/* unchanged */)
        .build()
}
```

Every existing repository/DAO (`MailRepository`, `ContactSyncRepository`,
`GroupsSyncClient`, etc.) is unchanged — they already only know about
`AppDatabase`/DAOs, never about the disk file directly. Data lives in RAM
for the process's lifetime and disappears on process death; nothing new
needs to be written to keep it out of persistent storage.

### Toggling on

1. Close the current (disk-backed) `AppDatabase` instance.
2. `appContext.deleteDatabase("kypost_mail.db")` — removes the main file
   plus `-wal`/`-shm` journal files. This is the "wipe immediately on
   enable" behavior: nothing from before the toggle survives.
3. Persist `HostileLocationSettings.enabled = true`.
4. Relaunch the app process (see "Automatic relaunch," below).

### Toggling off

1. Close the current in-memory instance (data is lost — expected; nothing
   to migrate).
2. Persist `enabled = false`.
3. Relaunch. `DataGraph` rebuilds disk-backed against a fresh (empty)
   `kypost_mail.db`; `KyPostApp.onStart`'s existing pull/contact sync calls
   repopulate it exactly as they do on any other fresh install.

### Automatic relaunch

Both the Room swap and any future toggle that needs a fresh `DataGraph`
share one helper (e.g. `AppRestart.relaunch(context)`): finish all
activities, restart `MainActivity` via
`PendingIntent`/`AlarmManager.setExact` scheduled a few hundred ms out, then
`Process.killProcess(Process.myPid())`. This is the standard "restart my own
app" pattern on Android — there is no public API to hot-swap a live Room
instance out from under already-created ViewModels/Activities, so a process
restart is the correct fix, not a workaround.

### Attachments

While Hostile Location Protection is on, `EmailDetailActivity`'s attachment
chip no longer calls `saveToDownloads`. Instead:

- `mailRepository.downloadAttachment(...)` still fetches bytes as today.
- Bytes are handed to a new `EphemeralAttachmentProvider`
  (a `ContentProvider` implementing `openAssetFile()`/`openFile()`) that
  serves them through a pipe (`ParcelFileDescriptor.createReliablePipe()`,
  written from a background thread) rather than ever writing them to a
  file — no cache dir, no Downloads, no disk at all.
- The chip's action launches `ACTION_VIEW` on the resulting
  `content://.../ephemeral/<token>` URI with
  `FLAG_GRANT_READ_URI_PERMISSION`, letting the OS pick a viewer app for the
  MIME type, same as "Open" flows elsewhere in Android.
- The `<token>` maps to an in-memory `ByteArray` held only long enough to
  service the read (removed once the pipe's write side closes or after a
  short timeout, whichever first) — never persisted, never reused.
- The button label changes to reflect this (e.g. "View" instead of
  "Download") whenever Hostile Location Protection is on, so the changed
  behavior is visible, not silent.

This closes the pre-existing gap where every attachment currently lands in
public storage regardless of any setting — but only while the toggle is on;
outside Hostile Location Protection, attachment downloads keep today's
"save to Downloads" behavior unchanged (out of scope to change default
behavior for users not opting into this mode).

## Feature 3 in detail: Require unlock to receive push/MFA

Off by default; a materially different tradeoff from toggle 1, not just an
extension of it, so it needs its own explicit opt-in with the warning
described above.

### Why this is a real tradeoff, not a pure win

`SecurePairingStore`'s Keystore-backed encryption already protects
`deviceSecret` against a casual attacker (no root, no debugging enabled)
picking up the device. But background push/pull/MFA-approval need to
authenticate *unattended*, which means the app must decrypt `deviceSecret`
into plain memory on a background thread/service without any user present.
A **rooted device**, ADB-root, or forensic extraction tooling can read that
live process's memory directly, or invoke Keystore on the app's behalf,
regardless of how strong the at-rest encryption is — this is inherent to
any "works unattended" design, not a gap specific to this implementation.
PIN-gating the credential closes that specific window (nothing to extract
from a locked app's background state, because nothing is decrypted while
locked) at the direct cost of background push/MFA not functioning during
that window. There is no version of this that keeps both properties; the
setting exists so the user can pick which one they want, explicitly and
per-device.

### Implementation

- When `credentialPinGateEnabled` is on, `SecurePairingStore` wraps
  `deviceSecret` with an additional AES-GCM layer, keyed by
  `PBKDF2WithHmacSHA256(pin, salt)` (the same PIN as toggle 1 — this
  setting cannot be on without toggle 1 also being on, enforced by the UI
  dependency above). The salt is non-secret and stored alongside the
  (now double-wrapped) ciphertext.
- `AppLockManager`, on a successful unlock, derives this key once and holds
  it in memory only for as long as the app is unlocked (same lifetime as
  the "locked" `StateFlow` itself) — background push/pull/MFA during an
  *unlocked* session work exactly as today, using this cached key.
- The instant `AppLockManager` transitions to locked (backgrounding, per
  the "Immediately" policy), the derived key is dropped from memory.
  Any background push/pull/MFA attempt made while locked finds the
  credential undecryptable and no-ops gracefully (queues nothing, retries
  on next successful foreground unlock — `KyPostApp.onStart`'s existing
  sync calls already run on every foreground transition, so no separate
  "catch up" mechanism is needed).
- When `credentialPinGateEnabled` is off (default), `SecurePairingStore`
  behaves exactly as it does today — Keystore-only, always available,
  independent of `AppLockManager`'s state.

## Additional hardening included this round

### FLAG_SECURE on sensitive screens

Add `window.setFlags(FLAG_SECURE, FLAG_SECURE)` to `onCreate` of:
`InboxActivity`, `EmailDetailActivity`, `ComposeActivity`, `PgpKeyActivity`,
`SecuritySettingsActivity`, `UnlockActivity`. Blocks screenshots/screen
recording of these screens and blanks their thumbnail in the
Recents/app-switcher. Screens without sensitive content (e.g.
`ThemesActivity`) are intentionally left unchanged.

### Disable backup/data extraction

In `AndroidManifest.xml`, on the `<application>` element:

```xml
android:allowBackup="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```

with a `data_extraction_rules.xml` that excludes all app data from both
device-transfer and cloud/ADB backup. Prevents `adb backup` and Android's
Auto Backup to Google Drive from ever seeing the encrypted prefs or Room
DB, regardless of Hostile Location Protection's own state.

### Certificate pinning (TOFU, not a hardcoded pin)

kypost is self-hosted with a per-user `serverUrl` captured at pairing time
— there is no single fixed certificate to hardcode into the app, so
standard static `CertificatePinner` pinning doesn't fit. Instead:

- At the moment pairing succeeds, capture the leaf certificate's SPKI hash
  (SHA-256) from the TLS handshake and store it in `SecurePairingStore`
  alongside the rest of the pairing data.
- All subsequent relay requests (`RelayMailSource`, `PushRepository`, etc.,
  all of which already share `pairingHttpClient()`) validate the presented
  certificate's SPKI hash against the stored one via a custom
  `CertificatePinner`/`TrustManager`, hard-failing the request on mismatch.
- Recovery path: a certificate mismatch surfaces as a distinct error state
  (not folded into generic "network error") offering "Clear pairing and
  re-pair" — needed for legitimate cert rotation on the user's own server,
  which otherwise would permanently lock them out.
- This protects against MITM **after** the initial pairing (an attacker
  who can intercept only post-pairing traffic gains nothing), which is the
  realistic threat model for a self-hosted relay; it does not protect the
  initial pairing handshake itself, which already has to trust whatever
  `serverUrl` the user typed in.

## Explicitly out of scope this round

- Duress/panic PIN (a second PIN that silently wipes instead of unlocking).
- Root/tamper detection.
- Clipboard-sensitive flagging for copied fingerprints/pairing codes.
- Secure/overwrite deletion of the old disk-backed DB file (plain
  `deleteDatabase` is used — recoverable via forensic disk recovery in
  principle; flagged here, not fixed).
- Any change to non-Hostile-Location-Protection attachment behavior
  (default "save to Downloads" stays as-is).
- Server-side work (kypost-server) implied by the credential-tradeoff
  discussion, e.g. short-lived/rotating/scoped push tokens that would
  reduce the value of an extracted `deviceSecret` without requiring any
  PIN gate at all. Noted here as the more fundamental fix, but it's a
  separate repo/spec.

## Porting notes: kypost-Linux and iOS

The user asked for this design to carry over to the other kypost clients.
This Android app has no shared code with them (separate repos/languages),
so nothing here is auto-applied — these are pointers for a person or a
future session driving the equivalent work in each repo, not a promise that
file names/APIs match exactly.

### kypost-Linux (Qt/QML, C++)

Confirmed equivalents by inspection:

- **Data layer**: `core/db/Database.cpp` + per-entity DAOs
  (`EmailDao`, `ContactDao`, `GroupDao`, `FolderDao`,
  `PendingContactChangeDao`) — the same "one DB, several DAOs" shape as
  Android's Room setup. Qt's SQLite driver supports an in-memory database
  via the special filename `:memory:` on `QSqlDatabase::addDatabase(...)`,
  which is the direct analogue of `Room.inMemoryDatabaseBuilder` — the
  Hostile Location Protection seam belongs in whatever sets up
  `Database`'s connection (mirrors `DataGraph`).
- **Credential storage**: `core/stores/SecureStore.h` is an interface with
  two implementations — `app/platform/SecureStoreKeychain.cpp` (OS
  keychain/Secret Service — the credential-PIN-gate layer would wrap here)
  and `core/stores/SecureStoreFile.cpp` (presumably a fallback for
  environments without a keychain service, e.g. headless/some Linux
  desktops — worth checking whether that fallback is already
  weaker-than-Android's-Keystore before assuming parity here).
- **Background delivery**: `core/net/NtfySubscriber.h`/
  `PushNotificationClient.h` and `app/pairing/MfaController.cpp` are the
  Linux equivalents of Android's `KyPostFirebaseMessagingService`/
  `MfaResponseReceiver` — same "must authenticate unattended" tension
  applies to feature 3's tradeoff.
- **App lock UI**: no existing equivalent found — this would be new QML
  (`app/qml/pages`), likely gated at the top-level window/tray
  (`app/tray/`) rather than per-page, since this is a single-window desktop
  app rather than a multi-Activity mobile one. A "lock on screen
  lock/suspend" hook (rather than Android's background/foreground
  lifecycle) is the natural equivalent trigger for a desktop app.
- **FLAG_SECURE** has no direct desktop equivalent; the closest available
  hardening is disabling window content in the taskbar/overview thumbnail
  where the desktop environment supports it, which is much more
  DE-dependent than Android's single API — treat as best-effort, not
  guaranteed.

### iOS (no local repo available to inspect — not verified against source)

Not confirmed against any code, since no iOS/Mac source was available in
this environment — these are platform-idiom pointers only, to be verified
against the actual repo before implementing:

- **Require Unlock to Open**: `LocalAuthentication` framework
  (`LAContext.evaluatePolicy` with `.deviceOwnerAuthentication` for PIN
  fallback + Face ID/Touch ID) is the idiomatic replacement for a custom
  PIN screen — iOS conventionally defers to the device passcode rather
  than apps rolling their own PIN, which is a meaningfully different
  (and arguably stronger, since it inherits the OS's own rate-limiting/
  wipe policy) default than this Android design's custom PIN. Worth an
  explicit decision on iOS whether to match Android's custom-PIN model for
  consistency or lean into the platform default.
- **Hostile Location Protection**: whatever local persistence iOS uses
  (Core Data/SQLite/a custom store) would need an equivalent in-memory-only
  mode; `NSPersistentContainer` supports an in-memory store type
  (`NSInMemoryStoreType`) analogous to Room's in-memory builder, if Core
  Data is in use.
- **Keychain** is already the iOS equivalent of `EncryptedSharedPreferences`
  for credential storage; `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (or
  stricter) is the relevant access-control flag for the credential-PIN-gate
  tradeoff, and `kSecAccessControlBiometryCurrentSet` for tying a Keychain
  item to biometric presence specifically.
- **Certificate pinning**: `URLSession`'s
  `urlSession(_:didReceive:completionHandler:)` challenge handling is where
  TOFU pin capture/verification would live, analogous to the custom
  `TrustManager`/`CertificatePinner` described above for Android.
