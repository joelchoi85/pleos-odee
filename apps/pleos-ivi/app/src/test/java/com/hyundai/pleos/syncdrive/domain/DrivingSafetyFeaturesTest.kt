package com.hyundai.pleos.syncdrive.domain

import com.hyundai.pleos.syncdrive.audio.ActiveVoiceCallState
import com.hyundai.pleos.syncdrive.audio.AudioFocusAdapter
import com.hyundai.pleos.syncdrive.audio.AudioDspDiagnostics
import com.hyundai.pleos.syncdrive.audio.InCarVoiceSessionManager
import com.hyundai.pleos.syncdrive.audio.VoiceRoomState
import com.hyundai.pleos.syncdrive.audio.WebRtcRoomConnector
import com.hyundai.pleos.syncdrive.sdk.SimulatorBridgeGatewayImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingSafetyFeaturesTest {

    // =========================================================================
    // 기능 1 단위 테스트: 급제동 감지 (checkIsHardBraking)
    // =========================================================================

    @Test
    fun `cruising without braking returns false`() {
        assertFalse(
            checkIsHardBraking(
                isBraking = false,
                currentSpeedKmh = 45.0,
                gear = GearState.D
            )
        )
        assertFalse(
            checkIsHardBraking(
                isBraking = false,
                currentSpeedKmh = 90.0,
                gear = GearState.D
            )
        )
    }

    @Test
    fun `braking at 30kmh or above triggers hard braking mute`() {
        // 도심 주행 중 급제동 (45 km/h)
        assertTrue(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 45.0,
                previousSpeedKmh = 45.0,
                gear = GearState.D
            )
        )
        // 고속도로 주행 중 급제동 (90 km/h)
        assertTrue(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 90.0,
                previousSpeedKmh = 90.0,
                gear = GearState.D
            )
        )
        // 30.0 km/h 경계값 제동
        assertTrue(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 30.0,
                previousSpeedKmh = 30.0,
                gear = GearState.D
            )
        )
    }

    @Test
    fun `normal low speed braking does not trigger hard braking mute`() {
        assertFalse(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 12.0,
                previousSpeedKmh = 12.0,
                gear = GearState.D
            )
        )
    }

    @Test
    fun `rapid deceleration with brake triggers hard braking mute`() {
        // 직전 50km/h에서 20km/h로 급감속 (speedDrop = 30 >= 15)
        assertTrue(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 20.0,
                previousSpeedKmh = 50.0,
                gear = GearState.D
            )
        )
    }

    @Test
    fun `stationary red light brake at 0kmh does not trigger hard braking mute`() {
        // 신호 대기 중 브레이크를 밟고 정차해 있는 것은 정상적인 주행 상황이므로 뮤트하지 않음
        assertFalse(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 0.0,
                previousSpeedKmh = 0.0,
                gear = GearState.D
            )
        )
        assertFalse(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 0.8,
                previousSpeedKmh = 1.0,
                gear = GearState.D
            )
        )
    }

    @Test
    fun `parked in gear P does not trigger hard braking mute`() {
        assertFalse(
            checkIsHardBraking(
                isBraking = true,
                currentSpeedKmh = 0.0,
                gear = GearState.P
            )
        )
    }

    @Test
    fun `releasing brake restores unmuted state`() {
        // 급제동 후 브레이크 페달을 떼면 음소거가 즉시 해제되어야 함
        assertFalse(
            checkIsHardBraking(
                isBraking = false,
                currentSpeedKmh = 40.0,
                previousSpeedKmh = 45.0,
                gear = GearState.D
            )
        )
    }

    // =========================================================================
    // 기능 1 단위 테스트: 통화 중 급제동 안전 오디오 음소거 연동
    // =========================================================================

    private class MockAudioFocus : AudioFocusAdapter {
        private val _focus = MutableStateFlow(true)
        override val hasDuckingFocus: StateFlow<Boolean> = _focus.asStateFlow()
        override fun requestMayDuckVoiceFocus(onFocusLost: () -> Unit): Boolean = true
        override fun abandonVoiceFocus() = Unit
        override fun attachVoiceDspEffects(audioSessionId: Int): AudioDspDiagnostics =
            AudioDspDiagnostics(audioSessionId = audioSessionId, aecEnabled = true, noiseSuppressorEnabled = true)
        override fun releaseVoiceDspEffects() = Unit
    }

    @Test
    fun `hard braking ducks incoming voice and keeps my microphone untouched`() = runTest {
        var micEnabled = false
        var remoteVolume = 1.0
        val roomConnector = object : WebRtcRoomConnector {
            override suspend fun connectRoom(roomName: String, livekitUrl: String, token: String?) { micEnabled = true }
            override suspend fun disconnectRoom() { micEnabled = false }
            override fun setMicrophoneEnabled(enabled: Boolean) { micEnabled = enabled }
            override fun setRemoteAudioVolume(volume: Double) { remoteVolume = volume }
        }
        val manager = InCarVoiceSessionManager(MockAudioFocus(), SimulatorBridgeGatewayImpl(), roomConnector)

        // 통화 전 급제동 → 연결되자마자 수신 볼륨 감쇄
        manager.setHardBrakingMute(true)
        manager.startVoiceSession(
            VoiceSessionPayload(roomName = "ROOM-TEST", livekitUrl = "ws://localhost:7880", participantTokens = mapOf("host" to "t")),
            participantId = "host"
        )
        assertEquals(VoiceRoomState.CONNECTED_DUCKED, manager.state.value.roomState)
        assertEquals(InCarVoiceSessionManager.HARD_BRAKING_REMOTE_VOLUME, remoteVolume, 0.0)
        assertTrue(micEnabled)

        manager.setHardBrakingMute(false)
        assertEquals(1.0, remoteVolume, 0.0)

        // 수동 음소거는 급제동과 독립
        manager.toggleMicrophoneMute()
        manager.setHardBrakingMute(true)
        assertEquals(InCarVoiceSessionManager.HARD_BRAKING_REMOTE_VOLUME, remoteVolume, 0.0)
        manager.setHardBrakingMute(false)
        assertTrue(manager.state.value.isMicMuted)
        assertFalse(micEnabled)
    }

    // =========================================================================
    // 기능 2 단위 테스트: 정차 중 스마트 대열 1줄 브리핑 (generateStationaryBriefing)
    // =========================================================================

    @Test
    fun `all companions normal generates all normal briefing with distance`() {
        val local = LocalVehicleSnapshot(
            vehicleId = "host-01",
            displayName = "아이오닉 6 (1호차 · 호스트)",
            speedKmh = 0.0,
            gear = GearState.D
        )
        val companions = listOf(
            CompanionVehicleUiModel(
                memberId = "host-01",
                displayName = "아이오닉 6 (1호차 · 호스트)",
                role = MemberRole.PLEOS_HOST,
                powertrain = PowertrainType.EV,
                energyPercent = 82,
                remainingRangeKm = 418,
                speedKmh = 0,
                gear = GearState.P,
                etaDisplay = "14:40 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = true
            ),
            CompanionVehicleUiModel(
                memberId = "comp-02",
                displayName = "EV9 (2호차 · 김민준)",
                role = MemberRole.PLEOS_COMPANION,
                powertrain = PowertrainType.EV,
                energyPercent = 75,
                remainingRangeKm = 310,
                speedKmh = 80,
                gear = GearState.D,
                etaDisplay = "14:41 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false
            ),
            CompanionVehicleUiModel(
                memberId = "comp-03",
                displayName = "싼타페 HEV (3호차 · 박서연)",
                role = MemberRole.MOBILE_DRIVER,
                powertrain = PowertrainType.HEV,
                energyPercent = 68,
                remainingRangeKm = 520,
                speedKmh = 82,
                gear = GearState.D,
                etaDisplay = "14:41 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false
            )
        )

        val briefing = generateStationaryBriefing(local, companions)
        assertEquals("🚦 신호 대기 중 · 일행 전원(3대) 정상 주행 중", briefing)
    }

    @Test
    fun `companion pitstop or low battery generates pitstop warning briefing`() {
        val local = LocalVehicleSnapshot(speedKmh = 0.0, gear = GearState.D)
        val companions = listOf(
            CompanionVehicleUiModel(
                memberId = "host-01",
                displayName = "아이오닉 6 (1호차 · 호스트)",
                role = MemberRole.PLEOS_HOST,
                powertrain = PowertrainType.EV,
                energyPercent = 82,
                remainingRangeKm = 418,
                speedKmh = 0,
                gear = GearState.P,
                etaDisplay = "14:40 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = true
            ),
            CompanionVehicleUiModel(
                memberId = "comp-02",
                displayName = "EV9 (2호차 · 김민준)",
                role = MemberRole.PLEOS_COMPANION,
                powertrain = PowertrainType.EV,
                energyPercent = 18, // 18% 배터리
                remainingRangeKm = 68,
                speedKmh = 70,
                gear = GearState.D,
                etaDisplay = "14:45 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false
            ),
            CompanionVehicleUiModel(
                memberId = "comp-03",
                displayName = "싼타페 HEV (3호차 · 박서연)",
                role = MemberRole.MOBILE_DRIVER,
                powertrain = PowertrainType.HEV,
                energyPercent = 68,
                remainingRangeKm = 520,
                speedKmh = 82,
                gear = GearState.D,
                etaDisplay = "14:41 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false
            )
        )

        val briefing = generateStationaryBriefing(local, companions)
        assertEquals("🚦 신호 대기 중 · 2호차 배터리 18% · 3호차 정상 주행", briefing)
    }

    @Test
    fun `companion off route generates deviation warning briefing`() {
        val local = LocalVehicleSnapshot(speedKmh = 0.0, gear = GearState.D)
        val companions = listOf(
            CompanionVehicleUiModel(
                memberId = "host-01",
                displayName = "아이오닉 6 (1호차 · 호스트)",
                role = MemberRole.PLEOS_HOST,
                powertrain = PowertrainType.EV,
                energyPercent = 82,
                remainingRangeKm = 418,
                speedKmh = 0,
                gear = GearState.P,
                etaDisplay = "14:40 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = true
            ),
            CompanionVehicleUiModel(
                memberId = "comp-02",
                displayName = "EV9 (2호차 · 김민준)",
                role = MemberRole.PLEOS_COMPANION,
                powertrain = PowertrainType.EV,
                energyPercent = 70,
                remainingRangeKm = 280,
                speedKmh = 60,
                gear = GearState.D,
                etaDisplay = "14:50 도착",
                isOffRoute = true,
                crossTrackDistanceMeters = 150,
                inVoiceCall = false,
                isSelfHost = false
            ),
            CompanionVehicleUiModel(
                memberId = "comp-03",
                displayName = "싼타페 HEV (3호차 · 박서연)",
                role = MemberRole.MOBILE_DRIVER,
                powertrain = PowertrainType.HEV,
                energyPercent = 68,
                remainingRangeKm = 520,
                speedKmh = 82,
                gear = GearState.D,
                etaDisplay = "14:41 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false
            )
        )

        val briefing = generateStationaryBriefing(local, companions)
        assertEquals("🚦 신호 대기 중 · 2호차 경로 이탈 (150m) · 3호차 정상 주행", briefing)
    }

    @Test
    fun `stationary briefing with real GPS coordinates calculates distance accurately`() {
        // 호스트: 37.3947, 127.1112 (판교)
        val local = LocalVehicleSnapshot(
            lat = 37.3947,
            lng = 127.1112,
            speedKmh = 0.0,
            gear = GearState.D
        )
        // 2호차: 37.3915, 127.1112 (~355m 남쪽)
        val companions = listOf(
            CompanionVehicleUiModel(
                memberId = "host-01",
                displayName = "아이오닉 6 (1호차 · 호스트)",
                role = MemberRole.PLEOS_HOST,
                powertrain = PowertrainType.EV,
                energyPercent = 82,
                remainingRangeKm = 418,
                speedKmh = 0,
                gear = GearState.P,
                etaDisplay = "14:40 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = true,
                lat = 37.3947,
                lng = 127.1112
            ),
            CompanionVehicleUiModel(
                memberId = "comp-02",
                displayName = "EV9 (2호차 · 김민준)",
                role = MemberRole.PLEOS_COMPANION,
                powertrain = PowertrainType.EV,
                energyPercent = 80,
                remainingRangeKm = 350,
                speedKmh = 60,
                gear = GearState.D,
                etaDisplay = "14:41 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = false,
                lat = 37.3915,
                lng = 127.1112
            )
        )

        val briefing = generateStationaryBriefing(local, companions)
        assertTrue(briefing.contains("🚦 신호 대기 중 · 일행 전원(2대) 정상 주행 중"))
        assertTrue(briefing.contains("2호차 360m 거리") || briefing.contains("2호차 350m 거리"))
    }

    @Test
    fun `solo driving returns safe single driver message`() {
        val local = LocalVehicleSnapshot(speedKmh = 0.0, gear = GearState.D)
        val companions = listOf(
            CompanionVehicleUiModel(
                memberId = "host-01",
                displayName = "아이오닉 6 (1호차 · 호스트)",
                role = MemberRole.PLEOS_HOST,
                powertrain = PowertrainType.EV,
                energyPercent = 82,
                remainingRangeKm = 418,
                speedKmh = 0,
                gear = GearState.P,
                etaDisplay = "14:40 도착",
                isOffRoute = false,
                crossTrackDistanceMeters = 0,
                inVoiceCall = false,
                isSelfHost = true
            )
        )

        val briefing = generateStationaryBriefing(local, companions)
        assertEquals("🚦 신호 대기 중 · 목적지까지 안전 운행하세요", briefing)
        assertEquals("🅿️ 정차 중 · 목적지까지 안전 운행하세요", generateStationaryBriefing(local.copy(gear = GearState.P), companions))
    }

    // =========================================================================
    // 기능 2 단위 테스트: 정차 및 주행 전환 시 브리핑 생성 및 제거 (Speed Transitions)
    // =========================================================================

    @Test
    fun `stationary briefing is generated at or below 1kmh and cleared above 5kmh`() {
        val local = LocalVehicleSnapshot(speedKmh = 0.0, gear = GearState.D)
        val companions = DemoGroupSeed.defaultCompanions(local)

        var currentBriefing: String? = null

        fun onSpeedChanged(speedKmh: Double) {
            if (speedKmh <= 1.0) {
                currentBriefing = generateStationaryBriefing(local.copy(speedKmh = speedKmh), companions)
            } else if (speedKmh > 5.0) {
                currentBriefing = null
            }
        }

        // 1) 0.0 km/h 정차 중 -> 브리핑 생성
        onSpeedChanged(0.0)
        assertNotNull(currentBriefing)
        assertTrue(currentBriefing!!.startsWith("🚦 신호 대기 중"))

        // 2) 0.8 km/h 미세 이동/신호 대기 유지 -> 브리핑 유지
        onSpeedChanged(0.8)
        assertNotNull(currentBriefing)

        // 3) 2.5 km/h 출발 준비 저속 주행 (1.0 < speed <= 5.0) -> 이전 브리핑 유지
        onSpeedChanged(2.5)
        assertNotNull(currentBriefing)

        // 4) 6.0 km/h 본격 주행 (> 5.0 km/h) -> 브리핑 자동 제거 (null)
        onSpeedChanged(6.0)
        assertNull(currentBriefing)

        // 5) 45.0 km/h 도심 주행 중 -> 여전히 null
        onSpeedChanged(45.0)
        assertNull(currentBriefing)

        // 6) 다음 신호등에서 0.5 km/h로 감속 정차 (<= 1.0 km/h) -> 브리핑 재출현
        onSpeedChanged(0.5)
        assertNotNull(currentBriefing)
        assertTrue(currentBriefing!!.startsWith("🚦 신호 대기 중"))
    }
}
