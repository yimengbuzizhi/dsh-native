package xin.dshaa.nativeapp.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import xin.dshaa.core.tools.AskAnswer

/** Pure fixtures: no Android networking, desktop process or real credentials. */
class RemoteTransportTest {
    private class Preferences : RemoteProfilePreferences {
        val values = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()
        var fail = false
        var commits = 0
        override fun string(key: String) = values[key]
        override fun long(key: String) = longs[key] ?: 0L
        override fun commit(values: Map<String, String>, removed: Set<String>): Boolean {
            commits++
            if (fail) return false
            this.values.putAll(values)
            removed.forEach { this.values.remove(it); longs.remove(it) }
            return true
        }
    }
    private fun newStore(prefs: Preferences): RemoteProfileStore {
        var next = 0
        return RemoteProfileStore(prefs) { "profile-${++next}" }
    }

    @Test fun legacyCredentialsMigrateOnceAtomicallyAndRemainOutOfMetadata() {
        val prefs = Preferences().apply {
            values[RemoteProfileStore.LEGACY_URL] = "host:8137/"
            values[RemoteProfileStore.LEGACY_TOKEN] = "fixture-token"
            longs[RemoteProfileStore.LEGACY_LAST_OK] = 123
        }
        val first = newStore(prefs)
        assertEquals("profile-1", first.activeId)
        assertEquals("http://host:8137", first.profiles().single().baseUrl)
        assertEquals(123L, first.profiles().single().lastConnectedAt)
        assertEquals("fixture-token", first.token(first.activeId))
        assertFalse(prefs.values[RemoteProfileStore.METADATA]!!.contains("fixture-token"))
        assertFalse(prefs.values.containsKey(RemoteProfileStore.LEGACY_TOKEN))
        assertFalse(prefs.values.containsKey(RemoteProfileStore.LEGACY_URL))
        assertEquals(1, prefs.commits)
        val reopened = newStore(prefs)
        assertEquals(first.profiles(), reopened.profiles())
        assertEquals("fixture-token", reopened.token(reopened.activeId))
        assertEquals(1, prefs.commits)
    }

    @Test fun failedMigrationKeepsLegacyKeysAndCanBeRetried() {
        val prefs = Preferences().apply { values[RemoteProfileStore.LEGACY_TOKEN] = "fixture"; fail = true }
        assertThrows(IllegalStateException::class.java) { newStore(prefs) }
        assertEquals("fixture", prefs.values[RemoteProfileStore.LEGACY_TOKEN])
        assertNull(prefs.values[RemoteProfileStore.METADATA])
        prefs.fail = false
        assertEquals("fixture", newStore(prefs).token("profile-1"))
    }

    @Test fun profilesKeepSeparateTokensAndAddressEditsNeverReuseOldToken() {
        val prefs = Preferences()
        val store = newStore(prefs)
        val a = store.save(store.activeId, "A", "http://a:1", "token-a")
        val b = store.save(null, "B", "http://b:2", "token-b")
        store.select(b)
        assertEquals("token-a", store.token(a))
        assertEquals("token-b", store.token(b))
        store.save(b, "renamed", "http://b:2/", null)
        assertEquals("token-b", store.token(b))
        store.save(b, "new endpoint", "http://c:3", null)
        assertEquals("", store.token(b))
        assertEquals("token-a", store.token(a))
        assertFalse(store.profiles().first { it.id == b }.hasToken)
        assertTrue(store.remove(a))
        assertNull(prefs.values[RemoteProfileStore.tokenKey(a)])
        assertEquals("", store.token(b))
        val reopened = newStore(prefs)
        assertEquals(b, reopened.activeId)
        assertEquals(listOf(b), reopened.profiles().map { it.id })
    }

    @Test fun failedSaveDoesNotReplaceMetadataOrCredentials() {
        val prefs = Preferences()
        val store = newStore(prefs)
        val id = store.save(store.activeId, "A", "a:1", "fixture")
        val before = prefs.values.toMap()
        prefs.fail = true
        assertThrows(IllegalStateException::class.java) { store.save(id, "B", "b:2", "other") }
        assertEquals(before, prefs.values)
        assertEquals("http://a:1", store.profiles().single().baseUrl)
        assertEquals("fixture", store.token(id))
    }

    @Test fun malformedStoredProfilesDoNotReimportLegacyTokens() {
        val prefs = Preferences().apply {
            values[RemoteProfileStore.METADATA] = "broken"
            values[RemoteProfileStore.LEGACY_TOKEN] = "unrelated"
        }
        assertThrows(Exception::class.java) { newStore(prefs) }
        assertEquals(0, prefs.commits)
        assertEquals("unrelated", prefs.values[RemoteProfileStore.LEGACY_TOKEN])
    }

    @Test fun addressesRejectInlineCredentialsQueriesAndUnsupportedSchemes() {
        val store = newStore(Preferences())
        for (url in listOf("http://user:password@host", "http://host?token=x", "http://host#fragment", "ftp://host")) {
            assertThrows(IllegalArgumentException::class.java) { store.save(null, "bad", url, "x") }
        }
    }

    @Test fun targetSwitchRejectsLateHttpWsAndSameSessionIdFromOtherProfile() {
        val gate = RemoteGenerationGate()
        val http = gate.key("A", null)
        val session = gate.key("A", "same", sessionScoped = true)
        val ws = session.copy(follow = gate.follow)
        assertTrue(gate.accepts(ws, "A", "same"))
        gate.nextTarget()
        assertFalse(gate.accepts(http, "B", null))
        assertFalse(gate.accepts(session, "B", "same"))
        assertFalse(gate.accepts(ws, "B", "same"))
        gate.nextTarget()
        assertFalse(gate.accepts(http, "A", null))
        assertFalse(gate.accepts(ws, "A", "same"))
    }

    @Test fun reconnectRejectsOldWsButRetainsHttpAndSessionSwitchRejectsAtoBtoA() {
        val gate = RemoteGenerationGate()
        val session = gate.key("A", "s", sessionScoped = true)
        val ws = session.copy(follow = gate.follow)
        val creationFromEmpty = gate.key("A", null, sessionScoped = true)
        gate.nextFollow()
        assertTrue(gate.accepts(session, "A", "s"))
        assertFalse(gate.accepts(ws, "A", "s"))
        gate.nextSession()
        assertFalse(gate.accepts(creationFromEmpty, "A", null))
        gate.nextSession()
        assertFalse(gate.accepts(session, "A", "s"))
        assertTrue(gate.accepts(gate.key("A", null), "A", null))
    }

    @Test fun allQuestionsMultiSelectAndCustomAnswerUseExistingWireEnvelope() {
        val questions = RemoteProtocol.parseQuestions("call", "ask_user_question", """{"questions":[
          {"id":"one","header":"First","question":"Choose","options":[{"label":"A","description":"a"}]},
          {"id":"two","question":"More","multiSelect":true,"detail":"detail","options":[{"label":"B"},{"label":"C"}]}
        ]}""")
        assertEquals(listOf("one", "two"), questions.map { it.id })
        assertTrue(questions[1].multiSelect)
        assertEquals("detail", questions[1].detail)
        assertEquals(2, questions[1].options.size)
        val args = Json.parseToJsonElement(RemoteProtocol.answerArgs("session", "call", listOf(
            AskAnswer("one", emptyList()), AskAnswer("two", listOf("B", "C"), "custom"),
        ))).jsonObject
        assertEquals(setOf("agentId", "callId", "answer"), args.keys)
        assertEquals("session", args["agentId"]!!.jsonPrimitive.content)
        val answers = args["answer"]!!.jsonObject["answers"]!!.jsonArray
        assertTrue(answers[0].jsonObject["selected"]!!.jsonArray.isEmpty())
        assertEquals(listOf("B", "C"), answers[1].jsonObject["selected"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("custom", answers[1].jsonObject["custom"]!!.jsonPrimitive.content)
        assertTrue(RemoteProtocol.parseQuestions("call", "other", "{}").isEmpty())
    }

    @Test fun sessionsAndPromptFixturesDoNotInventDesktopFields() {
        val sessions = RemoteProtocol.parseSessions("""{"items":[
          {"sessionId":"old","updatedAt":1,"cwd":"C:/remote","projections":{"values":{"title":"Remote","modelSelection":{"lastUsed":{"model":"m"}},"sessionListMetadata":{"lastPromptAt":9}}}},
          {"sessionId":"new","updatedAt":5,"running":true},
          {"sessionId":"blank","blank":true}
        ]}""")
        assertEquals(listOf("new", "old"), sessions.map { it.id })
        assertEquals("m", sessions[1].model)
        assertEquals(9L, sessions[1].lastMessageAt)
        val request = Json.parseToJsonElement(RemoteProtocol.promptArgs("s", "r", "line\n\"text\"", "Asia/Shanghai")).jsonObject["request"]!!.jsonObject
        assertEquals(setOf("sessionId", "requestId", "mode", "clientTimeZone", "content"), request.keys)
        assertEquals("queue", request["mode"]!!.jsonPrimitive.content)
        assertEquals("line\n\"text\"", request["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) { RemoteProtocol.parseSessions("{}") }
    }
}
