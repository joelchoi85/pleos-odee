package com.hyundai.pleos.syncdrive.sdk

import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.DemoGroupSeed
import com.hyundai.pleos.syncdrive.domain.GearState
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.PoiCategory
import com.hyundai.pleos.syncdrive.domain.RecommendedWaypoint
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/**
 * AAOS 에뮬레이터 디버그 바 및 단위 테스트용 런타임 주행 상태 시뮬레이션 브릿지 게이트웨이.
 *
 * - 속도(0 / 45 / 90 km/h), 기어(P / D), 브레이크, SOC % 즉시 전환 지원
 * - setAmbientAlertState 호출 시 이전 앰비언트 상태를 스택에 보존하여
 *   restoreAmbientState() 호출 시 사용자의 직전 앰비언트 조명 설정을 정확히 복원합니다.
 */
class SimulatorBridgeGatewayImpl(
    initialSnapshot: LocalVehicleSnapshot = LocalVehicleSnapshot(),
    initialBaselineAmbient: AmbientAlertColor = AmbientAlertColor.NONE,
    private val delegateVoiceGateway: VehiclePlatformGateway? = null
) : VehiclePlatformGateway {

    private val _telemetryFlow = MutableStateFlow(initialSnapshot)
    override val telemetryFlow: StateFlow<LocalVehicleSnapshot> = _telemetryFlow.asStateFlow()

    private var userBaselineAmbientColor: AmbientAlertColor = initialBaselineAmbient
    private val ambientHistoryStack = ArrayDeque<AmbientAlertColor>()

    private val _ambientColorFlow = MutableStateFlow(initialBaselineAmbient)
    override val ambientColorFlow: StateFlow<AmbientAlertColor> = _ambientColorFlow.asStateFlow()

    private val _navigationWaypointsFlow = MutableStateFlow(emptyList<RecommendedWaypoint>())
    val navigationWaypointsFlow: StateFlow<List<RecommendedWaypoint>> = _navigationWaypointsFlow.asStateFlow()

    private val _lastSpokenBriefing = MutableStateFlow<String?>(null)
    val lastSpokenBriefing: StateFlow<String?> = _lastSpokenBriefing.asStateFlow()

    private val _isListeningForVoice = MutableStateFlow(false)
    val isListeningForVoice: StateFlow<Boolean> = _isListeningForVoice.asStateFlow()

    private var pendingVoiceCallback: ((VoiceDecision) -> Unit)? = null

    fun applyStationaryParkPreset() {
        _telemetryFlow.update {
            it.copy(
                speedKmh = 0.0,
                gear = GearState.P,
                isBraking = false
            )
        }
    }

    fun applyUrbanDrivingPreset() {
        _telemetryFlow.update {
            it.copy(
                speedKmh = 45.0,
                gear = GearState.D,
                isBraking = false
            )
        }
    }

    fun applyHighwayHighFocusPreset() {
        _telemetryFlow.update {
            it.copy(
                speedKmh = 90.0,
                gear = GearState.D,
                isBraking = false
            )
        }
    }

    fun setSpeedAndGear(speedKmh: Double, gear: GearState) {
        _telemetryFlow.update {
            it.copy(
                speedKmh = speedKmh.coerceIn(0.0, 260.0),
                gear = gear
            )
        }
    }

    fun setBraking(isBraking: Boolean) {
        _telemetryFlow.update { it.copy(isBraking = isBraking) }
    }

    fun setSocPercent(socPercent: Double) {
        val clamped = socPercent.coerceIn(0.0, 100.0)
        _telemetryFlow.update {
            it.copy(
                socPercent = clamped,
                remainingRangeKm = (clamped * 5.1)
            )
        }
    }

    fun updateSnapshot(transform: (LocalVehicleSnapshot) -> LocalVehicleSnapshot) {
        _telemetryFlow.update(transform)
    }

    /**
     * 사용자가 수동으로 설정한 기본 실내 무드등 색상을 갱신합니다.
     */
    fun setUserBaselineAmbient(color: AmbientAlertColor) {
        userBaselineAmbientColor = color
        ambientHistoryStack.clear()
        _ambientColorFlow.value = color
    }

    override suspend fun updateNavigationWaypoint(lat: Double, lng: Double, title: String): Boolean {
        val newWaypoint = RecommendedWaypoint(
            name = title,
            lat = lat,
            lng = lng,
            poiCategory = PoiCategory.EV_CHARGER
        )
        _navigationWaypointsFlow.update { current ->
            val deduplicated = current.filterNot { it.name == title }
            listOf(newWaypoint) + deduplicated
        }
        delegateVoiceGateway?.updateNavigationWaypoint(lat, lng, title)
        return true
    }

    override suspend fun setAmbientAlertState(alertColor: AmbientAlertColor) {
        val current = _ambientColorFlow.value
        if (current != alertColor) {
            ambientHistoryStack.push(current)
            _ambientColorFlow.value = alertColor
        }
        delegateVoiceGateway?.setAmbientAlertState(alertColor)
    }

    override suspend fun restoreAmbientState() {
        val previous = if (ambientHistoryStack.isNotEmpty()) {
            ambientHistoryStack.pop()
        } else {
            userBaselineAmbientColor
        }
        _ambientColorFlow.value = previous
        delegateVoiceGateway?.restoreAmbientState()
    }

    override fun speakBriefing(text: String, onDone: () -> Unit) {
        _lastSpokenBriefing.value = text
        val delegate = delegateVoiceGateway
        if (delegate != null) {
            delegate.speakBriefing(text, onDone)
        } else {
            onDone()
        }
    }

    override fun startVoiceConfirmation(onCommandRecognized: (VoiceDecision) -> Unit) {
        _isListeningForVoice.value = true
        pendingVoiceCallback = { decision ->
            _isListeningForVoice.value = false
            pendingVoiceCallback = null
            onCommandRecognized(decision)
        }
        val delegate = delegateVoiceGateway
        if (delegate != null) {
            delegate.startVoiceConfirmation { recognized ->
                pendingVoiceCallback?.invoke(recognized)
            }
        }
    }

    override fun stopVoiceConfirmation() {
        _isListeningForVoice.value = false
        pendingVoiceCallback = null
        delegateVoiceGateway?.stopVoiceConfirmation()
    }

    /**
     * 에뮬레이터 UI 또는 테스트에서 음성 인식 결과를 직접 시뮬레이션합니다.
     */
    fun simulateVoiceUtterance(utterance: String): Boolean {
        val decision = VoiceCommandParser.parse(utterance) ?: return false
        return simulateVoiceDecision(decision)
    }

    fun simulateVoiceDecision(decision: VoiceDecision): Boolean {
        val cb = pendingVoiceCallback ?: return false
        cb(decision)
        return true
    }
}
