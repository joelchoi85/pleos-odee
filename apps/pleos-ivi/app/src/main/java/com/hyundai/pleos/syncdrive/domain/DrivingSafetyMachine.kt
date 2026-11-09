package com.hyundai.pleos.syncdrive.domain

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
enum class DrivingSafetyLevel {
    STATIONARY,
    NORMAL_DRIVING,
    HIGH_FOCUS
}

@Serializable
enum class GearState {
    P,
    R,
    N,
    D
}

@Serializable
enum class PowertrainType {
    EV,
    ICE,
    HEV
}

@Serializable
enum class MemberRole {
    PLEOS_HOST,
    PLEOS_COMPANION,
    MOBILE_DRIVER,
    MOBILE_NAVIGATOR
}

@Serializable
enum class AmbientAlertColor {
    NONE,
    AMBER_DEVIATION,
    CYAN_PROPOSAL,
    GREEN_VOICE_ACTIVE
}

@Serializable
enum class VoiceDecision {
    ACCEPT_AND_CALL,
    ACCEPT_ROUTE_ONLY,
    KEEP_ROUTE
}

/**
 * Pleos Vehicle SDK (Speed / Gear / Brake) 기반 적응형 주행 안전 등급 산출.
 * packages/shared-types/src/protocol.ts 의 deriveDrivingSafetyLevel 규칙과 1:1 일치합니다.
 */
fun deriveDrivingSafetyLevel(
    speedKmh: Double,
    gear: GearState,
    isBraking: Boolean,
    isUxRestricted: Boolean = false
): DrivingSafetyLevel {
    // 플랫폼이 주행 규제를 걸면 속도·기어와 무관하게 정차 UI 금지
    if (speedKmh <= 1.0 && gear == GearState.P && !isUxRestricted) {
        return DrivingSafetyLevel.STATIONARY
    }
    if (speedKmh >= 60.0 || isBraking) {
        return DrivingSafetyLevel.HIGH_FOCUS
    }
    return DrivingSafetyLevel.NORMAL_DRIVING
}

const val MIN_TELEMETRY_INTERVAL_MS = 1_000L
const val MAX_TELEMETRY_INTERVAL_MS = 10_000L

/**
 * 서버 TELEMETRY_ACK.nextIntervalMs 값을 안전 범위(1000ms ~ 10000ms)로 보정합니다.
 */
fun clampTelemetryIntervalMs(intervalMs: Long): Long {
    return intervalMs.coerceIn(MIN_TELEMETRY_INTERVAL_MS, MAX_TELEMETRY_INTERVAL_MS)
}

/**
 * 서버 응답 전 또는 오프라인 상태에서의 주행 안전 등급 기반 기본 텔레메트리 전송 주기.
 */
fun deriveDefaultTelemetryIntervalMs(
    safetyLevel: DrivingSafetyLevel,
    isOffRoute: Boolean = false
): Long {
    if (isOffRoute) return MIN_TELEMETRY_INTERVAL_MS
    return when (safetyLevel) {
        DrivingSafetyLevel.STATIONARY -> 10_000L
        DrivingSafetyLevel.NORMAL_DRIVING -> 5_000L
        DrivingSafetyLevel.HIGH_FOCUS -> 2_000L
    }
}

/**
 * 급제동(Hard Braking) 감지 판정:
 * - isBraking == true 상태이면서,
 * - P단 정차나 0~1.0 km/h 신호 대기가 아닐 때,
 * - 속도가 30 km/h 이상이거나, 직전 속도 대비 급격한 감속(>= 15 km/h)이거나, 주행 중(> 5.0 km/h) 제동 시 true.
 */
fun checkIsHardBraking(
    isBraking: Boolean,
    currentSpeedKmh: Double,
    previousSpeedKmh: Double = currentSpeedKmh,
    gear: GearState = GearState.D
): Boolean {
    if (!isBraking || gear == GearState.P || currentSpeedKmh <= 1.0) {
        return false
    }
    val speedDrop = previousSpeedKmh - currentSpeedKmh
    return currentSpeedKmh >= 30.0 || speedDrop >= 15.0 || currentSpeedKmh > 5.0
}

/**
 * 두 위경도 좌표 간의 구면 거리(미터) 산출 (Haversine Formula).
 */
fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

/**
 * 정차 중(0km/h, 신호 대기) 스마트 대열 1줄 브리핑(Stationary Context Briefing) 생성.
 *
 * - 일행 전원 정상 주행 시: "🚦 신호 대기 중 · 일행 전원(N대) 정상 주행 중 · 2호차가 후방 400m에 있습니다"
 * - 이탈/경유 차량 발생 시: "🚦 신호 대기 중 · 2호차 충전소 경유 중 (배터리 18%) · 3호차 정상 주행"
 */
fun generateStationaryBriefing(
    local: LocalVehicleSnapshot,
    companions: List<CompanionVehicleUiModel>
): String {
    val vehicles = companions.filter { it.role != MemberRole.MOBILE_NAVIGATOR }
    val otherVehicles = vehicles.filterNot { it.isSelfHost }
    val totalVehicleCount = vehicles.size.coerceAtLeast(1)

    if (otherVehicles.isEmpty()) {
        return "🚦 신호 대기 중 · 목적지까지 안전 운행하세요"
    }

    fun formatVehicleLabel(v: CompanionVehicleUiModel): String {
        val carNumberMatch = Regex("""\d+호차""").find(v.displayName)?.value
        if (carNumberMatch != null) return carNumberMatch
        return v.displayName.substringBefore(" (").trim()
    }

    val abnormalVehicles = otherVehicles.filter { it.isOffRoute || it.energyPercent <= 20 }

    return if (abnormalVehicles.isNotEmpty()) {
        val abnormalSummaries = abnormalVehicles.map { v ->
            val label = formatVehicleLabel(v)
            if (v.energyPercent <= 20) {
                "$label 충전소 경유 중 (배터리 ${v.energyPercent}%)"
            } else {
                "$label 경로 이탈 (${v.crossTrackDistanceMeters}m)"
            }
        }
        val normalVehicles = otherVehicles.filterNot { it.isOffRoute || it.energyPercent <= 20 }
        val normalSummary = if (normalVehicles.isNotEmpty()) {
            val normalLabels = normalVehicles.joinToString(", ") { formatVehicleLabel(it) }
            "$normalLabels 정상 주행"
        } else null

        val parts = listOfNotNull("🚦 신호 대기 중", abnormalSummaries.joinToString(" · "), normalSummary)
        parts.joinToString(" · ")
    } else {
        val firstOther = otherVehicles.first()
        val firstOtherLabel = formatVehicleLabel(firstOther)

        val distanceText = if (local.lat != 0.0 && local.lng != 0.0 &&
            firstOther.lat != null && firstOther.lng != null &&
            (firstOther.lat != 0.0 || firstOther.lng != 0.0)
        ) {
            val d = calculateDistanceMeters(local.lat, local.lng, firstOther.lat, firstOther.lng)
            val rounded = ((d / 10.0).roundToInt() * 10).coerceAtLeast(50)
            "${rounded}m"
        } else {
            "400m"
        }

        "🚦 신호 대기 중 · 일행 전원(${totalVehicleCount}대) 정상 주행 중 · ${firstOtherLabel}가 후방 ${distanceText}에 있습니다"
    }
}

