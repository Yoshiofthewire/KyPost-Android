# Pairing Auth: Query Params to Headers (Android Client) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch every networking class in this app that sends pairing-auth
credentials (`subscriberId`/`subscriberHash`) from attaching them as
`?sub=&hash=` URL query params to sending them as `X-Kypost-Subscriber-Id`/
`X-Kypost-Subscriber-Hash` HTTP headers, so the credentials stop appearing
in server access logs / reverse-proxy logs.

**Architecture:** Add one small extension function,
`Request.Builder.pairingAuthHeaders(subscriberId, subscriberHash):
Request.Builder`, and use it at every call site that currently does
`.addQueryParameter("sub", ...)` / `.addQueryParameter("hash", ...)`. The
server (`kypost-server`, already shipped — see
`docs/superpowers/plans/2026-07-19-pairing-auth-headers.md` in that sibling
repo) already accepts the new headers and prefers them over the legacy
query params, so this client change is a clean cutover with no dual-write
needed: this app can just always send headers going forward.

**Tech Stack:** Kotlin, OkHttp 5.2.1 (`Request.Builder.header(name,
value)` — a long-stable OkHttp API, already relied on elsewhere in this
codebase for reading response headers, just not yet for writing request
headers), JUnit4 (plain JVM unit tests, no mocking framework, no
MockWebServer — this codebase uses hand-rolled `Call.Factory` fakes
throughout).

## Global Constraints

- Build: `./gradlew :app:compileDebugKotlin` (or a full `./gradlew
  assembleDebug`) must succeed with zero errors.
- Unit tests: `./gradlew testDebugUnitTest` must pass for every
  touched/added test class.
- No new dependencies — `Request.Builder.header()` is stdlib-equivalent
  OkHttp API, already a transitive part of the existing `okhttp` dependency.
- Header names are exactly `X-Kypost-Subscriber-Id` and
  `X-Kypost-Subscriber-Hash`, matching what the server (already shipped)
  reads.
- This is a clean cutover, not a dual-write: the client sends headers only,
  no `?sub=&hash=` fallback. The server accepts both (already shipped), so
  this is safe — no coordination window needed on the client side.
- Scope is exactly the 5 files below plus one new shared helper file. Out
  of scope, do not touch: `PgpQrClient.fetchKey` (unrelated single-use `t`
  token, not `sub`/`hash`), `MfaResponseClient.kt`, and
  `NativeRegistrationClient`/`NativeRegistration.kt` (both already send
  credentials in the POST body, nothing to change).
- Every class doc comment that currently states "query params only" or
  similar must be updated to describe the header-based auth — these
  comments are being relied on as documentation of the auth mechanism, so
  leaving them stale after this change would be misleading.
- Full background: `docs/superpowers/specs/2026-07-19-pairing-auth-headers-design.md`
  (note: this design doc originally scoped only this repo's Android client;
  the actual rollout also covered the server and, in later separate plans,
  kypost-Linux and kypost-for-Mac).

---

### Task 1: Shared `pairingAuthHeaders()` extension helper

**Files:**
- Create: `app/src/main/java/com/urlxl/mail/PairingAuthHeaders.kt`
- Test: `app/src/test/java/com/urlxl/mail/PairingAuthHeadersTest.kt` (new file)

**Interfaces:**
- Consumes: nothing new — only `okhttp3.Request`.
- Produces: `fun Request.Builder.pairingAuthHeaders(subscriberId: String,
  subscriberHash: String): Request.Builder` and the constants
  `HEADER_SUBSCRIBER_ID`, `HEADER_SUBSCRIBER_HASH` — all in package
  `com.urlxl.mail` (root package, matching where the existing shared
  networking helper `HttpExecute.kt`'s `executeSync` lives). Tasks 2–6 each
  add `import com.urlxl.mail.pairingAuthHeaders` and call this function by
  name on their `Request.Builder` chains.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/urlxl/mail/PairingAuthHeadersTest.kt`:

```kotlin
package com.urlxl.mail

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingAuthHeadersTest {

    @Test
    fun pairingAuthHeaders_setsBothHeadersOnTheRequest() {
        val request = Request.Builder()
            .url("https://relay.example.com/api/inbox".toHttpUrl())
            .get()
            .pairingAuthHeaders("sub-1", "hash-1")
            .build()

        assertEquals("sub-1", request.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", request.header(HEADER_SUBSCRIBER_HASH))
    }

    @Test
    fun pairingAuthHeaders_doesNotAddQueryParams() {
        val request = Request.Builder()
            .url("https://relay.example.com/api/inbox".toHttpUrl())
            .get()
            .pairingAuthHeaders("sub-1", "hash-1")
            .build()

        assertNull(request.url.queryParameter("sub"))
        assertNull(request.url.queryParameter("hash"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.PairingAuthHeadersTest"`
Expected: FAIL — `pairingAuthHeaders`, `HEADER_SUBSCRIBER_ID`, and
`HEADER_SUBSCRIBER_HASH` are undefined.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/urlxl/mail/PairingAuthHeaders.kt`:

```kotlin
package com.urlxl.mail

import okhttp3.Request

const val HEADER_SUBSCRIBER_ID = "X-Kypost-Subscriber-Id"
const val HEADER_SUBSCRIBER_HASH = "X-Kypost-Subscriber-Hash"

/**
 * Attaches pairing-auth credentials as headers — the server (already migrated) reads these in
 * preference to the legacy `?sub=&hash=` query params. This app always sends headers now; there
 * is no query-param fallback on the client side.
 */
fun Request.Builder.pairingAuthHeaders(subscriberId: String, subscriberHash: String): Request.Builder =
    header(HEADER_SUBSCRIBER_ID, subscriberId).header(HEADER_SUBSCRIBER_HASH, subscriberHash)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.PairingAuthHeadersTest"`
Expected: PASS (2/2 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/PairingAuthHeaders.kt app/src/test/java/com/urlxl/mail/PairingAuthHeadersTest.kt
git commit -m "mail: add shared pairing-auth header extension helper"
```

---

### Task 2: `RelayMailSource.kt` — switch all 10 call sites to headers

This file has the most call sites: a shared `authed(base, pairing): HttpUrl`
helper used directly by 5 methods, chained-with-more-query-params by 3
methods, plus 2 methods (`fetchInbox`, `listFolders`) that never used
`authed()` and inlined `sub`/`hash` separately — the exact duplication this
file's own class doc comment already calls out. `authed()` returned an
`HttpUrl` because query params live on the URL; headers don't, so `authed()`
is deleted outright and every call site is rewritten to build the plain
`base` URL and attach headers via `.pairingAuthHeaders(...)` on the
`Request.Builder` instead.

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/mail/RelayMailSource.kt`
- Test: `app/src/test/java/com/urlxl/mail/mail/RelayMailSourceTest.kt`

**Interfaces:**
- Consumes: `pairingAuthHeaders` from Task 1.
- Produces: no new public interface — `RelayMailSource`'s public method
  signatures are unchanged. The private `authed(base, pairing): HttpUrl`
  helper is removed.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/urlxl/mail/mail/RelayMailSourceTest.kt`, add
`import com.urlxl.mail.HEADER_SUBSCRIBER_ID` and `import
com.urlxl.mail.HEADER_SUBSCRIBER_HASH` to the import block, and add these 5
tests inside `class RelayMailSourceTest { ... }` (anywhere after the
existing tests):

```kotlin
    @Test
    fun fetchInbox_sendsPairingHeaders_notQueryParams() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = null)
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"tabs": [], "byTab": {}, "cursor": "c1", "delta": true, "removed": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        source.fetchInbox("INBOX", 50)

        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
    }

    @Test
    fun listFolders_sendsPairingHeaders_notQueryParams() {
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"parent": null, "folders": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = FakeMailCursorProvider(),
            callFactory = callFactory,
        )

        source.listFolders(null)

        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
    }

    @Test
    fun createFolder_sendsPairingHeaders_notQueryParams() {
        val callFactory = FakeCallFactory { request -> jsonResponse(request, "", code = 200) }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = FakeMailCursorProvider(),
            callFactory = callFactory,
        )

        source.createFolder("INBOX", "New Folder")

        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
    }

    @Test
    fun deleteFolder_sendsPairingHeaders_notQueryParams() {
        val callFactory = FakeCallFactory { request -> jsonResponse(request, "", code = 200) }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = FakeMailCursorProvider(),
            callFactory = callFactory,
        )

        source.deleteFolder("INBOX/Old")

        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("INBOX/Old", sentRequest.url.queryParameter("folder"))
    }

    @Test
    fun downloadAttachment_sendsPairingHeaders_notQueryParams() {
        val callFactory = FakeCallFactory { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Disposition", "attachment; filename=\"file.pdf\"")
                .header("Content-Type", "application/pdf")
                .body("bytes".toResponseBody("application/pdf".toMediaType()))
                .build()
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = FakeMailCursorProvider(),
            callFactory = callFactory,
        )

        source.downloadAttachment("m1", "INBOX", 0)

        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
    }
```

These 5 tests cover all 3 structural shapes in this file: the two
originally-inlined GET sites (`fetchInbox`, `listFolders`), the
direct-`authed()`-as-`.url()` pattern shared by `createFolder`,
`renameFolder`, `performAction`, `saveDraft`, `sendMail` (represented here
by `createFolder`), the chained-`authed().newBuilder()` pattern shared by
`deleteFolder` and `listAttachments` (represented here by `deleteFolder`,
which also proves the chained `folder` query param still works alongside
headers), and the one method that bypasses the shared `execute()` wrapper
entirely for binary handling (`downloadAttachment`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.mail.RelayMailSourceTest"`
Expected: the 5 new tests FAIL (headers are null — the code still only
sends query params); the pre-existing tests still PASS.

- [ ] **Step 3: Rewrite the production code**

In `app/src/main/java/com/urlxl/mail/mail/RelayMailSource.kt`:

Add to the import block (after the existing `com.urlxl.mail.executeSync`
import):
```kotlin
import com.urlxl.mail.pairingAuthHeaders
```

Update the class doc comment (currently line 24):
```kotlin
 * Auth is `sub`/`hash` query params only, sourced from the pairing state (never headers/cookies).
```
to:
```kotlin
 * Auth is sent as X-Kypost-Subscriber-Id/X-Kypost-Subscriber-Hash headers, sourced from the
 * pairing state (never query params/cookies).
```

In `fetchInbox`, replace:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("sub", pairing.subscriberId)
            .addQueryParameter("hash", pairing.subscriberHash)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("mailbox", mailbox)
            .addQueryParameter("since", since)
            .build()
        return execute(Request.Builder().url(url).get().build()) { code, body ->
```
with:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("mailbox", mailbox)
            .addQueryParameter("since", since)
            .build()
        val request = Request.Builder().url(url).get()
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
        return execute(request) { code, body ->
```

In `listFolders`, replace:
```kotlin
        val urlBuilder = base.newBuilder()
            .addQueryParameter("sub", pairing.subscriberId)
            .addQueryParameter("hash", pairing.subscriberHash)
        if (!parent.isNullOrBlank()) urlBuilder.addQueryParameter("parent", parent)
        return execute(Request.Builder().url(urlBuilder.build()).get().build()) { code, body ->
```
with:
```kotlin
        val urlBuilder = base.newBuilder()
        if (!parent.isNullOrBlank()) urlBuilder.addQueryParameter("parent", parent)
        val request = Request.Builder().url(urlBuilder.build()).get()
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
        return execute(request) { code, body ->
```

In `createFolder`, replace:
```kotlin
        val request = Request.Builder().url(authed(base, pairing)).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val request = Request.Builder().url(base).post(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
```

In `renameFolder`, replace:
```kotlin
        val request = Request.Builder().url(authed(base, pairing)).put(body.toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val request = Request.Builder().url(base).put(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
```

In `deleteFolder`, replace:
```kotlin
        val url = authed(base, pairing).newBuilder().addQueryParameter("folder", folder).build()
        return execute(Request.Builder().url(url).delete().build()) { code, rawBody -> mutationOutcome(code, rawBody) }
```
with:
```kotlin
        val url = base.newBuilder().addQueryParameter("folder", folder).build()
        val request = Request.Builder().url(url).delete()
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
        return execute(request) { code, rawBody -> mutationOutcome(code, rawBody) }
```

In `performAction`, replace:
```kotlin
        val request = Request.Builder().url(authed(base, pairing)).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val request = Request.Builder().url(base).post(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
```

In `saveDraft`, replace:
```kotlin
        val request = Request.Builder().url(authed(base, pairing)).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val request = Request.Builder().url(base).post(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
```

In `sendMail`, replace:
```kotlin
        val request = Request.Builder().url(authed(base, pairing)).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val request = Request.Builder().url(base).post(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
```

In `listAttachments`, replace:
```kotlin
        val url = authed(base, pairing).newBuilder()
            .addQueryParameter("mailbox", folder)
            .addQueryParameter("messageId", messageId)
            .build()
        return execute(Request.Builder().url(url).get().build()) { code, body ->
```
with:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("mailbox", folder)
            .addQueryParameter("messageId", messageId)
            .build()
        val request = Request.Builder().url(url).get()
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
        return execute(request) { code, body ->
```

In `downloadAttachment`, replace:
```kotlin
        val url = authed(base, pairing).newBuilder()
            .addQueryParameter("mailbox", folder)
            .addQueryParameter("messageId", messageId)
            .addQueryParameter("index", index.toString())
            .build()
        // Binary response: read bytes and metadata headers inside the use block, not execute()'s
        // string() path.
        val result = callFactory.executeSync(Request.Builder().url(url).get().build()) { response ->
```
with:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("mailbox", folder)
            .addQueryParameter("messageId", messageId)
            .addQueryParameter("index", index.toString())
            .build()
        val request = Request.Builder().url(url).get()
            .pairingAuthHeaders(pairing.subscriberId, pairing.subscriberHash)
            .build()
        // Binary response: read bytes and metadata headers inside the use block, not execute()'s
        // string() path.
        val result = callFactory.executeSync(request) { response ->
```

Finally, delete the now-unused `authed()` helper entirely:
```kotlin
    private fun authed(base: HttpUrl, pairing: PairingData): HttpUrl = base.newBuilder()
        .addQueryParameter("sub", pairing.subscriberId)
        .addQueryParameter("hash", pairing.subscriberHash)
        .build()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.mail.RelayMailSourceTest"`
Expected: PASS (all tests, including the 5 new ones and every pre-existing
one)

- [ ] **Step 5: Verify the build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS — confirms `authed()`'s removal left no dangling
references and the `HttpUrl` import is still needed (by `baseUrl(...):
HttpUrl?`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/mail/RelayMailSource.kt app/src/test/java/com/urlxl/mail/mail/RelayMailSourceTest.kt
git commit -m "mail: send pairing auth as headers in RelayMailSource"
```

---

### Task 3: `ContactSyncClient.kt` — switch `pull`/`push`/`dedupe` to headers

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/ContactSyncClient.kt`
- Test: `app/src/test/java/com/urlxl/mail/contacts/ContactSyncClientTest.kt`

**Interfaces:**
- Consumes: `pairingAuthHeaders` from Task 1.
- Produces: no interface change.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/com/urlxl/mail/contacts/ContactSyncClientTest.kt`, add
`import com.urlxl.mail.HEADER_SUBSCRIBER_ID`, `import
com.urlxl.mail.HEADER_SUBSCRIBER_HASH`, and `import
org.junit.Assert.assertNull` to the import block. Update the existing
`dedupe_200_decodesReportAndSendsExpectedRequest` test's assertion block
from:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals("https://relay.example.com/api/contacts/dedupe", sentRequest.url.newBuilder().query(null).build().toString())
        assertEquals("sub-1", sentRequest.url.queryParameter("sub"))
        assertEquals("hash-1", sentRequest.url.queryParameter("hash"))
        assertEquals("POST", sentRequest.method)
```
to:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals("https://relay.example.com/api/contacts/dedupe", sentRequest.url.newBuilder().query(null).build().toString())
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("POST", sentRequest.method)
```

Then add these 2 new tests (`pull` and `push` currently have zero test
coverage of any kind in this file — these are new, not just header
variants of existing tests):

```kotlin
    @Test
    fun pull_200_sendsPairingHeaders_notQueryParams() = runBlocking {
        val callFactory = FakeCallFactory { request -> response(request, "{}", 200) }
        val client = ContactSyncClient(callFactory = callFactory)

        val result = client.pull("https://relay.example.com", "sub-1", "hash-1", since = 0L)

        assertTrue(result is ContactSyncResult.Success)
        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("0", sentRequest.url.queryParameter("since"))
    }

    @Test
    fun push_200_sendsPairingHeaders_notQueryParams() = runBlocking {
        val callFactory = FakeCallFactory { request -> response(request, "{}", 200) }
        val client = ContactSyncClient(callFactory = callFactory)

        val result = client.push("https://relay.example.com", "sub-1", "hash-1", baseCursor = 0L, changes = emptyList())

        assertTrue(result is ContactSyncResult.Success)
        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("POST", sentRequest.method)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactSyncClientTest"`
Expected: `dedupe_200_decodesReportAndSendsExpectedRequest`,
`pull_200_sendsPairingHeaders_notQueryParams`, and
`push_200_sendsPairingHeaders_notQueryParams` FAIL (headers are null); the
other pre-existing `dedupe_*` tests still PASS (they don't assert on
sub/hash).

- [ ] **Step 3: Rewrite the production code**

In `app/src/main/java/com/urlxl/mail/contacts/ContactSyncClient.kt`, add to
the import block:
```kotlin
import com.urlxl.mail.pairingAuthHeaders
```

Update the class doc comment (currently lines 42-45):
```kotlin
 * Talks to `/api/contacts/sync`. Auth is `sub`/`hash` query params only (never headers/cookies),
 * kept parallel to [com.urlxl.mail.push.PullNotificationClient] — same okhttp/serialization stack.
```
to:
```kotlin
 * Talks to `/api/contacts/sync`. Auth is sent as X-Kypost-Subscriber-Id/X-Kypost-Subscriber-Hash
 * headers (never query params/cookies), kept parallel to
 * [com.urlxl.mail.push.PullNotificationClient] — same okhttp/serialization stack.
```

Replace `pull`'s body:
```kotlin
    suspend fun pull(serverUrl: String, subscriberId: String, subscriberHash: String, since: Long): ContactSyncResult {
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .addQueryParameter("since", since.coerceAtLeast(0L).toString())
            .build()
        return execute(Request.Builder().url(url).get().build())
    }
```
with:
```kotlin
    suspend fun pull(serverUrl: String, subscriberId: String, subscriberHash: String, since: Long): ContactSyncResult {
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("since", since.coerceAtLeast(0L).toString())
            .build()
        val request = Request.Builder().url(url).get()
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()
        return execute(request)
    }
```

Replace `push`'s body:
```kotlin
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .build()
        val body = json.encodeToString(ContactSyncPushRequestDto(baseCursor = baseCursor, changes = changes))
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return execute(request)
```
with:
```kotlin
        val base = syncUrl(serverUrl) ?: return ContactSyncResult.BadRequest("Server URL is not valid")
        val body = json.encodeToString(ContactSyncPushRequestDto(baseCursor = baseCursor, changes = changes))
        val request = Request.Builder().url(base).post(body.toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()
        return execute(request)
```

Replace `dedupe`'s URL/request construction:
```kotlin
        val base = dedupeUrl(serverUrl) ?: return ContactDedupeResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .build()
        val request = Request.Builder().url(url).post("".toRequestBody(JSON_MEDIA_TYPE)).build()
```
with:
```kotlin
        val base = dedupeUrl(serverUrl) ?: return ContactDedupeResult.BadRequest("Server URL is not valid")
        val request = Request.Builder().url(base).post("".toRequestBody(JSON_MEDIA_TYPE))
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.ContactSyncClientTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/ContactSyncClient.kt app/src/test/java/com/urlxl/mail/contacts/ContactSyncClientTest.kt
git commit -m "contacts: send pairing auth as headers in ContactSyncClient"
```

---

### Task 4: `GroupsSyncClient.kt` — switch `pull` to headers

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/contacts/GroupsSyncClient.kt`
- Test: `app/src/test/java/com/urlxl/mail/contacts/GroupsSyncClientTest.kt`

**Interfaces:**
- Consumes: `pairingAuthHeaders` from Task 1.
- Produces: no interface change.

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/urlxl/mail/contacts/GroupsSyncClientTest.kt`, add
`import com.urlxl.mail.HEADER_SUBSCRIBER_ID`, `import
com.urlxl.mail.HEADER_SUBSCRIBER_HASH`, and `import
org.junit.Assert.assertNull` to the import block. Update
`pull_200_decodesGroupsAndSendsExpectedRequest`'s assertion block from:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals("https://relay.example.com/api/groups", sentRequest.url.newBuilder().query(null).build().toString())
        assertEquals("sub-1", sentRequest.url.queryParameter("sub"))
        assertEquals("hash-1", sentRequest.url.queryParameter("hash"))
        assertEquals("GET", sentRequest.method)
```
to:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals("https://relay.example.com/api/groups", sentRequest.url.newBuilder().query(null).build().toString())
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("GET", sentRequest.method)
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.GroupsSyncClientTest"`
Expected: `pull_200_decodesGroupsAndSendsExpectedRequest` FAILs (headers are
null); other tests in this file are unaffected.

- [ ] **Step 3: Rewrite the production code**

In `app/src/main/java/com/urlxl/mail/contacts/GroupsSyncClient.kt`, add to
the import block:
```kotlin
import com.urlxl.mail.pairingAuthHeaders
```

Update the class doc comment (currently lines 20-26), changing the phrase
`mirroring [ContactSyncClient]'s \`sub\`/\`hash\` query-param auth` to
`mirroring [ContactSyncClient]'s X-Kypost-Subscriber-Id/X-Kypost-Subscriber-Hash
header auth` (rest of the comment unchanged).

Replace `pull`'s URL/request construction:
```kotlin
        val base = groupsUrl(serverUrl) ?: return GroupsSyncResult.BadRequest("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .build()
        val request = Request.Builder().url(url).get().build()
```
with:
```kotlin
        val base = groupsUrl(serverUrl) ?: return GroupsSyncResult.BadRequest("Server URL is not valid")
        val request = Request.Builder().url(base).get()
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.contacts.GroupsSyncClientTest"`
Expected: PASS (all tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/contacts/GroupsSyncClient.kt app/src/test/java/com/urlxl/mail/contacts/GroupsSyncClientTest.kt
git commit -m "contacts: send pairing auth as headers in GroupsSyncClient"
```

---

### Task 5: `PullNotificationClient.kt` — switch `pull` to headers, and widen the constructor for testability

This class's constructor currently takes the concrete `OkHttpClient`
instead of `Call.Factory` like the other four networking classes in this
plan — the one thing standing between it and the same
fake-injection test pattern used everywhere else. `OkHttpClient` already
implements `Call.Factory`, so widening the parameter's declared type is a
same-shape, backward-compatible change (confirmed: the only production
construction site, `PullSyncCoordinator.kt:22`, uses the default value with
no named argument, so a rename doesn't break it). This task makes that
change alongside the header switch so the new test can use the same
hand-rolled `FakeCallFactory` pattern as every sibling class in this plan,
rather than introducing a different testing approach just for this one file.

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/push/PullNotificationClient.kt`
- Test: `app/src/test/java/com/urlxl/mail/push/PullNotificationClientTest.kt`
  (new file — no test file for this class exists today; the similarly-named
  `PullNotificationTest.kt` in the same package tests unrelated pure
  functions and has no `Call`/`Request`/`Response` fakes, so a fresh,
  unprefixed `FakeCallFactory` here does not collide with it)

**Interfaces:**
- Consumes: `pairingAuthHeaders` from Task 1.
- Produces: `PullNotificationClient`'s constructor parameter changes from
  `okHttpClient: OkHttpClient` to `callFactory: Call.Factory` (same
  position, same default `OkHttpClient.Builder().build()`). No other
  public interface change — `pull(...)`'s signature and `PullResult` are
  unchanged.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/urlxl/mail/push/PullNotificationClientTest.kt`:

```kotlin
package com.urlxl.mail.push

import com.urlxl.mail.HEADER_SUBSCRIBER_HASH
import com.urlxl.mail.HEADER_SUBSCRIBER_ID
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fakes OkHttp's [Call.Factory], mirroring RelayMailSourceTest/ContactSyncClientTest's
 *  hand-rolled-fake style (no mocking framework, no MockWebServer dependency in this repo). */
private class FakeCallFactory(private val responder: (Request) -> Response) : Call.Factory {
    val requests = mutableListOf<Request>()

    override fun newCall(request: Request): Call {
        requests.add(request)
        return FakeCall(request, responder(request))
    }
}

private class FakeCall(private val req: Request, private val response: Response) : Call {
    private var executed = false
    private var canceled = false
    override fun request(): Request = req
    override fun execute(): Response {
        executed = true
        return response
    }
    override fun enqueue(responseCallback: Callback) = responseCallback.onResponse(this, response)
    override fun cancel() { canceled = true }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = canceled
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = FakeCall(req, response)
}

private fun response(request: Request, body: String, code: Int, message: String = "OK"): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message(message)
    .body(body.toResponseBody("application/json".toMediaType()))
    .build()

class PullNotificationClientTest {

    @Test
    fun pull_200_sendsPairingHeaders_notQueryParams() = runBlocking {
        val callFactory = FakeCallFactory { request ->
            response(request, """{"notifications": [], "cursor": 0}""", 200)
        }
        val client = PullNotificationClient(callFactory = callFactory)

        val result = client.pull(
            pullEndpoint = "https://relay.example.com/api/notifications/native/pull",
            subscriberId = "sub-1",
            subscriberHash = "hash-1",
            afterCursor = 0L,
        )

        assertTrue(result is PullResult.Success)
        val sentRequest = callFactory.requests.single()
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("0", sentRequest.url.queryParameter("after"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.PullNotificationClientTest"`
Expected: FAIL to compile — `PullNotificationClient(callFactory = ...)` does
not match the current constructor, which takes `okHttpClient`.

- [ ] **Step 3: Rewrite the production code**

In `app/src/main/java/com/urlxl/mail/push/PullNotificationClient.kt`, add
to the import block:
```kotlin
import com.urlxl.mail.pairingAuthHeaders
import okhttp3.Call
```

Update the class doc comment (currently lines 29-33):
```kotlin
/**
 * Talks to `GET <pullEndpoint>?sub=&hash=&after=`. Auth is the query params only; okhttp's
 * query builder URL-encodes them (notably the HMAC `hash`). Kept parallel to
 * [NativeRegistrationClient] — same okhttp/serialization stack, no session/bearer.
 */
```
to:
```kotlin
/**
 * Talks to `GET <pullEndpoint>?after=`. Auth is sent as X-Kypost-Subscriber-Id/
 * X-Kypost-Subscriber-Hash headers, never query params. Kept parallel to
 * [NativeRegistrationClient] — same okhttp/serialization stack, no session/bearer.
 */
```

Replace the class constructor:
```kotlin
class PullNotificationClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder().build(),
) {
```
with:
```kotlin
class PullNotificationClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    // Call.Factory (not the concrete OkHttpClient) so tests can inject a fake without a real
    // network call or a MockWebServer dependency; OkHttpClient itself satisfies this interface.
    // Mirrors RelayMailSource/ContactSyncClient/GroupsSyncClient/PgpQrClient's callFactory pattern.
    private val callFactory: Call.Factory = OkHttpClient.Builder().build(),
) {
```

Replace `pull`'s URL/request construction and its use of `okHttpClient`:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .addQueryParameter("after", afterCursor.coerceAtLeast(0L).toString())
            .build()

        val httpRequest = Request.Builder().url(url).get().build()

        val result = withContext(Dispatchers.IO) {
            okHttpClient.executeSync(httpRequest) { response ->
```
with:
```kotlin
        val url = base.newBuilder()
            .addQueryParameter("after", afterCursor.coerceAtLeast(0L).toString())
            .build()

        val httpRequest = Request.Builder().url(url).get()
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()

        val result = withContext(Dispatchers.IO) {
            callFactory.executeSync(httpRequest) { response ->
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.push.PullNotificationClientTest"`
Expected: PASS

- [ ] **Step 5: Verify the production caller still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: SUCCESS — confirms `PullSyncCoordinator.kt:22`'s
`PullNotificationClient()` default-arg construction still type-checks
against the renamed/retyped parameter.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/push/PullNotificationClient.kt app/src/test/java/com/urlxl/mail/push/PullNotificationClientTest.kt
git commit -m "push: send pairing auth as headers in PullNotificationClient, widen to Call.Factory for testability"
```

---

### Task 6: `PgpQrClient.kt` — switch `mintToken` to headers

**Files:**
- Modify: `app/src/main/java/com/urlxl/mail/pgp/PgpQrClient.kt`
- Test: `app/src/test/java/com/urlxl/mail/pgp/PgpQrClientTest.kt`

**Interfaces:**
- Consumes: `pairingAuthHeaders` from Task 1.
- Produces: no interface change. `fetchKey` (unrelated `t`-token auth) is
  untouched.

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/com/urlxl/mail/pgp/PgpQrClientTest.kt`, add `import
com.urlxl.mail.HEADER_SUBSCRIBER_ID` and `import
com.urlxl.mail.HEADER_SUBSCRIBER_HASH` to the import block (`assertNull` is
already imported). Update `mintToken_200_decodesTokenAndSendsExpectedRequest`'s
assertion block from:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals(
            "https://relay.example.com/api/pgp/qr/token",
            sentRequest.url.newBuilder().query(null).build().toString(),
        )
        assertEquals("sub-1", sentRequest.url.queryParameter("sub"))
        assertEquals("hash-1", sentRequest.url.queryParameter("hash"))
        assertEquals("GET", sentRequest.method)
```
to:
```kotlin
        val sentRequest = callFactory.requests.single()
        assertEquals(
            "https://relay.example.com/api/pgp/qr/token",
            sentRequest.url.newBuilder().query(null).build().toString(),
        )
        assertEquals("sub-1", sentRequest.header(HEADER_SUBSCRIBER_ID))
        assertEquals("hash-1", sentRequest.header(HEADER_SUBSCRIBER_HASH))
        assertNull(sentRequest.url.queryParameter("sub"))
        assertNull(sentRequest.url.queryParameter("hash"))
        assertEquals("GET", sentRequest.method)
```

Do not modify the `fetchKey_*` tests — that method is out of scope and its
existing assertions that `sub`/`hash` are absent must keep passing
unmodified.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpQrClientTest"`
Expected: `mintToken_200_decodesTokenAndSendsExpectedRequest` FAILs (headers
are null); `fetchKey_*` tests are unaffected.

- [ ] **Step 3: Rewrite the production code**

In `app/src/main/java/com/urlxl/mail/pgp/PgpQrClient.kt`, add to the import
block:
```kotlin
import com.urlxl.mail.pairingAuthHeaders
```

Update the two doc comments that mention `sub`/`hash` query params — line
12:
```kotlin
/** Outcome of `GET /api/pgp/qr/token` (pairing-authenticated via `sub`/`hash` query params). */
```
to:
```kotlin
/** Outcome of `GET /api/pgp/qr/token` (pairing-authenticated via X-Kypost-Subscriber-Id/X-Kypost-Subscriber-Hash headers). */
```

and the class doc comment (currently lines 49-55):
```kotlin
/**
 * Talks to the backend's PGP QR key-exchange endpoints. `mintToken` is pairing-authenticated
 * exactly like every other endpoint this app calls (`sub`/`hash` query params, never a session
 * cookie — this app has no session-cookie concept). `fetchKey` is unauthenticated; the token
 * itself is the credential. Kept parallel to [com.urlxl.mail.contacts.ContactSyncClient] — same
 * okhttp/serialization stack and status-code-to-result mapping shape.
 */
```
to:
```kotlin
/**
 * Talks to the backend's PGP QR key-exchange endpoints. `mintToken` is pairing-authenticated
 * exactly like every other endpoint this app calls (X-Kypost-Subscriber-Id/X-Kypost-Subscriber-Hash
 * headers, never a session cookie — this app has no session-cookie concept). `fetchKey` is
 * unauthenticated; the token itself is the credential. Kept parallel to
 * [com.urlxl.mail.contacts.ContactSyncClient] — same okhttp/serialization stack and
 * status-code-to-result mapping shape.
 */
```

Replace `mintToken`'s URL/request construction:
```kotlin
        val base = tokenUrl(serverUrl) ?: return PgpQrTokenResult.Retryable("Server URL is not valid")
        val url = base.newBuilder()
            .addQueryParameter("sub", subscriberId)
            .addQueryParameter("hash", subscriberHash)
            .build()

        val result = executeRequest(Request.Builder().url(url).get().build())
```
with:
```kotlin
        val base = tokenUrl(serverUrl) ?: return PgpQrTokenResult.Retryable("Server URL is not valid")
        val request = Request.Builder().url(base).get()
            .pairingAuthHeaders(subscriberId, subscriberHash)
            .build()

        val result = executeRequest(request)
```

Do not modify `fetchKey` — it only uses the unrelated `t` token and is
explicitly out of scope for this plan.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.urlxl.mail.pgp.PgpQrClientTest"`
Expected: PASS (all tests, including all `fetchKey_*` tests unmodified)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/urlxl/mail/pgp/PgpQrClient.kt app/src/test/java/com/urlxl/mail/pgp/PgpQrClientTest.kt
git commit -m "pgp: send pairing auth as headers in PgpQrClient mintToken"
```

---

### Final verification (after all 6 tasks)

- [ ] Run the full unit test suite: `./gradlew testDebugUnitTest` — must
  pass, including every touched test class.
- [ ] Run `./gradlew assembleDebug` — must succeed with zero errors.
- [ ] `grep -rn 'addQueryParameter("sub"\|addQueryParameter("hash"' app/src/main`
  — must return zero matches (confirms no call site was missed).
- [ ] Manual: install a debug build, pair a device against a server running
  the already-shipped header-accepting backend, and confirm mail
  fetch/folder list/contact sync/group pull/PGP QR pairing all still work
  end-to-end (these endpoints now depend entirely on headers reaching the
  server correctly — the fastest way to catch a missed or malformed header
  is exercising each flow once on a real device).

### Out of scope for this plan

- `PgpQrClient.fetchKey`, `MfaResponseClient`, `NativeRegistrationClient`/
  `NativeRegistration.kt` — untouched, as documented in Global Constraints.
- kypost-Linux and kypost-for-Mac client changes — separate plans, each
  gated on nothing further (the server already accepts headers), but out of
  scope for this Android-only plan.
- The server-side removal of legacy `?sub=&hash=` query-param support
  (Rollout Step 3 in the server's design doc) — that's a server-repo change
  gated on client adoption metrics, not part of this plan.
