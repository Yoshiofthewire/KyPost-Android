package com.urlxl.mail.mail

import com.urlxl.mail.push.PairingData
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

private fun testPairing() = PairingData(
    subscriberId = "sub-1",
    subscriberHash = "hash-1",
    serverUrl = "https://relay.example.com",
    registrationUrl = "",
    pairingToken = "",
    deviceId = null,
    pairedAtEpochMs = 0L,
)

/** In-memory fake matching this repo's hand-rolled-fake test style (no mocking framework). */
private class FakeMailCursorProvider(
    var storedCursor: String? = null,
    var forceDue: Boolean = false,
) : MailCursorProvider {
    var savedCursor: String? = null
        private set
    var fullResyncRecorded = false
        private set

    override fun cursor(subscriberId: String, folder: String): String? = storedCursor
    override fun saveCursor(subscriberId: String, folder: String, cursor: String) {
        savedCursor = cursor
        storedCursor = cursor
    }
    override fun shouldForceFullResync(subscriberId: String, folder: String): Boolean = forceDue
    override fun recordFullResync(subscriberId: String, folder: String) {
        fullResyncRecorded = true
    }
}

/** Fakes OkHttp's [Call.Factory] so RelayMailSource can be exercised without a real network call
 *  or a MockWebServer dependency — this repo has neither and prefers hand-rolled fakes. */
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

private fun jsonResponse(request: Request, body: String, code: Int = 200): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("OK")
    .body(body.toResponseBody("application/json".toMediaType()))
    .build()

class RelayMailSourceTest {

    @Test
    fun freshPairing_noPersistedCursor_sendsSinceZero() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = null)
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"tabs": [], "byTab": {}, "cursor": "c1", "delta": true, "removed": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        val outcome = source.fetchInbox("INBOX", 50)

        assertTrue(outcome is MailOutcome.Success)
        assertEquals("0", callFactory.requests.single().url.queryParameter("since"))
        assertEquals("c1", cursorProvider.savedCursor)
    }

    @Test
    fun subsequentPoll_sendsPersistedCursor() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = "cursor-42")
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"tabs": [], "byTab": {}, "cursor": "cursor-43", "delta": true, "removed": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        source.fetchInbox("INBOX", 50)

        assertEquals("cursor-42", callFactory.requests.single().url.queryParameter("since"))
        assertEquals("cursor-43", cursorProvider.savedCursor)
    }

    @Test
    fun deltaPoll_parsesNewUpdatedAndRemoved() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = "cursor-1")
        val body = """
            {
              "tabs": ["Work"],
              "byTab": {
                "Work": [
                  {"messageId": "m1", "sender": "a@example.com", "subject": "New", "body": "Full body", "label": "Work", "status": "unread", "changeType": "new"},
                  {"messageId": "m2", "sender": "b@example.com", "subject": "Updated", "label": "Work", "status": "read", "changeType": "updated"}
                ]
              },
              "cursor": "cursor-2",
              "delta": true,
              "removed": ["m3"]
            }
        """.trimIndent()
        val callFactory = FakeCallFactory { request -> jsonResponse(request, body) }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        val outcome = source.fetchInbox("INBOX", 50)

        val result = (outcome as MailOutcome.Success).value
        assertTrue(result.isDelta)
        assertEquals(setOf("m2"), result.updatedMessageIds)
        assertEquals(listOf("m3"), result.removedMessageIds)
        assertEquals(2, result.messages.size)
        assertNull(result.messages.first { it.id == "m2" }.body)
        assertEquals("Full body", result.messages.first { it.id == "m1" }.body)
        assertEquals("cursor-2", cursorProvider.savedCursor)
    }

    @Test
    fun explicitForceFullResync_sendsSinceZero_regardlessOfPersistedCursor() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = "cursor-99")
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"tabs": [], "byTab": {}, "cursor": "cursor-100", "delta": true, "removed": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        source.fetchInbox("INBOX", 50, forceFullResync = true)

        assertEquals("0", callFactory.requests.single().url.queryParameter("since"))
        assertTrue(cursorProvider.fullResyncRecorded)
    }

    @Test
    fun cadenceDue_sendsSinceZero_evenWithoutExplicitForce() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = "cursor-5", forceDue = true)
        val callFactory = FakeCallFactory { request ->
            jsonResponse(request, """{"tabs": [], "byTab": {}, "cursor": "cursor-6", "delta": true, "removed": []}""")
        }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        source.fetchInbox("INBOX", 50)

        assertEquals("0", callFactory.requests.single().url.queryParameter("since"))
        assertTrue(cursorProvider.fullResyncRecorded)
    }

    @Test
    fun nonDeltaLegacyResponse_stillParsesAsFullSnapshot() {
        val cursorProvider = FakeMailCursorProvider(storedCursor = null)
        val body = """
            {
              "tabs": ["Work"],
              "byTab": {"Work": [{"messageId": "m1", "sender": "a@example.com", "subject": "S", "body": "B", "label": "Work", "status": "unread"}]}
            }
        """.trimIndent()
        val callFactory = FakeCallFactory { request -> jsonResponse(request, body) }
        val source = RelayMailSource(
            pairingProvider = { testPairing() },
            cursorProvider = cursorProvider,
            callFactory = callFactory,
        )

        val outcome = source.fetchInbox("INBOX", 50)

        val result = (outcome as MailOutcome.Success).value
        assertTrue(!result.isDelta)
        assertEquals(1, result.messages.size)
        assertNull(cursorProvider.savedCursor)
    }
}
