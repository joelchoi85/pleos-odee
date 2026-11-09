package com.hyundai.pleos.syncdrive.audio

import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.VoiceSessionPayload
import com.hyundai.pleos.syncdrive.sdk.VehiclePlatformGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class VoiceRoomState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_DUCKED
}

data class AudioDspDiagnostics(
    val audioSessionId: Int? = null,
    val aecEnabled: Boolean = false,
    val noiseSuppressorEnabled: Boolean = false
)

data class ActiveVoiceCallState(
    val roomState: VoiceRoomState = VoiceRoomState.DISCONNECTED,
    val roomName: String? = null,
    val livekitUrl: String? = null,
    val activeToken: String? = null,
    val isMediaDucked: Boolean = false,
    val mediaDuckVolumePercent: Int = 100,
    val isMicMuted: Boolean = false,
    val dsp: AudioDspDiagnostics = AudioDspDiagnostics(),
    val isHardBrakingMuted: Boolean = false
)

/**
 * AAOS 인포테인먼트 오디오 포커스(MAY_DUCK) 및 하드웨어 AEC/NS 제어 어댑터 인터페이스.
 */
interface AudioFocusAdapter {
    val hasDuckingFocus: StateFlow<Boolean>

    fun requestMayDuckVoiceFocus(onFocusLost: () -> Unit = {}): Boolean
    fun abandonVoiceFocus()
    fun attachVoiceDspEffects(audioSessionId: Int): AudioDspDiagnostics
    fun releaseVoiceDspEffects()
}

/**
 * Android AudioManager + AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) 실기기/에뮬레이터 구현체.
 *
 * - Usage: AudioAttributes.USAGE_VOICE_COMMUNICATION
 * - ContentType: AudioAttributes.CONTENT_TYPE_SPEECH
 * - 활성 오디오 세션에 AcousticEchoCanceler(AEC) 및 NoiseSuppressor(NS) 바인딩
 */
class AndroidAudioFocusAdapter(context: Context) : AudioFocusAdapter {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _hasDuckingFocus = MutableStateFlow(false)
    override val hasDuckingFocus: StateFlow<Boolean> = _hasDuckingFocus.asStateFlow()

    private var activeFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    override fun requestMayDuckVoiceFocus(onFocusLost: () -> Unit): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                _hasDuckingFocus.value = false
                onFocusLost()
            }
        }

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()

        val result = audioManager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            previousAudioMode = audioManager.mode
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            activeFocusRequest = request
            _hasDuckingFocus.value = true
        }
        return granted
    }

    override fun abandonVoiceFocus() {
        val request = activeFocusRequest
        if (request != null) {
            runCatching { audioManager.abandonAudioFocusRequest(request) }
            activeFocusRequest = null
        }
        runCatching { audioManager.mode = previousAudioMode }
        _hasDuckingFocus.value = false
    }

    override fun attachVoiceDspEffects(audioSessionId: Int): AudioDspDiagnostics {
        releaseVoiceDspEffects()
        val resolvedSessionId = if (audioSessionId > 0) {
            audioSessionId
        } else {
            runCatching { audioManager.generateAudioSessionId() }.getOrDefault(0)
        }
        if (resolvedSessionId <= 0 || resolvedSessionId == AudioManager.ERROR) {
            return AudioDspDiagnostics(audioSessionId = null)
        }

        var aecActive = false
        var nsActive = false

        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(resolvedSessionId)?.apply {
                    enabled = true
                    aecActive = enabled
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "AEC attach failed: ${e.message}")
        }

        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(resolvedSessionId)?.apply {
                    enabled = true
                    nsActive = enabled
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "NoiseSuppressor attach failed: ${e.message}")
        }

        return AudioDspDiagnostics(
            audioSessionId = resolvedSessionId,
            aecEnabled = aecActive,
            noiseSuppressorEnabled = nsActive
        )
    }

    override fun releaseVoiceDspEffects() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }

    companion object {
        private const val TAG = "AndroidAudioFocus"
    }
}

/**
 * WebRTC / LiveKit 룸 시그널링 전송 어댑터 인터페이스.
 */
interface WebRtcRoomConnector {
    suspend fun connectRoom(roomName: String, livekitUrl: String, token: String?)
    suspend fun disconnectRoom()
    fun setMicrophoneEnabled(enabled: Boolean)
    /** 일행 음성(수신) 볼륨 0.0~1.0 */
    fun setRemoteAudioVolume(volume: Double) {}
}

/**
 * LiveKit 실제 연결. 오디오 포커스는 AndroidAudioFocusAdapter 가 관리하므로 SDK 자체 처리(AudioSwitch)는 끈다.
 */
class LiveKitRoomConnector(private val appContext: Context) : WebRtcRoomConnector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var room: Room? = null

    override suspend fun connectRoom(roomName: String, livekitUrl: String, token: String?) {
        requireNotNull(token) { "LiveKit token missing for $roomName" }
        disconnectRoom()
        val r = LiveKit.create(
            appContext = appContext,
            overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = NoAudioHandler()))
        )
        room = r
        r.connect(livekitUrl, token)
        r.localParticipant.setMicrophoneEnabled(true)
    }

    override suspend fun disconnectRoom() {
        room?.let { it.disconnect(); it.release() }
        room = null
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        val r = room ?: return
        scope.launch { r.localParticipant.setMicrophoneEnabled(enabled) }
    }

    // ponytail: 현재 구독 중인 트랙에만 적용. 급제동은 수 초라 그 사이 새로 들어온 참가자는 원래 볼륨
    override fun setRemoteAudioVolume(volume: Double) {
        val r = room ?: return
        r.remoteParticipants.values.forEach { p ->
            p.audioTrackPublications.forEach { (_, track) -> (track as? RemoteAudioTrack)?.setVolume(volume) }
        }
    }
}

/**
 * 차량 내 멀티파티 WebRTC 음성 통화 및 미디어 볼륨 30% 더킹(Audio Ducking) 세션 매니저.
 *
 * - 통화 시작 시: AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 획득 -> AEC/NS 활성화 ->
 *   WebRTC 룸 연결 -> 차량 앰비언트 라이트 GREEN_VOICE_ACTIVE 전환 -> CONNECTED_DUCKED(30%)
 * - 통화 종료 시: WebRTC 연결 해제 -> AEC/NS 해제 -> abandonAudioFocusRequest 보장 ->
 *   차량 앰비언트 라이트 이전 상태 복원(restoreAmbientState) -> DISCONNECTED
 */
class InCarVoiceSessionManager(
    private val audioFocusAdapter: AudioFocusAdapter,
    private val vehicleGateway: VehiclePlatformGateway,
    private val roomConnector: WebRtcRoomConnector
) {
    private val _state = MutableStateFlow(ActiveVoiceCallState())
    val state: StateFlow<ActiveVoiceCallState> = _state.asStateFlow()

    private var ambientGreenOverridden: Boolean = false

    suspend fun startVoiceSession(
        voiceSession: VoiceSessionPayload,
        participantId: String,
        audioSessionId: Int = DEFAULT_VOICE_AUDIO_SESSION_ID
    ): Boolean {
        if (_state.value.roomState == VoiceRoomState.CONNECTED_DUCKED) {
            return true
        }

        val token = voiceSession.participantTokens[participantId]
            ?: voiceSession.participantTokens.values.firstOrNull()

        _state.value = ActiveVoiceCallState(
            roomState = VoiceRoomState.CONNECTING,
            roomName = voiceSession.roomName,
            livekitUrl = voiceSession.livekitUrl,
            activeToken = token,
            isHardBrakingMuted = _state.value.isHardBrakingMuted
        )

        val focusGranted = audioFocusAdapter.requestMayDuckVoiceFocus()
        if (!focusGranted) {
            _state.value = ActiveVoiceCallState(isHardBrakingMuted = _state.value.isHardBrakingMuted)
            return false
        }

        return try {
            val dspStatus = audioFocusAdapter.attachVoiceDspEffects(audioSessionId)
            roomConnector.connectRoom(
                roomName = voiceSession.roomName,
                livekitUrl = voiceSession.livekitUrl,
                token = token
            )
            vehicleGateway.setAmbientAlertState(AmbientAlertColor.GREEN_VOICE_ACTIVE)
            ambientGreenOverridden = true

            val isHardBraking = _state.value.isHardBrakingMuted
            if (isHardBraking) {
                roomConnector.setRemoteAudioVolume(HARD_BRAKING_REMOTE_VOLUME)
            }

            _state.value = ActiveVoiceCallState(
                roomState = VoiceRoomState.CONNECTED_DUCKED,
                roomName = voiceSession.roomName,
                livekitUrl = voiceSession.livekitUrl,
                activeToken = token,
                isMediaDucked = true,
                mediaDuckVolumePercent = DUCKED_MEDIA_VOLUME_PERCENT,
                isMicMuted = false,
                dsp = dspStatus,
                isHardBrakingMuted = isHardBraking
            )
            true
        } catch (t: Throwable) {
            cleanupResources()
            false
        }
    }

    suspend fun endVoiceSession() {
        if (_state.value.roomState == VoiceRoomState.DISCONNECTED && !ambientGreenOverridden) {
            return
        }
        try {
            runCatching { roomConnector.disconnectRoom() }
        } finally {
            cleanupResources()
        }
    }

    /** 급제동 중엔 일행 음성(수신)을 줄여 운전자 주의를 도로로. 내 마이크는 건드리지 않음 */
    fun setHardBrakingMute(muted: Boolean) {
        if (_state.value.isHardBrakingMuted == muted) return
        _state.update { it.copy(isHardBrakingMuted = muted) }

        if (_state.value.roomState == VoiceRoomState.CONNECTED_DUCKED) {
            roomConnector.setRemoteAudioVolume(if (muted) HARD_BRAKING_REMOTE_VOLUME else 1.0)
        }
    }

    fun toggleMicrophoneMute() {
        if (_state.value.roomState != VoiceRoomState.CONNECTED_DUCKED) return
        val nextMuted = !_state.value.isMicMuted
        _state.update { it.copy(isMicMuted = nextMuted) }
        roomConnector.setMicrophoneEnabled(!nextMuted)
    }

    private suspend fun cleanupResources() {
        audioFocusAdapter.releaseVoiceDspEffects()
        audioFocusAdapter.abandonVoiceFocus()
        if (ambientGreenOverridden) {
            ambientGreenOverridden = false
            vehicleGateway.restoreAmbientState()
        }
        // 급제동 플래그는 통화와 무관한 차량 상태라 유지 (리셋하면 제동 중 시작한 다음 통화가 마이크 켜진 채 연결됨)
        _state.value = ActiveVoiceCallState(isHardBrakingMuted = _state.value.isHardBrakingMuted)
    }

    companion object {
        const val DUCKED_MEDIA_VOLUME_PERCENT = 30
        /** 급제동 중 일행 음성 볼륨 (완전 차단 대신 20%: 일행이 경고를 외칠 수도 있음) */
        const val HARD_BRAKING_REMOTE_VOLUME = 0.2
        const val DEFAULT_VOICE_AUDIO_SESSION_ID = 4101
    }
}
