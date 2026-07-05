# llama mail for Android

This app includes a production push client flow for **Llama Labels notifications via native backend pairing + FCM**. Novu is not used on the client — the backend owns the Novu integration (if any) behind its own registration endpoint.

## What this build does

- Pairs device from a desktop deep link / QR:
  `llamalabels://native-pair?sub=<subscriberId>&hash=<subscriberHash>&srv=<serverUrl>&reg=<registrationUrl>&pt=<pairingToken>`
- Persists pairing proof material (subscriber id/hash, server URL, registration URL, pairing token, last-known device id) in a Keystore-backed `EncryptedSharedPreferences` file — not the plaintext DataStore used for notification history and sync status.
- Registers the FCM token against the backend's native registration endpoint (`reg` from the QR, or derived as `{srv}/api/notifications/native/register` when `reg` is absent) on pair and on token refresh.
- Only marks the device as paired once the registration call actually succeeds (`ok:true`/`synced:true`) — scanning a QR alone does not pair the device.
- Handles FCM data payload keys: `messageId`, `senderName`, `emailSubject`, `Keywords`.
- Shows system notifications and keeps an in-app notification history.
- Supports a per-user **delivery mode** (`push` | `pull`) chosen on the web Notifications page. In `pull` mode the server sends nothing to FCM; the app polls the server directly (see below).

## App Pull mode (FCM/relay bypass)

- The native registration response now also returns `deliveryMode` (`push`|`pull`) and `pullEndpoint`; both are persisted. When `pullEndpoint` is absent it is derived as `{srv}/api/notifications/native/pull`.
- When the mode is `pull`, the app polls `GET {pullEndpoint}?sub=&hash=&after=<cursor>` — auth is the query params only (the same subscriber HMAC `hash`, URL-encoded), no session/bearer. FCM stays registered but is not the source of truth.
- Each returned notification is rendered through the same dispatcher as an FCM data message, so the tap behavior is identical. De-duplication is by the strictly-increasing `seq`; a durable per-subscriber `lastCursor` is advanced (to `max(lastCursor, response.cursor)`) only after notifications are handed off, so a crash re-fetches rather than drops.
- The `deliveryMode` in both the register and pull responses is authoritative: flipping to `push` on the web stops polling; flipping to `pull` resumes it. It is re-read on every app foreground.
- **Cadence tradeoff:** pull mode has no push to wake the app, so background delivery uses WorkManager periodic work at the platform minimum (15 min) plus an immediate pull on app foreground and after (re)pairing. Near-real-time background delivery would require a foreground service with a short poll loop and a persistent notification — intentionally not the default. 400/401/503 and network errors back off without tight-looping.

## Firebase setup

1. Create/update your Firebase Android app for application id `com.urlxl.mail`.
2. Download `google-services.json`.
3. Place it at `app/google-services.json`.
4. Ensure FCM is enabled in Firebase project settings.

## Notification permission behavior (Android 13+)

- App requests `POST_NOTIFICATIONS` at launch.
- If denied, push payloads are still parsed and saved to in-app history when delivered to app process, but system notifications are not shown.

## Pairing from desktop QR

1. Desktop web shows a QR containing the deep link with `sub`, `hash`, `srv`, `pt`, and optionally `reg`.
2. In the Push Notifications screen, tap **Scan QR Code** (or open the deep link directly, e.g. by tapping it elsewhere on the device — the app registers as a handler for `llamalabels://native-pair`).
3. App validates required params (`sub`, `hash`, `srv`, `pt`) and resolves the registration endpoint.
4. App calls the native registration endpoint with the FCM token; the device is marked paired only on success.
5. On later FCM token refreshes, the app repeats the same registration call using the stored pairing.

## Troubleshooting checklist

- Verify the deep link scheme/host is exactly `llamalabels://native-pair` (the older `llamalabels://novu-pair` scheme is no longer supported).
- Verify required query params exist: `sub`, `hash`, `srv`, `pt`.
- Verify network access to the resolved registration endpoint (`reg`, or `{srv}/api/notifications/native/register`).
- Verify Firebase project config matches package `com.urlxl.mail`.
- If registration fails with `400`, the request was malformed or missing fields.
- If registration fails with `401`, the pairing token (`pt`) is expired or invalid — rescan a fresh QR.
- If registration fails with `503`, the backend is missing its `PAIRING_SECRET` configuration (not something the app can retry around).
- If no visible notification on Android 13+, verify notification permission is granted.

## Build and test

```sh
./gradlew testDebugUnitTest
```

```sh
./gradlew assembleDebug
```

Instrumented tests (require a connected device/emulator, used for the EncryptedSharedPreferences-backed pairing store):

```sh
./gradlew connectedDebugAndroidTest
```

## Test coverage included

- Deep-link parser tests (`NativePairingDeepLinkParserTest`)
- Pairing validation tests (`PairingValidatorTest`)
- Native registration endpoint resolution tests (`NativeRegistrationEndpointResolverTest`)
- Payload parser tests (`messageId`, `senderName`, `emailSubject`, `Keywords`)
- Native registration request mapper tests (`NativeRegistrationRequestMapperTest`)
- Secure pairing store round-trip/encryption tests (`SecurePairingStoreTest`, instrumented)
