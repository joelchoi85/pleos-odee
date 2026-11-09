package com.hyundai.pleos.syncdrive.ui.map

import com.hyundai.pleos.syncdrive.domain.GeoPoint
import kotlin.math.cos

/** 차량 위치가 전체 경로의 몇 % 지점인지 (가장 가까운 꼭짓점 기준 누적 거리 비율, 0..1). */
fun progressAlongRoute(route: List<GeoPoint>, p: GeoPoint): Float {
    if (route.size < 2) return 0f
    val cumulative = DoubleArray(route.size)
    for (i in 1 until route.size) cumulative[i] = cumulative[i - 1] + flatDistance(route[i - 1], route[i])
    val total = cumulative.last()
    if (total <= 0.0) return 0f
    val nearest = route.indices.minBy { flatDistance(route[it], p) }
    return (cumulative[nearest] / total).toFloat().coerceIn(0f, 1f)
}

private fun flatDistance(a: GeoPoint, b: GeoPoint): Double {
    val dx = (a.lng - b.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val dy = a.lat - b.lat
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
