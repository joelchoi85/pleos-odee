package com.hyundai.pleos.syncdrive.sdk

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class GleoVoiceCallControlTest {

    @Test
    fun parseCallActionDirectEnum() {
        assertEquals(GleoIntentEventBus.VoiceCallAction.END_CALL, GleoIntentEventBus.parseCallAction("END_CALL"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE, GleoIntentEventBus.parseCallAction("TOGGLE_MUTE"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.MUTE, GleoIntentEventBus.parseCallAction("MUTE"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.UNMUTE, GleoIntentEventBus.parseCallAction("UNMUTE"))
    }

    @Test
    fun parseCallActionCaseInsensitiveAndWhitespace() {
        assertEquals(GleoIntentEventBus.VoiceCallAction.END_CALL, GleoIntentEventBus.parseCallAction("  end_call  "))
        assertEquals(GleoIntentEventBus.VoiceCallAction.MUTE, GleoIntentEventBus.parseCallAction("mute"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.UNMUTE, GleoIntentEventBus.parseCallAction("unmute"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE, GleoIntentEventBus.parseCallAction("toggle_mute"))
    }

    @Test
    fun parseCallActionNaturalKoreanPhrases() {
        assertEquals(GleoIntentEventBus.VoiceCallAction.END_CALL, GleoIntentEventBus.parseCallAction("통화 끊어줘"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.END_CALL, GleoIntentEventBus.parseCallAction("통화 종료"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.MUTE, GleoIntentEventBus.parseCallAction("마이크 꺼줘"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.MUTE, GleoIntentEventBus.parseCallAction("음소거해줘"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.UNMUTE, GleoIntentEventBus.parseCallAction("마이크 켜줘"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.UNMUTE, GleoIntentEventBus.parseCallAction("음소거 해제"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE, GleoIntentEventBus.parseCallAction("마이크 토글"))
        assertEquals(GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE, GleoIntentEventBus.parseCallAction("toggle mute"))

        assertNull(GleoIntentEventBus.parseCallAction(null))
        assertNull(GleoIntentEventBus.parseCallAction(""))
        assertNull(GleoIntentEventBus.parseCallAction("오늘 날씨 어때?"))
    }

    @Test
    fun emitAndCollectCallCommands() = runTest {
        val emitted = mutableListOf<GleoIntentEventBus.VoiceCallAction>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            GleoIntentEventBus.callCommands.collect {
                emitted.add(it)
            }
        }

        GleoIntentEventBus.emitCallAction(GleoIntentEventBus.VoiceCallAction.MUTE)
        GleoIntentEventBus.emitCallAction(GleoIntentEventBus.VoiceCallAction.UNMUTE)
        GleoIntentEventBus.emitCallAction(GleoIntentEventBus.VoiceCallAction.END_CALL)

        assertEquals(
            listOf(
                GleoIntentEventBus.VoiceCallAction.MUTE,
                GleoIntentEventBus.VoiceCallAction.UNMUTE,
                GleoIntentEventBus.VoiceCallAction.END_CALL
            ),
            emitted
        )
        job.cancel()
    }

    @Test
    fun schemaJsonContainsControlVoiceCall() {
        val schemaFile = File("src/main/assets/schema.json")
        assertTrue("schema.json must exist", schemaFile.exists())
        val jsonElement = Json.parseToJsonElement(schemaFile.readText())
        val actionCalls = jsonElement.jsonObject["actionCalls"]?.jsonArray
        assertNotNull("actionCalls must exist", actionCalls)

        var foundControlCall = false
        for (item in actionCalls!!) {
            val func = item.jsonObject["function"]?.jsonObject ?: continue
            if (func["name"]?.jsonPrimitive?.content == "CONTROL_VOICE_CALL") {
                foundControlCall = true
                assertEquals("service", func["component"]?.jsonPrimitive?.content)
                val params = func["parameters"]?.jsonObject
                assertNotNull(params)
                val props = params?.get("properties")?.jsonObject
                assertNotNull(props)
                assertTrue(props!!.containsKey("action"))
                val required = params?.get("required")?.jsonArray
                assertNotNull(required)
                assertEquals("action", required!![0].jsonPrimitive.content)
            }
        }
        assertTrue("CONTROL_VOICE_CALL must be defined in schema.json", foundControlCall)
    }
}
