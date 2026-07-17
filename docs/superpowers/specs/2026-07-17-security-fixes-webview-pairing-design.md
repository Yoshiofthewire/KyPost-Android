# Security fixes: WebView XSS and deep-link pairing hijack

Date: 2026-07-17
Status: Approved

## Goal

A security audit (adapting a web-app checklist to this Android client) found
two Critical issues, both already confirmed by direct code inspection. Fix
both, minimally.

## Fix 1: disable JavaScript in the email-reading WebView

`EmailDetailActivity.kt` renders arbitrary senders' HTML email bodies with
JavaScript execution enabled and no HTML sanitization
(`webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null)`
at `EmailDetailActivity.kt:163`, where `htmlContent` embeds the sender's raw
`content?.html`). Any sender can run arbitrary JS in the WebView the moment
their email is opened.

No `addJavascriptInterface` bridge exists anywhere in the codebase (confirmed
by audit), so this doesn't reach native app internals, but same-origin script
execution and unrestricted network requests (tracking beacons) are live.

Fix: disable JavaScript outright — legitimate email bodies don't need it, and
this matches how mainstream mail clients (Gmail, Outlook) handle HTML bodies.

```kotlin
// EmailDetailActivity.kt:120-127
webView.settings.apply {
    javaScriptEnabled = false   // was: true
    builtInZoomControls = true
    displayZoomControls = false
    useWideViewPort = true
    loadWithOverviewMode = true
    defaultTextEncodingName = "utf-8"
}
```

Out of scope: no HTML sanitizer library added, no change to image-loading
behavior (tracking-pixel blocking) — neither was part of the audited Critical
finding, and disabling JS alone closes the reported vector. The compose
editor (`RichHtmlEditorWebView`, a separate third-party component, not this
app's source) is untouched — `javaScriptEnabled` is set in exactly one place
in this codebase, `EmailDetailActivity.kt:121`.

## Fix 2: require confirmation before applying a deep-link pairing

`PushPairingActivity` is `exported="true"` with a `BROWSABLE` intent-filter
for the custom scheme `llamalabels://native-pair`
(`AndroidManifest.xml:65-79`). Any other app on the device (or a browser
navigating to that URI) can silently trigger `consumeDeepLink` →
`viewModel.pairFromLink(data)` → `attemptPairing`, overwriting
`SecurePairingStore`'s `serverUrl`/`sub`/`hash`/`pairingToken` with no user
confirmation — redirecting all future sync traffic to an attacker-controlled
relay.

QR-code scanning (`scanQr()`, `PushPairingActivity.kt:198-211`) calls the
same `viewModel.pairFromLink(raw)` — but scanning is itself a deliberate user
action (open camera, point at a code), so it's left unchanged. Only the
deep-link entry point needs a confirmation step, since it can fire with zero
user awareness.

### `PushHomeViewModel.kt`: extract a shared apply-step

Current `pairFromLink` (`PushHomeViewModel.kt:65-101`) does parse → resolve →
`attemptPairing` in one shot. Split the resolve+attempt portion into a
private suspend helper, reused by both the existing QR/link entry point and
a new deep-link-specific entry point:

```kotlin
fun pairFromLink(link: String) {
    scope.launch {
        isWorking.value = true
        val parsed = NativePairingDeepLinkParser.parse(link)
        when (parsed) {
            is PairingParseResult.Error -> {
                localMessage.value = parsed.reason
                isWorking.value = false
            }
            is PairingParseResult.Success -> applyParsedPairing(parsed.pairing)
        }
    }
}

/** Applies a deep-link pairing the user has already confirmed via the
 *  destination-server dialog in PushPairingActivity — unlike [pairFromLink]
 *  (QR scan, itself a deliberate user action), a deep link can fire from any
 *  app with zero user awareness, so it requires that separate confirmation
 *  step before reaching this. */
fun applyDeepLinkPairing(pairing: PairingData) {
    scope.launch {
        isWorking.value = true
        applyParsedPairing(pairing)
    }
}

private suspend fun applyParsedPairing(pairing: PairingData) {
    val resolution = NativeRegistrationEndpointResolver.resolve(
        qrReg = pairing.registrationUrl.takeIf { it.isNotBlank() },
        qrServerUrl = pairing.serverUrl,
    )
    when (resolution) {
        is NativeRegistrationEndpointResolver.Resolution.MissingServerUrl -> {
            localMessage.value = "Pairing QR is missing a server URL"
            isWorking.value = false
        }
        is NativeRegistrationEndpointResolver.Resolution.Resolved -> {
            val pending = pairing.copy(registrationUrl = resolution.registrationUrl)
            val result = graph.syncCoordinator.attemptPairing(pending)
            if (result is NativeRegistrationResult.Success) {
                graph.pullCoordinator.pullNowAsync()
            }
            localMessage.value = when (result) {
                is NativeRegistrationResult.Success -> "Device paired and token synced"
                is NativeRegistrationResult.Error -> {
                    val suffix = if (result.expiredPairingToken) " — rescan the pairing QR code" else ""
                    "Pairing failed: ${result.message}$suffix"
                }
            }
            isWorking.value = false
        }
    }
}
```

`applyParsedPairing`'s body is byte-identical to `pairFromLink`'s current
`Success` branch — pure extraction, no behavior change for the QR path.

### `PushPairingActivity.kt`: confirm before applying

```kotlin
private fun consumeDeepLink(intent: android.content.Intent?) {
    val data = intent?.dataString ?: return
    when (val parsed = NativePairingDeepLinkParser.parse(data)) {
        is PairingParseResult.Error -> Toast.makeText(this, parsed.reason, Toast.LENGTH_SHORT).show()
        is PairingParseResult.Success -> confirmAndApplyDeepLinkPairing(parsed.pairing)
    }
}

private fun confirmAndApplyDeepLinkPairing(pairing: PairingData) {
    AlertDialog.Builder(this)
        .setTitle(R.string.pairing_confirm_title)
        .setMessage(getString(R.string.pairing_confirm_message, pairing.serverUrl))
        .setPositiveButton(R.string.pairing_confirm_positive) { _, _ -> viewModel.applyDeepLinkPairing(pairing) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
```

`consumeDeepLink` is called from both `onCreate` (`PushPairingActivity.kt:75`)
and `onNewIntent` (`PushPairingActivity.kt:111-114`) — both paths get the
confirmation automatically since it's the same function. `PairingData`,
`PairingParseResult`, `NativePairingDeepLinkParser` are all in the same
`com.urlxl.mail.push` package as `PushPairingActivity` — no new imports for
those. `AlertDialog` needs `import androidx.appcompat.app.AlertDialog`,
matching the existing pattern in `ComposeActivity.kt:15,240-248`.

### New strings

`app/src/main/res/values/strings.xml`, after the existing
`push_pairing_use_firebase` line (currently line 92):

```xml
<string name="pairing_confirm_title">Pair this device?</string>
<string name="pairing_confirm_message">This link wants to pair this device with:\n\n%1$s\n\nOnly continue if you trust the source of this link.</string>
<string name="pairing_confirm_positive">Pair</string>
```

Reuses the existing `R.string.cancel` for the negative button.

## Out of scope

- No change to the QR-scan pairing path's behavior (still applies
  immediately — scanning is itself the confirmation).
- No calling-package verification (`getCallingPackage()`) — the confirmation
  dialog is the chosen mitigation, not caller allowlisting (deep links are
  legitimately opened from browsers/other apps by design).
- No HTML sanitizer library, no image/tracking-pixel blocking changes (Fix 1).
- No changes to `AndroidManifest.xml` — `PushPairingActivity` stays
  `exported="true"` (deep links must remain externally reachable to work at
  all); the fix is at the point where the parsed result gets *applied*, not
  at the intent-filter level.

## Verification

No automated test covers either UI flow currently (manual-only, matching
this codebase's established convention for `InboxActivity`'s and
`PushPairingActivity`'s UI wiring). Build and check by hand:

1. Open an email with an HTML body containing `<script>alert(1)</script>` —
   confirm no alert fires (JS disabled). Confirm normal HTML (bold, links,
   images) still renders — only script execution is disabled, not markup
   rendering.
2. Trigger the `llamalabels://native-pair?...` deep link (e.g. via
   `adb shell am start -a android.intent.action.VIEW -d
   "llamalabels://native-pair?sub=x&hash=y&srv=https://example.com&pt=z"`) —
   confirm a dialog appears showing `https://example.com` before anything is
   applied; confirm Cancel leaves the existing pairing untouched; confirm
   "Pair" applies it (existing `attemptPairing` flow, unchanged).
3. Scan a real pairing QR code — confirm it still applies immediately with
   no new dialog (QR path unaffected).
