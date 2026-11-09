package com.hyundai.pleos.syncdrive.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 호스트 Pleos Connect IVI 차량의 로컬 실시간 센서 스냅샷.
 */
data class LocalVehicleSnapshot(
    val vehicleId: String = "HYUNDAI-IONIQ5-01",
    val displayName: String = "아이오닉 6 (1호차 · 호스트)",
    val role: MemberRole = MemberRole.PLEOS_HOST,
    val lat: Double = 37.3947,
    val lng: Double = 127.1112,
    val heading: Double = 98.0,
    val speedKmh: Double = 0.0,
    val powertrain: PowertrainType = PowertrainType.EV,
    val socPercent: Double? = 82.0,
    val fuelPercent: Double? = null,
    val remainingRangeKm: Double? = 418.0,
    val gear: GearState = GearState.P,
    val isBraking: Boolean = false,
    /** 플랫폼 CarUxRestrictions: 주의분산 최적화 요구 여부 */
    val isUxRestricted: Boolean = false,
    val activeRouteId: String = "route-gangneung-express",
    // ponytail: 데모용 고정 ETA(앱 시작 +25분, 시뮬레이터 1호차와 같음). 실차는 NaviHelper 잔여 시간으로 갱신
    val etaIso: String = java.time.Instant.now().plusSeconds(1500).toString()
) {
    val safetyLevel: DrivingSafetyLevel
        get() = deriveDrivingSafetyLevel(
            speedKmh = speedKmh,
            gear = gear,
            isBraking = isBraking,
            isUxRestricted = isUxRestricted
        )

    fun toTelemetryPayload(groupId: String, timestampMs: Long = System.currentTimeMillis()): VehicleTelemetryPayload {
        return VehicleTelemetryPayload(
            groupId = groupId,
            vehicleId = vehicleId,
            displayName = displayName,
            role = role,
            timestampMs = timestampMs,
            location = LocationTelemetry(
                lat = lat,
                lng = lng,
                heading = heading,
                speedKmh = speedKmh
            ),
            vehicleState = VehicleStateTelemetry(
                powertrain = powertrain,
                socPercent = socPercent,
                fuelPercent = fuelPercent,
                remainingRangeKm = remainingRangeKm,
                gear = gear,
                isBraking = isBraking
            ),
            routeState = RouteStateTelemetry(
                activeRouteId = activeRouteId,
                etaIso = etaIso
            )
        )
    }
}

/**
 * 그룹 주행 참여 차량/동승자(2~4대) 대시보드 카드 표시용 UI 모델.
 */
data class CompanionVehicleUiModel(
    val memberId: String,
    val displayName: String,
    val role: MemberRole,
    val powertrain: PowertrainType,
    val energyPercent: Int,
    val remainingRangeKm: Int?,
    val speedKmh: Int,
    val gear: GearState,
    val etaDisplay: String,
    val isOffRoute: Boolean,
    val crossTrackDistanceMeters: Int,
    val inVoiceCall: Boolean,
    val isSelfHost: Boolean,
    val lat: Double? = null,
    val lng: Double? = null
) {
    val energyLabel: String
        get() = when (powertrain) {
            PowertrainType.EV -> "SOC $energyPercent%"
            PowertrainType.HEV -> "연료 $energyPercent%"
            PowertrainType.ICE -> "연료 $energyPercent%"
        }

    val roleLabelKo: String
        get() = when (role) {
            MemberRole.PLEOS_HOST -> "Pleos 리더"
            MemberRole.PLEOS_COMPANION -> "Pleos 동행차"
            MemberRole.MOBILE_DRIVER -> "모바일 운전자"
            MemberRole.MOBILE_NAVIGATOR -> "동승석 네비게이터"
        }
}

/**
 * 주행 안전 적응형 우측 1/3 오버레이 표시용 그룹 알림 UI 모델.
 */
data class GroupAlertUiModel(
    val eventId: String,
    val groupId: String,
    val createdAtMs: Long,
    val type: GroupAlertType,
    val category: DeviationCategory?,
    val sourceMemberId: String,
    val sourceDisplayName: String,
    val etaDeltaMinutes: Int,
    val recommendedWaypoint: RecommendedWaypoint?,
    val ambientColor: AmbientAlertColor,
    val headlineKo: String,
    val detailKo: String,
    val ttsScriptKo: String,
    val voiceSession: VoiceSessionPayload,
    val timeoutSeconds: Int
) {
    val isTimeSaving: Boolean
        get() = etaDeltaMinutes < 0

    val isCallInvite: Boolean
        get() = type == GroupAlertType.VOICE_INVITE

    val formattedEtaBadgeKo: String
        get() = when {
            isCallInvite -> "통화 요청"
            etaDeltaMinutes < 0 -> "${abs(etaDeltaMinutes)}분 단축"
            etaDeltaMinutes > 0 -> "+${etaDeltaMinutes}분 예상"
            else -> "도착 시간 동일"
        }

    val categoryBadgeKo: String
        get() = when (type) {
            GroupAlertType.NAVIGATOR_ROUTE_PROPOSAL -> "동승석 네비게이터 우회 제안"
            GroupAlertType.PROACTIVE_PITSTOP -> "AI 선제 충전/주유 경유 제안"
            GroupAlertType.VOICE_INVITE -> "일행 1:1 통화"
            GroupAlertType.VEHICLE_DEVIATION -> when (category) {
                DeviationCategory.INTENTIONAL_PITSTOP_CHARGE_FUEL -> "동행 차량 충전/주유 경유 감지"
                DeviationCategory.INTENTIONAL_PITSTOP_REST -> "동행 차량 휴게소 진입 감지"
                DeviationCategory.ACCIDENTAL_MISSED_EXIT -> "동행 차량 경로 이탈 감지"
                DeviationCategory.PROACTIVE_LOW_ENERGY_WARNING -> "배터리/연료 부족 경유 알림"
                null -> "그룹 경로 변경 이벤트"
            }
        }
}

fun GroupAlertEvent.toUiModel(): GroupAlertUiModel = GroupAlertUiModel(
    eventId = eventId,
    groupId = groupId,
    createdAtMs = createdAtMs,
    type = type,
    category = category,
    sourceMemberId = sourceMemberId,
    sourceDisplayName = sourceDisplayName,
    etaDeltaMinutes = etaDeltaMinutes.roundToInt(),
    recommendedWaypoint = recommendedWaypoint,
    ambientColor = ambientColor,
    headlineKo = aiBriefing.headlineKo,
    detailKo = aiBriefing.detailKo,
    ttsScriptKo = aiBriefing.ttsScriptKo,
    voiceSession = voiceSession,
    timeoutSeconds = timeoutSeconds.coerceAtLeast(1)
)

/** 1:1 통화 호출을 기존 알림 흐름(전면 표시·TTS·음성 수락·타임아웃)에 태우기 위한 로컬 알림 */
fun ServerWsEvent.VoiceInvite.toIncomingCallAlert(groupId: String): GroupAlertEvent = GroupAlertEvent(
    eventId = "call-$roomName-${System.currentTimeMillis()}",
    groupId = groupId,
    createdAtMs = System.currentTimeMillis(),
    type = GroupAlertType.VOICE_INVITE,
    sourceMemberId = fromMemberId,
    sourceDisplayName = fromDisplayName,
    etaDeltaMinutes = 0.0,
    ambientColor = AmbientAlertColor.GREEN_VOICE_ACTIVE,
    aiBriefing = if (toMemberId == "*") AiBriefingPayload(
        headlineKo = "$fromDisplayName 그룹 통화 시작",
        detailKo = "참여하려면 \"연결\"이라고 말하거나 버튼을 누르세요",
        ttsScriptKo = "${fromDisplayName}이 그룹 통화를 시작했습니다. 참여할까요?"
    ) else AiBriefingPayload(
        headlineKo = "$fromDisplayName 통화 요청",
        detailKo = "받으려면 \"연결\"이라고 말하거나 버튼을 누르세요",
        ttsScriptKo = "${fromDisplayName}이 통화를 요청합니다. 연결할까요?"
    ),
    voiceSession = VoiceSessionPayload(roomName = roomName, livekitUrl = ""),
    timeoutSeconds = 15
)

fun GroupMemberSnapshot.toCompanionUiModel(selfVehicleId: String): CompanionVehicleUiModel {
    val telemetry = lastTelemetry
    val powertrain = telemetry?.vehicleState?.powertrain ?: PowertrainType.EV
    val energy = (telemetry?.vehicleState?.socPercent ?: telemetry?.vehicleState?.fuelPercent ?: 76.0).roundToInt()
    val range = telemetry?.vehicleState?.remainingRangeKm?.roundToInt()
    val speed = (telemetry?.location?.speedKmh ?: 0.0).roundToInt()
    val gear = telemetry?.vehicleState?.gear ?: GearState.P
    val rawEta = telemetry?.routeState?.etaIso.orEmpty()
    val formattedEta = formatIsoTimeForHud(rawEta)

    return CompanionVehicleUiModel(
        memberId = memberId,
        displayName = displayName,
        role = role,
        powertrain = powertrain,
        energyPercent = energy,
        remainingRangeKm = range,
        speedKmh = speed,
        gear = gear,
        etaDisplay = formattedEta,
        isOffRoute = isOffRoute,
        crossTrackDistanceMeters = crossTrackDistanceMeters.roundToInt(),
        inVoiceCall = inVoiceCall,
        isSelfHost = memberId == selfVehicleId,
        lat = telemetry?.location?.lat,
        lng = telemetry?.location?.lng
    )
}

private fun formatIsoTimeForHud(etaIso: String): String =
    formatEtaClock(etaIso)?.let { "$it 도착" } ?: "도착 시간 미정"

/** ISO-8601(UTC "Z" 포함) → 차량 로컬 시각 "HH:mm". 파싱 실패 시 null */
fun formatEtaClock(etaIso: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? =
    runCatching {
        java.time.OffsetDateTime.parse(etaIso).atZoneSameInstant(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()

/**
 * 초기 정차 대시보드 및 오프라인 에뮬레이터 시연을 위한 기본 4대 그룹 시드 데이터.
 */
object DemoGroupSeed {
    const val DEFAULT_GROUP_ID = "GRP-PLEOS-DEMO"
    const val DEFAULT_INVITE_CODE = "842-910"

    fun defaultCompanions(localSnapshot: LocalVehicleSnapshot): List<CompanionVehicleUiModel> = listOf(
        CompanionVehicleUiModel(
            memberId = localSnapshot.vehicleId,
            displayName = localSnapshot.displayName,
            role = MemberRole.PLEOS_HOST,
            powertrain = localSnapshot.powertrain,
            energyPercent = (localSnapshot.socPercent ?: 82.0).roundToInt(),
            remainingRangeKm = localSnapshot.remainingRangeKm?.roundToInt() ?: 418,
            speedKmh = localSnapshot.speedKmh.roundToInt(),
            gear = localSnapshot.gear,
            etaDisplay = formatIsoTimeForHud(localSnapshot.etaIso),
            isOffRoute = false,
            crossTrackDistanceMeters = 0,
            inVoiceCall = false,
            isSelfHost = true
        ),
        CompanionVehicleUiModel(
            memberId = "veh-ev9-02",
            displayName = "EV9 (2호차 · 김민준)",
            role = MemberRole.PLEOS_COMPANION,
            powertrain = PowertrainType.EV,
            energyPercent = 18,
            remainingRangeKm = 68,
            speedKmh = 84,
            gear = GearState.D,
            etaDisplay = "14:45 도착",
            isOffRoute = false,
            crossTrackDistanceMeters = 14,
            inVoiceCall = false,
            isSelfHost = false
        ),
        CompanionVehicleUiModel(
            memberId = "veh-santafe-03",
            displayName = "싼타페 HEV (3호차 · 박서연)",
            role = MemberRole.MOBILE_DRIVER,
            powertrain = PowertrainType.HEV,
            energyPercent = 69,
            remainingRangeKm = 520,
            speedKmh = 86,
            gear = GearState.D,
            etaDisplay = "14:41 도착",
            isOffRoute = false,
            crossTrackDistanceMeters = 9,
            inVoiceCall = false,
            isSelfHost = false
        ),
        CompanionVehicleUiModel(
            memberId = "nav-companion-04",
            displayName = "이지훈 (3호차 동승 네비게이터)",
            role = MemberRole.MOBILE_NAVIGATOR,
            powertrain = PowertrainType.HEV,
            energyPercent = 69,
            remainingRangeKm = 520,
            speedKmh = 86,
            gear = GearState.D,
            etaDisplay = "우회 탐색 중",
            isOffRoute = false,
            crossTrackDistanceMeters = 0,
            inVoiceCall = false,
            isSelfHost = false
        )
    )

    fun scenarioADeviationAlert(groupId: String = DEFAULT_GROUP_ID): GroupAlertEvent = GroupAlertEvent(
        eventId = "evt-sim-deviation-${System.currentTimeMillis()}",
        groupId = groupId,
        createdAtMs = System.currentTimeMillis(),
        type = GroupAlertType.VEHICLE_DEVIATION,
        sourceMemberId = "veh-ev9-02",
        sourceDisplayName = "2호차 EV9 (김민준)",
        category = DeviationCategory.INTENTIONAL_PITSTOP_CHARGE_FUEL,
        etaDeltaMinutes = 5.0,
        recommendedWaypoint = RecommendedWaypoint(
            name = "평창휴게소 E-pit 초급속 충전소",
            lat = 37.5718,
            lng = 128.4190,
            poiCategory = PoiCategory.EV_CHARGER
        ),
        ambientColor = AmbientAlertColor.AMBER_DEVIATION,
        aiBriefing = AiBriefingPayload(
            headlineKo = "2호차 충전소 진입 감지 (SOC 18%)",
            detailKo = "평창휴게소 E-pit 동반 경유 시 +5분 소요 · 원터치 보이스 연결 가능",
            ttsScriptKo = "2호차 EV9이 배터리 부족으로 평창휴게소 충전소에 진입했습니다. 함께 경유하고 보이스톡을 연결할까요?"
        ),
        voiceSession = VoiceSessionPayload(
            roomName = "pleos-room-$groupId",
            livekitUrl = "ws://10.0.2.2:7880",
            participantTokens = mapOf("HYUNDAI-IONIQ5-01" to "demo-livekit-jwt-host"),
            autoConnectOnAccept = true
        ),
        timeoutSeconds = 8
    )

    fun scenarioBNavigatorProposalAlert(groupId: String = DEFAULT_GROUP_ID): GroupAlertEvent = GroupAlertEvent(
        eventId = "evt-sim-proposal-${System.currentTimeMillis()}",
        groupId = groupId,
        createdAtMs = System.currentTimeMillis(),
        type = GroupAlertType.NAVIGATOR_ROUTE_PROPOSAL,
        sourceMemberId = "nav-companion-04",
        sourceDisplayName = "3호차 동승석 이지훈",
        category = null,
        etaDeltaMinutes = -8.0,
        recommendedWaypoint = RecommendedWaypoint(
            name = "둔내IC 국도 6호선 정체 우회로",
            lat = 37.5121,
            lng = 128.2154,
            poiCategory = PoiCategory.ETC
        ),
        ambientColor = AmbientAlertColor.CYAN_PROPOSAL,
        aiBriefing = AiBriefingPayload(
            headlineKo = "동승석 정체 우회 제안 · 8분 단축",
            detailKo = "영동고속도로 사고 정체 우회 (둔내IC 경유) · 그룹 전체 ETA 8분 단축 검증 완료",
            ttsScriptKo = "3호차 동승석에서 전방 정체 우회 경로를 제안했습니다. 도착 시간이 8분 단축됩니다. 경로를 반영하고 통화를 연결할까요?"
        ),
        voiceSession = VoiceSessionPayload(
            roomName = "pleos-room-$groupId",
            livekitUrl = "ws://10.0.2.2:7880",
            participantTokens = mapOf("HYUNDAI-IONIQ5-01" to "demo-livekit-jwt-host"),
            autoConnectOnAccept = true
        ),
        timeoutSeconds = 8
    )
}
