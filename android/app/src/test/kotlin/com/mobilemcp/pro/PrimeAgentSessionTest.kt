package com.mobilemcp.pro

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Exercise real agent turns and HTTP/SSE boundaries, without a live account. */
class PrimeAgentSessionTest {
    private class ApiFixture : AutoCloseable {
        private val server = MockWebServer()
        val answers = ConcurrentLinkedQueue<String>()
        val requests = CopyOnWriteArrayList<JSONObject>()
        val modelRequests = AtomicInteger()
        val credentials = object : PrimeCredentials {
            override fun isSignedIn() = true
            override suspend fun accessToken() = "test-token"
        }
        val endpoints: PrimeApiEndpoints
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/models") {
                        modelRequests.incrementAndGet()
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"models":[{"slug":"test_sol","display_name":"Test Sol","visibility":"list"}]}""")
                    }
                    if (request.path != "/responses") return MockResponse().setResponseCode(404)
                    requests += JSONObject(request.body.readUtf8())
                    val answer = answers.poll() ?: """{"type":"reply","text":"پاسخ آزمون"}"""
                    val event = JSONObject().put("type", "response.completed").put("response", JSONObject()
                        .put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "message")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", answer))))))
                    return MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("event: response.completed\ndata: $event\n\n")
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            endpoints = PrimeApiEndpoints(server.url("/models").toString(), server.url("/responses").toString())
        }
        fun agent() = PrimeAgent(credentials, endpoints).also { it.resetSession() }
        override fun close() { server.shutdown() }
    }

    private val ui = """{"status":"ready","localPhoneControl":true,"screen":{"package":"test.telegram","nodes":[{"text":"علی","clickable":true}]}}"""
    private suspend fun turn(agent: PrimeAgent, text: String,
        runner: suspend (String, JSONObject) -> PrimeActionResult,
        uiProvider: suspend () -> String = { ui }, confirmed: Boolean = false): PrimeOutcome = agent.run(
        text, confirmed, uiProvider, runner, onProgress = {})

    @Test fun repeatedAppSwitchesAlwaysExecuteLocallyWithoutCallingTheModel() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            val opened = mutableListOf<String>()
            val runner: suspend (String, JSONObject) -> PrimeActionResult = { command, params ->
                assertEquals("open_app", command)
                opened += params.getString("name")
                PrimeActionResult(true, "باز شد")
            }
            listOf("برو داخل تلگرام", "برو گوگل", "برو روبیکا", "حالا برو تلگرام", "اپ یادداشت من رو باز کن")
                .forEach { assertEquals("باز شد", turn(agent, it, runner).text) }
            assertEquals(listOf("Telegram", "Google", "Rubika", "Telegram", "یادداشت من"), opened)
            assertEquals(0, api.modelRequests.get())
            assertTrue(api.requests.isEmpty())
        }
    }

    @Test fun oldRefusalsAndRestoredHistoryCannotDisableTheNextLaunch() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            agent.restoreConversation(listOf("user" to "برو گوگل", "assistant" to "من به گوشی دسترسی ندارم."))
            var ran = false
            val outcome = turn(agent, "برو روبیکا", { command, params ->
                ran = true; assertEquals("open_app", command); assertEquals("Rubika", params.getString("name"))
                PrimeActionResult(true, "روبیکا باز شد")
            })
            assertTrue(ran)
            assertEquals("روبیکا باز شد", outcome.text)
            assertTrue(api.requests.isEmpty())
        }
    }

    @Test fun aFailedAppOrThrownLocalErrorDoesNotBreakLaterCommands() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            val failed = turn(agent, "برو اپ ناموجود", { _, _ -> PrimeActionResult(false, "برنامه نصب نیست") })
            assertEquals("برنامه نصب نیست", failed.text)
            val thrown = turn(agent, "برو گوگل", { _, _ -> throw IllegalStateException("خطای اجرا") })
            assertEquals("خطای اجرا", thrown.text)
            val next = turn(agent, "برو تلگرام", { _, _ -> PrimeActionResult(true, "تلگرام باز شد") })
            assertEquals("تلگرام باز شد", next.text)
            assertTrue(api.requests.isEmpty())
        }
    }

    @Test fun restoringALongChatKeepsPhoneContextAfterTextHistoryIsPruned() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            val history = mutableListOf("user" to "برو تلگرام", "assistant" to "تلگرام باز شد")
            repeat(15) { history += "user" to "ممنون $it"; history += "assistant" to "خواهش می‌کنم" }
            agent.restoreConversation(history)
            api.answers += """{"type":"action","command":"set_text","params":{"text":"سلام"}}"""
            api.answers += """{"type":"reply","text":"سلام را در کادر نوشتم"}"""
            var typed = false
            val outcome = turn(agent, "بنویس سلام", { command, params ->
                assertEquals("set_text", command); assertEquals("سلام", params.getString("text"))
                typed = true; PrimeActionResult(true, "OK")
            })
            assertTrue(typed)
            assertEquals("سلام را در کادر نوشتم", outcome.text)
        }
    }

    @Test fun localNavigationDoesNotDependOnChatgptQuotaOrSignIn() = runBlocking {
        val offline = object : PrimeCredentials {
            override fun isSignedIn() = false
            override suspend fun accessToken(): String = error("No token should be requested")
        }
        val agent = PrimeAgent(offline)
        val commands = mutableListOf<String>()
        val runner: suspend (String, JSONObject) -> PrimeActionResult = { command, params ->
            commands += "$command:${params.optString("key", params.optString("name"))}"
            PrimeActionResult(true, "اجرا شد")
        }
        turn(agent, "برو روبیکا", runner)
        turn(agent, "حالا برگرد", runner)
        turn(agent, "برو صفحه اصلی", runner)
        assertEquals(listOf("open_app:Rubika", "press_key:back", "press_key:home"), commands)
    }

    @Test fun compoundTasksUseThePhonePlannerAndRefreshTheScreenAfterEachAction() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            api.answers += """{"type":"action","command":"open_app","params":{"name":"روبیکا"}}"""
            api.answers += """{"type":"action","command":"click_element","params":{"text":"علی"}}"""
            api.answers += """{"type":"reply","text":"چت علی باز شد"}"""
            val commands = mutableListOf<String>()
            var screenReads = 0
            var currentPackage = "test.home"
            val outcome = turn(agent, "برو روبیکا و چت علی رو باز کن", { command, _ ->
                commands += command
                if (command == "open_app") currentPackage = "test.rubika"
                PrimeActionResult(true, "OK")
            }, { screenReads++; ui.replace("test.telegram", currentPackage) })
            assertEquals(listOf("open_app", "click_element"), commands)
            assertEquals(3, screenReads)
            assertEquals("چت علی باز شد", outcome.text)
            assertTrue(api.requests.all { it.getString("instructions").contains("local action") })
            assertTrue(api.requests[0].getJSONArray("input").toString().contains("test.home"))
            assertTrue(api.requests[1].getJSONArray("input").toString().contains("test.rubika"))
        }
    }

    @Test fun correctsOneGenericCapabilityDenialWithoutReplayingOldDenialsAsAuthority() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            val oldDenial = "من به گوشی شما دسترسی ندارم."
            agent.restoreConversation(listOf("user" to "برو گوگل", "assistant" to oldDenial))
            api.answers += JSONObject().put("type", "reply").put("text", oldDenial).toString()
            api.answers += """{"type":"action","command":"click_element","params":{"text":"علی"}}"""
            api.answers += """{"type":"reply","text":"چت علی باز شد"}"""
            var actions = 0
            val outcome = turn(agent, "روی علی بزن", { _, _ -> actions++; PrimeActionResult(true, "OK") })
            assertEquals(1, actions)
            assertEquals("چت علی باز شد", outcome.text)
            assertFalse(api.requests.first().getJSONArray("input").toString().contains(oldDenial))
            assertTrue(api.requests[1].getJSONArray("input").toString().contains("Runtime correction"))
        }
    }

    @Test fun boundedCorrectionDoesNotLoopOrDisableTheNextSimpleAppCommand() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            repeat(2) { api.answers += """{"type":"reply","text":"I cannot control your phone."}""" }
            var actions = 0
            val outcome = turn(agent, "برو روبیکا و چت علی رو باز کن", { _, _ -> actions++; PrimeActionResult(true, "OK") })
            assertEquals(2, api.requests.size)
            assertEquals(0, actions)
            assertFalse(outcome.text.contains("نمی‌توانم"))
            turn(agent, "برو گوگل", { _, _ -> actions++; PrimeActionResult(true, "گوگل باز شد") })
            assertEquals(1, actions)
            assertEquals(2, api.requests.size)
        }
    }

    @Test fun reportsActualAccessibilityDisconnectWithoutAskingTheModelToGuess() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            val outcome = turn(agent, "برو روبیکا و چت علی رو باز کن", { _, _ -> error("Must not act") },
                { "ACCESSIBILITY_OFF: disconnected" })
            assertTrue(outcome.text.contains("دسترسی"))
            assertTrue(api.requests.isEmpty())
        }
    }

    @Test fun anObservedActionFailureIsReportedPreciselyAndTheSessionRemainsUsable() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            api.answers += """{"type":"action","command":"click_element","params":{"text":"علی"}}"""
            api.answers += """{"type":"reply","text":"I cannot control your phone."}"""
            val outcome = turn(agent, "در تلگرام روی علی بزن", { _, _ -> PrimeActionResult(false, "Element not found") })
            assertTrue(outcome.text.contains("Element not found"))
            assertEquals(2, api.requests.size)
            assertEquals("گوگل باز شد", turn(agent, "برو گوگل", { _, _ -> PrimeActionResult(true, "گوگل باز شد") }).text)
        }
    }

    @Test fun protectedStepsAndConfirmationRequestsArePreserved() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            api.answers += """{"type":"reply","text":"برای ورود به تلگرام رمز لازم است؛ آن را خودت وارد کن."}"""
            val handover = turn(agent, "برو تلگرام و وارد شو", { _, _ -> error("Must not act") })
            assertTrue(handover.text.contains("رمز"))
            api.answers += """{"type":"confirmation","text":"پیام را ارسال کنم؟"}"""
            val confirmation = turn(agent, "در تلگرام پیام را بفرست", { _, _ -> error("Must not send") })
            assertTrue(confirmation.needsConfirmation)
            assertEquals(2, api.requests.size)
        }
    }

    @Test fun cancellationNeverBecomesASuccessfulLaunch() = runBlocking {
        val credentials = object : PrimeCredentials {
            override fun isSignedIn() = false
            override suspend fun accessToken() = ""
        }
        try {
            turn(PrimeAgent(credentials), "برو گوگل", { _, _ -> throw CancellationException("closed") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
    @Test fun providerBoundarySupportsNonOpenAiImplementations() = runBlocking {
        val requests = mutableListOf<AiTextRequest>()
        var resetCount = 0

        val provider = object : AiProvider {
            override val providerId = "fake-provider"
            override fun isAvailable() = true
            override suspend fun listModels(forceRefresh: Boolean) =
                listOf(AiModel("fake_luna", "Fake Luna"))

            override suspend fun streamText(
                request: AiTextRequest,
                onTextDelta: ((String) -> Unit)?
            ): String {
                requests += request
                onTextDelta?.invoke("سلام")
                return "سلام از provider آزمایشی"
            }

            override fun reset() {
                resetCount += 1
            }
        }

        val deltas = mutableListOf<String>()
        val agent = PrimeAgent(provider)
        val outcome = agent.run(
            userText = "یک پاسخ کوتاه بده",
            confirmedForTask = false,
            uiProvider = { error("Normal chat must not read the phone UI") },
            actionRunner = { _, _ -> error("Normal chat must not run phone actions") },
            onProgress = {},
            onTextDelta = { deltas += it }
        )

        assertEquals("سلام از provider آزمایشی", outcome.text)
        assertEquals(listOf("سلام"), deltas)
        assertEquals(1, requests.size)
        assertEquals("fake_luna", requests.single().model)
        assertEquals("user", requests.single().messages.last().role)
        assertEquals("یک پاسخ کوتاه بده", requests.single().messages.last().content)

        agent.resetSession()
        assertEquals(1, resetCount)
    }


    @Test fun runtimeConfirmationBlocksSensitiveToolBeforeAccessibilityRunner() = runBlocking {
        ApiFixture().use { api ->
            val agent = api.agent()
            api.answers += """{"type":"action","command":"click_element","params":{"text":"ارسال"},"risk":"write"}"""

            var actions = 0
            val first = turn(
                agent,
                "پیام را ارسال کن",
                { _, _ ->
                    actions += 1
                    PrimeActionResult(true, "sent")
                }
            )

            assertTrue(first.needsConfirmation)
            assertEquals(0, actions)
            assertTrue(first.text.contains("حساس") || first.text.contains("تأیید"))

            api.answers += """{"type":"action","command":"click_element","params":{"text":"ارسال"},"risk":"sensitive"}"""
            api.answers += """{"type":"reply","text":"پیام ارسال شد"}"""

            val confirmed = turn(
                agent,
                "پیام را ارسال کن",
                { command, params ->
                    actions += 1
                    assertEquals("click_element", command)
                    assertEquals("ارسال", params.getString("text"))
                    PrimeActionResult(true, "sent")
                },
                confirmed = true
            )

            assertEquals(1, actions)
            assertEquals("پیام ارسال شد", confirmed.text)
        }
    }


}
