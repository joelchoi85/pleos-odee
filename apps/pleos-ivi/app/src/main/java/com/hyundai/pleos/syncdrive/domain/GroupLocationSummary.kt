package com.hyundai.pleos.syncdrive.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/** 경로 출발점부터 p까지 경로를 따라간 거리(m). 가장 가까운 꼭짓점 기준 */
// ponytail: 꼭짓점 스냅이라 오차 = 꼭짓점 간격. 성긴 경로면 선분 투영으로 교체
fun distanceAlongRouteMeters(route: List<GeoPoint>, p: GeoPoint): Double? {
    if (route.size < 2) return null
    var acc = 0.0
    var best = 0.0
    var bestD = Double.MAX_VALUE
    route.forEachIndexed { i, v ->
        if (i > 0) acc += calculateDistanceMeters(route[i - 1].lat, route[i - 1].lng, v.lat, v.lng)
        val d = calculateDistanceMeters(v.lat, v.lng, p.lat, p.lng)
        if (d < bestD) { bestD = d; best = acc }
    }
    return best
}

/** 음성용 차량 이름: "EV9 (2호차 · 김민준)" → "2호차 EV9". 괄호는 TTS가 어색하게 읽음 */
fun voiceVehicleLabel(displayName: String): String {
    val model = displayName.substringBefore(" (").trim()
    val carNo = Regex("""\d+호차""").find(displayName)?.value
    return if (carNo != null && carNo != model) "$carNo $model" else model
}

private fun formatGap(meters: Double): String =
    if (meters < 1000) "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)}m"
    else "${(meters / 1000).roundToInt()}km"

/**
 * Gleo "일행 어디쯤?" 음성 응답. 그룹 공통 경로를 따라 우리 차 대비 앞/뒤 거리 + 도착 예정.
 * 예: "2호차 EV9 32km 앞, 14:45 도착. 3호차 싼타페 HEV 5km 뒤, 경로 이탈 중."
 * 경로·좌표가 없으면 거리는 빼고 도착 예정만 (지어내지 않음).
 */
fun buildGroupLocationSummary(
    local: LocalVehicleSnapshot,
    companions: List<CompanionVehicleUiModel>,
    route: List<GeoPoint>
): String {
    // 동승 네비게이터는 같은 차에 타고 있으므로 제외
    val others = companions.filter { !it.isSelfHost && it.role != MemberRole.MOBILE_NAVIGATOR }
    if (others.isEmpty()) return "아직 연결된 일행이 없습니다."

    val hasLocal = local.lat != 0.0 || local.lng != 0.0
    val mine = if (hasLocal) distanceAlongRouteMeters(route, GeoPoint(local.lat, local.lng)) else null

    return others.joinToString(" ") { c ->
        val theirs = if (c.lat != null && c.lng != null && (c.lat != 0.0 || c.lng != 0.0))
            distanceAlongRouteMeters(route, GeoPoint(c.lat, c.lng)) else null
        val gap = if (mine != null && theirs != null) {
            val diff = theirs - mine
            when {
                abs(diff) < 300 -> "바로 근처"
                diff > 0 -> "${formatGap(diff)} 앞"
                else -> "${formatGap(-diff)} 뒤"
            }
        } else null
        val status = if (c.isOffRoute) "경로 이탈 중" else c.etaDisplay
        listOfNotNull(voiceVehicleLabel(c.displayName), gap?.let { "$it," } ?: "", status)
            .filter { it.isNotEmpty() }.joinToString(" ") + "."
    }
}
