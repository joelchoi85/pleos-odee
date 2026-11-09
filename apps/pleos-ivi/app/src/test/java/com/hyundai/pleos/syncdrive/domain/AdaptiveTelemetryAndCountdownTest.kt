package com.hyundai.pleos.syncdrive.domain

import com.hyundai.pleos.syncdrive.network.GroupSyncWebSocketClient
import com.hyundai.pleos.syncdrive.sdk.VoiceCommandParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveTelemetryAndCountdownTest {

    @Test
    fun `adaptive telemetry interval clamps within 1000ms to 10000ms and updates via TELEMETRY_ACK`() = runTest {
        assertEquals(1_000L, clampTelemetryIntervalMs(250L))
        assertEquals(2_000L, clampTelemetryIntervalMs(2_000L))
        assertEquals(10_000L, clampTelemetryIntervalMs(25_000L))

        assertEquals(10_000L, deriveDefaultTelemetryIntervalMs(DrivingSafetyLevel.STATIONARY))
        assertEquals(5_000L, deriveDefaultTelemetryIntervalMs(DrivingSafetyLevel.NORMAL_DRIVING))
        assertEquals(2_000L, deriveDefaultTelemetryIntervalMs(DrivingSafetyLevel.HIGH_FOCUS))
        assertEquals(1_000L, deriveDefaultTelemetryIntervalMs(DrivingSafetyLevel.NORMAL_DRIVING, isOffRoute = true))

        val client = GroupSyncWebSocketClient(
            scope = backgroundScope,
            telemetryProvider = { LocalVehicleSnapshot() }
        )

        client.handleRawServerMessage(
            """
            {
              "type": "TELEMETRY_ACK",
              "vehicleId": "veh-ioniq6-host",
              "nextIntervalMs": 1000,
              "crossTrackDistanceMeters": 94.5,
              "safetyLevel": "HIGH_FOCUS"
            }
            """.trimIndent()
        )
        assertEquals(1_000L, client.telemetryIntervalMs.value)

        // Out-of-bounds server interval (e.g. 30000ms) is safely clamped to 10000ms
        client.handleRawServerMessage(
            """
            {
              "type": "TELEMETRY_ACK",
              "vehicleId": "veh-ioniq6-host",
              "nextIntervalMs": 30000,
              "crossTrackDistanceMeters": 2.0,
              "safetyLevel": "STATIONARY"
            }
            """.trimIndent()
        )
        assertEquals(10_000L, client.telemetryIntervalMs.value)
    }

    @Test
    fun `8-second countdown coordinator falls back to KEEP_ROUTE safe default on timeout`() = runTest {
        val coordinator = AlertCountdownCoordinator(this)
        var resolvedDecision: VoiceDecision? = null

        coordinator.startCountdown(
            eventId = "evt-timeout-test",
            timeoutSeconds = 8
        ) { safeDefault ->
            resolvedDecision = safeDefault
        }

        assertTrue(coordinator.state.value.isRunning)
        assertEquals(8, coordinator.state.value.remainingSeconds)

        advanceTimeBy(5_000L)
        runCurrent()
        assertNull(resolvedDecision)
        assertEquals(3, coordinator.state.value.remainingSeconds)

        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(VoiceDecision.KEEP_ROUTE, resolvedDecision)
        assertFalse(coordinator.state.value.isRunning)
    }

    @Test
    fun `cancelling countdown early prevents timeout KEEP_ROUTE callback`() = runTest {
        val coordinator = AlertCountdownCoordinator(this)
        var timeoutTriggered = false

        coordinator.startCountdown(
            eventId = "evt-early-accept",
            timeoutSeconds = 8
        ) {
            timeoutTriggered = true
        }

        advanceTimeBy(3_000L)
        runCurrent()
        coordinator.cancel()

        advanceTimeBy(10_000L)
        runCurrent()
        assertFalse(timeoutTriggered)
    }

    @Test
    fun `TELEMETRY_UPDATE wire JSON omits null optional fields and GROUP_ALERT decodes accurately`() {
        val snapshot = LocalVehicleSnapshot(
            socPercent = 79.0,
            fuelPercent = null
        )
        val encoded = SyncProtocolJson.encodeTelemetryUpdate(
            snapshot.toTelemetryPayload(groupId = "grp-01", timestampMs = 1730000000000L)
        )
        assertTrue(encoded.contains("\"type\":\"TELEMETRY_UPDATE\""))
        assertTrue(encoded.contains("\"socPercent\":79.0"))
        assertFalse("Null optional fields must be omitted for Zod compatibility", encoded.contains("fuelPercent"))

        val decodedAlert = SyncProtocolJson.decodeServerMessage(
            """
            {
              "type": "GROUP_ALERT",
              "alert": {
                "eventId": "evt-99",
                "groupId": "grp-01",
                "createdAtMs": 1730000001000,
                "type": "NAVIGATOR_ROUTE_PROPOSAL",
                "sourceMemberId": "nav-04",
                "sourceDisplayName": "3호차 동승석",
                "etaDeltaMinutes": -8,
                "recommendedWaypoint": {
                  "name": "둔내IC 우회로",
                  "lat": 37.51,
                  "lng": 128.21,
                  "poiCategory": "ETC"
                },
                "ambientColor": "CYAN_PROPOSAL",
                "aiBriefing": {
                  "headlineKo": "정체 우회 제안 · 8분 단축",
                  "detailKo": "국도 우회 시 8분 단축",
                  "ttsScriptKo": "8분 단축 우회 경로를 반영할까요?"
                },
                "voiceSession": {
                  "roomName": "room-grp-01",
                  "livekitUrl": "ws://10.0.2.2:7880",
                  "participantTokens": {"veh-ioniq6-host": "tok-1"},
                  "autoConnectOnAccept": true
                },
                "timeoutSeconds": 8
              }
            }
            """.trimIndent()
        ) as? ServerWsEvent.GroupAlert

        assertNotNull(decodedAlert)
        val uiModel = decodedAlert!!.alert.toUiModel()
        assertTrue(uiModel.isTimeSaving)
        assertEquals("8분 단축", uiModel.formattedEtaBadgeKo)
        assertEquals(AmbientAlertColor.CYAN_PROPOSAL, uiModel.ambientColor)
    }

    @Test
    fun `VoiceCommandParser recognizes Korean and English hands-free utterances`() {
        assertEquals(VoiceDecision.ACCEPT_AND_CALL, VoiceCommandParser.parse("수락하고 보이스 연결해줘"))
        assertEquals(VoiceDecision.ACCEPT_ROUTE_ONLY, VoiceCommandParser.parse("통화 없이 경로만 반영"))
        assertEquals(VoiceDecision.KEEP_ROUTE, VoiceCommandParser.parse("기존 경로 유지할게"))
    }

    @org.junit.Test
    fun voiceInvite_decodesAndBecomesLocalCallAlert() {
        val event = SyncProtocolJson.decodeServerMessage(
            """{"type":"VOICE_INVITE","fromMemberId":"car-02","fromDisplayName":"2호차","toMemberId":"car-01","roomName":"ROOM-AB12-car-01-car-02"}"""
        ) as ServerWsEvent.VoiceInvite
        val alert = event.toIncomingCallAlert("grp-01").toUiModel()
        org.junit.Assert.assertTrue(alert.isCallInvite)
        org.junit.Assert.assertEquals("ROOM-AB12-car-01-car-02", alert.voiceSession.roomName)
        org.junit.Assert.assertEquals("일행 1:1 통화", alert.categoryBadgeKo)
        org.junit.Assert.assertFalse(event.isDecline)
        val decline = SyncProtocolJson.decodeServerMessage(
            """{"type":"VOICE_DECLINE","fromMemberId":"car-01","fromDisplayName":"1호차","toMemberId":"car-02","roomName":"ROOM-AB12-car-01-car-02"}"""
        ) as ServerWsEvent.VoiceInvite
        org.junit.Assert.assertTrue(decline.isDecline)
    }
}
