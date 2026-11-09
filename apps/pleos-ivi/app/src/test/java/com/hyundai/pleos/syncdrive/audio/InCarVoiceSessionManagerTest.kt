package com.hyundai.pleos.syncdrive.audio

import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.VoiceSessionPayload
import com.hyundai.pleos.syncdrive.sdk.SimulatorBridgeGatewayImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InCarVoiceSessionManagerTest {

    private class FakeAudioFocusAdapter(
        private val shouldGrantFocus: Boolean = true
    ) : AudioFocusAdapter {
        private val _hasDuckingFocus = MutableStateFlow(false)
        override val hasDuckingFocus: StateFlow<Boolean> = _hasDuckingFocus.asStateFlow()

        var requestFocusCalls: Int = 0
        var abandonFocusCalls: Int = 0
        var dspAttachedSessionId: Int? = null
        var dspReleasedCalls: Int = 0

        override fun requestMayDuckVoiceFocus(onFocusLost: () -> Unit): Boolean {
            requestFocusCalls += 1
            if (shouldGrantFocus) {
                _hasDuckingFocus.value = true
            }
            return shouldGrantFocus
        }

        override fun abandonVoiceFocus() {
            abandonFocusCalls += 1
            _hasDuckingFocus.value = false
        }

        override fun attachVoiceDspEffects(audioSessionId: Int): AudioDspDiagnostics {
            dspAttachedSessionId = audioSessionId
            return AudioDspDiagnostics(
                audioSessionId = audioSessionId,
                aecEnabled = true,
                noiseSuppressorEnabled = true
            )
        }

        override fun releaseVoiceDspEffects() {
            dspReleasedCalls += 1
            dspAttachedSessionId = null
        }
    }

    private val sampleVoiceSession = VoiceSessionPayload(
        roomName = "pleos-room-gangneung",
        livekitUrl = "ws://10.0.2.2:7880",
        participantTokens = mapOf("veh-ioniq6-host" to "jwt-token-host-01"),
        autoConnectOnAccept = true
    )

    @Test
    fun `starting voice call requests MAY_DUCK audio focus and sets GREEN_VOICE_ACTIVE, ending call releases focus and restores prior ambient state`() = runTest {
        val fakeAudio = FakeAudioFocusAdapter()
        val gateway = SimulatorBridgeGatewayImpl(
            initialBaselineAmbient = AmbientAlertColor.CYAN_PROPOSAL
        )
        val roomConnector = object : WebRtcRoomConnector {
            var isConnected = false
            override suspend fun connectRoom(roomName: String, livekitUrl: String, token: String?) { isConnected = true }
            override suspend fun disconnectRoom() { isConnected = false }
            override fun setMicrophoneEnabled(enabled: Boolean) = Unit
        }
        val manager = InCarVoiceSessionManager(
            audioFocusAdapter = fakeAudio,
            vehicleGateway = gateway,
            roomConnector = roomConnector
        )

        assertEquals(AmbientAlertColor.CYAN_PROPOSAL, gateway.ambientColorFlow.value)
        assertEquals(VoiceRoomState.DISCONNECTED, manager.state.value.roomState)

        // Start voice session
        val started = manager.startVoiceSession(
            voiceSession = sampleVoiceSession,
            participantId = "veh-ioniq6-host",
            audioSessionId = 777
        )

        assertTrue(started)
        assertEquals(1, fakeAudio.requestFocusCalls)
        assertTrue(fakeAudio.hasDuckingFocus.value)
        assertEquals(777, fakeAudio.dspAttachedSessionId)
        assertTrue(roomConnector.isConnected)
        assertEquals(VoiceRoomState.CONNECTED_DUCKED, manager.state.value.roomState)
        assertEquals(30, manager.state.value.mediaDuckVolumePercent)
        assertTrue(manager.state.value.isMediaDucked)
        assertEquals(AmbientAlertColor.GREEN_VOICE_ACTIVE, gateway.ambientColorFlow.value)

        // End voice session -> must abandon audio focus, release AEC/NS, and restore CYAN_PROPOSAL
        manager.endVoiceSession()

        assertEquals(1, fakeAudio.abandonFocusCalls)
        assertFalse(fakeAudio.hasDuckingFocus.value)
        assertEquals(1, fakeAudio.dspReleasedCalls)
        assertFalse(roomConnector.isConnected)
        assertEquals(VoiceRoomState.DISCONNECTED, manager.state.value.roomState)
        assertEquals(AmbientAlertColor.CYAN_PROPOSAL, gateway.ambientColorFlow.value)

        // Calling endVoiceSession a second time is idempotent and does not corrupt ambient state
        manager.endVoiceSession()
        assertEquals(AmbientAlertColor.CYAN_PROPOSAL, gateway.ambientColorFlow.value)
    }

    @Test
    fun `failed WebRTC room connection guarantees audio focus release and ambient restoration`() = runTest {
        val fakeAudio = FakeAudioFocusAdapter()
        val gateway = SimulatorBridgeGatewayImpl(
            initialBaselineAmbient = AmbientAlertColor.NONE
        )
        val failingConnector = object : WebRtcRoomConnector {
            override suspend fun connectRoom(roomName: String, livekitUrl: String, token: String?) {
                throw IllegalStateException("Simulated LiveKit signaling failure")
            }

            override suspend fun disconnectRoom() = Unit
            override fun setMicrophoneEnabled(enabled: Boolean) = Unit
        }

        val manager = InCarVoiceSessionManager(
            audioFocusAdapter = fakeAudio,
            vehicleGateway = gateway,
            roomConnector = failingConnector
        )

        val started = manager.startVoiceSession(
            voiceSession = sampleVoiceSession,
            participantId = "veh-ioniq6-host"
        )

        assertFalse(started)
        assertEquals(1, fakeAudio.requestFocusCalls)
        assertEquals(1, fakeAudio.abandonFocusCalls)
        assertFalse(fakeAudio.hasDuckingFocus.value)
        assertEquals(VoiceRoomState.DISCONNECTED, manager.state.value.roomState)
        assertEquals(AmbientAlertColor.NONE, gateway.ambientColorFlow.value)
    }
}
