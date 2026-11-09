package com.hyundai.pleos.syncdrive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyundai.pleos.syncdrive.BuildConfig
import com.hyundai.pleos.syncdrive.audio.ActiveVoiceCallState
import com.hyundai.pleos.syncdrive.audio.AndroidAudioFocusAdapter
import com.hyundai.pleos.syncdrive.audio.InCarVoiceSessionManager
import com.hyundai.pleos.syncdrive.audio.VoiceRoomState
import com.hyundai.pleos.syncdrive.domain.GeoPoint
import com.hyundai.pleos.syncdrive.domain.AlertCountdownCoordinator
import com.hyundai.pleos.syncdrive.domain.AlertCountdownState
import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.CompanionVehicleUiModel
import com.hyundai.pleos.syncdrive.domain.DemoGroupSeed
import com.hyundai.pleos.syncdrive.domain.DrivingSafetyLevel
import com.hyundai.pleos.syncdrive.domain.GroupAlertEvent
import com.hyundai.pleos.syncdrive.domain.GroupAlertUiModel
import com.hyundai.pleos.syncdrive.domain.GroupSessionSnapshot
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.RecommendedWaypoint
import com.hyundai.pleos.syncdrive.domain.ServerWsEvent
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import com.hyundai.pleos.syncdrive.domain.checkIsHardBraking
import com.hyundai.pleos.syncdrive.domain.generateStationaryBriefing
import com.hyundai.pleos.syncdrive.domain.toCompanionUiModel
import com.hyundai.pleos.syncdrive.domain.toUiModel
import com.hyundai.pleos.syncdrive.domain.toIncomingCallAlert
import com.hyundai.pleos.syncdrive.domain.GroupAlertType
import com.hyundai.pleos.syncdrive.network.GroupSyncWebSocketClient
import com.hyundai.pleos.syncdrive.network.WsConnectionStatus
import com.hyundai.pleos.syncdrive.sdk.GleoIntentEventBus
import com.hyundai.pleos.syncdrive.sdk.PleosNativeGatewayImpl
import com.hyundai.pleos.syncdrive.sdk.SimulatorBridgeGatewayImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.hyundai.pleos.syncdrive.audio.LiveKitRoomConnector
import com.hyundai.pleos.syncdrive.domain.VoiceSessionPayload

data class SyncDriveScreenState(
    val groupId: String = DemoGroupSeed.DEFAULT_GROUP_ID,
    val groupTitle: String = "강릉 안목해변 주말 패밀리 투어",
    val inviteCode: String = DemoGroupSeed.DEFAULT_INVITE_CODE,
    val originName: String = "판교 현대모터스튜디오",
    val destinationName: String = "강릉 안목해변 공영주차장",
    val localVehicle: LocalVehicleSnapshot = LocalVehicleSnapshot(),
    val safetyLevel: DrivingSafetyLevel = DrivingSafetyLevel.STATIONARY,
    val ambientColor: AmbientAlertColor = AmbientAlertColor.NONE,
    val companions: List<CompanionVehicleUiModel> = emptyList(),
    val waypoints: List<RecommendedWaypoint> = emptyList(),
    val activeAlert: GroupAlertUiModel? = null,
    val countdown: AlertCountdownState = AlertCountdownState(),
    val voiceCall: ActiveVoiceCallState = ActiveVoiceCallState(),
    val isVoiceCommandListening: Boolean = false,
    val wsStatus: WsConnectionStatus = WsConnectionStatus.DISCONNECTED,
    val wsUrl: String = BuildConfig.DEFAULT_SYNC_WS_URL,
    val telemetryIntervalMs: Long = 10_000L,
    val isPleosNativeAarDetected: Boolean = false,
    val statusBannerKo: String? = null,
    /** 그룹 기준 경로 (서버 baselinePolyline). 비면 지도는 일행 위치만 표시 */
    val routePolyline: List<GeoPoint> = emptyList(),
    val isHardBrakingAudioMuted: Boolean = false,
    val stationaryBriefing: String? = null
)

class SyncDriveViewModel(application: Application) : AndroidViewModel(application) {

    private val nativeGateway = PleosNativeGatewayImpl(application.applicationContext)
    private val simulatorGateway = SimulatorBridgeGatewayImpl(
        delegateVoiceGateway = nativeGateway
    )
    private val audioFocusAdapter = AndroidAudioFocusAdapter(application.applicationContext)
    private val voiceSessionManager = InCarVoiceSessionManager(
        audioFocusAdapter = audioFocusAdapter,
        vehicleGateway = simulatorGateway,
        roomConnector = LiveKitRoomConnector(application.applicationContext)
    )
    private val countdownCoordinator = AlertCountdownCoordinator(viewModelScope)

    private val wsClient = GroupSyncWebSocketClient(
        scope = viewModelScope,
        telemetryProvider = { simulatorGateway.telemetryFlow.value },
        initialWsUrl = BuildConfig.DEFAULT_SYNC_WS_URL,
        initialGroupId = BuildConfig.DEFAULT_GROUP_ID
    )

    private val activeAlertFlow = MutableStateFlow<GroupAlertUiModel?>(null)
    private val statusBannerFlow = MutableStateFlow<String?>(null)
    private val isHardBrakingAudioMutedFlow = MutableStateFlow(false)
    private val stationaryBriefingFlow = MutableStateFlow<String?>(null)
    private var previousSpeedKmh: Double = 0.0
    private var alertAmbientOverridden: Boolean = false

    private data class CoreFlowsTuple(
        val localVehicle: LocalVehicleSnapshot,
        val ambientColor: AmbientAlertColor,
        val waypoints: List<RecommendedWaypoint>,
        val sessionSnapshot: GroupSessionSnapshot?,
        val activeAlert: GroupAlertUiModel?
    )

    private val coreFlows = combine(
        simulatorGateway.telemetryFlow,
        simulatorGateway.ambientColorFlow,
        simulatorGateway.navigationWaypointsFlow,
        wsClient.sessionSnapshot,
        activeAlertFlow
    ) { localVehicle, ambientColor, localWaypoints, sessionSnapshot, activeAlert ->
        CoreFlowsTuple(
            localVehicle = localVehicle,
            ambientColor = ambientColor,
            waypoints = localWaypoints,
            sessionSnapshot = sessionSnapshot,
            activeAlert = activeAlert
        )
    }

    private data class RuntimeFlowsTuple(
        val countdown: AlertCountdownState,
        val voiceCall: ActiveVoiceCallState,
        val isListening: Boolean,
        val wsStatus: WsConnectionStatus,
        val intervalMs: Long
    )

    private val runtimeFlows = combine(
        countdownCoordinator.state,
        voiceSessionManager.state,
        simulatorGateway.isListeningForVoice,
        wsClient.connectionStatus,
        wsClient.telemetryIntervalMs
    ) { countdown, voiceCall, isListening, wsStatus, intervalMs ->
        RuntimeFlowsTuple(
            countdown = countdown,
            voiceCall = voiceCall,
            isListening = isListening,
            wsStatus = wsStatus,
            intervalMs = intervalMs
        )
    }

    val uiState: StateFlow<SyncDriveScreenState> = combine(
        coreFlows,
        runtimeFlows,
        statusBannerFlow,
        isHardBrakingAudioMutedFlow,
        stationaryBriefingFlow
    ) { core, runtime, banner, isHardBrakingAudioMuted, stationaryBriefing ->
        val snapshot = core.sessionSnapshot
        val local = core.localVehicle
        val companions = resolveCompanions(snapshot, local, runtime.voiceCall)

        val mergedWaypoints = if (snapshot != null && snapshot.activeWaypoints.isNotEmpty()) {
            snapshot.activeWaypoints
        } else {
            core.waypoints
        }

        SyncDriveScreenState(
            groupId = snapshot?.groupId ?: wsClient.groupId.value,
            groupTitle = snapshot?.title ?: "그룹 연결 중",
            inviteCode = snapshot?.inviteCode ?: DemoGroupSeed.DEFAULT_INVITE_CODE,
            originName = snapshot?.origin?.name ?: "판교 현대모터스튜디오",
            destinationName = snapshot?.destination?.name ?: "목적지 대기",
            localVehicle = local,
            safetyLevel = local.safetyLevel,
            ambientColor = core.ambientColor,
            companions = companions,
            waypoints = mergedWaypoints,
            activeAlert = core.activeAlert,
            countdown = runtime.countdown,
            voiceCall = runtime.voiceCall,
            isVoiceCommandListening = runtime.isListening,
            wsStatus = runtime.wsStatus,
            wsUrl = wsClient.wsUrl.value,
            telemetryIntervalMs = runtime.intervalMs,
            isPleosNativeAarDetected = nativeGateway.isPleosSdkBound,
            statusBannerKo = banner,
            routePolyline = snapshot?.baselinePolyline.orEmpty(),
            isHardBrakingAudioMuted = isHardBrakingAudioMuted,
            stationaryBriefing = stationaryBriefing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SyncDriveScreenState(
            companions = DemoGroupSeed.defaultCompanions(LocalVehicleSnapshot()),
        )
    )

    init {
        // 실차/Pleos 에뮬레이터 센서 값을 시뮬레이터 스냅샷에 병합 (서비스 없으면 흐름 없음)
        viewModelScope.launch { nativeGateway.vehicleUpdates.collect(simulatorGateway::updateSnapshot) }
        observeWebSocketEvents()
        observeGlobalGleoCommands()
        observeGleoCallCommands()
        observeDrivingSafetyAndBriefing()
        // 그룹 목적지를 순정 내비에 1회 설정 (목적지가 바뀔 때만 다시)
        viewModelScope.launch {
            wsClient.sessionSnapshot.mapNotNull { it?.destination }.distinctUntilChanged().collect {
                nativeGateway.setNavigationDestination(it.lat, it.lng, it.name)
            }
        }
    }

    private fun observeDrivingSafetyAndBriefing() {
        viewModelScope.launch {
            combine(
                simulatorGateway.telemetryFlow,
                wsClient.sessionSnapshot
            ) { local, snapshot ->
                Pair(local, snapshot)
            }.collect { (local, snapshot) ->
                val isHardBraking = checkIsHardBraking(
                    isBraking = local.isBraking,
                    currentSpeedKmh = local.speedKmh,
                    previousSpeedKmh = previousSpeedKmh,
                    gear = local.gear
                )
                previousSpeedKmh = local.speedKmh

                if (isHardBraking != isHardBrakingAudioMutedFlow.value) {
                    isHardBrakingAudioMutedFlow.value = isHardBraking
                    voiceSessionManager.setHardBrakingMute(isHardBraking)
                    if (isHardBraking) {
                        simulatorGateway.stopVoiceConfirmation()
                    }
                }

                if (local.speedKmh <= 1.0) {
                    val companions = resolveCompanions(snapshot, local, voiceSessionManager.state.value)
                    stationaryBriefingFlow.value = generateStationaryBriefing(local, companions)
                } else if (local.speedKmh > 5.0) {
                    stationaryBriefingFlow.value = null
                }
            }
        }
    }

    private fun resolveCompanions(
        snapshot: GroupSessionSnapshot?,
        local: LocalVehicleSnapshot,
        voiceCall: ActiveVoiceCallState
    ): List<CompanionVehicleUiModel> {
        return if (snapshot != null && snapshot.members.isNotEmpty()) {
            snapshot.members.map { member ->
                if (member.memberId == local.vehicleId) {
                    member.toCompanionUiModel(local.vehicleId).copy(
                        speedKmh = local.speedKmh.toInt(),
                        gear = local.gear,
                        energyPercent = (local.socPercent ?: 82.0).toInt(),
                        inVoiceCall = voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED,
                        lat = local.lat,
                        lng = local.lng
                    )
                } else {
                    member.toCompanionUiModel(local.vehicleId)
                }
            }
        } else {
            DemoGroupSeed.defaultCompanions(local).map { item ->
                if (item.isSelfHost) {
                    item.copy(
                        inVoiceCall = voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED
                    )
                } else {
                    item
                }
            }
        }
    }

    /** 위치·음성 이용 고지에 동의한 뒤에만 그룹 세션(위치 전송) 시작 */
    fun startGroupSession() {
        wsClient.connect()
    }

    /** 받은 1:1 호출(거절 시 발신자에게 VOICE_DECLINE 보낼 대상). 그룹 호출이면 null */
    private var pendingDirectInvite: ServerWsEvent.VoiceInvite? = null

    private fun observeWebSocketEvents() {
        viewModelScope.launch {
            wsClient.events.collect { event ->
                when (event) {
                    is ServerWsEvent.SessionSnapshot -> {
                        val serverAlert = event.snapshot.activeAlert
                        if (serverAlert != null && activeAlertFlow.value?.eventId != serverAlert.eventId) {
                            presentIncomingAlert(serverAlert)
                        }
                    }
                    is ServerWsEvent.GroupAlert -> {
                        presentIncomingAlert(event.alert)
                    }
                    is ServerWsEvent.AlertResolved -> {
                        if (activeAlertFlow.value?.eventId == event.eventId) {
                            dismissActiveAlertCleanupOnly()
                            statusBannerFlow.value = "그룹 경로 의사결정 동기화 완료 (${event.decision.name})"
                        }
                    }
                    is ServerWsEvent.ProposalRejected -> {
                        statusBannerFlow.value = "경로 제안 반려: ${event.reasonKo}"
                    }
                    is ServerWsEvent.SessionTerminated -> {
                        dismissActiveAlertCleanupOnly()
                        voiceSessionManager.endVoiceSession()
                        statusBannerFlow.value =
                            "목적지 도착 · 그룹 위치 및 음성 세션 데이터가 즉시 파기되었습니다."
                    }
                    is ServerWsEvent.VoiceInvite -> {
                        val me = simulatorGateway.telemetryFlow.value.vehicleId
                        if (event.isDecline) {
                            // 내가 건 1:1 통화를 상대가 거절 → 혼자 남은 방에서 나옴
                            if (event.toMemberId == me && voiceSessionManager.state.value.roomName == event.roomName) {
                                voiceSessionManager.endVoiceSession()
                                wsClient.sendVoiceStateChange(inVoiceCall = false)
                                statusBannerFlow.value = "${event.fromDisplayName} 통화 거절"
                            }
                        } else if (event.fromMemberId != me && (event.toMemberId == me || event.toMemberId == "*")) {
                            pendingDirectInvite = event.takeIf { it.toMemberId == me }
                            presentIncomingAlert(event.toIncomingCallAlert(wsClient.groupId.value))
                        }
                    }
                    is ServerWsEvent.TelemetryAck -> Unit
                    is ServerWsEvent.ErrorMessage -> {
                        statusBannerFlow.value = "서버 알림: ${event.message}"
                    }
                }
            }
        }
    }

    private fun observeGlobalGleoCommands() {
        viewModelScope.launch {
            GleoIntentEventBus.decisions.collect { decision ->
                if (activeAlertFlow.value != null) {
                    onAlertDecisionSelected(decision)
                }
            }
        }
    }

    private fun observeGleoCallCommands() {
        viewModelScope.launch {
            GleoIntentEventBus.callCommands.collect { action ->
                when (action) {
                    GleoIntentEventBus.VoiceCallAction.END_CALL -> leaveVoiceCall()
                    GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE -> toggleVoiceMute()
                    GleoIntentEventBus.VoiceCallAction.MUTE -> {
                        if (!voiceSessionManager.state.value.isMicMuted) {
                            toggleVoiceMute()
                        }
                    }
                    GleoIntentEventBus.VoiceCallAction.UNMUTE -> {
                        if (voiceSessionManager.state.value.isMicMuted) {
                            toggleVoiceMute()
                        }
                    }
                }
            }
        }
    }

    private suspend fun presentIncomingAlert(event: GroupAlertEvent) {
        // 내 차의 이탈은 내 화면에 띄우지 않음 (운전자는 이미 알고, 순정 내비가 재탐색). 일행에게만 의미 있음
        if (event.sourceMemberId == simulatorGateway.telemetryFlow.value.vehicleId) return
        // Restore previous alert override if a new alert supersedes an unclosed alert
        if (alertAmbientOverridden) {
            alertAmbientOverridden = false
            simulatorGateway.restoreAmbientState()
        }

        val uiModel = event.toUiModel()
        activeAlertFlow.value = uiModel

        if (voiceSessionManager.state.value.roomState != VoiceRoomState.CONNECTED_DUCKED) {
            simulatorGateway.setAmbientAlertState(uiModel.ambientColor)
            alertAmbientOverridden = true
        }

        simulatorGateway.speakBriefing(uiModel.ttsScriptKo) {
            if (activeAlertFlow.value?.eventId == uiModel.eventId) {
                simulatorGateway.startVoiceConfirmation { recognizedDecision ->
                    onAlertDecisionSelected(recognizedDecision)
                }
            }
        }
        // 마이크는 TTS가 끝난 뒤에만 연다: 브리핑의 "경유·연결"을 스스로 듣고 자동 수락하는 것 방지.
        // Gleo 응답은 observeGlobalGleoCommands 가 따로 처리하므로 미리 열 필요 없음.

        countdownCoordinator.startCountdown(
            eventId = uiModel.eventId,
            timeoutSeconds = uiModel.timeoutSeconds
        ) { safeDefaultDecision ->
            resolveAlertInternal(uiModel, safeDefaultDecision, triggeredByTimeout = true)
        }
    }

    fun onAlertDecisionSelected(decision: VoiceDecision) {
        val currentAlert = activeAlertFlow.value ?: return
        viewModelScope.launch {
            resolveAlertInternal(currentAlert, decision, triggeredByTimeout = false)
        }
    }

    private suspend fun resolveAlertInternal(
        alert: GroupAlertUiModel,
        decision: VoiceDecision,
        triggeredByTimeout: Boolean
    ) {
        if (activeAlertFlow.value?.eventId != alert.eventId) return
        dismissActiveAlertCleanupOnly()

        if (alert.type == GroupAlertType.VOICE_INVITE) {
            val direct = pendingDirectInvite
            pendingDirectInvite = null
            // 서버 알림이 아니므로 ALERT_DECISION 미전송. 수락=같은 1:1 방 입장, 그 외=거절
            if (decision == VoiceDecision.ACCEPT_AND_CALL) {
                if (connectVoice(alert.voiceSession.roomName)) statusBannerFlow.value = "${alert.sourceDisplayName} 와 통화 연결됨"
            } else {
                // 그룹 통화는 다른 사람이 받을 수 있으니 1:1만 거절 신호
                direct?.takeIf { it.roomName == alert.voiceSession.roomName }?.let {
                    wsClient.sendVoiceInvite(it.fromMemberId, it.roomName, decline = true)
                }
                statusBannerFlow.value = if (triggeredByTimeout) "${alert.sourceDisplayName} 통화 요청 · 응답 없음" else "통화 요청 거절"
            }
            return
        }

        wsClient.sendAlertDecision(alert.eventId, decision)

        when (decision) {
            VoiceDecision.ACCEPT_AND_CALL -> {
                alert.recommendedWaypoint?.let { wp ->
                    simulatorGateway.updateNavigationWaypoint(wp.lat, wp.lng, wp.name)
                }
                connectVoice(alert.voiceSession.roomName)
                statusBannerFlow.value = buildString {
                    append("경유지 반영 완료")
                    alert.recommendedWaypoint?.let { append(" (${it.name})") }
                    append(" · 그룹 보이스톡 연결됨 (미디어 30% 더킹)")
                }
            }
            VoiceDecision.ACCEPT_ROUTE_ONLY -> {
                alert.recommendedWaypoint?.let { wp ->
                    simulatorGateway.updateNavigationWaypoint(wp.lat, wp.lng, wp.name)
                }
                statusBannerFlow.value = "네비게이션 경유지 반영 완료 (${alert.recommendedWaypoint?.name ?: "신규 경로"})"
            }
            VoiceDecision.KEEP_ROUTE -> {
                statusBannerFlow.value = if (triggeredByTimeout) {
                    "8초 무응답 안전 복귀 · 기존 경로를 그대로 유지합니다."
                } else {
                    "기존 경로 유지 선택됨 · 그룹에 상태가 공유되었습니다."
                }
            }
        }
    }

    private suspend fun dismissActiveAlertCleanupOnly() {
        countdownCoordinator.cancel()
        simulatorGateway.stopVoiceConfirmation()
        activeAlertFlow.value = null
        if (alertAmbientOverridden) {
            alertAmbientOverridden = false
            simulatorGateway.restoreAmbientState()
        }
    }

    /** 상세 화면 "그룹 통화": 그룹 전체 방(서버 규칙 ROOM-<초대코드>)에 참여 */
    fun startGroupCall() {
        val room = "ROOM-${uiState.value.inviteCode}"
        val me = simulatorGateway.telemetryFlow.value.vehicleId
        // 이미 누가 통화 중이면 조용히 합류, 아무도 없으면 전원 호출
        val someoneInCall = wsClient.sessionSnapshot.value?.members?.any { it.inVoiceCall && it.memberId != me } == true
        viewModelScope.launch {
            if (connectVoice(room)) {
                if (!someoneInCall) wsClient.sendVoiceInvite("*", room)
                statusBannerFlow.value = "그룹 통화 연결됨"
            }
        }
    }

    /** 일행 카드 "통화": 두 차량만의 방에 먼저 들어가고, 상대에게 VOICE_INVITE로 수신 알림을 띄운다 */
    fun startDirectCall(memberId: String) {
        val me = simulatorGateway.telemetryFlow.value.vehicleId
        val pair = listOf(me, memberId).sorted().joinToString("-")
        val name = uiState.value.companions.firstOrNull { it.memberId == memberId }?.displayName ?: memberId
        val room = "ROOM-${uiState.value.inviteCode}-$pair"
        viewModelScope.launch {
            if (connectVoice(room)) {
                wsClient.sendVoiceInvite(memberId, room)
                statusBannerFlow.value = "$name 호출 중…"
            }
        }
    }

    private fun joinVoiceRoom(roomName: String, banner: String) {
        viewModelScope.launch {
            if (connectVoice(roomName)) statusBannerFlow.value = banner
        }
    }

    /**
     * 서버에서 이 방 토큰을 받아 LiveKit 연결. URL은 서버 값, localhost면 차량에서 닿는 기본값.
     * 실패 시 false + 배너 안내.
     */
    private suspend fun connectVoice(roomName: String): Boolean {
        val me = simulatorGateway.telemetryFlow.value
        val (serverUrl, token) = wsClient.fetchVoiceToken(roomName, me.vehicleId, me.displayName) ?: run {
            statusBannerFlow.value = "통화 서버에 연결할 수 없습니다"
            return false
        }
        // Cloud 등 공개 주소는 그대로, localhost(로컬 docker)는 에뮬에서 닿는 기본값으로
        val livekitUrl = serverUrl.takeUnless { it.isBlank() || "localhost" in it || "127.0.0.1" in it }
            ?: BuildConfig.DEFAULT_LIVEKIT_URL
        val connected = voiceSessionManager.startVoiceSession(
            voiceSession = VoiceSessionPayload(
                roomName = roomName,
                livekitUrl = livekitUrl,
                participantTokens = mapOf(me.vehicleId to token)
            ),
            participantId = me.vehicleId
        )
        if (connected) wsClient.sendVoiceStateChange(inVoiceCall = true)
        else statusBannerFlow.value = "통화 연결 실패"
        return connected
    }

    fun hangUpVoiceCall() {
        viewModelScope.launch {
            voiceSessionManager.endVoiceSession()
            wsClient.sendVoiceStateChange(inVoiceCall = false)
            statusBannerFlow.value = "그룹 보이스톡 종료 · 미디어 볼륨 및 앰비언트 라이트 복원 완료"
        }
    }

    fun leaveVoiceCall() = hangUpVoiceCall()

    fun toggleMicMute() {
        voiceSessionManager.toggleMicrophoneMute()
    }

    fun toggleVoiceMute() = toggleMicMute()

    // Emulator Developer Quick Simulation Controls
    fun selectStationaryParkPreset() {
        simulatorGateway.applyStationaryParkPreset()
        wsClient.pushImmediateTelemetry()
    }

    fun selectUrbanDrivingPreset() {
        simulatorGateway.applyUrbanDrivingPreset()
        wsClient.pushImmediateTelemetry()
    }

    fun selectHighwayFocusPreset() {
        simulatorGateway.applyHighwayHighFocusPreset()
        wsClient.pushImmediateTelemetry()
    }

    fun toggleBrakingSimulation() {
        val next = !simulatorGateway.telemetryFlow.value.isBraking
        simulatorGateway.setBraking(next)
        wsClient.pushImmediateTelemetry()
    }

    fun stepSocPercent(delta: Double) {
        val current = simulatorGateway.telemetryFlow.value.socPercent ?: 80.0
        simulatorGateway.setSocPercent(current + delta)
        wsClient.pushImmediateTelemetry()
    }

    fun triggerDemoScenarioADeviation() {
        viewModelScope.launch {
            presentIncomingAlert(DemoGroupSeed.scenarioADeviationAlert(wsClient.groupId.value))
        }
    }

    fun triggerDemoScenarioBProposal() {
        viewModelScope.launch {
            presentIncomingAlert(DemoGroupSeed.scenarioBNavigatorProposalAlert(wsClient.groupId.value))
        }
    }

    fun showNavigationScreen(): Boolean = nativeGateway.showNavigationScreen()

    fun reconnectWebSocket() {
        wsClient.connect()
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
        nativeGateway.shutdown()
    }
}
